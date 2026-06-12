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

    // ---- New code types (1.9) — mirrors iOS ScanTests --------------------

    @Test fun parsesWalletConnectV2() {
        val raw = "wc:7f6e504bfad60b485450578e05678ed3e8e8c4751d3c6160be17160d63ec90f9@2?relay-protocol=irn&symKey=587d5484ce2a2a6ee3ba1962fdd7e8588e06200c46823bd18fbd67def96ad303"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.WalletConnect::class.java)
        val wc = (p as ScanPayload.WalletConnect).payload
        assertThat(wc.version).isEqualTo("2")
        assertThat(wc.relayProtocol).isEqualTo("irn")
        assertThat(wc.hasKey).isTrue()
    }

    @Test fun parsesNostrUriAndBareNsec() {
        val p = ScanPayloadParser.parse("nostr:npub1sn0wdenkukak0d9dfczzeacvhkrgz92ak56egt7vdgzn8pv2wfqqhrjdv9")
        assertThat(p).isInstanceOf(ScanPayload.Nostr::class.java)
        assertThat((p as ScanPayload.Nostr).payload.entity)
            .isEqualTo(NostrPayload.Entity.PROFILE)

        val nsec = ScanPayloadParser.parse("nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5")
        assertThat((nsec as ScanPayload.Nostr).payload.isPrivateKey).isTrue()
    }

    @Test fun parsesGoogleAuthenticatorExport() {
        val raw = "otpauth-migration://offline?data=CjUKCkhlbGxvId6tvu8SGEV4YW1wbGU6YWxpY2VAZ29vZ2xlLmNvbRoHRXhhbXBsZSABKAEwAhABGAEgACgq"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.OtpMigration::class.java)
        val m = (p as ScanPayload.OtpMigration).payload
        assertThat(m.accounts).hasSize(1)
        assertThat(m.accounts[0].issuer).isEqualTo("Example")
        assertThat(m.accounts[0].name).isEqualTo("Example:alice@google.com")
        assertThat(m.accounts[0].type).isEqualTo("TOTP")
    }

    @Test fun parsesPlusCodeWithLocality() {
        val p = ScanPayloadParser.parse("849VCWC8+R9 Stockholm, Sweden")
        assertThat(p).isInstanceOf(ScanPayload.PlusCode::class.java)
        val pc = (p as ScanPayload.PlusCode).payload
        assertThat(pc.code).isEqualTo("849VCWC8+R9")
        assertThat(pc.locality).isEqualTo("Stockholm, Sweden")
    }

    @Test fun parsesWhat3Words() {
        val p = ScanPayloadParser.parse("///filled.count.soap")
        assertThat(p).isInstanceOf(ScanPayload.What3Words::class.java)
        assertThat((p as ScanPayload.What3Words).payload.words).isEqualTo("filled.count.soap")
    }

    @Test fun parsesValidIBANAndRejectsBadChecksum() {
        val p = ScanPayloadParser.parse("GB82WEST12345698765432")
        assertThat(p).isInstanceOf(ScanPayload.Iban::class.java)
        assertThat((p as ScanPayload.Iban).payload.formatted).isEqualTo("GB82 WEST 1234 5698 7654 32")

        assertThat(ScanPayloadParser.parse("GB00WEST12345698765432"))
            .isNotInstanceOf(ScanPayload.Iban::class.java)
    }

    @Test fun parsesPaymentHandlesAndMessaging() {
        assertThat((ScanPayloadParser.parse("https://paypal.me/alice") as ScanPayload.RichUrl).payload.kind)
            .isEqualTo(RichURLPayload.Kind.PAY_PAL)
        assertThat((ScanPayloadParser.parse("https://cash.app/\$alice") as ScanPayload.RichUrl).payload.kind)
            .isEqualTo(RichURLPayload.Kind.CASH_APP)
        assertThat((ScanPayloadParser.parse("alipays://platformapi/startapp?saId=10000007") as ScanPayload.RichUrl).payload.kind)
            .isEqualTo(RichURLPayload.Kind.ALI_PAY)
        assertThat((ScanPayloadParser.parse("https://meet.google.com/abc-defg-hij") as ScanPayload.RichUrl).payload.kind)
            .isEqualTo(RichURLPayload.Kind.MEETING)
    }
}
