package me.nettrash.scan.generator

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.util.Base64
import androidx.core.graphics.createBitmap
import com.google.common.truth.Truth.assertThat
import com.google.zxing.common.BitMatrix
import java.io.File
import java.io.OutputStream
import kotlin.math.abs
import me.nettrash.scan.data.CodeSvg
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The QR art studio: geometry, the styled renderer, and the vector export.
 *
 * The invariant that matters is that a styled code is still *the same code*
 * as the plain one — same modules, same places, same count — so these tests
 * compare the renderer against the raw ZXing [BitMatrix] and against
 * [CodeGenerator.bitmap] rather than against hand-drawn pictures.
 *
 * Native graphics are required: the styled renderer paints with `Canvas`,
 * which Robolectric's legacy mode silently no-ops.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class QRStyleTest {

    private val content = "https://nettrash.me/scan"
    private val moduleSize = 8
    private val margin = 4

    private val matrix: BitMatrix by lazy {
        CodeGenerator.matrix(content, GeneratableSymbology.QR, QRErrorCorrection.HIGH)
            ?: error("ZXing refused to encode $content")
    }

    private val area: QRRect by lazy {
        QRGeometry.symbolArea(matrix) ?: error("matrix carries no ink")
    }

    // MARK: - Geometry

    @Test fun symbolAreaCoversExactlyTheInkedModules() {
        val outsideArea = mutableListOf<String>()
        var inkedInMatrix = 0
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (!matrix[x, y]) continue
                if (!area.contains(x.toFloat(), y.toFloat())) {
                    outsideArea += "module $x,$y lies outside $area"
                }
                inkedInMatrix++
            }
        }
        assertThat(inkedInMatrix).isGreaterThan(0)
        assertThat(outsideArea).isEmpty()

        var inkedInArea = 0
        for (y in 0 until area.height.toInt()) {
            for (x in 0 until area.width.toInt()) {
                if (matrix[area.x.toInt() + x, area.y.toInt() + y]) inkedInArea++
            }
        }
        assertThat(inkedInArea).isEqualTo(inkedInMatrix)
        assertThat(area.width).isEqualTo(area.height)
    }

    @Test fun eyeRectsSitOnTheThreeCorners() {
        val side = area.width.toInt()
        val eyes = QRGeometry.eyeRects(side, side)

        assertThat(eyes).hasSize(3)
        assertThat(eyes[0]).isEqualTo(QRRect(0f, 0f, 7f, 7f))
        assertThat(eyes[1]).isEqualTo(QRRect((side - 7).toFloat(), 0f, 7f, 7f))
        assertThat(eyes[2]).isEqualTo(QRRect(0f, (side - 7).toFloat(), 7f, 7f))

        assertThat(QRGeometry.isInEye(0, 0, eyes)).isTrue()
        assertThat(QRGeometry.isInEye(3, 3, eyes)).isTrue()
        assertThat(QRGeometry.isInEye(11, 11, eyes)).isFalse()
    }

    @Test fun everyEyeShapeKeepsTheThreeConcentricPieces() {
        // The 7-5-3 ratio is what a reader locks onto, whatever the shape:
        // outer ring, background gap, ink centre.
        val wrong = mutableListOf<String>()
        for (shape in QREyeShape.values()) {
            val pieces = QRGeometry.eyeShapes(shape, QRRect(0f, 0f, 7f, 7f))
            if (pieces.size != 3) {
                wrong += "$shape has ${pieces.size} pieces, not 3"
                continue
            }

            val outer = QRGeometry.bounds(pieces[0])
            val gap = QRGeometry.bounds(pieces[1])
            val centre = QRGeometry.bounds(pieces[2])
            if (outer != QRRect(0f, 0f, 7f, 7f)) wrong += "$shape outer is $outer"
            if (gap != QRRect(1f, 1f, 5f, 5f)) wrong += "$shape gap is $gap"
            if (centre != QRRect(2f, 2f, 3f, 3f)) wrong += "$shape centre is $centre"
        }
        assertThat(wrong).isEmpty()
    }

    // MARK: - Quiet zone

    @Test fun quietZoneTrimmingActuallyTrims() {
        // ZXing wraps the symbol in its own quiet-zone border, so the raw
        // matrix is strictly bigger than the symbol we draw.
        assertThat(area.width).isLessThan(matrix.width.toFloat())
        assertThat(matrix.width - area.width.toInt()).isAtLeast(2)
        for (x in 0 until matrix.width) assertThat(matrix[x, 0]).isFalse()
        for (y in 0 until matrix.height) assertThat(matrix[0, y]).isFalse()

        // The renderer re-adds its own clamped margin around the *trimmed*
        // symbol — it does not reuse the matrix's border.
        val bitmap = QRStyledRenderer.bitmap(matrix, softStyle(), moduleSize)!!
        assertThat(bitmap.width).isEqualTo((area.width.toInt() + margin * 2) * moduleSize)
        assertThat(bitmap.width).isNotEqualTo(matrix.width * moduleSize)
    }

    @Test fun quietZoneIsClampedNeverAsked() {
        val bitmap = QRStyledRenderer.bitmap(matrix, QRStyle(quietZone = 0), moduleSize)!!
        assertThat(bitmap.width).isEqualTo((area.width.toInt() + margin * 2) * moduleSize)
    }

    // MARK: - Renderer

    @Test fun everyPresetRendersAtTheRequestedSize() {
        val expected = (area.width.toInt() + margin * 2) * moduleSize
        val wrong = mutableListOf<String>()

        for (preset in QRStyle.presets) {
            val bitmap = QRStyledRenderer.bitmap(matrix, preset.style, moduleSize)
            if (bitmap == null) {
                wrong += "${preset.name} rendered nothing"
                continue
            }
            if (bitmap.width != expected || bitmap.height != expected) {
                wrong += "${preset.name} is ${bitmap.width}x${bitmap.height}, expected $expected"
            }
            if (bitmap.config != Bitmap.Config.ARGB_8888) {
                wrong += "${preset.name} configured ${bitmap.config}"
            }
        }
        assertThat(wrong).isEmpty()
    }

    @Test fun everyPresetInkClearsTheContrastFloor() {
        // Applying a preset must never raise the generator's own 3:1
        // warning — the reason Nature and Sunset use the increased-contrast
        // system green / orange, as on iOS.
        val wrong = mutableListOf<String>()
        for (preset in QRStyle.presets) {
            for (ink in preset.style.inks) {
                val ratio = CodeGenerator.contrastRatio(ink, preset.style.backgroundArgb)
                if (ratio < 3.0) wrong += "${preset.name} ink ${Integer.toHexString(ink)} is ${"%.2f".format(ratio)}:1"
            }
        }
        assertThat(wrong).isEmpty()
    }

    @Test fun hardToScanThresholdsMatchIOS() {
        // Which codes the generator warns about, by module shape, logo and
        // symbol side ("X" = hard to scan), for every eye shape, flat or
        // gradient. iOS's QRStudioTests.testHardToScanThresholdsMatchAndroid
        // pins the same table, so both ports warn on the same codes.
        assertThat(QRStyle.DENSE_DOT_SYMBOL_SIDE).isEqualTo(81)
        assertThat(QRStyle.DENSE_DOT_WITH_LOGO_SYMBOL_SIDE).isEqualTo(57)
        assertThat(QRStyle.NON_SQUARE_WITH_LOGO_SYMBOL_SIDE).isEqualTo(65)
        val sides = listOf(21, 53, 57, 61, 65, 77, 81, 177)
        val table = listOf(
            Triple(QRModuleShape.SQUARE, "........", "........"),
            Triple(QRModuleShape.ROUNDED, "........", "....XXXX"),
            Triple(QRModuleShape.DOT, "......XX", "..XXXXXX"),
            Triple(QRModuleShape.LEAF, "........", "....XXXX"),
            Triple(QRModuleShape.BARS, "........", "....XXXX"),
        )
        fun row(style: QRStyle) = sides.joinToString("") { if (style.isHardToScan(it)) "X" else "." }
        val logo = solidBitmap(Color.RED, 4, 4)
        val wrong = mutableListOf<String>()
        for ((shape, plain, withLogo) in table) {
            for (eye in QREyeShape.entries) {
                for (inks in listOf(listOf(Color.BLACK), listOf(Color.BLACK, Color.BLUE))) {
                    val style = QRStyle(moduleShape = shape, eyeShape = eye, inks = inks)
                    val label = "$shape modules, $eye eyes, ${inks.size} ink(s)"
                    if (row(style) != plain) wrong += "$label: ${row(style)}"
                    val logoRow = row(style.copy(logo = logo))
                    if (logoRow != withLogo) wrong += "$label, logo: $logoRow"
                }
            }
        }
        assertThat(wrong).isEmpty()
    }

    @Test fun scannabilityWarningsMatchIOS() {
        // The words under the preview, pinned here and in iOS's
        // QRStudioTests.testScannabilityWarningsMatchAndroid. With a logo the
        // advice narrows to square modules once every shaped module is past its
        // threshold.
        val dots = "Dots get hard for cameras to read on a code this dense. " +
            "Square, rounded, leaf or bar modules scan more reliably."
        val dotsWithLogo = "Dots get hard for cameras to read on a code this dense with a logo. " +
            "Square, rounded, leaf or bar modules — or no logo — scan more reliably."
        val shaped = "Shaped modules with a logo get hard for cameras to read on a code this dense. " +
            "Square modules — or no logo — scan more reliably."
        val logo = solidBitmap(Color.RED, 4, 4)
        val dotStyle = QRStyle(moduleShape = QRModuleShape.DOT)
        assertThat(dotStyle.scannabilityWarning(77)).isNull()
        assertThat(dotStyle.scannabilityWarning(81)).isEqualTo(dots)
        val dotsLogo = dotStyle.copy(logo = logo)
        assertThat(dotsLogo.scannabilityWarning(53)).isNull()
        assertThat(dotsLogo.scannabilityWarning(57)).isEqualTo(dotsWithLogo)
        assertThat(dotsLogo.scannabilityWarning(61)).isEqualTo(dotsWithLogo)
        assertThat(dotsLogo.scannabilityWarning(65)).isEqualTo(shaped)
        assertThat(dotsLogo.scannabilityWarning(177)).isEqualTo(shaped)
        for (shape in listOf(QRModuleShape.ROUNDED, QRModuleShape.LEAF, QRModuleShape.BARS)) {
            val shapedLogo = QRStyle(moduleShape = shape, logo = logo)
            assertThat(shapedLogo.scannabilityWarning(61)).isNull()
            assertThat(shapedLogo.scannabilityWarning(65)).isEqualTo(shaped)
            assertThat(shapedLogo.copy(logo = null).scannabilityWarning(177)).isNull()
        }
        // Classic with a logo never warns; every other preset with one does.
        for (preset in QRStyle.presets) {
            val styled = preset.style.copy(logo = logo)
            assertThat(styled.scannabilityWarning(61))
                .isEqualTo(if (preset.style.moduleShape == QRModuleShape.DOT) dotsWithLogo else null)
            assertThat(styled.scannabilityWarning(65)).isEqualTo(if (preset.id == "classic") null else shaped)
        }
    }

    @Test fun onlyClassicIsAPlainStyle() {
        assertThat(QRStyle.standard.isPlain).isTrue()
        assertThat(QRStyle.preset("classic")!!.style.isPlain).isTrue()
        val wronglyPlain = QRStyle.presets
            .filterNot { it.id == "classic" }
            .filter { it.style.isPlain }
            .map { it.name }
        assertThat(wronglyPlain).isEmpty()
    }

    @Test fun unstyledStyleMatchesTheUnstyledModulePattern() {
        val scale = 6
        val plain = CodeGenerator.bitmap(
            content = content,
            symbology = GeneratableSymbology.QR,
            scale = scale,
            errorCorrection = QRErrorCorrection.HIGH,
        )!!
        val styled = QRStyledRenderer.bitmap(matrix, QRStyle.standard, scale)!!
        val mismatches = mutableListOf<String>()

        for (y in 0 until area.height.toInt()) {
            for (x in 0 until area.width.toInt()) {
                val styledPixel = styled.getPixel(
                    (margin + x) * scale + scale / 2,
                    (margin + y) * scale + scale / 2,
                )
                val plainPixel = plain.getPixel(
                    (area.x.toInt() + x) * scale + scale / 2,
                    (area.y.toInt() + y) * scale + scale / 2,
                )
                if (styledPixel != plainPixel) {
                    mismatches += "module $x,$y styled=$styledPixel plain=$plainPixel"
                }
            }
        }
        assertThat(mismatches).isEmpty()
    }

    @Test fun gradientStyleYieldsMoreThanOneInkColour() {
        val flatColours = moduleColours(
            QRStyledRenderer.bitmap(matrix, QRStyle.standard, moduleSize)!!,
        )
        assertThat(flatColours).containsExactly(Color.WHITE, Color.BLACK)

        val gradientColours = moduleColours(
            QRStyledRenderer.bitmap(matrix, gradientStyle(), moduleSize)!!,
        )
        // The quiet zone is still clean background...
        assertThat(gradientColours).contains(Color.WHITE)
        // ...and the ink is more than one colour: a wash from black to blue,
        // sampled at module centres, is far more than the two declared inks.
        val inks = gradientColours - Color.WHITE
        assertThat(inks.size).isGreaterThan(1)
        assertThat(inks.size).isGreaterThan(flatColours.size - 1)
        inks.forEach { ink ->
            assertThat(Color.blue(ink)).isAtLeast(Color.red(ink))
        }
    }

    @Test fun gradientIsClippedToTheShapesNotPaintedBehindThem() {
        val styled = QRStyledRenderer.bitmap(matrix, gradientStyle(), moduleSize)!!

        // An "off" module in the middle of the symbol must still be pure
        // background; a gradient painted behind the modules would tint it.
        val tinted = mutableListOf<String>()
        var checkedOffModules = 0
        for (y in 1 until area.height.toInt() - 1) {
            for (x in 1 until area.width.toInt() - 1) {
                if (matrix[area.x.toInt() + x, area.y.toInt() + y]) continue
                val pixel = styled.getPixel(
                    (margin + x) * moduleSize + moduleSize / 2,
                    (margin + y) * moduleSize + moduleSize / 2,
                )
                if (pixel != Color.WHITE) tinted += "off module $x,$y is $pixel"
                checkedOffModules++
            }
        }
        assertThat(checkedOffModules).isGreaterThan(0)
        assertThat(tinted).isEmpty()
    }

    // MARK: - Error correction

    @Test fun stylesThatCostContrastRaiseErrorCorrection() {
        assertThat(QRStyle.standard.minimumErrorCorrection).isEqualTo(QRErrorCorrection.MEDIUM)
        assertThat(QRStyle(moduleShape = QRModuleShape.ROUNDED).minimumErrorCorrection)
            .isEqualTo(QRErrorCorrection.HIGH)
        assertThat(QRStyle(eyeShape = QREyeShape.RING).minimumErrorCorrection)
            .isEqualTo(QRErrorCorrection.HIGH)

        // The user's pick is silently raised, never lowered.
        assertThat(CodeGenerator.effectiveErrorCorrection(QRErrorCorrection.LOW, softStyle()))
            .isEqualTo(QRErrorCorrection.HIGH)
        assertThat(CodeGenerator.effectiveErrorCorrection(QRErrorCorrection.QUARTILE, softStyle()))
            .isEqualTo(QRErrorCorrection.HIGH)
        assertThat(CodeGenerator.effectiveErrorCorrection(QRErrorCorrection.HIGH, softStyle()))
            .isEqualTo(QRErrorCorrection.HIGH)
        assertThat(CodeGenerator.effectiveErrorCorrection(QRErrorCorrection.LOW, QRStyle.standard))
            .isEqualTo(QRErrorCorrection.LOW)
    }

    @Test fun styledMatrixUsesTheRaisedLevel() {
        // A style that needs High gets High even when the user asked for Low,
        // so the matrix is bit-for-bit the one High produces.
        val raised = CodeGenerator.styledMatrix(content, softStyle(), QRErrorCorrection.LOW)!!
        val high = CodeGenerator.matrix(content, GeneratableSymbology.QR, QRErrorCorrection.HIGH)!!
        val low = CodeGenerator.matrix(content, GeneratableSymbology.QR, QRErrorCorrection.LOW)!!

        assertThat(raised.width).isEqualTo(high.width)
        assertThat(raised.height).isEqualTo(high.height)
        assertThat(bitsOf(raised)).isEqualTo(bitsOf(high))
        assertThat(bitsOf(raised)).isNotEqualTo(bitsOf(low))
    }

    @Test fun previewExplainsWhyItHasNoCode() {
        // A preview that cannot draw says why instead of asking for fields that
        // are already filled in. The same fixtures as iOS's
        // QRStudioTests.testPreviewExplainsWhyItHasNoCode: the lengths sit on the
        // same side of High's and Medium's capacity in both ports' encoders
        // (ZXing, whose UTF-8 ECI header costs 12 bits: 1272 and 2330 bytes;
        // Core Image: 1273 and 2331).
        fun a(length: Int) = "a".repeat(length)
        // What GeneratorScreen draws: the studio for a styled QR, the plain
        // generator otherwise.
        fun preview(
            content: String,
            style: QRStyle,
            symbology: GeneratableSymbology = GeneratableSymbology.QR,
            picked: QRErrorCorrection = QRErrorCorrection.MEDIUM,
        ): Bitmap? = if (symbology == GeneratableSymbology.QR && !style.isPlain) {
            CodeGenerator.styledBitmap(content, style, moduleSize = 12, errorCorrection = picked)
        } else {
            CodeGenerator.bitmap(content, symbology, scale = 12, errorCorrection = picked)
        }
        fun reason(
            content: String,
            style: QRStyle,
            symbology: GeneratableSymbology = GeneratableSymbology.QR,
            picked: QRErrorCorrection = QRErrorCorrection.MEDIUM,
        ) = PreviewFailure.reason(content, symbology, style, picked)

        val rounded = QRStyle(moduleShape = QRModuleShape.ROUNDED)
        val ringEyes = QRStyle(eyeShape = QREyeShape.RING)
        val withLogo = QRStyle(logo = solidBitmap(Color.RED, 4, 4))
        val gradient = gradientStyle()

        // A shape, shaped eyes or a logo raise Medium to High, which holds less.
        assertThat(preview(a(1270), rounded)).isNotNull()
        val v40 = CodeGenerator.styledMatrix(a(1270), rounded, QRErrorCorrection.MEDIUM)!!
        assertThat(QRGeometry.symbolArea(v40)!!.width).isEqualTo(177f)
        for (style in listOf(rounded, ringEyes, withLogo)) {
            assertThat(preview(a(1274), style)).isNull()
            assertThat(reason(a(1274), style)).isEqualTo(PreviewFailure.TooLongForStyle)
        }
        // The same content still fits a plain code, and a gradient alone raises
        // nothing.
        assertThat(preview(a(1274), QRStyle.standard)).isNotNull()
        assertThat(preview(a(1274), gradient)).isNotNull()
        // Too long at the user's own level is not the style's doing.
        assertThat(preview(a(1274), rounded, picked = QRErrorCorrection.HIGH)).isNull()
        assertThat(reason(a(1274), rounded, picked = QRErrorCorrection.HIGH))
            .isEqualTo(PreviewFailure.TooLong(GeneratableSymbology.QR))
        assertThat(preview(a(2328), QRStyle.standard)).isNotNull()
        assertThat(preview(a(2332), QRStyle.standard)).isNull()
        assertThat(reason(a(2332), QRStyle.standard)).isEqualTo(PreviewFailure.TooLong(GeneratableSymbology.QR))
        assertThat(preview(a(2332), gradient)).isNull()
        assertThat(reason(a(2332), gradient)).isEqualTo(PreviewFailure.TooLong(GeneratableSymbology.QR))
        // ZXing sets Code 128 no length limit and Core Image one of 4096, so
        // only the decision is shared there, not an encoder fixture.
        assertThat(reason(a(5000), QRStyle.standard, GeneratableSymbology.CODE128))
            .isEqualTo(PreviewFailure.TooLong(GeneratableSymbology.CODE128))
        assertThat(preview("", rounded)).isNull()
        assertThat(reason("", rounded)).isEqualTo(PreviewFailure.Empty)
        assertThat(preview("café", QRStyle.standard, GeneratableSymbology.CODE128)).isNull()
        assertThat(reason("café", QRStyle.standard, GeneratableSymbology.CODE128)).isEqualTo(PreviewFailure.NotAscii)

        // The words, pinned on both ports.
        assertThat(PreviewFailure.Empty.message).isEqualTo("Fill in the fields above to see a preview.")
        assertThat(PreviewFailure.TooLongForStyle.message).isEqualTo(
            "Too long for shaped modules, shaped eyes or a logo — they need High error correction. " +
                "Use square modules and eyes without a logo, or shorten the content.",
        )
        assertThat(PreviewFailure.NotAscii.message)
            .isEqualTo("Code 128 only holds plain ASCII text — no accents, emoji or other scripts.")
        assertThat(GeneratableSymbology.entries.map { PreviewFailure.TooLong(it).message }).containsExactly(
            "This content is too long for a QR code.",
            "This content is too long for an Aztec code.",
            "This content is too long for a PDF417 code.",
            "This content is too long for a Code 128 code.",
        ).inOrder()
    }

    // MARK: - Vector export

    @Test fun defaultStyleExportsPlainRects() {
        val unstyled = CodeSvg.svg(matrix)
        assertThat(unstyled).contains("<rect")
        assertThat(unstyled).doesNotContain("<path")
        assertThat(unstyled).doesNotContain("linearGradient")

        // A *plain* style object must not smuggle shapes into the export.
        assertThat(CodeSvg.svg(matrix, style = QRStyle.standard)).isEqualTo(unstyled)
    }

    @Test fun styledCodeExportsShapesAndGradient() {
        val svg = CodeSvg.svg(matrix, style = gradientStyle())

        assertThat(svg).contains("<path ")
        assertThat(svg).doesNotContain("<rect x=") // no run-length module rects
        assertThat(svg).contains("shape-rendering=\"geometricPrecision\"")
        assertThat(svg).contains("<linearGradient id=\"scan-ink\"")
        assertThat(svg).contains("url(#scan-ink)")
        assertThat(svg).contains("<stop offset=\"0\" stop-color=\"#000000\"/>")
        assertThat(svg).contains("<stop offset=\"1\" stop-color=\"#0000FF\"/>")
        assertThat(svg).contains("<path fill=\"url(#scan-ink)\"")
        assertThat(svg).contains("<path fill=\"#FFFFFF\"")

        // Shapes without a gradient still get shapes, just no <defs>.
        val shaped = CodeSvg.svg(matrix, style = softStyle())
        assertThat(shaped).contains("<path ")
        assertThat(shaped).doesNotContain("linearGradient")
    }

    // MARK: - Logo

    @Test fun logoLayoutClearsTheSameModulesAsIOSForEveryVersion() {
        // Cleared side in modules for versions 1…40: the largest odd number
        // ≤ min(⌊side × 0.22 × 1.12⌋, side − 18). iOS's QRStudioTests pins
        // the same table, so both ports clear the same modules.
        val expected = listOf(
            3, 5, 7, 7, 9, 9, 11, 11, 13, 13, 15, 15, 17, 17, 17, 19, 19, 21, 21, 23,
            23, 25, 25, 27, 27, 29, 29, 31, 31, 33, 33, 35, 35, 37, 37, 39, 39, 41, 41, 43,
        )
        val wrong = mutableListOf<String>()
        for (version in 1..40) {
            val side = 21 + 4 * (version - 1)
            val modules = expected[version - 1]
            val layout = QRGeometry.logoLayout(side)
            val cleared = layout.cleared
            val origin = (side - modules) / 2f
            if (cleared != QRRect(origin, origin, modules.toFloat(), modules.toFloat())) {
                wrong += "v$version clears $cleared, expected $modules modules at $origin"
            }
            // Never into the finders, separators, timing patterns or format
            // information: the first and last nine rows and columns.
            if (cleared.left < 9f || cleared.right > side - 9f) wrong += "v$version reaches the border"
            // About 6 % of the symbol at most.
            if (modules * modules > 0.061 * side * side) wrong += "v$version clears too much"
            // The artwork is the cleared square inset by (w − w / 1.12) / 2.
            val inset = (modules - modules / 1.12f) / 2f
            val artwork = layout.artwork
            if (abs(artwork.x - (origin + inset)) > 1e-4f || abs(artwork.y - (origin + inset)) > 1e-4f ||
                abs(artwork.width - modules / 1.12f) > 1e-4f || artwork.width != artwork.height
            ) {
                wrong += "v$version artwork $artwork"
            }
        }
        assertThat(wrong).isEmpty()
        // Exact values for the SVG export's `<image>` (iOS writes the same).
        assertThat(QRGeometry.logoLayout(21).artwork.x).isWithin(1e-5f).of(9.160714f)
        assertThat(QRGeometry.logoLayout(33).artwork).isEqualTo(QRRect(13.375f, 13.375f, 6.25f, 6.25f))
    }

    @Test fun preparedLogoSizeCapsTheLongSideLikeIOS() {
        // iOS `preparedLogo` on the same inputs: 4032 × 3024 → 1024 × 768.
        assertThat(QRStyledRenderer.preparedLogoSize(4032, 3024)).isEqualTo(1024 to 768)
        assertThat(QRStyledRenderer.preparedLogoSize(3024, 4032)).isEqualTo(768 to 1024)
        assertThat(QRStyledRenderer.preparedLogoSize(1024, 10)).isEqualTo(1024 to 10)
        assertThat(QRStyledRenderer.preparedLogoSize(800, 600)).isEqualTo(800 to 600)
        // Never rounds a thin side away, and rounds half up.
        assertThat(QRStyledRenderer.preparedLogoSize(5000, 2)).isEqualTo(1024 to 1)
        assertThat(QRStyledRenderer.preparedLogoSize(2048, 1023, maxPixels = 512)).isEqualTo(512 to 256)
    }

    @Test fun aspectFitCentresTheArtworkInsideItsBox() {
        val box = QRRect(10f, 10f, 6f, 6f)
        assertThat(QRGeometry.aspectFit(200f, 100f, box)).isEqualTo(QRRect(10f, 11.5f, 6f, 3f))
        assertThat(QRGeometry.aspectFit(50f, 100f, box)).isEqualTo(QRRect(11.5f, 10f, 3f, 6f))
        assertThat(QRGeometry.aspectFit(0f, 100f, box)).isEqualTo(box)
    }

    @Test fun logoClearsWholeModulesAndDrawsNoPunchBox() {
        // Square modules, black on white, a 2:1 red logo: every module centre
        // outside the cleared square must still show the matrix, the cleared
        // square around the artwork must be bare background, and the artwork
        // must sit aspect-fitted where logoLayout puts it.
        val logo = solidBitmap(Color.RED, 40, 20)
        val style = QRStyle(logo = logo)
        val coded = CodeGenerator.styledMatrix(content, style)!!
        val symbol = QRGeometry.symbolArea(coded)!!
        val side = symbol.width.toInt()
        val layout = QRGeometry.logoLayout(side)
        val fitted = QRGeometry.aspectFit(40f, 20f, layout.artwork)
        val eyes = QRGeometry.eyeRects(side, side)
        val bitmap = QRStyledRenderer.bitmap(coded, style, moduleSize)!!

        val wrong = mutableListOf<String>()
        var clearedModules = 0
        var artworkModules = 0
        for (y in 0 until side) {
            for (x in 0 until side) {
                if (QRGeometry.isInEye(x, y, eyes)) continue
                val cx = x + 0.5f
                val cy = y + 0.5f
                // Module centres within a quarter module of the artwork's edge
                // sample its antialiased border; skip them.
                val nearEdge = fitted.insetBy(-0.25f, -0.25f).contains(cx, cy) &&
                    !fitted.insetBy(0.25f, 0.25f).contains(cx, cy)
                if (nearEdge) continue
                val expected = when {
                    fitted.contains(cx, cy) -> Color.RED.also { artworkModules++ }
                    layout.cleared.contains(cx, cy) -> Color.WHITE.also { clearedModules++ }
                    coded[symbol.x.toInt() + x, symbol.y.toInt() + y] -> Color.BLACK
                    else -> Color.WHITE
                }
                val pixel = bitmap.getPixel(
                    (margin + x) * moduleSize + moduleSize / 2,
                    (margin + y) * moduleSize + moduleSize / 2,
                )
                if (pixel != expected) wrong += "module $x,$y is ${Integer.toHexString(pixel)}"
            }
        }
        assertThat(clearedModules).isGreaterThan(0)
        assertThat(artworkModules).isGreaterThan(0)
        assertThat(wrong).isEmpty()
    }

    @Test fun styledSvgInlinesTheLogoOnceInModuleUnits() {
        // Bigger than the 512-pixel cap, so the inlined copy is scaled down.
        val style = softStyle().copy(logo = solidBitmap(Color.RED, 1200, 600))
        val coded = CodeGenerator.styledMatrix(content, style)!!
        val side = QRGeometry.symbolArea(coded)!!.width.toInt()
        val svg = CodeSvg.svg(coded, style = style)
        val artwork = QRGeometry.logoLayout(side).artwork

        // One copy of the artwork, under `xlink:href` alone (as on iOS), with
        // the namespace declared — no SVG 2 `href` duplicate, no backing patch.
        assertThat(svg.split("base64,").size - 1).isEqualTo(1)
        assertThat(svg).contains("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" ")
        assertThat(svg).doesNotContain(" href=")
        assertThat(svg).doesNotContain("<rect x=")
        val image = "<image x=\"${num(artwork.x)}\" y=\"${num(artwork.y)}\" width=\"${num(artwork.width)}\" " +
            "height=\"${num(artwork.height)}\" preserveAspectRatio=\"xMidYMid meet\" " +
            "xlink:href=\"data:image/png;base64,"
        assertThat(svg).contains(image)
        // Inside the module-unit group, after the three passes.
        assertThat(svg.indexOf("<image ")).isGreaterThan(svg.lastIndexOf("<path "))
        assertThat(svg.indexOf("<image ")).isLessThan(svg.indexOf("</g>"))

        // The inlined PNG is the logo at most 512 px on its long side.
        val base64 = svg.substringAfter("base64,").substringBefore('"')
        val png = Base64.decode(base64, Base64.DEFAULT)
        assertThat(pngSize(png)).isEqualTo(512 to 256)

        // A logo already under the cap is inlined at its own size.
        val small = CodeSvg.svg(coded, style = softStyle().copy(logo = solidBitmap(Color.RED, 64, 48)))
        val smallPng = Base64.decode(small.substringAfter("base64,").substringBefore('"'), Base64.DEFAULT)
        assertThat(pngSize(smallPng)).isEqualTo(64 to 48)
        // No logo, no xlink namespace.
        assertThat(CodeSvg.svg(coded, style = softStyle())).doesNotContain("xlink")
    }

    @Config(shadows = [RasterPdfDocumentShadow::class])
    @Test fun styledPdfPageIsThePreviewPicture() {
        // The page writePdfUri draws for a styled code with a logo must be the
        // very picture the preview / PNG shows: same size, same shapes and
        // inks, the logo in the same place. (It used to leave the logo out.)
        val context = RuntimeEnvironment.getApplication()
        val logo = solidBitmap(Color.RED, 40, 20)
        val style = QRStyle.preset("ink")!!.style.copy(logo = logo)
        val coded = CodeGenerator.styledMatrix(content, style)!!
        CodeSvg.writePdfUri(context, coded, moduleSize = moduleSize, fileName = "studio-test.pdf", style = style)
        val bytes = File(File(context.cacheDir, "shared"), "studio-test.pdf").readBytes()
        val page = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val preview = QRStyledRenderer.bitmap(coded, style, moduleSize)!!

        assertThat(page.width).isEqualTo(preview.width)
        assertThat(page.height).isEqualTo(preview.height)
        assertThat(page.sameAs(preview)).isTrue()
        val side = QRGeometry.symbolArea(coded)!!.width.toInt()
        val artwork = QRGeometry.aspectFit(40f, 20f, QRGeometry.logoLayout(side).artwork)
        val centre = page.getPixel(
            ((margin + artwork.x + artwork.width / 2) * moduleSize).toInt(),
            ((margin + artwork.y + artwork.height / 2) * moduleSize).toInt(),
        )
        assertThat(centre).isEqualTo(Color.RED)
    }

    // MARK: - Helpers

    /** The SVG export's number form: at most three decimals, no trailing zeros. */
    private fun num(value: Float): String {
        val thousandths = Math.round(value * 1000f)
        val whole = thousandths / 1000
        val fraction = thousandths % 1000
        return if (fraction == 0) "$whole" else "$whole." + fraction.toString().padStart(3, '0').trimEnd('0')
    }

    /** Width and height from a PNG's IHDR chunk. */
    private fun pngSize(png: ByteArray): Pair<Int, Int> {
        fun int(at: Int) = ((png[at].toInt() and 0xFF) shl 24) or ((png[at + 1].toInt() and 0xFF) shl 16) or
            ((png[at + 2].toInt() and 0xFF) shl 8) or (png[at + 3].toInt() and 0xFF)
        return int(16) to int(20)
    }

    private fun solidBitmap(argb: Int, width: Int, height: Int): Bitmap =
        createBitmap(width, height).apply { eraseColor(argb) }

    /** Colours at module centres only — module edges antialias, centres don't. */
    private fun moduleColours(bitmap: Bitmap): Set<Int> {
        val colours = mutableSetOf<Int>()
        for (y in -margin until area.height.toInt() + margin) {
            for (x in -margin until area.width.toInt() + margin) {
                val px = (margin + x) * moduleSize + moduleSize / 2
                val py = (margin + y) * moduleSize + moduleSize / 2
                if (px !in 0 until bitmap.width || py !in 0 until bitmap.height) continue
                colours += bitmap.getPixel(px, py)
            }
        }
        return colours
    }

    private fun softStyle() = QRStyle(
        moduleShape = QRModuleShape.ROUNDED,
        eyeShape = QREyeShape.ROUNDED,
    )

    /** Flattened matrix, for comparing two encodings. */
    private fun bitsOf(matrix: BitMatrix): List<Boolean> =
        (0 until matrix.height).flatMap { y -> (0 until matrix.width).map { x -> matrix[x, y] } }

    /** Square modules, so every colour change can only come from the inks. */
    private fun gradientStyle() = QRStyle(
        inks = listOf(Color.BLACK, Color.BLUE),
    )
}

/**
 * Robolectric has no native [PdfDocument] (every call reports "document is
 * closed!"), so the PDF test swaps in this shadow: the page is a bitmap canvas
 * and `writeTo` saves it as a PNG. The drawing CodeSvg does onto the page is
 * real; only Skia's PDF serialisation is left out.
 */
@Implements(PdfDocument::class)
class RasterPdfDocumentShadow {

    private var page: Bitmap? = null

    /** Skips the native document (and the close guard that would nag about it). */
    @Implementation
    fun __constructor__() = Unit

    @Implementation
    fun startPage(pageInfo: PdfDocument.PageInfo): PdfDocument.Page {
        val bitmap = createBitmap(pageInfo.pageWidth, pageInfo.pageHeight)
        page = bitmap
        val constructor = PdfDocument.Page::class.java
            .getDeclaredConstructor(Canvas::class.java, PdfDocument.PageInfo::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(Canvas(bitmap), pageInfo)
    }

    @Implementation
    fun finishPage(page: PdfDocument.Page) {
        check(page.info.pageWidth == this.page?.width) { "finished a page this document never started" }
    }

    @Implementation
    fun writeTo(out: OutputStream) {
        checkNotNull(page) { "no page drawn" }.compress(Bitmap.CompressFormat.PNG, 100, out)
    }

    @Implementation
    fun close() = Unit
}
