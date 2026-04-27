package me.nettrash.scan.scanner

import androidx.compose.ui.geometry.Rect

/**
 * One decoded barcode result. `previewRect` is the bounding rect of the code
 * inside the live preview's coordinate space (in pixels) — the scanner UI
 * uses it to anchor the on-screen reticle to the detected code.
 */
data class ScannedCode(
    val value: String,
    val symbology: Symbology,
    val timestampMillis: Long,
    val previewRect: Rect? = null
)
