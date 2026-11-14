package me.nettrash.scan.data.payload

import java.text.DateFormat
import java.util.Date

/**
 * FIDO cross-device sign-in QR (CTAP 2.2 "hybrid transport" — the code a
 * website shows so a phone can serve as the passkey authenticator).
 *
 * Mirrors the iOS [PasskeyPayload] struct. The payload is `FIDO:/`
 * followed by a digit-encoded CBOR map: every 7 bytes become 17
 * zero-padded decimal digits (little-endian), and a trailing partial
 * chunk uses a fixed smaller digit count. We decode it to show *what the
 * code is for* — sign-in vs. create-a-passkey, when it was generated,
 * tunnel-server count — but, like the 2FA-export payload, deliberately
 * never display the embedded one-time secret. Scan cannot complete the
 * handshake itself (that flow is reserved for the system camera / Google
 * Password Manager), so the result sheet is informational.
 */
data class PasskeyPayload(
    /**
     * False when the `FIDO:/` prefix matched but the digit blob didn't
     * decode to the expected CBOR map (truncated scan, future format).
     */
    val decoded: Boolean,
    /**
     * CTAP operation hint — "ga" (sign in) / "mc" (create a passkey).
     * Other values are possible (the spec allows unknown hints).
     */
    val operationHint: String? = null,
    /** Number of assigned tunnel-server domains (CBOR key 2). */
    val tunnelDomains: Int? = null,
    /**
     * When the displaying device generated the code (CBOR key 3, epoch
     * seconds of its own clock — informational only).
     */
    val generatedAtEpochSeconds: Long? = null,
    /**
     * Whether the displaying device offers "remember this phone"
     * state-assisted transactions (CBOR key 4).
     */
    val linkable: Boolean? = null,
    val raw: String
) {
    val purposeDescription: String
        get() = when (operationHint) {
            "ga" -> "Sign in with a passkey"
            "mc" -> "Create a passkey"
            null -> "Cross-device passkey"
            else -> "Cross-device passkey ($operationHint)"
        }

    fun labelledFields(): List<LabelledField> {
        val rows = mutableListOf(
            LabelledField("Format", "FIDO cross-device sign-in"),
            LabelledField("Purpose", purposeDescription),
        )
        generatedAtEpochSeconds?.let {
            val f = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            rows += LabelledField("Generated", f.format(Date(it * 1000)))
        }
        tunnelDomains?.let { rows += LabelledField("Tunnel servers", "$it") }
        linkable?.let { rows += LabelledField("Device linking", if (it) "Offered" else "Not offered") }
        if (!decoded) {
            rows += LabelledField("Payload", "Not decodable — possibly a newer format")
        }
        return rows
    }
}

object PasskeyParser {

    /**
     * `FIDO:/` + at least one character. The QR alphanumeric mode the
     * spec mandates is uppercase, but be liberal in what we accept.
     */
    fun looksLikePasskey(s: String): Boolean =
        s.length > 6 && s.startsWith("FIDO:/", ignoreCase = true)

    fun parse(raw: String): PasskeyPayload? {
        if (!looksLikePasskey(raw)) return null
        val digits = raw.substring(6)
        // A real hybrid QR is strictly decimal digits after the prefix;
        // anything else is some other (unknown) FIDO: form → plain text.
        if (!digits.all { it in '0'..'9' }) return null

        val bytes = digitDecode(digits)
        val fields = bytes?.let { decodeCborMap(it) }
            // Recognised but undecodable — still better than raw "text".
            ?: return PasskeyPayload(decoded = false, raw = raw)

        var operationHint: String? = null
        var tunnelDomains: Int? = null
        var generatedAt: Long? = null
        var linkable: Boolean? = null
        for ((key, value) in fields) {
            when {
                key == 2L && value is CborValue.Uint && value.v <= Int.MAX_VALUE ->
                    tunnelDomains = value.v.toInt()
                key == 3L && value is CborValue.Uint && value.v < (1L shl 40) ->
                    generatedAt = value.v
                key == 4L && value is CborValue.Bool -> linkable = value.v
                key == 5L && value is CborValue.Text -> operationHint = value.v
                // Keys 0 (public key) / 1 (QR secret) / unknown — ignored.
            }
        }
        return PasskeyPayload(
            decoded = true,
            operationHint = operationHint,
            tunnelDomains = tunnelDomains,
            generatedAtEpochSeconds = generatedAt,
            linkable = linkable,
            raw = raw,
        )
    }

    // ---- CTAP digit encoding ----

    /** Digit count of a trailing partial chunk → its byte count. */
    private val partialChunkBytes = mapOf(3 to 1, 5 to 2, 8 to 3, 10 to 4, 13 to 5, 15 to 6)

    /**
     * Reverses CTAP 2.2 `digitEncode`: each 17-decimal-digit chunk is a
     * 7-byte little-endian integer; the final partial chunk must have one
     * of the fixed digit counts above.
     */
    private fun digitDecode(digits: String): ByteArray? {
        if (digits.isEmpty()) return null
        val out = ArrayList<Byte>(digits.length / 17 * 7 + 7)
        var i = 0
        while (i < digits.length) {
            val remaining = digits.length - i
            val take: Int
            val byteCount: Int
            if (remaining >= 17) {
                take = 17; byteCount = 7
            } else {
                take = remaining
                byteCount = partialChunkBytes[remaining] ?: return null
            }
            // 17 digits max ≈ 1e17 < Long.MAX_VALUE, so Long is enough.
            var v = digits.substring(i, i + take).toLongOrNull() ?: return null
            if (v >= (1L shl (8 * byteCount))) return null
            repeat(byteCount) {
                out += (v and 0xFF).toByte()
                v = v ushr 8
            }
            i += take
        }
        return out.toByteArray()
    }

    // ---- CBOR ----

    private sealed class CborValue {
        data class Uint(val v: Long) : CborValue()
        data class Bytes(val v: ByteArray) : CborValue() {
            override fun equals(other: Any?) = other is Bytes && v.contentEquals(other.v)
            override fun hashCode() = v.contentHashCode()
        }
        data class Text(val v: String) : CborValue()
        data class Bool(val v: Boolean) : CborValue()
        object Other : CborValue()
    }

    private fun decodeCborMap(bytes: ByteArray): List<Pair<Long, CborValue>>? {
        val reader = CborReader(bytes)
        val header = reader.readHeader() ?: return null
        if (header.first != 5) return null
        val pairs = mutableListOf<Pair<Long, CborValue>>()
        repeat(clampCount(header.second, bytes.size) ?: return null) {
            val key = reader.readValue() ?: return null
            val value = reader.readValue() ?: return null
            if (key is CborValue.Uint) pairs += key.v to value
        }
        return pairs
    }

    /** A declared count can't exceed the remaining bytes (≥ 1 byte each). */
    private fun clampCount(arg: Long, limit: Int): Int? =
        if (arg in 0..limit.toLong()) arg.toInt() else null

    /**
     * Minimal CBOR reader — just enough to walk the CTAP2 canonical-CBOR
     * map inside a hybrid-transport QR. Understands unsigned ints, byte /
     * text strings and bools; skips everything else structurally.
     * Indefinite lengths (banned in CTAP2 CBOR) abort the parse.
     * Mirrors [ProtoReader]'s never-throw style.
     */
    private class CborReader(private val bytes: ByteArray) {
        private var i = 0

        /** (major type, argument) or null on truncation / indefinite length. */
        fun readHeader(): Pair<Int, Long>? {
            if (i >= bytes.size) return null
            val b = bytes[i++].toInt() and 0xFF
            val major = b shr 5
            val info = b and 0x1F
            return when {
                info < 24 -> major to info.toLong()
                info <= 27 -> {
                    val n = 1 shl (info - 24)
                    if (i + n > bytes.size) return null
                    var v = 0L
                    repeat(n) { v = (v shl 8) or (bytes[i++].toLong() and 0xFF) }
                    major to v
                }
                else -> null
            }
        }

        fun readValue(): CborValue? {
            val (major, arg) = readHeader() ?: return null
            return when (major) {
                0 -> if (arg >= 0) CborValue.Uint(arg) else CborValue.Other
                1 -> CborValue.Other            // negative int — arg consumed
                2, 3 -> {
                    val len = clampCount(arg, bytes.size - i) ?: return null
                    val d = bytes.copyOfRange(i, i + len)
                    i += len
                    if (major == 2) CborValue.Bytes(d)
                    else CborValue.Text(d.toString(Charsets.UTF_8))
                }
                4, 5 -> {
                    // Nested array / map — skip elementwise.
                    val count = clampCount(arg, bytes.size) ?: return null
                    repeat(if (major == 5) 2 * count else count) {
                        readValue() ?: return null
                    }
                    CborValue.Other
                }
                6 -> readValue()?.let { CborValue.Other }
                7 -> when (arg) {
                    20L -> CborValue.Bool(false)
                    21L -> CborValue.Bool(true)
                    else -> CborValue.Other     // null / float — bytes consumed
                }
                else -> null
            }
        }
    }
}
