package me.nettrash.scan.data.payload

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Bank / receipt parser coverage: EPC SEPA Payment, Russian unified payment
 * (ST00012), FNS retail receipt, EMVCo Merchant QR (top-level + nested
 * template drilling), Swiss QR-bill, Serbian SUF fiscal receipt URL,
 * Serbian NBS IPS QR. Mirrors the equivalent iOS XCTest cases line for line.
 */
@RunWith(RobolectricTestRunner::class)
class BankPaymentParserTest {

    @Test fun parsesEPCSEPAPayment() {
        val raw = """
            BCD
            002
            1
            SCT

            Acme GmbH
            DE89370400440532013000
            EUR12.34
            OTHR

            Invoice 2024-0001
        """.trimIndent()
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.EpcPayment::class.java)
        val epc = (p as ScanPayload.EpcPayment).payload
        assertThat(epc.beneficiaryName).isEqualTo("Acme GmbH")
        assertThat(epc.iban).isEqualTo("DE89370400440532013000")
        assertThat(epc.currency).isEqualTo("EUR")
        assertThat(epc.amount).isEqualTo("12.34")
        assertThat(epc.unstructuredRemittance).isEqualTo("Invoice 2024-0001")
    }

    @Test fun parsesRussianUnifiedPayment() {
        val raw = "ST00012|Name=ООО Ромашка|PersonalAcc=40702810000000000000|" +
            "BankName=Сбербанк|BIC=044525225|CorrespAcc=30101810400000000225|" +
            "PayeeINN=7707083893|Sum=12345|Purpose=Оплата по счёту 7"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.RuPayment::class.java)
        val ru = (p as ScanPayload.RuPayment).payload
        assertThat(ru.version).isEqualTo("ST00012")

        val dict = ru.labelledFields().associate { it.label to it.value }
        assertThat(dict["Recipient"]).isEqualTo("ООО Ромашка")
        assertThat(dict["Account"]).isEqualTo("40702810000000000000")
        assertThat(dict["BIC"]).isEqualTo("044525225")
        assertThat(dict["Recipient INN"]).isEqualTo("7707083893")
        // Sum is encoded in kopecks → rubles in the labelled field.
        assertThat(dict["Amount"]).isEqualTo("123.45 ₽")
    }

    @Test fun parsesFNSReceipt() {
        val raw = "t=20231225T1530&s=1234.56&fn=8710000100000123&i=12345&fp=987654321&n=1"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.FnsReceipt::class.java)
        val r = (p as ScanPayload.FnsReceipt).payload
        assertThat(r.rawTimestamp).isEqualTo("20231225T1530")
        assertThat(r.sum).isEqualTo("1234.56")
        assertThat(r.fiscalNumber).isEqualTo("8710000100000123")
        assertThat(r.receiptNumber).isEqualTo("12345")
        assertThat(r.fiscalSign).isEqualTo("987654321")
        assertThat(r.receiptTypeLabel).isEqualTo("Sale")
        assertThat(r.date).isNotNull()
    }

    @Test fun parsesEMVCoMerchantQR() {
        // Same payload as the iOS test; lengths are 2-digit decimal:
        //   00 02 01           Payload format
        //   01 02 12           Initiation: dynamic
        //   52 04 5812         Merchant category
        //   53 03 986          Currency BRL
        //   54 05 12.34        Amount
        //   58 02 BR           Country
        //   59 09 ACME LTDA    Merchant name
        //   60 08 SAOPAULO     Merchant city
        //   63 04 ABCD         CRC (placeholder)
        val raw = "000201" +
            "010212" +
            "52045812" +
            "5303986" +
            "540512.34" +
            "5802BR" +
            "5909ACME LTDA" +
            "6008SAOPAULO" +
            "6304ABCD"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.EmvPayment::class.java)
        val emv = (p as ScanPayload.EmvPayment).payload
        assertThat(emv.merchantName).isEqualTo("ACME LTDA")
        assertThat(emv.merchantCity).isEqualTo("SAOPAULO")
        assertThat(emv.country).isEqualTo("BR")
        assertThat(emv.amount).isEqualTo("12.34")
        assertThat(emv.currency).isEqualTo("986")
        val labels = emv.labelledFields().associate { it.label to it.value }
        assertThat(labels["Currency"]).isEqualTo("BRL (986)")
    }

    @Test fun parsesSwissQRBill() {
        // 31-line minimum QR-bill with QRR reference and unstructured message.
        val raw = """
            SPC
            0200
            1
            CH4431999123000889012
            S
            Robert Schneider AG
            Rue du Lac
            1268
            2501
            Biel
            CH







            199.95
            CHF
            S
            Pia-Maria Rutschmann-Schnyder
            Grosse Marktgasse
            28
            9400
            Rorschach
            CH
            QRR
            210000000003139471430009017
            Order of 19 May 2024
            EPD
        """.trimIndent()
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.SwissQRBill::class.java)
        val s = (p as ScanPayload.SwissQRBill).payload
        assertThat(s.iban).isEqualTo("CH4431999123000889012")
        assertThat(s.creditor?.name).isEqualTo("Robert Schneider AG")
        assertThat(s.amount).isEqualTo("199.95")
        assertThat(s.currency).isEqualTo("CHF")
        assertThat(s.ultimateDebtor?.name).isEqualTo("Pia-Maria Rutschmann-Schnyder")
        assertThat(s.referenceType).isEqualTo("QRR")
        assertThat(s.reference).isEqualTo("210000000003139471430009017")
        assertThat(s.unstructuredMessage).isEqualTo("Order of 19 May 2024")
    }

    @Test fun parsesSerbianSUFReceipt() {
        val raw = "https://suf.purs.gov.rs/v/?vl=A1F2VktKTjZUNFY3MEs1WmVsLVNlY3JldA"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.SufReceipt::class.java)
        assertThat((p as ScanPayload.SufReceipt).payload.url).isEqualTo(raw)
    }

    @Test fun nonSerbianSUFURLFallsThroughToURL() {
        val raw = "https://example.com/v/?vl=anything"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isNotInstanceOf(ScanPayload.SufReceipt::class.java)
    }

    @Test fun parsesNBSIPSPrintedBill() {
        val raw = "K:PR|V:01|C:1|R:160600000007029817|N:Acme%20DOO|" +
            "I:RSD250%2C00|SF:289|S:Test%20payment|RO:00%201234567890"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.IpsPayment::class.java)
        val ips = (p as ScanPayload.IpsPayment).payload
        assertThat(ips.kind).isEqualTo("PR")
        assertThat(ips.valueFor("R")).isEqualTo("160600000007029817")
        // Raw recipient name is percent-encoded; labelled fields decode.
        assertThat(ips.valueFor("N")).isEqualTo("Acme%20DOO")

        val labels = ips.labelledFields().associate { it.label to it.value }
        assertThat(labels["Recipient"]).isEqualTo("Acme DOO")
        assertThat(labels["Amount"]).isEqualTo("RSD250,00")
        assertThat(labels["Code"]).isEqualTo("Bill payment (PR)")
        assertThat(labels["Account"]).isEqualTo("160600000007029817")
    }

    @Test fun rejectsInvalidIPSPayload() {
        val raw = "K:PR|C:1|N:Acme"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isNotInstanceOf(ScanPayload.IpsPayment::class.java)
    }

    @Test fun drillsIntoEMVCoMerchantAccountTemplate() {
        // Merchant account info template at tag 26 carrying a Pix GUID
        // (sub-tag 00) + key (sub-tag 01).
        val guid = "BR.GOV.BCB.PIX"     // length 14
        val key = "alice@bcb.gov.br"    // length 16
        check(guid.length == 14 && key.length == 16) {
            "EMV nested test fixture lengths drifted"
        }
        val inner = "00" + "%02d".format(guid.length) + guid +
            "01" + "%02d".format(key.length) + key
        val merchantField = "26" + "%02d".format(inner.length) + inner
        val raw = "000201" +              // payload format
            "010211" +                    // initiation method
            merchantField +
            "5303986" +                   // currency BRL
            "5802BR" +
            "5907Test BR" +
            "6304ABCD"

        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.EmvPayment::class.java)
        val emv = (p as ScanPayload.EmvPayment).payload
        val labels = emv.labelledFields().map { it.label }
        assertThat(labels).contains("Pix account (26)")
        assertThat(labels.any { it.contains("Scheme GUID") }).isTrue()
        assertThat(labels.any { it.contains("Identifier") }).isTrue()
    }
}
