package me.nettrash.scan.scanner

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import me.nettrash.scan.generator.CodeComposer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Mirrors the iOS `BulkScanSession` semantics — dedupe by value, sighting
 * counts, the capacity cap, index-based deletion, and the CSV shape the
 * History importer already knows.
 */
@RunWith(RobolectricTestRunner::class)
class BulkScanSessionTest {

    private fun code(value: String, symbology: Symbology = Symbology.QR, ts: Long = 1_700_000_000_000) =
        ScannedCode(value = value, symbology = symbology, timestampMillis = ts)

    private fun running(): BulkScanSession = BulkScanSession().apply { start() }

    @Test fun firstInsertIsNewWithOneSighting() {
        val session = running()
        assertThat(session.ingest(code("https://nettrash.me"))).isTrue()
        assertThat(session.count).isEqualTo(1)
        assertThat(session.entriesSnapshot().single().sightings).isEqualTo(1)
    }

    @Test fun repeatIsNotNewAndBumpsSightingsOnly() {
        val session = running()
        session.ingest(code("A"))
        assertThat(session.ingest(code("A"))).isFalse()
        assertThat(session.count).isEqualTo(1)
        assertThat(session.entriesSnapshot().single().sightings).isEqualTo(2)
    }

    @Test fun twoDistinctCodesCountSeparately() {
        val session = running()
        assertThat(session.ingest(code("A"))).isTrue()
        assertThat(session.ingest(code("B"))).isTrue()
        assertThat(session.count).isEqualTo(2)
        assertThat(session.entriesSnapshot().map { it.value }).containsExactly("A", "B").inOrder()
        assertThat(session.entriesSnapshot().map { it.sightings }).containsExactly(1, 1).inOrder()
    }

    @Test fun ingestIsIgnoredWhileStopped() {
        val session = BulkScanSession()
        assertThat(session.ingest(code("A"))).isFalse()
        assertThat(session.isEmpty).isTrue()
    }

    @Test fun ingestIgnoresEmptyValues() {
        val session = running()
        assertThat(session.ingest(code(""))).isFalse()
        assertThat(session.isEmpty).isTrue()
    }

    @Test fun csvTextHasHistoryShapeAndQuotesCommaFields() {
        val session = running()
        session.ingest(code("HELLO,WORLD", symbology = Symbology.CODE128, ts = 1_700_000_000_000))
        session.ingest(code("say \"hi\"", symbology = Symbology.QR, ts = 1_700_000_000_000))

        val csv = session.csvText()
        assertThat(csv).startsWith("value,symbology,kind,sightings,first_seen\n")
        // A comma forces quoting; an embedded quote is doubled and the
        // whole field wrapped — the same escape the History export uses.
        assertThat(csv).contains("\"HELLO,WORLD\",Code 128,")
        assertThat(csv).contains("\"say \"\"hi\"\"\"")
        // Sightings then the shared ISO-8601 rendering; neither needs quotes.
        assertThat(csv).contains(",1,2023-11-14T22:13:20.000Z")
    }

    @Test fun csvLinesKeepAMultiLineValueInOneQuotedRow() {
        val session = running()
        val card = "BEGIN:VCARD\nVERSION:3.0\nFN:Jo\nEND:VCARD"
        session.ingest(code(card, ts = 1_700_000_000_000))
        session.ingest(code("plain", ts = 1_700_000_000_000))

        val lines = session.csvLines()
        // Header + one row per entry — the vCard's line breaks stay inside
        // its quoted field instead of becoming rows of their own.
        assertThat(lines).hasSize(3)
        assertThat(lines[0]).isEqualTo("value,symbology,kind,sightings,first_seen")
        assertThat(lines[1]).startsWith("\"BEGIN:VCARD\nVERSION:3.0\nFN:Jo\nEND:VCARD\",QR,")
        assertThat(lines[2]).startsWith("plain,QR,")
        assertThat(session.csvText()).isEqualTo(lines.joinToString("\n"))
    }

    @Test fun removeAtDropsEntryAndFreesItsDedupeSlot() {
        val session = running()
        session.ingest(code("A"))
        session.ingest(code("B"))
        session.ingest(code("A"))

        session.remove(at = 0)

        assertThat(session.count).isEqualTo(1)
        assertThat(session.entriesSnapshot().single().value).isEqualTo("B")
        // The removed value is no longer deduped, so re-scanning it counts
        // as genuinely new again.
        assertThat(session.ingest(code("A"))).isTrue()
        assertThat(session.count).isEqualTo(2)
        // And the survivor kept its own sighting count rather than borrowing
        // the deleted row's position.
        assertThat(session.entriesSnapshot().first { it.value == "B" }.sightings).isEqualTo(1)
    }

    @Test fun removeEntryDropsItByIndex() {
        val session = running()
        session.ingest(code("A"))
        session.ingest(code("B"))
        session.ingest(code("C"))
        val target = session.entriesSnapshot().first { it.value == "B" }

        session.remove(target)

        assertThat(session.entriesSnapshot().map { it.value }).containsExactly("A", "C").inOrder()
        // Freed slot, so the same value is new again.
        assertThat(session.ingest(code("B"))).isTrue()
    }

    @Test fun capacityCapStopsAcceptingAndReportsFull() {
        val session = running()
        var added = 0
        for (i in 0 until BulkScanSession.CAPACITY + 20) {
            if (session.ingest(code("code-$i"))) added++
        }

        assertThat(added).isEqualTo(BulkScanSession.CAPACITY)
        assertThat(session.isFull).isTrue()
        assertThat(session.count).isEqualTo(BulkScanSession.CAPACITY)
        // Still counts sightings on codes already in the list at the cap.
        assertThat(session.ingest(code("code-0"))).isFalse()
        assertThat(session.entriesSnapshot().first().sightings).isEqualTo(2)
    }

    @Test fun resetClearsEntriesAndDedupeState() {
        val session = running()
        session.ingest(code("A"))
        session.reset()
        assertThat(session.isEmpty).isTrue()
        assertThat(session.ingest(code("A"))).isTrue()
    }

    @Test fun startResumesTheKeptCapture() {
        val session = running()
        session.ingest(code("A"))
        session.stop()
        session.start()
        // Entering Bulk again resumes the same capture; only Clear empties it.
        assertThat(session.entriesSnapshot().map { it.value }).containsExactly("A")
        assertThat(session.isRunning).isTrue()
    }

    // iOS `ScannerQuickEntryTests`, same inputs.

    @Test fun listSurvivesLeavingAndReenteringBulk() {
        val session = running()
        assertThat(session.ingest(code("A"))).isTrue()
        assertThat(session.ingest(code("B"))).isTrue()
        assertThat(session.ingest(code("A"))).isFalse()

        // Codes, Live Text, another tab or the background: paused, kept.
        session.stop()
        assertThat(session.isRunning).isFalse()
        assertThat(session.ingest(code("C"))).isFalse()
        assertThat(session.entriesSnapshot().map { it.value }).containsExactly("A", "B").inOrder()

        // Back in Bulk: the same list, and the counter shows it.
        session.start()
        assertThat(session.isRunning).isTrue()
        assertThat(session.count).isEqualTo(2)
        assertThat(session.entriesSnapshot().map { it.sightings }).containsExactly(2, 1).inOrder()
        assertThat(session.ingest(code("B"))).isFalse()
        assertThat(session.ingest(code("C"))).isTrue()
        assertThat(session.entriesSnapshot().map { it.value }).containsExactly("A", "B", "C").inOrder()
        assertThat(session.entriesSnapshot().map { it.sightings }).containsExactly(2, 2, 1).inOrder()
    }

    // "Save all to History" saves each entry once — the iOS rule, same inputs.

    @Test fun saveAllTakesOnlyTheEntriesNotSavedYet() {
        val session = running()
        session.ingest(code("A"))
        session.ingest(code("B"))
        assertThat(session.hasUnsavedEntries).isTrue()

        assertThat(session.takeUnsaved().map { it.value }).containsExactly("A", "B").inOrder()
        assertThat(session.entriesSnapshot().map { it.isSaved }).containsExactly(true, true)
        // Every entry saved: nothing left to save, a second tap writes nothing.
        assertThat(session.hasUnsavedEntries).isFalse()
        assertThat(session.takeUnsaved()).isEmpty()

        // More sightings keep an entry saved.
        session.ingest(code("A"))
        assertThat(session.entriesSnapshot().first().sightings).isEqualTo(2)
        assertThat(session.entriesSnapshot().first().isSaved).isTrue()
        assertThat(session.hasUnsavedEntries).isFalse()

        // A code collected afterwards is the only one the next save takes.
        session.ingest(code("C"))
        assertThat(session.hasUnsavedEntries).isTrue()
        assertThat(session.takeUnsaved().map { it.value }).containsExactly("C")
        assertThat(session.hasUnsavedEntries).isFalse()
    }

    @Test fun anEmptyListHasNothingToSave() {
        val session = running()
        assertThat(session.hasUnsavedEntries).isFalse()
        assertThat(session.takeUnsaved()).isEmpty()
    }

    @Test fun clearForgetsWhatWasSaved() {
        val session = running()
        session.ingest(code("A"))
        session.takeUnsaved()
        session.reset()
        // A fresh capture: the same code is new, and unsaved, again.
        session.ingest(code("A"))
        assertThat(session.hasUnsavedEntries).isTrue()
        assertThat(session.takeUnsaved().map { it.value }).containsExactly("A")
    }

    // The bulk sheet keys its exported CSV on csvRevision — iOS's same steps.
    @Test fun csvRevisionFollowsWhatTheCsvShows() {
        val session = running()
        var revision = session.csvRevision
        var csv = session.csvLines()
        fun assertCsvChanged(why: String) {
            assertWithMessage(why).that(session.csvRevision).isNotEqualTo(revision)
            assertWithMessage(why).that(session.csvLines()).isNotEqualTo(csv)
            revision = session.csvRevision
            csv = session.csvLines()
        }
        fun assertCsvUnchanged(why: String) {
            assertWithMessage(why).that(session.csvRevision).isEqualTo(revision)
            assertWithMessage(why).that(session.csvLines()).isEqualTo(csv)
        }

        session.ingest(code("A"))
        assertCsvChanged("a new code")
        session.ingest(code("A"))
        assertCsvChanged("another sighting: the count of entries is the same, the sightings column is not")
        session.ingest(code("B"))
        assertCsvChanged("a second code")
        session.takeUnsaved()
        assertCsvUnchanged("the CSV doesn't show what is saved, so an exported file stays good")
        session.stop()
        session.ingest(code("A"))
        assertCsvUnchanged("a paused capture changes nothing")
        session.start()
        session.remove(at = 0)
        assertCsvChanged("a swipe-delete")
        session.remove(session.entriesSnapshot().first())
        assertCsvChanged("a removal by entry")
        session.ingest(code("C"))
        assertCsvChanged("a new code")
        session.reset()
        assertCsvChanged("Clear")
    }

    // CSV cells are neutralised against formula injection, as in History.

    @Test fun csvNeutralisesFormulaValues() {
        val session = running()
        session.ingest(code("=HYPERLINK(\"https://evil.example\")", ts = 1_700_000_000_000))
        session.ingest(code("@cmd", ts = 1_700_000_000_000))

        val lines = session.csvLines()
        assertThat(lines[1]).isEqualTo("\"'=HYPERLINK(\"\"https://evil.example\"\")\",QR,Text,1,2023-11-14T22:13:20.000Z")
        assertThat(lines[2]).isEqualTo("'@cmd,QR,Text,1,2023-11-14T22:13:20.000Z")
    }

    // R8-02: a CRLF value — Scan's own generated vCard — stays one quoted field.
    @Test fun csvKeepsACrlfVCardInOneQuotedField() {
        val session = running()
        session.ingest(code(CodeComposer.vCard(fullName = "Ana", phone = "+15551234"), ts = 1_700_000_000_000))

        val lines = session.csvLines()
        assertThat(lines).hasSize(2)
        assertThat(lines[1]).startsWith("\"BEGIN:VCARD\r\n")
        assertThat(lines[1]).endsWith(",QR,Contact,1,2023-11-14T22:13:20.000Z")
    }

    @Test fun onlyClearEmptiesTheList() {
        val session = running()
        session.ingest(code("A"))
        session.start()
        repeat(3) {
            session.stop()
            session.start()
        }
        assertThat(session.entriesSnapshot().map { it.value }).containsExactly("A")

        session.reset()
        assertThat(session.isEmpty).isTrue()
        // Clear empties the list without stopping the capture.
        assertThat(session.isRunning).isTrue()
        assertThat(session.ingest(code("A"))).isTrue()

        session.stop()
        session.reset()
        assertThat(session.isEmpty).isTrue()
        assertThat(session.isRunning).isFalse()
    }
}
