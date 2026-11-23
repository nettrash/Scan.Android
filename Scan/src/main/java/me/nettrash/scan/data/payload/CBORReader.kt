package me.nettrash.scan.data.payload

/**
 * Minimal RFC 8949 (CBOR) reader — just enough to walk the small,
 * definite-length maps that ride inside the codes this app understands.
 * Shared by the CTAP hybrid-transport passkey QR and the ISO/IEC 18013-5
 * identity codes; it used to live privately inside [PasskeyParser] until
 * the mDL parser needed it too (all 1.10).
 *
 * Deliberately incomplete: indefinite-length items are banned in both
 * CTAP2 canonical CBOR and the mdoc profiles, so encountering one aborts
 * the parse rather than guessing. Floats, tags and negative integers are
 * consumed structurally and reported as [Value.Other] — no caller needs
 * them.
 *
 * Mirrors the iOS `CBORReader` struct, with one addition: containers may
 * nest at most [MAX_DEPTH] deep. The input is a scanned code — whoever
 * printed it chose every byte — and an array-of-array-of-… payload
 * recurses once per byte: a few thousand frames, enough to overflow a
 * worker thread's stack and take the app down. No structure we decode
 * nests more than half a dozen levels, so the cap costs nothing real.
 */
class CBORReader(private val bytes: ByteArray) {

    private var i = 0

    /**
     * True while unread input remains — lets a caller distinguish
     * "parsed to the end" from "stopped early".
     */
    val hasMore: Boolean get() = i < bytes.size

    sealed class Value {
        /**
         * Major type 0. A [ULong] like the iOS `UInt64`, so a caller's
         * range check (`<= Int.MAX_VALUE`, `< 2^40`) sees the real value:
         * a signed `Long` would turn anything from 2^63 up into a negative
         * number that sails through an upper-bound test.
         */
        data class Uint(val v: ULong) : Value()
        data class Bytes(val v: ByteArray) : Value() {
            override fun equals(other: Any?) = other is Bytes && v.contentEquals(other.v)
            override fun hashCode() = v.contentHashCode()
        }
        data class Text(val v: String) : Value()
        data class Bool(val v: Boolean) : Value()
        data class Array(val v: List<Value>) : Value()
        data class Map(val v: List<CBORPair>) : Value()
        object Other : Value()
    }

    /**
     * (major type, argument) or null on truncation / indefinite length.
     * [arg] holds the raw 64-bit argument in a signed `Long`: from 2^63 up
     * it reads as negative, which every length / count check below
     * rejects, and [readValue] widens it back to an unsigned [Value.Uint].
     */
    data class Header(val major: Int, val arg: Long)

    fun readHeader(): Header? {
        if (i >= bytes.size) return null
        val b = bytes[i++].toInt() and 0xFF
        val major = b shr 5
        val info = b and 0x1F
        return when {
            info < 24 -> Header(major, info.toLong())
            info <= 27 -> {
                val n = 1 shl (info - 24)
                if (i + n > bytes.size) return null
                var v = 0L
                repeat(n) { v = (v shl 8) or (bytes[i++].toLong() and 0xFF) }
                Header(major, v)
            }
            else -> null                  // 28–31: indefinite / simple — unsupported
        }
    }

    fun readValue(): Value? = readValue(depth = 0)

    private fun readValue(depth: Int): Value? {
        val h = readHeader() ?: return null
        return when (h.major) {
            0 -> Value.Uint(h.arg.toULong())
            1 -> Value.Other              // negative int — arg consumed
            2, 3 -> {
                if (h.arg < 0 || h.arg > bytes.size) return null
                val len = h.arg.toInt()
                if (i + len > bytes.size) return null
                val d = bytes.copyOfRange(i, i + len)
                i += len
                if (h.major == 2) Value.Bytes(d)
                else PayloadCodec.utf8(d)?.let { Value.Text(it) } ?: Value.Other
            }
            4, 5 -> {
                if (depth >= MAX_DEPTH) return null
                // Nested array / map — walk elementwise. An element is ≥ 1
                // byte, so a declared count larger than the remaining input
                // is malformed rather than a long loop.
                if (h.arg < 0 || h.arg > bytes.size) return null
                val count = h.arg.toInt()
                if (h.major == 4) {
                    val items = ArrayList<Value>(count)
                    repeat(count) { items += readValue(depth + 1) ?: return null }
                    Value.Array(items)
                } else {
                    val pairs = ArrayList<CBORPair>(count)
                    repeat(count) {
                        val k = readValue(depth + 1) ?: return null
                        val v = readValue(depth + 1) ?: return null
                        pairs += CBORPair(k, v)
                    }
                    Value.Map(pairs)
                }
            }
            // Tag: the tag number was consumed with the header; now the item.
            // Tags can wrap tags, so they count towards the depth too.
            6 -> if (depth >= MAX_DEPTH) null else readValue(depth + 1)?.let { Value.Other }
            7 -> when (h.arg) {
                20L -> Value.Bool(false)
                21L -> Value.Bool(true)
                else -> Value.Other       // null / undefined / float
            }
            else -> null
        }
    }

    companion object {
        /** Deepest container / tag nesting accepted — see the class comment. */
        const val MAX_DEPTH = 64
    }
}

/**
 * One key/value entry of a decoded CBOR map. A class (rather than a
 * labelled pair) so [CBORReader.Value] gets structural equality for free
 * and so the lookups below read cleanly.
 */
data class CBORPair(val key: CBORReader.Value, val value: CBORReader.Value)

/** Value for a text key, if present. */
fun List<CBORPair>.valueForKey(key: String): CBORReader.Value? =
    firstOrNull { it.key == CBORReader.Value.Text(key) }?.value

/** Value for an unsigned-integer key, if present. */
fun List<CBORPair>.valueForKey(key: ULong): CBORReader.Value? =
    firstOrNull { it.key == CBORReader.Value.Uint(key) }?.value

/**
 * Every value stored under a text key. CBOR permits duplicate keys and
 * the mdoc structures key by integer *or* name depending on profile, so
 * callers that want completeness use this instead of [valueForKey].
 */
fun List<CBORPair>.valuesForKey(key: String): List<CBORReader.Value> =
    filter { it.key == CBORReader.Value.Text(key) }.map { it.value }
