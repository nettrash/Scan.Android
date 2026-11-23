package me.nettrash.scan.ui.generator

import com.google.common.truth.Truth.assertThat
import me.nettrash.scan.data.payload.ComposeKind
import me.nettrash.scan.data.payload.ComposeResult
import me.nettrash.scan.data.payload.HeuristicExplainer
import me.nettrash.scan.generator.CodeComposer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Generate tab's "describe the code" box, end to end through the
 * built-in engine: a sentence in, the form values out. The engine itself is
 * covered by AssistantTest; this pins the hand-over to the form, which iOS
 * documents in `ComposeResult.swift`.
 */
@RunWith(RobolectricTestRunner::class)
class ComposeFormFillTest {

    private fun result(kind: ComposeKind, values: Map<String, String> = emptyMap(), text: String = "") =
        ComposeResult(kind, values, text, ComposeResult.ComposeSource.BUILT_IN)

    @Test fun wifiSentenceFillsTheWifiForm() {
        val composed = HeuristicExplainer.compose("wifi network BlueHouse password hunter2 hidden")!!
        assertThat(ComposeFormFill.from(composed)).isEqualTo(
            ComposeFormFill.Wifi(ssid = "BlueHouse", password = "hunter2", security = null, hidden = true)
        )
    }

    @Test fun wifiSecurityWordingMapsOntoThePicker() {
        assertThat(ComposeFormFill.wifiSecurity("wpa2")).isEqualTo(CodeComposer.WifiSecurity.WPA)
        assertThat(ComposeFormFill.wifiSecurity("WPA2/WPA3")).isEqualTo(CodeComposer.WifiSecurity.WPA)
        assertThat(ComposeFormFill.wifiSecurity("WPA3")).isEqualTo(CodeComposer.WifiSecurity.WPA3)
        assertThat(ComposeFormFill.wifiSecurity("SAE")).isEqualTo(CodeComposer.WifiSecurity.WPA3)
        assertThat(ComposeFormFill.wifiSecurity("wep")).isEqualTo(CodeComposer.WifiSecurity.WEP)
        assertThat(ComposeFormFill.wifiSecurity("nopass")).isEqualTo(CodeComposer.WifiSecurity.OPEN)
        assertThat(ComposeFormFill.wifiSecurity("open")).isEqualTo(CodeComposer.WifiSecurity.OPEN)
        // Unsaid or unknown keeps the form's own choice.
        assertThat(ComposeFormFill.wifiSecurity(null)).isNull()
        assertThat(ComposeFormFill.wifiSecurity("radius")).isNull()
    }

    @Test fun wifiWithoutAnSsidIsNothingToFill() {
        assertThat(ComposeFormFill.from(result(ComposeKind.WIFI, mapOf("password" to "x")))).isNull()
    }

    @Test fun contactFillsEveryFieldAndClearsTheRest() {
        val fill = ComposeFormFill.from(
            result(ComposeKind.CONTACT, mapOf("name" to "Ana Ivić", "phone" to "+381641234567"))
        )
        assertThat(fill).isEqualTo(
            ComposeFormFill.Contact(name = "Ana Ivić", phone = "+381641234567", email = "", organization = "")
        )
        assertThat(ComposeFormFill.from(result(ComposeKind.CONTACT))).isNull()
    }

    @Test fun linkAndTextCarryTheirLiteral() {
        val url = HeuristicExplainer.compose("url https://example.com/a")!!
        assertThat(ComposeFormFill.from(url)).isEqualTo(ComposeFormFill.Url("https://example.com/a"))
        assertThat(ComposeFormFill.from(result(ComposeKind.TEXT, text = "  hello  ")))
            .isEqualTo(ComposeFormFill.Text("hello"))
        // Kinds the form has no tab for keep their text rather than vanish.
        assertThat(ComposeFormFill.from(result(ComposeKind.PHONE, text = "+44 20 7946 0000")))
            .isEqualTo(ComposeFormFill.Text("+44 20 7946 0000"))
        assertThat(ComposeFormFill.from(result(ComposeKind.URL, text = "  "))).isNull()
    }
}
