package me.nettrash.scan.data.payload

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The EN 16931 e-invoice QR added in 1.10. Two fixtures: an XRechnung
 * document in the real CII syntax binding — the shape a German
 * public-sector invoice actually has — and the iOS suite's
 * `xrechnungXML`, line for line, so both ports keep answering the same
 * questions about the same bytes.
 *
 * The CII fixture is the one that matters for correctness: it is what
 * showed the guideline, currency, due-date and seller-VAT lookups reading
 * element names a CII document never contains.
 */
@RunWith(RobolectricTestRunner::class)
class InvoicePayloadTest {

    /**
     * EN 16931 CII as written: BT-23 (business process) ahead of BT-24
     * (guideline), BT-5 `InvoiceCurrencyCode` in the header settlement,
     * BT-9 under `SpecifiedTradePaymentTerms`, BT-31 as a `VA` tax
     * registration and the buyer's VAT id right after the seller's.
     */
    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rsm:CrossIndustryInvoice xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"
            xmlns:ram="urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100"
            xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100">
          <rsm:ExchangedDocumentContext>
            <ram:BusinessProcessSpecifiedDocumentContextParameter>
              <ram:ID>urn:fdc:peppol.eu:2017:poacc:billing:01:1.0</ram:ID>
            </ram:BusinessProcessSpecifiedDocumentContextParameter>
            <ram:GuidelineSpecifiedDocumentContextParameter>
              <ram:ID>urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0</ram:ID>
            </ram:GuidelineSpecifiedDocumentContextParameter>
          </rsm:ExchangedDocumentContext>
          <rsm:ExchangedDocument>
            <ram:ID>R-2026-0142</ram:ID>
            <ram:TypeCode>380</ram:TypeCode>
            <ram:IssueDateTime>
              <udt:DateTimeString format="102">20260514</udt:DateTimeString>
            </ram:IssueDateTime>
          </rsm:ExchangedDocument>
          <rsm:SupplyChainTradeTransaction>
            <ram:IncludedSupplyChainTradeLineItem>
              <ram:AssociatedDocumentLineDocument>
                <ram:LineID>1</ram:LineID>
              </ram:AssociatedDocumentLineDocument>
              <ram:SpecifiedTradeProduct>
                <ram:GlobalID schemeID="0160">4012345678901</ram:GlobalID>
                <ram:Name>Beratungsleistung</ram:Name>
              </ram:SpecifiedTradeProduct>
              <ram:SpecifiedLineTradeSettlement>
                <ram:ApplicableTradeTax>
                  <ram:TypeCode>VAT</ram:TypeCode>
                  <ram:CategoryCode>S</ram:CategoryCode>
                  <ram:RateApplicablePercent>19.00</ram:RateApplicablePercent>
                </ram:ApplicableTradeTax>
                <ram:SpecifiedTradeSettlementLineMonetarySummation>
                  <ram:LineTotalAmount>1000.00</ram:LineTotalAmount>
                </ram:SpecifiedTradeSettlementLineMonetarySummation>
              </ram:SpecifiedLineTradeSettlement>
            </ram:IncludedSupplyChainTradeLineItem>
            <ram:ApplicableHeaderTradeAgreement>
              <ram:BuyerReference>04011000-12345-81</ram:BuyerReference>
              <ram:SellerTradeParty>
                <ram:Name>Muster GmbH</ram:Name>
                <ram:SpecifiedLegalOrganization>
                  <ram:ID>HRB 12345</ram:ID>
                </ram:SpecifiedLegalOrganization>
                <ram:PostalTradeAddress>
                  <ram:CityName>Berlin</ram:CityName>
                  <ram:CountryID>DE</ram:CountryID>
                </ram:PostalTradeAddress>
                <ram:SpecifiedTaxRegistration>
                  <ram:ID schemeID="FC">201/113/40209</ram:ID>
                </ram:SpecifiedTaxRegistration>
                <ram:SpecifiedTaxRegistration>
                  <ram:ID schemeID="VA">DE813792021</ram:ID>
                </ram:SpecifiedTaxRegistration>
              </ram:SellerTradeParty>
              <ram:BuyerTradeParty>
                <ram:Name>Beispiel Stadt</ram:Name>
                <ram:PostalTradeAddress>
                  <ram:CityName>Muenchen</ram:CityName>
                  <ram:CountryID>DE</ram:CountryID>
                </ram:PostalTradeAddress>
                <ram:SpecifiedTaxRegistration>
                  <ram:ID schemeID="VA">DE129273398</ram:ID>
                </ram:SpecifiedTaxRegistration>
              </ram:BuyerTradeParty>
            </ram:ApplicableHeaderTradeAgreement>
            <ram:ApplicableHeaderTradeDelivery/>
            <ram:ApplicableHeaderTradeSettlement>
              <ram:PaymentReference>R-2026-0142</ram:PaymentReference>
              <ram:InvoiceCurrencyCode>EUR</ram:InvoiceCurrencyCode>
              <ram:SpecifiedTradeSettlementPaymentMeans>
                <ram:TypeCode>58</ram:TypeCode>
                <ram:PayeePartyCreditorFinancialAccount>
                  <ram:IBANID>DE02120300000000202051</ram:IBANID>
                </ram:PayeePartyCreditorFinancialAccount>
              </ram:SpecifiedTradeSettlementPaymentMeans>
              <ram:ApplicableTradeTax>
                <ram:CalculatedAmount>190.00</ram:CalculatedAmount>
                <ram:TypeCode>VAT</ram:TypeCode>
                <ram:BasisAmount>1000.00</ram:BasisAmount>
                <ram:CategoryCode>S</ram:CategoryCode>
                <ram:RateApplicablePercent>19.00</ram:RateApplicablePercent>
              </ram:ApplicableTradeTax>
              <ram:SpecifiedTradePaymentTerms>
                <ram:Description>Zahlbar innerhalb von 30 Tagen</ram:Description>
                <ram:DueDateDateTime>
                  <udt:DateTimeString format="102">20260613</udt:DateTimeString>
                </ram:DueDateDateTime>
              </ram:SpecifiedTradePaymentTerms>
              <ram:SpecifiedTradeSettlementHeaderMonetarySummation>
                <ram:LineTotalAmount>1000.00</ram:LineTotalAmount>
                <ram:TaxBasisTotalAmount>1000.00</ram:TaxBasisTotalAmount>
                <ram:TaxTotalAmount currencyID="EUR">190.00</ram:TaxTotalAmount>
                <ram:GrandTotalAmount>1190.00</ram:GrandTotalAmount>
                <ram:DuePayableAmount>1190.00</ram:DuePayableAmount>
              </ram:SpecifiedTradeSettlementHeaderMonetarySummation>
            </ram:ApplicableHeaderTradeSettlement>
          </rsm:SupplyChainTradeTransaction>
        </rsm:CrossIndustryInvoice>
        """.trimIndent()

    /** The iOS `ScanTests.xrechnungXML`, joined the same way. */
    private val iosXml = listOf(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
        "<rsm:CrossIndustryInvoice xmlns:rsm=\"urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100\"",
        " xmlns:ram=\"urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100\">",
        " <rsm:ExchangedDocumentContext>",
        "  <ram:GuidelineSpecifiedDocumentContextParameter>",
        "   <ram:ID>urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0</ram:ID>",
        "  </ram:GuidelineSpecifiedDocumentContextParameter>",
        " </rsm:ExchangedDocumentContext>",
        " <rsm:ExchangedDocument><ram:ID>RE-2026-0042</ram:ID>",
        "  <ram:IssueDateTime><udt:DateTimeString xmlns:udt=\"urn:un:unece:uncefact:data:standard:UniqueDataType\">20260514</udt:DateTimeString></ram:IssueDateTime>",
        " </rsm:ExchangedDocument>",
        " <rsm:SupplyChainTradeTransaction>",
        "  <ram:ApplicableHeaderTradeAgreement>",
        "   <ram:BuyerReference>04011000-00000-55</ram:BuyerReference>",
        "   <ram:SellerTradeParty><ram:Name>Example GmbH</ram:Name>",
        "    <ram:SpecifiedLegalOrganization><ram:ID>DE812345678</ram:ID></ram:SpecifiedLegalOrganization>",
        "   </ram:SellerTradeParty>",
        "   <ram:BuyerTradeParty><ram:Name>Stadt Musterstadt</ram:Name></ram:BuyerTradeParty>",
        "  </ram:ApplicableHeaderTradeAgreement>",
        "  <ram:ApplicableHeaderTradeSettlement>",
        "   <ram:PaymentDueDate><udt:DateTimeString xmlns:udt=\"urn:un:unece:uncefact:data:standard:UniqueDataType\">20260613</udt:DateTimeString></ram:PaymentDueDate>",
        "   <ram:SpecifiedTradeSettlementHeaderMonetarySummation>",
        "    <ram:LineTotalAmount>1000.00</ram:LineTotalAmount>",
        "    <ram:TaxTotalAmount>190.00</ram:TaxTotalAmount>",
        "    <ram:GrandTotalAmount>1190.00</ram:GrandTotalAmount>",
        "    <ram:DuePayableAmount>1190.00</ram:DuePayableAmount>",
        "   </ram:SpecifiedTradeSettlementHeaderMonetarySummation>",
        "   <ram:CreditTransferTransferSettlement>",
        "    <ram:PayeePartyCreditorFinancialAccount><ram:IBANID>DE89370400440532013000</ram:IBANID></ram:PayeePartyCreditorFinancialAccount>",
        "   </ram:CreditTransferTransferSettlement>",
        "  </ram:ApplicableHeaderTradeSettlement>",
        " </rsm:SupplyChainTradeTransaction>",
        "</rsm:CrossIndustryInvoice>",
    ).joinToString("\n")

    private fun base64(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    // ---- The CII document ---------------------------------------------

    @Test fun recognisesRawXMLCarrier() {
        assertThat(InvoiceParser.looksLikeEInvoice(xml)).isTrue()
        assertThat(InvoiceParser.looksLikeEInvoice("just some text from a QR")).isFalse()
        assertThat(InvoiceParser.looksLikeEInvoice("aG93ZHk=")).isFalse()
    }

    @Test fun parsesRawXRechnung() {
        val p = ScanPayloadParser.parse(xml)
        assertThat(p).isInstanceOf(ScanPayload.EInvoice::class.java)
        assertThat(p.kindLabel).isEqualTo("E-Invoice")
        val inv = (p as ScanPayload.EInvoice).payload
        assertThat(inv.standard).isEqualTo("XRechnung (EN 16931, Germany)")
        assertThat(inv.invoiceNumber).isEqualTo("R-2026-0142")
        assertThat(inv.sellerName).isEqualTo("Muster GmbH")
        assertThat(inv.sellerVAT).isEqualTo("DE813792021")
        assertThat(inv.buyerName).isEqualTo("Beispiel Stadt")
        assertThat(inv.buyerReference).isEqualTo("04011000-12345-81")
        assertThat(inv.totalAmount).isEqualTo("1190.00")
        assertThat(inv.currency).isEqualTo("EUR")
        assertThat(inv.taxAmount).isEqualTo("190.00")
        assertThat(inv.issueDate).isEqualTo("2026-05-14")
        assertThat(inv.dueDate).isEqualTo("2026-06-13")
        assertThat(inv.iban).isEqualTo("DE02120300000000202051")
        assertThat(inv.guideline)
            .isEqualTo("urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0")
        assertThat(inv.wasEncoded).isFalse()
        assertThat(inv.raw).isEqualTo(xml)
    }

    @Test fun rowsQuoteWhatTheDocumentAsserts() {
        val inv = InvoiceParser.parse(xml)!!
        assertThat(inv.labelledFields().map { it.label }).containsExactly(
            "Format", "Invoice no.", "Seller", "Seller VAT", "Buyer", "Buyer ref / Leitweg-ID",
            "Total", "Tax", "Issued", "Due", "IBAN", "Guideline",
        ).inOrder()
        val rows = inv.labelledFields().associate { it.label to it.value }
        assertThat(rows["Format"]).isEqualTo("XRechnung (EN 16931, Germany)")
        assertThat(rows["Invoice no."]).isEqualTo("R-2026-0142")
        assertThat(rows["Seller"]).isEqualTo("Muster GmbH")
        assertThat(rows["Seller VAT"]).isEqualTo("DE813792021")
        assertThat(rows["Buyer"]).isEqualTo("Beispiel Stadt")
        assertThat(rows["Buyer ref / Leitweg-ID"]).isEqualTo("04011000-12345-81")
        assertThat(rows["Total"]).isEqualTo("1190.00 EUR")
        assertThat(rows["Tax"]).isEqualTo("190.00")
        assertThat(rows["Issued"]).isEqualTo("2026-05-14")
        assertThat(rows["Due"]).isEqualTo("2026-06-13")
        assertThat(rows["IBAN"]).isEqualTo("DE02120300000000202051")
        assertThat(rows["Guideline"]).contains("xrechnung_3.0")
    }

    @Test fun parsesBase64CarrierAndSaysSo() {
        val encoded = base64(xml)
        assertThat(InvoiceParser.looksLikeEInvoice(encoded)).isTrue()
        val p = ScanPayloadParser.parse(encoded)
        assertThat(p).isInstanceOf(ScanPayload.EInvoice::class.java)
        val inv = (p as ScanPayload.EInvoice).payload
        assertThat(inv.wasEncoded).isTrue()
        assertThat(inv.raw).isEqualTo(encoded)
        assertThat(inv.standard).contains("XRechnung")
        assertThat(inv.invoiceNumber).isEqualTo("R-2026-0142")
        assertThat(inv.sellerName).isEqualTo("Muster GmbH")
        assertThat(inv.currency).isEqualTo("EUR")
        assertThat(inv.issueDate).isEqualTo("2026-05-14")
        assertThat(inv.labelledFields().associate { it.label to it.value }["Carrier"])
            .isEqualTo("base64-encoded XML")
    }

    @Test fun base64CarrierIgnoresLineBreaksButNotMissingPadding() {
        // The iOS decode ignores characters outside the alphabet (a
        // MIME-wrapped blob) yet still wants whole, padded groups.
        val wrapped = java.util.Base64.getMimeEncoder().encodeToString(xml.toByteArray(Charsets.UTF_8))
        assertThat(wrapped).contains("\r\n")
        assertThat(InvoiceParser.parse(wrapped)?.invoiceNumber).isEqualTo("R-2026-0142")
        // The pre-check still wants a single line before paying for a decode.
        assertThat(InvoiceParser.looksLikeEInvoice(wrapped)).isFalse()

        // One of any three consecutive lengths needs no padding, so pick a
        // trailing-space count whose encoding does end in `=`.
        val padded = listOf("", " ", "  ").map { base64(xml + it) }.first { it.endsWith("=") }
        assertThat(InvoiceParser.parse(padded)).isNotNull()
        assertThat(InvoiceParser.parse(padded.trimEnd('='))).isNull()
    }

    @Test fun guidelineIsTheSpecificationNotTheBusinessProcess() {
        // BT-23 comes first in CII and also ends in
        // `DocumentContextParameter`; only BT-24 is the guideline.
        val inv = InvoiceParser.parse(xml)!!
        assertThat(inv.guideline).doesNotContain("poacc")
        assertThat(inv.guideline).isNotEqualTo(inv.invoiceNumber)
        assertThat(inv.invoiceNumber).doesNotContain("cen.eu")

        val noGuideline = xml.replace(
            Regex("<ram:GuidelineSpecifiedDocumentContextParameter>[\\s\\S]*?</ram:GuidelineSpecifiedDocumentContextParameter>"),
            "",
        )
        assertThat(InvoiceParser.parse(noGuideline)?.guideline).isNull()
    }

    @Test fun currencyIsTheInvoiceCurrencyNeverTheVatAccountingCurrency() {
        // BT-6 `TaxCurrencyCode` is the VAT accounting currency; quoting it
        // next to the grand total would misstate the total.
        val vatOnly = xml.replace(
            "<ram:InvoiceCurrencyCode>EUR</ram:InvoiceCurrencyCode>",
            "<ram:TaxCurrencyCode>CHF</ram:TaxCurrencyCode>",
        )
        val inv = InvoiceParser.parse(vatOnly)!!
        assertThat(inv.currency).isNull()
        assertThat(inv.labelledFields().associate { it.label to it.value }["Total"]).isEqualTo("1190.00")

        // UBL's name for BT-5 is still read.
        val ublName = xml.replace(
            "<ram:InvoiceCurrencyCode>EUR</ram:InvoiceCurrencyCode>",
            "<cbc:DocumentCurrencyCode>SEK</cbc:DocumentCurrencyCode>",
        )
        assertThat(InvoiceParser.parse(ublName)?.currency).isEqualTo("SEK")
    }

    @Test fun sellerVatIsTheSellersVaRegistration() {
        val inv = InvoiceParser.parse(xml)!!
        // Not the FC tax number, not the legal registration, not the buyer's VA id.
        assertThat(inv.sellerVAT).isEqualTo("DE813792021")

        val fcOnly = xml.replace("<ram:ID schemeID=\"VA\">DE813792021</ram:ID>", "")
        assertThat(InvoiceParser.parse(fcOnly)?.sellerVAT).isNull()
    }

    @Test fun sellerVatIsNeverBorrowedFromAnotherParty() {
        // A VAT-shaped invoice number comes first in the document, and the
        // buyer has a VA registration: neither is the seller's VAT id.
        val noSellerVat = xml
            .replace("<ram:ID>R-2026-0142</ram:ID>", "<ram:ID>INV20260001</ram:ID>")
            .replace("<ram:ID schemeID=\"VA\">DE813792021</ram:ID>", "")
        val inv = InvoiceParser.parse(noSellerVat)!!
        assertThat(inv.invoiceNumber).isEqualTo("INV20260001")
        assertThat(inv.sellerVAT).isNull()
        assertThat(inv.labelledFields().map { it.label }).doesNotContain("Seller VAT")
    }

    @Test fun anchoringKeepsLookalikeElementNamesApart() {
        // `Name` under a tax representative must not become the seller, and
        // an anchor that is absent means no match at all — not "search the
        // whole document".
        val swapped = xml
            .replace("<ram:SellerTradeParty>", "<ram:TaxRepresentativeTradeParty>")
            .replace("</ram:SellerTradeParty>", "</ram:TaxRepresentativeTradeParty>")
            .replace("<ram:BuyerTradeParty>", "<ram:TaxRepresentativeTradeParty>")
            .replace("</ram:BuyerTradeParty>", "</ram:TaxRepresentativeTradeParty>")
        val inv = InvoiceParser.parse(swapped)
        assertThat(inv?.sellerName).isNull()
        assertThat(inv?.buyerName).isNull()
        assertThat(inv?.sellerVAT).isNull()
        // Still an invoice — the number and total carry it.
        assertThat(inv?.invoiceNumber).isEqualTo("R-2026-0142")
        assertThat(inv?.totalAmount).isEqualTo("1190.00")
    }

    @Test fun datesNormaliseOnlyWhenTheyAreDigits() {
        val inv = InvoiceParser.parse(
            "<rsm:CrossIndustryInvoice><rsm:ExchangedDocument><ram:ID>X1</ram:ID>" +
                "<ram:IssueDateTime><udt:DateString>2026-05-14</udt:DateString></ram:IssueDateTime>" +
                "</rsm:ExchangedDocument><ram:PaymentDueDate>June 13</ram:PaymentDueDate>" +
                "</rsm:CrossIndustryInvoice>"
        )!!
        assertThat(inv.issueDate).isEqualTo("2026-05-14")
        assertThat(inv.dueDate).isEqualTo("June 13")
    }

    @Test fun unreadableBlobStaysText() {
        val stub = "<rsm:CrossIndustryInvoice>" +
            "<ram:SomeBlob>nothing a buyer can reconcile</ram:SomeBlob>" +
            "</rsm:CrossIndustryInvoice>"
        assertThat(InvoiceParser.parse(stub)).isNull()
        assertThat(ScanPayloadParser.parse(stub)).isInstanceOf(ScanPayload.Text::class.java)
    }

    @Test fun namesProfilesByTheirMarkers() {
        fun cii(marker: String) = InvoiceParser.parse(
            "<rsm:CrossIndustryInvoice><rsm:ExchangedDocument>" +
                "<ram:ID>INV-1</ram:ID></rsm:ExchangedDocument>" +
                "<ram:GrandTotalAmount>10.00</ram:GrandTotalAmount>$marker" +
                "</rsm:CrossIndustryInvoice>"
        )
        assertThat(cii("<ram:Name>ZUGFeRD</ram:Name>")?.standard)
            .isEqualTo("ZUGFeRD (EN 16931, Germany)")
        assertThat(cii("<ram:Name>Factur-X</ram:Name>")?.standard)
            .isEqualTo("Factur-X (EN 16931, France)")
        // The parser reads CII only, so a CII document that merely mentions
        // Peppol or UBL is no Peppol or UBL invoice.
        assertThat(cii("<ram:Name>Peppol</ram:Name>")?.standard)
            .isEqualTo("Electronic invoice (UN/CEFACT)")
        assertThat(cii("<ram:Name>UBL</ram:Name>")?.standard)
            .isEqualTo("Electronic invoice (UN/CEFACT)")
        assertThat(cii("<ram:Name>en16931</ram:Name>")?.standard)
            .isEqualTo("EN 16931 e-invoice")
        assertThat(cii("<ram:Name>Whatever</ram:Name>")?.standard)
            .isEqualTo("Electronic invoice (UN/CEFACT)")
    }

    @Test fun aUblInvoiceIsNotRead() {
        // Peppol BIS Billing 3.0 is UBL — the iOS `testInvoiceProfilesAreOnlyTheCIIOnes`.
        val ubl = "<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\" " +
            "xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\">" +
            "<cbc:CustomizationID>urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0" +
            "</cbc:CustomizationID><cbc:ID>INV-9</cbc:ID>" +
            "<cbc:PayableAmount currencyID=\"EUR\">125.00</cbc:PayableAmount></Invoice>"
        assertThat(ScanPayloadParser.parse(ubl).kindLabel).isEqualTo("Text")
    }

    @Test fun valuesAreTrimmedBySwiftsWhitespaceSet() {
        // A leading U+001C is not whitespace to Swift, so iOS keeps it; so
        // does this port now (Kotlin's `trim()` stripped it).
        val invoice = "<rsm:CrossIndustryInvoice><rsm:ExchangedDocument><ram:ID>INV-1</ram:ID></rsm:ExchangedDocument>" +
            "<ram:SellerTradeParty><ram:Name>\u001CACME GmbH</ram:Name></ram:SellerTradeParty>" +
            "<ram:GrandTotalAmount>10.00</ram:GrandTotalAmount></rsm:CrossIndustryInvoice>"
        assertThat(InvoiceParser.parse(invoice)?.sellerName).isEqualTo("\u001CACME GmbH")
    }

    // ---- The iOS suite's fixture (ScanTests: EN 16931 e-invoice) ------

    @Test fun iosParsesXRechnungInvoice() {
        val p = ScanPayloadParser.parse(iosXml)
        assertThat(p).isInstanceOf(ScanPayload.EInvoice::class.java)
        val inv = (p as ScanPayload.EInvoice).payload
        assertThat(inv.standard).contains("XRechnung")
        assertThat(inv.invoiceNumber).isEqualTo("RE-2026-0042")
        assertThat(inv.sellerName).isEqualTo("Example GmbH")
        assertThat(inv.sellerVAT).isEqualTo("DE812345678")
        assertThat(inv.buyerName).isEqualTo("Stadt Musterstadt")
        assertThat(inv.buyerReference).isEqualTo("04011000-00000-55")
        assertThat(inv.totalAmount).isEqualTo("1190.00")
        assertThat(inv.issueDate).isEqualTo("2026-05-14")
        assertThat(inv.dueDate).isEqualTo("2026-06-13")
        assertThat(inv.guideline).contains("xrechnung_3.0")
        assertThat(inv.wasEncoded).isFalse()
        // No BT-5 element in this fixture, so the total carries no currency.
        assertThat(inv.currency).isNull()
        assertThat(inv.iban).isEqualTo("DE89370400440532013000")
    }

    @Test fun iosParsesBase64EncodedInvoiceCarrier() {
        val p = ScanPayloadParser.parse(base64(iosXml))
        assertThat(p).isInstanceOf(ScanPayload.EInvoice::class.java)
        val inv = (p as ScanPayload.EInvoice).payload
        assertThat(inv.wasEncoded).isTrue()
        assertThat(inv.invoiceNumber).isEqualTo("RE-2026-0042")
    }

    @Test fun iosInvoiceDoesNotStealFieldsFromTheWrongParent() {
        // The guideline identifier is also a `<ram:ID>`; anchoring must keep
        // it out of the invoice-number slot.
        val inv = (ScanPayloadParser.parse(iosXml) as ScanPayload.EInvoice).payload
        assertThat(inv.invoiceNumber).isNotEqualTo(inv.guideline)
    }

    @Test fun iosArbitraryBase64IsNotAnInvoice() {
        val noise = base64("not an invoice ".repeat(40))
        assertThat(InvoiceParser.looksLikeEInvoice(noise)).isFalse()
        assertThat(ScanPayloadParser.parse(noise)).isNotInstanceOf(ScanPayload.EInvoice::class.java)
    }
}
