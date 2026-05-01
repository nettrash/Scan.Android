package me.nettrash.scan.data.payload

import android.net.Uri
import java.util.Locale

/**
 * A URL we recognised as a known service — gives the UI a more useful
 * smart action than a generic "Open in browser". Mirrors the iOS
 * [RichURLPayload] struct shape.
 */
data class RichURLPayload(
    val kind: Kind,
    val url: String,
    val fields: List<LabelledField>
) {
    enum class Kind(val displayName: String) {
        WHATS_APP("WhatsApp"),
        TELEGRAM("Telegram"),
        APPLE_WALLET("Apple Wallet"),
        APP_STORE("App Store"),
        PLAY_STORE("Google Play"),
        YOUTUBE("YouTube"),
        SPOTIFY("Spotify"),
        APPLE_MUSIC("Apple Music"),
        GOOGLE_MAPS("Google Maps"),
        APPLE_MAPS("Apple Maps"),
        /** New in 1.4. Identity-flow URLs — DigiD (NL government
         *  SSO), EUDI Wallet (EU Digital Identity), and the generic
         *  OpenID4VC verifier / issuer endpoints they're built on.
         *  Special-cased so the result sheet can show a security
         *  warning before the user taps "Continue in browser". */
        DIGITAL_IDENTITY("Digital identity"),
    }

    fun labelledFields(): List<LabelledField> {
        val rows = mutableListOf(LabelledField("Service", kind.displayName))
        rows += fields
        rows += LabelledField("URL", url)
        return rows
    }
}

object RichURLParser {

    fun parse(rawUrl: String): RichURLPayload? {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val path = uri.path.orEmpty()

        // ---- WhatsApp click-to-chat ----
        if (host == "wa.me" || host.endsWith(".wa.me")) {
            val phone = path.trim('/')
            val text = uri.getQueryParameterCi("text")
            return RichURLPayload(
                RichURLPayload.Kind.WHATS_APP, rawUrl,
                listOfNotNull(
                    LabelledField("Phone", phone),
                    text?.let { LabelledField("Message", it) }
                )
            )
        }
        if (host == "api.whatsapp.com" || host == "whatsapp.com") {
            val phone = uri.getQueryParameterCi("phone").orEmpty()
            val text = uri.getQueryParameterCi("text")
            return RichURLPayload(
                RichURLPayload.Kind.WHATS_APP, rawUrl,
                listOfNotNull(
                    LabelledField("Phone", phone),
                    text?.let { LabelledField("Message", it) }
                )
            )
        }

        // ---- Telegram ----
        if (host == "t.me" || host == "telegram.me") {
            val target = path.split('/').filter { it.isNotEmpty() }.joinToString("/")
            return RichURLPayload(
                RichURLPayload.Kind.TELEGRAM, rawUrl,
                if (target.isEmpty()) emptyList()
                else listOf(LabelledField("Target", "@$target"))
            )
        }

        // ---- Apple Wallet pkpass ----
        if (path.lowercase(Locale.ROOT).endsWith(".pkpass")) {
            val name = path.split('/').lastOrNull() ?: ""
            return RichURLPayload(
                RichURLPayload.Kind.APPLE_WALLET, rawUrl,
                listOf(LabelledField("Pass file", name))
            )
        }

        // ---- App Store ----
        if (host == "apps.apple.com" || host == "itunes.apple.com") {
            val segs = path.split('/').filter { it.isNotEmpty() }
            val appId = segs.firstOrNull { it.startsWith("id") }?.removePrefix("id")
            return RichURLPayload(
                RichURLPayload.Kind.APP_STORE, rawUrl,
                appId?.let { listOf(LabelledField("App ID", it)) } ?: emptyList()
            )
        }

        // ---- Play Store ----
        if (host == "play.google.com") {
            val pkg = uri.getQueryParameterCi("id")
            return RichURLPayload(
                RichURLPayload.Kind.PLAY_STORE, rawUrl,
                pkg?.let { listOf(LabelledField("Package", it)) } ?: emptyList()
            )
        }

        // ---- YouTube ----
        if (host == "youtu.be" || host.endsWith("youtube.com")) {
            val videoId: String? = when {
                host == "youtu.be" -> path.split('/').firstOrNull { it.isNotEmpty() }
                path.startsWith("/shorts/") ->
                    path.removePrefix("/shorts/").split('/').firstOrNull { it.isNotEmpty() }
                else -> uri.getQueryParameterCi("v")
            }
            return RichURLPayload(
                RichURLPayload.Kind.YOUTUBE, rawUrl,
                videoId?.let { listOf(LabelledField("Video", it)) } ?: emptyList()
            )
        }

        // ---- Spotify ----
        if (host == "open.spotify.com") {
            val segs = path.split('/').filter { it.isNotEmpty() }
            return if (segs.size >= 2) {
                RichURLPayload(
                    RichURLPayload.Kind.SPOTIFY, rawUrl,
                    listOf(
                        LabelledField("Kind", segs[0].replaceFirstChar { it.uppercase() }),
                        LabelledField("ID", segs[1])
                    )
                )
            } else {
                RichURLPayload(RichURLPayload.Kind.SPOTIFY, rawUrl, emptyList())
            }
        }

        // ---- Apple Music ----
        if (host == "music.apple.com") {
            val last = path.split('/').lastOrNull { it.isNotEmpty() }
            return RichURLPayload(
                RichURLPayload.Kind.APPLE_MUSIC, rawUrl,
                last?.let { listOf(LabelledField("ID", it)) } ?: emptyList()
            )
        }

        // ---- Google Maps share ----
        if (host == "maps.google.com" || host == "www.google.com" || host == "google.com") {
            val coords = extractGoogleMapsCoords(uri, path)
            if (coords != null) {
                return RichURLPayload(
                    RichURLPayload.Kind.GOOGLE_MAPS, rawUrl,
                    listOf(
                        LabelledField("Latitude", coords.first.toString()),
                        LabelledField("Longitude", coords.second.toString())
                    )
                )
            }
        }
        if (host == "maps.app.goo.gl") {
            return RichURLPayload(RichURLPayload.Kind.GOOGLE_MAPS, rawUrl, emptyList())
        }

        // ---- Digital identity flows (new in 1.4) ----
        digitalIdentityPayload(rawUrl, uri, host, path)?.let { return it }

        // ---- Apple Maps share ----
        if (host == "maps.apple.com") {
            val ll = uri.getQueryParameterCi("ll")
            if (ll != null) {
                val coords = parseLatLon(ll)
                if (coords != null) {
                    val q = uri.getQueryParameterCi("q")
                    return RichURLPayload(
                        RichURLPayload.Kind.APPLE_MAPS, rawUrl,
                        listOfNotNull(
                            LabelledField("Latitude", coords.first.toString()),
                            LabelledField("Longitude", coords.second.toString()),
                            q?.let { LabelledField("Query", it) }
                        )
                    )
                }
            }
        }

        return null
    }

    /**
     * Recognise URLs that look like DigiD / EUDI Wallet / generic
     * OpenID4VC identity flows. Returns null if nothing matches —
     * the caller falls back to plain `.url` in that case. Mirrors
     * iOS's `digitalIdentityPayload`.
     *
     * Conservative on purpose: we only flag well-known brands plus a
     * path-level signal (`openid-credential-offer`, `openid4vp`,
     * `oidvp`) so a vanilla `https://example.com/login` doesn't get
     * mis-classified and trigger the security warning.
     */
    private fun digitalIdentityPayload(
        rawUrl: String, uri: Uri, host: String, path: String
    ): RichURLPayload? {
        val lowerPath = path.lowercase(Locale.ROOT)
        val isDigiD = host.endsWith("digid.nl") || host == "mijn.digid.nl"
        val isEUDI = host.endsWith("eudiw.dev")
            || host.endsWith("eu-digital-identity-wallet.eu")
            || (host.endsWith("ec.europa.eu") && lowerPath.contains("eudi"))
        val isOpenID4VC = lowerPath.contains("openid-credential-offer")
            || lowerPath.contains("openid4vp")
            || lowerPath.contains("/oidvp/")
            || (lowerPath.contains("authorize")
                && (uri.getQueryParameterCi("response_type") == "vp_token"
                    || uri.getQueryParameterCi("client_id_scheme") != null))
        if (!isDigiD && !isEUDI && !isOpenID4VC) return null

        val fields = mutableListOf<LabelledField>()
        fields += when {
            isDigiD -> LabelledField("Provider", "DigiD (Netherlands)")
            isEUDI  -> LabelledField("Provider", "EU Digital Identity Wallet")
            else    -> LabelledField("Protocol", "OpenID for Verifiable Credentials")
        }
        fields += LabelledField("Host", host)
        for (key in listOf("client_id", "response_type", "scope",
                           "request_uri", "presentation_definition_uri")) {
            uri.getQueryParameterCi(key)?.let { fields += LabelledField(key, it) }
        }
        return RichURLPayload(RichURLPayload.Kind.DIGITAL_IDENTITY, rawUrl, fields)
    }

    private fun parseLatLon(s: String): Pair<Double, Double>? {
        val parts = s.split(',').map { it.trim() }
        if (parts.size < 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        return lat to lon
    }

    private fun extractGoogleMapsCoords(uri: Uri, path: String): Pair<Double, Double>? {
        val ll = uri.getQueryParameterCi("ll")
        if (ll != null) parseLatLon(ll)?.let { return it }
        val atIdx = path.indexOf('@')
        if (atIdx >= 0) {
            val tail = path.substring(atIdx + 1)
            val chunks = tail.split(',')
            if (chunks.size >= 2) {
                val lat = chunks[0].toDoubleOrNull() ?: return null
                val lonRaw = chunks[1].split('z').firstOrNull() ?: chunks[1]
                val lon = lonRaw.toDoubleOrNull() ?: return null
                return lat to lon
            }
        }
        return null
    }

    private fun Uri.getQueryParameterCi(name: String): String? {
        for (key in queryParameterNames) {
            if (key.equals(name, ignoreCase = true)) return getQueryParameter(key)
        }
        return null
    }
}
