package me.nettrash.scan.data.payload

import me.nettrash.scan.ui.components.friendlyWifiSecurity
import java.util.Locale

//
//  The assistant's offline floor. When no on-device generative model is
//  present the "what is this code?" button still has to answer, so every
//  recognised payload kind carries its own plain-language write-up here. It is
//  also the safety net when a model returns something unusable, and — because
//  it is pure and deterministic — the half of the assistant we can actually
//  unit-test. Port of the iOS app's `Scan/HeuristicExplainer.swift`.
//

object HeuristicExplainer {

    fun explain(raw: String, payload: ScanPayload?): AssistantExplanation {
        val kind = payload ?: ScanPayloadParser.parse(raw)
        val points = mutableListOf<String>()
        val cautions = mutableListOf<String>()

        when (kind) {
            is ScanPayload.Url -> {
                // The host as the code spells it, split the way the safety
                // check (and the iOS port) split it.
                val url = parseUrl(kind.url)
                val host = url?.host?.ifEmpty { null } ?: kind.url
                points += "Opens $host in your browser."
                // `http:example.com` has a scheme even though it does not split.
                if ((url?.scheme ?: kind.url.substringBefore(':', "").lowercase()) == "http") {
                    points += "The site is served without encryption."
                }
                cautions += "Check the spelling of the domain before you sign in or pay."
            }

            is ScanPayload.Email -> {
                points += "Starts a new message to ${kind.address}."
                kind.subject?.let { if (it.isNotEmpty()) points += "Pre-filled subject: $it." }
            }

            is ScanPayload.Phone -> {
                points += "Places a call to ${kind.number}."
                cautions += "A QR is not a caller-ID — the number is whatever the code says."
            }

            is ScanPayload.Sms -> {
                points += "Opens a message to ${kind.number}."
                kind.body?.let { if (it.isNotEmpty()) points += "Pre-filled text: ${SwiftText.prefix(it, 80)}." }
                cautions += "Some codes auto-send a premium-rate SMS — read the text before you confirm."
            }

            is ScanPayload.Wifi -> {
                // The safety check's own rule, so the two cannot disagree.
                val isOpen = QrRiskAssessor.isOpenNetwork(kind.password, kind.security)
                points += "Joins the Wi-Fi network “${kind.ssid}”."
                // A code with no `T:` field and no password is an open
                // network, not a WPA one.
                points += "Security: ${friendlyWifiSecurity(kind.security ?: if (isOpen) "nopass" else "WPA")}."
                if (kind.hidden) points += "The network hides its name."
                if (isOpen) points += "No password is needed — the network is open."
                cautions += "Only join networks whose name you can see printed by the venue."
            }

            is ScanPayload.Geo -> {
                points += "Opens a map at " +
                    String.format(Locale.US, "%.5f, %.5f", kind.latitude, kind.longitude) + "."
                kind.query?.let { if (it.isNotEmpty()) points += "Search text: $it." }
            }

            is ScanPayload.Contact -> {
                val c = kind.payload
                val name = c.fullName
                if (name != null && name.isNotEmpty()) {
                    points += "Adds “$name” to your contacts."
                } else {
                    points += "Adds a contact card to your contacts."
                }
                c.phones.firstOrNull()?.let { points += "Phone $it." }
                c.organization?.let { if (it.isNotEmpty()) points += "Organisation $it." }
                points += "Nothing is saved until you press Add in the system form."
            }

            is ScanPayload.Calendar -> {
                val event = kind.payload
                points += "Proposes a calendar entry: ${event.summary ?: "untitled"}."
                event.location?.let { if (it.isNotEmpty()) points += "Location $it." }
                points += "Nothing is saved until you press Add in the system form."
            }

            is ScanPayload.Otp -> {
                points += "Enrols this device in two-factor sign-in for an account."
                cautions += "Never show or forward this code — anyone holding it can sign in as you."
            }

            is ScanPayload.OtpMigration -> {
                points += "Bulk-imports several two-factor accounts at once."
                cautions += "Only scan a 2FA export you started yourself."
            }

            is ScanPayload.ProductCode -> {
                points += "Retail item identifier — a ${kind.system} code."
                points += "Barcode number ${kind.code}."
                points += "Scan does not look prices up; that would need a server."
            }

            is ScanPayload.Crypto -> {
                val p = kind.payload
                points += "Requests a ${p.chain.displayName} payment to address ${prefix(p.address)}."
                p.amount?.let { points += "Asks for $it." }
                cautions += "Verify the address in your wallet before sending — crypto transfers cannot be undone."
            }

            is ScanPayload.EpcPayment -> {
                val p = kind.payload
                points += "A SEPA bank transfer request."
                p.beneficiaryName?.let { points += "Payee $it." }
                if (p.amount != null && p.currency != null) {
                    points += "Amount ${p.amount} ${p.currency}."
                }
                cautions += "Compare the IBAN with the printed invoice before you approve."
            }

            is ScanPayload.EmvPayment -> {
                val p = kind.payload
                points += "A merchant payment QR (EMVCo)."
                p.merchantName?.let { points += "Merchant $it." }
                if (p.valueFor("01") == "11") {
                    points += "It is a static code, reusable for any number of payments."
                }
                cautions += "Confirm the merchant name and amount in your bank app."
            }

            is ScanPayload.SwissQRBill, is ScanPayload.IpsPayment, is ScanPayload.UpiPayment,
            is ScanPayload.CzechSPD, is ScanPayload.Regional, is ScanPayload.RuPayment,
            is ScanPayload.PaBySquare -> {
                points += "A payment request in a regional standard."
                points += "Scan shows the individual fields so you can check each one."
                cautions += "Confirm the recipient and amount in your own banking app."
            }

            is ScanPayload.SufReceipt, is ScanPayload.FnsReceipt -> {
                points += "A fiscal receipt verification code."
                points += "Opens the tax authority's verification page for this receipt."
            }

            is ScanPayload.WalletConnect -> {
                points += "Pairs a crypto wallet with a decentralised app."
                points += "WalletConnect v${kind.payload.version}."
                cautions += "Approving a pairing lets that app see your addresses and ask you to sign."
            }

            is ScanPayload.Nostr -> {
                val p = kind.payload
                if (p.entity == NostrPayload.Entity.PRIVATE_KEY) {
                    // Not even a prefix of the key: an explanation is not the
                    // place to repeat a secret.
                    points += "A Nostr ${p.entity.displayName.lowercase(Locale.ROOT)}."
                    cautions += "This is a private key. Anyone holding it can post and spend as you."
                } else {
                    points += "A Nostr ${p.entity.displayName.lowercase(Locale.ROOT)} — ${prefix(p.id)}."
                }
            }

            is ScanPayload.Passkey -> {
                points += "Cross-device sign-in: it asks your phone to act as the passkey authenticator."
                cautions += "Only approve a passkey code you started yourself."
            }

            is ScanPayload.MobileDrivingLicense -> {
                points += kind.payload.keyPoints()
                cautions += kind.payload.cautions()
            }

            is ScanPayload.VerifiableCredential -> {
                val p = kind.payload
                points += p.explanationPoints()
                cautions += p.cautions()
            }

            is ScanPayload.EInvoice -> {
                val p = kind.payload
                points += "An electronic-invoice summary in ${p.standard}."
                p.totalAmount?.let { points += "Total $it." }
                cautions += "Reconcile the IBAN and amount against the document before paying."
            }

            is ScanPayload.BoardingPass -> {
                val leg = kind.payload.legs.firstOrNull()
                if (leg != null) {
                    points += "Flight ${leg.carrier}${leg.flightNumber}, ${leg.from} → ${leg.to}."
                }
                cautions += "A boarding pass is a bearer document — do not post it."
            }

            is ScanPayload.DrivingLicense -> {
                points += "The machine-readable data from a driving licence."
                cautions += "Never photograph or share this — it is enough to impersonate you."
            }

            is ScanPayload.GS1 -> {
                points += "Product data in GS1 format."
                kind.payload.gtin?.let { points += "GTIN $it." }
            }

            is ScanPayload.DigitalProductPassport -> {
                points += "A Digital Product Passport record."
                points += "Product ${prefix(kind.payload.gtin ?: "unknown")}."
            }

            is ScanPayload.Magnet -> {
                points += "A torrent download link."
                cautions += "Content is whatever the swarm serves, not what the label said."
            }

            is ScanPayload.RichUrl -> points += "Opens ${kind.payload.kind.displayName} content."

            is ScanPayload.PlusCode, is ScanPayload.What3Words -> {
                points += "A compact way to write down a location."
            }

            is ScanPayload.Iban -> {
                points += "A bank account number in IBAN form (${kind.payload.countryCode})."
                cautions += "Check it against the invoice — a changed digit sends money elsewhere."
            }

            is ScanPayload.Text -> {
                val count = SwiftText.count(kind.text)
                points += "Plain text, $count character${if (count == 1) "" else "s"}."
            }
        }

        val report = QrRiskAssessor.assess(raw, kind)
        cautions += report.findings
            .filter { it.severity != QrRiskFinding.Severity.NOTE }
            .map { it.detail }
            .take(3)

        return AssistantExplanation(
            headline = headline(kind),
            summary = summary(kind, points),
            keyPoints = points.take(6),
            cautions = cautions.distinct().sorted(),
            source = AssistantSource.BUILT_IN,
        )
    }

    // MARK: Text builders

    private fun headline(payload: ScanPayload): String = when (payload) {
        is ScanPayload.Url -> "A web link"
        is ScanPayload.Email -> "An email to compose"
        is ScanPayload.Phone -> "A phone number to dial"
        is ScanPayload.Sms -> "A text message to send"
        is ScanPayload.Wifi -> "A Wi-Fi login"
        is ScanPayload.Geo -> "A map location"
        is ScanPayload.Contact -> "A contact card"
        is ScanPayload.Calendar -> "A calendar invitation"
        is ScanPayload.Otp -> "A two-factor enrolment"
        is ScanPayload.OtpMigration -> "A two-factor export"
        is ScanPayload.ProductCode -> "A retail barcode"
        is ScanPayload.Crypto -> "A crypto payment request"
        is ScanPayload.EpcPayment -> "A SEPA payment request"
        is ScanPayload.EmvPayment -> "A merchant payment QR"
        is ScanPayload.SwissQRBill -> "A Swiss QR-bill"
        is ScanPayload.IpsPayment -> "A Serbian IPS payment"
        is ScanPayload.UpiPayment -> "An Indian UPI payment"
        is ScanPayload.CzechSPD -> "A Czech payment QR"
        is ScanPayload.SufReceipt, is ScanPayload.FnsReceipt -> "A fiscal receipt check"
        is ScanPayload.WalletConnect -> "A wallet pairing request"
        is ScanPayload.Nostr -> "A Nostr identity or note"
        is ScanPayload.Passkey -> "A cross-device sign-in request"
        is ScanPayload.MobileDrivingLicense -> payload.payload.headline
        is ScanPayload.VerifiableCredential -> payload.payload.kindLabel
        is ScanPayload.EInvoice -> "An electronic invoice"
        is ScanPayload.BoardingPass -> "A boarding pass"
        is ScanPayload.DrivingLicense -> "Driving licence data"
        is ScanPayload.GS1 -> "GS1 product data"
        is ScanPayload.DigitalProductPassport -> "A Digital Product Passport"
        is ScanPayload.Magnet -> "A torrent link"
        is ScanPayload.RichUrl -> "${payload.payload.kind.displayName} link"
        is ScanPayload.PlusCode, is ScanPayload.What3Words -> "A location code"
        is ScanPayload.Iban -> "A bank account (IBAN)"
        is ScanPayload.Regional -> "${payload.payload.scheme.displayName} payment"
        is ScanPayload.RuPayment -> "A Russian payment order"
        is ScanPayload.PaBySquare -> "A Slovak payment QR"
        is ScanPayload.Text -> "Plain text"
    }

    private fun summary(payload: ScanPayload, points: List<String>): String {
        val lead = points.firstOrNull() ?: "Scan recognised the contents and listed the fields."
        val rest = points.drop(1).take(2).joinToString(" ")
        return if (rest.isEmpty()) lead else "$lead $rest"
    }

    private fun prefix(value: String, count: Int = 10): String =
        if (value.length <= count * 2) value else "${value.take(count)}…${value.takeLast(4)}"


    // MARK: Compose fallback

    /**
     * Rule-based version of the Generate tab's "describe the code" box.
     * Deliberately narrow: it only claims a result when the request names the
     * thing explicitly ("wifi", "contact", a link, "text …"), otherwise the
     * user gets the normal form. The rules run in that order and the first one
     * that understands the request wins. Port of iOS `compose(request:)`.
     *
     * The patterns are the iOS ones. They are written without `\b`, `\s`,
     * `\w` and `\d`, which Android's regex engine (ICU, like iOS) reads as
     * Unicode classes but the JVM that runs the unit tests reads as ASCII:
     * [SP] is ICU's `\s`, [START] / [END] are its word boundary on either side
     * of a word, and `\p{Nd}` is its `\d`.
     */
    fun compose(request: String): ComposeResult? {
        val trimmed = request.trim()
        if (trimmed.isEmpty()) return null
        return composeWifi(trimmed)
            ?: composeContact(trimmed)
            ?: composeUrl(trimmed)
            ?: composeText(trimmed)
    }

    /** One whitespace character, and the same inside a bracket expression. */
    private const val SP = """[\t\n\f\r\p{Z}]"""
    private const val SP_CHARS = """\t\n\f\r\p{Z}"""

    /** A word starts / ends here (ICU's `\b` next to a letter or digit). */
    private const val START = """(?<![\p{L}\p{M}\p{Nd}\p{Pc}])"""
    private const val END = """(?![\p{L}\p{M}\p{Nd}\p{Pc}])"""

    // MARK: Wi-Fi

    /** Words that end a network name: whatever comes next describes the network rather than naming it. */
    private const val WIFI_KEYWORDS =
        "password|passphrase|passcode|pass|pwd|pw|key|hidden|security|encryption|wpa3|wpa2|wpa|wep|sae|nopass|open|no" +
            SP + "+password"

    /**
     * "wifi BlueHouse password hunter2 hidden", "Wi-Fi QR for guest network
     * BlueHouse, password hunter2", "guest wifi called Cafe Lumen with password
     * espresso42", `network "Home 5G" wpa3`.
     */
    private fun composeWifi(request: String): ComposeResult? {
        if (!matches(START + "(?:wi-?fi|wi" + SP + "+fi|wlan|ssid)" + END, request)) return null
        val values = mutableMapOf<String, String>()
        var rest = request

        // The password first, and then out of the way: what is left can be
        // searched for the network name without a password that happens to
        // contain "network" or "hidden" getting in the way. "password" beats
        // the looser "key" / "pass", so "wifi Key West password x" reads x; a
        // quoted password may contain spaces. "no password" is not one.
        val connector = "(?:[" + SP_CHARS + ",;]*" + START + "(?:with|and|using)" + SP + "+(?:the" + SP + "+|a" + SP + "+)?)?"
        val notNegated = "(?<!no" + SP + ")(?<!without" + SP + ")(?<!without" + SP + "a" + SP + ")"
        val introducer = SP + "*(?:(?:is|was)" + SP + "+|[=:]" + SP + "*)?"
        passwordSearch@ for (keywords in listOf("password|passphrase|passcode|pwd|pw", "pass|key")) {
            for (value in listOf("""["“]([^"”\n]{1,63})["”]""", "[\"“'‘]?([^" + SP_CHARS + "\"”'’,;]{3,63})[\"”'’]?")) {
                val pattern = connector + notNegated + START + "(?:" + keywords + ")" + END + introducer + value
                val (found, range) = captureWithRange(pattern, rest) ?: continue
                // A full stop that ends the sentence is not part of the password.
                val endsRequest = range.last + 1 == rest.length
                values["password"] = if (endsRequest && found.endsWith(".")) found.dropLast(1) else found
                rest = rest.replaceRange(range, " , ")
                break@passwordSearch
            }
        }

        val lower = rest.lowercase()
        val security = capture(START + "(wpa3|wpa2|wpa|wep|sae)" + END, lower)
        if (security != null) {
            values["security"] = security.uppercase()
        } else if (matches(
                START + "(?:nopass|no" + SP + "+password|without" + SP + "+(?:a" + SP + "+)?password|open" +
                    SP + "+(?:wi-?fi|network))" + END,
                lower,
            )
        ) {
            values["security"] = "NOPASS"
        }
        if (matches(START + "hidden" + END, lower)) values["hidden"] = "true"

        // The network name: a quoted name wins, then the most specific keyword
        // ("ssid", "name", "network", "named"/"called") before the bare
        // "wifi <name>" form, so "Wi-Fi QR for guest network X" reads X and
        // not "QR for guest network X".
        val name = "[\"“'‘]?([^" + SP_CHARS + "\"”'’,;][^\"”,;\\n]{0,31}?)[\"”'’]?"
        val stop = "(?=" + SP + "*(?:[,;]|\\.(?:" + SP + "|$)|$)|" + SP + "+(?:(?:with|and|using)" + SP + "+)*(?:" +
            WIFI_KEYWORDS + ")" + END + ")"
        val introduced = SP + "*(?:is" + SP + "+|[=:]" + SP + "*)?"
        val ssidPatterns = listOf(
            """["“]([^"”\n]{1,32})["”]""",
            START + "ssid" + END + introduced + name + stop,
            START + "(?:network" + SP + "+)?name" + END + introduced + name + stop,
            START + "network" + SP + "+(?:(?:is|named|called)" + SP + "+|[=:]" + SP + "*)?" + name + stop,
            START + "(?:named|called)" + SP + "+" + name + stop,
            START + "(?:wi-?fi|wi" + SP + "+fi|wlan)" + SP + "+(?:(?:qr" + SP + "+code|qr|code)" + SP + "+)?(?:(?:network|for)" +
                SP + "+)*" + name + stop,
        )
        for (pattern in ssidPatterns) {
            val ssid = capture(pattern, rest)
            if (ssid != null && ssid.isNotEmpty() &&
                !matches("^(?:" + WIFI_KEYWORDS + "|network|qr|code|for)$", ssid)
            ) {
                values["ssid"] = ssid
                break
            }
        }
        if (values["ssid"] == null) return null
        return ComposeResult(ComposeKind.WIFI, values, "", ComposeResult.ComposeSource.BUILT_IN)
    }

    // MARK: Contact

    /**
     * "vcard for Ana Ivić +381641234567 ana@example.com", "contact card for
     * Marko Petrović, phone 064 123 4567, company Acme".
     */
    private fun composeContact(request: String): ComposeResult? {
        if (!matches(
                "^" + SP + "*(?:vcard|v-card|contact)" + END + "|" + START + "(?:business|contact)" + SP + "+card" +
                    END + "|" + START + "vcard" + END,
                request,
            )
        ) {
            return null
        }
        val values = mutableMapOf<String, String>()
        capture("""([A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,})""", request)?.let { values["email"] = it }
        val phone = capture(
            """(?<![\p{L}\p{M}\p{Nd}\p{Pc}@.])(\+?\p{Nd}[\p{Nd} \-().]{5,20}\p{Nd})(?![\p{L}\p{M}\p{Nd}\p{Pc}@])""",
            request,
        )
        if (phone != null && phone.codePoints().filter { Character.isDigit(it) }.count() >= 7) {
            values["phone"] = phone
        }
        val stop = "(?=" + SP + """*(?:[,;]|$|\+|\p{Nd}|\(|[A-Za-z0-9._%+\-]+@)|""" + SP +
            "+(?:phone|tel|mobile|cell|email|e-mail|mail|company|org|organi[sz]ation|works)" + END + ")"
        capture(
            START + "(?:for|name|named|called)" + END + SP + "*:?" + SP + """+(\p{L}[\p{L}\p{M}'’ .\-]{1,59}?)""" + stop,
            request,
        )?.let { values["name"] = it }
        capture(
            START + "(?:company|org|organi[sz]ation|works" + SP + "+at)" + END + SP + "*:?" + SP +
                """+(\p{L}[\p{L}\p{M}0-9&'’ .\-]{1,59}?)""" + stop,
            request,
        )?.let { values["org"] = it }
        if (values["name"] == null && values["phone"] == null && values["email"] == null) return null
        return ComposeResult(ComposeKind.CONTACT, values, "", ComposeResult.ComposeSource.BUILT_IN)
    }

    // MARK: URL

    /**
     * "url https://example.com/x", or a bare domain when the request says it
     * is a link: "a QR for our website example.com/menu" (or münchen.de). A
     * domain behind another scheme (`ftp://…`) is not quietly made https.
     */
    private fun composeUrl(request: String): ComposeResult? {
        capture("(https?://[^$SP_CHARS]+)", request)?.let {
            return ComposeResult(ComposeKind.URL, emptyMap(), trimmingLinkPunctuation(it), ComposeResult.ComposeSource.BUILT_IN)
        }
        val label = """[\p{L}\p{Nd}](?:[\p{L}\p{M}\p{Nd}\-]*[\p{L}\p{M}\p{Nd}])?"""
        if (!matches(
                START + "(?:url|link|website|web" + SP + "+site|site|web" + SP + "+page|webpage|homepage)" + END,
                request,
            )
        ) {
            return null
        }
        val domain = capture(
            """(?<![@\p{L}\p{M}\p{Nd}\p{Pc}./\-])((?:www\.)?""" + label + """(?:\.""" + label +
                """)*\.\p{L}{2,}(?:/[^""" + SP_CHARS + "]*)?)",
            request,
        ) ?: return null
        return ComposeResult(
            ComposeKind.URL, emptyMap(), "https://" + trimmingLinkPunctuation(domain), ComposeResult.ComposeSource.BUILT_IN,
        )
    }

    // MARK: Text

    /** "text anything at all", "note: back in 5 minutes". */
    private fun composeText(request: String): ComposeResult? {
        val text = capture("(?s)^(?:text|note|message)" + END + SP + "*[:\\-]?" + SP + "+(.+)$", request) ?: return null
        return ComposeResult(ComposeKind.TEXT, emptyMap(), text, ComposeResult.ComposeSource.BUILT_IN)
    }

    // MARK: Regex helpers

    /** A sentence's closing punctuation is not part of the link. */
    private fun trimmingLinkPunctuation(link: String): String = link.trimEnd { it in ".,;:!?)\"”'’" }

    private fun regex(pattern: String): Regex? =
        runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()

    private fun matches(pattern: String, text: String): Boolean = regex(pattern)?.containsMatchIn(text) == true

    private fun capture(pattern: String, text: String): String? = captureWithRange(pattern, text)?.first

    /** The first capture group, trimmed, and the range of the whole match. */
    private fun captureWithRange(pattern: String, text: String): Pair<String, IntRange>? {
        val match = regex(pattern)?.find(text) ?: return null
        val group = match.groups[1] ?: return null
        return group.value.trim() to match.range
    }
}
