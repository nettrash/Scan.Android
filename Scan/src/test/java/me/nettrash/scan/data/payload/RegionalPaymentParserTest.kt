package me.nettrash.scan.data.payload

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

/** UPI / Czech SPD / Pay by Square / Bezahlcode / Swish / Vipps coverage. */
@RunWith(RobolectricTestRunner::class)
class RegionalPaymentParserTest {

    // ---- UPI --------------------------------------------------------------

    @Test fun parsesUPIPayURI() {
        val raw = "upi://pay?pa=merchant@upi&pn=Acme%20Store" +
            "&am=199.99&cu=INR&tn=Order%20%23123"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.UpiPayment::class.java)
        val u = (p as ScanPayload.UpiPayment).payload
        assertThat(u.payeeAddress).isEqualTo("merchant@upi")
        assertThat(u.payeeName).isEqualTo("Acme Store")
        assertThat(u.amount).isEqualTo("199.99")
        assertThat(u.currency).isEqualTo("INR")
        assertThat(u.note).isEqualTo("Order #123")
    }

    @Test fun rejectsUPIWithoutPayeeAddress() {
        val raw = "upi://pay?am=100&cu=INR"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isNotInstanceOf(ScanPayload.UpiPayment::class.java)
    }

    // ---- Czech SPD --------------------------------------------------------

    @Test fun parsesCzechSPD() {
        val raw = "SPD*1.0*ACC:CZ4912340000004567890123*AM:1500.00*CC:CZK*" +
            "MSG:Faktura+2024+09*X-VS:202409*"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.CzechSPD::class.java)
        val s = (p as ScanPayload.CzechSPD).payload
        assertThat(s.version).isEqualTo("1.0")
        assertThat(s.iban).isEqualTo("CZ4912340000004567890123")
        assertThat(s.amount).isEqualTo("1500.00")
        assertThat(s.currency).isEqualTo("CZK")
        // `+` decoded to space per SPD escaping rules.
        assertThat(s.message).isEqualTo("Faktura 2024 09")
        assertThat(s.variableSymbol).isEqualTo("202409")
    }

    // ---- Slovak Pay by Square --------------------------------------------

    @Test fun recognisesPayBySquare() {
        val raw = "0000A00000000000000000000000000000000000ABCDEF"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.PaBySquare::class.java)
    }

    @Test fun doesNotMisclassifyPayBySquareLookalike() {
        // Right length, wrong header.
        val raw = "ABCDEF0000000000000000000000000000000000ABCDEF"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isNotInstanceOf(ScanPayload.PaBySquare::class.java)
    }

    // ---- Bezahlcode -------------------------------------------------------

    @Test fun parsesBezahlcode() {
        val raw = "bank://singlepaymentsepa?name=Acme%20GmbH" +
            "&iban=DE89370400440532013000&amount=42.00&currency=EUR" +
            "&reason=Invoice%20%2342"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.Regional::class.java)
        val r = (p as ScanPayload.Regional).payload
        assertThat(r.scheme).isEqualTo(RegionalPaymentPayload.Scheme.BEZAHLCODE)
        val labels = r.parsed.associate { it.label to it.value }
        assertThat(labels["Beneficiary"]).isEqualTo("Acme GmbH")
        assertThat(labels["IBAN"]).isEqualTo("DE89370400440532013000")
        assertThat(labels["Amount"]).isEqualTo("42.00")
        assertThat(labels["Purpose"]).isEqualTo("Invoice #42")
    }

    // ---- Swish (base64-JSON) ---------------------------------------------

    @Test fun parsesSwishWithBase64JSON() {
        val json = """{"payee":"+46701234567","amount":"100","message":"Lunch"}"""
        val b64 = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val raw = "swish://payment?data=$b64"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.Regional::class.java)
        val r = (p as ScanPayload.Regional).payload
        assertThat(r.scheme).isEqualTo(RegionalPaymentPayload.Scheme.SWISH)
        val labels = r.parsed.associate { it.label to it.value }
        assertThat(labels["Payee"]).isEqualTo("+46701234567")
        assertThat(labels["Amount"]).isEqualTo("100")
        assertThat(labels["Message"]).isEqualTo("Lunch")
    }

    // ---- Vipps ------------------------------------------------------------

    @Test fun recognisesVippsURI() {
        val raw = "vipps://?phonenumber=+4791234567&amount=200&message=Pizza"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.Regional::class.java)
        assertThat((p as ScanPayload.Regional).payload.scheme)
            .isEqualTo(RegionalPaymentPayload.Scheme.VIPPS)
    }
}
