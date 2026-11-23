package me.nettrash.scan.nfc

import me.nettrash.scan.data.payload.ScanPayload
import me.nettrash.scan.data.payload.ScanPayloadParser
import me.nettrash.scan.generator.CodeComposer
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Pure NDEF <-> text conversion, with no `android.nfc` types in sight.
 *
 * Everything [NfcTagWriter] does with the framework (reader mode, `Tag`,
 * `Ndef.writeNdefMessage`) is plumbing that can only be exercised on a real
 * device; *what* goes onto the tag and *what* comes back off it is plain
 * byte twiddling, so it lives here where it can be tested on the JVM.
 * Byte-for-byte the port of `NDEFRecordCodec` + `WiFiSimpleConfig` in
 * `Scan/NFCTagWriter.swift`: a tag written by either app must read back the
 * same on both.
 *
 * Records, per the NFC Forum NDEF 1.0, RTD Text 1.0, RTD URI 1.0 and Smart
 * Poster RTD 1.0 specifications, plus the two MIME media records Scan writes:
 *  - RTD Text (`T`) — a status byte, an ASCII language tag and UTF-8 text
 *    (UTF-16 is read too);
 *  - RTD URI (`U`) — a one-byte prefix code into the NFC Forum prefix table,
 *    then the rest of the URI;
 *  - Smart Poster (`Sp`, read only) — a nested message whose URI record is
 *    what the poster means;
 *  - `application/vnd.wfa.wsc` — a Wi-Fi Simple Configuration token, which
 *    is what makes an Android phone offer to join the network on tap;
 *  - `text/vcard` (and the older `text/x-vcard`) — a contact card;
 *  - absolute-URI records, which carry the URI in their TYPE field.
 *
 * Anything else (Android application records, external types) is skipped
 * rather than guessed at.
 */
object NfcPayloadCodec {

    // NDEF 1.0 type name formats. The same numbers as
    // `android.nfc.NdefRecord.TNF_*` (and Core NFC's `NFCTypeNameFormat`) —
    // NfcPayloadCodecTest pins them against the framework constants, because a
    // record built with the wrong TNF is rejected by `NdefRecord`'s
    // constructor and the whole write fails.
    const val TNF_EMPTY: Short = 0x00
    const val TNF_WELL_KNOWN: Short = 0x01
    const val TNF_MIME_MEDIA: Short = 0x02
    const val TNF_ABSOLUTE_URI: Short = 0x03
    const val TNF_EXTERNAL_TYPE: Short = 0x04
    const val TNF_UNKNOWN: Short = 0x05

    /** Only in the middle and last chunks of a chunked payload. */
    const val TNF_UNCHANGED: Short = 0x06
    const val TNF_RESERVED: Short = 0x07

    /** Well-known type names for the RTD records we speak. */
    const val RTD_TEXT: String = "T"
    const val RTD_URI: String = "U"
    const val RTD_SMART_POSTER: String = "Sp"

    /**
     * The most records [parseMessage] reads from one message — iOS
     * `maxMessageRecords`. A Smart Poster is one URI plus a little metadata
     * (a title per language, an action, a size, a type, an icon), so a poster
     * with more is rejected rather than read on, which bounds what a hostile
     * tag can make the parser allocate. The NDEF spec lets a parser refuse
     * messages beyond its design limits (NDEF 1.0 §4.3).
     */
    const val MAX_MESSAGE_RECORDS: Int = 255

    const val WIFI_MEDIA_TYPE: String = "application/vnd.wfa.wsc"
    const val VCARD_MEDIA_TYPE: String = "text/vcard"

    /** Older name for the same thing; still what some writers emit. */
    const val LEGACY_VCARD_MEDIA_TYPE: String = "text/x-vcard"

    /** NFC Forum RTD URI identifier codes 0x00..0x23; the index is the code. */
    private val URI_PREFIXES = listOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:",
        "mailto:", "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://",
        "sftp://", "smb://", "nfs://", "ftp://", "dav://", "news:",
        "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:", "sips:",
        "tftp:", "btspp://", "btl2cap://", "btgoep://", "tcpobex://",
        "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:",
        "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:",
    )

    /** Language tags are a 6-bit field, so 63 ASCII bytes is the hard cap. */
    private const val MAX_LANGUAGE_TAG_BYTES = 63

    /**
     * One record as it sits on the tag, minus the header flags the framework
     * adds when it frames the message: the type name format, the raw TYPE
     * field (`T`, `U`, a MIME type, an absolute URI) and the payload.
     */
    class Record(
        val tnf: Short,
        val type: ByteArray,
        val payload: ByteArray,
    ) {
        /** [type] read as ASCII — what the well-known and MIME checks compare. */
        val typeName: String get() = String(type, StandardCharsets.US_ASCII)
    }

    // MARK: - Write side

    /**
     * The records to put on a tag for [request], or null when it can't be
     * encoded (which the writer reports as "Could not encode the tag
     * content."). One record per tag: the first record sets the meaning of an
     * NDEF message, and a second one would only eat into a small tag.
     */
    fun recordsFor(request: NfcTagWriteRequest): List<Record>? {
        if (!request.isValid) return null
        val value = request.value
        val record = when (request.kind) {
            NfcTagPayloadKind.Url -> uriRecord(normalizedUrl(value))
            NfcTagPayloadKind.Text -> textRecord(value, request.languageTag)
            // A Wi-Fi Simple Configuration token is what makes an Android
            // phone offer to join the network on tap. Networks WSC can't
            // describe faithfully keep the QR's own `WIFI:` string, which Scan
            // reads back as Wi-Fi on either platform.
            NfcTagPayloadKind.Wifi -> WifiSimpleConfig.token(value)
                ?.let { mediaRecord(WIFI_MEDIA_TYPE, it) }
                ?: textRecord(value, request.languageTag)
            // A vCard as `text/vcard` is the contact card phones import on
            // tap; anything else rides as text, like its QR would.
            NfcTagPayloadKind.Contact -> if (looksLikeVCard(value)) {
                mediaRecord(VCARD_MEDIA_TYPE, value.toByteArray(StandardCharsets.UTF_8))
            } else {
                textRecord(value, request.languageTag)
            }
        }
        return listOf(record)
    }

    /** RTD Text: status byte (bit 7 clear = UTF-8, bits 5..0 = language length), tag, text. */
    fun textRecord(text: String, languageTag: String): Record =
        Record(TNF_WELL_KNOWN, RTD_TEXT.ascii(), textRecordPayload(text, languageTag))

    /** RTD URI: the code of the longest prefix the URI starts with, then the rest. */
    fun uriRecord(uri: String): Record =
        Record(TNF_WELL_KNOWN, RTD_URI.ascii(), uriRecordPayload(uri))

    fun mediaRecord(mediaType: String, payload: ByteArray): Record =
        Record(TNF_MIME_MEDIA, mediaType.ascii(), payload)

    /** The RTD Text payload alone. */
    fun textRecordPayload(text: String, languageTag: String): ByteArray {
        val language = normalizeLanguageTag(languageTag).toByteArray(StandardCharsets.US_ASCII)
        val body = text.toByteArray(StandardCharsets.UTF_8)
        val status = (language.size and 0x3F).toByte()
        return byteArrayOf(status) + language + body
    }

    /** Inverse of [textRecordPayload], accepting the UTF-16 form too; null when malformed. */
    fun textFromRecordPayload(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val status = payload[0].toInt() and 0xFF
        val languageLength = status and 0x3F
        if (payload.size < 1 + languageLength) return null
        return if ((status and 0x80) != 0) {
            utf16String(payload, 1 + languageLength)
        } else {
            String(payload, 1 + languageLength, payload.size - 1 - languageLength, StandardCharsets.UTF_8)
        }
    }

    /** The RTD URI payload alone. */
    fun uriRecordPayload(url: String): ByteArray {
        val (code, prefix) = longestPrefixFor(url)
        val rest = url.substring(prefix.length).toByteArray(StandardCharsets.UTF_8)
        return byteArrayOf(code.toByte()) + rest
    }

    /** Inverse of [uriRecordPayload]; null for a reserved identifier code. */
    fun uriFromRecordPayload(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val code = payload[0].toInt() and 0xFF
        // 0x00 (no prefix) is legal; 0x24..0xFF are reserved.
        if (code >= URI_PREFIXES.size) return null
        val rest = String(payload, 1, payload.size - 1, StandardCharsets.UTF_8)
        return URI_PREFIXES[code] + rest
    }

    // MARK: - Read side

    /**
     * The string a camera scan of the same content would have produced: the
     * first record Scan can read, in record order (NDEF's first record sets
     * what a message means), or null when there is none. One exception, from
     * the Smart Poster spec (§3.4): a readable Smart Poster overrides any URI
     * records beside it, so those are passed over. Mirrors iOS
     * `readableString(in:)`.
     */
    fun readableString(records: List<Record>): String? {
        val hasPoster = records.any { smartPosterUri(it) != null }
        return records.asSequence()
            .filterNot { hasPoster && it.tnf == TNF_WELL_KNOWN && it.typeName == RTD_URI }
            .firstNotNullOfOrNull { readableString(it) }
    }

    /** The readable string for one record, or null when it's skipped. */
    fun readableString(record: Record): String? {
        val text = when (record.tnf) {
            TNF_WELL_KNOWN -> when (record.typeName) {
                RTD_TEXT -> textFromRecordPayload(record.payload)
                RTD_URI -> uriFromRecordPayload(record.payload)
                RTD_SMART_POSTER -> smartPosterUri(record)
                else -> null
            }
            // NDEF 1.0 puts an absolute URI in the TYPE field; a few writers
            // put it in the payload instead.
            TNF_ABSOLUTE_URI -> {
                val field = if (record.type.isEmpty()) record.payload else record.type
                String(field, StandardCharsets.UTF_8)
            }
            TNF_MIME_MEDIA -> when (mediaType(record)) {
                WIFI_MEDIA_TYPE -> WifiSimpleConfig.wifiString(record.payload)
                VCARD_MEDIA_TYPE, LEGACY_VCARD_MEDIA_TYPE -> String(record.payload, StandardCharsets.UTF_8)
                else -> null
            }
            else -> null
        }
        return text?.takeIf { trimWhitespace(it).isNotEmpty() }
    }

    /**
     * What a Smart Poster (well-known type `Sp`, NFC Forum Smart Poster RTD
     * 1.0) stands for: its URI. The payload is a nested NDEF message of one
     * URI record plus optional titles (`T`, one per language), an action
     * (`act`), a size (`s`), a type (`t`) and icons; only the URI is the
     * value, so a poster tag reads exactly like a plain link tag. A title is
     * display text the tag's author chose — the spec itself warns it can dress
     * one link up as another — so it never becomes the value. Only URI records
     * count inside, which keeps nesting at one level: a poster inside a poster
     * is not followed. Null for anything but a poster, and for a poster whose
     * payload isn't a well-formed message or holds no readable URI record.
     * iOS `smartPosterURI(in:)`.
     */
    fun smartPosterUri(record: Record): String? {
        if (record.tnf != TNF_WELL_KNOWN || record.typeName != RTD_SMART_POSTER) return null
        val nested = parseMessage(record.payload) ?: return null
        return nested.asSequence()
            .filter { it.tnf == TNF_WELL_KNOWN && it.typeName == RTD_URI }
            .firstNotNullOfOrNull { readableString(it) }
    }

    /**
     * The records of one raw NDEF message, or null unless [message] is exactly
     * one well-formed message (NFC Forum NDEF 1.0 §2.3, §3.2): MB on the first
     * record only and ME on the last, nothing after it, every length inside
     * the buffer (a length is never trusted for an allocation), chunked
     * payloads (§2.3.3) reassembled, and the TNF rules of §3.2.6 — the checks
     * the framework's `NdefMessage(byte[])` makes (NfcPayloadCodecTest holds
     * the two side by side), here without `android.nfc` and identical to iOS
     * `records(inMessage:)`. The framework splits the tag's own message; this
     * reads the one nested in a Smart Poster. At most [MAX_MESSAGE_RECORDS]
     * records, chunks included.
     */
    fun parseMessage(message: ByteArray): List<Record>? {
        var index = 0
        /** The next [count] bytes, or null when fewer are left. */
        fun take(count: Long): ByteArray? {
            if (message.size - index < count) return null
            val start = index
            index += count.toInt()
            return message.copyOfRange(start, index)
        }
        fun byte(): Int? = take(1)?.let { it[0].toInt() and 0xFF }

        val records = mutableListOf<Record>()
        // A chunked payload being reassembled: the initial chunk's TNF and
        // TYPE, the payload so far, and whether that chunk carried an ID.
        var chunk: Record? = null
        var chunkPayload = ByteArrayOutputStream()
        var chunkHasId = false
        var physicalRecords = 0
        var ended = false
        while (!ended) {
            if (++physicalRecords > MAX_MESSAGE_RECORDS) return null
            val header = byte() ?: return null
            val begins = header and 0x80 != 0
            ended = header and 0x40 != 0
            val chunked = header and 0x20 != 0
            val short = header and 0x10 != 0
            val hasIdLength = header and 0x08 != 0
            val tnf = (header and 0x07).toShort()
            val continuing = chunk != null
            if (begins != (records.isEmpty() && !continuing)) return null
            // A chunked payload ends inside the message (§2.3.3), its later
            // chunks — and only they — are "unchanged", and they carry
            // neither a TYPE nor an ID.
            if (chunked && ended) return null
            if (continuing != (tnf == TNF_UNCHANGED)) return null
            if (continuing && hasIdLength) return null
            val typeLength = byte() ?: return null
            // Big-endian; up to 2^32 - 1, which take() checks against what is
            // actually left.
            val payloadLength = take(if (short) 1 else 4)
                ?.fold(0L) { length, next -> (length shl 8) or (next.toLong() and 0xFF) } ?: return null
            val idLength = if (hasIdLength) byte() ?: return null else 0
            if (continuing && typeLength != 0) return null
            val type = take(typeLength.toLong()) ?: return null
            val id = take(idLength.toLong()) ?: return null
            val payload = take(payloadLength) ?: return null

            val head = chunk ?: Record(tnf, type, ByteArray(0))
            if (!continuing) {
                chunkPayload = ByteArrayOutputStream()
                chunkHasId = id.isNotEmpty()
            }
            chunkPayload.write(payload)
            if (chunked) {
                // The initial chunk names the type of the whole payload.
                if (!continuing && typeLength == 0 && tnf != TNF_UNKNOWN) return null
                chunk = head
                continue
            }
            chunk = null
            val record = Record(head.tnf, head.type, chunkPayload.toByteArray())
            when (record.tnf) {
                TNF_EMPTY -> if (record.type.isNotEmpty() || chunkHasId || record.payload.isNotEmpty()) return null
                TNF_UNKNOWN, TNF_RESERVED -> if (record.type.isNotEmpty()) return null
            }
            records += record
        }
        return if (index == message.size) records else null
    }

    // MARK: - Helpers

    /**
     * Whether [value] already carries a scheme: RFC 3986 says a letter, then
     * letters, digits, `+`, `-`, `.` — ASCII only, as on iOS — so `"12:30"`
     * is text, not a URL.
     */
    fun hasScheme(value: String): Boolean {
        val colon = value.indexOf(':')
        if (colon <= 0) return false
        val scheme = value.substring(0, colon)
        return scheme.first().isAsciiLetter() &&
            scheme.all { it.isAsciiLetter() || it in '0'..'9' || it == '+' || it == '-' || it == '.' }
    }

    /** Prepend `https://` when the content is a bare host. */
    fun normalizedUrl(value: String): String {
        val trimmed = trimWhitespace(value)
        return if (hasScheme(trimmed)) trimmed else "https://$trimmed"
    }

    /**
     * [value] less the leading and trailing characters iOS trims — Swift's
     * `CharacterSet.whitespacesAndNewlines` — rather than Kotlin's `trim()`,
     * which also strips U+001C..U+001F but keeps U+0085 and the zero-width
     * space U+200B. One set on both ports is what makes the same string pick
     * the same record: a link pasted with a zero-width space in front is a
     * link on both.
     */
    fun trimWhitespace(value: String): String = value.trim { it.isIosWhitespace() }

    /** Lowercased, ASCII-only, length-capped language tag; `en` when nothing usable. */
    fun normalizeLanguageTag(languageTag: String): String {
        val cleaned = trimWhitespace(languageTag)
            .takeWhile { it.isAsciiLetter() || it == '-' }
            .lowercase(Locale.ROOT)
            .take(MAX_LANGUAGE_TAG_BYTES)
        return cleaned.ifEmpty { "en" }
    }

    private fun looksLikeVCard(value: String): Boolean =
        trimWhitespace(value).lowercase(Locale.ROOT).startsWith("begin:vcard")

    /**
     * A media record's type without parameters — everything before the first
     * `;`, less the spaces and tabs RFC 9110 allows around it — lowercased.
     * iOS cuts at the same byte and trims the same two characters.
     */
    private fun mediaType(record: Record): String =
        record.typeName.substringBefore(';').trim(' ', '\t').lowercase(Locale.ROOT)

    /**
     * RTD Text's UTF-16 form from [from] on, decoded exactly as iOS
     * `utf16String` does — not with Java's "UTF-16" charset, which swallows
     * the unit after an unpaired high surrogate and turns an odd trailing
     * byte into U+FFFD, and whose behaviour differs again under ART. A
     * byte-order mark picks the byte order and is dropped; without one the
     * text is big-endian (RTD Text 1.0). An odd last byte is ignored, and
     * each unpaired surrogate becomes one U+FFFD.
     */
    private fun utf16String(bytes: ByteArray, from: Int): String {
        var start = from
        var bigEndian = true
        if (bytes.size - start >= 2) {
            val first = bytes[start].toInt() and 0xFF
            val second = bytes[start + 1].toInt() and 0xFF
            if (first == 0xFE && second == 0xFF) {
                start += 2
            } else if (first == 0xFF && second == 0xFE) {
                bigEndian = false
                start += 2
            }
        }
        val units = CharArray((bytes.size - start) / 2) {
            val high = bytes[start + 2 * it].toInt() and 0xFF
            val low = bytes[start + 2 * it + 1].toInt() and 0xFF
            (if (bigEndian) (high shl 8) or low else (low shl 8) or high).toChar()
        }
        val text = StringBuilder(units.size)
        var index = 0
        while (index < units.size) {
            val unit = units[index]
            if (unit.isHighSurrogate() && index + 1 < units.size && units[index + 1].isLowSurrogate()) {
                text.append(unit).append(units[index + 1])
                index += 2
                continue
            }
            text.append(if (unit.isSurrogate()) '\uFFFD' else unit)
            index += 1
        }
        return text.toString()
    }

    /** Membership in Swift's `whitespacesAndNewlines` (Apple's set includes U+200B). */
    private fun Char.isIosWhitespace(): Boolean = this in '\u0009'..'\u000D' ||
        this == ' ' || this == '\u0085' || this == '\u00A0' || this == '\u1680' ||
        this in '\u2000'..'\u200B' || this == '\u2028' || this == '\u2029' ||
        this == '\u202F' || this == '\u205F' || this == '\u3000'

    /** Highest-coverage prefix code whose prefix actually starts the URL. */
    private fun longestPrefixFor(url: String): Pair<Int, String> {
        var bestCode = 0
        var bestPrefix = ""
        URI_PREFIXES.forEachIndexed { code, prefix ->
            if (prefix.length > bestPrefix.length && url.startsWith(prefix)) {
                bestCode = code
                bestPrefix = prefix
            }
        }
        return bestCode to bestPrefix
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    private fun String.ascii(): ByteArray = toByteArray(StandardCharsets.US_ASCII)
}

/**
 * The Wi-Fi Simple Configuration (WSC 2.0) NFC configuration token: the
 * `application/vnd.wfa.wsc` payload Android's NFC service turns into a
 * "Connect to this network?" prompt. A run of big-endian type-length-value
 * attributes: Version, Credential { Network Index, SSID, Authentication
 * Type, Encryption Type, Network Key, MAC Address }, and the Wi-Fi Alliance
 * vendor extension that carries Version2. Port of iOS `WiFiSimpleConfig`.
 */
object WifiSimpleConfig {

    // Attribute types.
    private const val VERSION = 0x104A
    private const val CREDENTIAL = 0x100E
    private const val NETWORK_INDEX = 0x1026
    private const val SSID = 0x1045
    private const val AUTHENTICATION = 0x1003
    private const val ENCRYPTION = 0x100F
    private const val NETWORK_KEY = 0x1027
    private const val MAC_ADDRESS = 0x1020
    private const val VENDOR_EXTENSION = 0x1049

    // Authentication type flags.
    private const val AUTH_OPEN = 0x0001
    private const val AUTH_WPA_PERSONAL = 0x0002
    private const val AUTH_SHARED = 0x0004
    private const val AUTH_WPA_ENTERPRISE = 0x0008
    private const val AUTH_WPA2_ENTERPRISE = 0x0010
    private const val AUTH_WPA2_PERSONAL = 0x0020

    // Encryption type flags.
    private const val ENCRYPTION_NONE = 0x0001
    private const val ENCRYPTION_WEP = 0x0002
    private const val ENCRYPTION_AES = 0x0008

    /** Version 1.0, kept for older readers; WSC 2.0 moved the real version below. */
    private val LEGACY_VERSION = byteArrayOf(0x10)

    /** Wi-Fi Alliance vendor id 00:37:2A, then sub-element Version2 (id 0, length 1) = 2.0. */
    private val VERSION2_EXTENSION = byteArrayOf(0x00, 0x37, 0x2A, 0x00, 0x01, 0x20)

    /** A tag doesn't know the access point's BSSID; the broadcast address is the conventional "any". */
    private val ANY_MAC_ADDRESS = ByteArray(6) { 0xFF.toByte() }

    /**
     * The token for a `WIFI:` string, or null when WSC can't say it
     * faithfully: WEP and WPA3 (SAE) networks, Passpoint, a hidden SSID
     * (there is no attribute for it), an SSID over 32 bytes or a key over 64.
     * Those keep the plain-text form.
     */
    fun token(wifiString: String): ByteArray? {
        val wifi = runCatching { ScanPayloadParser.parse(wifiString) }.getOrNull() as? ScanPayload.Wifi
            ?: return null
        if (wifi.hidden) return null
        val key = wifi.password.orEmpty()
        val authentication: Int
        val encryption: Int
        when (wifi.security?.uppercase(Locale.ROOT)) {
            "WPA", "WPA2" -> {
                if (key.isEmpty()) return null
                authentication = AUTH_WPA2_PERSONAL
                encryption = ENCRYPTION_AES
            }
            "NOPASS" -> {
                authentication = AUTH_OPEN
                encryption = ENCRYPTION_NONE
            }
            // No type and no password is an open network; no type with a
            // password is ambiguous, so it stays text.
            null, "" -> {
                if (key.isNotEmpty()) return null
                authentication = AUTH_OPEN
                encryption = ENCRYPTION_NONE
            }
            else -> return null
        }
        val ssidBytes = wifi.ssid.toByteArray(StandardCharsets.UTF_8)
        val keyBytes = if (authentication == AUTH_OPEN) ByteArray(0) else key.toByteArray(StandardCharsets.UTF_8)
        if (ssidBytes.size !in 1..32 || keyBytes.size > 64) return null

        val credential = tlv(NETWORK_INDEX, byteArrayOf(0x01)) +
            tlv(SSID, ssidBytes) +
            tlv(AUTHENTICATION, uint16(authentication)) +
            tlv(ENCRYPTION, uint16(encryption)) +
            tlv(NETWORK_KEY, keyBytes) +
            tlv(MAC_ADDRESS, ANY_MAC_ADDRESS)
        return tlv(VERSION, LEGACY_VERSION) +
            tlv(CREDENTIAL, credential) +
            tlv(VENDOR_EXTENSION, VERSION2_EXTENSION)
    }

    /**
     * The `WIFI:` string for a token's first credential, or null when the
     * token is malformed or holds an enterprise (EAP) network, whose sign-in
     * a `WIFI:` string can't carry.
     */
    fun wifiString(token: ByteArray): String? {
        val credential = attributes(token, 0, token.size)
            ?.firstOrNull { it.type == CREDENTIAL } ?: return null
        val fields = attributes(token, credential.start, credential.end) ?: return null

        var ssid = ""
        var key = ""
        var authentication = 0
        var encryption = 0
        for (field in fields) {
            when {
                field.type == SSID -> ssid = field.string(token)
                field.type == NETWORK_KEY -> key = field.string(token)
                field.type == AUTHENTICATION && field.length == 2 -> authentication = field.uint16(token)
                field.type == ENCRYPTION && field.length == 2 -> encryption = field.uint16(token)
            }
        }
        if (ssid.isEmpty()) return null

        val security = when {
            authentication and (AUTH_WPA_PERSONAL or AUTH_WPA2_PERSONAL) != 0 ->
                CodeComposer.WifiSecurity.WPA
            authentication and (AUTH_WPA_ENTERPRISE or AUTH_WPA2_ENTERPRISE) != 0 -> return null
            authentication and AUTH_SHARED != 0 || encryption == ENCRYPTION_WEP ->
                CodeComposer.WifiSecurity.WEP
            authentication and AUTH_OPEN != 0 -> CodeComposer.WifiSecurity.OPEN
            else -> if (key.isEmpty()) CodeComposer.WifiSecurity.OPEN else CodeComposer.WifiSecurity.WPA
        }
        return CodeComposer.wifi(ssid = ssid, password = key, security = security, hidden = false)
    }

    // MARK: - TLV

    private class Attribute(val type: Int, val start: Int, val end: Int) {
        val length: Int get() = end - start
        fun string(source: ByteArray): String = String(source, start, length, StandardCharsets.UTF_8)
        fun uint16(source: ByteArray): Int =
            ((source[start].toInt() and 0xFF) shl 8) or (source[start + 1].toInt() and 0xFF)
    }

    private fun tlv(type: Int, value: ByteArray): ByteArray = uint16(type) + uint16(value.size) + value

    private fun uint16(value: Int): ByteArray =
        byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())

    /** The attributes in `bytes[from, to)`, or null when one overruns the range. */
    private fun attributes(bytes: ByteArray, from: Int, to: Int): List<Attribute>? {
        val result = mutableListOf<Attribute>()
        var index = from
        while (index < to) {
            if (to - index < 4) return null
            val type = ((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)
            val length = ((bytes[index + 2].toInt() and 0xFF) shl 8) or (bytes[index + 3].toInt() and 0xFF)
            val start = index + 4
            if (to - start < length) return null
            result += Attribute(type, start, start + length)
            index = start + length
        }
        return result
    }
}
