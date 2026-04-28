package me.nettrash.scan.data.payload

import java.net.URLDecoder
import java.util.Locale

/**
 * Parsed BitTorrent magnet URI (`magnet:?xt=urn:btih:…`).
 *
 * Mirrors the iOS [MagnetPayload] struct. Surfaces info-hash, display
 * name, trackers and exact-length so each can be tap-copied; the raw URI
 * is what we pass to the OS for "Open in torrent client".
 */
data class MagnetPayload(
    val infoHash: String?,
    val displayName: String?,
    val trackers: List<String>,
    val exactLength: Long?,
    val raw: String
) {
    fun labelledFields(): List<LabelledField> {
        val rows = mutableListOf<LabelledField>()
        displayName?.takeIf { it.isNotEmpty() }?.let { rows += LabelledField("Name", it) }
        infoHash?.takeIf { it.isNotEmpty() }?.let { rows += LabelledField("Info hash", it) }
        exactLength?.let { rows += LabelledField("Size", formatBytes(it)) }
        if (trackers.isNotEmpty()) {
            rows += LabelledField(
                if (trackers.size == 1) "Tracker" else "Trackers",
                trackers.joinToString("\n")
            )
        }
        return rows
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        var b = bytes.toDouble()
        val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB")
        var unit = "B"
        for (u in units) {
            b /= 1024.0
            unit = u
            if (b < 1024) break
        }
        return String.format(Locale.US, "%.2f %s", b, unit)
    }
}

object MagnetURIParser {

    fun looksLikeMagnet(s: String): Boolean =
        s.lowercase(Locale.ROOT).startsWith("magnet:?")

    fun parse(raw: String): MagnetPayload? {
        if (!looksLikeMagnet(raw)) return null
        // Magnet URIs are *opaque* (no `//` after scheme), so Android's
        // `Uri.parse(...)` returns an opaque Uri whose `queryParameterNames`
        // / `getQueryParameters` throw UnsupportedOperationException at
        // runtime. Robolectric is more permissive so unit tests pass — but
        // the real device crashes. Parse the query string ourselves.
        val q = raw.substringAfter("magnet:?", missingDelimiterValue = "")
        if (q.isEmpty()) return null

        var infoHash: String? = null
        var name: String? = null
        val trackers = mutableListOf<String>()
        var exactLength: Long? = null

        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val rawKey: String
            val rawValue: String
            if (eq < 0) {
                rawKey = pair
                rawValue = ""
            } else {
                rawKey = pair.substring(0, eq)
                rawValue = pair.substring(eq + 1)
            }
            val key = decode(rawKey).lowercase(Locale.ROOT)
            val value = decode(rawValue)
            when (key) {
                "xt" -> {
                    val idx = value.indexOf("btih:")
                    if (idx >= 0) infoHash = value.substring(idx + "btih:".length)
                }
                "dn" -> name = value
                "tr" -> trackers += value
                "xl" -> exactLength = value.toLongOrNull()
            }
        }

        if (infoHash.isNullOrEmpty() && name.isNullOrEmpty()) return null
        return MagnetPayload(infoHash, name, trackers, exactLength, raw)
    }

    /**
     * Tolerant URL-decode. Magnet `tr=` values are typically already
     * URL-encoded; bad encoding shouldn't take down the parser.
     */
    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrElse { s }
}
