package me.nettrash.scan.data.payload

import com.google.common.truth.Truth.assertThat
import me.nettrash.scan.scanner.Symbology
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Mirrors the iOS app's `ScanTests` URL / Wi-Fi / mailto / tel / sms / geo /
 * vCard / MECARD / product-code / kindLabel coverage. Robolectric provides
 * a real `android.net.Uri` so the mailto / sms / URL paths exercise the
 * same `Uri.parse` / `getQueryParameter` calls they would on a device.
 */
@RunWith(RobolectricTestRunner::class)
class ScanPayloadParserTest {

    // ---- URL --------------------------------------------------------------

    @Test fun parsesHttpsURL() {
        val p = ScanPayloadParser.parse("https://nettrash.me")
        assertThat(p).isInstanceOf(ScanPayload.Url::class.java)
        assertThat((p as ScanPayload.Url).url).isEqualTo("https://nettrash.me")
    }

    @Test fun nonURLFallsBackToText() {
        val p = ScanPayloadParser.parse("not a url, just text")
        assertThat(p).isInstanceOf(ScanPayload.Text::class.java)
        assertThat((p as ScanPayload.Text).text).isEqualTo("not a url, just text")
    }

    // ---- Wi-Fi ------------------------------------------------------------

    @Test fun parsesWifi() {
        val p = ScanPayloadParser.parse("WIFI:T:WPA;S:HomeNet;P:supersecret;H:false;;")
        assertThat(p).isInstanceOf(ScanPayload.Wifi::class.java)
        val w = p as ScanPayload.Wifi
        assertThat(w.ssid).isEqualTo("HomeNet")
        assertThat(w.password).isEqualTo("supersecret")
        assertThat(w.security).isEqualTo("WPA")
        assertThat(w.hidden).isFalse()
    }

    @Test fun parsesWifiWithEscapedSemicolon() {
        val p = ScanPayloadParser.parse("""WIFI:T:WPA;S:Cafe;P:p\;ass;;""")
        assertThat(p).isInstanceOf(ScanPayload.Wifi::class.java)
        assertThat((p as ScanPayload.Wifi).password).isEqualTo("p;ass")
    }

    // ---- mailto / tel / sms ----------------------------------------------

    @Test fun parsesMailto() {
        val p = ScanPayloadParser.parse("mailto:hi@example.com?subject=Hello&body=World")
        assertThat(p).isInstanceOf(ScanPayload.Email::class.java)
        val e = p as ScanPayload.Email
        assertThat(e.address).isEqualTo("hi@example.com")
        assertThat(e.subject).isEqualTo("Hello")
        assertThat(e.body).isEqualTo("World")
    }

    @Test fun parsesTel() {
        val p = ScanPayloadParser.parse("tel:+15551234567")
        assertThat(p).isInstanceOf(ScanPayload.Phone::class.java)
        assertThat((p as ScanPayload.Phone).number).isEqualTo("+15551234567")
    }

    @Test fun parsesSMSTo() {
        val p = ScanPayloadParser.parse("smsto:+15551234567:Howdy")
        assertThat(p).isInstanceOf(ScanPayload.Sms::class.java)
        val s = p as ScanPayload.Sms
        assertThat(s.number).isEqualTo("+15551234567")
        assertThat(s.body).isEqualTo("Howdy")
    }

    // ---- geo --------------------------------------------------------------

    @Test fun parsesGeo() {
        val p = ScanPayloadParser.parse("geo:37.3349,-122.0090?q=Apple+Park")
        assertThat(p).isInstanceOf(ScanPayload.Geo::class.java)
        val g = p as ScanPayload.Geo
        assertThat(g.latitude).isWithin(0.0001).of(37.3349)
        assertThat(g.longitude).isWithin(0.0001).of(-122.0090)
        // The iOS test asserts `Apple+Park` (no space conversion) because URLComponents
        // doesn't decode `+`. Java's URLDecoder converts `+` to ` `, so we expect that.
        assertThat(g.query).isEqualTo("Apple Park")
    }

    // ---- vCard / MECARD ---------------------------------------------------

    @Test fun parsesMECard() {
        val p = ScanPayloadParser.parse("MECARD:N:Doe,Jane;TEL:+15551234567;EMAIL:jane@example.com;;")
        assertThat(p).isInstanceOf(ScanPayload.Contact::class.java)
        val c = (p as ScanPayload.Contact).payload
        assertThat(c.fullName).isEqualTo("Jane Doe")
        assertThat(c.phones).containsExactly("+15551234567")
        assertThat(c.emails).containsExactly("jane@example.com")
    }

    @Test fun parsesVCard() {
        val v = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Jane Doe
            TEL;TYPE=CELL:+15551234567
            EMAIL:jane@example.com
            END:VCARD
        """.trimIndent()
        val p = ScanPayloadParser.parse(v)
        assertThat(p).isInstanceOf(ScanPayload.Contact::class.java)
        val c = (p as ScanPayload.Contact).payload
        assertThat(c.fullName).isEqualTo("Jane Doe")
        assertThat(c.phones).containsExactly("+15551234567")
        assertThat(c.emails).containsExactly("jane@example.com")
    }

    // ---- Product codes ----------------------------------------------------

    @Test fun ean13ProductCode() {
        val p = ScanPayloadParser.parse("4006381333931", symbology = Symbology.EAN13)
        assertThat(p).isInstanceOf(ScanPayload.ProductCode::class.java)
        val pc = p as ScanPayload.ProductCode
        assertThat(pc.code).isEqualTo("4006381333931")
        assertThat(pc.system).isEqualTo("EAN-13")
    }

    @Test fun kindLabels() {
        assertThat(ScanPayload.Text("hi").kindLabel).isEqualTo("Text")
        assertThat(ScanPayload.Url("https://x").kindLabel).isEqualTo("URL")
        assertThat(ScanPayload.ProductCode("123", "EAN-13").kindLabel).isEqualTo("Product")
    }

    // ---- Detection priority ----------------------------------------------

    @Test fun epcIsRecognisedBeforeGenericText() {
        val raw = "BCD\n001\n1\nSCT\n\nAcme\nDE12345\nEUR1.00\n\n\nNote\n"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isNotInstanceOf(ScanPayload.Text::class.java)
    }
}
