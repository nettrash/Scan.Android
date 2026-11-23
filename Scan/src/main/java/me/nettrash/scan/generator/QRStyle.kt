package me.nettrash.scan.generator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.google.zxing.common.BitMatrix
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The QR design studio: module shape, finder-eye shape, gradient inks, and
 * a preset library — drawn from the same [BitMatrix] the existing PNG /
 * SVG / PDF exports already consume. Ports iOS's `Scan/QRStyle.swift`.
 *
 * Everything is drawn by hand from the module grid instead of asking ZXing
 * for a picture, because rounded modules, leaf corners and ring eyes are
 * vector decisions. One grid in, one shape list out, filled either flat or
 * with a diagonal gradient — so the preview, the PNG, the SVG and the PDF
 * cannot drift apart.
 *
 * Rules that keep a "designer" code scannable, enforced here rather than
 * trusted to the user:
 *
 *   * the 4-module quiet zone is never styled away (clamped, not asked);
 *   * a finder eye is always three concentric shapes — outer ring, gap,
 *     centre — because that is what a reader locks onto;
 *   * non-square modules or inset artwork raise the requested error
 *     correction to HIGH to pay back the contrast they cost;
 *   * the centre artwork is sized off the symbol, not the canvas, and
 *     clears whole modules only ([QRGeometry.logoLayout]) — about 6 % of
 *     the symbol at most, well inside what High error correction rebuilds.
 */

/** Shape of a single data module. */
enum class QRModuleShape(val label: String) {
    SQUARE("Square"),
    ROUNDED("Rounded"),
    DOT("Dots"),
    LEAF("Leaves"),
    BARS("Bars"),
    ;

    /** Non-square modules trade module area for looks. */
    val needsExtraRedundancy: Boolean get() = this != SQUARE
}

/** Shape of the three finder eyes. */
enum class QREyeShape(val label: String) {
    SQUARE("Square"),
    ROUNDED("Rounded"),
    CIRCLE("Circle"),
    RING("Ring"),
}

/** Corners a rounded rect may round. The leaf module needs a mix. */
enum class QRCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_RIGHT,
    BOTTOM_LEFT,
    ;

    companion object {
        val ALL: Set<QRCorner> = values().toSet()
    }
}

/**
 * One style = shapes + inks. A single-entry [inks] is a flat fill; two or
 * more is a diagonal gradient across the whole code.
 *
 * Colours are argb ints (what [android.graphics.Paint] wants) rather than
 * Compose colours so this file stays free of UI dependencies and is
 * unit-testable on the JVM.
 */
data class QRStyle(
    val moduleShape: QRModuleShape = QRModuleShape.SQUARE,
    val eyeShape: QREyeShape = QREyeShape.SQUARE,
    val inks: List<Int> = listOf(Color.BLACK),
    val backgroundArgb: Int = Color.WHITE,
    val quietZone: Int = 4,
    /** Artwork over the centre. Kept null-equatable so [isPlain] stays honest. */
    val logo: Bitmap? = null,
) {
    /** The default generator output — nothing for the styled path to do. */
    val isPlain: Boolean
        get() = moduleShape == QRModuleShape.SQUARE &&
            eyeShape == QREyeShape.SQUARE &&
            inks.size <= 1 &&
            logo == null

    val usesGradient: Boolean get() = inks.size > 1

    /** Error-correction level a matrix should be generated at for this style. */
    val minimumErrorCorrection: QRErrorCorrection
        get() = if (logo != null || moduleShape.needsExtraRedundancy || eyeShape != QREyeShape.SQUARE) {
            QRErrorCorrection.HIGH
        } else {
            QRErrorCorrection.MEDIUM
        }

    val primaryInk: Int get() = inks.firstOrNull() ?: Color.BLACK

    /**
     * Whether a code of [symbolSide] modules is too dense for this style's
     * modules to scan reliably — the generator says so rather than letting
     * the user print it. A shaped module keeps less of its square's area,
     * which leaves a reader less margin, and a logo spends part of the error
     * correction that margin relies on. Measured with Apple's reader (Vision,
     * what iOS Scan's photo and file import uses), which fails this port's
     * renders at about the same sizes as the iOS ones:
     *
     *   * dots keep under half of it: without a logo, coloured dots start
     *     failing around version 18–20 and black ones by version 24–26; with
     *     a logo, coloured dots fail from version 11 and black ones from 14;
     *   * rounded, leaf and bar modules — Soft, Ink and Nature among the
     *     presets — read through version 25 without a logo, but with one they
     *     fail from version 13 to 20, depending on the artwork;
     *   * square modules read through version 25 with or without a logo, and
     *     the eye shape makes no difference.
     *
     * Each threshold sits a version or two below the first failures. Not
     * covered: artwork darker than a coloured ink — Sunset's dots with a dark
     * logo already fail at versions 7–9. Same rule and thresholds as iOS
     * `QRStyle.isHardToScan(symbolSide:)`, so both ports warn on the same
     * codes.
     */
    fun isHardToScan(symbolSide: Int): Boolean = when (moduleShape) {
        QRModuleShape.SQUARE -> false
        QRModuleShape.DOT ->
            symbolSide >= if (logo == null) DENSE_DOT_SYMBOL_SIDE else DENSE_DOT_WITH_LOGO_SYMBOL_SIDE
        QRModuleShape.ROUNDED, QRModuleShape.LEAF, QRModuleShape.BARS ->
            logo != null && symbolSide >= NON_SQUARE_WITH_LOGO_SYMBOL_SIDE
    }

    /**
     * What the generator says under the preview when the code is hard to
     * scan; null when it isn't. With a logo, from version 12 up every shaped
     * module is past its threshold, so the advice narrows to square modules
     * or no logo. Same words as iOS `QRStyle.scannabilityWarning(symbolSide:)`.
     */
    fun scannabilityWarning(symbolSide: Int): String? = when {
        !isHardToScan(symbolSide) -> null
        logo == null ->
            "Dots get hard for cameras to read on a code this dense. Square, rounded, leaf or bar " +
                "modules scan more reliably."
        moduleShape == QRModuleShape.DOT && symbolSide < NON_SQUARE_WITH_LOGO_SYMBOL_SIDE ->
            "Dots get hard for cameras to read on a code this dense with a logo. Square, rounded, " +
                "leaf or bar modules — or no logo — scan more reliably."
        else ->
            "Shaped modules with a logo get hard for cameras to read on a code this dense. Square " +
                "modules — or no logo — scan more reliably."
    }

    /** A named studio look. Applying one moves shapes *and* inks. */
    data class Preset(val id: String, val name: String, val style: QRStyle)

    companion object {

        /** Version 16 (81 modules a side): dots get unreliable from here. */
        const val DENSE_DOT_SYMBOL_SIDE: Int = 81

        /** Version 10 (57 modules a side): the same, once a logo is inset. */
        const val DENSE_DOT_WITH_LOGO_SYMBOL_SIDE: Int = 57

        /** Version 12 (65 modules a side): rounded, leaf and bar modules with a logo. */
        const val NON_SQUARE_WITH_LOGO_SYMBOL_SIDE: Int = 65

        val standard = QRStyle()

        /**
         * Fixed sRGB literals shared with iOS (`QRStyle.presets`), so a
         * preset prints the same code on either platform.
         *
         * Every preset ink clears the generator's own 3:1 contrast floor
         * against its background, or applying the preset would raise the
         * contrast warning. That is why Nature and Sunset start from the
         * increased-contrast variants of iOS system green (#248A3D, 4.4:1)
         * and system orange (#C93400, 5.0:1) rather than the standard ones
         * (#34C759 at 2.2:1, #FF9500 at 2.1:1) — QRStyleTest pins it.
         */
        private const val GREEN = 0xFF248A3D.toInt()
        private const val FOREST = 0xFF216B40.toInt()
        private const val MIDNIGHT = 0xFF141A33.toInt()
        private const val VIOLET = 0xFF5933A6.toInt()
        private const val ORANGE = 0xFFC93400.toInt()
        private const val PINK = 0xFFFF2D55.toInt()
        private const val PARCHMENT = 0xFFFCF7ED.toInt()

        val presets: List<Preset> = listOf(
            Preset("classic", "Classic", standard),
            Preset(
                "soft",
                "Soft",
                standard.copy(
                    moduleShape = QRModuleShape.ROUNDED,
                    eyeShape = QREyeShape.ROUNDED,
                ),
            ),
            Preset(
                "dots",
                "Dots",
                standard.copy(
                    moduleShape = QRModuleShape.DOT,
                    eyeShape = QREyeShape.CIRCLE,
                ),
            ),
            Preset(
                "nature",
                "Nature",
                standard.copy(
                    moduleShape = QRModuleShape.LEAF,
                    eyeShape = QREyeShape.RING,
                    inks = listOf(GREEN, FOREST),
                ),
            ),
            Preset(
                "ink",
                "Ink",
                standard.copy(
                    moduleShape = QRModuleShape.ROUNDED,
                    eyeShape = QREyeShape.RING,
                    inks = listOf(MIDNIGHT, VIOLET),
                ),
            ),
            Preset(
                "sunset",
                "Sunset",
                standard.copy(
                    moduleShape = QRModuleShape.DOT,
                    eyeShape = QREyeShape.CIRCLE,
                    inks = listOf(ORANGE, PINK),
                    backgroundArgb = PARCHMENT,
                ),
            ),
        )

        fun preset(id: String?): Preset? = presets.firstOrNull { it.id == id }
    }
}

/** Axis-aligned rectangle in module units. */
data class QRRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val left: Float get() = x
    val top: Float get() = y
    val right: Float get() = x + width
    val bottom: Float get() = y + height

    fun insetBy(dx: Float, dy: Float) = QRRect(x + dx, y + dy, width - 2 * dx, height - 2 * dy)

    fun contains(px: Float, py: Float): Boolean =
        px >= left && px < right && py >= top && py < bottom
}

/**
 * A filled shape in module units. Deliberately neutral (no `Path`, no SVG)
 * so the raster, PDF and SVG renderers all consume the same geometry.
 */
sealed interface QRInkShape {
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) : QRInkShape
    data class Oval(val left: Float, val top: Float, val right: Float, val bottom: Float) : QRInkShape
    data class RoundRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val topLeft: Float,
        val topRight: Float,
        val bottomRight: Float,
        val bottomLeft: Float,
    ) : QRInkShape
}

/** One place decides where ink goes. Every renderer consumes it. */
object QRGeometry {

    /** Finder-eye squares of a symbol, in module units (top-left origin). */
    fun eyeRects(width: Int, height: Int): List<QRRect> = listOf(
        QRRect(0f, 0f, 7f, 7f),
        QRRect((width - 7).toFloat(), 0f, 7f, 7f),
        QRRect(0f, (height - 7).toFloat(), 7f, 7f),
    )

    fun isInEye(x: Int, y: Int, eyes: List<QRRect>): Boolean {
        val px = x + 0.5f
        val py = y + 0.5f
        return eyes.any { it.contains(px, py) }
    }

    /** One data module, in module units at the origin. */
    fun moduleShape(shape: QRModuleShape): QRInkShape = when (shape) {
        QRModuleShape.SQUARE -> QRInkShape.Rect(0f, 0f, 1f, 1f)
        QRModuleShape.ROUNDED -> roundedRect(QRRect(0.06f, 0.06f, 0.88f, 0.88f), 0.26f)
        QRModuleShape.DOT -> QRInkShape.Oval(0.12f, 0.12f, 0.88f, 0.88f)
        QRModuleShape.LEAF -> roundedRect(
            QRRect(0.04f, 0.04f, 0.92f, 0.92f),
            0.46f,
            setOf(QRCorner.TOP_LEFT, QRCorner.BOTTOM_RIGHT),
        )
        QRModuleShape.BARS -> roundedRect(QRRect(0.16f, 0f, 0.68f, 1f), 0.34f)
    }

    /**
     * The three concentric pieces of a finder eye, in module units, in
     * draw order: ink outer, background gap, ink centre.
     */
    fun eyeShapes(shape: QREyeShape, eye: QRRect): List<QRInkShape> {
        val outer = eye
        val gap = eye.insetBy(1f, 1f)
        val centre = eye.insetBy(2f, 2f)
        return when (shape) {
            QREyeShape.SQUARE -> listOf(
                QRInkShape.Rect(outer.left, outer.top, outer.right, outer.bottom),
                QRInkShape.Rect(gap.left, gap.top, gap.right, gap.bottom),
                QRInkShape.Rect(centre.left, centre.top, centre.right, centre.bottom),
            )
            QREyeShape.ROUNDED -> listOf(
                roundedRect(outer, 2f),
                roundedRect(gap, 1.3f),
                roundedRect(centre, 0.9f),
            )
            QREyeShape.CIRCLE -> listOf(
                QRInkShape.Oval(outer.left, outer.top, outer.right, outer.bottom),
                QRInkShape.Oval(gap.left, gap.top, gap.right, gap.bottom),
                QRInkShape.Oval(centre.left, centre.top, centre.right, centre.bottom),
            )
            QREyeShape.RING -> listOf(
                // Square frame, round interior — reads as a ring, stays boxy
                // enough for a reader to find the three-to-one ratio.
                roundedRect(outer, 1.8f),
                QRInkShape.Oval(gap.left, gap.top, gap.right, gap.bottom),
                QRInkShape.Oval(centre.left, centre.top, centre.right, centre.bottom),
            )
        }
    }

    /**
     * Rounded rectangle with per-corner control — "all corners or none" is
     * not enough for the leaf module.
     */
    fun roundedRect(
        rect: QRRect,
        radius: Float,
        corners: Set<QRCorner> = QRCorner.ALL,
    ): QRInkShape.RoundRect {
        val clamped = min(radius, min(rect.width, rect.height) / 2f)
        fun r(corner: QRCorner) = if (corner in corners) clamped else 0f
        return QRInkShape.RoundRect(
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            topLeft = r(QRCorner.TOP_LEFT),
            topRight = r(QRCorner.TOP_RIGHT),
            bottomRight = r(QRCorner.BOTTOM_RIGHT),
            bottomLeft = r(QRCorner.BOTTOM_LEFT),
        )
    }

    /**
     * Bounding box of the inked modules, in module units. ZXing hands back a
     * symbol *with* its own quiet-zone border (we ask for 1 module via
     * [com.google.zxing.EncodeHintType.MARGIN]), so that padding is trimmed
     * here and re-added by the renderer at the style's own width. This is
     * what turns "a bitmap with a border" into "a symbol we can place eyes
     * on".
     */
    fun symbolArea(matrix: BitMatrix): QRRect? {
        var minX = matrix.width
        var minY = matrix.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (!matrix[x, y]) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < minX || maxY < minY) return null
        return QRRect(
            x = minX.toFloat(),
            y = minY.toFloat(),
            width = (maxX - minX + 1).toFloat(),
            height = (maxY - minY + 1).toFloat(),
        )
    }

    /**
     * The three ink passes a styled code is drawn in, in module units of
     * [area]: ink (data modules plus the outer ring of every eye), the
     * background gap, then the ink centre. Keeping one list per pass is what
     * lets the raster, PDF and SVG exports share a single geometry decision
     * — and drawing the eyes in three passes is what stops a gradient
     * smearing across the gap of an eye.
     */
    fun inkPasses(matrix: BitMatrix, style: QRStyle, area: QRRect): InkPasses {
        val width = area.width.toInt()
        val height = area.height.toInt()
        val eyes = eyeRects(width, height)
        val ink = mutableListOf<QRInkShape>()
        val gap = mutableListOf<QRInkShape>()
        val centre = mutableListOf<QRInkShape>()

        val originX = area.x.toInt()
        val originY = area.y.toInt()
        val cleared = if (style.logo == null) null else logoLayout(width).cleared

        // Eyes get their own shapes below; skipping their modules here keeps a
        // square module from squaring off a round eye. Modules under the logo
        // are left out rather than painted over, so no sliver of one peeks out
        // from behind the artwork.
        fun isDataInk(x: Int, y: Int): Boolean {
            if (!matrix[originX + x, originY + y] || isInEye(x, y, eyes)) return false
            return cleared?.contains(x + 0.5f, y + 0.5f) != true
        }

        if (style.moduleShape == QRModuleShape.SQUARE) {
            // Square modules merge into one rectangle per horizontal run: the
            // same picture with a fraction of the subpaths, which keeps the SVG
            // and PDF small (iOS `QRGeometry.passes` does the same).
            for (y in 0 until height) {
                var x = 0
                while (x < width) {
                    if (!isDataInk(x, y)) { x++; continue }
                    val start = x
                    while (x < width && isDataInk(x, y)) x++
                    ink += QRInkShape.Rect(start.toFloat(), y.toFloat(), x.toFloat(), y + 1f)
                }
            }
        } else {
            val module = moduleShape(style.moduleShape)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (isDataInk(x, y)) ink += module.translated(x.toFloat(), y.toFloat())
                }
            }
        }
        for (eye in eyes) {
            val pieces = eyeShapes(style.eyeShape, eye)
            ink += pieces[0]
            gap += pieces[1]
            centre += pieces[2]
        }
        return InkPasses(ink, gap, centre, eyes)
    }

    /** The result of [inkPasses]. */
    data class InkPasses(
        val ink: List<QRInkShape>,
        val gap: List<QRInkShape>,
        val centre: List<QRInkShape>,
        val eyes: List<QRRect>,
    )

    // MARK: Logo

    /** Where the centre logo goes, in module units of the symbol. */
    data class LogoLayout(
        /** Whole modules left blank under the artwork. Their data is lost
         *  either way; error correction rebuilds it. */
        val cleared: QRRect,
        /** Where the artwork is aspect-fitted, padded inside [cleared]. */
        val artwork: QRRect,
    )

    /** Proportions of the plain generator's logo (`CodeGenerator.composite`):
     *  artwork 22 % of the side, padded by 6 % of itself on every side. */
    private const val ARTWORK_FRACTION = 0.22
    private const val ARTWORK_PADDING = 0.06

    /**
     * Sized off the *symbol*, never off the canvas, and snapped to whole
     * modules: the cleared square is the largest odd number of modules that
     * fits the plain generator's proportions, centred on the middle module (a
     * symbol side is always odd), so its edges fall on module boundaries.
     * What it wipes is therefore exact: at most about 6 % of the symbol, well
     * inside what High error correction rebuilds even on the smallest
     * versions, where its share of the codewords is largest.
     *
     * The first and last nine rows and columns hold the finders, their
     * separators, the timing patterns and both copies of the format
     * information; the cleared square never reaches them. Only version 1 is
     * small enough for that to bind (3 modules instead of 5). On larger
     * versions it can cover alignment patterns near the centre, as any
     * centred logo does.
     *
     * Same arithmetic, in the same order and precision, as iOS
     * `QRGeometry.logoLayout(side:)`, so both ports clear the same modules.
     */
    fun logoLayout(side: Int): LogoLayout {
        val proportional = side * ARTWORK_FRACTION * (1 + 2 * ARTWORK_PADDING)
        var modules = min(floor(proportional).toInt(), side - 18)
        if (modules % 2 == 0) modules -= 1
        modules = max(modules, 1)
        val origin = (side - modules) / 2f
        val cleared = QRRect(origin, origin, modules.toFloat(), modules.toFloat())
        val artworkSide = modules / (1 + 2 * ARTWORK_PADDING)
        val inset = ((modules - artworkSide) / 2).toFloat()
        return LogoLayout(cleared = cleared, artwork = cleared.insetBy(inset, inset))
    }

    /** A [width] × [height] picture aspect-fitted and centred in [rect]. */
    fun aspectFit(width: Float, height: Float, rect: QRRect): QRRect {
        if (width <= 0f || height <= 0f) return rect
        val scale = min(rect.width / width, rect.height / height)
        val w = width * scale
        val h = height * scale
        return QRRect(rect.x + rect.width / 2f - w / 2f, rect.y + rect.height / 2f - h / 2f, w, h)
    }

    /** Translate a shape into module units at (dx, dy). */
    fun QRInkShape.translated(dx: Float, dy: Float): QRInkShape = when (this) {
        is QRInkShape.Rect -> copy(left = left + dx, top = top + dy, right = right + dx, bottom = bottom + dy)
        is QRInkShape.Oval -> copy(left = left + dx, top = top + dy, right = right + dx, bottom = bottom + dy)
        is QRInkShape.RoundRect -> copy(
            left = left + dx,
            top = top + dy,
            right = right + dx,
            bottom = bottom + dy,
        )
    }

    /** Bounding box of a shape, in module units. */
    fun bounds(shape: QRInkShape): QRRect = when (shape) {
        is QRInkShape.Rect -> shape.toRect()
        is QRInkShape.Oval -> shape.toRect()
        is QRInkShape.RoundRect -> shape.toRect()
    }

    private fun QRInkShape.Rect.toRect() =
        QRRect(left, top, right - left, bottom - top)

    private fun QRInkShape.Oval.toRect() =
        QRRect(left, top, right - left, bottom - top)

    private fun QRInkShape.RoundRect.toRect() =
        QRRect(left, top, right - left, bottom - top)

    /** Append [shape] to [path], offset by (dx, dy) module units. */
    fun appendTo(shape: QRInkShape, path: Path, dx: Float = 0f, dy: Float = 0f) {
        when (shape) {
            is QRInkShape.Rect -> path.addRect(
                shape.left + dx, shape.top + dy, shape.right + dx, shape.bottom + dy,
                Path.Direction.CW,
            )
            is QRInkShape.Oval -> path.addOval(
                RectF(shape.left + dx, shape.top + dy, shape.right + dx, shape.bottom + dy),
                Path.Direction.CW,
            )
            is QRInkShape.RoundRect -> {
                val radii = floatArrayOf(
                    shape.topLeft, shape.topLeft,
                    shape.topRight, shape.topRight,
                    shape.bottomRight, shape.bottomRight,
                    shape.bottomLeft, shape.bottomLeft,
                )
                path.addRoundRect(
                    RectF(shape.left + dx, shape.top + dy, shape.right + dx, shape.bottom + dy),
                    radii,
                    Path.Direction.CW,
                )
            }
        }
    }

    /** Build one path [Path] from [shapes]. */
    fun toPath(shapes: List<QRInkShape>): Path {
        val path = Path()
        shapes.forEach { appendTo(it, path) }
        return path
    }

    /** Serialise shapes into one SVG path `d` attribute. */
    fun svgPathD(shapes: List<QRInkShape>): String {
        if (shapes.isEmpty()) return ""
        val out = StringBuilder(shapes.size * 96)
        for (shape in shapes) appendSvgShape(out, shape)
        return out.toString()
    }

    private fun appendSvgShape(out: StringBuilder, shape: QRInkShape) {
        when (shape) {
            is QRInkShape.Rect ->
                out.append("M").append(number(shape.left)).append(',').append(number(shape.top))
                    .append("H").append(number(shape.right))
                    .append("V").append(number(shape.bottom))
                    .append("H").append(number(shape.left))
                    .append("Z")

            is QRInkShape.Oval -> {
                val rx = (shape.right - shape.left) / 2f
                val ry = (shape.bottom - shape.top) / 2f
                val cx = shape.left + rx
                out.append("M").append(number(cx)).append(',').append(number(shape.top))
                    .append("A").append(number(rx)).append(',').append(number(ry))
                    .append(" 0 1 1 ").append(number(cx)).append(',').append(number(shape.bottom))
                    .append("A").append(number(rx)).append(',').append(number(ry))
                    .append(" 0 1 1 ").append(number(cx)).append(',').append(number(shape.top))
                    .append("Z")
            }

            is QRInkShape.RoundRect -> appendSvgRoundRect(out, shape)
        }
    }

    private fun appendSvgRoundRect(out: StringBuilder, shape: QRInkShape.RoundRect) {
        val x0 = shape.left
        val y0 = shape.top
        val x1 = shape.right
        val y1 = shape.bottom
        out.append("M").append(number(x0 + shape.topLeft)).append(',').append(number(y0))
        if (shape.topRight > 0f) {
            out.append("H").append(number(x1 - shape.topRight))
                .append("A").append(number(shape.topRight)).append(',').append(number(shape.topRight))
                .append(" 0 0 1 ").append(number(x1)).append(',').append(number(y0 + shape.topRight))
        } else {
            out.append("H").append(number(x1))
        }
        if (shape.bottomRight > 0f) {
            out.append("V").append(number(y1 - shape.bottomRight))
                .append("A").append(number(shape.bottomRight)).append(',').append(number(shape.bottomRight))
                .append(" 0 0 1 ").append(number(x1 - shape.bottomRight)).append(',').append(number(y1))
        } else {
            out.append("V").append(number(y1))
        }
        if (shape.bottomLeft > 0f) {
            out.append("H").append(number(x0 + shape.bottomLeft))
                .append("A").append(number(shape.bottomLeft)).append(',').append(number(shape.bottomLeft))
                .append(" 0 0 1 ").append(number(x0)).append(',').append(number(y1 - shape.bottomLeft))
        } else {
            out.append("H").append(number(x0))
        }
        if (shape.topLeft > 0f) {
            out.append("V").append(number(y0 + shape.topLeft))
                .append("A").append(number(shape.topLeft)).append(',').append(number(shape.topLeft))
                .append(" 0 0 1 ").append(number(x0 + shape.topLeft)).append(',').append(number(y0))
        } else {
            out.append("V").append(number(y0))
        }
        out.append("Z")
    }

    /** Compact, locale-independent number formatting for path data (and the
     *  logo's placement in the SVG export, which shares its module units). */
    internal fun number(value: Float): String {
        val rounded = Math.round(value * 1000f) / 1000f
        return if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
    }
}

/**
 * Draws a styled code from a ZXing [BitMatrix]. Mirrors iOS's
 * `QRStyledRenderer`.
 */
object QRStyledRenderer {

    /** Pixels per module the bitmap is drawn at. */
    const val DEFAULT_MODULE_SIZE = 12

    /** Hard ceiling on canvas size — a runaway style must not OOM the app. */
    private const val MAX_SIDE_PIXELS = 12_000

    /** Longest side, in pixels, a picked logo is kept at (iOS `preparedLogo`). */
    const val LOGO_MAX_PIXELS = 1024

    /**
     * Pixel size of a [width] × [height] logo shrunk to at most [maxPixels] on
     * its long side, aspect kept — unchanged when it already fits. Same
     * rounding as iOS `QRStyledRenderer.preparedLogo`. A camera-roll photo is
     * 12 MP: redrawn on every preview refresh, embedded in the PDF or inlined
     * into the SVG as base64, it would cost tens of milliseconds and tens of
     * megabytes for artwork that ends up a few hundred pixels wide.
     */
    fun preparedLogoSize(width: Int, height: Int, maxPixels: Int = LOGO_MAX_PIXELS): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= maxPixels) return width to height
        val factor = maxPixels.toDouble() / longest
        return max(1, (width * factor).roundToInt()) to max(1, (height * factor).roundToInt())
    }

    /**
     * Render [matrix] with [style]. Returns null when the matrix carries no
     * ink or the requested geometry would be absurdly large.
     */
    fun bitmap(
        matrix: BitMatrix,
        style: QRStyle,
        moduleSize: Int = DEFAULT_MODULE_SIZE,
    ): Bitmap? {
        val area = QRGeometry.symbolArea(matrix) ?: return null
        // The quiet zone is clamped, never asked for: a code a reader can't
        // find is worse than one the user didn't fully customise.
        val margin = max(style.quietZone, 4)
        val side = (area.width.toInt() + margin * 2) * moduleSize
        if (area.width <= 0f || area.height <= 0f || side <= 0 || side >= MAX_SIDE_PIXELS) return null

        val out = createBitmap(side, side)
        draw(Canvas(out), matrix, style, area, moduleSize)
        return out
    }

    /**
     * Draw the whole code — background, the three ink passes, the logo —
     * onto [canvas], whose origin is the code's top-left corner (the outer
     * edge of the quiet zone) and whose unit is one pixel or PDF point,
     * [moduleSize] of them per module. The styled PDF export draws through
     * this too, so the page is the same picture as the preview and the PNG
     * (iOS `QRStyledRenderer.draw`).
     */
    fun draw(canvas: Canvas, matrix: BitMatrix, style: QRStyle, area: QRRect, moduleSize: Int) {
        val margin = max(style.quietZone, 4)
        canvas.drawColor(style.backgroundArgb)

        // Draw in module units: translate past the quiet zone, then scale.
        // Android's canvas is already y-down, so nothing needs flipping.
        canvas.withTranslation(margin * moduleSize.toFloat(), margin * moduleSize.toFloat()) {
            scale(moduleSize.toFloat(), moduleSize.toFloat())

            val passes = QRGeometry.inkPasses(matrix, style, area)

            // Pass 1 — data modules plus the outer ring of every eye.
            fill(this, QRGeometry.toPath(passes.ink), style, area)

            // Pass 2 — the gap between ring and centre, in background ink.
            drawPath(QRGeometry.toPath(passes.gap), backgroundPaint(style))

            // Pass 3 — the centre dot, filled against the same gradient anchor
            // so every eye reads with the same wash as the data modules.
            fill(this, QRGeometry.toPath(passes.centre), style, area)

            // The modules under the logo were never drawn (`inkPasses` leaves
            // them out), so the artwork sits on clean background.
            style.logo?.let { drawLogo(this, it, area.width.toInt()) }
        }
    }

    /**
     * Flat fill for a single ink, gradient fill for a multi-ink style.
     *
     * The gradient is anchored to the *code area* (module units, origin at the
     * symbol's top-left) so several inks read as one wash across the whole
     * symbol rather than per module, and it only paints inside the path —
     * iOS reaches for `clip()` plus a gradient draw, a shader gives the same
     * result with antialiased edges.
     */
    private fun fill(canvas: Canvas, path: Path, style: QRStyle, area: QRRect) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        if (style.usesGradient) {
            paint.shader = LinearGradient(
                0f, 0f, area.width, area.height,
                style.inks.toIntArray(),
                null,
                Shader.TileMode.CLAMP,
            )
        } else {
            paint.color = style.primaryInk
        }
        canvas.drawPath(path, paint)
    }

    private fun backgroundPaint(style: QRStyle): Paint {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = style.backgroundArgb
        return paint
    }

    /**
     * Centre artwork, aspect-fitted into [QRGeometry.logoLayout]'s padded
     * square. [canvas] is in module units of a [symbolSide]-module symbol, so
     * the artwork is sized off the symbol, never off the canvas. No punch box:
     * the cleared modules already are background (iOS `QRStyledRenderer.draw`).
     */
    private fun drawLogo(canvas: Canvas, logo: Bitmap, symbolSide: Int) {
        if (logo.width <= 0 || logo.height <= 0) return
        val box = QRGeometry.logoLayout(symbolSide).artwork
        val fitted = QRGeometry.aspectFit(logo.width.toFloat(), logo.height.toFloat(), box)
        val dst = RectF(fitted.left, fitted.top, fitted.right, fitted.bottom)
        val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(logo, null, dst, logoPaint)
    }
}
