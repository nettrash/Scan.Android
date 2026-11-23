package me.nettrash.scan.nfc

import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import me.nettrash.scan.data.payload.ScanPayload
import me.nettrash.scan.data.payload.ScanPayloadParser
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.nio.charset.StandardCharsets

/**
 * Coverage for the pure half of `Scan/NFCTagWriter.swift` (`NDEFRecordCodec`
 * + `WiFiSimpleConfig`): what goes onto a tag, what comes back off one, and
 * the gate that decides whether the UI shows the control at all. The
 * reader-mode session itself needs a real tag and is left to manual testing,
 * but the framework's own `NdefRecord` / `NdefMessage` (real classes under
 * Robolectric) are used here to prove that what the codec builds is
 * something the framework accepts and frames back unchanged.
 */
@RunWith(RobolectricTestRunner::class)
class NfcPayloadCodecTest {

    // MARK: - Type name formats

    @Test fun tnfValuesAreTheFrameworkOnes() {
        // A record with the wrong TNF is rejected by NdefRecord's constructor
        // (TNF_EMPTY with a type, say), which is how an off-by-one table made
        // every write fail before a byte reached the tag.
        assertThat(NfcPayloadCodec.TNF_EMPTY).isEqualTo(NdefRecord.TNF_EMPTY)
        assertThat(NfcPayloadCodec.TNF_WELL_KNOWN).isEqualTo(NdefRecord.TNF_WELL_KNOWN)
        assertThat(NfcPayloadCodec.TNF_MIME_MEDIA).isEqualTo(NdefRecord.TNF_MIME_MEDIA)
        assertThat(NfcPayloadCodec.TNF_ABSOLUTE_URI).isEqualTo(NdefRecord.TNF_ABSOLUTE_URI)
        assertThat(NfcPayloadCodec.TNF_EXTERNAL_TYPE).isEqualTo(NdefRecord.TNF_EXTERNAL_TYPE)
        assertThat(NfcPayloadCodec.TNF_UNKNOWN).isEqualTo(NdefRecord.TNF_UNKNOWN)
        assertThat(NfcPayloadCodec.TNF_UNCHANGED).isEqualTo(NdefRecord.TNF_UNCHANGED)
        assertThat(NfcPayloadCodec.TNF_RESERVED).isEqualTo(0x07.toShort())
        assertThat(NfcPayloadCodec.RTD_TEXT.toByteArray(StandardCharsets.US_ASCII))
            .isEqualTo(NdefRecord.RTD_TEXT)
        assertThat(NfcPayloadCodec.RTD_URI.toByteArray(StandardCharsets.US_ASCII))
            .isEqualTo(NdefRecord.RTD_URI)
        assertThat(NfcPayloadCodec.RTD_SMART_POSTER.toByteArray(StandardCharsets.US_ASCII))
            .isEqualTo(NdefRecord.RTD_SMART_POSTER)
    }

    @Test fun everyWrittenRecordSurvivesTheFrameworkRoundTrip() {
        val requests = listOf(
            NfcTagWriteRequest(NfcTagPayloadKind.Url, "https://nettrash.me/scan"),
            NfcTagWriteRequest(NfcTagPayloadKind.Text, "Pass #42 — gate B"),
            NfcTagWriteRequest(NfcTagPayloadKind.Wifi, "WIFI:T:WPA;S:Guest;P:s3cret;;"),
            NfcTagWriteRequest(NfcTagPayloadKind.Wifi, "WIFI:T:SAE;S:Home;P:wpa3only;;"),
            NfcTagWriteRequest(
                NfcTagPayloadKind.Contact,
                "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ana Ivić\r\nEND:VCARD",
            ),
        )
        for (request in requests) {
            val records = NfcPayloadCodec.recordsFor(request)!!
            val framed = NdefMessage(
                records.map { NdefRecord(it.tnf, it.type, null, it.payload) }.toTypedArray()
            )
            // What a tag hands back: the bytes, re-parsed by the framework.
            val readBack = NdefMessage(framed.toByteArray()).records.map {
                NfcPayloadCodec.Record(it.tnf, it.type, it.payload)
            }
            val text = NfcPayloadCodec.readableString(readBack)
            val expected = ScanPayloadParser.parse(request.value)
            assertThat(ScanPayloadParser.parse(text!!)).isEqualTo(expected)
        }
    }

    // MARK: - Text records

    @Test fun writesRtdTextRecordWithStatusLanguageAndUtf8() {
        val payload = NfcPayloadCodec.textRecordPayload("Pass #42", "en")
        // Status byte: UTF-16 bit clear, low six bits = "en".length.
        assertThat(payload[0].toInt() and 0xFF).isEqualTo(2)
        assertThat(String(payload, 1, 2, StandardCharsets.US_ASCII)).isEqualTo("en")
        assertThat(String(payload, 3, payload.size - 3, StandardCharsets.UTF_8)).isEqualTo("Pass #42")
    }

    @Test fun textRecordRoundTrips() {
        val payload = NfcPayloadCodec.textRecordPayload("WIFI:S:Guest;T:WPA2;P:s3cret;;", "de")
        assertThat(NfcPayloadCodec.textFromRecordPayload(payload)).isEqualTo("WIFI:S:Guest;T:WPA2;P:s3cret;;")
    }

    @Test fun readsUtf16TextRecordWithAndWithoutByteOrderMark() {
        val language = "en".toByteArray(StandardCharsets.US_ASCII)
        val status = byteArrayOf((0x80 or language.size).toByte())
        // Java's UTF_16 encoder writes a big-endian BOM.
        val withBom = status + language + "Ünïcode ✓".toByteArray(StandardCharsets.UTF_16)
        assertThat(NfcPayloadCodec.textFromRecordPayload(withBom)).isEqualTo("Ünïcode ✓")
        // No BOM means big-endian (RTD Text 1.0).
        val bare = status + language + "Hi".toByteArray(StandardCharsets.UTF_16BE)
        assertThat(NfcPayloadCodec.textFromRecordPayload(bare)).isEqualTo("Hi")
    }

    @Test fun rejectsMalformedTextPayloads() {
        assertThat(NfcPayloadCodec.textFromRecordPayload(ByteArray(0))).isNull()
        // Claims a four-byte language tag but carries two bytes in total.
        assertThat(NfcPayloadCodec.textFromRecordPayload(byteArrayOf(0x04, 'e'.code.toByte()))).isNull()
    }

    @Test fun normalizesLanguageTagToAsciiOnly() {
        assertThat(NfcPayloadCodec.normalizeLanguageTag("EN-US")).isEqualTo("en-us")
        assertThat(NfcPayloadCodec.normalizeLanguageTag("  fr ")).isEqualTo("fr")
        assertThat(NfcPayloadCodec.normalizeLanguageTag("")).isEqualTo("en")
        assertThat(NfcPayloadCodec.normalizeLanguageTag("!!!")).isEqualTo("en")
        assertThat(NfcPayloadCodec.normalizeLanguageTag("a".repeat(80))).hasLength(63)
        // iOS keeps ASCII letters only; a non-ASCII letter ends the tag.
        assertThat(NfcPayloadCodec.normalizeLanguageTag("dé")).isEqualTo("d")
    }

    // MARK: - URI records

    @Test fun compressesUriWithLongestMatchingPrefix() {
        assertThat(prefixCodeOf("https://www.nettrash.me")).isEqualTo(0x02)
        assertThat(prefixCodeOf("https://nettrash.me")).isEqualTo(0x04)
        assertThat(prefixCodeOf("http://www.example.com")).isEqualTo(0x01)
        assertThat(prefixCodeOf("tel:+15551234")).isEqualTo(0x05)
        assertThat(prefixCodeOf("urn:epc:id:sgtin:1")).isEqualTo(0x1E)
        // Unlisted schemes fall back to code 0x00, "no prefix".
        assertThat(prefixCodeOf("nettrash.me")).isEqualTo(0x00)
    }

    @Test fun uriRecordRoundTrips() {
        for (url in listOf(
            "https://www.nettrash.me/scan",
            "http://192.168.0.1",
            "mailto:hi@example.com",
            "urn:nfc:whatever",
            "nettrash.me",
            "https://example.com/café",
        )) {
            val expanded = NfcPayloadCodec.uriFromRecordPayload(NfcPayloadCodec.uriRecordPayload(url))
            assertThat(expanded).isEqualTo(url)
        }
    }

    @Test fun rejectsReservedUriPrefixCode() {
        assertThat(NfcPayloadCodec.uriFromRecordPayload(byteArrayOf(0x7F) + "x".toByteArray())).isNull()
        assertThat(NfcPayloadCodec.uriFromRecordPayload(ByteArray(0))).isNull()
    }

    // MARK: - What can be written

    @Test fun urlRequestBecomesSingleUriRecord() {
        val record = NfcPayloadCodec.recordsFor(
            NfcTagWriteRequest(kind = NfcTagPayloadKind.Url, value = "https://nettrash.me/scan")
        )!!.single()
        assertThat(record.tnf).isEqualTo(NfcPayloadCodec.TNF_WELL_KNOWN)
        assertThat(record.typeName).isEqualTo(NfcPayloadCodec.RTD_URI)
        assertThat(NfcPayloadCodec.uriFromRecordPayload(record.payload)).isEqualTo("https://nettrash.me/scan")
    }

    @Test fun bareHostIsNormalisedButStillNotAValidUrlRequest() {
        // iOS quirk kept on purpose, so both apps write the same bytes:
        // `normalizedURL` would prepend https:// to a bare host, but
        // `isValid` rejects one first — so the only way to write a URL record
        // is to carry the scheme, which `inferred` guarantees.
        assertThat(NfcPayloadCodec.normalizedUrl("nettrash.me")).isEqualTo("https://nettrash.me")
        assertThat(NfcTagWriteRequest(kind = NfcTagPayloadKind.Url, value = "nettrash.me").isValid).isFalse()
    }

    @Test fun plainTextBecomesATextRecord() {
        val record = NfcPayloadCodec.recordsFor(NfcTagWriteRequest(NfcTagPayloadKind.Text, "hello"))!!.single()
        assertThat(record.tnf).isEqualTo(NfcPayloadCodec.TNF_WELL_KNOWN)
        assertThat(record.typeName).isEqualTo(NfcPayloadCodec.RTD_TEXT)
        assertThat(NfcPayloadCodec.textFromRecordPayload(record.payload)).isEqualTo("hello")
    }

    @Test fun wpaNetworkBecomesAWifiSimpleConfigToken() {
        val record = NfcPayloadCodec.recordsFor(
            NfcTagWriteRequest(NfcTagPayloadKind.Wifi, "WIFI:T:WPA;S:Guest;P:s3cret;;")
        )!!.single()
        assertThat(record.tnf).isEqualTo(NfcPayloadCodec.TNF_MIME_MEDIA)
        assertThat(record.typeName).isEqualTo(NfcPayloadCodec.WIFI_MEDIA_TYPE)
        // Byte for byte what iOS `WiFiSimpleConfig.token(forWiFiString:)`
        // builds: Version 1.0, Credential { index 1, SSID, WPA2-Personal,
        // AES, key, broadcast MAC }, then the WFA Version2 extension.
        val expected = hex(
            "104A 0001 10" +
                "100E 002E" +
                "1026 0001 01" +
                "1045 0005 4775657374" +
                "1003 0002 0020" +
                "100F 0002 0008" +
                "1027 0006 733363726574" +
                "1020 0006 FFFFFFFFFFFF" +
                "1049 0006 00372A000120"
        )
        assertThat(record.payload).isEqualTo(expected)
        assertThat(WifiSimpleConfig.wifiString(record.payload)).isEqualTo("WIFI:T:WPA;S:Guest;P:s3cret;;")
    }

    @Test fun openNetworkTokenRoundTrips() {
        val token = WifiSimpleConfig.token("WIFI:T:nopass;S:Café Free;;")!!
        val back = WifiSimpleConfig.wifiString(token)!!
        val parsed = ScanPayloadParser.parse(back) as ScanPayload.Wifi
        assertThat(parsed.ssid).isEqualTo("Café Free")
        assertThat(parsed.password.isNullOrEmpty()).isTrue()
    }

    @Test fun networksWscCannotDescribeStayText() {
        for (wifi in listOf(
            "WIFI:T:SAE;S:Home;P:wpa3only;;",        // WPA3
            "WIFI:T:WEP;S:Old;P:abcde;;",             // WEP
            "WIFI:T:WPA;S:Hidden;P:secret12;H:true;;", // hidden SSID has no attribute
            "WIFI:T:WPA;S:Empty;;",                   // WPA with no key
            "WIFI:S:Ambiguous;P:secret12;;",          // no type but a password
            "WIFI:T:WPA;S:${"x".repeat(33)};P:secret12;;", // SSID over 32 bytes
        )) {
            assertThat(WifiSimpleConfig.token(wifi)).isNull()
            val record = NfcPayloadCodec.recordsFor(NfcTagWriteRequest(NfcTagPayloadKind.Wifi, wifi))!!.single()
            assertThat(record.typeName).isEqualTo(NfcPayloadCodec.RTD_TEXT)
            assertThat(NfcPayloadCodec.textFromRecordPayload(record.payload)).isEqualTo(wifi)
        }
    }

    @Test fun enterpriseOrMalformedTokensReadAsNothing() {
        val enterprise = hex(
            "104A 0001 10" +
                "100E 0016" +
                "1045 0006 4F6666696365" +
                "1003 0002 0010" +
                "100F 0002 0008"
        )
        assertThat(WifiSimpleConfig.wifiString(enterprise)).isNull()
        // A length that runs off the end of the buffer.
        assertThat(WifiSimpleConfig.wifiString(hex("100E 00FF 1045"))).isNull()
        assertThat(WifiSimpleConfig.wifiString(ByteArray(0))).isNull()
    }

    @Test fun vCardContactBecomesATextVcardMediaRecord() {
        val card = "BEGIN:VCARD\nVERSION:3.0\nN:Doe;John\nEND:VCARD"
        val record = NfcPayloadCodec.recordsFor(NfcTagWriteRequest(NfcTagPayloadKind.Contact, card))!!.single()
        assertThat(record.tnf).isEqualTo(NfcPayloadCodec.TNF_MIME_MEDIA)
        assertThat(record.typeName).isEqualTo(NfcPayloadCodec.VCARD_MEDIA_TYPE)
        assertThat(String(record.payload, StandardCharsets.UTF_8)).isEqualTo(card)
        // Anything that isn't a vCard rides as text, like its QR would.
        val note = NfcPayloadCodec.recordsFor(NfcTagWriteRequest(NfcTagPayloadKind.Contact, "Ana, +381 64"))!!.single()
        assertThat(note.typeName).isEqualTo(NfcPayloadCodec.RTD_TEXT)
    }

    @Test fun emptyOrSchemelessUrlIsNotValidAndEncodesToNothing() {
        assertThat(NfcTagWriteRequest(kind = NfcTagPayloadKind.Text, value = "   ").isValid).isFalse()
        assertThat(NfcTagWriteRequest(kind = NfcTagPayloadKind.Url, value = "nettrash.me").isValid).isFalse()
        assertThat(NfcTagWriteRequest(kind = NfcTagPayloadKind.Url, value = "https://nettrash.me").isValid).isTrue()
        assertThat(NfcTagWriteRequest(kind = NfcTagPayloadKind.Text, value = "  hi  ").isValid).isTrue()
        assertThat(
            NfcPayloadCodec.recordsFor(NfcTagWriteRequest(kind = NfcTagPayloadKind.Url, value = "nettrash.me"))
        ).isNull()
    }

    @Test fun schemeDetectionFollowsIosRules() {
        assertThat(NfcPayloadCodec.hasScheme("https://nettrash.me")).isTrue()
        assertThat(NfcPayloadCodec.hasScheme("mailto:hi@example.com")).isTrue()
        assertThat(NfcPayloadCodec.hasScheme("web+scan://x")).isTrue()
        assertThat(NfcPayloadCodec.hasScheme("nettrash.me")).isFalse()
        assertThat(NfcPayloadCodec.hasScheme("12:30")).isFalse()
        assertThat(NfcPayloadCodec.hasScheme(":nope")).isFalse()
        // ASCII only, as Swift's `isASCII && isLetter`.
        assertThat(NfcPayloadCodec.hasScheme("über:x")).isFalse()
    }

    @Test fun kindIsInferredFromTheContentLikeIos() {
        assertThat(NfcTagPayloadKind.inferred("WIFI:T:WPA;S:x;P:y;;")).isEqualTo(NfcTagPayloadKind.Wifi)
        assertThat(NfcTagPayloadKind.inferred("  begin:vcard\nEND:VCARD")).isEqualTo(NfcTagPayloadKind.Contact)
        assertThat(NfcTagPayloadKind.inferred("HTTPS://nettrash.me")).isEqualTo(NfcTagPayloadKind.Url)
        assertThat(NfcTagPayloadKind.inferred("http://x")).isEqualTo(NfcTagPayloadKind.Url)
        // "httpd is down" starts with "http" but is not a link.
        assertThat(NfcTagPayloadKind.inferred("httpd is down")).isEqualTo(NfcTagPayloadKind.Text)
        assertThat(NfcTagPayloadKind.inferred("mailto:hi@example.com")).isEqualTo(NfcTagPayloadKind.Url)
    }

    // MARK: - What comes back from a read

    @Test fun firstReadableRecordWins() {
        // NDEF's first record sets what a message means (iOS
        // `readableString(in:)` takes the first one Scan can read).
        val records = listOf(
            textRecord("https://nettrash.me/scan/abc"),
            NfcPayloadCodec.uriRecord("https://nettrash.me"),
        )
        assertThat(NfcPayloadCodec.readableString(records)).isEqualTo("https://nettrash.me/scan/abc")
    }

    @Test fun skipsRecordsItDoesNotUnderstand() {
        val records = listOf(
            NfcPayloadCodec.Record(
                tnf = NfcPayloadCodec.TNF_EXTERNAL_TYPE,
                type = "android.com:pkg".toByteArray(StandardCharsets.US_ASCII),
                payload = "com.example".toByteArray(StandardCharsets.UTF_8),
            ),
            NfcPayloadCodec.Record(
                tnf = NfcPayloadCodec.TNF_WELL_KNOWN,
                // A smart poster whose payload isn't an NDEF message.
                type = "Sp".toByteArray(StandardCharsets.US_ASCII),
                payload = "Sp".toByteArray(StandardCharsets.UTF_8),
            ),
            NfcPayloadCodec.mediaRecord("image/png", byteArrayOf(1, 2, 3)),
            textRecord("   "),
            textRecord("usable"),
        )
        assertThat(NfcPayloadCodec.readableString(records)).isEqualTo("usable")
    }

    @Test fun readsAbsoluteUriFromTheTypeFieldOrThePayload() {
        val inType = NfcPayloadCodec.Record(
            tnf = NfcPayloadCodec.TNF_ABSOLUTE_URI,
            type = "https://nettrash.me/tag".toByteArray(StandardCharsets.UTF_8),
            payload = ByteArray(0),
        )
        assertThat(NfcPayloadCodec.readableString(listOf(inType))).isEqualTo("https://nettrash.me/tag")
        // A few writers put it in the payload instead.
        val inPayload = NfcPayloadCodec.Record(
            tnf = NfcPayloadCodec.TNF_ABSOLUTE_URI,
            type = ByteArray(0),
            payload = "https://nettrash.me/legacy".toByteArray(StandardCharsets.UTF_8),
        )
        assertThat(NfcPayloadCodec.readableString(listOf(inPayload))).isEqualTo("https://nettrash.me/legacy")
    }

    @Test fun readsVcardMediaRecordsWhateverTheTypeSpelling() {
        val card = "BEGIN:VCARD\nVERSION:3.0\nFN:Jo\nEND:VCARD"
        for (type in listOf("text/vcard", "text/x-vcard", "Text/VCard; charset=utf-8")) {
            val record = NfcPayloadCodec.mediaRecord(type, card.toByteArray(StandardCharsets.UTF_8))
            assertThat(NfcPayloadCodec.readableString(listOf(record))).isEqualTo(card)
        }
    }

    @Test fun emptyTagReadsAsNothing() {
        assertThat(NfcPayloadCodec.readableString(emptyList())).isNull()
    }

    // MARK: - Shared fixtures (byte for byte iOS NFCTagCodecParityTests)
    //
    // The two tables were generated together and change together, so a tag
    // reads, and a request writes, the same on both apps. On top of what iOS checks, the
    // framework's own NdefMessage parser is held against parseMessage.

    @Test fun sharedReadFixturesReadAsOnIos() {
        for (fixture in readFixtures) {
            val bytes = hex(fixture.hex)
            val records = NfcPayloadCodec.parseMessage(bytes)
            assertWithMessage(fixture.name).that(records?.map(::fields))
                .isEqualTo(NdefMessage(bytes).records.map(::fields))
            assertWithMessage(fixture.name).that(NfcPayloadCodec.readableString(records!!))
                .isEqualTo(fixture.expected)
        }
    }

    @Test fun nestedMessagesParseExactlyAsTheFrameworkDoes() {
        val posters = readFixtures.flatMap { NfcPayloadCodec.parseMessage(hex(it.hex)).orEmpty() }
            .filter { it.tnf == NfcPayloadCodec.TNF_WELL_KNOWN && it.typeName == NfcPayloadCodec.RTD_SMART_POSTER }
            .map { it.payload }
        assertThat(posters.size).isAtLeast(10)
        for (nested in posters + malformedNestedMessages.map { hex(it.hex) }) {
            // Same records where the framework accepts the bytes, null where it throws.
            val framework = runCatching { NdefMessage(nested).records.map(::fields) }.getOrNull()
            assertWithMessage(nested.toHex()).that(NfcPayloadCodec.parseMessage(nested)?.map(::fields))
                .isEqualTo(framework)
        }
    }

    @Test fun malformedNestedMessagesAreRejected() {
        for (fixture in malformedNestedMessages) {
            val nested = hex(fixture.hex)
            assertWithMessage(fixture.name).that(NfcPayloadCodec.parseMessage(nested)).isNull()
            assertWithMessage(fixture.name)
                .that(runCatching { NdefMessage(nested) }.exceptionOrNull())
                .isInstanceOf(FormatException::class.java)
            // A poster carrying it reads as nothing, and nothing throws.
            val poster = NfcPayloadCodec.Record(NfcPayloadCodec.TNF_WELL_KNOWN, "Sp".ascii(), nested)
            assertWithMessage(fixture.name).that(NfcPayloadCodec.smartPosterUri(poster)).isNull()
            assertWithMessage(fixture.name).that(NfcPayloadCodec.readableString(listOf(poster))).isNull()
        }
    }

    @Test fun smartPosterExampleParsesRecordByRecord() {
        // Example A.2's nested message: a long-form (SR = 0) URI record, the
        // action, then titles in two languages. The titles are display text
        // and never the value.
        val nested = hex(
            "81010000000E55016E66632D666F72756D2E6F7267 11030161637400" +
                " 1101125405656E2D555348656C6C6F2C20776F726C64 510113540266694D6F726A656E732C206D6161696C6D61"
        )
        assertThat(NfcPayloadCodec.parseMessage(nested)?.map(::fields)).containsExactly(
            Triple(NfcPayloadCodec.TNF_WELL_KNOWN, "U".ascii().toHex(), "01".plusAscii("nfc-forum.org")),
            Triple(NfcPayloadCodec.TNF_WELL_KNOWN, "act".ascii().toHex(), "00"),
            Triple(NfcPayloadCodec.TNF_WELL_KNOWN, "T".ascii().toHex(), "05".plusAscii("en-USHello, world")),
            Triple(NfcPayloadCodec.TNF_WELL_KNOWN, "T".ascii().toHex(), "02".plusAscii("fiMorjens, maailma")),
        ).inOrder()
        val poster = NfcPayloadCodec.Record(NfcPayloadCodec.TNF_WELL_KNOWN, "Sp".ascii(), nested)
        assertThat(NfcPayloadCodec.smartPosterUri(poster)).isEqualTo("http://www.nfc-forum.org")
        assertThat(NfcPayloadCodec.smartPosterUri(NfcPayloadCodec.uriRecord("https://nettrash.me"))).isNull()
    }

    @Test fun chunkedRecordIsReassembled() {
        // Initial chunk (CF, TNF 0x01, "U"), a middle chunk and the
        // terminating one (TNF 0x06, no TYPE): one record, one payload.
        val bytes = hex("B1010955046E65747472617368 3600032E6D65 5600052F7363616E")
        assertThat(NfcPayloadCodec.parseMessage(bytes)?.map(::fields)).containsExactly(
            Triple(NfcPayloadCodec.TNF_WELL_KNOWN, "U".ascii().toHex(), "04".plusAscii("nettrash.me/scan")),
        )
        assertThat(NdefMessage(bytes).records.map(::fields)).isEqualTo(NfcPayloadCodec.parseMessage(bytes)?.map(::fields))
    }

    @Test fun recordCountIsBounded() {
        /** A poster of [count] records: its URI ("https://x"), then well-known
         *  "x" records with no payload, the last one with ME. */
        fun poster(count: Int): NfcPayloadCodec.Record {
            val nested = mutableListOf(0x91, 0x01, 0x02, 0x55, 0x04, 0x78)
            for (index in 1 until count) nested += listOf(if (index == count - 1) 0x51 else 0x11, 0x01, 0x00, 0x78)
            return NfcPayloadCodec.Record(
                NfcPayloadCodec.TNF_WELL_KNOWN,
                "Sp".ascii(),
                ByteArray(nested.size) { nested[it].toByte() },
            )
        }
        assertThat(NfcPayloadCodec.MAX_MESSAGE_RECORDS).isEqualTo(255)
        assertThat(NfcPayloadCodec.readableString(listOf(poster(255)))).isEqualTo("https://x")
        assertThat(NfcPayloadCodec.readableString(listOf(poster(256)))).isNull()
        // The cap is Scan's own (NDEF 1.0 §4.3 allows one); the framework has none.
        assertThat(NdefMessage(poster(256).payload).records).hasLength(256)
    }

    @Test fun uriPrefixTableMatchesIos() {
        for (fixture in uriPrefixFixtures) {
            val payload = NfcPayloadCodec.uriRecordPayload(fixture.uri)
            assertWithMessage(fixture.uri).that(payload[0].toInt() and 0xFF).isEqualTo(fixture.code)
            assertWithMessage(fixture.uri).that(NfcPayloadCodec.uriFromRecordPayload(payload)).isEqualTo(fixture.uri)
        }
        assertWithMessage("every identifier code is exercised")
            .that(uriPrefixFixtures.map { it.code }.toSet()).isEqualTo((0x00..0x23).toSet())
    }

    @Test fun writeFixturesMatchIos() {
        for (fixture in writeFixtures) {
            val kind = NfcTagPayloadKind.valueOf(fixture.kind.replaceFirstChar { it.uppercase() })
            val record = NfcPayloadCodec.recordsFor(NfcTagWriteRequest(kind, fixture.value))?.single()
            assertWithMessage(fixture.name).that(record?.let(::fields))
                .isEqualTo(Triple(fixture.tnf.toShort(), fixture.type.ascii().toHex(), fixture.payload))
            // ...and the framework takes it as it is.
            NdefRecord(record!!.tnf, record.type, null, record.payload)
        }
    }

    @Test fun kindInferenceAndTrimmingMatchIos() {
        // Swift's whitespacesAndNewlines on both ports: U+0085 and U+200B are
        // trimmed, U+001C..U+001F are not — Kotlin's own trim() is the reverse.
        for (fixture in inferenceFixtures) {
            val kind = NfcTagPayloadKind.inferred(fixture.value)
            assertWithMessage(fixture.value).that(kind.name.lowercase()).isEqualTo(fixture.kind)
            assertWithMessage(fixture.value).that(NfcTagWriteRequest(kind, fixture.value).isValid)
                .isEqualTo(fixture.valid)
        }
    }

    // MARK: - Phone, email, SMS and map links

    @Test fun linksBecomeUriRecordsTheFrameworkReadsBack() {
        assertThat(NfcTagPayloadKind.LINK_PREFIXES)
            .containsExactly("http://", "https://", "tel:", "mailto:", "sms:", "smsto:", "geo:").inOrder()
        for (fixture in linkFixtures) {
            val kind = NfcTagPayloadKind.inferred(fixture.value)
            assertWithMessage(fixture.name).that(kind).isEqualTo(NfcTagPayloadKind.Url)
            val record = NfcPayloadCodec.recordsFor(NfcTagWriteRequest(kind, fixture.value))!!.single()
            assertWithMessage(fixture.name).that(fields(record))
                .isEqualTo(Triple(NfcPayloadCodec.TNF_WELL_KNOWN, "U".ascii().toHex(), fixture.payload))
            // Scan reads back exactly what a scan of the code gives...
            assertWithMessage(fixture.name).that(NfcPayloadCodec.readableString(listOf(record)))
                .isEqualTo(fixture.uri)
            // ...and the framework's own reader — the URI NFC dispatch dials,
            // composes or shows — hands an app the same URI once the record is
            // framed and parsed back: the URI itself, its scheme lowercased by
            // Uri.normalizeScheme() when it was typed in capitals.
            val framed = NdefMessage(arrayOf(NdefRecord(record.tnf, record.type, null, record.payload)))
            val system = NdefMessage(framed.toByteArray()).records.single().toUri()
            assertWithMessage(fixture.name).that(system).isEqualTo(Uri.parse(fixture.uri).normalizeScheme())
            val scheme = fixture.uri.substringBefore(':')
            if (scheme == scheme.lowercase()) {
                assertWithMessage(fixture.name).that(system.toString()).isEqualTo(fixture.uri)
            }
        }
    }

    // MARK: - An outcome belongs to the content it was for

    @Test fun editingTheContentClearsTheLastOutcome() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        // A phone without NFC hardware, as the iOS simulator is, so content
        // worth writing fails before reader mode is armed.
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_NFC, false)
        val writer = NfcTagWriter()
        try {
            writer.contentChanged()
            assertWithMessage("nothing to clear").that(writer.status.value).isEqualTo(NfcTagWriter.Status.Idle)
            for (fixture in outcomeFixtures) {
                writer.write(activity, NfcTagWriteRequest(NfcTagPayloadKind.inferred(fixture.value), fixture.value))
                assertWithMessage(fixture.value).that(writer.status.value)
                    .isEqualTo(NfcTagWriter.Status.Failed(fixture.failure))
                writer.contentChanged()
                assertWithMessage(fixture.value).that(writer.status.value).isEqualTo(NfcTagWriter.Status.Idle)
            }
        } finally {
            writer.dispose()
        }
    }

    // MARK: - Availability gate

    @Test fun availabilityNeedsFeatureAndEnabledAdapter() {
        assertThat(NfcSupport.isAvailable(hasNfcFeature = true, adapterEnabled = true)).isTrue()
        // Hardware present but the user has NFC switched off.
        assertThat(NfcSupport.isAvailable(hasNfcFeature = true, adapterEnabled = false)).isFalse()
        // No adapter at all — no hardware, or no NFC service on the device.
        assertThat(NfcSupport.isAvailable(hasNfcFeature = true, adapterEnabled = null)).isFalse()
        // Some OEM builds report the feature while handing back no adapter.
        assertThat(NfcSupport.isAvailable(hasNfcFeature = false, adapterEnabled = true)).isFalse()
        assertThat(NfcSupport.isAvailable(hasNfcFeature = false, adapterEnabled = null)).isFalse()
    }

    /** The RTD_URI identifier code in the first byte of an encoded record. */
    private fun prefixCodeOf(url: String): Int = NfcPayloadCodec.uriRecordPayload(url)[0].toInt() and 0xFF

    private fun textRecord(text: String) = NfcPayloadCodec.textRecord(text, "en")

    private fun hex(spaced: String): ByteArray {
        val digits = spaced.replace(" ", "")
        return ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    private fun String.ascii(): ByteArray = toByteArray(StandardCharsets.US_ASCII)

    /** [this] hex digits, then [text] as ASCII hex. */
    private fun String.plusAscii(text: String): String = this + text.ascii().toHex()

    /** A record as comparable values: TNF, TYPE and payload (hex). */
    private fun fields(record: NfcPayloadCodec.Record) = Triple(record.tnf, record.type.toHex(), record.payload.toHex())

    private fun fields(record: NdefRecord) = Triple(record.tnf, record.type.toHex(), record.payload.toHex())

    private class ReadFixture(val name: String, val hex: String, val expected: String?)
    private class BadFixture(val name: String, val hex: String)
    private class PrefixFixture(val uri: String, val code: Int)
    private class WriteFixture(
        val name: String,
        val kind: String,
        val value: String,
        val tnf: Int,
        val type: String,
        val payload: String,
    )
    private class InferFixture(val value: String, val kind: String, val valid: Boolean)
    private class LinkFixture(val name: String, val value: String, val uri: String, val payload: String)
    private class OutcomeFixture(val value: String, val failure: String)

    // Row for row the ones in iOS NFCTagCodecParityTests; change both together.
    private companion object {
        val readFixtures = listOf(
            ReadFixture("Smart Poster RTD 1.0 example A.1: a simple URI", "D102125370D1010E55016E66632D666F72756D2E6F7267", "http://www.nfc-forum.org"),
            ReadFixture("Smart Poster RTD 1.0 example A.2: long URI record, action, titles in en-US and fi", "D10249537081010000000E55016E66632D666F72756D2E6F7267110301616374001101125405656E2D555348656C6C6F2C20776F726C64510113540266694D6F726A656E732C206D6161696C6D61", "http://www.nfc-forum.org"),
            ReadFixture("poster with titles first (one UTF-16), a URI with an ID, action, size, type and a long-form icon", "D1027253709101135402656E5363616E206279206E6574747261736811010D54826A61FEFF30B930AD30E330F3190111015531046E657474726173682E6D652F7363616E11030161637401110104730000100011010974746578742F68746D6C420900000008696D6167652F706E6789504E470D0A1A0A", "https://nettrash.me/scan"),
            ReadFixture("poster whose URI record is chunked in three", "D1021B5370B1010955046E657474726173683600032E6D655600052F7363616E", "https://nettrash.me/scan"),
            ReadFixture("poster followed by an Android application record", "9102125370D1010E55016E66632D666F72756D2E6F7267 540F10616E64726F69642E636F6D3A706B676D652E6E657474726173682E7363616E", "http://www.nfc-forum.org"),
            ReadFixture("a poster overrides a URI record beside it (Smart Poster RTD 1.0 section 3.4)", "91010C55026578616D706C652E636F6D 5102155370D1011155046E657474726173682E6D652F7363616E", "https://nettrash.me/scan"),
            ReadFixture("a text record before a poster still comes first", "9101085402656E68656C6C6F 5102125370D1010E55016E66632D666F72756D2E6F7267", "hello"),
            ReadFixture("an unreadable poster does not hide the URI beside it", "91010C55046578616D706C652E636F6D 5102055370D101FF5504", "https://example.com"),
            ReadFixture("two URI records in a poster: the first readable one", "D102145370910102557F7851010A5504622E6578616D706C65", "https://b.example"),
            ReadFixture("a poster inside a poster is not followed", "D102175370D102125370D1010E55016E66632D666F72756D2E6F7267", null),
            ReadFixture("a poster with titles only", "D1021953709101075402656E5363616E51010A540264655363616E6E656E", null),
            ReadFixture("a poster with an empty payload", "D102005370", null),
            ReadFixture("URI 0x00: no prefix", "D1010E55006E657474726173682E6D652F78", "nettrash.me/x"),
            ReadFixture("URI 0x02: https://www.", "D1010C55026E657474726173682E6D65", "https://www.nettrash.me"),
            ReadFixture("URI 0x23: urn:nfc:, the last code", "D101055523736E3A31", "urn:nfc:sn:1"),
            ReadFixture("URI 0x24: the first reserved code", "D10102552478", null),
            ReadFixture("URI 0xFF: reserved", "D1010255FF78", null),
            ReadFixture("URI: a prefix code and nothing else", "D101015504", "https://"),
            ReadFixture("URI: empty payload", "D1010055", null),
            ReadFixture("URI: UTF-8 path kept as is", "D1010F55046578616D706C652E636F6D2FC3A4", "https://example.com/\u00E4"),
            ReadFixture("URI: an invalid UTF-8 byte", "D101025504FF", "https://\uFFFD"),
            ReadFixture("Text: UTF-8, en", "D1010B5402656E5061737320233432", "Pass #42"),
            ReadFixture("Text: UTF-8, five-byte language tag", "D101125405656E2D555348656C6C6F2C20776F726C64", "Hello, world"),
            ReadFixture("Text: no language tag", "D10105540062617265", "bare"),
            ReadFixture("Text: reserved status bit 6 set", "D101045442656E78", "x"),
            ReadFixture("Text: UTF-16 big-endian, no BOM", "D101095482656E004800692713", "Hi\u2713"),
            ReadFixture("Text: UTF-16 big-endian BOM", "D1010B5482656EFEFF004800692713", "Hi\u2713"),
            ReadFixture("Text: UTF-16 little-endian BOM (what Core NFC writes)", "D1010B5482656EFFFE480069001327", "Hi\u2713"),
            ReadFixture("Text: UTF-16 surrogate pair", "D101095482656EFFFE3DD800DE", "\uD83D\uDE00"),
            ReadFixture("Text: UTF-16 with an odd trailing byte", "D101065482656E004800", "H"),
            ReadFixture("Text: UTF-16 unpaired high surrogate", "D101075482656ED8000041", "\uFFFDA"),
            ReadFixture("Text: UTF-16 lone low surrogate", "D101075482656EDC000041", "\uFFFDA"),
            ReadFixture("Text: UTF-16 high surrogate at the end", "D101075482656E0041D800", "A\uFFFD"),
            ReadFixture("Text: UTF-16 high, high, low", "D101095482656ED800D800DC00", "\uFFFD\uD800\uDC00"),
            ReadFixture("Text: UTF-16 BOM and a lone byte", "D101065482656EFFFE48", null),
            ReadFixture("Text: language length past the end", "D10103543F656E", null),
            ReadFixture("Text: blank", "D101065402656E202020", null),
            ReadFixture("Text: only U+0085", "D101055402656EC285", null),
            ReadFixture("Text: only U+200B", "D101065402656EE2808B", null),
            ReadFixture("Text: only U+001D is not blank", "D101045402656E1D", "\u001D"),
            ReadFixture("WSC: WPA2-Personal", "D217416170706C69636174696F6E2F766E642E7766612E777363104A000110100E002E1026000101104500054775657374100300020020100F000200081027000673336372657410200006FFFFFFFFFFFF1049000600372A000120", "WIFI:T:WPA;S:Guest;P:s3cret;;"),
            ReadFixture("WSC: characters the WIFI: string escapes", "D217466170706C69636174696F6E2F766E642E7766612E777363104A000110100E003310260001011045000947756573743B4E6574100300020020100F0002000810270007733363723A657410200006FFFFFFFFFFFF1049000600372A000120", "WIFI:T:WPA;S:Guest\\;Net;P:s3cr\\:et;;"),
            ReadFixture("WSC: open", "D2173A6170706C69636174696F6E2F766E642E7766612E777363104A000110100E002710260001011045000443616665100300020001100F000200011027000010200006FFFFFFFFFFFF1049000600372A000120", "WIFI:T:nopass;S:Cafe;;"),
            ReadFixture("WSC: WPA and WPA2 mixed", "D217436170706C69636174696F6E2F766E642E7766612E777363104A000110100E00301026000101104500054D69786564100300020022100F0002000C10270008736563726574313210200006FFFFFFFFFFFF1049000600372A000120", "WIFI:T:WPA;S:Mixed;P:secret12;;"),
            ReadFixture("WSC: shared key (WEP)", "D2173E6170706C69636174696F6E2F766E642E7766612E777363104A000110100E002B1026000101104500034F6C64100300020004100F0002000210270005313233343510200006FFFFFFFFFFFF1049000600372A000120", "WIFI:T:WEP;S:Old;P:12345;;"),
            ReadFixture("WSC: WEP encryption on open authentication", "D2173E6170706C69636174696F6E2F766E642E7766612E777363104A000110100E002B1026000101104500034F6C64100300020001100F0002000210270005313233343510200006FFFFFFFFFFFF1049000600372A000120", "WIFI:T:WEP;S:Old;P:12345;;"),
            ReadFixture("WSC: no authentication flags but a key", "D217416170706C69636174696F6E2F766E642E7766612E777363104A000110100E002E10260001011045000357686F100300020000100F0002000010270008736563726574313210200006FFFFFFFFFFFF1049000600372A000120", "WIFI:T:WPA;S:Who;P:secret12;;"),
            ReadFixture("WSC: no authentication flags and no key", "D217396170706C69636174696F6E2F766E642E7766612E777363104A000110100E002610260001011045000357686F100300020000100F000200001027000010200006FFFFFFFFFFFF1049000600372A000120", "WIFI:T:nopass;S:Who;;"),
            ReadFixture("WSC: WPA2-Personal with an empty key", "D2173B6170706C69636174696F6E2F766E642E7766612E777363104A000110100E00281026000101104500054E6F4B6579100300020020100F000200081027000010200006FFFFFFFFFFFF1049000600372A000120", "WIFI:T:WPA;S:NoKey;;"),
            ReadFixture("WSC: WPA2-Enterprise", "D2173D6170706C69636174696F6E2F766E642E7766612E777363104A000110100E002A1026000101104500064F6666696365100300020010100F00020008102700017810200006FFFFFFFFFFFF1049000600372A000120", null),
            ReadFixture("WSC: media type in capitals", "D217414150504C49434154494F4E2F564E442E5746412E575343104A000110100E002E1026000101104500054775657374100300020020100F000200081027000673336372657410200006FFFFFFFFFFFF1049000600372A000120", "WIFI:T:WPA;S:Guest;P:s3cret;;"),
            ReadFixture("WSC: a repeated SSID, the last one wins", "D2171D6170706C69636174696F6E2F766E642E7766612E777363100E0019104500054669727374104500065365636F6E64100300020001", "WIFI:T:nopass;S:Second;;"),
            ReadFixture("WSC: an authentication attribute of the wrong length is ignored", "D2171E6170706C69636174696F6E2F766E642E7766612E777363100E001A104500034F646410030003002000102700087365637265743132", "WIFI:T:WPA;S:Odd;P:secret12;;"),
            ReadFixture("WSC: truncated", "D217066170706C69636174696F6E2F766E642E7766612E777363100E00FF1045", null),
            ReadFixture("WSC: no credential", "D217056170706C69636174696F6E2F766E642E7766612E777363104A000110", null),
            ReadFixture("vCard: text/vcard", "D20A31746578742F7663617264424547494E3A56434152440D0A56455253494F4E3A332E300D0A464E3A416E6120497669C4870D0A454E443A5643415244", "BEGIN:VCARD\u000D\u000AVERSION:3.0\u000D\u000AFN:Ana Ivi\u0107\u000D\u000AEND:VCARD"),
            ReadFixture("vCard: text/x-vcard", "D20C31746578742F782D7663617264424547494E3A56434152440D0A56455253494F4E3A332E300D0A464E3A416E6120497669C4870D0A454E443A5643415244", "BEGIN:VCARD\u000D\u000AVERSION:3.0\u000D\u000AFN:Ana Ivi\u0107\u000D\u000AEND:VCARD"),
            ReadFixture("vCard: capitals and a parameter", "D21B31546578742F582D56436172643B20636861727365743D7574662D38424547494E3A56434152440D0A56455253494F4E3A332E300D0A464E3A416E6120497669C4870D0A454E443A5643415244", "BEGIN:VCARD\u000D\u000AVERSION:3.0\u000D\u000AFN:Ana Ivi\u0107\u000D\u000AEND:VCARD"),
            ReadFixture("vCard: spaces and a tab around the type", "D2103120746578742F7663617264093B783D79424547494E3A56434152440D0A56455253494F4E3A332E300D0A464E3A416E6120497669C4870D0A454E443A5643415244", "BEGIN:VCARD\u000D\u000AVERSION:3.0\u000D\u000AFN:Ana Ivi\u0107\u000D\u000AEND:VCARD"),
            ReadFixture("vCard: nothing before the semicolon", "D20B313B746578742F7663617264424547494E3A56434152440D0A56455253494F4E3A332E300D0A464E3A416E6120497669C4870D0A454E443A5643415244", null),
            ReadFixture("vCard: a newline is not optional whitespace", "D20B31746578742F76636172640A424547494E3A56434152440D0A56455253494F4E3A332E300D0A464E3A416E6120497669C4870D0A454E443A5643415244", null),
            ReadFixture("media: text/plain is skipped", "D20A05746578742F706C61696E68656C6C6F", null),
            ReadFixture("media: an image is skipped", "D20904696D6167652F706E6789504E47", null),
            ReadFixture("absolute URI in TYPE", "D3170068747470733A2F2F6E657474726173682E6D652F746167", "https://nettrash.me/tag"),
            ReadFixture("absolute URI in the payload", "D3001A68747470733A2F2F6E657474726173682E6D652F6C6567616379", "https://nettrash.me/legacy"),
            ReadFixture("absolute URI in both: TYPE wins", "D3121268747470733A2F2F612E6578616D706C652F68747470733A2F2F622E6578616D706C652F", "https://a.example/"),
            ReadFixture("absolute URI with neither", "D30000", null),
            ReadFixture("the empty record", "D00000", null),
            ReadFixture("unknown TNF", "D5000568656C6C6F", null),
            ReadFixture("external type", "D40F10616E64726F69642E636F6D3A706B676D652E6E657474726173682E7363616E", null),
            ReadFixture("reserved TNF", "D7000568656C6C6F", null),
            ReadFixture("well-known type nobody defined", "D10205587868656C6C6F", null),
            ReadFixture("external record, then a URI", "940F10616E64726F69642E636F6D3A706B676D652E6E657474726173682E7363616E 51010C55046E657474726173682E6D65", "https://nettrash.me"),
            ReadFixture("blank text, then text", "9101055402656E2020 5101095402656E757361626C65", "usable"),
            ReadFixture("unknown, then a vCard, then a URI", "9500013F 120A31746578742F7663617264424547494E3A56434152440D0A56455253494F4E3A332E300D0A464E3A416E6120497669C4870D0A454E443A5643415244 51010C55046E657474726173682E6D65", "BEGIN:VCARD\u000D\u000AVERSION:3.0\u000D\u000AFN:Ana Ivi\u0107\u000D\u000AEND:VCARD"),
        )

        val malformedNestedMessages = listOf(
            BadFixture("empty", ""),
            BadFixture("a payload length past the end", "D1 01 FF 55 04"),
            BadFixture("a four-byte payload length of 2^32 - 1", "C1 01 FFFFFFFF 55 04 78"),
            BadFixture("a type length past the end", "D1 05 00 55"),
            BadFixture("an ID length past the end", "D9 01 00 05 55"),
            BadFixture("a header cut short", "91 01"),
            BadFixture("no MB on the first record", "51 01 02 55 04 78"),
            BadFixture("MB on a second record", "91 01 02 55 04 78 D1 01 02 55 04 79"),
            BadFixture("no ME before the end", "91 01 02 55 04 78"),
            BadFixture("a byte after ME", "D1 01 02 55 04 78 00"),
            BadFixture("ME on an initial chunk", "F1 01 02 55 04 78"),
            BadFixture("ME on a middle chunk", "B1 01 02 55 04 78 76 00 01 79 56 00 01 7A"),
            BadFixture("a later chunk that is not TNF 0x06", "B1 01 02 55 04 78 51 00 01 79"),
            BadFixture("TNF 0x06 outside a chunk", "D6 00 01 78"),
            BadFixture("a later chunk with a TYPE", "B1 01 02 55 04 78 56 01 01 55 79"),
            BadFixture("a later chunk with an ID length", "B1 01 02 55 04 78 5E 00 01 00 79"),
            BadFixture("an initial chunk without a TYPE", "B1 00 02 04 78 56 00 01 79"),
            BadFixture("an empty record with a payload", "D0 00 01 78"),
            BadFixture("an empty record with an ID", "D8 00 00 01 78"),
            BadFixture("an unknown record with a TYPE", "D5 01 01 55 78"),
            BadFixture("a reserved record with a TYPE", "D7 01 01 55 78"),
            BadFixture("MB on a middle chunk", "B1 01 02 55 04 78 B6 00 01 79 56 00 01 7A"),
        )

        val uriPrefixFixtures = listOf(
            PrefixFixture("https://www.nettrash.me", 0x02),
            PrefixFixture("https://nettrash.me", 0x04),
            PrefixFixture("http://www.example.com", 0x01),
            PrefixFixture("http://example.com", 0x03),
            PrefixFixture("tel:+15551234", 0x05),
            PrefixFixture("mailto:a@b.c", 0x06),
            PrefixFixture("ftp://anonymous:anonymous@x", 0x07),
            PrefixFixture("ftp://ftp.x.org", 0x08),
            PrefixFixture("ftps://x", 0x09),
            PrefixFixture("sftp://x", 0x0A),
            PrefixFixture("smb://x", 0x0B),
            PrefixFixture("nfs://x", 0x0C),
            PrefixFixture("ftp://x.org", 0x0D),
            PrefixFixture("dav://x", 0x0E),
            PrefixFixture("news:x", 0x0F),
            PrefixFixture("telnet://x", 0x10),
            PrefixFixture("imap:x", 0x11),
            PrefixFixture("rtsp://x", 0x12),
            PrefixFixture("urn:x", 0x13),
            PrefixFixture("pop:x", 0x14),
            PrefixFixture("sip:x", 0x15),
            PrefixFixture("sips:x", 0x16),
            PrefixFixture("tftp:x", 0x17),
            PrefixFixture("btspp://x", 0x18),
            PrefixFixture("btl2cap://x", 0x19),
            PrefixFixture("btgoep://x", 0x1A),
            PrefixFixture("tcpobex://x", 0x1B),
            PrefixFixture("irdaobex://x", 0x1C),
            PrefixFixture("file:///x", 0x1D),
            PrefixFixture("urn:epc:id:x", 0x1E),
            PrefixFixture("urn:epc:tag:x", 0x1F),
            PrefixFixture("urn:epc:pat:x", 0x20),
            PrefixFixture("urn:epc:raw:x", 0x21),
            PrefixFixture("urn:epc:x", 0x22),
            PrefixFixture("urn:nfc:x", 0x23),
            PrefixFixture("ftp://ftp.", 0x08),
            PrefixFixture("HTTPS://X.ORG", 0x00),
            PrefixFixture("nettrash.me", 0x00),
            PrefixFixture("https:/x", 0x00),
            PrefixFixture("", 0x00),
        )

        val writeFixtures = listOf(
            WriteFixture("a URL, abbreviated", "url", "https://www.nettrash.me/scan", 0x01, "U", "026E657474726173682E6D652F7363616E"),
            WriteFixture("a URL, trimmed first", "url", "  https://nettrash.me \u000A", 0x01, "U", "046E657474726173682E6D65"),
            WriteFixture("a URL behind a zero-width space", "url", "\u200Bhttps://nettrash.me", 0x01, "U", "046E657474726173682E6D65"),
            WriteFixture("text, as UTF-8 with \"en\"", "text", "Pass #42 \u2014 gate B", 0x01, "T", "02656E506173732023343220E2809420676174652042"),
            WriteFixture("text keeps its spaces", "text", "  hi  ", 0x01, "T", "02656E202068692020"),
            WriteFixture("a WPA network", "wifi", "WIFI:T:WPA;S:Guest;P:s3cret;;", 0x02, "application/vnd.wfa.wsc", "104A000110100E002E1026000101104500054775657374100300020020100F000200081027000673336372657410200006FFFFFFFFFFFF1049000600372A000120"),
            WriteFixture("a WPA2 network with escapes", "wifi", "WIFI:T:WPA2;S:Guest\\;Net;P:s3cr\\:et;;", 0x02, "application/vnd.wfa.wsc", "104A000110100E003310260001011045000947756573743B4E6574100300020020100F0002000810270007733363723A657410200006FFFFFFFFFFFF1049000600372A000120"),
            WriteFixture("an open network", "wifi", "WIFI:T:nopass;S:Cafe;;", 0x02, "application/vnd.wfa.wsc", "104A000110100E002710260001011045000443616665100300020001100F000200011027000010200006FFFFFFFFFFFF1049000600372A000120"),
            WriteFixture("an open network drops a stray password", "wifi", "WIFI:T:nopass;S:Cafe;P:ignored;;", 0x02, "application/vnd.wfa.wsc", "104A000110100E002710260001011045000443616665100300020001100F000200011027000010200006FFFFFFFFFFFF1049000600372A000120"),
            WriteFixture("no type and no password is open", "wifi", "WIFI:S:Cafe;;", 0x02, "application/vnd.wfa.wsc", "104A000110100E002710260001011045000443616665100300020001100F000200011027000010200006FFFFFFFFFFFF1049000600372A000120"),
            WriteFixture("a WPA3 network stays text", "wifi", "WIFI:T:SAE;S:Home;P:longpassword;;", 0x01, "T", "02656E574946493A543A5341453B533A486F6D653B503A6C6F6E6770617373776F72643B3B"),
            WriteFixture("a WEP network stays text", "wifi", "WIFI:T:WEP;S:Old;P:12345;;", 0x01, "T", "02656E574946493A543A5745503B533A4F6C643B503A31323334353B3B"),
            WriteFixture("a Passpoint network stays text", "wifi", "WIFI:T:HS20;S:Air;P:x;;", 0x01, "T", "02656E574946493A543A485332303B533A4169723B503A783B3B"),
            WriteFixture("a hidden network stays text", "wifi", "WIFI:T:WPA;S:Hid;P:longpassword;H:true;;", 0x01, "T", "02656E574946493A543A5750413B533A4869643B503A6C6F6E6770617373776F72643B483A747275653B3B"),
            WriteFixture("a WPA without a password network stays text", "wifi", "WIFI:T:WPA;S:Net;;", 0x01, "T", "02656E574946493A543A5750413B533A4E65743B3B"),
            WriteFixture("a no type with a password network stays text", "wifi", "WIFI:S:Net;P:secret12;;", 0x01, "T", "02656E574946493A533A4E65743B503A73656372657431323B3B"),
            WriteFixture("a a 33-byte SSID network stays text", "wifi", "WIFI:T:WPA;S:xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx;P:secret12;;", 0x01, "T", "02656E574946493A543A5750413B533A7878787878787878787878787878787878787878787878787878787878787878783B503A73656372657431323B3B"),
            WriteFixture("a a 65-byte key network stays text", "wifi", "WIFI:T:WPA;S:Net;P:kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk;;", 0x01, "T", "02656E574946493A543A5750413B533A4E65743B503A6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B3B3B"),
            WriteFixture("a 32-byte SSID and a 64-byte key still fit", "wifi", "WIFI:T:WPA;S:ssssssssssssssssssssssssssssssss;P:kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk;;", 0x02, "application/vnd.wfa.wsc", "104A000110100E00831026000101104500207373737373737373737373737373737373737373737373737373737373737373100300020020100F00020008102700406B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B6B10200006FFFFFFFFFFFF1049000600372A000120"),
            WriteFixture("a vCard", "contact", "BEGIN:VCARD\u000D\u000AVERSION:3.0\u000D\u000AFN:Ana Ivi\u0107\u000D\u000AEND:VCARD", 0x02, "text/vcard", "424547494E3A56434152440D0A56455253494F4E3A332E300D0A464E3A416E6120497669C4870D0A454E443A5643415244"),
            WriteFixture("a vCard with a combining mark after BEGIN:VCARD", "contact", "BEGIN:VCARD\u0301\u000D\u000AEND:VCARD", 0x02, "text/vcard", "424547494E3A5643415244CC810D0A454E443A5643415244"),
            WriteFixture("a MECARD stays text", "contact", "MECARD:N:Doe,Jane;;", 0x01, "T", "02656E4D45434152443A4E3A446F652C4A616E653B3B"),
        )

        val inferenceFixtures = listOf(
            InferFixture("\u200Bhttps://nettrash.me", "url", true),
            InferFixture(" \u0009\u000Ahttp://x ", "url", true),
            InferFixture("\u0085WIFI:T:WPA;S:x;P:y;;", "wifi", true),
            InferFixture("\u3000begin:vcard\u000AEND:VCARD", "contact", true),
            InferFixture("\u001Dhttps://nettrash.me", "text", true),
            InferFixture("\u001FBEGIN:VCARD\u000AEND:VCARD", "text", true),
            InferFixture("httpd is down", "text", true),
            InferFixture("mailto:hi@example.com", "url", true),
            InferFixture("\u200B \u0085", "text", false),
            InferFixture("\u001D", "text", true),
            // The links besides the web's (LINK_PREFIXES), in any case...
            InferFixture("tel:+15551234", "url", true),
            InferFixture("SMSTO:+15551234:Hi", "url", true),
            InferFixture("\u00A0Geo:44.8125,20.4612", "url", true),
            InferFixture("Tel: +1 555 1234", "url", true),
            // ...and lookalikes, which stay text.
            InferFixture("telnet://x", "text", true),
            InferFixture("geology: rocks", "text", true),
            InferFixture("sms", "text", true),
            // A prefix is matched char by char, as iOS matches it byte by
            // byte: a combining mark straight after it \u2014 on the colon, even \u2014
            // doesn't hide it.
            InferFixture("tel:\u03015551234", "url", true),
            InferFixture("https://\u0301x", "url", true),
            InferFixture("BEGIN:VCARD\u0301\u000AEND:VCARD", "contact", true),
            // Nothing but whitespace can't be written, so the Write button is off.
            InferFixture("", "text", false),
            InferFixture(" \u000A\u0009\u3000\u2028", "text", false),
        )

        val linkFixtures = listOf(
            LinkFixture("a phone number: tel: is code 0x05", "tel:+15551234", "tel:+15551234", "052B3135353531323334"),
            LinkFixture("an email: mailto: is code 0x06", "mailto:hi@example.com?subject=Hi%20there", "mailto:hi@example.com?subject=Hi%20there", "066869406578616D706C652E636F6D3F7375626A6563743D48692532307468657265"),
            LinkFixture("an SMS: sms: has no code, so it is written whole", "sms:+15551234?body=Hi", "sms:+15551234?body=Hi", "00736D733A2B31353535313233343F626F64793D4869"),
            LinkFixture("an SMS as smsto:, written whole", "smsto:+15551234:Hi", "smsto:+15551234:Hi", "00736D73746F3A2B31353535313233343A4869"),
            LinkFixture("a place: geo: has no code, so it is written whole", "geo:44.8125,20.4612", "geo:44.8125,20.4612", "0067656F3A34342E383132352C32302E34363132"),
            LinkFixture("a scheme in capitals is a link too, written as typed", "TEL:+15551234", "TEL:+15551234", "0054454C3A2B3135353531323334"),
            LinkFixture("a scheme in mixed case, written as typed", "MailTo:hi@example.com", "MailTo:hi@example.com", "004D61696C546F3A6869406578616D706C652E636F6D"),
            LinkFixture("trimmed, like a web link", " \u000Ageo:0,0?q=Belgrade\u0009 ", "geo:0,0?q=Belgrade", "0067656F3A302C303F713D42656C6772616465"),
            LinkFixture("a combining mark straight after the colon", "tel:\u03015551234", "tel:\u03015551234", "05CC8135353531323334"),
        )

        val outcomeFixtures = listOf(
            OutcomeFixture(" \u000A", "Nothing to write yet \u2014 fill in the content first."),
            OutcomeFixture("tel:+15551234", "This device can't write NFC tags."),
        )
    }
}
