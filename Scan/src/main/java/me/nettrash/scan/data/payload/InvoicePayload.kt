package me.nettrash.scan.data.payload

/**
 * Electronic-invoice QR codes in the EN 16931 CII syntax (UN/CEFACT Cross
 * Industry Invoice) and the profiles built on it: ZUGFeRD / Factur-X and
 * XRechnung. UBL documents — Peppol BIS among them — are not read: the
 * gate below only admits CII. Where a payment code tells your bank what to
 * pay, an e-invoice QR carries the invoice *summary* itself, so a buyer can
 * check the payee and the total before approving a transfer — which is
 * exactly the mismatch a swapped QR exploits.
 *
 * Two carrier shapes are seen in the wild: the QR holds the XML document
 * (small invoices, or the "embedded QR" variant of ZUGFeRD), or it holds
 * that XML base64-encoded. Both are handled.
 *
 * Parsing is deliberately shallow: we pull the handful of elements a human
 * needs in order to say "yes, this is the invoice I am holding" and stop.
 * No XML validity check, no schema, no Schematron rules — that is what the
 * accounting software on the other end is for, and doing it here would
 * imply we certified the document.
 *
 * Mirrors the iOS `InvoicePayload.swift`: the lookups follow the EN 16931
 * CII syntax binding, the ones that are easy to get wrong name the business
 * term (BT-n) they read, and values are trimmed by Swift's whitespace set
 * ([PayloadCodec.trimSwiftWhitespace]).
 */
data class InvoicePayload(
    /** The profile / guideline family the document declares. */
    val standard: String,
    /**
     * `GuidelineSpecifiedDocumentContextParameter/ID`, verbatim — the
     * assertion we are quoting rather than classifying ourselves.
     */
    val guideline: String? = null,
    val invoiceNumber: String? = null,
    val sellerName: String? = null,
    val sellerVAT: String? = null,
    val buyerName: String? = null,
    /** German `Leitweg-ID` and the generic buyer reference. */
    val buyerReference: String? = null,
    val currency: String? = null,
    val totalAmount: String? = null,
    val taxAmount: String? = null,
    val issueDate: String? = null,
    val dueDate: String? = null,
    val iban: String? = null,
    /** True when the QR held base64 rather than raw XML. */
    val wasEncoded: Boolean,
    val raw: String,
) {
    fun labelledFields(): List<LabelledField> {
        val rows = mutableListOf(LabelledField("Format", standard))
        invoiceNumber?.let { rows += LabelledField("Invoice no.", it) }
        sellerName?.let { rows += LabelledField("Seller", it) }
        sellerVAT?.let { rows += LabelledField("Seller VAT", it) }
        buyerName?.let { rows += LabelledField("Buyer", it) }
        buyerReference?.let { rows += LabelledField("Buyer ref / Leitweg-ID", it) }
        totalAmount?.let { total ->
            rows += LabelledField("Total", currency?.let { c -> "$total $c" } ?: total)
        }
        taxAmount?.let { rows += LabelledField("Tax", it) }
        issueDate?.let { rows += LabelledField("Issued", it) }
        dueDate?.let { rows += LabelledField("Due", it) }
        iban?.let { rows += LabelledField("IBAN", it) }
        guideline?.let { rows += LabelledField("Guideline", it) }
        if (wasEncoded) rows += LabelledField("Carrier", "base64-encoded XML")
        return rows
    }
}

object InvoiceParser {

    private val markers = listOf(
        "CrossIndustryInvoice",
        "urn:un:unece:uncefact:data:standard:CrossIndustryInvoice",
        "ram:CrossIndustryInvoice",
        "<rsm:",
    )

    /**
     * Cheap pre-check used by the master parser before we attempt any
     * base64 decoding — a QR of arbitrary text must not pay that cost.
     */
    fun looksLikeEInvoice(raw: String): Boolean {
        val trimmed = PayloadCodec.trimSwiftWhitespace(raw)
        if (markers.any { trimmed.contains(it) }) return true
        // Base64 carrier: only worth a decode attempt when it is long,
        // single-line and base64-shaped.
        if (trimmed.length <= 160) return false
        if (!(trimmed.length % 4 == 0 || trimmed.endsWith("="))) return false
        if (!trimmed.all { it.isLetterOrDigit() || "+/=_-".contains(it) }) return false
        val data = PayloadCodec.base64Data(trimmed) ?: return false
        val text = PayloadCodec.utf8(data) ?: return false
        return markers.any { text.contains(it) }
    }

    fun parse(raw: String): InvoicePayload? {
        val trimmed = PayloadCodec.trimSwiftWhitespace(raw)
        var xml = trimmed
        var encoded = false
        if (!markers.any { trimmed.contains(it) }) {
            val data = PayloadCodec.base64Data(trimmed) ?: return null
            val text = PayloadCodec.utf8(data) ?: return null
            if (!markers.any { text.contains(it) }) return null
            xml = text
            encoded = true
        }
        if (!markers.any { xml.contains(it) }) return null

        val invoiceNumber = firstValue("ID", xml, "ExchangedDocument")
            ?: firstValue("ID", xml, "DocumentReference")
            ?: firstValue("DocumentNumber", xml, null)
            ?: firstValue("InvoiceNumber", xml, null)
        val sellerName = firstValue("Name", xml, "SellerTradeParty")
        val totalAmount = firstValue("GrandTotalAmount", xml, null)
        val buyerReference = firstValue("BuyerReference", xml, null)
            ?: firstValue("LeaderID", xml, null)

        val p = InvoicePayload(
            standard = standardFor(xml),
            // BT-24, the specification identifier. Named in full: the
            // suffix `DocumentContextParameter` also matches BT-23's
            // `BusinessProcessSpecifiedDocumentContextParameter`, which CII
            // puts first, so an XRechnung document would quote its
            // business-process id as the guideline.
            guideline = firstValue("ID", xml, "GuidelineSpecifiedDocumentContextParameter"),
            invoiceNumber = invoiceNumber,
            sellerName = sellerName,
            sellerVAT = vatNumber(xml),
            buyerName = firstValue("Name", xml, "BuyerTradeParty"),
            buyerReference = buyerReference,
            // BT-5, the currency the invoice's amounts are in: CII calls it
            // `InvoiceCurrencyCode`; a carrier that borrows the
            // `DocumentCurrencyCode` spelling is read too. Never
            // `TaxCurrencyCode` — that is BT-6, the VAT *accounting*
            // currency, which may differ, and pinning it to the total would
            // misstate the one figure the buyer is checking.
            currency = firstValue("InvoiceCurrencyCode", xml, null)
                ?: firstValue("DocumentCurrencyCode", xml, null),
            totalAmount = totalAmount,
            taxAmount = firstValue("TaxTotalAmount", xml, null)
                ?: firstValue("CalculatedAmount", xml, "ApplicableTradeTax"),
            issueDate = dateValue("IssueDateTime", xml),
            // BT-9: CII's `SpecifiedTradePaymentTerms/DueDateDateTime`, or
            // the `PaymentDueDate` spelling minimal summary carriers use.
            dueDate = dateValue("DueDateDateTime", xml) ?: dateValue("PaymentDueDate", xml),
            iban = firstValue("IBANID", xml, null)
                ?: firstValue("IBAN", xml, null)
                ?: firstValue("ID", xml, "CreditorFinancialAccount")
                ?: firstValue("ID", xml, "PayeeFinancialAccount"),
            wasEncoded = encoded,
            raw = raw,
        )
        // Nothing a human could reconcile against the paper invoice: better
        // to leave it as text than to badge an unreadable blob "e-invoice".
        if (p.invoiceNumber == null && p.sellerName == null && p.totalAmount == null &&
            p.buyerReference == null
        ) {
            return null
        }
        return p
    }

    // MARK: Profile naming

    private fun standardFor(xml: String): String {
        val lower = xml.lowercase()
        if (lower.contains("xrechnung") || lower.contains("leitweg") || lower.contains("br-de-1")) {
            return "XRechnung (EN 16931, Germany)"
        }
        if (lower.contains("zugferd")) return "ZUGFeRD (EN 16931, Germany)"
        if (lower.contains("factur-x") || lower.contains("facturx")) return "Factur-X (EN 16931, France)"
        if (lower.contains("en16931")) return "EN 16931 e-invoice"
        return "Electronic invoice (UN/CEFACT)"
    }

    // MARK: Element scraping
    //
    // Namespaced XML (`<ram:Name>`), so a match is on the local name. The
    // `anchor` keeps us out of trouble when the same element name appears
    // under several parents — `Name` exists for seller, buyer, tax
    // representative and payee alike.

    private fun firstValue(tag: String, xml: String, anchor: String?): String? {
        val search = searchArea(xml, anchor) ?: return null
        val pattern = "<(?:[A-Za-z0-9_]+:)?" + Regex.escape(tag) +
            "(?:\\s[^>]*)?>([^<]+)</(?:[A-Za-z0-9_]+:)?" + Regex.escape(tag) + ">"
        val regex = runCatching { Regex(pattern, RegexOption.DOT_MATCHES_ALL) }.getOrNull() ?: return null
        val match = regex.find(search) ?: return null
        val groups = match.groups
        if (groups.size < 2) return null
        val value = groups[1]?.value?.let { PayloadCodec.trimSwiftWhitespace(it) } ?: return null
        return if (value.isEmpty()) null else value
    }

    /**
     * The slice to look in. An anchor is an element *name*: we start just
     * after its opening tag and read forward. A missing anchor means "no
     * match", not "ignore the anchor" — `Name` from the tax representative
     * reported as the seller is the one mistake an invoice summary must not
     * make, and a substring match would also let `ExchangedDocumentContext`
     * stand in for `ExchangedDocument`.
     */
    private fun searchArea(xml: String, anchor: String?): String? {
        if (anchor == null) return xml
        // A leading `[A-Za-z0-9_]*` lets a suffix anchor work, because CII
        // qualifies the names it reuses: the guideline parameter is really
        // `GuidelineSpecifiedDocumentContextParameter`. The closing boundary
        // is what keeps `ExchangedDocument` from matching
        // `ExchangedDocumentContext`.
        val pattern = "<(?:[A-Za-z0-9_]+:)?[A-Za-z0-9_]*" +
            Regex.escape(anchor) + "(?:\\s[^>]*)?>"
        val regex = runCatching { Regex(pattern) }.getOrNull() ?: return null
        val match = regex.find(xml) ?: return null
        return xml.substring(match.range.last + 1)
    }

    /**
     * EN 16931 dates arrive as `DateTimeString` inside a
     * `…DateTime/DateTimeString` block, usually `20260514`.
     */
    private fun dateValue(tag: String, xml: String): String? {
        val raw = firstValue("DateTimeString", xml, tag)
            ?: firstValue("DateString", xml, tag)
            ?: firstValue(tag, xml, null)
            ?: return null
        val digits = raw.filter { it.isDigit() }
        if (digits.length >= 8) {
            val y = digits.substring(0, 4)
            val m = digits.substring(4, 6)
            val d = digits.substring(6, 8)
            return "$y-$m-$d"
        }
        return raw
    }

    /**
     * Seller VAT id, EN 16931 BT-31. In CII that is the seller's
     * `SpecifiedTaxRegistration/ID` with `schemeID="VA"` — the scheme is
     * what tells it apart from the tax number (`FC`, BT-32) kept in the
     * same element. A DE/FR/GB…-shaped id without a scheme still counts,
     * as minimal summary carriers put it under the legal organisation.
     *
     * Both are looked for inside the seller's own element and nowhere
     * else. The first VAT-shaped id in the document can be the invoice
     * number (`INV20260001` fits the shape and comes first) or the
     * buyer's VAT id, and calling either the seller's is the
     * misattribution this summary exists to prevent.
     */
    private fun vatNumber(xml: String): String? {
        val seller = elementBody(xml, "SellerTradeParty") ?: return null
        vatRegistration.find(seller)?.groupValues?.get(1)?.let { PayloadCodec.trimSwiftWhitespace(it) }
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        return vatShapedId.find(seller)?.groupValues?.get(1)
    }

    private val vatRegistration =
        Regex("<(?:[A-Za-z0-9_]+:)?ID\\s[^>]*\\bschemeID\\s*=\\s*[\"']VA[\"'][^>]*>([^<]+)<")

    private val vatShapedId =
        Regex("<(?:[A-Za-z0-9_]+:)?(?:ID|GlobalID)\\s*>\\s*([A-Z]{2}[0-9A-Z]{8,12})\\s*<")

    /**
     * The content of the first `<name>` element up to its own closing tag
     * — unlike [searchArea], which reads on to the end of the document.
     * Null when the element is missing or never closed: an unbounded
     * search is exactly how another party's id would leak in.
     */
    private fun elementBody(xml: String, name: String): String? {
        val open = Regex("<((?:[A-Za-z0-9_]+:)?" + Regex.escape(name) + ")(?:\\s[^>]*)?>")
            .find(xml) ?: return null
        val close = Regex("</" + Regex.escape(open.groupValues[1]) + "\\s*>")
            .find(xml, open.range.last + 1) ?: return null
        return xml.substring(open.range.last + 1, close.range.first)
    }
}
