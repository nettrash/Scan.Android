package me.nettrash.scan.data.payload

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Base64

//
//  On-device QR safety assessment — the "should I tap this?" answer.
//  Port of the iOS app's `Scan/QrRisk.swift`.
//
//  A scanned code is an instruction the user cannot read: a parking-meter
//  sticker, a donation box, a restaurant table tent, an emailed invoice. The
//  abuse pattern in all of them is the same — swap the printed code for
//  yours, or shorten a link so the destination is invisible. This file scores
//  those patterns from the payload alone.
//
//  There is deliberately **no** URL-reputation or "safe browsing" service
//  call: sending the user's scanned URLs to somebody's API to be graded
//  breaks the on-device invariant the app is built on. Everything here is
//  string analysis over data already in memory.
//

/** How worried the user should be. Enum order *is* severity order. */
enum class QrRiskLevel {
    SAFE,
    CAUTION,
    DANGER,
    ;

    /** Headline word for the banner, matching the iOS labels. */
    val label: String
        get() = when (this) {
            SAFE -> "Looks fine"
            CAUTION -> "Check before you open"
            DANGER -> "Suspicious"
        }
}

/**
 * One thing the assessor noticed. [code] is a stable identifier — it is what
 * tests assert and what a future "mute this check" setting would store, so
 * display text can change underneath it. Mirrors iOS `QrRiskFinding`.
 */
data class QrRiskFinding(
    val code: String,
    val title: String,
    val detail: String,
    val severity: Severity,
) {
    enum class Severity {
        /** Informational: does not raise the report's level. */
        NOTE,

        CAUTION,
        DANGER,
    }

    val id: String get() = code
}

/** The whole verdict for one decoded code. Mirrors iOS `QrRiskReport`. */
data class QrRiskReport(
    val level: QrRiskLevel,
    val findings: List<QrRiskFinding>,
) {
    val isBlocking: Boolean get() = level == QrRiskLevel.DANGER
    val isEmpty: Boolean get() = findings.isEmpty()

    val headline: String
        get() = when (level) {
            QrRiskLevel.SAFE ->
                findings.firstOrNull()?.title ?: "Nothing unusual detected"
            QrRiskLevel.CAUTION ->
                findings.firstOrNull { it.severity != QrRiskFinding.Severity.NOTE }?.title
                    ?: "Check before you open"
            QrRiskLevel.DANGER ->
                findings.firstOrNull { it.severity == QrRiskFinding.Severity.DANGER }?.title
                    ?: "Suspicious"
        }
}


/**
 * Pure string analysis over a decoded code. [assess] is the only entry point
 * the UI needs; [looksLookalike] and [registrableDomain] are public because
 * the identity sheets ask the same questions without duplicating the tables.
 */
object QrRiskAssessor {

    /**
     * Assess one decoded code. [codesInFrame] is the number of distinct codes
     * the camera saw in the same frame — more than one is the classic
     * sticker-over-sticker tamper, so the caller passes it in when known.
     */
    fun assess(
        raw: String,
        payload: ScanPayload? = null,
        codesInFrame: Int = 1,
    ): QrRiskReport {
        val found = mutableListOf<QrRiskFinding>()
        val trimmed = raw.trim()

        if (codesInFrame > 1) {
            found += finding(
                "multi-code-frame",
                "$codesInFrame codes in this frame",
                "More than one code is visible at once. That is how a tamper " +
                    "sticker is placed over a real one — make sure you are scanning the " +
                    "code the venue printed, not something stuck on top.",
                QrRiskFinding.Severity.CAUTION,
            )
        }

        // URL-shaped payloads get the full host treatment.
        val url = webUrl(trimmed)
        if (url != null) {
            found += analyse(url)
        } else if (isWebLink(payload)) {
            // The parser and the Open button took it for a web link, but it
            // does not split into a domain: a browser would repair it and go
            // somewhere nobody checked.
            found += finding(
                "unreadable-address",
                "Scan can't tell where this link goes",
                "The address is written in a way Scan cannot read a domain from, but a " +
                    "browser may still open it — somewhere you cannot check first.",
                QrRiskFinding.Severity.DANGER,
            )
        } else {
            val scheme = schemeOf(trimmed)
            if (scheme != null && scheme in dangerousSchemes) {
                found += finding(
                    "dangerous-scheme",
                    "$scheme code",
                    "This payload asks the system to run a $scheme action rather " +
                        "than show something. Nothing safe should need it.",
                    QrRiskFinding.Severity.DANGER,
                )
            } else if (scheme == "market") {
                found += finding(
                    "store-link",
                    "A Google Play store link",
                    "This code points at an app's page in Google Play (market:) rather than " +
                        "showing something. That is harmless in itself — install apps only " +
                        "from a developer you recognise.",
                    QrRiskFinding.Severity.NOTE,
                )
            }
        }
        // iOS also grades Foundation's re-encoding of the link, which is what
        // its Open button hands the system; Android opens the text itself,
        // which is what was graded above.

        // Secrets and keys that should never be sitting in a scannable code.
        val secret = exposedSecretKind(trimmed)
        if (secret != null) {
            found += finding(
                "secret-exposed",
                "Secret material in the code",
                "It contains a $secret. Anyone who can photograph this code can " +
                    "use it — treat it as a credential, not a link.",
                if (secret == "private key" || secret == "seed phrase") {
                    QrRiskFinding.Severity.DANGER
                } else {
                    QrRiskFinding.Severity.CAUTION
                },
            )
        }

        found += analyse(payload)

        val level = when {
            found.any { it.severity == QrRiskFinding.Severity.DANGER } -> QrRiskLevel.DANGER
            found.any { it.severity == QrRiskFinding.Severity.CAUTION } -> QrRiskLevel.CAUTION
            else -> QrRiskLevel.SAFE
        }
        return QrRiskReport(level, found)
    }

    // MARK: URL analysis

    /**
     * The web link to analyse: the code's own text, split the way a browser
     * splits it. Only http/https qualify — a rich URL can also be an app
     * scheme (`matrix:u/alice:example.org`, `weixin://…`), and grading one of
     * those as a web address reports "no domain" on a perfectly ordinary
     * code. Those fall through to the scheme check instead, exactly as on iOS.
     *
     * Split by hand ([parseUrl]) rather than with [android.net.Uri] /
     * [java.net.URI]: both are free to normalise a hostile host, and the
     * checks need the characters the victim will actually see — the same
     * ones the iOS port sees.
     */
    private fun webUrl(raw: String): DecodedUrl? {
        val url = parseUrl(raw) ?: return null
        return if (url.scheme == "http" || url.scheme == "https") url else null
    }

    /** The parser read the code as a web link, so the result sheet offers to open it. */
    private fun isWebLink(payload: ScanPayload?): Boolean {
        val link = when (payload) {
            is ScanPayload.Url -> payload.url
            is ScanPayload.RichUrl -> payload.payload.url
            else -> return false
        }
        return schemeOf(link.trim()) in setOf("http", "https")
    }

    private fun analyse(url: DecodedUrl): List<QrRiskFinding> {
        val found = mutableListOf<QrRiskFinding>()
        val name = HostName(url.host)
        val host = name.normalized

        if (host.isEmpty()) {
            found += finding(
                "no-host",
                "Address has no domain",
                "There is no readable domain name in this link.",
                QrRiskFinding.Severity.CAUTION,
            )
            // A login with nothing after it is still the login trick.
            if (url.userInfo == null) return found
        }

        // An internationalised name is not suspicious in itself — münchen.de
        // and президент.рф are what their owners are called. These two are.
        if (name.hasMalformedPunycode) {
            found += finding(
                "punycode-host",
                "Internationalised domain",
                "The domain $host carries an encoded (punycode) label that does not " +
                    "decode to valid text. Browsers reject names like this, and nothing " +
                    "legitimate needs one.",
                QrRiskFinding.Severity.DANGER,
            )
        } else if (name.isWholeScriptLookalike) {
            found += finding(
                "punycode-host",
                "Internationalised domain",
                "The domain $host is spelled in another alphabet with letters that " +
                    "pass for Latin ones, so it can read as a familiar address without " +
                    "being one. Compare it letter by letter with the site you meant to visit.",
                QrRiskFinding.Severity.DANGER,
            )
        }

        if (name.mixesAlphabets) {
            found += finding(
                "mixed-script-host",
                "Mixed-alphabet domain",
                "$host mixes characters from more than one alphabet. That is the " +
                    "signature of a homoglyph attack: it looks like a real brand but " +
                    "resolves somewhere else.",
                QrRiskFinding.Severity.DANGER,
            )
        }

        val brand = lookalikeBrand(name)
        val front = if (brand == null) brandInFront(name) else null
        if (brand != null) {
            val display = brandDisplay(brand)
            found += finding(
                "lookalike-domain",
                "Impersonates $display",
                "$host imitates $display, but $display does not operate on this domain.",
                QrRiskFinding.Severity.DANGER,
            )
        } else if (front != null) {
            val display = brandDisplay(front.first)
            found += finding(
                "lookalike-domain",
                "Impersonates $display",
                "$host puts $display's address in front of ${front.second}, which is where the " +
                    "link really goes — and $display does not operate on it.",
                QrRiskFinding.Severity.DANGER,
            )
        }

        val address = if (host.contains(":")) null else ipv4Address(host)
        if (host.contains(":")) { // IPv6
            found += finding(
                "ip-literal-host",
                "Points at a raw IP address",
                "The link goes straight to $host with no domain, so there is no " +
                    "certificate identity to check and no brand to compare against.",
                QrRiskFinding.Severity.CAUTION,
            )
        } else if (address != null) {
            val written = if (address == host) address else "$address (written as $host)"
            found += finding(
                "ip-literal-host",
                "Points at a raw IP address",
                "The link goes straight to $written with no domain, so there is no " +
                    "certificate identity to check and no brand to compare against.",
                if (isPrivateIP(address)) QrRiskFinding.Severity.NOTE else QrRiskFinding.Severity.CAUTION,
            )
        }

        // `https://paypal.com@evil.test/` renders as paypal.com in most
        // address bars but resolves to evil.test — the classic QR trick.
        if (url.userInfo != null) {
            found += finding(
                "credentials-in-url",
                "Login embedded in the link",
                "The address carries a username (and possibly a password) before " +
                    "the domain. Everything after the @ is the real destination — and " +
                    "it is not who it appears to be.",
                QrRiskFinding.Severity.DANGER,
            )
        }

        val port = url.port?.toIntOrNull()
        if (port != null && port !in setOf(80, 443, 8080, 8443)) {
            found += finding(
                "odd-port",
                "Unusual port :$port",
                "Shops and banks do not serve pages from port $port. Legitimate " +
                    "uses exist, but it is common on throwaway phishing hosts.",
                QrRiskFinding.Severity.CAUTION,
            )
        }

        if (url.scheme == "http") {
            found += finding(
                "plain-http",
                "Unencrypted (http)",
                "The page is served without TLS, so anything you type on it " +
                    "travels in the clear.",
                QrRiskFinding.Severity.CAUTION,
            )
        }

        val labels = host.split(".").filter { it.isNotEmpty() }
        // Levels in front of the registrable domain, so www.tax.service.gov.uk
        // (two, under gov.uk) is not counted like a.b.c.example.com (three).
        val subdomains = registrableStart(labels)
        if (subdomains > 2) {
            found += finding(
                "deep-subdomain",
                "Deeply nested subdomain",
                "$host stacks $subdomains subdomain levels. Attackers bury " +
                    "the brand they are faking in the middle of a long host.",
                QrRiskFinding.Severity.CAUTION,
            )
        }

        val tld = labels.lastOrNull()
        if (tld != null && tld in abuseTLDs) {
            found += finding(
                "abuse-tld",
                "High-abuse domain ending (.$tld)",
                "Domains ending .$tld are registered by the thousand for phishing " +
                    "and are cheap to throw away.",
                QrRiskFinding.Severity.CAUTION,
            )
        }

        val lastTwo = labels.takeLast(2).joinToString(".")
        if (host !in shortenerExceptions && (host in shorteners || lastTwo in shorteners)) {
            found += finding(
                "url-shortener",
                "Shortened link",
                "The destination is hidden behind a redirect service. Scan does " +
                    "not follow links, so the real address stays unknown until your " +
                    "browser does.",
                QrRiskFinding.Severity.CAUTION,
            )
        }

        val redirect = redirectTarget(url, host)
        if (redirect != null) {
            found += finding(
                "open-redirect",
                "Link bounces through a redirect parameter",
                "The trusted-looking domain $host is only carrying you onward — " +
                    "${redirect.first} sends you to ${redirect.second}.",
                QrRiskFinding.Severity.CAUTION,
            )
        }

        val query = url.query?.lowercase()
        if (query != null) {
            if (query.contains("data=") && looksLikeBase64(query)) {
                found += finding(
                    "base64-payload",
                    "Encoded blob in the link",
                    "The address carries a base64 payload, which usually means the " +
                        "real instruction is deliberately unreadable.",
                    QrRiskFinding.Severity.CAUTION,
                )
            }
        }

        if (url.absoluteString.length > 900) {
            found += finding(
                "oversized-url",
                "Very long link",
                "Over 900 characters. Long URLs like this typically smuggle a " +
                    "token or a second URL inside the path.",
                QrRiskFinding.Severity.NOTE,
            )
        }

        // Amazon's own parcel pages and PayPal's own invoices use these words
        // too; on the brand's own domain they are the site, not a lure.
        val pathish = (url.path + "?" + (url.query ?: "")).lowercase()
        val bait = if (isBrandOwned(labels)) null else phishingKeywords.firstOrNull { containsWord(it, pathish) }
        if (bait != null) {
            found += finding(
                "bait-keyword",
                "Urgent wording ($bait)",
                "Payment, refund and account-verification language in a link you " +
                    "did not ask for is the most common QR lure.",
                QrRiskFinding.Severity.CAUTION,
            )
        }

        return found
    }

    // MARK: Payload-shaped findings

    private fun analyse(payload: ScanPayload?): List<QrRiskFinding> {
        val found = mutableListOf<QrRiskFinding>()
        when (payload) {
            is ScanPayload.EmvPayment -> {
                val p = payload.payload
                if (p.merchantName?.trim()?.isEmpty() != false) {
                    found += finding(
                        "payment-no-merchant",
                        "Payment code with no merchant name",
                        "Nothing in this code identifies who gets the money. " +
                            "Confirm the name in your bank app before you approve.",
                        QrRiskFinding.Severity.CAUTION,
                    )
                }
                // A dynamic code (`01` = 12) is drawn on a screen for one
                // payment, so its amount is expected; the sticker trick needs
                // a printed one.
                if (p.valueFor("54") != null && p.valueFor("01") != "12") {
                    found += finding(
                        "static-amount-poster",
                        "Fixed amount baked into the code",
                        "The amount is set by the code, not by you. A sticker " +
                            "pasted over a genuine one quietly changes what you pay.",
                        QrRiskFinding.Severity.CAUTION,
                    )
                }
                if (p.valueFor("01") == "11") {
                    found += finding(
                        "static-payment-code",
                        "Static payment code",
                        "This QR works for unlimited payments and never changes, " +
                            "which is precisely why tampering with it pays. Check it is " +
                            "the venue's own.",
                        QrRiskFinding.Severity.CAUTION,
                    )
                }
            }

            is ScanPayload.EpcPayment -> {
                if (payload.payload.beneficiaryName?.trim()?.isEmpty() != false) {
                    found += finding(
                        "payment-no-payee",
                        "Payment code with no payee name",
                        "Only an IBAN is present. Check it against the invoice " +
                            "before paying — that is exactly what a swapped QR targets.",
                        QrRiskFinding.Severity.CAUTION,
                    )
                }
            }

            is ScanPayload.IpsPayment -> {
                if (payload.payload.valueFor("N")?.trim()?.isEmpty() != false) {
                    found += finding(
                        "payment-no-payee",
                        "Payment code with no recipient name",
                        "Confirm the recipient and the amount inside your bank " +
                            "app before you approve.",
                        QrRiskFinding.Severity.CAUTION,
                    )
                }
            }

            is ScanPayload.Crypto -> {
                if (payload.payload.amount != null) {
                    found += finding(
                        "crypto-amount-preset",
                        "Address and amount both preset",
                        "Verify the address characters in your wallet — a lookalike " +
                            "address sends funds somewhere unrecoverable.",
                        QrRiskFinding.Severity.CAUTION,
                    )
                }
            }

            is ScanPayload.Wifi -> {
                if (isOpenNetwork(payload.password, payload.security)) {
                    found += finding(
                        "open-wifi",
                        "Open network",
                        "This joins an unencrypted Wi-Fi network. Anything you send " +
                            "on it can be read by whoever named it.",
                        QrRiskFinding.Severity.CAUTION,
                    )
                }
            }

            is ScanPayload.Text -> {
                if (lureInText(payload.text)) {
                    found += finding(
                        "lure-text",
                        "Payment instruction in free text",
                        "Text like this is normally printed by a venue, not typed " +
                            "into a code by a stranger. Check the physical source before " +
                            "acting.",
                        QrRiskFinding.Severity.CAUTION,
                    )
                }
            }

            is ScanPayload.Otp, is ScanPayload.OtpMigration -> found += finding(
                "otp-share-warning",
                "Two-factor enrolment code",
                "Anyone holding this code can sign in as you. Nobody legitimate " +
                    "will ask you to scan one for them.",
                QrRiskFinding.Severity.CAUTION,
            )

            // The identity, invoice and passport kinds carry their cautions in
            // the explanation and the result sheet, as on iOS: a risk finding
            // here would put a banner on every ordinary wallet request.
            null, is ScanPayload.Url, is ScanPayload.RichUrl, is ScanPayload.Email,
            is ScanPayload.Phone, is ScanPayload.Sms, is ScanPayload.Geo,
            is ScanPayload.Contact, is ScanPayload.Calendar, is ScanPayload.ProductCode,
            is ScanPayload.SwissQRBill, is ScanPayload.RuPayment, is ScanPayload.FnsReceipt,
            is ScanPayload.SufReceipt, is ScanPayload.UpiPayment, is ScanPayload.CzechSPD,
            is ScanPayload.PaBySquare, is ScanPayload.Regional, is ScanPayload.Magnet,
            is ScanPayload.BoardingPass, is ScanPayload.DrivingLicense,
            is ScanPayload.WalletConnect, is ScanPayload.Nostr, is ScanPayload.PlusCode,
            is ScanPayload.What3Words, is ScanPayload.Iban, is ScanPayload.Passkey,
            is ScanPayload.GS1, is ScanPayload.MobileDrivingLicense,
            is ScanPayload.VerifiableCredential, is ScanPayload.EInvoice,
            is ScanPayload.DigitalProductPassport -> Unit
        }
        return found
    }

    // MARK: Helpers used elsewhere

    /**
     * Public so the identity sheets can ask the same question without
     * duplicating the brand table.
     */
    fun looksLookalike(host: String): Boolean {
        val name = HostName(host)
        return lookalikeBrand(name) != null || brandInFront(name) != null
    }

    /**
     * What the "This link looks suspicious" dialog says about a link inside
     * another code: the verdict, then the reason. The reason names the host
     * lower-cased (`paypai.com`) — never the link as written, whose capital I
     * passes for an l in a sans-serif font. Mirrors iOS `gatedLinkMessage(for:)`.
     */
    fun gatedLinkMessage(link: String): String {
        val report = assess(link)
        val reason = report.findings.firstOrNull { it.severity == QrRiskFinding.Severity.DANGER }?.detail
            ?: return report.headline
        return "${report.headline}\n\n$reason"
    }

    /**
     * A `WIFI:` code with no password and no security (absent, empty,
     * `nopass` or `none`) joins an unencrypted network. The explainer asks the
     * same question, so the two cannot disagree. Mirrors iOS `isOpenNetwork`.
     */
    fun isOpenNetwork(password: String?, security: String?): Boolean {
        if (!password.isNullOrEmpty()) return false
        val tag = security?.trim()?.uppercase() ?: ""
        return tag.isEmpty() || tag == "NOPASS" || tag == "NONE"
    }

    private fun finding(
        code: String,
        title: String,
        detail: String,
        severity: QrRiskFinding.Severity,
    ) = QrRiskFinding(code, title, detail, severity)

    private fun schemeOf(raw: String): String? {
        val end = raw.indexOf(':')
        if (end <= 0) return null
        val s = raw.substring(0, end).lowercase()
        return if (s.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) s else null
    }

    /** `market:` is not here: it only opens a Google Play listing, and gets a note instead. */
    private val dangerousSchemes = setOf(
        "javascript", "vbscript", "data", "file", "jar", "intent",
    )

    /** Brands are stored lowercased; this puts the capitalisation back for display. */
    private fun brandDisplay(brand: String): String {
        brandNames[brand]?.let { return it }
        val first = brand.firstOrNull() ?: return brand
        return first.uppercase() + brand.drop(1)
    }

    /** An IPv4 address in any spelling a browser accepts ([ipv4Address]), or anything with a colon, which in a host is IPv6. */
    fun isIpLiteral(host: String): Boolean = host.contains(":") || ipv4Address(host) != null

    /**
     * The host read the way a browser reads an IPv4 address (the WHATWG URL
     * Standard's IPv4 parser): one to four dot-separated numbers, each
     * decimal, octal (a leading 0) or hex (0x), the last one filling the
     * bytes that are left — so 3627732046, 0xd83ad60e and 0330.072.0326.016
     * are all 216.58.204.78. The dotted-quad form, or null when the host is
     * not IPv4. Mirrors iOS `ipv4Address(_:)`.
     */
    fun ipv4Address(host: String): String? {
        val parts = host.split(".").toMutableList()
        if (parts.size > 1 && parts.last().isEmpty()) parts.removeAt(parts.size - 1)
        if (parts.size !in 1..4) return null
        val numbers = parts.map { ipv4Number(it) ?: return null }
        if (numbers.dropLast(1).any { it > 255 } || numbers.last() >= 1L shl (8 * (5 - numbers.size))) return null
        var address = numbers.last()
        numbers.dropLast(1).forEachIndexed { index, number -> address += number shl (8 * (3 - index)) }
        return listOf(24, 16, 8, 0).joinToString(".") { ((address shr it) and 0xFF).toString() }
    }

    /**
     * One part of an IPv4 host as a number, or null when it is not one. A
     * value too large for any address comes back as 2^33, which every caller
     * rejects.
     */
    private fun ipv4Number(part: String): Long? {
        if (part.isEmpty()) return null
        var digits = part
        var radix = 10L
        if (digits.length >= 2 && digits[0] == '0' && (digits[1] == 'x' || digits[1] == 'X')) {
            digits = digits.substring(2)
            radix = 16L
        } else if (digits.length >= 2 && digits[0] == '0') {
            digits = digits.substring(1)
            radix = 8L
        }
        var value = 0L
        for (c in digits) {
            val digit = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                else -> return null
            }.toLong()
            if (digit >= radix) return null
            value = minOf(value * radix + digit, 1L shl 33)
        }
        return value
    }

    private fun isPrivateIP(host: String): Boolean {
        if (host.startsWith("10.") || host.startsWith("192.168.") ||
            host.startsWith("127.") || host.startsWith("169.254.")
        ) {
            return true
        }
        if (host.startsWith("172.")) {
            val second = host.split(".").getOrNull(1)?.toIntOrNull()
            if (second != null) return second in 16..31
        }
        return false
    }

    /**
     * Registrable-domain shorthand: the label in front of the longest public
     * suffix the table knows (`example.co.uk`, not `co.uk`), else the last two
     * labels.
     */
    fun registrableDomain(host: String): String {
        val labels = host.split(".").filter { it.isNotEmpty() }
        return labels.drop(registrableStart(labels)).joinToString(".")
    }

    /**
     * Index of the registrable domain's first label in [labels] — which is
     * also how many subdomain labels stand in front of it.
     */
    private fun registrableStart(labels: List<String>): Int {
        if (labels.size <= 2) return 0
        val lower = labels.map { it.lowercase() }
        var suffix = 1
        for (entry in multiPartSuffixes) {
            if (entry.size > suffix && entry.size < lower.size &&
                entry.indices.all { entry[it] == "*" || entry[it] == lower[lower.size - entry.size + it] }
            ) {
                suffix = entry.size
            }
        }
        return lower.size - suffix - 1
    }

    /**
     * The registrable domain is the brand's own name under `.com` or a
     * country ending (amazon.co.uk, paypal.de) — not a brand under some other
     * ending, which is a domain anybody could have bought.
     */
    private fun isBrandOwned(labels: List<String>): Boolean {
        val lower = labels.map { it.lowercase() }
        if (lower.isEmpty()) return false
        val start = registrableStart(lower)
        if (lower[start] !in brands) return false
        val tld = lower.drop(start + 1).lastOrNull() ?: return false
        return tld == "com" || (tld.length == 2 && tld.all { it in 'a'..'z' })
    }

    /**
     * The brand the domain is imitating, if any. Looked for in the first label
     * of the registrable domain only, because that is the part nobody else can
     * register:
     * - the brand as a hyphen-separated word of a longer label
     *   (`paypal-secure-login`, `revolut-pay`) — the brand run together with
     *   other letters is not enough, or `ubereats`, `appleinsider` and
     *   `startups` (UPS!) would all be "impersonations". A brand that is also
     *   an everyday word (`big-apple-pizza`, `wise-owl-books`) counts only
     *   when another word is about accounts, payments or parcels
     *   (`apple-support-login`, `ups-delivery`);
     * - a label that reads as the brand once look-alike characters are folded
     *   (`paypa1`, `paypaI`, `аpple`, `rnicrosoft`, `páypal`);
     * - for brands of six letters or more, one doubled letter or one swapped
     *   pair (`gooogle`, `paypla`) — not an arbitrary edit, which would make
     *   `telegraph` a Telegram fake and `wish` a Wise one.
     * A label that *is* the brand in another case (`Google.com`) is the brand:
     * DNS ignores case. Mirrors iOS `lookalikeBrand(_:)`.
     */
    private fun lookalikeBrand(name: HostName): String? {
        val labels = name.unicodeLabels
        if (labels.isEmpty()) return null
        val lower = labels.map { it.lowercase() }
        val start = registrableStart(lower)
        if (lower.drop(start).joinToString(".") in brandOwnedDomains) return null
        val label = labels[start]
        if (label.codePointCount(0, label.length) < 4) return null
        if (label.lowercase() in brands) return null
        val words = label.split("-").filter { it.isNotEmpty() }
        val lowerWords = words.map { it.lowercase() }
        val wordSkeletons = words.map { if (it.codePointCount(0, it.length) >= 4) confusableSkeleton(it) else null }
        val inPhishingContext = lowerWords.any { it in phishingContextWords }
        val folded = confusableSkeleton(label)
        for (brand in brands) {
            val target = brandSkeletons[brand] ?: brand
            if (words.size > 1) {
                // A look-alike spelling of the brand as a word is never innocent.
                val disguised = lowerWords.indices.any { lowerWords[it] != brand && wordSkeletons[it] == target }
                val named = brand in lowerWords && (inPhishingContext || brand !in commonWordBrands)
                if (disguised || named) return brand
            }
            if (folded == target) return brand
            if (brand.length >= 6 && isSlip(codePoints(folded), codePoints(target))) return brand
        }
        return null
    }

    /**
     * A brand's own address written in front of somebody else's domain —
     * `paypal.com.secure-login.info`, `apple.com-support.help` — the most
     * common phishing host there is: a brand label (or a look-alike of it)
     * followed by a label that reads as a domain ending, still in front of the
     * registrable domain, or followed by a registrable domain that starts
     * `com-` / `co-`. The brand and the domain the link really goes to.
     * Mirrors iOS `brandInFront(_:)`.
     */
    private fun brandInFront(name: HostName): Pair<String, String>? {
        val lower = name.unicodeLabels.map { it.lowercase() }
        val start = registrableStart(lower)
        if (start <= 0) return null
        for (index in 0 until start) {
            val skeleton = confusableSkeleton(name.unicodeLabels[index])
            val brand = brands.firstOrNull { (brandSkeletons[it] ?: it) == skeleton } ?: continue
            if (lower[start] == brand) continue
            val next = lower[index + 1]
            val endingFollows = if (index + 1 < start) {
                next in topLevelLikeLabels || (next.length == 2 && next.all { it in 'a'..'z' })
            } else {
                next.startsWith("com-") || next.startsWith("co-")
            }
            if (endingFollows) return brand to lower.drop(start).joinToString(".")
        }
        return null
    }

    /**
     * The Latin letters a label passes for: capital I and digits folded onto
     * the letters they are drawn like, other alphabets' look-alikes onto the
     * ASCII letter they imitate, accents dropped, `rn` read as `m` and `vv` as
     * `w`. Two labels with the same skeleton look the same at a glance.
     */
    internal fun confusableSkeleton(label: String): String {
        // Capital I is drawn exactly like a lower-case l in most sans-serif
        // faces ("paypaI"), so it is folded before case is lost.
        val lowered = label.replace("I", "l").lowercase()
        val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        val out = StringBuilder()
        for (cp in codePoints(decomposed)) {
            when (Character.getType(cp).toByte()) {
                Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> continue
            }
            val ascii = latinLookalikes[cp]
            if (ascii != null) out.append(ascii) else out.appendCodePoint(cp)
        }
        return out.toString().replace("rn", "m").replace("vv", "w")
    }

    /** A letter [confusableSkeleton] folds onto an ASCII letter. */
    internal fun passesForLatin(cp: Int): Boolean = latinLookalikes[cp]?.let { it in 'a'..'z' } == true

    /** [label] is [brand] with one letter doubled or two neighbours swapped. */
    private fun isSlip(label: List<Int>, brand: List<Int>): Boolean {
        if (label.size == brand.size + 1) {
            for (k in 1 until label.size) {
                if (label[k] != label[k - 1]) continue
                if (label.filterIndexed { index, _ -> index != k } == brand) return true
            }
            return false
        }
        if (label.size != brand.size || label == brand) return false
        val differ = label.indices.filter { label[it] != brand[it] }
        return differ.size == 2 && differ[1] == differ[0] + 1 &&
            label[differ[0]] == brand[differ[1]] && label[differ[1]] == brand[differ[0]]
    }

    /**
     * [word] occurs in [text] with no ASCII letter directly before or after
     * it, so "claim" is bait but "disclaimer" is not. Compared in UTF-16 units,
     * as the iOS port does.
     */
    private fun containsWord(word: String, text: String): Boolean {
        if (word.isEmpty()) return false
        fun isLetter(c: Char) = c in 'A'..'Z' || c in 'a'..'z'
        var start = text.indexOf(word)
        while (start >= 0) {
            val before = start > 0 && isLetter(text[start - 1])
            val end = start + word.length
            val after = end < text.length && isLetter(text[end])
            if (!before && !after) return true
            start = text.indexOf(word, start + 1)
        }
        return false
    }

    /**
     * Where a redirector link really sends you, when that is a different site
     * from the link's own: a redirect parameter whose decoded value is an
     * absolute web address (`url=`, `next=`, DuckDuckGo's `uddg=`, Google's
     * `q=` on /url, Bing's base64 `u=a1…`), Yahoo's `/RU=…/` path segment, or
     * Google's `/amp/s/<host>` path. Same-site hops — a sign-in page returning
     * to its own site — are what those parameters are for. Mirrors iOS
     * `redirectTarget(of:host:)`.
     */
    private fun redirectTarget(url: DecodedUrl, host: String): Pair<String, String>? {
        val site = registrableDomain(host)
        val siteLabel = site.split(".").firstOrNull() ?: ""
        val path = url.path.lowercase()
        val searchRedirect = (siteLabel == "google" && path == "/url") || (site == "youtube.com" && path == "/redirect")
        for ((name, value) in queryItems(url.query)) {
            val key = name.lowercase()
            if (key !in redirectParameterKeys && !(key == "q" && searchRedirect)) continue
            var target = absoluteWebUrl(value)
            if (target == null && key == "u" && value.startsWith("a1")) {
                target = absoluteWebUrl(base64UrlDecoded(value.substring(2)))
            }
            val other = target?.let { foreignHost(it, site) }
            if (other != null) return "its `$name` value" to other
        }
        if (siteLabel == "yahoo") {
            val marker = url.path.indexOf("/RU=", ignoreCase = true)
            if (marker >= 0) {
                val value = url.path.substring(marker + "/RU=".length).substringBefore('/')
                val other = absoluteWebUrl(value)?.let { foreignHost(it, site) }
                if (other != null) return "its `RU` path segment" to other
            }
        }
        if (siteLabel == "google" && path.startsWith("/amp/s/")) {
            val other = absoluteWebUrl("https://" + url.path.substring("/amp/s/".length))?.let { foreignHost(it, site) }
            if (other != null) return "its `/amp/s/` path" to other
        }
        return null
    }

    /** The host of [url] when it is on another site than [site]. */
    private fun foreignHost(url: DecodedUrl, site: String): String? {
        val host = HostName(url.host).normalized
        if (host.isEmpty() || registrableDomain(host) == site) return null
        return host
    }

    /**
     * A redirect value read as a web address: absolute http(s), or `//host`,
     * after up to two rounds of percent-decoding.
     */
    private fun absoluteWebUrl(value: String?): DecodedUrl? {
        var text = value ?: return null
        for (round in 0 until 3) {
            val url = webUrl(if (text.startsWith("//")) "https:$text" else text)
            if (url != null && url.host.isNotEmpty()) return url
            if (round >= 2) return null
            val decoded = HostName.percentDecoded(text)
            if (decoded == null || decoded == text) return null
            text = decoded
        }
        return null
    }

    /**
     * A query string's name/value pairs, split on `&` and `;`, each side
     * percent-decoded (kept as written when the escape is malformed). `+`
     * stays a plus.
     */
    private fun queryItems(query: String?): List<Pair<String, String>> {
        if (query == null) return emptyList()
        return query.split('&', ';').filter { it.isNotEmpty() }.map { pair ->
            val equals = pair.indexOf('=')
            val name = if (equals >= 0) pair.substring(0, equals) else pair
            val value = if (equals >= 0) pair.substring(equals + 1) else ""
            (HostName.percentDecoded(name) ?: name) to (HostName.percentDecoded(value) ?: value)
        }
    }

    /** Base64url (or plain base64) text decoded as UTF-8, padding optional. */
    private fun base64UrlDecoded(text: String): String? {
        var standard = text.replace('-', '+').replace('_', '/')
        if (standard.length % 4 == 1) return null
        while (standard.length % 4 != 0) standard += "="
        val bytes = try {
            Base64.getDecoder().decode(standard)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return utf8OrNull(bytes)
    }

    private fun exposedSecretKind(raw: String): String? {
        val lower = raw.lowercase()
        // PEM ("…PRIVATE KEY-----") and PGP ("…PRIVATE KEY BLOCK-----") alike.
        if (lower.contains("-----begin") && lower.contains("private key")) return "private key"
        if (containsWifKey(raw)) return "private key"
        if (lower.startsWith("nsec1") || lower.startsWith("nostr:nsec1")) return "Nostr secret key"
        if (lower.startsWith("xprv") || lower.startsWith("zprv") || lower.startsWith("tprv")) {
            return "extended wallet key"
        }
        if (lower.contains("m/44'") || lower.contains("m/84'")) return "wallet derivation path"
        if (SeedPhrase.isMnemonic(raw)) return "seed phrase"
        return null
    }

    /**
     * A Bitcoin private key in wallet-import format — what a paper wallet
     * prints: 51 or 52 Base58 characters starting 5, K or L (the shape the
     * redaction blanks) whose Base58Check checksum holds, so a random token of
     * that length is not taken for one. A bare 64-digit hex string is
     * deliberately not a key here: transaction hashes and file digests look
     * exactly the same. Mirrors iOS `containsWIFKey(_:)`.
     */
    fun containsWifKey(text: String): Boolean {
        val run = StringBuilder()
        fun runIsKey(): Boolean {
            val candidate = run.toString()
            run.setLength(0)
            if (candidate.length != 51 && candidate.length != 52) return false
            if (candidate[0] != '5' && candidate[0] != 'K' && candidate[0] != 'L') return false
            val bytes = base58Decoded(candidate) ?: return false
            val compressed = bytes.size == 38 && bytes[33] == 0x01.toByte()
            if ((bytes.size != 37 && !compressed) || bytes[0] != 0x80.toByte()) return false
            val sha256 = MessageDigest.getInstance("SHA-256")
            val digest = sha256.digest(sha256.digest(bytes.copyOfRange(0, bytes.size - 4)))
            return digest.copyOfRange(0, 4).contentEquals(bytes.copyOfRange(bytes.size - 4, bytes.size))
        }
        for (c in text) {
            if (c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z') {
                run.append(c)
            } else if (runIsKey()) {
                return true
            }
        }
        return runIsKey()
    }

    /** Base58 (the Bitcoin alphabet) to bytes, null on a character outside it. */
    private fun base58Decoded(text: String): ByteArray? {
        val bytes = ArrayList<Int>()
        for (c in text) {
            var carry = BASE58_ALPHABET.indexOf(c)
            if (carry < 0) return null
            for (index in bytes.indices.reversed()) {
                carry += bytes[index] * 58
                bytes[index] = carry and 0xFF
                carry = carry shr 8
            }
            while (carry > 0) {
                bytes.add(0, carry and 0xFF)
                carry = carry shr 8
            }
        }
        val zeros = text.takeWhile { it == '1' }.length
        return ByteArray(zeros + bytes.size) { if (it < zeros) 0 else bytes[it - zeros].toByte() }
    }

    private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /**
     * Free text that reads like a payment lure rather than something a venue
     * prints — the "scan to pay £1 / contact us on Telegram" table tent.
     * Whole words only, so "blocked" is not "locked".
     */
    private fun lureInText(text: String): Boolean {
        val lower = text.lowercase()
        val hasInstruction = lurePhrases.any { containsWord(it, lower) } ||
            phishingKeywords.any { containsWord(it, lower) }
        val hasContactHandle = lower.contains("t.me/") || lower.contains("telegram") ||
            lower.contains("whatsapp") || lower.contains("wa.me/") ||
            lower.contains("@gmail.com") || lower.contains("@mail.ru")
        return hasInstruction && hasContactHandle
    }

    /** At least 24 base64 / base64url characters straight after `data=`. */
    private fun looksLikeBase64(query: String): Boolean {
        val marker = query.indexOf("data=")
        if (marker < 0) return false
        val tail = codePoints(query.substring(marker + 5)).take(64)
        return tail.size >= 24 && tail.all { cp ->
            cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code || cp in '0'.code..'9'.code ||
                cp in base64Punctuation
        }
    }

    private val base64Punctuation = "+/=_-".map { it.code }.toSet()

    // MARK: Tables

    private val brands = listOf(
        "paypal", "netflix", "amazon", "google", "apple", "microsoft", "facebook",
        "instagram", "whatsapp", "telegram", "revolut", "wise", "barclays",
        "hsbc", "n26", "coinbase", "binance", "trustwallet", "metamask",
        "tiktok", "twitter", "spotify", "airbnb", "uber", "deliveroo",
        "ebay", "aliexpress", "dhl", "fedex", "ups", "westunion", "westernunion",
        "youtube", "linkedin", "adobe", "usps",
    )

    /**
     * Brands that are also everyday words. As one word of a hyphenated name
     * they count only next to a [phishingContextWords] word.
     */
    private val commonWordBrands = setOf(
        "apple", "amazon", "wise", "uber", "ups", "telegram", "twitter",
    )

    /** What a fake brand domain is about: signing in, paying, a parcel. */
    private val phishingContextWords = setOf(
        "login", "signin", "logon", "secure", "security", "verify", "verification",
        "account", "accounts", "id", "auth", "password", "support", "help", "service",
        "care", "customer", "billing", "pay", "payment", "refund", "invoice", "update",
        "unlock", "recovery", "wallet", "transfer", "delivery", "redelivery", "track",
        "tracking", "parcel", "package", "shipment",
    )

    /** Hyphenated domains the brand itself runs. */
    private val brandOwnedDomains = setOf("youtube-nocookie.com")

    /**
     * Labels that read as a domain ending when they follow a brand
     * (`paypal.com.…`); any two-letter label (`.de`, `.uk`) does too.
     */
    private val topLevelLikeLabels = setOf(
        "com", "net", "org", "co", "io", "app", "info", "biz", "gov", "edu",
    )

    /**
     * Display spellings for brands whose lowercased key is not how people
     * write them. Anything absent falls back to a capitalised first letter.
     */
    private val brandNames: Map<String, String> = mapOf(
        "paypal" to "PayPal", "youtube" to "YouTube", "instagram" to "Instagram",
        "whatsapp" to "WhatsApp", "revolut" to "Revolut", "hsbc" to "HSBC",
        "n26" to "N26", "coinbase" to "Coinbase", "binance" to "Binance",
        "trustwallet" to "Trust Wallet", "metamask" to "MetaMask",
        "tiktok" to "TikTok", "twitter" to "X (Twitter)", "airbnb" to "Airbnb",
        "deliveroo" to "Deliveroo", "ebay" to "eBay", "aliexpress" to "AliExpress",
        "dhl" to "DHL", "fedex" to "FedEx", "ups" to "UPS",
        "westunion" to "Western Union", "westernunion" to "Western Union",
        "microsoft" to "Microsoft", "facebook" to "Facebook", "netflix" to "Netflix",
        "amazon" to "Amazon", "google" to "Google", "apple" to "Apple",
        "telegram" to "Telegram", "spotify" to "Spotify", "uber" to "Uber",
        "wise" to "Wise", "barclays" to "Barclays", "linkedin" to "LinkedIn",
        "adobe" to "Adobe", "usps" to "USPS",
    )

    /**
     * What [confusableSkeleton] folds each look-alike onto: digits and the
     * dotted i that pass for Latin letters, the Latin letters other alphabets
     * imitate (Cyrillic, Greek, Armenian), and the IPA forms of a, g and i.
     * Lower-case only — the skeleton is built after case-folding.
     */
    private val latinLookalikes: Map<Int, Char> = mapOf(
        // Digits and Latin
        0x30 to 'o', 0x31 to 'l', 0x33 to 'e', 0x35 to 's', 0x69 to 'l',
        0x131 to 'l', 0x251 to 'a', 0x261 to 'g', 0x269 to 'l',
        // Cyrillic
        0x430 to 'a', 0x441 to 'c', 0x501 to 'd', 0x435 to 'e', 0x4BB to 'h', 0x456 to 'l',
        0x458 to 'j', 0x4CF to 'l', 0x43E to 'o', 0x440 to 'p', 0x51B to 'q', 0x455 to 's',
        0x51D to 'w', 0x445 to 'x', 0x443 to 'y', 0x4AF to 'y',
        // Greek
        0x3B1 to 'a', 0x3B3 to 'y', 0x3B9 to 'l', 0x3BA to 'k', 0x3BD to 'v', 0x3BF to 'o',
        0x3C1 to 'p', 0x3C5 to 'u', 0x3C7 to 'x', 0x3F2 to 'c', 0x3F3 to 'j',
        // Armenian
        0x570 to 'h', 0x578 to 'n', 0x57D to 'u', 0x581 to 'g', 0x585 to 'o', 0x566 to 'q',
    )

    private val brandSkeletons: Map<String, String> = brands.associateWith { confusableSkeleton(it) }

    /**
     * Phrases that turn a free-text QR into a payment instruction. A venue
     * that really wants money prints the account on the receipt; a code that
     * tells you to scan and hurry, then asks you to message someone, is the
     * shape of the table-sticker scam.
     */
    private val lurePhrases = listOf(
        "scan this code", "scan the code", "scan to pay", "scan and pay",
        "scan here", "scan now", "to pay", "send to", "transfer to",
        "wire to", "send money", "pay us", "pay now", "urgent",
        "do not share", "don't share", "cash app", "cashapp", "venmo",
    )

    /**
     * Redirect services that hide the destination. Chat deep links
     * (`wa.me/<number>`, `t.me/<name>`) are not here: their destination is
     * right there in the path.
     */
    private val shorteners = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "buff.ly", "ow.ly",
        "bit.do", "shorturl.at", "cutt.ly", "rb.gy", "rebrand.ly", "tiny.cc",
        "shorte.st", "lnkd.in", "lnk.to", "dlvr.it", "ift.tt",
        "tiny.one", "s.id", "linktr.ee",
    )

    /**
     * Short links that can only lead to one place: Google Maps' share links
     * open a Maps place, and the parser already reads them as Google Maps.
     */
    private val shortenerExceptions = setOf("maps.app.goo.gl")

    private val abuseTLDs = setOf(
        // Only endings that are cheap, bulk-registered and rarely used by
        // real businesses — `.shop` / `.live` are popular with legitimate
        // traders, so flagging them would train users to ignore the badge.
        "zip", "mov", "top", "xyz", "cyou", "sbs", "lol", "mom", "click", "icu",
        "quest", "monster", "autos", "beauty", "bond", "rest", "skin", "hair",
    )

    /**
     * Parameter names, compared whole and case-insensitively. Google's and
     * YouTube's `q` counts only on their redirect paths ([redirectTarget]).
     */
    private val redirectParameterKeys = setOf(
        "url", "u", "redirect", "redirect_uri", "redirecturl", "next", "target",
        "dest", "destination", "to", "link", "goto", "continue", "returnurl",
        "uddg", "adurl",
    )

    private val phishingKeywords = listOf(
        "verify", "verification", "confirm-your", "unusual-activity", "refund",
        "invoice", "payment-required", "unlock", "suspend", "locked", "kyc",
        "claim", "winner", "prize", "package", "delivery-failed", "overdue",
    )

    /**
     * The public suffixes of more than one label a scanner realistically
     * meets — commercial, government and academic second levels, Japan's
     * prefectures, and Amazon S3's bucket hosts (`*` is any one label) — a
     * curated slice of the Public Suffix List, the same as on iOS.
     */
    private val multiPartSuffixes: List<List<String>> = listOf(
        // The United Kingdom
        "co.uk", "org.uk", "me.uk", "ltd.uk", "plc.uk", "net.uk", "ac.uk", "gov.uk",
        "sch.uk", "nhs.uk", "police.uk", "mod.uk",
        // Australia, New Zealand
        "com.au", "net.au", "org.au", "edu.au", "gov.au", "asn.au", "id.au",
        "qld.gov.au", "sa.gov.au", "tas.gov.au", "vic.gov.au", "wa.gov.au",
        "co.nz", "net.nz", "org.nz", "ac.nz", "govt.nz", "school.nz",
        // The Americas
        "com.br", "net.br", "org.br", "gov.br", "edu.br", "com.mx", "org.mx", "gob.mx",
        "edu.mx", "com.ar", "gob.ar", "com.co", "gov.co", "com.pe", "gob.pe", "gc.ca",
        // Asia
        "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp", "lg.jp", "ed.jp", "gr.jp",
        "co.kr", "go.kr", "or.kr", "ac.kr", "ne.kr",
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
        "com.tw", "org.tw", "gov.tw", "edu.tw", "com.hk", "org.hk", "gov.hk", "edu.hk",
        "com.sg", "org.sg", "gov.sg", "edu.sg",
        "co.in", "net.in", "org.in", "gov.in", "nic.in", "ac.in", "edu.in", "res.in",
        "co.id", "or.id", "go.id", "ac.id", "co.th", "or.th", "go.th", "ac.th", "in.th",
        "com.my", "gov.my", "edu.my", "com.ph", "gov.ph", "com.vn", "gov.vn", "com.pk", "gov.pk",
        // The Middle East, Africa
        "co.il", "org.il", "ac.il", "gov.il", "com.tr", "gov.tr", "edu.tr", "org.tr",
        "com.sa", "gov.sa", "co.ae", "gov.ae", "ac.ae", "com.eg", "gov.eg",
        "co.za", "org.za", "gov.za", "ac.za", "co.ke", "go.ke", "com.ng", "gov.ng",
        // Europe
        "com.pl", "net.pl", "org.pl", "gov.pl", "edu.pl", "com.ru", "net.ru", "org.ru",
        "com.ua", "gov.ua", "org.ua", "co.rs", "org.rs", "edu.rs", "in.rs", "gov.rs", "ac.rs",
        "gouv.fr", "com.es", "org.es", "gob.es", "co.at", "or.at", "gv.at", "ac.at",
        "com.gr", "gov.gr", "com.pt", "gov.pt", "com.cy", "com.mt",
        // Japan's prefectures
        "aichi.jp", "akita.jp", "aomori.jp", "chiba.jp", "ehime.jp", "fukui.jp", "fukuoka.jp",
        "fukushima.jp", "gifu.jp", "gunma.jp", "hiroshima.jp", "hokkaido.jp", "hyogo.jp",
        "ibaraki.jp", "ishikawa.jp", "iwate.jp", "kagawa.jp", "kagoshima.jp", "kanagawa.jp",
        "kochi.jp", "kumamoto.jp", "kyoto.jp", "mie.jp", "miyagi.jp", "miyazaki.jp",
        "nagano.jp", "nagasaki.jp", "nara.jp", "niigata.jp", "oita.jp", "okayama.jp",
        "okinawa.jp", "osaka.jp", "saga.jp", "saitama.jp", "shiga.jp", "shimane.jp",
        "shizuoka.jp", "tochigi.jp", "tokushima.jp", "tokyo.jp", "tottori.jp", "toyama.jp",
        "wakayama.jp", "yamagata.jp", "yamaguchi.jp", "yamanashi.jp",
        // Amazon S3 bucket hosts
        "s3.amazonaws.com", "s3.*.amazonaws.com", "s3-website.*.amazonaws.com",
        "s3.dualstack.*.amazonaws.com",
    ).map { it.split(".") }
}

// MARK: - URL and host parsing

/**
 * The slice of a URL the risk checks need, split by hand the way a browser
 * splits it (the WHATWG URL Standard). [userInfo] is non-null the moment an
 * `@` appears in the authority, which is the whole point of the
 * `credentials-in-url` check. iOS `DecodedURL` splits the same way, so both
 * ports grade the same host.
 */
internal data class DecodedUrl(
    /** Lower-cased. */
    val scheme: String,
    val userInfo: String?,
    /** As written; IPv6 without its brackets. */
    val host: String,
    /** ASCII digits only. */
    val port: String?,
    val path: String,
    val query: String?,
    /** Trimmed, with tabs and line breaks taken out. */
    val absoluteString: String,
)

/**
 * Null unless the text is `scheme:` and an authority whose host has no space
 * or control character in it. Read the way a browser reads it: the ends
 * trimmed, every tab and line break dropped, and for http and https any run of
 * `/` and `\` — none at all included — taken as the `//`, so
 * `https:paypa1.com` is paypa1.com. Other schemes need `//`. The authority
 * ends at `/`, `?`, `#` or `\` — except that a `\` with an `@` after it
 * (before the next `/`, `?` or `#`) is read as part of the login, the way
 * Foundation reads it, because that is what iOS opens: both ports then call
 * `https://paypal.com\@evil.example` a login trick. See
 * [QrRiskAssessor.webUrl] for why this isn't [java.net.URI].
 */
internal fun parseUrl(raw: String): DecodedUrl? {
    val s = normalisedUrlText(raw)
    val colon = s.indexOf(':')
    if (colon <= 0 || !s[0].isAsciiLetter() ||
        !s.substring(1, colon).all { it.isAsciiLetter() || it in '0'..'9' || it in "+-." }
    ) {
        return null
    }
    val scheme = s.substring(0, colon).lowercase()

    var start = colon + 1
    if (scheme == "http" || scheme == "https") {
        while (start < s.length && (s[start] == '/' || s[start] == '\\')) start += 1
    } else {
        if (start + 1 >= s.length || s[start] != '/' || s[start + 1] != '/') return null
        start += 2
    }
    val terminator = (start until s.length).firstOrNull { s[it] == '/' || s[it] == '?' || s[it] == '#' } ?: s.length
    var authorityEnd = terminator
    val backslash = s.indexOf('\\', start)
    if (backslash in start until terminator && s.indexOf('@', backslash + 1).let { it < 0 || it >= terminator }) {
        authorityEnd = backslash
    }
    val at = s.lastIndexOf('@', authorityEnd - 1).takeIf { it >= start }
    val userInfo = at?.let { s.substring(start, it) }
    val hostPort = s.substring(at?.plus(1) ?: start, authorityEnd)
    if (hostPort.any { it.code <= 0x20 || it.code == 0x7F || it in "<>^|" }) return null
    val tail = s.substring(authorityEnd)

    fun digits(part: String) = part.all { it in '0'..'9' }
    var host = hostPort
    var port: String? = null
    if (hostPort.startsWith("[")) {
        val close = hostPort.indexOf(']')
        if (close > 0) {
            host = hostPort.substring(1, close)
            val after = hostPort.substring(close + 1)
            if (after.startsWith(":") && after.length > 1 && digits(after.substring(1))) port = after.substring(1)
        }
    } else {
        val portColon = hostPort.lastIndexOf(':')
        if (portColon >= 0) {
            val maybePort = hostPort.substring(portColon + 1)
            if (digits(maybePort)) {
                host = hostPort.substring(0, portColon)
                port = maybePort.ifEmpty { null }
            }
        }
    }

    val beforeFragment = tail.substring(0, tail.indexOf('#').let { if (it < 0) tail.length else it })
    val question = beforeFragment.indexOf('?')
    val path = if (question >= 0) beforeFragment.substring(0, question) else beforeFragment
    val query = if (question >= 0) beforeFragment.substring(question + 1) else null
    return DecodedUrl(scheme, userInfo, host, port, path, query, s)
}

/**
 * [raw] with the white space and control characters at either end trimmed —
 * the C0 controls and space a browser trims, plus what Swift's
 * `whitespacesAndNewlines` holds — and every tab, line feed and carriage
 * return inside dropped, as a browser drops them. Mirrors iOS
 * `DecodedURL.normalised`.
 */
private fun normalisedUrlText(raw: String): String {
    fun trimmed(c: Char) = c.code <= 0x20 || c == '\u0085' || c == ' ' || c == ' ' ||
        c in ' '..'​' || c == ' ' || c == ' ' || c == ' ' ||
        c == ' ' || c == '　'
    var end = raw.length
    while (end > 0 && trimmed(raw[end - 1])) end -= 1
    var begin = 0
    while (begin < end && trimmed(raw[begin])) begin += 1
    return raw.substring(begin, end).filterNot { it == '\t' || it == '\n' || it == '\r' }
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

/**
 * A host the way a browser reads it, for the checks: percent-escapes decoded,
 * compatibility forms folded (fullwidth letters are ordinary letters),
 * invisible characters dropped, `。` read as a dot, and each `xn--` label
 * decoded back to the letters it stands for. Mirrors iOS `HostName`.
 */
internal class HostName(raw: String) {
    /** Lower-cased — what the findings name and the ASCII tables compare. */
    val normalized: String

    /** Every label in Unicode with its case kept, for the alphabet and look-alike checks. */
    val unicodeLabels: List<String>

    /** An `xn--` label that does not decode. */
    val hasMalformedPunycode: Boolean

    init {
        val decoded = Normalizer.normalize(percentDecoded(raw) ?: raw, Normalizer.Form.NFKC)
        val folded = StringBuilder()
        for (cp in codePoints(decoded)) {
            if (cp in INVISIBLE) continue
            if (cp == 0x3002) folded.append('.') else folded.appendCodePoint(cp)
        }
        val host = folded.toString().removeSuffix(".")
        normalized = host.lowercase()

        var malformed = false
        unicodeLabels = host.split(".").filter { it.isNotEmpty() }.map { label ->
            if (!label.lowercase().startsWith("xn--")) return@map label
            // A valid A-label always stands for something outside ASCII.
            val unicode = Punycode.decode(label.substring(4))
            if (unicode != null && unicode.any { it.code > 0x7F }) return@map unicode
            malformed = true
            label
        }
        hasMalformedPunycode = malformed
    }

    /**
     * A label mixing letters of two alphabets that imitate each other — Latin
     * with Cyrillic, Greek, Armenian or Cherokee. Accents (münchen) and Latin
     * next to CJK are not a mix.
     */
    val mixesAlphabets: Boolean
        get() = unicodeLabels.any { label -> codePoints(label).mapNotNull(HostAlphabet::of).toSet().size > 1 }

    /**
     * A label spelled entirely in one non-Latin alphabet with letters that all
     * pass for Latin ones ("аррӏе" in Cyrillic reads as "apple"), under a
     * top-level domain that is not in that alphabet. президент.рф is not one:
     * its letters do not look Latin, and .рф is Cyrillic too.
     */
    val isWholeScriptLookalike: Boolean
        get() {
            if (unicodeLabels.size < 2) return false
            val tldAlphabets = codePoints(unicodeLabels.last()).mapNotNull(HostAlphabet::of).toSet()
            for (label in unicodeLabels.dropLast(1)) {
                val letters = codePoints(label.lowercase()).filter { Character.isLetter(it) }
                val first = letters.firstOrNull()?.let(HostAlphabet::of) ?: continue
                if (first == HostAlphabet.LATIN || first in tldAlphabets) continue
                if (letters.all { HostAlphabet.of(it) == first && QrRiskAssessor.passesForLatin(it) }) return true
            }
            return false
        }

    companion object {
        /** Soft hyphen, zero-width space / joiners, word joiner, BOM. */
        private val INVISIBLE = setOf(0xAD, 0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF)

        /**
         * `%XX` escapes decoded as UTF-8, or null when an escape is malformed
         * or the bytes are not UTF-8 — the caller then keeps the text as
         * written. Mirrors iOS `HostName.percentDecoded`.
         */
        fun percentDecoded(s: String): String? {
            if ('%' !in s) return s
            val source = s.toByteArray(Charsets.UTF_8)
            val bytes = ByteArrayOutputStream(source.size)
            var i = 0
            while (i < source.size) {
                if (source[i] == '%'.code.toByte()) {
                    if (i + 2 >= source.size) return null
                    val hi = Character.digit(source[i + 1].toInt().toChar(), 16)
                    val lo = Character.digit(source[i + 2].toInt().toChar(), 16)
                    if (hi < 0 || lo < 0 || source[i + 1] < 0 || source[i + 2] < 0) return null
                    bytes.write(hi * 16 + lo)
                    i += 3
                } else {
                    bytes.write(source[i].toInt())
                    i += 1
                }
            }
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString()
            } catch (_: CharacterCodingException) {
                null
            }
        }
    }
}

/**
 * The alphabets whose letters stand in for one another in homoglyph attacks.
 * Code-point ranges rather than the Unicode Script property, so both ports
 * classify identically whatever Unicode version their OS ships.
 */
internal enum class HostAlphabet {
    LATIN, GREEK, CYRILLIC, ARMENIAN, CHEROKEE;

    companion object {
        fun of(cp: Int): HostAlphabet? {
            if (!Character.isLetter(cp)) return null
            return when (cp) {
                in 0x41..0x5A, in 0x61..0x7A, 0xAA, 0xBA, in 0xC0..0x2AF, in 0x1E00..0x1EFF,
                in 0x2C60..0x2C7F, in 0xA720..0xA7FF, in 0xAB30..0xAB6F -> LATIN
                in 0x370..0x3FF, in 0x1F00..0x1FFF -> GREEK
                in 0x400..0x52F, in 0x1C80..0x1C8F, in 0x2DE0..0x2DFF, in 0xA640..0xA69F -> CYRILLIC
                in 0x531..0x58F, in 0xFB13..0xFB17 -> ARMENIAN
                in 0x13A0..0x13FF, in 0xAB70..0xABBF -> CHEROKEE
                else -> null
            }
        }
    }
}

/**
 * RFC 3492 decoding of an IDNA label's `xn--` tail, so a punycoded host is
 * checked as the letters it stands for. Null when the text is not valid
 * punycode. The iOS port is the same algorithm line for line.
 */
internal object Punycode {
    private const val BASE = 36
    private const val T_MIN = 1
    private const val T_MAX = 26
    private const val SKEW = 38
    private const val DAMP = 700
    private const val MAX_VALUE = 0x7FFF_FFFF

    fun decode(input: String): String? {
        if (input.any { it.code > 0x7F }) return null
        val delimiter = input.lastIndexOf('-')
        val output = mutableListOf<Int>()
        if (delimiter >= 0) input.substring(0, delimiter).forEach { output += it.code }
        var index = if (delimiter >= 0) delimiter + 1 else 0
        var n = 128
        var i = 0
        var bias = 72
        while (index < input.length) {
            val oldI = i
            var w = 1
            var k = BASE
            while (true) {
                if (index >= input.length) return null
                val digit = digitValue(input[index]) ?: return null
                index += 1
                if (digit > (MAX_VALUE - i) / w) return null
                i += digit * w
                val t = if (k <= bias) T_MIN else if (k >= bias + T_MAX) T_MAX else k - bias
                if (digit < t) break
                if (w > MAX_VALUE / (BASE - t)) return null
                w *= BASE - t
                k += BASE
            }
            val count = output.size + 1
            bias = adapt(i - oldI, count, oldI == 0)
            if (i / count > MAX_VALUE - n) return null
            n += i / count
            i %= count
            if (n > 0x10FFFF || n in 0xD800..0xDFFF) return null
            output.add(i, n)
            i += 1
        }
        val out = StringBuilder()
        output.forEach { out.appendCodePoint(it) }
        return out.toString()
    }

    private fun digitValue(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0' + 26
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a'
        else -> null
    }

    private fun adapt(delta: Int, count: Int, first: Boolean): Int {
        var d = if (first) delta / DAMP else delta / 2
        d += d / count
        var k = 0
        while (d > ((BASE - T_MIN) * T_MAX) / 2) {
            d /= BASE - T_MIN
            k += BASE
        }
        return k + (BASE - T_MIN + 1) * d / (d + SKEW)
    }
}

/**
 * BIP-39 recovery phrases — the "seed" a crypto wallet is restored from —
 * recognised the same way by the safety check and by the redaction in front of
 * an explanation engine, and the same way on both ports: 12, 15, 18, 21 or 24
 * words, every one of them in the BIP-39 English wordlist, in any case,
 * separated by white space or commas, optionally numbered ("1.", "1)", "01:")
 * and optionally after a label such as "Seed:" or "Recovery phrase:". An
 * ordinary twelve-word sentence is not one: "welcome" is a BIP-39 word,
 * "guests" is not. The iOS port (`SeedPhrase` in QrRisk.swift) is the same
 * code over the same embedded list.
 */
internal object SeedPhrase {

    /** Whether the whole of [text] is a recovery phrase. */
    fun isMnemonic(text: String): Boolean {
        var count = 0
        for (token in tokens(unlabelled(text)) { isSeparator(it) || it == ',' }) {
            val word = word(token) ?: continue
            if (word !in englishWords || count >= 24) return false
            count += 1
        }
        return count in setOf(12, 15, 18, 21, 24)
    }

    /** [text] less a leading "Seed:"-style label. */
    private fun unlabelled(text: String): String {
        val colon = text.indexOf(':')
        if (colon < 0 || colon > 40) return text
        val label = tokens(text.substring(0, colon), ::isSeparator).joinToString(" ") { ascii(it) }
        return if (label in labels) text.substring(colon + 1) else text
    }

    /** The runs of [text] between characters [isBreak] accepts. */
    private fun tokens(text: String, isBreak: (Char) -> Boolean): List<String> {
        val out = mutableListOf<String>()
        var start = -1
        for (index in text.indices) {
            if (isBreak(text[index])) {
                if (start >= 0) out += text.substring(start, index)
                start = -1
            } else if (start < 0) {
                start = index
            }
        }
        if (start >= 0) out += text.substring(start)
        return out
    }

    /**
     * A token as a candidate word, less any "12." / "3)" / "01:" numbering in
     * front, or null when it is nothing but numbering.
     */
    private fun word(token: String): String? {
        var rest = token
        val digits = rest.takeWhile { it in '0'..'9' }.length
        if (digits in 1..2) {
            val after = rest.substring(digits)
            val mark = after.firstOrNull() ?: return null
            if (mark in ".):") {
                rest = after.substring(1)
                if (rest.isEmpty()) return null
            }
        }
        return ascii(rest)
    }

    /**
     * [text] with A–Z lower-cased and nothing else touched — the wordlist is
     * ASCII, and both ports fold the same way.
     */
    private fun ascii(text: String): String = String(CharArray(text.length) { text[it].let { c -> if (c in 'A'..'Z') c + 0x20 else c } })

    /**
     * White space, spelled out so both ports agree whatever Unicode version
     * their platform ships: tab to carriage return, next line, and the space,
     * line and paragraph separators.
     */
    private fun isSeparator(c: Char): Boolean = c in '\u0009'..'\u000D' || c == ' ' || c == '\u0085' ||
        c == '\u00A0' || c == '\u1680' || c in '\u2000'..'\u200A' || c == '\u2028' || c == '\u2029' ||
        c == '\u202F' || c == '\u205F' || c == '\u3000'

    private val labels = setOf(
        "seed", "seed phrase", "seed words", "mnemonic", "mnemonic phrase", "recovery phrase",
        "recovery words", "recovery seed", "backup phrase", "backup words", "backup seed",
        "secret phrase", "secret recovery phrase", "wallet seed", "wallet seed phrase",
    )

    /**
     * The BIP-39 English wordlist in order — github.com/bitcoin/bips,
     * bip-0039/english.txt, whose SHA-256 is
     * 2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda (the
     * tests check it). The iOS port embeds the same lines.
     */
    val englishWordlist: List<String> = """
        abandon ability able about above absent absorb abstract absurd abuse access accident
        account accuse achieve acid acoustic acquire across act action actor actress actual
        adapt add addict address adjust admit adult advance advice aerobic affair afford
        afraid again age agent agree ahead aim air airport aisle alarm album
        alcohol alert alien all alley allow almost alone alpha already also alter
        always amateur amazing among amount amused analyst anchor ancient anger angle angry
        animal ankle announce annual another answer antenna antique anxiety any apart apology
        appear apple approve april arch arctic area arena argue arm armed armor
        army around arrange arrest arrive arrow art artefact artist artwork ask aspect
        assault asset assist assume asthma athlete atom attack attend attitude attract auction
        audit august aunt author auto autumn average avocado avoid awake aware away
        awesome awful awkward axis baby bachelor bacon badge bag balance balcony ball
        bamboo banana banner bar barely bargain barrel base basic basket battle beach
        bean beauty because become beef before begin behave behind believe below belt
        bench benefit best betray better between beyond bicycle bid bike bind biology
        bird birth bitter black blade blame blanket blast bleak bless blind blood
        blossom blouse blue blur blush board boat body boil bomb bone bonus
        book boost border boring borrow boss bottom bounce box boy bracket brain
        brand brass brave bread breeze brick bridge brief bright bring brisk broccoli
        broken bronze broom brother brown brush bubble buddy budget buffalo build bulb
        bulk bullet bundle bunker burden burger burst bus business busy butter buyer
        buzz cabbage cabin cable cactus cage cake call calm camera camp can
        canal cancel candy cannon canoe canvas canyon capable capital captain car carbon
        card cargo carpet carry cart case cash casino castle casual cat catalog
        catch category cattle caught cause caution cave ceiling celery cement census century
        cereal certain chair chalk champion change chaos chapter charge chase chat cheap
        check cheese chef cherry chest chicken chief child chimney choice choose chronic
        chuckle chunk churn cigar cinnamon circle citizen city civil claim clap clarify
        claw clay clean clerk clever click client cliff climb clinic clip clock
        clog close cloth cloud clown club clump cluster clutch coach coast coconut
        code coffee coil coin collect color column combine come comfort comic common
        company concert conduct confirm congress connect consider control convince cook cool copper
        copy coral core corn correct cost cotton couch country couple course cousin
        cover coyote crack cradle craft cram crane crash crater crawl crazy cream
        credit creek crew cricket crime crisp critic crop cross crouch crowd crucial
        cruel cruise crumble crunch crush cry crystal cube culture cup cupboard curious
        current curtain curve cushion custom cute cycle dad damage damp dance danger
        daring dash daughter dawn day deal debate debris decade december decide decline
        decorate decrease deer defense define defy degree delay deliver demand demise denial
        dentist deny depart depend deposit depth deputy derive describe desert design desk
        despair destroy detail detect develop device devote diagram dial diamond diary dice
        diesel diet differ digital dignity dilemma dinner dinosaur direct dirt disagree discover
        disease dish dismiss disorder display distance divert divide divorce dizzy doctor document
        dog doll dolphin domain donate donkey donor door dose double dove draft
        dragon drama drastic draw dream dress drift drill drink drip drive drop
        drum dry duck dumb dune during dust dutch duty dwarf dynamic eager
        eagle early earn earth easily east easy echo ecology economy edge edit
        educate effort egg eight either elbow elder electric elegant element elephant elevator
        elite else embark embody embrace emerge emotion employ empower empty enable enact
        end endless endorse enemy energy enforce engage engine enhance enjoy enlist enough
        enrich enroll ensure enter entire entry envelope episode equal equip era erase
        erode erosion error erupt escape essay essence estate eternal ethics evidence evil
        evoke evolve exact example excess exchange excite exclude excuse execute exercise exhaust
        exhibit exile exist exit exotic expand expect expire explain expose express extend
        extra eye eyebrow fabric face faculty fade faint faith fall false fame
        family famous fan fancy fantasy farm fashion fat fatal father fatigue fault
        favorite feature february federal fee feed feel female fence festival fetch fever
        few fiber fiction field figure file film filter final find fine finger
        finish fire firm first fiscal fish fit fitness fix flag flame flash
        flat flavor flee flight flip float flock floor flower fluid flush fly
        foam focus fog foil fold follow food foot force forest forget fork
        fortune forum forward fossil foster found fox fragile frame frequent fresh friend
        fringe frog front frost frown frozen fruit fuel fun funny furnace fury
        future gadget gain galaxy gallery game gap garage garbage garden garlic garment
        gas gasp gate gather gauge gaze general genius genre gentle genuine gesture
        ghost giant gift giggle ginger giraffe girl give glad glance glare glass
        glide glimpse globe gloom glory glove glow glue goat goddess gold good
        goose gorilla gospel gossip govern gown grab grace grain grant grape grass
        gravity great green grid grief grit grocery group grow grunt guard guess
        guide guilt guitar gun gym habit hair half hammer hamster hand happy
        harbor hard harsh harvest hat have hawk hazard head health heart heavy
        hedgehog height hello helmet help hen hero hidden high hill hint hip
        hire history hobby hockey hold hole holiday hollow home honey hood hope
        horn horror horse hospital host hotel hour hover hub huge human humble
        humor hundred hungry hunt hurdle hurry hurt husband hybrid ice icon idea
        identify idle ignore ill illegal illness image imitate immense immune impact impose
        improve impulse inch include income increase index indicate indoor industry infant inflict
        inform inhale inherit initial inject injury inmate inner innocent input inquiry insane
        insect inside inspire install intact interest into invest invite involve iron island
        isolate issue item ivory jacket jaguar jar jazz jealous jeans jelly jewel
        job join joke journey joy judge juice jump jungle junior junk just
        kangaroo keen keep ketchup key kick kid kidney kind kingdom kiss kit
        kitchen kite kitten kiwi knee knife knock know lab label labor ladder
        lady lake lamp language laptop large later latin laugh laundry lava law
        lawn lawsuit layer lazy leader leaf learn leave lecture left leg legal
        legend leisure lemon lend length lens leopard lesson letter level liar liberty
        library license life lift light like limb limit link lion liquid list
        little live lizard load loan lobster local lock logic lonely long loop
        lottery loud lounge love loyal lucky luggage lumber lunar lunch luxury lyrics
        machine mad magic magnet maid mail main major make mammal man manage
        mandate mango mansion manual maple marble march margin marine market marriage mask
        mass master match material math matrix matter maximum maze meadow mean measure
        meat mechanic medal media melody melt member memory mention menu mercy merge
        merit merry mesh message metal method middle midnight milk million mimic mind
        minimum minor minute miracle mirror misery miss mistake mix mixed mixture mobile
        model modify mom moment monitor monkey monster month moon moral more morning
        mosquito mother motion motor mountain mouse move movie much muffin mule multiply
        muscle museum mushroom music must mutual myself mystery myth naive name napkin
        narrow nasty nation nature near neck need negative neglect neither nephew nerve
        nest net network neutral never news next nice night noble noise nominee
        noodle normal north nose notable note nothing notice novel now nuclear number
        nurse nut oak obey object oblige obscure observe obtain obvious occur ocean
        october odor off offer office often oil okay old olive olympic omit
        once one onion online only open opera opinion oppose option orange orbit
        orchard order ordinary organ orient original orphan ostrich other outdoor outer output
        outside oval oven over own owner oxygen oyster ozone pact paddle page
        pair palace palm panda panel panic panther paper parade parent park parrot
        party pass patch path patient patrol pattern pause pave payment peace peanut
        pear peasant pelican pen penalty pencil people pepper perfect permit person pet
        phone photo phrase physical piano picnic picture piece pig pigeon pill pilot
        pink pioneer pipe pistol pitch pizza place planet plastic plate play please
        pledge pluck plug plunge poem poet point polar pole police pond pony
        pool popular portion position possible post potato pottery poverty powder power practice
        praise predict prefer prepare present pretty prevent price pride primary print priority
        prison private prize problem process produce profit program project promote proof property
        prosper protect proud provide public pudding pull pulp pulse pumpkin punch pupil
        puppy purchase purity purpose purse push put puzzle pyramid quality quantum quarter
        question quick quit quiz quote rabbit raccoon race rack radar radio rail
        rain raise rally ramp ranch random range rapid rare rate rather raven
        raw razor ready real reason rebel rebuild recall receive recipe record recycle
        reduce reflect reform refuse region regret regular reject relax release relief rely
        remain remember remind remove render renew rent reopen repair repeat replace report
        require rescue resemble resist resource response result retire retreat return reunion reveal
        review reward rhythm rib ribbon rice rich ride ridge rifle right rigid
        ring riot ripple risk ritual rival river road roast robot robust rocket
        romance roof rookie room rose rotate rough round route royal rubber rude
        rug rule run runway rural sad saddle sadness safe sail salad salmon
        salon salt salute same sample sand satisfy satoshi sauce sausage save say
        scale scan scare scatter scene scheme school science scissors scorpion scout scrap
        screen script scrub sea search season seat second secret section security seed
        seek segment select sell seminar senior sense sentence series service session settle
        setup seven shadow shaft shallow share shed shell sheriff shield shift shine
        ship shiver shock shoe shoot shop short shoulder shove shrimp shrug shuffle
        shy sibling sick side siege sight sign silent silk silly silver similar
        simple since sing siren sister situate six size skate sketch ski skill
        skin skirt skull slab slam sleep slender slice slide slight slim slogan
        slot slow slush small smart smile smoke smooth snack snake snap sniff
        snow soap soccer social sock soda soft solar soldier solid solution solve
        someone song soon sorry sort soul sound soup source south space spare
        spatial spawn speak special speed spell spend sphere spice spider spike spin
        spirit split spoil sponsor spoon sport spot spray spread spring spy square
        squeeze squirrel stable stadium staff stage stairs stamp stand start state stay
        steak steel stem step stereo stick still sting stock stomach stone stool
        story stove strategy street strike strong struggle student stuff stumble style subject
        submit subway success such sudden suffer sugar suggest suit summer sun sunny
        sunset super supply supreme sure surface surge surprise surround survey suspect sustain
        swallow swamp swap swarm swear sweet swift swim swing switch sword symbol
        symptom syrup system table tackle tag tail talent talk tank tape target
        task taste tattoo taxi teach team tell ten tenant tennis tent term
        test text thank that theme then theory there they thing this thought
        three thrive throw thumb thunder ticket tide tiger tilt timber time tiny
        tip tired tissue title toast tobacco today toddler toe together toilet token
        tomato tomorrow tone tongue tonight tool tooth top topic topple torch tornado
        tortoise toss total tourist toward tower town toy track trade traffic tragic
        train transfer trap trash travel tray treat tree trend trial tribe trick
        trigger trim trip trophy trouble truck true truly trumpet trust truth try
        tube tuition tumble tuna tunnel turkey turn turtle twelve twenty twice twin
        twist two type typical ugly umbrella unable unaware uncle uncover under undo
        unfair unfold unhappy uniform unique unit universe unknown unlock until unusual unveil
        update upgrade uphold upon upper upset urban urge usage use used useful
        useless usual utility vacant vacuum vague valid valley valve van vanish vapor
        various vast vault vehicle velvet vendor venture venue verb verify version very
        vessel veteran viable vibrant vicious victory video view village vintage violin virtual
        virus visa visit visual vital vivid vocal voice void volcano volume vote
        voyage wage wagon wait walk wall walnut want warfare warm warrior wash
        wasp waste water wave way wealth weapon wear weasel weather web wedding
        weekend weird welcome west wet whale what wheat wheel when where whip
        whisper wide width wife wild will win window wine wing wink winner
        winter wire wisdom wise wish witness wolf woman wonder wood wool word
        work world worry worth wrap wreck wrestle wrist write wrong yard year
        yellow you young youth zebra zero zone zoo
        """.split(' ', '\n').filter { it.isNotEmpty() }

    private val englishWords: Set<String> = englishWordlist.toSet()
}

/** [bytes] as UTF-8, or null when they are not valid UTF-8 — Swift's `String(validating:as:)`. */
private fun utf8OrNull(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    null
}

/** The code points of [s], in order. */
private fun codePoints(s: String): List<Int> {
    val out = ArrayList<Int>(s.length)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        out += cp
        i += Character.charCount(cp)
    }
    return out
}
