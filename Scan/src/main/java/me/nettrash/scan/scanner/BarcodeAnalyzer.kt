package me.nettrash.scan.scanner

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Rect
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * CameraX [ImageAnalysis.Analyzer] that runs ML Kit's barcode scanner on
 * each frame and forwards the first decoded result to [onScan]. The
 * analyzer requests every supported symbology — devices that can't decode
 * one of them just won't return matches.
 */
class BarcodeAnalyzer(
    private val onScan: (ScannedCode) -> Unit
) : ImageAnalysis.Analyzer {

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_AZTEC,
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_CODABAR
            )
            .build()
    )

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val media = imageProxy.image
        if (media == null) {
            imageProxy.close(); return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(media, rotation)
        scanner.process(input)
            .addOnSuccessListener { results ->
                val first = results.firstOrNull { !it.rawValue.isNullOrEmpty() }
                if (first != null) {
                    val raw = first.rawValue!!
                    val rect = first.boundingBox?.let { box ->
                        Rect(
                            box.left.toFloat(),
                            box.top.toFloat(),
                            box.right.toFloat(),
                            box.bottom.toFloat()
                        )
                    }
                    onScan(
                        ScannedCode(
                            value = raw,
                            symbology = Symbology.fromMlKit(first.format),
                            timestampMillis = System.currentTimeMillis(),
                            previewRect = rect
                        )
                    )
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}
