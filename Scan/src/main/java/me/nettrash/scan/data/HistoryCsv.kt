package me.nettrash.scan.data

import android.content.Context
import androidx.core.content.FileProvider
import me.nettrash.scan.data.db.ScanRecord
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Render a list of [ScanRecord]s as CSV and stash the result in the
 * app-local cache directory. Returns a `content://` URI minted via
 * [FileProvider] so the share-sheet target can read the file without
 * needing storage permissions on the consumer side.
 *
 * Mirrors the iOS `HistoryCSV.swift` writer column-for-column:
 * `timestamp,symbology,value,notes,favourite` with RFC 4180 quoting
 * and CRLF line endings. Power users who flip between the iOS export
 * and the Android export get an identical schema.
 */
object HistoryCsv {

    /// Spreadsheet-friendly ISO-8601 with milliseconds + offset.
    /// Matches `ISO8601DateFormatter.formatOptions = .withFractionalSeconds`
    /// on iOS — both apps export the same string for the same instant.
    private val isoFormatter: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    private const val HEADER = "timestamp,symbology,value,notes,favourite"

    fun write(context: Context, records: List<ScanRecord>): android.net.Uri {
        // Cache dir is automatically swept by Android so we don't have
        // to clean up old exports ourselves. We re-use a stable filename
        // — a fresh share always overwrites — so the share-sheet target
        // sees the friendly name "Scan-history.csv".
        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val target = File(sharedDir, "Scan-history.csv")

        target.bufferedWriter(Charsets.UTF_8).use { w ->
            w.append(HEADER).append("\r\n")
            for (r in records) {
                val cells = listOf(
                    isoFormatter.format(Date(r.timestamp)),
                    r.symbology,
                    r.value,
                    r.notes ?: "",
                    if (r.isFavorite) "1" else "0",
                ).map(::csvEscape)
                w.append(cells.joinToString(separator = ",")).append("\r\n")
            }
        }

        return FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            target,
        )
    }

    /// RFC 4180 escape: only quote when we have to (saves bytes and
    /// keeps the output friendly for naïve consumers). Embedded
    /// double-quotes are doubled, then the whole field wrapped.
    private fun csvEscape(s: String): String {
        val needsQuoting = s.any { it == '"' || it == ',' || it == '\r' || it == '\n' }
        if (!needsQuoting) return s
        return "\"" + s.replace("\"", "\"\"") + "\""
    }
}
