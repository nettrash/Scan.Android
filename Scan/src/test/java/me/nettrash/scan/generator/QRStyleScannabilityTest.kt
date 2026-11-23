package me.nettrash.scan.generator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.math.abs
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Proof that what the studio renders is still a code a reader can read: every
 * preset, with and without a logo, over payloads from a short URL to ~300
 * characters, rendered at the app's own module size and decoded by ZXing's QR
 * reader. The only codes allowed not to decode are the dense ones the
 * generator already warns about ([QRStyle.isHardToScan]): dots past their
 * thresholds, and the other shaped modules with a logo from version 12.
 * ZXing is the lenient reader here, the only one on the JVM; iOS's
 * QRStudioTests also holds the same matrix to Apple's much stricter Vision,
 * the reader those thresholds were measured with.
 *
 * Alongside the decode, the two structural rules a reader relies on before it
 * reads a single bit: a quiet zone of at least 4 modules of pure background
 * (ISO/IEC 18004) and finder patterns that still read 1:1:3:1:1 through their
 * centre, horizontally and vertically, whatever the eye shape.
 *
 * Native graphics are required: Robolectric's legacy mode no-ops `Canvas`.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class QRStyleScannabilityTest {

    private val moduleSize = QRStyledRenderer.DEFAULT_MODULE_SIZE

    private val payloads: Map<String, String> = linkedMapOf(
        "short URL" to "https://nettrash.me/scan",
        "Wi-Fi" to CodeComposer.wifi(
            ssid = "Guest Network",
            password = "correct horse battery staple",
            security = CodeComposer.WifiSecurity.WPA,
        ),
        "vCard" to CodeComposer.vCard(
            fullName = "Jane Doe",
            phone = "+1 555 123 4567",
            email = "jane.doe@example.com",
            organization = "ACME Corporation",
            url = "https://example.com/jane",
        ),
        "300-char text" to "The quick brown fox jumps over the lazy dog. ".repeat(7).take(300),
    )

    /**
     * Artwork with real contrast in it — a dark tile, a light disc, a coloured
     * core, 3:2 — so the reader sees module-sized ink where data was cleared,
     * the way a real logo would put it there.
     */
    private val logo: Bitmap by lazy {
        val bitmap = createBitmap(90, 60)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF1A1A2E.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(45f, 30f, 24f, paint)
        paint.color = 0xFFE94560.toInt()
        canvas.drawCircle(45f, 30f, 12f, paint)
        bitmap
    }

    @Test fun everyPresetDecodesWithAndWithoutALogo() {
        val failures = mutableListOf<String>()
        val exempt = mutableListOf<String>()
        var decoded = 0

        for (preset in QRStyle.presets) {
            for (withLogo in listOf(false, true)) {
                val style = if (withLogo) preset.style.copy(logo = logo) else preset.style
                for ((name, content) in payloads) {
                    val label = "${preset.name}${if (withLogo) " + logo" else ""}, $name"
                    // Encoded the way the generator encodes it: the style raises
                    // the error correction the user picked (Medium by default).
                    val matrix = CodeGenerator.styledMatrix(content, style, QRErrorCorrection.MEDIUM)
                    if (matrix == null) {
                        failures += "$label: ZXing refused to encode"
                        continue
                    }
                    val side = QRGeometry.symbolArea(matrix)!!.width.toInt()
                    val bitmap = QRStyledRenderer.bitmap(matrix, style, moduleSize)
                    if (bitmap == null) {
                        failures += "$label: rendered nothing"
                        continue
                    }
                    val margin = style.quietZone.coerceAtLeast(4)
                    failures += quietZoneDefects(bitmap, style, margin).map { "$label: $it" }
                    failures += finderDefects(bitmap, style, side, margin).map { "$label: $it" }

                    val text = decode(bitmap)
                    when {
                        style.isHardToScan(side) -> exempt += "$label ($side modules): " +
                            if (text == content) "decoded anyway" else "not decoded"
                        text == content -> decoded++
                        else -> failures += "$label ($side modules): decoded ${text?.take(40)}"
                    }
                }
            }
        }

        println("Studio scannability: $decoded decoded; exempt dense codes: $exempt")
        assertThat(failures).isEmpty()
        // The exemption must stay the exception, not the rule: every case but
        // the dense ones the generator warns about has to read — the dense dots,
        // and Soft, Nature and Ink with the logo on the vCard and the
        // 300-character text. iOS's QRStudioTests holds the same bound.
        assertThat(exempt.size).isAtMost(12)
        assertThat(decoded).isEqualTo(QRStyle.presets.size * 2 * payloads.size - exempt.size)
    }

    @Test fun thePlainOutputStillDecodes() {
        // Classic without a logo never reaches the studio: the generator keeps
        // its original output, ZXing's one-module border included, byte for
        // byte. It must read too.
        val failures = mutableListOf<String>()
        for ((name, content) in payloads) {
            val bitmap = CodeGenerator.bitmap(content, GeneratableSymbology.QR, scale = moduleSize)
            if (bitmap == null || decode(bitmap) != content) failures += name
        }
        assertThat(failures).isEmpty()
    }

    @Test fun theDensityThresholdsAreWhereTheWarningSaysTheyAre() {
        // The exemptions above are real codes past the thresholds, not a
        // loophole: dots past theirs, the other shaped presets only with a logo
        // and only from version 12 (65 modules), and Classic never.
        for (preset in QRStyle.presets) {
            val name = preset.name
            val dots = preset.style.moduleShape == QRModuleShape.DOT
            val shaped = preset.style.moduleShape != QRModuleShape.SQUARE
            val withLogo = preset.style.copy(logo = logo)
            assertWithMessage(name).that(preset.style.isHardToScan(177)).isEqualTo(dots)
            assertWithMessage(name).that(preset.style.isHardToScan(QRStyle.DENSE_DOT_SYMBOL_SIDE - 4)).isFalse()
            assertWithMessage(name).that(withLogo.isHardToScan(QRStyle.DENSE_DOT_WITH_LOGO_SYMBOL_SIDE))
                .isEqualTo(dots)
            assertWithMessage(name).that(withLogo.isHardToScan(QRStyle.NON_SQUARE_WITH_LOGO_SYMBOL_SIDE - 4))
                .isEqualTo(dots)
            assertWithMessage(name).that(withLogo.isHardToScan(QRStyle.NON_SQUARE_WITH_LOGO_SYMBOL_SIDE))
                .isEqualTo(shaped)
            assertWithMessage(name).that(withLogo.isHardToScan(177)).isEqualTo(shaped)
        }
    }

    // MARK: - Helpers

    private fun decode(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        return try {
            QRCodeReader().decode(
                BinaryBitmap(HybridBinarizer(source)),
                mapOf(DecodeHintType.TRY_HARDER to true),
            ).text
        } catch (_: ReaderException) {
            null
        }
    }

    /** Pixels in the outer [margin]-module band that are not the background. */
    private fun quietZoneDefects(bitmap: Bitmap, style: QRStyle, margin: Int): List<String> {
        if (margin < 4) return listOf("quiet zone is $margin modules")
        val band = margin * moduleSize
        var stray = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val inBand = x < band || y < band || x >= bitmap.width - band || y >= bitmap.height - band
                if (inBand && bitmap.getPixel(x, y) != style.backgroundArgb) stray++
            }
        }
        return if (stray == 0) emptyList() else listOf("$stray non-background pixels in the quiet zone")
    }

    /**
     * The dark/light runs through the centre of each finder eye — one module of
     * separator either side included — must be 1:1:3:1:1 between the two
     * light separator modules, to within a quarter of a module, both across
     * and down.
     */
    private fun finderDefects(bitmap: Bitmap, style: QRStyle, side: Int, margin: Int): List<String> {
        val threshold = (luma(style.backgroundArgb) + style.inks.maxOf { luma(it) }) / 2
        fun dark(x: Int, y: Int) = luma(bitmap.getPixel(x, y)) < threshold
        val expected = listOf(false to 1f, true to 1f, false to 1f, true to 3f, false to 1f, true to 1f, false to 1f)
        val defects = mutableListOf<String>()
        for (eye in QRGeometry.eyeRects(side, side)) {
            val left = (margin + eye.x.toInt()) * moduleSize
            val top = (margin + eye.y.toInt()) * moduleSize
            val centreX = left + 7 * moduleSize / 2
            val centreY = top + 7 * moduleSize / 2
            val span = (left - moduleSize) until (left + 8 * moduleSize)
            val spanDown = (top - moduleSize) until (top + 8 * moduleSize)
            val across = runs(span.map { dark(it, centreY) })
            val down = runs(spanDown.map { dark(centreX, it) })
            for ((direction, measured) in listOf("across" to across, "down" to down)) {
                val ok = measured.size == expected.size && measured.zip(expected).all { (got, want) ->
                    got.first == want.first && abs(got.second - want.second) <= 0.25f
                }
                if (!ok) defects += "${style.eyeShape} eye at ${eye.x.toInt()},${eye.y.toInt()} $direction reads $measured"
            }
        }
        return defects
    }

    /** Run-length encode a line of dark/light pixels, lengths in modules. */
    private fun runs(line: List<Boolean>): List<Pair<Boolean, Float>> {
        val out = mutableListOf<Pair<Boolean, Float>>()
        var start = 0
        for (i in 1..line.size) {
            if (i == line.size || line[i] != line[start]) {
                out += line[start] to (i - start).toFloat() / moduleSize
                start = i
            }
        }
        return out
    }

    private fun luma(argb: Int): Double =
        0.299 * Color.red(argb) + 0.587 * Color.green(argb) + 0.114 * Color.blue(argb)
}
