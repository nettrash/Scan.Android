package me.nettrash.scan.data.payload

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import me.nettrash.scan.scanner.Symbology
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import javax.imageio.ImageIO
import com.google.zxing.Result as Decoded

/**
 * Every image in `test-fixtures/` decodes to the payload kind its README
 * promises: read off the PNG with ZXing, then classified by the parser the
 * scanner uses. The iOS `testEveryFixtureImageIsTheKindItsREADMESays` reads
 * the same README table, so a fixture that stops matching it — as
 * `43-emvco-pix.png` did, with TLV lengths that did not add up — fails on
 * both phones.
 */
@RunWith(RobolectricTestRunner::class)
class FixtureCorpusTest {

    /** Gradle runs unit tests in the module directory, beside the repo's `test-fixtures`. */
    private val directory = File("../test-fixtures")

    /** `| \`NN-name.png\` | payload | kind |` rows; "re-classified to **Location**" names its kind in bold. */
    private fun expectedKinds(): List<Pair<String, String>> =
        File(directory, "README.md").readLines().filter { it.startsWith("| `") }.mapNotNull { line ->
            val cells = line.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (cells.size < 2) return@mapNotNull null
            val last = cells.last()
            val kind = Regex("""\*\*[^*]+\*\*""").find(last)?.value?.replace("**", "") ?: last
            cells[0].trim('`') to kind
        }

    /**
     * ZXing's finder-pattern detector misses one of these clean, generated
     * QR codes (`15-ethereum-uri.png`, which Apple's detectors read), so an
     * image it finds nothing in is read again as the pure, unrotated
     * barcode every fixture is.
     */
    private fun decode(file: File): Decoded {
        val image = ImageIO.read(file)
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))
        return try {
            MultiFormatReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true))
        } catch (_: NotFoundException) {
            MultiFormatReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.PURE_BARCODE to true))
        }
    }

    private fun symbology(format: BarcodeFormat): Symbology = when (format) {
        BarcodeFormat.QR_CODE -> Symbology.QR
        BarcodeFormat.EAN_13 -> Symbology.EAN13
        BarcodeFormat.PDF_417 -> Symbology.PDF417
        else -> Symbology.UNKNOWN
    }

    @Test fun everyFixtureImageIsTheKindItsReadmeSays() {
        val expected = expectedKinds()
        assertThat(expected).hasSize(61)
        for ((file, kind) in expected) {
            val decoded = decode(File(directory, file))
            val payload = ScanPayloadParser.parse(decoded.text, symbology(decoded.barcodeFormat))
            // A README note after the kind — "Merchant QR (sub-row …)" — is allowed.
            assertWithMessage("$file: ${payload.kindLabel}, but the README says $kind")
                .that(payload.kindLabel == kind || kind.startsWith(payload.kindLabel + " ("))
                .isTrue()
            if (file == "43-emvco-pix.png") {
                assertThat(payload).isInstanceOf(ScanPayload.EmvPayment::class.java)
                assertThat((payload as ScanPayload.EmvPayment).payload.labelledFields().map { it.label })
                    .contains("Pix account (26)")
            }
        }
    }
}
