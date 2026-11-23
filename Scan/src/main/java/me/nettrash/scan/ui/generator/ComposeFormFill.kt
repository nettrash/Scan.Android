package me.nettrash.scan.ui.generator

import me.nettrash.scan.data.payload.ComposeKind
import me.nettrash.scan.data.payload.ComposeResult
import me.nettrash.scan.generator.CodeComposer
import java.util.Locale

/**
 * What the Generate tab's "describe the code" box changes on the form, worked
 * out from a [ComposeResult] without touching any Compose state — so the
 * mapping is unit-testable and the screen only has to copy fields across.
 *
 * The vocabulary is the one both engines use (iOS `ComposeResult.swift`):
 *  - Wi-Fi: `ssid`, `password`, `security` (as written, e.g. "WPA3"),
 *    `hidden` ("true");
 *  - contact: `name`, `phone`, `email`, `org`;
 *  - URL / text: no values — the link or the text is in `text`.
 */
internal sealed interface ComposeFormFill {
    data class Text(val text: String) : ComposeFormFill
    data class Url(val url: String) : ComposeFormFill

    /** [security] is null when the request didn't say — the form keeps its own choice. */
    data class Wifi(
        val ssid: String,
        val password: String,
        val security: CodeComposer.WifiSecurity?,
        val hidden: Boolean,
    ) : ComposeFormFill

    data class Contact(
        val name: String,
        val phone: String,
        val email: String,
        val organization: String,
    ) : ComposeFormFill

    companion object {
        /**
         * The fill for [result], or null when it carries nothing the form can
         * use (an engine that answered with an empty shell).
         *
         * The Generate tab builds text, URL, Wi-Fi and contact codes; the other
         * kinds exist so the vocabulary matches iOS and neither engine produces
         * them today — if one ever does, its literal text lands in the Text
         * form rather than being dropped.
         */
        fun from(result: ComposeResult): ComposeFormFill? {
            val text = result.text.trim()
            return when (result.kind) {
                ComposeKind.WIFI -> result.value("ssid")?.let { ssid ->
                    Wifi(
                        ssid = ssid,
                        password = result.value("password").orEmpty(),
                        security = wifiSecurity(result.value("security")),
                        hidden = result.value("hidden")?.lowercase(Locale.ROOT) == "true",
                    )
                }
                ComposeKind.CONTACT -> {
                    val fill = Contact(
                        name = result.value("name").orEmpty(),
                        phone = result.value("phone").orEmpty(),
                        email = result.value("email").orEmpty(),
                        organization = result.value("org").orEmpty(),
                    )
                    fill.takeIf {
                        it.name.isNotEmpty() || it.phone.isNotEmpty() ||
                            it.email.isNotEmpty() || it.organization.isNotEmpty()
                    }
                }
                ComposeKind.URL -> text.takeIf { it.isNotEmpty() }?.let(::Url)
                ComposeKind.TEXT,
                ComposeKind.EMAIL,
                ComposeKind.PHONE,
                ComposeKind.PAYMENT,
                ComposeKind.PRODUCT,
                -> text.takeIf { it.isNotEmpty() }?.let(::Text)
            }
        }

        /**
         * The form's security option for a request's wording — iOS
         * `ComposeResult.wifiSecurityTag`, mapped onto the picker's cases.
         */
        fun wifiSecurity(raw: String?): CodeComposer.WifiSecurity? =
            when (raw?.trim()?.uppercase(Locale.ROOT)) {
                "WPA", "WPA2", "WPA/WPA2", "WPA2/WPA3" -> CodeComposer.WifiSecurity.WPA
                "WPA3", "SAE" -> CodeComposer.WifiSecurity.WPA3
                "WEP" -> CodeComposer.WifiSecurity.WEP
                "NOPASS", "NOPEN", "NONE", "OPEN" -> CodeComposer.WifiSecurity.OPEN
                else -> null
            }
    }
}
