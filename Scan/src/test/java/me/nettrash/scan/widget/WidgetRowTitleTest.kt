package me.nettrash.scan.widget

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import me.nettrash.scan.data.db.ScanRecord
import me.nettrash.scan.data.payload.ScanPayloadParser
import me.nettrash.scan.generator.CodeComposer
import me.nettrash.scan.scanner.Symbology
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A Recent-scans widget row names the kind of code and never prints the
 * value: the home screen, screenshots and the launcher (another app) all see
 * it. Only a link's host and a Wi-Fi network's name are added — never a
 * password, a 2FA seed, a key, a passkey code or anything else in the value.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetRowTitleTest {

    private fun row(value: String, symbology: String = "QR") =
        ScanRecord(value = value, symbology = symbology, timestamp = 1_700_000_000_000)

    private fun title(value: String) = widgetRowTitle(row(value))

    private fun kindOf(value: String) = ScanPayloadParser.parse(value, Symbology.QR).kindLabel

    @Test fun wifiShowsTheNetworkNameNeverThePassword() {
        assertThat(title("WIFI:T:WPA;S:HomeNet;P:hunter2;;")).isEqualTo("Wi-Fi · HomeNet")
        assertThat(title("WIFI:S:CafeGuest;T:WPA;P:latte2024;H:false;;")).isEqualTo("Wi-Fi · CafeGuest")
        assertThat(title("WIFI:T:WPA;S:HomeNet;P:hunter2;;")).doesNotContain("hunter2")
    }

    @Test fun aLinkShowsItsHostOnly() {
        assertThat(title("https://example.com/reset?token=abc123")).isEqualTo("URL · example.com")
        // No user-info, port, path or query — where tokens live.
        assertThat(title("https://user:pa55word@example.com:8443/a/b?code=123#frag")).isEqualTo("URL · example.com")
        // A recognised service link: its kind and host, not the number or message.
        assertThat(title("https://wa.me/15551234567?text=hello")).isEqualTo("WhatsApp · wa.me")
    }

    @Test fun secretsShowTheirKindAlone() {
        val corpus = mapOf(
            "otpauth://totp/Acme:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Acme" to
                listOf("JBSWY3DPEHPK3PXP", "alice", "secret"),
            "otpauth-migration://offline?data=CjEKCkhlbGxvId6tvu8SGEV4YW1wbGU6YWxpY2VAZ29vZ2xlLmNvbRoHRXhhbXBsZSABKAEwAhABGAEgAA%3D%3D" to
                listOf("CjEKCkhl", "data"),
            "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5" to listOf("nsec1", "vl029"),
            // A CTAP 2.2 hybrid-transport sign-in QR (the parser tests' fixture).
            "FIDO:/005640586143991420254210104918118704520152650222346064982042512635050847625585230466405085311340191775070633618515609790059716415128245854177999084978946107096654083332" to
                listOf("0056405861", "FIDO:"),
            "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7\n-----END PRIVATE KEY-----" to
                listOf("MIIEvQ", "BEGIN"),
            "abandon ability able about above absent absorb abstract absurd abuse access accident" to
                listOf("abandon", "accident"),
            "5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ" to listOf("5HueCGU8"),
        )
        for ((value, secrets) in corpus) {
            val shown = title(value)
            assertWithMessage("title of $value").that(shown).isEqualTo(kindOf(value))
            for (secret in secrets) assertWithMessage("title of $value").that(shown).doesNotContain(secret)
            // No run of digits from a passkey code, a seed or a key.
            assertWithMessage("title of $value").that(shown).doesNotContainMatch("[0-9]{4,}")
        }
    }

    @Test fun aMultiLineValueIsOneShortLine() {
        val card = CodeComposer.vCard(fullName = "Ana Lima", phone = "+15551234")
        assertThat(title(card)).isEqualTo("Contact")
        assertThat(title("BEGIN:VCARD\nVERSION:3.0\nFN:Jo\nEND:VCARD")).doesNotContain("\n")
    }

    @Test fun plainTextShowsTheKindNotTheText() {
        assertThat(title("Meet me at the back door, code 4711")).isEqualTo("Text")
    }

    @Test fun aSavedLoyaltyCardFallsBackToItsKind() {
        // Saved with the "Loyalty" symbology label the result sheet uses.
        val shown = widgetRowTitle(row("4006381333931", symbology = "Loyalty"))
        assertThat(shown).doesNotContain("4006381333931")
    }
}
