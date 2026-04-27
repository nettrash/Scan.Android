package me.nettrash.scan.generator

import com.google.common.truth.Truth.assertThat
import me.nettrash.scan.data.payload.ScanPayload
import me.nettrash.scan.data.payload.ScanPayloadParser
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round-trip composer tests. The composer + parser are tested as a pair —
 * if either side drifts, both go red.
 */
@RunWith(RobolectricTestRunner::class)
class CodeComposerTest {

    @Test fun vCardComposerRoundTrip() {
        val composed = CodeComposer.vCard(
            fullName = "Jane Doe",
            phone = "+15551234567",
            email = "jane@example.com",
            organization = "Acme Inc."
        )
        val payload = ScanPayloadParser.parse(composed)
        assertThat(payload).isInstanceOf(ScanPayload.Contact::class.java)
        val c = (payload as ScanPayload.Contact).payload
        assertThat(c.fullName).isEqualTo("Jane Doe")
        assertThat(c.phones).containsExactly("+15551234567")
        assertThat(c.emails).containsExactly("jane@example.com")
        assertThat(c.organization).isEqualTo("Acme Inc.")
    }

    @Test fun wifiComposerRoundTrip() {
        val composed = CodeComposer.wifi(
            ssid = "Home Net",
            password = "p@ss;wo,rd",
            security = CodeComposer.WifiSecurity.WPA,
            hidden = true
        )
        assertThat(composed).startsWith("WIFI:")
        assertThat(composed).endsWith(";;")

        val payload = ScanPayloadParser.parse(composed)
        assertThat(payload).isInstanceOf(ScanPayload.Wifi::class.java)
        val w = payload as ScanPayload.Wifi
        assertThat(w.ssid).isEqualTo("Home Net")
        assertThat(w.password).isEqualTo("p@ss;wo,rd")
        assertThat(w.security).isEqualTo("WPA")
        assertThat(w.hidden).isTrue()
    }

    @Test fun wifiComposerOpenNetworkSkipsPassword() {
        val composed = CodeComposer.wifi(
            ssid = "Cafe",
            password = "ignored",
            security = CodeComposer.WifiSecurity.OPEN,
            hidden = false
        )
        assertThat(composed).doesNotContain(";P:")
    }
}
