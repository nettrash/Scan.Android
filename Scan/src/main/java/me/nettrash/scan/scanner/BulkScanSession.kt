package me.nettrash.scan.scanner

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.nettrash.scan.data.HistoryCsv
import me.nettrash.scan.data.payload.ScanPayloadParser

/**
 * Accumulates codes across frames for Bulk mode. Port of the iOS
 * `BulkScanSession` in `ScanMode.swift`.
 *
 * Dedupe is by *value*, not by geometry: the same code appears in dozens of
 * consecutive frames and a pallet has hundreds of distinct ones. `sightings`
 * tracks how many frames a code was seen in, which is a decent proxy for
 * "was this really scanned, or was it a neighbour's edge" and lets the HUD
 * show progress without pretending to know quantities.
 *
 * The list outlives the mode: leaving Bulk — for Codes or Live Text, another
 * tab, or the background — only pauses the capture ([stop]), and coming back
 * resumes the same list ([start]). [reset], the sheet's Clear, is the one
 * thing that empties it. Memory only, never written to disk: what the user
 * keeps goes through "Save all to History" or a CSV. Each entry remembers
 * whether it already went to History ([Entry.isSaved]), so saving the kept
 * list again adds only the codes collected since — never a second copy.
 *
 * Observability is Compose snapshot state rather than SwiftUI-style
 * `@Published`: [entries] and [isRunning] are plain reads of a
 * `mutableStateOf`, so a Composable that touches them recomposes when the
 * camera ingests a new code, and a JVM test can read them without a
 * coroutine or a running composition.
 *
 * Main-thread only — the CameraX analyzer delivers frames on the main
 * executor, which is the only intended writer.
 */
class BulkScanSession {

    /**
     * One distinct code, as the bulk list shows it. [isSaved] is set once
     * "Save all to History" has written it, and survives further sightings.
     */
    data class Entry(
        val id: Long,
        val value: String,
        val symbology: String,
        val sightings: Int,
        val firstSeenMillis: Long,
        val kindLabel: String,
        val isSaved: Boolean = false,
    )

    private var entries by mutableStateOf(emptyList<Entry>())
    private var running by mutableStateOf(false)
    private var revision by mutableIntStateOf(0)

    /// Value → position in [entries]. Rebuilt whenever the list is
    /// compacted so the positions never go stale.
    private val index = HashMap<String, Int>()
    private var nextId = 0L

    /**
     * Cap so a runaway camera cannot grow the list without bound. The
     * limit is generous for a pallet and low enough that the sheet stays
     * usable; hitting it is reported (via [isFull], and by the "Limit
     * reached" chip in the bulk sheet) rather than silently truncating.
     */
    companion object {
        const val CAPACITY = 500
    }

    /** Snapshot of the collected codes, in the order they were first seen. */
    fun entriesSnapshot(): List<Entry> = entries

    val count: Int get() = entries.size
    val isEmpty: Boolean get() = entries.isEmpty()
    val isFull: Boolean get() = entries.size >= CAPACITY
    val isRunning: Boolean get() = running

    /**
     * True while some entry has not gone to History yet — what enables
     * "Save all to History". False for an empty list and once every entry
     * is saved: the same rule as the iOS bulk sheet.
     */
    val hasUnsavedEntries: Boolean get() = entries.any { !it.isSaved }

    /**
     * Bumped whenever what [csvLines] writes changes — a new code, another
     * sighting, a removal, Clear — and not by saving to History, which the
     * CSV doesn't show. The bulk sheet keys its exported file on it, so a
     * shared CSV never lags the counts on screen. Mirrors iOS
     * `BulkScanSession.csvRevision`.
     */
    val csvRevision: Int get() = revision

    /**
     * The entries "Save all to History" has not written yet, in list order,
     * now marked saved — so a second tap, or reopening the sheet and tapping
     * again, never writes the same code twice. The caller inserts exactly
     * these and reports their count ("N saved to History").
     */
    fun takeUnsaved(): List<Entry> {
        val unsaved = entries.filterNot { it.isSaved }
        if (unsaved.isNotEmpty()) {
            entries = entries.map { if (it.isSaved) it else it.copy(isSaved = true) }
        }
        return unsaved
    }

    /**
     * Puts entries back to unsaved — what a failed "Save all to History"
     * does for the ones it never wrote, so the next save writes them once.
     * Ids no longer in the list (removed meanwhile) are ignored. Saving is
     * not part of the CSV, so the export revision is left alone.
     */
    fun markUnsaved(ids: Set<Long>) {
        if (ids.isEmpty()) return
        entries = entries.map { if (it.isSaved && it.id in ids) it.copy(isSaved = false) else it }
    }

    /** Collects again, carrying on with whatever is already in the list. */
    fun start() {
        if (running) return
        running = true
    }

    /** Stops collecting; the list stays for the next [start]. */
    fun stop() {
        if (!running) return
        running = false
    }

    /** Empties the list — Clear in the bulk sheet. A running capture keeps running. */
    fun reset() {
        entries = emptyList()
        index.clear()
        revision++
    }

    /**
     * Adds a code. Returns true when it was new, so the caller can fire a
     * haptic only on genuine progress rather than on every frame. A repeat
     * bumps [Entry.sightings] (keeping [Entry.isSaved]) and returns false;
     * so does a code that arrives while the session is stopped, empty, or
     * already at [CAPACITY]. A new entry starts unsaved.
     */
    fun ingest(code: ScannedCode): Boolean {
        if (!running || code.value.isEmpty()) return false
        val existing = index[code.value]
        if (existing != null && entries.indices.contains(existing)) {
            val current = entries[existing]
            entries = entries.toMutableList().apply {
                this[existing] = current.copy(sightings = current.sightings + 1)
            }
            revision++
            return false
        }
        if (isFull) return false
        index[code.value] = entries.size
        entries = entries + Entry(
            id = nextId++,
            value = code.value,
            symbology = code.symbology.displayName,
            sightings = 1,
            firstSeenMillis = code.timestampMillis,
            kindLabel = kindLabelFor(code.value, code.symbology),
        )
        revision++
        return true
    }

    /**
     * Delete by row index, as the list's swipe-to-dismiss hands them over.
     * Written out rather than leaning on a view type so the session stays
     * free of an Android dependency and stays testable on its own — the
     * same reason iOS spells out `remove(atOffsets:)` by hand.
     */
    fun remove(at: Int) = remove(listOf(at))

    fun remove(at: Collection<Int>) {
        val doomed = at.toSet()
        entries.filterIndexed { i, _ -> i in doomed }
            .forEach { index.remove(it.value) }
        entries = entries.filterIndexed { i, _ -> i !in doomed }
        reindex()
        revision++
    }

    fun remove(entry: Entry) {
        index.remove(entry.value)
        entries = entries.filterNot { it.id == entry.id }
        reindex()
        revision++
    }

    /**
     * The CSV rows — header first — in the same shape as the History export,
     * so a spreadsheet that already reads one reads both. Header and column
     * order match iOS `BulkScanSession.csvLines()` verbatim. Kept as rows
     * (rather than one string to split later) so a value with an embedded
     * line break stays one quoted field: splitting the joined text on "\n"
     * would cut a multi-line vCard into several broken records.
     */
    fun csvLines(): List<String> = buildList {
        add("value,symbology,kind,sightings,first_seen")
        for (entry in entries) {
            add(
                listOf(
                    HistoryCsv.csvField(entry.value),
                    HistoryCsv.csvField(entry.symbology),
                    HistoryCsv.csvField(entry.kindLabel),
                    entry.sightings.toString(),
                    HistoryCsv.csvField(HistoryCsv.timestampString(entry.firstSeenMillis)),
                ).joinToString(separator = ",")
            )
        }
    }

    /** [csvLines] joined with "\n", mirroring iOS `csvText()`. */
    fun csvText(): String = csvLines().joinToString(separator = "\n")

    /**
     * The positions in [index] shift whenever an earlier row is dropped, so
     * the map is rebuilt from the surviving list instead of being left to
     * point at stale rows.
     */
    private fun reindex() {
        index.clear()
        entries.forEachIndexed { position, entry -> index[entry.value] = position }
    }

    private fun kindLabelFor(value: String, symbology: Symbology): String =
        runCatching { ScanPayloadParser.parse(value, symbology).kindLabel }
            .getOrElse { "Text" }
}
