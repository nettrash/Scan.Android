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
 *
 * Every cell is also neutralised against spreadsheet formula injection
 * (see [csvField]): a scanned value is attacker-controlled text, and a
 * spreadsheet would otherwise run a cell such as `=HYPERLINK(…)` as a
 * formula. Both platforms write identical bytes for identical input.
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

    /// Bulk-capture export. Takes the already-rendered rows from
    /// `BulkScanSession.csvLines()` (header first) and writes them with
    /// the same CRLF / quoting rules as the history export, so a
    /// spreadsheet that reads one reads both. Mirrors iOS
    /// `HistoryCSV.writeBulk(lines:)`.
    fun writeBulk(context: Context, lines: List<String>): android.net.Uri {
        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val target = File(sharedDir, "Scan-bulk-capture.csv")

        target.bufferedWriter(Charsets.UTF_8).use { w ->
            for (line in lines) {
                w.append(line).append("\r\n")
            }
        }

        return FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            target,
        )
    }

    /// One CSV cell, exactly as both exports write it: formula
    /// neutralisation, then RFC 4180 quoting. Exposed for the
    /// bulk-capture export in `BulkScanSession`, so both exports
    /// escape identically. Mirrors iOS `HistoryCSV.csvField`.
    fun csvField(s: String): String = csvEscape(s)

    /// Shared timestamp rendering (ISO 8601 with fractional seconds).
    /// Mirrors iOS `HistoryCSV.timestampString`.
    fun timestampString(millis: Long): String = isoFormatter.format(Date(millis))

    /// First characters that make a spreadsheet treat a cell as a
    /// formula (OWASP's CSV-injection list): `=` `+` `-` `@`, tab, CR.
    private val formulaTriggers = charArrayOf('=', '+', '-', '@', '\t', '\r')

    /// Formula neutralisation, then RFC 4180 escape. A cell whose first
    /// character is a formula trigger gets a leading apostrophe, which
    /// spreadsheets read as "this is text". The check is on the first
    /// UTF-16 unit — for these ASCII triggers, the first Unicode scalar —
    /// so "\r\n…" and a trigger followed by a combining mark are caught
    /// too; the iOS escape must test the first scalar, not the first
    /// grapheme, to write the same bytes. The apostrophe goes on before
    /// quoting, so it ends up inside the quotes. Quoting only when we
    /// have to saves bytes and keeps the output friendly for naïve
    /// consumers; embedded double-quotes are doubled, then the whole
    /// field wrapped.
    private fun csvEscape(s: String): String {
        val cell = if (s.isNotEmpty() && s[0] in formulaTriggers) "'$s" else s
        val needsQuoting = cell.any { it == '"' || it == ',' || it == '\r' || it == '\n' }
        if (!needsQuoting) return cell
        return "\"" + cell.replace("\"", "\"\"") + "\""
    }
}
