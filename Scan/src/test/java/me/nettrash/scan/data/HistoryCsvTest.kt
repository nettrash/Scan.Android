package me.nettrash.scan.data

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import me.nettrash.scan.data.db.ScanRecord
import me.nettrash.scan.generator.CodeComposer
import me.nettrash.scan.scanner.BulkScanSession
import me.nettrash.scan.scanner.ScannedCode
import me.nettrash.scan.scanner.Symbology
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The CSV cell both exports write (History and Bulk). The same inputs as
 * iOS `ScanTests` (`testCSVFieldQuotesByScalarNotCharacter`,
 * `testCSVNeutralisesFormulaCells`, `testBulkCSVKeepsACRLFValueInOneRecord`,
 * `testHistoryCSVQuotesCRLFAndNeutralisesFormulas`), the same bytes out:
 *
 *  - spreadsheet formula injection is neutralised — a cell starting with
 *    `=` `+` `-` `@`, a tab or a CR gets a leading apostrophe, before
 *    quoting, so the apostrophe sits inside the quotes;
 *  - RFC 4180 quoting is decided per character (not per grapheme): CRLF,
 *    and a comma or quote followed by a combining mark, still quote.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryCsvTest {

    private val context = RuntimeEnvironment.getApplication()

    /**
     * Writes an export and drops the share URI. FileProvider caches its roots
     * once per process, and Robolectric gives every test a new data
     * directory, so minting the `content://` URI can fail here; the file is
     * written before that, and the file's bytes are what these tests read.
     */
    private fun exportIgnoringUri(write: () -> Unit) {
        runCatching(write)
    }

    /** RFC 4180 records and fields, as a spreadsheet reads the file back. */
    private fun csvRecords(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                inQuotes && c == '"' -> inQuotes = false
                inQuotes -> field.append(c)
                c == '"' -> inQuotes = true
                c == ',' -> { record.add(field.toString()); field.clear() }
                c == '\r' && text.getOrNull(i + 1) == '\n' -> {
                    i++
                    record.add(field.toString()); field.clear()
                    records.add(record); record = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        return records
    }

    private fun code(value: String) =
        ScannedCode(value = value, symbology = Symbology.QR, timestampMillis = 1_700_000_000_000)

    @Test fun csvFieldQuotesByCharacterNotGrapheme() {
        // "\r\n" is one grapheme, equal to neither "\r" nor "\n"; a comma or
        // a quote followed by a combining mark is one grapheme too.
        assertThat(HistoryCsv.csvField("a\r\nb")).isEqualTo("\"a\r\nb\"")
        assertThat(HistoryCsv.csvField("a,́b")).isEqualTo("\"a,́b\"")
        assertThat(HistoryCsv.csvField("say \"́hi")).isEqualTo("\"say \"\"́hi\"")
        assertThat(HistoryCsv.csvField("a\nb")).isEqualTo("\"a\nb\"")
        assertThat(HistoryCsv.csvField("a\rb")).isEqualTo("\"a\rb\"")
        assertThat(HistoryCsv.csvField("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"")
        assertThat(HistoryCsv.csvField("plain")).isEqualTo("plain")
        assertThat(HistoryCsv.csvField("")).isEqualTo("")
    }

    @Test fun csvNeutralisesFormulaCells() {
        // A leading = + - @ tab or CR gets an apostrophe, before quoting.
        assertThat(HistoryCsv.csvField("=1+2")).isEqualTo("'=1+2")
        assertThat(HistoryCsv.csvField("+15551234")).isEqualTo("'+15551234")
        assertThat(HistoryCsv.csvField("-5")).isEqualTo("'-5")
        assertThat(HistoryCsv.csvField("@SUM(A1:A2)")).isEqualTo("'@SUM(A1:A2)")
        assertThat(HistoryCsv.csvField("\t=1+2")).isEqualTo("'\t=1+2")
        assertThat(HistoryCsv.csvField("\r=1+2")).isEqualTo("\"'\r=1+2\"")
        assertWithMessage("a combining mark doesn't hide it")
            .that(HistoryCsv.csvField("=́1")).isEqualTo("'=́1")
        // OWASP's example, and a link a spreadsheet would make clickable.
        assertThat(HistoryCsv.csvField("=1+2\";=1+2")).isEqualTo("\"'=1+2\"\";=1+2\"")
        assertThat(HistoryCsv.csvField("=HYPERLINK(\"https://evil.example\",\"Open\")"))
            .isEqualTo("\"'=HYPERLINK(\"\"https://evil.example\"\",\"\"Open\"\")\"")
        // Only the first character counts, and only those six: a line feed
        // just forces quoting.
        assertThat(HistoryCsv.csvField("a=b")).isEqualTo("a=b")
        assertThat(HistoryCsv.csvField(" =1+2")).isEqualTo(" =1+2")
        assertThat(HistoryCsv.csvField("\n=1+2")).isEqualTo("\"\n=1+2\"")
        assertThat(HistoryCsv.csvField("1-2")).isEqualTo("1-2")
        // A leading CRLF is a CR first; a trigger inside a quoted field too.
        assertThat(HistoryCsv.csvField("\r\nx")).isEqualTo("\"'\r\nx\"")
        assertThat(HistoryCsv.csvField("-1,5")).isEqualTo("\"'-1,5\"")

        // The Bulk export goes through the same escape.
        val session = BulkScanSession().apply { start() }
        session.ingest(code("=cmd|' /C calc'!A0"))
        val lines = session.csvLines()
        assertThat(lines).hasSize(2)
        assertThat(lines[1]).startsWith("'=cmd|' /C calc'!A0,QR,")
        assertThat(lines[1]).endsWith(",1,2023-11-14T22:13:20.000Z")
    }

    @Test fun bulkCsvKeepsACrlfValueInOneRecord() {
        // Scan's own generator writes vCards with CRLF, as RFC 6350 does; EPC
        // payment codes may use CRLF too.
        val vCard = CodeComposer.vCard(fullName = "Ana", phone = "+15551234")
        assertThat(vCard).contains("\r\n")
        val epc = "BCD\r\n002\r\n1\r\nSCT\r\nBFSWDE33BER\r\nWikimedia Foerdergesellschaft\r\n" +
            "DE33100205000001194700\r\nEUR123.45\r\n\r\n\r\nSpende"
        val session = BulkScanSession().apply { start() }
        session.ingest(code(vCard))
        session.ingest(code(epc))
        val lines = session.csvLines()
        assertThat(lines).hasSize(3)
        assertThat(lines[1]).startsWith("\"BEGIN:VCARD\r\n")
        assertThat(lines[1]).endsWith("END:VCARD\",QR,Contact,1,2023-11-14T22:13:20.000Z")
        assertThat(lines[2]).startsWith("\"BCD\r\n002\r\n")

        // The shared file: one CRLF outside quotes per record, and the values
        // read back as scanned.
        exportIgnoringUri { HistoryCsv.writeBulk(context, lines) }
        val written = File(context.cacheDir, "shared/Scan-bulk-capture.csv").readText(Charsets.UTF_8)
        val records = csvRecords(written)
        assertWithMessage("the header and one record per entry").that(records).hasSize(session.count + 1)
        assertThat(records.map { it.size }).containsExactly(5, 5, 5)
        assertThat(records.drop(1).map { it[0] }).containsExactly(vCard, epc).inOrder()
    }

    @Test fun historyCsvQuotesCrlfAndNeutralisesFormulas() {
        val vCard = CodeComposer.vCard(fullName = "Ana", phone = "+15551234")
        val formula = "=HYPERLINK(\"https://evil.example\",\"Open\")"
        val rows = listOf(vCard to "Met at \"Expo\"", formula to "@team").map { (value, notes) ->
            ScanRecord(value = value, symbology = "QR", timestamp = 1_700_000_000_000, notes = notes)
        }
        exportIgnoringUri { HistoryCsv.write(context, rows) }
        val written = File(context.cacheDir, "shared/Scan-history.csv").readText(Charsets.UTF_8)
        assertThat(written).isEqualTo(
            "timestamp,symbology,value,notes,favourite\r\n" +
                "2023-11-14T22:13:20.000Z,QR,\"$vCard\",\"Met at \"\"Expo\"\"\",0\r\n" +
                "2023-11-14T22:13:20.000Z,QR,\"'=HYPERLINK(\"\"https://evil.example\"\",\"\"Open\"\")\",'@team,0\r\n"
        )
        val records = csvRecords(written)
        assertWithMessage("the header and one five-field record per row")
            .that(records.map { it.size }).containsExactly(5, 5, 5)
        assertThat(records[1][2]).isEqualTo(vCard)
        assertThat(records[2][2]).isEqualTo("'$formula")
    }

    @Test fun historyExportNeutralisesEveryCellItWrites() {
        exportIgnoringUri {
            HistoryCsv.write(
                context,
                listOf(
                    ScanRecord(
                        value = "=cmd|' /C calc'!A0", symbology = "QR",
                        timestamp = 1_700_000_000_000, notes = "@note", isFavorite = true,
                    ),
                    ScanRecord(value = "plain", symbology = "EAN-13", timestamp = 1_700_000_000_000),
                ),
            )
        }
        assertThat(File(context.cacheDir, "shared/Scan-history.csv").readText(Charsets.UTF_8)).isEqualTo(
            "timestamp,symbology,value,notes,favourite\r\n" +
                "2023-11-14T22:13:20.000Z,QR,'=cmd|' /C calc'!A0,'@note,1\r\n" +
                "2023-11-14T22:13:20.000Z,EAN-13,plain,,0\r\n"
        )
    }
}
