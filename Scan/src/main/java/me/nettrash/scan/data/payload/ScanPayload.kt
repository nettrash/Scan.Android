package me.nettrash.scan.data.payload

import android.net.Uri
import me.nettrash.scan.scanner.Symbology
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * A parsed barcode/QR payload. Mirrors the iOS sealed enum.
 *
 * The UI uses `kindLabel` for a short capsule, then dispatches on the
 * concrete subtype to render dedicated smart-action affordances.
 */
sealed class ScanPayload {
    data class Url(val url: String) : ScanPayload()
    data class Email(val address: String, val subject: String?, val body: String?) : ScanPayload()
    data class Phone(val number: String) : ScanPayload()
    data class Sms(val number: String, val body: String?) : ScanPayload()
    data class Wifi(
        val ssid: String,
        val password: String?,
        val security: String?,
        val hidden: Boolean
    ) : ScanPayload()
    data class Geo(val latitude: Double, val longitude: Double, val query: String?) : ScanPayload()
    data class Contact(val payload: ContactPayload) : ScanPayload()
    data class Calendar(val payload: CalendarPayload) : ScanPayload()
    data class Otp(val raw: String) : ScanPayload()
    data class ProductCode(val code: String, val system: String) : ScanPayload()
    data class Crypto(val payload: CryptoPayload) : ScanPayload()
    data class EpcPayment(val payload: EPCPaymentPayload) : ScanPayload()
    data class SwissQRBill(val payload: SwissQRBillPayload) : ScanPayload()
    data class RuPayment(val payload: RussianPaymentPayload) : ScanPayload()
    data class FnsReceipt(val payload: FNSReceiptPayload) : ScanPayload()
    data class EmvPayment(val payload: EMVPaymentPayload) : ScanPayload()
    data class SufReceipt(val payload: SerbianFiscalReceiptPayload) : ScanPayload()
    data class IpsPayment(val payload: SerbianIPSPayload) : ScanPayload()
    data class UpiPayment(val payload: UPIPayload) : ScanPayload()
    data class CzechSPD(val payload: CzechSPDPayload) : ScanPayload()
    data class PaBySquare(val payload: PayBySquarePayload) : ScanPayload()
    data class Regional(val payload: RegionalPaymentPayload) : ScanPayload()
    data class Magnet(val payload: MagnetPayload) : ScanPayload()
    data class RichUrl(val payload: RichURLPayload) : ScanPayload()
    data class GS1(val payload: GS1Payload) : ScanPayload()
    data class BoardingPass(val payload: BoardingPassPayload) : ScanPayload()
    data class DrivingLicense(val payload: DrivingLicensePayload) : ScanPayload()
    // New in 1.9.
    data class WalletConnect(val payload: WalletConnectPayload) : ScanPayload()
    data class Nostr(val payload: NostrPayload) : ScanPayload()
    data class OtpMigration(val payload: OTPMigrationPayload) : ScanPayload()
    data class PlusCode(val payload: PlusCodePayload) : ScanPayload()
    data class What3Words(val payload: What3WordsPayload) : ScanPayload()
    data class Iban(val payload: IBANPayload) : ScanPayload()
    data class Text(val text: String) : ScanPayload()

    /** Short label describing the payload kind, for UI badges. */
    val kindLabel: String
        get() = when (this) {
            is Url -> "URL"
            is Email -> "Email"
            is Phone -> "Phone"
            is Sms -> "SMS"
            is Wifi -> "Wi-Fi"
            is Geo -> "Location"
            is Contact -> "Contact"
            is Calendar -> "Calendar"
            is Otp -> "OTP"
            is ProductCode -> "Product"
            is Crypto -> "Crypto"
            is EpcPayment -> "SEPA Payment"
            is SwissQRBill -> "QR-bill (Swiss)"
            is RuPayment -> "Payment"
            is FnsReceipt -> "Receipt"
            is EmvPayment -> "Merchant QR"
            is SufReceipt -> "Receipt (RS)"
            is IpsPayment -> "IPS Payment (RS)"
            is UpiPayment -> "UPI"
            is CzechSPD -> "SPD (CZ)"
            is PaBySquare -> "Pay by Square (SK)"
            is Regional -> payload.scheme.displayName
            is Magnet -> "Magnet"
            is RichUrl -> payload.kind.displayName
            is GS1 -> "GS1"
            is BoardingPass -> "Boarding Pass"
            is DrivingLicense -> "Driver's Licence"
            is WalletConnect -> "WalletConnect"
            is Nostr -> "Nostr"
            is OtpMigration -> "2FA export"
            is PlusCode -> "Plus Code"
            is What3Words -> "what3words"
            is Iban -> "IBAN"
            is Text -> "Text"
        }
}

/** A simple contact pulled out of vCard / MECARD payloads. */
data class ContactPayload(
    val fullName: String?,
    val phones: List<String>,
    val emails: List<String>,
    val urls: List<String>,
    val organization: String?,
    val note: String?
)

object ScanPayloadParser {

    /**
     * Parse a decoded barcode string into a structured payload.
     * `symbology` is used to recognise pure 1D product codes (EAN/UPC).
     */
    fun parse(raw: String, symbology: Symbology = Symbology.UNKNOWN): ScanPayload {
        val trimmed = raw.trim()

        when (symbology) {
            Symbology.EAN8, Symbology.EAN13, Symbology.UPCE, Symbology.UPCA, Symbology.ITF14 ->
                return ScanPayload.ProductCode(trimmed, symbology.displayName)
            else -> Unit
        }

        val lower = trimmed.lowercase(Locale.ROOT)

        // Identity & travel — high-confidence prefixes, must run before any
        // URL classification.

        // IATA Bar Coded Boarding Pass.
        if (BoardingPassParser.looksLikeBoardingPass(trimmed)) {
            BoardingPassParser.parse(trimmed)?.let { return ScanPayload.BoardingPass(it) }
        }
        // AAMVA driver's licence.
        if (DrivingLicenseParser.looksLikeAAMVA(trimmed)) {
            DrivingLicenseParser.parse(trimmed)?.let { return ScanPayload.DrivingLicense(it) }
        }

        // EPC SEPA Payment QR — line 1 is "BCD".
        if (trimmed.startsWith("BCD\n") || trimmed.startsWith("BCD\r\n")) {
            BankPaymentParser.parseEPC(trimmed)?.let { return ScanPayload.EpcPayment(it) }
        }

        // Swiss QR-bill — line 1 is "SPC".
        if (trimmed.startsWith("SPC\n") || trimmed.startsWith("SPC\r\n")) {
            BankPaymentParser.parseSwissQRBill(trimmed)?.let { return ScanPayload.SwissQRBill(it) }
        }

        // Russian unified payment.
        if (trimmed.startsWith("ST00012|") || trimmed.startsWith("ST00011|")) {
            BankPaymentParser.parseRussianPayment(trimmed)?.let { return ScanPayload.RuPayment(it) }
        }

        // EMVCo Merchant QR — Payload Format Indicator is "00 02 01".
        if (trimmed.startsWith("000201")) {
            BankPaymentParser.parseEMV(trimmed)?.let { return ScanPayload.EmvPayment(it) }
        }

        // FNS retail receipt verification QR.
        if (BankPaymentParser.looksLikeFNSReceipt(trimmed)) {
            BankPaymentParser.parseFNSReceipt(trimmed)?.let { return ScanPayload.FnsReceipt(it) }
        }

        // Serbian NBS IPS QR.
        if (BankPaymentParser.looksLikeSerbianIPS(trimmed)) {
            BankPaymentParser.parseSerbianIPS(trimmed)?.let { return ScanPayload.IpsPayment(it) }
        }

        // Serbian fiscal receipt verification URL — must be checked before
        // the generic URL fallback so we get the dedicated "Verify Receipt"
        // smart action.
        if (lower.contains("suf.purs.gov.rs")) {
            BankPaymentParser.parseSerbianSUFReceipt(trimmed)?.let { return ScanPayload.SufReceipt(it) }
        }

        // Cryptocurrency wallet URIs.
        val schemeIdx = trimmed.indexOf(':')
        if (schemeIdx > 0) {
            val scheme = trimmed.substring(0, schemeIdx).lowercase(Locale.ROOT)
            if (CryptoURIParser.knownSchemes.contains(scheme)) {
                CryptoURIParser.parse(trimmed)?.let { return ScanPayload.Crypto(it) }
            }
        }

        // WalletConnect pairing URI — `wc:<topic>@<version>?…`.
        if (lower.startsWith("wc:")) {
            WalletConnectParser.parse(trimmed)?.let { return ScanPayload.WalletConnect(it) }
        }

        // Nostr — `nostr:` URI. Bare NIP-19 tokens are caught near the end.
        if (lower.startsWith("nostr:")) {
            NostrParser.parse(trimmed)?.let { return ScanPayload.Nostr(it) }
        }

        // UPI (India).
        if (lower.startsWith("upi:")) {
            RegionalPaymentParser.parseUPI(trimmed)?.let { return ScanPayload.UpiPayment(it) }
        }

        // Other regional URI-scheme payments.
        if (schemeIdx > 0) {
            val scheme = trimmed.substring(0, schemeIdx).lowercase(Locale.ROOT)
            if (RegionalPaymentParser.knownURISchemes.containsKey(scheme)) {
                RegionalPaymentParser.parseRegional(trimmed)?.let { return ScanPayload.Regional(it) }
            }
        }

        // Czech SPD (Spayd).
        if (trimmed.startsWith("SPD*")) {
            RegionalPaymentParser.parseCzechSPD(trimmed)?.let { return ScanPayload.CzechSPD(it) }
        }

        // Slovak Pay by Square — recognised heuristically.
        if (RegionalPaymentParser.looksLikePayBySquare(trimmed)) {
            RegionalPaymentParser.parsePayBySquare(trimmed)?.let { return ScanPayload.PaBySquare(it) }
        }

        // Wi-Fi: WIFI:T:WPA;S:My_Network;P:my_password;H:false;;
        if (lower.startsWith("wifi:")) {
            return parseWifi(trimmed) ?: ScanPayload.Text(trimmed)
        }

        // mailto:
        if (lower.startsWith("mailto:")) {
            return parseMailto(trimmed) ?: ScanPayload.Text(trimmed)
        }

        // tel:
        if (lower.startsWith("tel:")) {
            return ScanPayload.Phone(trimmed.substring(4))
        }

        // sms: / smsto:
        if (lower.startsWith("smsto:") || lower.startsWith("sms:")) {
            return parseSMS(trimmed) ?: ScanPayload.Text(trimmed)
        }

        // geo:
        if (lower.startsWith("geo:")) {
            return parseGeo(trimmed) ?: ScanPayload.Text(trimmed)
        }

        // Google Authenticator bulk export — `otpauth-migration://offline?data=…`.
        if (lower.startsWith("otpauth-migration:")) {
            OTPMigrationParser.parse(trimmed)?.let { return ScanPayload.OtpMigration(it) }
        }

        // otpauth://
        if (lower.startsWith("otpauth://")) {
            return ScanPayload.Otp(trimmed)
        }

        // BEGIN:VCARD ...
        if (lower.startsWith("begin:vcard")) {
            return parseVCard(trimmed)
        }

        // MECARD:
        if (lower.startsWith("mecard:")) {
            return parseMECard(trimmed)
        }

        // BEGIN:VEVENT / VCALENDAR
        if (CalendarPayloadParser.looksLikeICalendar(trimmed)) {
            CalendarPayloadParser.parse(trimmed)?.let { return ScanPayload.Calendar(it) }
        }

        // magnet:?xt=urn:btih:…
        if (MagnetURIParser.looksLikeMagnet(trimmed)) {
            MagnetURIParser.parse(trimmed)?.let { return ScanPayload.Magnet(it) }
        }

        // GS1 — parens / FNC1 forms (Digital Link is a URL, handled below).
        if (trimmed.startsWith("(") && GS1Parser.looksLikeGS1(trimmed)) {
            GS1Parser.parse(trimmed)?.let { return ScanPayload.GS1(it) }
        }
        if (trimmed.firstOrNull()?.code == 0x1D) {
            GS1Parser.parse(trimmed)?.let { return ScanPayload.GS1(it) }
        }

        // what3words — `///word.word.word` or `w3w://…`.
        if (trimmed.startsWith("///") || lower.startsWith("w3w://")) {
            What3WordsParser.parse(trimmed)?.let { return ScanPayload.What3Words(it) }
        }

        // Service URIs with a custom app scheme — Alipay, WeChat Pay, TWINT,
        // Signal, Matrix. The https forms are handled by the URL block below.
        if (schemeIdx > 0) {
            val sc = trimmed.substring(0, schemeIdx).lowercase(Locale.ROOT)
            if (sc in setOf("alipay", "alipays", "alipayqr", "weixin", "wxp",
                    "wechat", "twint", "sgnl", "matrix")) {
                RichURLParser.parse(trimmed)?.let { return ScanPayload.RichUrl(it) }
            }
        }

        // URL-ish
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
        val sch = uri?.scheme?.lowercase(Locale.ROOT)
        if (sch == "http" || sch == "https") {
            // GS1 Digital Link first (host-agnostic).
            if (GS1Parser.looksLikeGS1(trimmed)) {
                GS1Parser.parse(trimmed)?.let { return ScanPayload.GS1(it) }
            }
            // Rich-URL flavour next — Maps URLs are re-classified to .Geo
            // when we can pull coordinates out, so the user gets the same
            // "Open in Maps" smart action they would for a `geo:` payload.
            RichURLParser.parse(trimmed)?.let { rich ->
                if (rich.kind == RichURLPayload.Kind.GOOGLE_MAPS ||
                    rich.kind == RichURLPayload.Kind.APPLE_MAPS) {
                    val lat = rich.fields.firstOrNull { it.label == "Latitude" }?.value?.toDoubleOrNull()
                    val lon = rich.fields.firstOrNull { it.label == "Longitude" }?.value?.toDoubleOrNull()
                    val q = rich.fields.firstOrNull { it.label == "Query" }?.value
                    if (lat != null && lon != null) {
                        return ScanPayload.Geo(lat, lon, q)
                    }
                }
                return ScanPayload.RichUrl(rich)
            }
            return ScanPayload.Url(trimmed)
        }

        // Bare cryptocurrency address / Lightning token — last-ditch before
        // text fallback because these are otherwise indistinguishable from
        // arbitrary alphanumeric strings.
        CryptoURIParser.parseBare(trimmed)?.let { return ScanPayload.Crypto(it) }

        // Bare Nostr NIP-19 token (npub1… / note1… / nevent1… / nsec1…).
        if (NostrParser.looksLikeBare(trimmed)) {
            NostrParser.parse(trimmed)?.let { return ScanPayload.Nostr(it) }
        }

        // Open Location Code (Plus Code) — e.g. "849VCWC8+R9 Stockholm".
        PlusCodeParser.parse(trimmed)?.let { return ScanPayload.PlusCode(it) }

        // Bare IBAN — structurally valid + ISO 7064 mod-97 checksum.
        IBANParser.parse(trimmed)?.let { return ScanPayload.Iban(it) }

        return ScanPayload.Text(trimmed)
    }

    // ---- Wi-Fi --------------------------------------------------------

    private fun splitSemicolonFields(body: String): List<Pair<String, String>> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                current.append(body[i + 1])
                i += 2; continue
            }
            if (c == ';') {
                fields += current.toString()
                current.setLength(0)
            } else {
                current.append(c)
            }
            i++
        }
        if (current.isNotEmpty()) fields += current.toString()
        return fields.mapNotNull { f ->
            if (f.isEmpty()) return@mapNotNull null
            val sep = f.indexOf(':').takeIf { it >= 0 } ?: return@mapNotNull null
            f.substring(0, sep) to f.substring(sep + 1)
        }
    }

    private fun parseWifi(raw: String): ScanPayload.Wifi? {
        val colon = raw.indexOf(':').takeIf { it > 0 } ?: return null
        val body = raw.substring(colon + 1)
        val pairs = splitSemicolonFields(body)
        var ssid: String? = null
        var password: String? = null
        var security: String? = null
        var hidden = false
        for ((k, v) in pairs) {
            when (k.uppercase(Locale.ROOT)) {
                "S" -> ssid = v
                "P" -> password = v
                "T" -> security = v
                "H" -> hidden = v.equals("true", ignoreCase = true)
            }
        }
        val s = ssid ?: return null
        return ScanPayload.Wifi(s, password, security, hidden)
    }

    // ---- mailto -------------------------------------------------------

    private fun parseMailto(raw: String): ScanPayload.Email? {
        // `mailto:…?subject=…&body=…` is an *opaque* URI (no `//` after
        // the scheme), so `Uri.parse(raw).getQueryParameter(...)` throws
        // UnsupportedOperationException on real Android even though
        // Robolectric tolerates it. Parse by hand instead so the same
        // code paths run in unit tests and on-device.
        if (!raw.regionMatches(0, "mailto:", 0, 7, ignoreCase = true)) return null
        val ssp = raw.substring(7)
        val qIdx = ssp.indexOf('?')
        val rawAddress = if (qIdx < 0) ssp else ssp.substring(0, qIdx)
        val address = runCatching { java.net.URLDecoder.decode(rawAddress, "UTF-8") }
            .getOrElse { rawAddress }
        if (address.isBlank()) return null

        var subject: String? = null
        var body: String? = null
        if (qIdx >= 0 && qIdx + 1 < ssp.length) {
            for (pair in ssp.substring(qIdx + 1).split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val key = if (eq < 0) pair else pair.substring(0, eq)
                val rawValue = if (eq < 0) "" else pair.substring(eq + 1)
                val value = runCatching {
                    java.net.URLDecoder.decode(rawValue, "UTF-8")
                }.getOrElse { rawValue }
                when (key.lowercase(Locale.ROOT)) {
                    "subject" -> subject = value
                    "body"    -> body    = value
                }
            }
        }
        return ScanPayload.Email(address, subject, body)
    }

    // ---- sms ----------------------------------------------------------

    private fun parseSMS(raw: String): ScanPayload.Sms? {
        val lower = raw.lowercase(Locale.ROOT)
        if (lower.startsWith("smsto:")) {
            val rest = raw.substring(6)
            val parts = rest.split(':', limit = 2)
            val num = parts.getOrNull(0).orEmpty()
            val body = parts.getOrNull(1)
            return ScanPayload.Sms(num, body)
        }
        // Same opaque-URI trap as mailto: `sms:` has no `//` so
        // `getQueryParameter` throws on real Android. Parse by hand.
        if (!lower.startsWith("sms:")) return null
        val ssp = raw.substring(4)
        val qIdx = ssp.indexOf('?')
        val num = if (qIdx < 0) ssp else ssp.substring(0, qIdx)
        if (num.isBlank()) return null
        var body: String? = null
        if (qIdx >= 0 && qIdx + 1 < ssp.length) {
            for (pair in ssp.substring(qIdx + 1).split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val key = if (eq < 0) pair else pair.substring(0, eq)
                val rawValue = if (eq < 0) "" else pair.substring(eq + 1)
                if (key.equals("body", ignoreCase = true)) {
                    body = runCatching {
                        java.net.URLDecoder.decode(rawValue, "UTF-8")
                    }.getOrElse { rawValue }
                }
            }
        }
        return ScanPayload.Sms(num, body)
    }

    // ---- geo ----------------------------------------------------------

    private fun parseGeo(raw: String): ScanPayload.Geo? {
        val body = raw.substring(4)
        val parts = body.split('?', limit = 2)
        val coords = parts[0].split(',')
        if (coords.size < 2) return null
        val lat = coords[0].toDoubleOrNull() ?: return null
        val lon = coords[1].toDoubleOrNull() ?: return null
        var query: String? = null
        if (parts.size > 1) {
            for (kv in parts[1].split('&')) {
                val kvParts = kv.split('=', limit = 2)
                if (kvParts.size == 2 && kvParts[0] == "q") {
                    query = runCatching {
                        URLDecoder.decode(kvParts[1], StandardCharsets.UTF_8.name())
                    }.getOrDefault(kvParts[1])
                    break
                }
            }
        }
        return ScanPayload.Geo(lat, lon, query)
    }

    // ---- vCard --------------------------------------------------------

    private fun parseVCard(raw: String): ScanPayload.Contact {
        var fullName: String? = null
        val phones = mutableListOf<String>()
        val emails = mutableListOf<String>()
        val urls = mutableListOf<String>()
        var organization: String? = null
        var note: String? = null

        val lines = raw.replace("\r\n", "\n").split('\n')
        for (line in lines) {
            val colon = line.indexOf(':').takeIf { it > 0 } ?: continue
            val head = line.substring(0, colon).uppercase(Locale.ROOT)
            val value = line.substring(colon + 1)
            val key = head.split(';').firstOrNull() ?: head
            when (key) {
                "FN" -> fullName = value
                "N" -> {
                    if (fullName == null) {
                        val parts = value.split(';')
                        val composed = listOfNotNull(parts.getOrNull(1), parts.getOrNull(0))
                            .filter { it.isNotEmpty() }
                            .joinToString(" ")
                        fullName = if (composed.isEmpty()) value else composed
                    }
                }
                "TEL" -> phones += value
                "EMAIL" -> emails += value
                "URL" -> urls += value
                "ORG" -> organization = value
                "NOTE" -> note = value
            }
        }
        return ScanPayload.Contact(
            ContactPayload(fullName, phones, emails, urls, organization, note)
        )
    }

    // ---- MECARD -------------------------------------------------------

    private fun parseMECard(raw: String): ScanPayload {
        val colon = raw.indexOf(':').takeIf { it > 0 } ?: return ScanPayload.Text(raw)
        val body = raw.substring(colon + 1)
        val pairs = splitSemicolonFields(body)
        var fullName: String? = null
        val phones = mutableListOf<String>()
        val emails = mutableListOf<String>()
        val urls = mutableListOf<String>()
        var organization: String? = null
        var note: String? = null
        for ((k, v) in pairs) {
            when (k.uppercase(Locale.ROOT)) {
                "N" -> {
                    val parts = v.split(',')
                    fullName = if (parts.size >= 2) "${parts[1]} ${parts[0]}" else v
                }
                "TEL" -> phones += v
                "EMAIL" -> emails += v
                "URL" -> urls += v
                "ORG" -> organization = v
                "NOTE" -> note = v
            }
        }
        return ScanPayload.Contact(
            ContactPayload(fullName, phones, emails, urls, organization, note)
        )
    }
}

// ---- 2FA bulk export (otpauth-migration://) — 1.9 ---------------------

/** A decoded Google-Authenticator bulk export. Surfaces *which* accounts
 *  are inside (issuer + name + type) but never the shared secrets. Mirrors
 *  iOS [OTPMigrationPayload]. */
data class OTPMigrationPayload(
    val accounts: List<Account>,
    val raw: String
) {
    data class Account(val issuer: String?, val name: String?, val type: String)

    fun labelledFields(): List<LabelledField> {
        val rows = mutableListOf(
            LabelledField("Format", "Google Authenticator export"),
            LabelledField("Accounts", accounts.size.toString()),
        )
        accounts.forEachIndexed { i, a ->
            val title = listOfNotNull(a.issuer, a.name).filter { it.isNotEmpty() }.joinToString(" — ")
            rows += LabelledField("${i + 1}. ${a.type}", title.ifEmpty { "(unnamed)" })
        }
        return rows
    }
}

object OTPMigrationParser {
    fun parse(raw: String): OTPMigrationPayload? {
        if (!raw.lowercase(Locale.ROOT).startsWith("otpauth-migration:")) return null
        val dataParam = extractDataParam(raw) ?: return OTPMigrationPayload(emptyList(), raw)
        val bytes = decodeBase64(dataParam) ?: return OTPMigrationPayload(emptyList(), raw)
        return OTPMigrationPayload(decodeMigration(bytes), raw)
    }

    private fun extractDataParam(raw: String): String? {
        val q = raw.indexOf('?').takeIf { it >= 0 } ?: return null
        for (pair in raw.substring(q + 1).split('&')) {
            val kv = pair.split('=', limit = 2)
            if (kv.size == 2 && kv[0].equals("data", ignoreCase = true)) {
                return runCatching {
                    URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name())
                }.getOrDefault(kv[1])
            }
        }
        return null
    }

    private fun decodeBase64(s: String): ByteArray? {
        var t = s.replace('-', '+').replace('_', '/')
        val pad = (4 - t.length % 4) % 4
        t += "=".repeat(pad)
        return runCatching { java.util.Base64.getDecoder().decode(t) }.getOrNull()
    }

    private fun decodeMigration(data: ByteArray): List<OTPMigrationPayload.Account> {
        val reader = ProtoReader(data)
        val accounts = mutableListOf<OTPMigrationPayload.Account>()
        while (true) {
            val tag = reader.readTag() ?: break
            if (tag.field == 1 && tag.wire == 2) {
                reader.readBytes()?.let { accounts += decodeParameters(it) }
            } else {
                reader.skip(tag.wire)
            }
        }
        return accounts
    }

    private fun decodeParameters(data: ByteArray): OTPMigrationPayload.Account {
        val reader = ProtoReader(data)
        var name: String? = null
        var issuer: String? = null
        var type = "OTP"
        while (true) {
            val tag = reader.readTag() ?: break
            when {
                tag.field == 2 && tag.wire == 2 -> name = reader.readString()
                tag.field == 3 && tag.wire == 2 -> issuer = reader.readString()
                tag.field == 6 && tag.wire == 0 -> type = when (reader.readVarint()) {
                    1L -> "HOTP"
                    2L -> "TOTP"
                    else -> "OTP"
                }
                else -> reader.skip(tag.wire)
            }
        }
        return OTPMigrationPayload.Account(issuer, name, type)
    }
}

/** Minimal protobuf wire-format reader for the Google-Authenticator
 *  `MigrationPayload` message. Mirrors the iOS `ProtoReader`. */
private class ProtoReader(private val bytes: ByteArray) {
    private var i = 0
    private val atEnd get() = i >= bytes.size

    data class Tag(val field: Int, val wire: Int)

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            i++
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift >= 64) break
        }
        return result
    }

    fun readTag(): Tag? {
        if (atEnd) return null
        val key = readVarint()
        return Tag((key ushr 3).toInt(), (key and 0x7).toInt())
    }

    fun readBytes(): ByteArray? {
        val len = readVarint().toInt()
        if (len < 0 || i + len > bytes.size) return null
        val out = bytes.copyOfRange(i, i + len)
        i += len
        return out
    }

    fun readString(): String? = readBytes()?.toString(Charsets.UTF_8)

    fun skip(wire: Int) {
        when (wire) {
            0 -> readVarint()
            2 -> readBytes()
            5 -> i += 4
            1 -> i += 8
            else -> i = bytes.size
        }
    }
}

// ---- Open Location Code (Plus Code) — 1.9 -----------------------------

/** A recognised Open Location Code. We hand it to Maps verbatim rather than
 *  decoding to lat/lon. Mirrors iOS [PlusCodePayload]. */
data class PlusCodePayload(val code: String, val locality: String?, val raw: String) {
    val mapsQuery: String get() = if (!locality.isNullOrEmpty()) "$code $locality" else code
    fun labelledFields(): List<LabelledField> {
        val rows = mutableListOf(LabelledField("Plus Code", code))
        if (!locality.isNullOrEmpty()) rows += LabelledField("Locality", locality)
        return rows
    }
}

object PlusCodeParser {
    private val regex = Regex(
        "^([23456789CFGHJMPQRVWX]{8}\\+[23456789CFGHJMPQRVWX]{2,3})(?:\\s+(.+))?$",
        RegexOption.IGNORE_CASE
    )

    fun parse(raw: String): PlusCodePayload? {
        val s = raw.trim()
        val m = regex.matchEntire(s) ?: return null
        val code = m.groupValues[1].uppercase(Locale.ROOT)
        val locality = m.groupValues.getOrNull(2)?.trim()?.ifEmpty { null }
        return PlusCodePayload(code, locality, s)
    }
}

// ---- what3words — 1.9 -------------------------------------------------

/** A what3words address (`///filled.count.soap`). Mirrors iOS
 *  [What3WordsPayload]. */
data class What3WordsPayload(val words: String, val raw: String) {
    val address: String get() = "///$words"
    fun labelledFields(): List<LabelledField> = listOf(LabelledField("what3words", address))
}

object What3WordsParser {
    fun parse(raw: String): What3WordsPayload? {
        var s = raw.trim()
        val lower = s.lowercase(Locale.ROOT)
        s = when {
            lower.startsWith("w3w://") -> s.substring("w3w://".length)
            s.startsWith("///") -> s.substring(3)
            else -> return null
        }
        s = s.split('?').first().split('#').first()
        val parts = s.split('.')
        if (parts.size != 3 || parts.any { it.isEmpty() || !it.all { c -> c.isLetter() } }) return null
        return What3WordsPayload(parts.joinToString("."), raw)
    }
}
