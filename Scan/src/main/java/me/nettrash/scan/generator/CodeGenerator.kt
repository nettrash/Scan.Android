package me.nettrash.scan.generator

import android.graphics.Bitmap
import android.graphics.Color
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

    /** Soft hint for the UI. */
    val maxRecommendedLength: Int
        get() = when (this) {
            QR, AZTEC, PDF417 -> 2048
            CODE128 -> 80
        }
}

object CodeGenerator {

    /**
     * Render `content` into a sharp, integer-scaled Bitmap. Returns null if
     * ZXing couldn't encode the content for the chosen symbology.
     */
    fun bitmap(
        content: String,
        symbology: GeneratableSymbology,
        scale: Int = 10
    ): Bitmap? {
        if (content.isEmpty()) return null
        val format = when (symbology) {
            GeneratableSymbology.QR -> BarcodeFormat.QR_CODE
            GeneratableSymbology.AZTEC -> BarcodeFormat.AZTEC
            GeneratableSymbology.PDF417 -> BarcodeFormat.PDF_417
            GeneratableSymbology.CODE128 -> BarcodeFormat.CODE_128
        }

        val hints = mutableMapOf<EncodeHintType, Any>(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to if (symbology == GeneratableSymbology.CODE128) 7 else 1
        )
        if (symbology == GeneratableSymbology.QR) {
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
        }

        val matrix: BitMatrix = runCatching {
            // Pass 0,0 as desired width/height to let ZXing pick a tight size;
            // we then scale it ourselves so each module is exactly `scale` px.
            MultiFormatWriter().encode(content, format, 0, 0, hints)
        }.getOrNull() ?: return null

        val w = matrix.width
        val h = matrix.height
        if (w <= 0 || h <= 0) return null

        val outW = w * scale
        val outH = h * scale
        val pixels = IntArray(outW * outH)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val color = if (matrix[x, y]) Color.BLACK else Color.WHITE
                for (sy in 0 until scale) {
                    val rowStart = (y * scale + sy) * outW + x * scale
                    for (sx in 0 until scale) {
                        pixels[rowStart + sx] = color
                    }
                }
            }
        }
        return Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
    }
}
