package me.nettrash.scan.data.payload

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * The two digital-identity codes people actually meet now that wallets
 * ship: the ISO/IEC 18013-5 mdoc engagement QR (`mdoc:`, the one a
 * digital-ID wallet shows an ID reader; `mdoc://` is the ISO/IEC 18013-7
 * online form a website hands a wallet) and the eIDAS 2.0 / OpenID4VP
 * wallet flows (the one a verifier site shows so an EU Digital Identity
 * Wallet can hand it attributes).
 *
 * Neither is a credential: one sets up a connection, the other tells a
 * wallet what to reveal. That makes them the same shape as the FIDO passkey
 * QR from 1.10 — a code that starts an exchange between two other parties —
 * so they get the same treatment: decode it, say plainly what it is for,
 * and never perform the exchange ourselves. Signature verification is
 * explicitly out of scope: we decode to inform, and say so, rather than
 * implying we vouched for it.
 *
 * Mirrors the iOS `IdentityPayload.swift`.
 */

// MARK: - Base64URL helper

/**
 * Shared base64URL decoder (lenient about padding, strict about the
 * alphabet — the iOS `PayloadCodec` rules). The existing private one in
 * [me.nettrash.scan.ui.deeplink.DeepLink] returns a `String`; the identity
 * payloads need raw bytes (CBOR inside a query parameter), so this lives
 * beside them. Never throws — every failure is a `null`.
 */
object PayloadCodec {

    /**
     * `-_` → `+/`, trim the ends, then pad to a multiple of four, so a JWT
     * segment or CBOR blob that arrived without its `=` padding still
     * decodes — the iOS `base64URLData` steps exactly, with its trim set
     * ([trimSwiftWhitespace], not Kotlin's `trim()`).
     *
     * Anything left outside the alphabet fails the decode, as it does for
     * iOS' `Data(base64Encoded:options: [])`. `android.util.Base64` would
     * instead skip it and decode the remainder: a scan with a stray space
     * or `!` in the middle would become *different* bytes, or not, depending
     * on how the padding happened to line up.
     */
    fun base64URLData(s: String): ByteArray? {
        var t = s.replace('-', '+').replace('_', '/')
        t = trimSwiftWhitespace(t)
        while (t.length % 4 != 0) t += "="
        if (!t.all { isBase64Char(it) || it == '=' }) return null
        return runCatching { Base64.decode(t, Base64.DEFAULT) }.getOrNull()
    }

    fun base64URLString(s: String): String? = base64URLData(s)?.let { utf8(it) }

    /**
     * Standard (not URL-safe) base64, for the base64-encoded e-invoice
     * carrier — the iOS `Data(base64Encoded:options: .ignoreUnknownCharacters)`
     * call. Characters outside the alphabet (the line breaks of a
     * MIME-wrapped carrier, say) are dropped before decoding. The `=`
     * padding is not supplied: Foundation insists on whole four-character
     * groups even while ignoring the rest, so an unpadded blob is no
     * invoice there, and it is not one here either.
     */
    internal fun base64Data(s: String): ByteArray? {
        val t = s.filter { isBase64Char(it) || it == '=' }
        if (t.length % 4 != 0) return null
        return runCatching { Base64.decode(t, Base64.DEFAULT) }.getOrNull()
    }

    /** The standard base64 alphabet, without the `=` pad. */
    private fun isBase64Char(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/'

    /**
     * [s] less the leading and trailing characters Swift's
     * `trimmingCharacters(in: .whitespacesAndNewlines)` removes: U+0009–
     * U+000D, the Unicode spaces, U+0085, U+2028, U+2029 and — Apple's set
     * measured, not documented — U+200B. Kotlin's `trim()` differs both
     * ways: it also strips U+001C–U+001F and keeps U+0085, so a code with
     * either in front of a field read differently on the two phones. The
     * NFC codec trims by the same set.
     */
    internal fun trimSwiftWhitespace(s: String): String = s.trim { c ->
        c in '\u0009'..'\u000D' || c == ' ' || c == '\u0085' || c == ' ' || c == ' ' ||
            c in ' '..'​' || c == ' ' || c == ' ' || c == ' ' ||
            c == ' ' || c == '　'
    }

    /**
     * Containers a JSON document may nest — the CBOR reader's cap, and the
     * iOS `PayloadCodec.maxJSONDepth`: deeper documents are refused on both
     * phones. (`JSONSerialization` itself would recurse far enough to
     * overrun the stack of the thread an iOS App Intent runs on.)
     */
    internal const val MAX_JSON_DEPTH = 64

    /**
     * True unless [text] opens more than [MAX_JSON_DEPTH] containers at
     * once, counting `{` / `[` outside JSON strings (backslash escapes
     * honoured). The pre-check iOS `PayloadCodec.jsonNestingIsWithinLimit`
     * runs before handing a blob to a recursive parser; Swish's `data=`
     * JSON goes through `org.json`, which would otherwise read nesting iOS
     * refuses. The structural characters are ASCII, so walking UTF-16 here
     * counts exactly what iOS counts walking UTF-8.
     */
    internal fun jsonNestingIsWithinLimit(text: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> {
                    depth += 1
                    if (depth > MAX_JSON_DEPTH) return false
                }
                '}', ']' -> depth -= 1
            }
        }
        return true
    }

    /**
     * JSON parse of a base64URL JWT segment. Returns null for anything
     * that is not a JSON object, which is the shape every claim set in
     * these profiles uses. Values come back as plain Kotlin (`Map`,
     * `List`, `String`, `Long` / `Double`, `Boolean`, null) so callers can
     * use `as?` instead of JSON types.
     */
    fun jsonObjectFromBase64URL(segment: String): Map<String, Any?>? =
        base64URLData(segment)?.let { jsonObject(it) }

    /** JSON parse of raw bytes. Null unless they are UTF-8 holding a JSON object. */
    internal fun jsonObject(bytes: ByteArray): Map<String, Any?>? =
        utf8(bytes)?.let { jsonObjectFromString(it) }

    /**
     * JSON parse of a string, by `JSONSerialization`'s rules (see
     * [StrictJsonReader]). Null for anything that is not a JSON object.
     */
    internal fun jsonObjectFromString(raw: String): Map<String, Any?>? =
        StrictJsonReader(raw).readObjectDocument()

    /**
     * Strict UTF-8 decode: `null` for malformed bytes, where Kotlin's
     * `ByteArray.toString(Charsets.UTF_8)` would hand back U+FFFD
     * replacement characters instead. Swift's
     * `String(data:encoding:.utf8)` returns nil there, and both the CBOR
     * text-string case and the base64 e-invoice carrier rely on that to
     * reject binary that only *looks* like text.
     */
    internal fun utf8(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
}

/**
 * A JSON reader with Foundation's `JSONSerialization` rules, so a claim
 * set or query document reads the same on both phones. `org.json` is no
 * stand-in: it accepts unquoted keys, single quotes, comments and trailing
 * garbage that iOS refuses — widening the "is this a credential?" gate
 * that keeps ordinary login tokens out — and it reads `[1,]` as
 * `[1, null]`, which voids a whole credential list one element later.
 *
 * The rules, measured against `JSONSerialization` itself: RFC 8259, plus a
 * single trailing comma after the last member or element, a byte-order
 * mark in front, and the *first* of two duplicate keys winning. A number
 * that overflows a `Double` and an unpaired surrogate escape are errors, and
 * so is a container nested more than [MAX_DEPTH] deep — the cap iOS checks
 * before `JSONSerialization` runs. Integers come back as `Long` when they
 * fit, everything else as `Double`.
 */
private class StrictJsonReader(private val s: String) {

    private var i = 0

    /** One shared sentinel: unwinding a bad document needs no stack trace. */
    private object Malformed : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    /**
     * The document's top-level object, or null when the document is not
     * valid JSON or is not an object \u2014 the two cases iOS'
     * `jsonObject(with:) as? [String: Any]` folds into nil.
     */
    fun readObjectDocument(): Map<String, Any?>? = try {
        if (s.startsWith('\uFEFF')) i = 1
        skipWhitespace()
        if (peek() != '{') throw Malformed
        val document = readObject(depth = 0)
        skipWhitespace()
        if (i != s.length) throw Malformed
        document
    } catch (_: Malformed) {
        null
    }

    /** [depth] counts the containers around the value. */
    private fun readValue(depth: Int): Any? {
        skipWhitespace()
        return when (peek()) {
            '{' -> readObject(depth)
            '[' -> readArray(depth)
            '"' -> readString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> readNumber()
        }
    }

    private fun readObject(depth: Int): Map<String, Any?> {
        if (depth >= MAX_DEPTH) throw Malformed     // this one would be container MAX_DEPTH + 1
        i++                                         // {
        val members = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (peek() == '}') { i++; return members }
        while (true) {
            skipWhitespace()
            if (peek() != '"') throw Malformed
            val key = readString()
            skipWhitespace()
            if (next() != ':') throw Malformed
            val value = readValue(depth + 1)
            if (key !in members) members[key] = value
            skipWhitespace()
            when (next()) {
                '}' -> return members
                ',' -> {
                    skipWhitespace()
                    if (peek() == '}') { i++; return members }
                }
                else -> throw Malformed
            }
        }
    }

    private fun readArray(depth: Int): List<Any?> {
        if (depth >= MAX_DEPTH) throw Malformed
        i++                                         // [
        val items = ArrayList<Any?>()
        skipWhitespace()
        if (peek() == ']') { i++; return items }
        while (true) {
            items += readValue(depth + 1)
            skipWhitespace()
            when (next()) {
                ']' -> return items
                ',' -> {
                    skipWhitespace()
                    if (peek() == ']') { i++; return items }
                }
                else -> throw Malformed
            }
        }
    }

    /**
     * A string token. Foundation builds each one through its UTF-8 string
     * initialiser, which takes a leading U+FEFF for a byte-order mark and
     * drops it — escaped or raw, in keys and values alike — so one leading
     * U+FEFF is dropped here too. Otherwise an invisible prefix on a
     * `client_id` would survive on one phone and change the host shown.
     */
    private fun readString(): String {
        i++                                         // opening quote
        val out = StringBuilder()
        while (true) {
            if (i >= s.length) throw Malformed
            val c = s[i++]
            when {
                c == '"' -> return if (out.startsWith('\uFEFF')) out.substring(1) else out.toString()
                c == '\\' -> readEscape(out)
                c < ' ' -> throw Malformed            // raw control character
                else -> out.append(c)
            }
        }
    }

    private fun readEscape(out: StringBuilder) {
        if (i >= s.length) throw Malformed
        when (s[i++]) {
            '"' -> out.append('"')
            '\\' -> out.append('\\')
            '/' -> out.append('/')
            'b' -> out.append('\b')
            'f' -> out.append('\u000C')
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            'u' -> {
                val unit = hex4()
                when (unit) {
                    in 0xD800..0xDBFF -> {
                        // A high surrogate must be followed by an escaped low one.
                        if (!s.startsWith("\\u", i)) throw Malformed
                        i += 2
                        val low = hex4()
                        if (low !in 0xDC00..0xDFFF) throw Malformed
                        out.append(unit.toChar()).append(low.toChar())
                    }
                    in 0xDC00..0xDFFF -> throw Malformed
                    else -> out.append(unit.toChar())
                }
            }
            else -> throw Malformed
        }
    }

    /** Four ASCII hex digits (`Character.digit` would also take `٣` or `Ａ`). */
    private fun hex4(): Int {
        if (i + 4 > s.length) throw Malformed
        var v = 0
        repeat(4) {
            val d = when (val h = s[i++]) {
                in '0'..'9' -> h - '0'
                in 'a'..'f' -> h - 'a' + 10
                in 'A'..'F' -> h - 'A' + 10
                else -> throw Malformed
            }
            v = (v shl 4) or d
        }
        return v
    }

    private fun readNumber(): Any {
        val start = i
        if (peek() == '-') i++
        when (peek()) {
            '0' -> i++
            in '1'..'9' -> while (peek() in '0'..'9') i++
            else -> throw Malformed
        }
        var integral = true
        if (peek() == '.') {
            integral = false
            i++
            if (peek() !in '0'..'9') throw Malformed
            while (peek() in '0'..'9') i++
        }
        if (peek() == 'e' || peek() == 'E') {
            integral = false
            i++
            if (peek() == '+' || peek() == '-') i++
            if (peek() !in '0'..'9') throw Malformed
            while (peek() in '0'..'9') i++
        }
        val text = s.substring(start, i)
        if (integral) text.toLongOrNull()?.let { return it }
        val d = text.toDouble()
        if (d.isInfinite() || d.isNaN()) throw Malformed
        return d
    }

    private fun literal(word: String, value: Boolean?): Boolean? {
        if (!s.startsWith(word, i)) throw Malformed
        i += word.length
        return value
    }

    /** JSON whitespace only — a no-break space is not one. */
    private fun skipWhitespace() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }

    private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

    private fun next(): Char = if (i < s.length) s[i++] else '\u0000'

    companion object {
        /** Containers a document may nest; one more is an error, as on iOS. */
        const val MAX_DEPTH = PayloadCodec.MAX_JSON_DEPTH
    }
}

// MARK: - Shared URI / text helpers

/**
 * A query's parameters, split by hand exactly as the iOS `queryItems`
 * does: up to a fragment, on `&` (empty parts skipped), each part at its
 * first `=`. The name is lowercased and a later duplicate wins; a part with
 * no `=` maps to null, as a nil `URLQueryItem.value` clears the name on
 * iOS. Name and value are percent-decoded on their own ([HostName.percentDecoded],
 * `+` stays `+`), and one that does not decode — a stray `%`, bytes that
 * are not UTF-8 — is kept as written, where `Uri.decode` would have put
 * U+FFFD in it and iOS would have kept the text.
 */
internal fun queryItemsByLowercasedName(query: String): Map<String, String?> {
    fun decoded(s: String) = HostName.percentDecoded(s) ?: s
    val items = LinkedHashMap<String, String?>()
    for (part in query.substringBefore('#').split('&')) {
        if (part.isEmpty()) continue
        val eq = part.indexOf('=')
        if (eq < 0) {
            items[decoded(part).lowercase(Locale.ROOT)] = null
        } else {
            items[decoded(part.substring(0, eq)).lowercase(Locale.ROOT)] = decoded(part.substring(eq + 1))
        }
    }
    return items
}

/**
 * The host a credential or Digital Product Passport row names for a URL in
 * the code: the host a browser would contact, written as the safety check's
 * findings write it ([HostName.normalized] — percent-decoded, NFKC-folded,
 * invisible characters dropped, lower-cased, Unicode kept), so the sheet,
 * the risk banner and the look-alike check all see one host. The authority is
 * split by [parseUrl]'s rules — it ends at `\`, IPv6 brackets and the port
 * are dropped — rather than by `android.net.Uri`: iOS used to show
 * Foundation's punycode and read a `\` differently, so one code named two
 * verifiers. The split runs on chars, as the iOS one runs on bytes: iOS
 * `DecodedURL` steps by `Character`, and a combining mark after a delimiter
 * would hide it there alone.
 *
 * An address that hides its host is not named at all: user-info (`a@b` is
 * how a link disguises its real host), an empty authority, spaces or control
 * characters in it, or a host with characters no host name has. The row then
 * says [UNUSUAL], and the sheet adds [HIDDEN_HOST_CAUTION]. Mirrors the iOS
 * `ShownHost` line for line.
 */
internal object ShownHost {
    /** What a row shows for such an address, and what a host field holds for one — the mdoc sheet's wording. */
    const val UNUSUAL = "Unusual address — not shown"

    /** The caution that goes with it. */
    const val HIDDEN_HOST_CAUTION =
        "An address in this code is written in a way that can hide where it leads, so Scan does not show its host."

    /** [host] as it reads inside a sentence ("fetches it from …"). */
    fun inSentence(host: String): String = if (host == UNUSUAL) "an unusual address (not shown)" else host

    /** The host to name for [raw]; [UNUSUAL] when the address hides it; null when [raw] is not a `scheme://` address at all. */
    fun of(raw: String): String? {
        val separator = raw.indexOf("://")
        if (separator < 0) return null
        // The authority as RFC 3986 bounds it, which — unlike a browser —
        // does not stop at `\`: an `@` anywhere in it is user-info to one
        // parser or the other.
        val start = separator + 3
        val end = raw.indexOfAny(charArrayOf('/', '?', '#'), start).let { if (it < 0) raw.length else it }
        val authority = raw.substring(start, end)
        if (authority.isEmpty() || authority.any { it == '@' || it.code <= 0x20 || it.code in 0x7F..0x9F }) {
            return UNUSUAL
        }
        // A browser reads `\` as `/`, so the host ends there.
        val hostPort = authority.substringBefore('\\')
        val bracketed = hostPort.startsWith("[")
        val host = if (bracketed) {
            val close = hostPort.indexOf(']')
            if (close < 0) return UNUSUAL
            val after = hostPort.substring(close + 1)
            if (after.isNotEmpty() && !(after[0] == ':' && after.drop(1).all { it in '0'..'9' })) return UNUSUAL
            hostPort.substring(1, close)
        } else {
            val colon = hostPort.lastIndexOf(':')
            if (colon >= 0 && hostPort.substring(colon + 1).all { it in '0'..'9' }) hostPort.substring(0, colon) else hostPort
        }
        val name = HostName(host).normalized
        return if (isPlainHost(name, bracketed)) name else UNUSUAL
    }

    /**
     * Something to show, and no character a host name cannot hold: the
     * WHATWG forbidden domain code points, C1 controls, spaces that survive
     * NFKC, bidi and other format controls, U+FFFD (a decoding failure), and
     * — outside an IPv6 literal — `:`. Code-point ranges, so both ports
     * agree whatever Unicode version their OS ships.
     */
    private fun isPlainHost(host: String, bracketed: Boolean): Boolean {
        val points = ArrayList<Int>(host.length)
        var i = 0
        while (i < host.length) {
            val cp = host.codePointAt(i)
            points += cp
            i += Character.charCount(cp)
        }
        if (points.isEmpty() || points.size > 253) return false
        if (bracketed) {
            return points.all { it in '0'.code..'9'.code || it in 'a'.code..'f'.code || it == ':'.code || it == '.'.code }
        }
        return points.none { cp ->
            cp in 0x00..0x20 || cp in 0x7F..0x9F || cp == 0x061C || cp == 0x1680 || cp == 0x180E ||
                cp == 0x200E || cp == 0x200F || cp in 0x2028..0x202E || cp in 0x2061..0x2064 ||
                cp in 0x2066..0x2069 || cp in 0xFFF9..0xFFFB || cp == 0xFFFD || cp in FORBIDDEN
        }
    }

    private val FORBIDDEN: Set<Int> = "#%/:<>?@[\\]^|".map { it.code }.toSet()
}

/**
 * Swift's `String.capitalized`, which the mdoc element and OpenID4VP scope
 * fallbacks use. Kotlin's `replaceFirstChar` only touches the first
 * character, so it would render "Birth date" where iOS says "Birth Date".
 *
 * Foundation's actual rule (measured against it — the documented "words
 * are delimited by whitespace" is not what it does): a cased letter that
 * does not follow another cased letter starts a word and is title-cased,
 * and the rest of the word is lower-cased. Anything else that is not
 * case-ignorable ends the word — digits, `.`, `-`, `_`, `:` and uncased
 * letters included, so `org.iso.mdl` → `Org.Iso.Mdl` and `x2y` → `X2Y`.
 * The case-ignorable characters (apostrophes, combining marks, format and
 * modifier characters) pass through without ending it: `o'neil` →
 * `O'neil`. A capital that starts a word is kept as written, and title
 * case is Unicode's own (`ǆ` → `ǅ`, `ß` → `Ss`).
 */
internal fun String.asCapitalizedWords(): String {
    val out = StringBuilder(length)
    var lastCased = false
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        i += Character.charCount(cp)
        val type = Character.getType(cp)
        if (isCaseIgnorable(cp, type)) {
            out.appendCodePoint(cp)
            continue
        }
        val cased = type == Character.UPPERCASE_LETTER.toInt() ||
            type == Character.LOWERCASE_LETTER.toInt() ||
            type == Character.TITLECASE_LETTER.toInt()
        if (!cased) {
            out.appendCodePoint(cp)
            lastCased = false
            continue
        }
        val letter = String(Character.toChars(cp))
        out.append(
            when {
                lastCased -> letter.lowercase(Locale.ROOT)
                type == Character.LOWERCASE_LETTER.toInt() -> titlecase(cp)
                else -> letter
            }
        )
        lastCased = true
    }
    return out.toString()
}

/**
 * The case-ignorable set Foundation's capitalisation honours: the older
 * Unicode definition — marks, format and modifier characters, plus the
 * apostrophes and the soft hyphen — not the later word-break one, which
 * would also let `.` and `:` run a word on.
 */
private fun isCaseIgnorable(cp: Int, type: Int): Boolean =
    cp == 0x27 || cp == 0xAD || cp == 0x2019 ||
        type == Character.NON_SPACING_MARK.toInt() ||
        type == Character.ENCLOSING_MARK.toInt() ||
        type == Character.FORMAT.toInt() ||
        type == Character.MODIFIER_LETTER.toInt() ||
        type == Character.MODIFIER_SYMBOL.toInt()

/** Full title case where one exists (`ß` → `Ss`), else the simple mapping. */
private fun titlecase(cp: Int): String =
    if (Character.isBmpCodePoint(cp)) cp.toChar().titlecase()
    else String(Character.toChars(Character.toTitleCase(cp)))

// MARK: - ISO/IEC 18013-5 / 18013-7 mdoc engagement

/**
 * A decoded `mdoc:` or `mdoc://` code: the *engagement* that opens an
 * ISO/IEC 18013-5 (in person) or ISO/IEC 18013-7 (online) presentation of a
 * mobile document — a mobile driving licence, an EU PID or any other mdoc.
 * "mdoc" is an IANA-registered (provisional) URI scheme.
 *
 * An engagement is neither the document nor a request for it. It carries a
 * version, a fresh one-time public key and the ways the other side may
 * connect; the request, the holder's approval and the data itself only
 * travel over that connection, between a wallet and a reader. Scan is
 * neither, so it describes the code and stops there. It never keeps what
 * works as a secret (a server-retrieval token, a Wi-Fi Aware passphrase),
 * the key bytes, or the radio identifiers (BLE UUIDs, device address).
 *
 * Mirrors the iOS `MobileDrivingLicensePayload` field for field and string
 * for string.
 */
data class MobileDrivingLicensePayload(
    val raw: String,
    val kind: Kind = Kind.DEVICE_ENGAGEMENT,
    /** False when the code has the `mdoc` scheme but did not decode. */
    val decoded: Boolean = false,
    /** Key 0 as written — "1.0" or "1.1" today. */
    val version: String? = null,
    /** The first element of `Security`; 1 is the only suite 18013-5 defines. */
    val cipherSuite: ULong? = null,
    /** `EDeviceKey` (or `EReaderKey`); null when the code carries none. */
    val key: KeyType? = null,
    val channels: List<Channel> = emptyList(),
    val serverRetrieval: List<ServerRetrieval> = emptyList(),
) {
    /** Who shows the code, and so who is meant to scan it. */
    enum class Kind {
        /**
         * `mdoc:` + `DeviceEngagement`: a holder's wallet shows it to an ID
         * reader (ISO/IEC 18013-5:2021 §8.2.2.3).
         */
        DEVICE_ENGAGEMENT,

        /**
         * `mdoc:` + `ReaderEngagement`: an ID reader shows it to a wallet
         * (the Reader QR of the 18013-5 second edition).
         */
        READER_ENGAGEMENT,

        /**
         * `mdoc://` + `ReaderEngagement`: a website asks a wallet for an mdoc
         * over the internet (ISO/IEC 18013-7 Annex A).
         */
        ONLINE_REQUEST,
    }

    /** One `DeviceRetrievalMethod`: a way the two sides can connect. */
    sealed class Channel {
        data object Nfc : Channel()

        /** The mdoc BLE modes (`BleOptions` keys 0 and 1). */
        data class Bluetooth(val peripheralServer: Boolean, val centralClient: Boolean) : Channel()

        data object WifiAware : Channel()

        /** The 18013-7 HTTP method (type 4); only its URL's host is kept. */
        data class Website(val host: String?) : Channel()

        data class Other(val type: ULong) : Channel()
    }

    /**
     * One `ServerRetrievalMethods` entry, without its token: [method] is
     * "WebAPI" or "OIDC", [host] the issuer URL's host (null when it is not
     * a plain web address).
     */
    data class ServerRetrieval(val method: String, val host: String?)

    /** The one-time key's COSE type: `kty` (label 1) and `crv` (label -1). */
    data class KeyType(val kty: ULong? = null, val crv: ULong? = null)

    // MARK: Display

    val kindLabel: String
        get() = when (kind) {
            Kind.DEVICE_ENGAGEMENT -> "Digital ID"
            Kind.READER_ENGAGEMENT -> "ID Reader"
            Kind.ONLINE_REQUEST -> "ID Request"
        }

    val headline: String
        get() = when (kind) {
            Kind.DEVICE_ENGAGEMENT -> "A digital-ID engagement code"
            Kind.READER_ENGAGEMENT -> "An ID reader's engagement code"
            Kind.ONLINE_REQUEST -> "An online ID request"
        }

    fun labelledFields(): List<LabelledField> {
        val rows = when (kind) {
            Kind.DEVICE_ENGAGEMENT -> mutableListOf(
                LabelledField("Format", "ISO/IEC 18013-5 device engagement"),
                LabelledField("Shown by", WALLET_DOCUMENTS),
                LabelledField("Meant for", "An ID reader"),
            )
            Kind.READER_ENGAGEMENT -> mutableListOf(
                LabelledField("Format", "ISO/IEC 18013-5 reader engagement"),
                LabelledField("Shown by", "An ID reader"),
                LabelledField("Meant for", WALLET_DOCUMENTS),
            )
            Kind.ONLINE_REQUEST -> mutableListOf(
                LabelledField("Format", "ISO/IEC 18013-7 online request"),
                LabelledField("Shown by", "A website"),
                LabelledField("Meant for", WALLET_DOCUMENTS),
            )
        }
        if (!decoded) {
            rows += LabelledField("Contents", "Not decodable — damaged or a newer version")
            return rows
        }
        rows += LabelledField("ID data", "None — only how to connect")
        rows += LabelledField("Version", versionText)
        val names = channelNames
        rows += LabelledField("Channels", if (names.isEmpty()) "None stated" else names.joinToString(", "))
        bluetoothModes?.let { rows += LabelledField("Bluetooth LE", it) }
        websiteText?.let { rows += LabelledField("Website", it) }
        if (serverRetrieval.isNotEmpty()) {
            rows += LabelledField(
                "Server retrieval",
                serverRetrieval.joinToString(", ") {
                    "${it.method} at ${it.host ?: "an unusual address (not shown)"}"
                },
            )
        }
        rows += LabelledField("Cipher suite", cipherSuiteText)
        rows += LabelledField(if (kind == Kind.DEVICE_ENGAGEMENT) "Device key" else "Reader key", keyText)
        return rows
    }

    /**
     * The explainer's points: what the code is, how the other side would
     * connect, and that it holds a key rather than the ID.
     */
    fun keyPoints(): List<String> {
        val points = mutableListOf<String>()
        val spoken = spokenList(channels.mapNotNull { knownChannelName(it) })
        when (kind) {
            Kind.DEVICE_ENGAGEMENT -> {
                points += "A digital-ID wallet's code for an ID reader (ISO/IEC 18013-5), for a mobile " +
                    "driving licence, an EU PID or another mdoc."
                if (decoded && spoken.isNotEmpty()) points += "A reader can connect over $spoken."
                if (decoded) points += "It carries a fresh one-time key, not the ID itself."
            }
            Kind.READER_ENGAGEMENT -> {
                points += "An ID reader's code for a digital-ID wallet (ISO/IEC 18013-5): it invites a " +
                    "wallet to connect so the reader can ask for an ID."
                if (decoded && spoken.isNotEmpty()) points += "A wallet would connect over $spoken."
                if (decoded) points += "It carries the reader's one-time key, not anyone's ID."
            }
            Kind.ONLINE_REQUEST -> {
                points += "A website's request for a digital ID (ISO/IEC 18013-7): it asks a wallet to " +
                    "share a mobile driving licence, an EU PID or another mdoc online."
                val hosts = websiteHosts
                if (decoded && hosts.isNotEmpty()) points += "A wallet would connect to ${hosts.joinToString(", ")}."
                if (decoded) points += "It carries the site's one-time key, not anyone's ID."
            }
        }
        if (!decoded) points += "Its contents could not be decoded — it may be damaged or a newer version."
        return points
    }

    /** The explainer's cautions. */
    fun cautions(): List<String> = when (kind) {
        Kind.DEVICE_ENGAGEMENT -> listOf(
            "Scan is not an ID reader: it cannot request or read the document.",
            "A nearby reader can use this code to start a request, which the wallet still asks its " +
                "holder to approve.",
        )
        Kind.READER_ENGAGEMENT -> listOf(
            "Scan is not a wallet: it cannot answer the reader or share a document.",
            "Scanned with a wallet, it lets the reader ask for data, and the wallet asks you to " +
                "approve what is shared.",
        )
        Kind.ONLINE_REQUEST -> listOf(
            "Scan is not a wallet: it cannot answer this request or share a document.",
            "Opened in a wallet, it lets the site ask for data, and the wallet asks you to approve " +
                "what is shared.",
        )
    } + warnings()

    /**
     * The result sheet's highlighted warnings — the subset of [cautions] that
     * asks the user to do something.
     */
    fun warnings(): List<String> = when (kind) {
        Kind.DEVICE_ENGAGEMENT -> buildList {
            add("Only show your own code to the reader you mean to share with.")
            if (decoded && serverRetrieval.isNotEmpty()) {
                add(
                    "It also carries a server-retrieval token: whoever scans it can ask the issuer " +
                        "for the holder's data directly."
                )
            }
        }
        Kind.READER_ENGAGEMENT -> listOf(
            "Only scan a reader's code with your wallet where you meant to show your ID.",
        )
        Kind.ONLINE_REQUEST -> listOf(
            "Only continue in your wallet for a site you opened yourself, and check its address.",
        )
    }

    /** The result sheet's explanation under the fields. */
    val notice: String
        get() = when (kind) {
            Kind.DEVICE_ENGAGEMENT ->
                "Scan is not an ID reader and cannot request or read the document. A nearby reader " +
                    "can use this code to start a request, which the wallet still asks its holder to " +
                    "approve. The code carries a fresh one-time key, so it is not the ID itself."
            Kind.READER_ENGAGEMENT ->
                "Scan is not a wallet and cannot answer the reader. Scanned with a wallet, the code " +
                    "lets the reader ask for data, and the wallet asks you to approve what is shared. " +
                    "It carries the reader's one-time key, not anyone's ID."
            Kind.ONLINE_REQUEST ->
                "Scan is not a wallet and cannot answer this request. Opened in a wallet, the code " +
                    "lets the site ask for data, and the wallet asks you to approve what is shared. It " +
                    "carries the site's one-time key, not anyone's ID."
        }

    // MARK: Field text

    /**
     * The version as written when it has the `major.minor` shape the
     * standard uses; anything else is not echoed back.
     */
    private val versionText: String
        get() = when {
            version == null -> "Not stated"
            MobileDrivingLicenseParser.versionNumbers(version) == null -> "Unrecognised"
            else -> version
        }

    private val channelNames: List<String>
        get() = channels.map { channelName(it) }.distinct()

    /** Every Bluetooth method's modes, together. */
    private val bluetoothModes: String?
        get() {
            val ble = channels.filterIsInstance<Channel.Bluetooth>()
            if (ble.isEmpty()) return null
            val peripheral = ble.any { it.peripheralServer }
            val central = ble.any { it.centralClient }
            return when {
                peripheral && central -> "Peripheral server and central client modes"
                peripheral -> "Peripheral server mode"
                central -> "Central client mode"
                else -> "No mode stated"
            }
        }

    private val websiteHosts: List<String>
        get() = channels.filterIsInstance<Channel.Website>().mapNotNull { it.host }.distinct().take(3)

    private val websiteText: String?
        get() {
            if (channels.none { it is Channel.Website }) return null
            val hosts = websiteHosts
            return if (hosts.isEmpty()) "Unusual address — not shown" else hosts.joinToString(", ")
        }

    private val cipherSuiteText: String
        get() = when (val suite = cipherSuite) {
            null -> "Not stated"
            1uL -> "1 (ECDH, AES-256-GCM)"
            else -> "$suite (unknown)"
        }

    private val keyText: String
        get() {
            val k = key ?: return "Missing"
            val curve = k.crv?.let { MobileDrivingLicenseParser.curveName(it) ?: "Curve $it" }
            val type = k.kty?.let { MobileDrivingLicenseParser.keyTypeName(it) ?: "Key type $it" }
            return when {
                curve != null && type != null -> "$curve ($type), one-time key"
                curve != null -> "$curve, one-time key"
                type != null -> "$type, one-time key"
                else -> "Present, type not readable"
            }
        }

    private companion object {
        const val WALLET_DOCUMENTS = "A digital-ID wallet (mDL, EU PID or other mdoc)"

        fun channelName(channel: Channel): String = when (channel) {
            Channel.Nfc -> "NFC"
            is Channel.Bluetooth -> "Bluetooth LE"
            Channel.WifiAware -> "Wi-Fi Aware"
            is Channel.Website -> "Website"
            is Channel.Other -> "Type ${channel.type}"
        }

        /** The name, for the methods a sentence can mention. */
        fun knownChannelName(channel: Channel): String? =
            if (channel is Channel.Other) null else channelName(channel)

        /** "a", "a or b", "a, b or c". */
        fun spokenList(items: List<String>): String {
            val unique = items.distinct()
            val last = unique.lastOrNull() ?: return ""
            return if (unique.size == 1) last else unique.dropLast(1).joinToString(", ") + " or " + last
        }
    }
}

object MobileDrivingLicenseParser {

    /**
     * The longest encoded engagement decoded. A real one is a few hundred
     * characters; the cap bounds what a hostile code can make us do.
     */
    const val MAX_ENCODED_LENGTH = 4096

    /** Retrieval methods kept; a real engagement lists one to three. */
    const val MAX_CHANNELS = 8

    // MARK: Recognition

    /**
     * `mdoc:` + an encoded engagement, or `mdoc://` + one in the authority.
     * `mdoc-openid4vp://` (ISO/IEC 18013-7 Annex B) is a different scheme —
     * an OpenID4VP request — and is left to the verifiable-credential parser.
     */
    fun looksLikeMdocURI(raw: String): Boolean = split(raw) != null

    fun parse(raw: String): MobileDrivingLicensePayload? {
        val (online, body) = split(raw) ?: return null
        val p = MobileDrivingLicensePayload(
            raw = raw,
            kind = if (online) {
                MobileDrivingLicensePayload.Kind.ONLINE_REQUEST
            } else {
                MobileDrivingLicensePayload.Kind.DEVICE_ENGAGEMENT
            },
        )
        if (body.length > MAX_ENCODED_LENGTH) return p
        val bytes = decodeBase64URL(body) ?: return p
        val e = decodeEngagement(bytes) ?: return p
        return p.copy(
            kind = if (!online && isReaderEngagement(e)) {
                MobileDrivingLicensePayload.Kind.READER_ENGAGEMENT
            } else {
                p.kind
            },
            decoded = true,
            version = e.version,
            cipherSuite = e.cipherSuite,
            key = e.key,
            channels = e.channels,
            serverRetrieval = e.serverRetrieval,
        )
    }

    /**
     * (online, encoded engagement), or null when [raw] is not an mdoc URI.
     * ASCII-only, as the iOS byte-wise split is, so both ports agree.
     */
    private fun split(raw: String): Pair<Boolean, String>? {
        val scheme = "mdoc:"
        if (raw.length <= scheme.length) return null
        for (i in scheme.indices) {
            val c = raw[i]
            if ((if (c in 'A'..'Z') c + 32 else c) != scheme[i]) return null
        }
        var rest = raw.substring(scheme.length)
        val online = rest.startsWith("//")
        if (online) {
            // ISO/IEC 18013-7: the engagement is the authority; whatever
            // follows it is not part of the engagement.
            rest = rest.substring(2).takeWhile { it != '/' && it != '?' && it != '#' }
        }
        if (rest.isEmpty() || !rest.all { isEncodedChar(it, online) }) return null
        return online to rest
    }

    /**
     * base64url, plus `=` padding — and, for the QR form, the standard
     * alphabet's `+` and `/`, which some encoders emit.
     */
    private fun isEncodedChar(c: Char, online: Boolean): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '=' ||
            (!online && (c == '+' || c == '/'))

    // MARK: Decoding

    /**
     * Canonical base64(url) only: padding at the end and only as much as the
     * length needs, no impossible lengths, and zero spare bits. Lenient
     * decoders disagree on everything else, and the two ports must not.
     */
    fun decodeBase64URL(text: String): ByteArray? {
        val data = text.takeWhile { it != '=' }
        val padding = text.length - data.length
        if (padding > 2 || !text.substring(data.length).all { it == '=' }) return null
        if (data.length % 4 == 1) return null
        if (padding != 0 && (data.length + padding) % 4 != 0) return null
        val last = data.lastOrNull()?.let { sextet(it) } ?: return null
        when (data.length % 4) {
            2 -> if (last and 0x0F != 0) return null
            3 -> if (last and 0x03 != 0) return null
        }
        return PayloadCodec.base64URLData(data)
    }

    private fun sextet(c: Char): Int? = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+', '-' -> 62
        '/', '_' -> 63
        else -> null
    }

    /** What the parser keeps of one engagement. */
    class Engagement {
        var version: String? = null
        var cipherSuite: ULong? = null
        var key: MobileDrivingLicensePayload.KeyType? = null
        var channels: List<MobileDrivingLicensePayload.Channel> = emptyList()
        var serverRetrieval: List<MobileDrivingLicensePayload.ServerRetrieval> = emptyList()

        /** OriginInfos (5) or Capabilities (6): only a DeviceEngagement 1.1 carries them. */
        var hasDeviceOnlyKeys = false
    }

    /**
     * `DeviceEngagement` / `ReaderEngagement` (ISO/IEC 18013-5 §8.2.1.1):
     * `{0: version, 1: Security, ?2: DeviceRetrievalMethods,
     * ?3: ServerRetrievalMethods, ?4: ProtocolInfo, ?5: OriginInfos,
     * ?6: Capabilities, …}`. Null unless it is one whole, well-formed map
     * with a text version.
     */
    fun decodeEngagement(bytes: ByteArray): Engagement? {
        val reader = CBORReader(bytes)
        val head = reader.readHeader() ?: return null
        if (head.major != 5 || head.arg < 0 || head.arg > bytes.size) return null
        val e = Engagement()
        var remaining = head.arg
        while (remaining-- > 0) {
            val key = reader.readValue() ?: return null
            if (key == CBORReader.Value.Uint(1uL)) {
                if (!readSecurity(reader, e)) return null
                continue
            }
            val value = reader.readValue() ?: return null
            when (key) {
                CBORReader.Value.Uint(0uL) -> text(value)?.let { e.version = it }
                CBORReader.Value.Uint(2uL) -> if (value is CBORReader.Value.Array) {
                    e.channels = value.v.take(MAX_CHANNELS).mapNotNull { channel(it) }
                }
                CBORReader.Value.Uint(3uL) -> if (value is CBORReader.Value.Map) {
                    e.serverRetrieval = serverRetrieval(value.v)
                }
                CBORReader.Value.Uint(5uL), CBORReader.Value.Uint(6uL) -> e.hasDeviceOnlyKeys = true
                else -> Unit                    // ProtocolInfo, RFU, extensions
            }
        }
        if (reader.hasMore || e.version == null) return null
        return e
    }

    /**
     * `Security = [cipher suite, #6.24(bstr .cbor COSE_Key)]`, walked by
     * hand: the shared reader reports a tag as [CBORReader.Value.Other] and
     * drops what it wraps, and the key's type is inside the wrapper. A
     * Security of any other shape ends the parse, as it would in a real
     * reader.
     */
    private fun readSecurity(reader: CBORReader, e: Engagement): Boolean {
        val head = reader.readHeader() ?: return false
        if (head.major != 4 || head.arg < 2 || head.arg > 16) return false
        val suite = reader.readValue() ?: return false
        (suite as? CBORReader.Value.Uint)?.let { e.cipherSuite = it.v }
        val tag = reader.readHeader() ?: return false
        if (tag.major != 6) return false
        val wrapped = reader.readValue() ?: return false
        e.key = if (tag.arg == 24L && wrapped is CBORReader.Value.Bytes) {
            keyType(wrapped.v)
        } else {
            MobileDrivingLicensePayload.KeyType()
        }
        repeat((head.arg - 2).toInt()) { reader.readValue() ?: return false }
        return true
    }

    /**
     * `kty` and `crv` of a COSE_Key. Labels are small integers in every key
     * we read; a text label cannot be stepped over with a bare header, so it
     * ends the walk with whatever was found before it.
     */
    private fun keyType(bytes: ByteArray): MobileDrivingLicensePayload.KeyType {
        var kty: ULong? = null
        var crv: ULong? = null
        val reader = CBORReader(bytes)
        val head = reader.readHeader()
        if (head == null || head.major != 5 || head.arg < 0 || head.arg > 32) {
            return MobileDrivingLicensePayload.KeyType()
        }
        var remaining = head.arg
        while (remaining-- > 0) {
            val label = reader.readHeader() ?: break
            if (label.major > 1 || label.arg < 0 || label.arg >= 256) break
            val value = reader.readValue() ?: break
            val v = (value as? CBORReader.Value.Uint)?.v ?: continue
            if (label.major == 0 && label.arg == 1L) kty = v      // kty
            if (label.major == 1 && label.arg == 0L) crv = v      // crv, label -1
        }
        return MobileDrivingLicensePayload.KeyType(kty, crv)
    }

    /**
     * `[type, version, options]` — ISO/IEC 18013-5 Table 7, plus the
     * ISO/IEC 18013-7 HTTP method.
     */
    private fun channel(method: CBORReader.Value): MobileDrivingLicensePayload.Channel? {
        val items = (method as? CBORReader.Value.Array)?.v ?: return null
        if (items.size < 2) return null
        val type = (items[0] as? CBORReader.Value.Uint)?.v ?: return null
        val options = if (items.size >= 3) (items[2] as? CBORReader.Value.Map)?.v.orEmpty() else emptyList()
        return when (type) {
            1uL -> MobileDrivingLicensePayload.Channel.Nfc
            2uL -> MobileDrivingLicensePayload.Channel.Bluetooth(
                peripheralServer = options.valueForKey(0uL) == CBORReader.Value.Bool(true),
                centralClient = options.valueForKey(1uL) == CBORReader.Value.Bool(true),
            )
            3uL -> MobileDrivingLicensePayload.Channel.WifiAware
            4uL -> MobileDrivingLicensePayload.Channel.Website(
                text(options.valueForKey(0uL))?.let { hostOfURL(it) },
            )
            else -> MobileDrivingLicensePayload.Channel.Other(type)
        }
    }

    /**
     * `{? "webApi": [version, issuer URL, token], ? "oidc": […]}` — the host
     * is kept, the token never is.
     */
    private fun serverRetrieval(pairs: List<CBORPair>): List<MobileDrivingLicensePayload.ServerRetrieval> {
        val out = mutableListOf<MobileDrivingLicensePayload.ServerRetrieval>()
        for ((name, method) in listOf("webApi" to "WebAPI", "oidc" to "OIDC")) {
            val entry = pairs.firstOrNull { text(it.key) == name }?.value
            val fields = (entry as? CBORReader.Value.Array)?.v ?: continue
            if (fields.size < 2) continue
            out += MobileDrivingLicensePayload.ServerRetrieval(method, text(fields[1])?.let { hostOfURL(it) })
        }
        return out
    }

    /**
     * A CBOR text value as iOS reads it: Foundation's UTF-8 string
     * initialiser drops one leading byte-order mark, so one is dropped here.
     */
    private fun text(value: CBORReader.Value?): String? =
        (value as? CBORReader.Value.Text)?.v?.removePrefix("\uFEFF")

    /**
     * A Reader QR (18013-5 second edition) is a `ReaderEngagement` behind the
     * same `mdoc:` prefix. It is version 1.1 or later with a key and
     * Bluetooth only, and has neither OriginInfos nor Capabilities — which a
     * `DeviceEngagement` 1.1 always carries at least one of.
     */
    private fun isReaderEngagement(e: Engagement): Boolean {
        val (major, minor) = versionNumbers(e.version ?: "") ?: return false
        if (major != 1 || minor < 1 || e.hasDeviceOnlyKeys || e.key == null || e.channels.isEmpty()) {
            return false
        }
        return e.channels.all { it is MobileDrivingLicensePayload.Channel.Bluetooth }
    }

    /** `major.minor`, one to four ASCII digits each. */
    fun versionNumbers(version: String): Pair<Int, Int>? {
        val parts = version.split('.')
        if (parts.size != 2 || parts.any { part -> part.length !in 1..4 || !part.all { it in '0'..'9' } }) {
            return null
        }
        return parts[0].toInt() to parts[1].toInt()
    }

    /**
     * The host of an `http(s)` URL, lower-cased — or null for anything that
     * is not a plain web address. Hand-rolled and strict so both ports agree
     * byte for byte: ASCII only (no look-alike letters, and nothing a case
     * mapping could fold into ASCII), no user-info (`a@b` is how a link hides
     * its real host), DNS labels or an IPv4 address.
     */
    fun hostOfURL(url: String): String? {
        if (!url.all { it.code in 0x21..0x7E }) return null
        val lower = buildString(url.length) { url.forEach { append(if (it in 'A'..'Z') it + 32 else it) } }
        val afterScheme = when {
            lower.startsWith("https://") -> lower.substring(8)
            lower.startsWith("http://") -> lower.substring(7)
            else -> return null
        }
        var host = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        if ('@' in host) return null
        val colon = host.lastIndexOf(':')
        if (colon >= 0) {                                                    // ':port'
            val port = host.substring(colon + 1)
            if (port.length !in 1..5 || !port.all { it in '0'..'9' } || port.toInt() > 65_535) return null
            host = host.substring(0, colon)
        }
        if (host.length !in 1..253) return null
        if (!host.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' }) return null
        if (host.split('.').any { it.isEmpty() }) return null
        return host
    }

    // MARK: Naming tables

    /**
     * IANA "COSE Elliptic Curves" — the ISO/IEC 18013-5 Table 22 curves and
     * secp256k1.
     */
    fun curveName(crv: ULong): String? = when (crv) {
        1uL -> "P-256"
        2uL -> "P-384"
        3uL -> "P-521"
        4uL -> "X25519"
        5uL -> "X448"
        6uL -> "Ed25519"
        7uL -> "Ed448"
        8uL -> "secp256k1"
        256uL -> "brainpoolP256r1"
        257uL -> "brainpoolP320r1"
        258uL -> "brainpoolP384r1"
        259uL -> "brainpoolP512r1"
        else -> null
    }

    /** IANA "COSE Key Types" that can hold an ECDH key. */
    fun keyTypeName(kty: ULong): String? = when (kty) {
        1uL -> "OKP"
        2uL -> "EC2"
        else -> null
    }
}
