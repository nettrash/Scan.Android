package me.nettrash.scan.ui.deeplink

import android.net.Uri
import android.util.Base64
import me.nettrash.scan.scanner.Symbology

/**
 * Android App Links handler. Mirrors `Scan/Scan/DeepLink.swift` on
 * iOS exactly — same URL shape, same encoding rules.
 *
 * URL shape:
 *
 *      https://nettrash.me/scan/<base64url-payload>[?t=<symbology>]
 *
 * The payload is the raw bytes the QR / barcode would have contained,
 * encoded with the URL-safe base64 variant (`-_`, no `=` padding).
 *
 * The optional `?t=<symbology>` query carries the symbology (matching
 * `Symbology.displayName`, percent-encoded). Without it the parser
 * defaults to [Symbology.UNKNOWN] and falls back to prefix-pattern
 * detection — fine for vanilla Universal Links shared via Mail / Slack,
 * but loses information when the share path *did* know the symbology
 * (e.g. an EAN-13 product code shared from a screenshot, where the
 * decoder told us the symbology). 1.6's share-intake plumbing
 * preserves it via this query.
 */
object DeepLink {

    /** Bundle of decoded fields. `symbology` is `UNKNOWN` for URLs
     *  minted before the `?t=` query was added. */
    data class Payload(val value: String, val symbology: Symbology)

    /** Top-level URI → decoded [Payload], or null if the URI isn't
     *  ours / can't be decoded. */
    fun decode(uri: Uri): Payload? {
        if (uri.host?.lowercase() != "nettrash.me") return null
        val segs = uri.pathSegments ?: return null
        if (segs.size != 2 || !segs[0].equals("scan", ignoreCase = true)) return null
        val value = base64UrlDecode(segs[1]) ?: return null
        // Uri.getQueryParameter does the percent-decoding for us —
        // `?t=Data%20Matrix` lands as `Data Matrix` here, which
        // matches `Symbology.DATA_MATRIX.displayName`.
        val typeRaw = uri.getQueryParameter("t")
        val symbology = typeRaw?.let { Symbology.fromDisplayName(it) } ?: Symbology.UNKNOWN
        return Payload(value, symbology)
    }

    /** URL-safe base64 (`-_`, no padding) → UTF-8 string. */
    private fun base64UrlDecode(s: String): String? = runCatching {
        // android.util.Base64.URL_SAFE handles `-_`; NO_PADDING and
        // NO_WRAP keep us liberal about the padding characters most
        // emitters omit.
        val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        val bytes = Base64.decode(s, flags)
        String(bytes, Charsets.UTF_8)
    }.getOrNull()

    /** Companion encoder — keeps the encode/decode rules in lockstep
     *  with iOS. Pass a non-`UNKNOWN` symbology to round-trip the
     *  type hint via `?t=`; the receiving side uses it to pick the
     *  right parser branch. */
    fun encode(payload: String, symbology: Symbology = Symbology.UNKNOWN): Uri? = runCatching {
        val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        val b64 = Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), flags).trim()
        val builder = Uri.parse("https://nettrash.me/scan/$b64").buildUpon()
        if (symbology != Symbology.UNKNOWN) {
            // appendQueryParameter handles percent-encoding for the
            // spaces in "Data Matrix" / "Code 128" / etc.
            builder.appendQueryParameter("t", symbology.displayName)
        }
        builder.build()
    }.getOrNull()
}
