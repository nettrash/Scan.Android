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
        APPLE_MAPS("Apple Maps")
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
