package me.nettrash.scan.data.payload

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The shared CBOR reader (moved out of `PasskeyPayload.kt` in 1.10 once
 * the mDL parser needed it) and the passkey parser that now walks with
 * it. The passkey cases pin what the swap must not change, plus the two
 * places where the old private reader disagreed with iOS: malformed UTF-8
 * text, and uint64 values that a signed `Long` cannot hold.
 */
class CBORReaderTest {

    private fun read(vararg bytes: Int): CBORReader.Value? =
        CBORReader(ByteArray(bytes.size) { bytes[it].toByte() }).readValue()

    // ---- Reader --------------------------------------------------------

    @Test fun readsEveryMajorType() {
        assertThat(read(0x00)).isEqualTo(CBORReader.Value.Uint(0uL))
        assertThat(read(0x17)).isEqualTo(CBORReader.Value.Uint(23uL))
        assertThat(read(0x18, 0xFF)).isEqualTo(CBORReader.Value.Uint(255uL))
        assertThat(read(0x19, 0x01, 0x00)).isEqualTo(CBORReader.Value.Uint(256uL))
        assertThat(read(0x1A, 0x00, 0x01, 0x00, 0x00)).isEqualTo(CBORReader.Value.Uint(65_536uL))
        assertThat(read(0x20)).isEqualTo(CBORReader.Value.Other)               // -1
        assertThat(read(0x42, 0x01, 0x02)).isEqualTo(CBORReader.Value.Bytes(byteArrayOf(1, 2)))
        assertThat(read(0x62, 0xC3, 0xA9)).isEqualTo(CBORReader.Value.Text("é"))
        assertThat(read(0xF4)).isEqualTo(CBORReader.Value.Bool(false))
        assertThat(read(0xF5)).isEqualTo(CBORReader.Value.Bool(true))
        assertThat(read(0xF6)).isEqualTo(CBORReader.Value.Other)               // null
        assertThat(read(0xF9, 0x3C, 0x00)).isEqualTo(CBORReader.Value.Other)   // 1.0 as float16
        assertThat(read(0xC1, 0x01)).isEqualTo(CBORReader.Value.Other)         // tag 1 (epoch)
        assertThat(read(0x82, 0x01, 0x61, 0x78)).isEqualTo(
            CBORReader.Value.Array(listOf(CBORReader.Value.Uint(1uL), CBORReader.Value.Text("x")))
        )
    }

    @Test fun keepsUint64AboveLongMaxAsAnUnsignedValue() {
        // A signed Long would read these as negative — and a negative
        // "count" or "timestamp" passes every `<=` upper-bound check.
        assertThat(read(0x1B, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF))
            .isEqualTo(CBORReader.Value.Uint(ULong.MAX_VALUE))
        assertThat(read(0x1B, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            .isEqualTo(CBORReader.Value.Uint(1uL shl 63))
    }

    @Test fun textMustBeValidUtf8() {
        // iOS `String(bytes:encoding:.utf8)` returns nil here; so do we,
        // instead of handing back U+FFFD replacement characters.
        assertThat(read(0x62, 0xFF, 0xFE)).isEqualTo(CBORReader.Value.Other)
        assertThat(read(0x61, 0xC3)).isEqualTo(CBORReader.Value.Other)
    }

    @Test fun rejectsTruncatedIndefiniteAndOversizedItems() {
        assertThat(read()).isNull()
        assertThat(read(0x19, 0x01)).isNull()                                  // header cut short
        assertThat(read(0x44, 0x01, 0x02)).isNull()                            // 4 bytes promised
        assertThat(read(0x82, 0x01)).isNull()                                  // 2 items promised
        assertThat(read(0x5F, 0x41, 0x00, 0xFF)).isNull()                      // indefinite bytes
        assertThat(read(0x9F, 0x01, 0xFF)).isNull()                            // indefinite array
        assertThat(read(0x9B, 0x7F, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)).isNull()
        assertThat(read(0x5B, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)).isNull()
    }

    @Test fun capsNestingInsteadOfOverflowingTheStack() {
        fun nested(levels: Int, opener: Int) =
            CBORReader(ByteArray(levels) { opener.toByte() } + byteArrayOf(0x01)).readValue()
        assertThat(nested(CBORReader.MAX_DEPTH, 0x81)).isNotNull()
        assertThat(nested(CBORReader.MAX_DEPTH + 1, 0x81)).isNull()
        assertThat(nested(CBORReader.MAX_DEPTH, 0xC1)).isEqualTo(CBORReader.Value.Other)
        assertThat(nested(CBORReader.MAX_DEPTH + 1, 0xC1)).isNull()
        // A QR-sized payload of nothing but openers: one level per byte.
        assertThat(nested(20_000, 0x81)).isNull()
        assertThat(nested(20_000, 0xA1)).isNull()
    }

    @Test fun mapLookupsByTextAndIntegerKey() {
        // {"a": 1, 2: "b", "a": 3} — CBOR allows the duplicate.
        val map = read(0xA3, 0x61, 0x61, 0x01, 0x02, 0x61, 0x62, 0x61, 0x61, 0x03) as CBORReader.Value.Map
        assertThat(map.v.valueForKey("a")).isEqualTo(CBORReader.Value.Uint(1uL))
        assertThat(map.v.valueForKey(2uL)).isEqualTo(CBORReader.Value.Text("b"))
        assertThat(map.v.valueForKey("missing")).isNull()
        assertThat(map.v.valuesForKey("a"))
            .containsExactly(CBORReader.Value.Uint(1uL), CBORReader.Value.Uint(3uL)).inOrder()
    }

    @Test fun hasMoreDistinguishesTrailingInput() {
        val reader = CBORReader(byteArrayOf(0x01, 0x02))
        assertThat(reader.readValue()).isEqualTo(CBORReader.Value.Uint(1uL))
        assertThat(reader.hasMore).isTrue()
        assertThat(reader.readValue()).isEqualTo(CBORReader.Value.Uint(2uL))
        assertThat(reader.hasMore).isFalse()
        assertThat(reader.readValue()).isNull()
    }

    // ---- Passkey QR on the shared reader --------------------------------

    private val publicKey = cborBytes(ByteArray(33) { if (it == 0) 2 else 7 })
    private val qrSecret = cborBytes(ByteArray(16) { 9 })

    private fun passkey(vararg pairs: Pair<ByteArray, ByteArray>): PasskeyPayload =
        PasskeyParser.parse("FIDO:/" + digitEncode(cborMap(*pairs)))!!

    @Test fun passkeyIgnoresValuesOutsideTheirRange() {
        // iOS guards with UInt64 comparisons; a negative Long would have
        // become tunnelDomains = -1 and a date before 1970.
        val p = passkey(
            cborUint(0uL) to publicKey, cborUint(1uL) to qrSecret,
            cborUint(2uL) to cborUint(ULong.MAX_VALUE), cborUint(3uL) to cborUint(1uL shl 63),
            cborUint(4uL) to byteArrayOf(0xF5.toByte()), cborUint(5uL) to cborText("ga"),
        )
        assertThat(p.decoded).isTrue()
        assertThat(p.tunnelDomains).isNull()
        assertThat(p.generatedAtEpochSeconds).isNull()
        assertThat(p.linkable).isTrue()
        assertThat(p.operationHint).isEqualTo("ga")
    }

    @Test fun passkeyWithMalformedHintFallsBackToTheGenericPurpose() {
        val p = passkey(
            cborUint(0uL) to publicKey, cborUint(1uL) to qrSecret, cborUint(2uL) to cborUint(2uL),
            cborUint(5uL) to (cborHead(3, 2uL) + byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
        )
        assertThat(p.decoded).isTrue()
        assertThat(p.operationHint).isNull()
        assertThat(p.purposeDescription).isEqualTo("Cross-device passkey")
        assertThat(p.tunnelDomains).isEqualTo(2)
    }

    @Test fun passkeyWalksPastNestedUnknownKeys() {
        val p = passkey(
            cborUint(0uL) to publicKey, cborUint(1uL) to qrSecret, cborUint(2uL) to cborUint(1uL),
            cborUint(3uL) to cborUint(1_755_763_200uL), cborUint(4uL) to byteArrayOf(0xF4.toByte()),
            cborUint(5uL) to cborText("mc"),
            cborUint(6uL) to cborMap(cborText("x") to (cborHead(4, 2uL) + cborUint(1uL) + byteArrayOf(0xD8.toByte(), 24) + cborBytes(byteArrayOf(1)))),
            cborText("textkey") to cborUint(7uL),
        )
        assertThat(p.decoded).isTrue()
        assertThat(p.purposeDescription).isEqualTo("Create a passkey")
        assertThat(p.tunnelDomains).isEqualTo(1)
        assertThat(p.generatedAtEpochSeconds).isEqualTo(1_755_763_200L)
        assertThat(p.linkable).isFalse()
    }

    @Test fun passkeyThatIsNotAMapIsRecognisedButUndecoded() {
        val notAMap = PasskeyParser.parse("FIDO:/" + digitEncode(cborHead(4, 2uL) + cborUint(1uL) + cborUint(2uL)))!!
        assertThat(notAMap.decoded).isFalse()
        // A map promising more entries than the bytes could hold.
        val overpromised = PasskeyParser.parse("FIDO:/" + digitEncode(cborHead(5, 200uL) + cborUint(2uL) + cborUint(1uL)))!!
        assertThat(overpromised.decoded).isFalse()
        assertThat(overpromised.labelledFields().associate { it.label to it.value }["Payload"])
            .isEqualTo("Not decodable — possibly a newer format")
    }

    // ---- Fixture builders ------------------------------------------------

    private fun cborHead(major: Int, arg: ULong): ByteArray {
        val m = major shl 5
        val width = when {
            arg < 24uL -> return byteArrayOf((m or arg.toInt()).toByte())
            arg <= 0xFFuL -> 1
            arg <= 0xFFFFuL -> 2
            arg <= 0xFFFF_FFFFuL -> 4
            else -> 8
        }
        val info = 24 + Integer.numberOfTrailingZeros(width)
        return byteArrayOf((m or info).toByte()) +
            ByteArray(width) { (arg shr (8 * (width - 1 - it))).toByte() }
    }

    private fun cborUint(v: ULong) = cborHead(0, v)
    private fun cborBytes(b: ByteArray) = cborHead(2, b.size.toULong()) + b
    private fun cborText(s: String) = s.toByteArray(Charsets.UTF_8).let { cborHead(3, it.size.toULong()) + it }
    private fun cborMap(vararg pairs: Pair<ByteArray, ByteArray>): ByteArray =
        pairs.fold(cborHead(5, pairs.size.toULong())) { acc, (k, v) -> acc + k + v }

    /** CTAP 2.2 `digitEncode`: 7-byte little-endian chunks as 17 digits, then a short tail. */
    private fun digitEncode(data: ByteArray): String = buildString {
        var i = 0
        while (i < data.size) {
            val n = minOf(7, data.size - i)
            var v = 0L
            for (j in n - 1 downTo 0) v = (v shl 8) or (data[i + j].toLong() and 0xFF)
            append(v.toString().padStart(if (n == 7) 17 else PARTIAL_DIGITS.getValue(n), '0'))
            i += n
        }
    }

    private companion object {
        val PARTIAL_DIGITS = mapOf(1 to 3, 2 to 5, 3 to 8, 4 to 10, 5 to 13, 6 to 15)
    }
}
