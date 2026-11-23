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
                key == 2uL && value is CBORReader.Value.Uint && value.v <= Int.MAX_VALUE.toULong() ->
                    tunnelDomains = value.v.toInt()
                key == 3uL && value is CBORReader.Value.Uint && value.v < (1uL shl 40) ->
                    generatedAt = value.v.toLong()
                key == 4uL && value is CBORReader.Value.Bool -> linkable = value.v
                key == 5uL && value is CBORReader.Value.Text -> operationHint = value.v
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

    /**
     * The top-level CTAP map, keyed by unsigned integer. Walked with the
     * shared [CBORReader] (1.10 moved it out of this file once the mDL
     * parser needed it too). Non-integer keys are skipped, exactly as the
     * iOS `decodeCBORMap` skips them.
     */
    private fun decodeCborMap(bytes: ByteArray): List<Pair<ULong, CBORReader.Value>>? {
        val reader = CBORReader(bytes)
        val header = reader.readHeader() ?: return null
        if (header.major != 5) return null
        // Every entry costs at least two bytes, so a declared count beyond
        // the input can never be met. iOS finds that out by running dry
        // partway through; bounding it first also keeps the count an Int.
        if (header.arg < 0 || header.arg > bytes.size) return null
        val pairs = mutableListOf<Pair<ULong, CBORReader.Value>>()
        repeat(header.arg.toInt()) {
            val key = reader.readValue() ?: return null
            val value = reader.readValue() ?: return null
            if (key is CBORReader.Value.Uint) pairs += key.v to value
        }
        return pairs
    }
}
