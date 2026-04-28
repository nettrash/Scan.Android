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
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val address = uri.schemeSpecificPart?.substringBefore('?') ?: return null
        val subject = uri.getQueryParameter("subject")
        val body = uri.getQueryParameter("body")
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
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val num = uri.schemeSpecificPart?.substringBefore('?') ?: return null
        val body = uri.getQueryParameter("body")
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
