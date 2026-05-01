package me.nettrash.scan.generator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Symbologies the app can *generate*. Strictly a subset of what we can
 * scan — limited to what ZXing can encode without extra third-party deps.
 */
enum class GeneratableSymbology(val displayName: String) {
    QR("QR"),
    AZTEC("Aztec"),
    PDF417("PDF417"),
    CODE128("Code 128");

    val is2D: Boolean get() = this != CODE128

    /** Logo embedding only meaningful for QR (Reed-Solomon margin). */
    val supportsLogo: Boolean get() = this == QR

    /** Soft hint for the UI. */
    val maxRecommendedLength: Int
        get() = when (this) {
            QR, AZTEC, PDF417 -> 2048
            CODE128 -> 80
        }
}

/** QR error-correction levels exposed to the UI. Mirrors iOS's
 * `QRErrorCorrection` enum so the cross-platform Settings copy can
 * match exactly. */
enum class QRErrorCorrection(
    val zxing: ErrorCorrectionLevel,
    val displayName: String,
) {
    LOW(ErrorCorrectionLevel.L, "Low (7%)"),
    MEDIUM(ErrorCorrectionLevel.M, "Medium (15%)"),
    QUARTILE(ErrorCorrectionLevel.Q, "Quartile (25%)"),
    HIGH(ErrorCorrectionLevel.H, "High (30%)"),
}

object CodeGenerator {

    /**
     * Render `content` into a sharp, integer-scaled Bitmap with custom
     * colours and an optional centred logo. Returns null if ZXing
     * couldn't encode the content.
     *
     * @param foregroundArgb argb-int painted on "on" modules
     * @param backgroundArgb argb-int painted on "off" modules
     * @param errorCorrection QR-only; ignored for other symbologies. Forced to
     *   HIGH whenever a logo is supplied — callers don't have to know.
     * @param logo optional bitmap painted at ~22% of the QR canvas with a
     *   white rounded-rect "punch" behind it. Has no effect on non-QR
     *   symbologies.
     */
    fun bitmap(
        content: String,
        symbology: GeneratableSymbology,
        scale: Int = 10,
        foregroundArgb: Int = Color.BLACK,
        backgroundArgb: Int = Color.WHITE,
        errorCorrection: QRErrorCorrection = QRErrorCorrection.MEDIUM,
        logo: Bitmap? = null,
    ): Bitmap? {
        val matrix = matrix(content, symbology, errorCorrection, logo) ?: return null

        val w = matrix.width
        val h = matrix.height
        if (w <= 0 || h <= 0) return null

        val outW = w * scale
        val outH = h * scale
        val pixels = IntArray(outW * outH)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val color = if (matrix[x, y]) foregroundArgb else backgroundArgb
                for (sy in 0 until scale) {
                    val rowStart = (y * scale + sy) * outW + x * scale
                    for (sx in 0 until scale) {
                        pixels[rowStart + sx] = color
                    }
                }
            }
        }
        val base = Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)

        return if (symbology.supportsLogo && logo != null) {
            composite(base, logo)
        } else {
            base
        }
    }

    /**
     * Build the underlying ZXing matrix without rasterising. Exposed
     * so [me.nettrash.scan.data.CodeSvg] can emit a vector form of
     * the same QR without re-encoding.
     */
    fun matrix(
        content: String,
        symbology: GeneratableSymbology,
        errorCorrection: QRErrorCorrection = QRErrorCorrection.MEDIUM,
        logo: Bitmap? = null,
    ): BitMatrix? {
        if (content.isEmpty()) return null
        val format = when (symbology) {
            GeneratableSymbology.QR -> BarcodeFormat.QR_CODE
            GeneratableSymbology.AZTEC -> BarcodeFormat.AZTEC
            GeneratableSymbology.PDF417 -> BarcodeFormat.PDF_417
            GeneratableSymbology.CODE128 -> BarcodeFormat.CODE_128
        }

        val effectiveCorrection = if (symbology == GeneratableSymbology.QR && logo != null) {
            QRErrorCorrection.HIGH
        } else {
            errorCorrection
        }

        val hints = mutableMapOf<EncodeHintType, Any>(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to if (symbology == GeneratableSymbology.CODE128) 7 else 1
        )
        if (symbology == GeneratableSymbology.QR) {
            hints[EncodeHintType.ERROR_CORRECTION] = effectiveCorrection.zxing
        }
        return runCatching {
            // Pass 0,0 as desired width/height to let ZXing pick a tight
            // size; the rasterising path scales it ourselves so each
            // module is exactly `scale` px.
            MultiFormatWriter().encode(content, format, 0, 0, hints)
        }.getOrNull()
    }

    /**
     * Compute the WCAG relative-luminance contrast ratio between two
     * colours. Mirrors iOS's helper exactly. UI uses this to surface
     * a warning when the user's chosen pair drops below the
     * scanner-friendly threshold (~3.0).
     */
    fun contrastRatio(fgArgb: Int, bgArgb: Int): Double {
        val l1 = relativeLuminance(fgArgb)
        val l2 = relativeLuminance(bgArgb)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(argb: Int): Double {
        fun ch(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        val r = ch(Color.red(argb))
        val g = ch(Color.green(argb))
        val b = ch(Color.blue(argb))
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /**
     * Paint a white rounded "punch" behind the logo, then aspect-fit
     * the logo on top. Punch is forced white (independent of the
     * user's background colour) so that the logo background always
     * has maximum contrast against the QR modules — the alternative
     * is the logo blending into a coloured background and the
     * scanner failing to lock the finder pattern.
     */
    private fun composite(base: Bitmap, logo: Bitmap): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        val logoSide = (minOf(out.width, out.height) * 0.22f).toInt()
        val cx = out.width  / 2f
        val cy = out.height / 2f
        val logoRect = RectF(
            cx - logoSide / 2f,
            cy - logoSide / 2f,
            cx + logoSide / 2f,
            cy + logoSide / 2f,
        )

        // Punch background: 6 % padding around the logo, white,
        // rounded for less visual noise.
        val punchInset = -logoSide * 0.06f
        val punch = RectF(
            logoRect.left  + punchInset,
            logoRect.top   + punchInset,
            logoRect.right - punchInset,
            logoRect.bottom- punchInset,
        )
        val punchRadius = punch.width() * 0.18f
        val punchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(punch, punchRadius, punchRadius, punchPaint)

        // Aspect-fit the logo inside its rect.
        val srcSide = minOf(logo.width, logo.height)
        val src = Rect(
            (logo.width - srcSide) / 2,
            (logo.height - srcSide) / 2,
            (logo.width + srcSide) / 2,
            (logo.height + srcSide) / 2,
        )
        val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(logo, src, logoRect, logoPaint)

        return out
    }
}
