package me.nettrash.scan.scanner

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Rect
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One block of text the camera recognised. Mirrors iOS
 * `RecognizedTextBlock`; [bounds] is in image-pixel space (ML Kit reports
 * blocks in the analysis image, not the preview), so the UI treats it as
 * advisory and offers the blocks as a tappable list the way iOS offers them
 * as tappable highlights.
 */
data class RecognizedTextBlock(val text: String, val bounds: Rect? = null)

/** What one Live Text frame produced: the text, plus any codes in it. */
data class TextFrame(
    val blocks: List<RecognizedTextBlock> = emptyList(),
    val codes: List<ScannedCode> = emptyList(),
)

/**
 * The Live Text recogniser for the Scan tab's Text mode — the Android
 * counterpart of iOS `VisionTextScanner` / `ImageAnalysisInteraction`.
 *
 * ML Kit's text recognition runs the model on-device (the bundled
 * `com.google.mlkit:text-recognition` artifact, no Play Services round-trip),
 * which keeps the privacy invariant: nothing the camera reads leaves the
 * phone. This is the only file in the app that talks to the text
 * recognisers, so everything above it — including the whole scanner UI —
 * deals in [RecognizedTextBlock] and nothing else.
 *
 * Live Text mode is gated on [isSupported]; when the recogniser is not on
 * the classpath the mode disappears from the picker, exactly as iOS drops
 * Live Text when `DataScannerViewController.isSupported` comes back false.
 */
object TextScanner {

    val isSupported: Boolean by lazy {
        runCatching {
            Class.forName("com.google.mlkit.vision.text.TextRecognition")
            true
        }.getOrDefault(false)
    }

    private var recognizer: TextRecognizer? = null

    /**
     * Recognise one analysis frame. [barcodeScanner] is optional: a menu
     * photographed at a café usually has a loyalty QR next to the text, so
     * Live Text keeps the barcode recogniser alive the way iOS configures
     * VisionKit's barcode recogniser in `.text` mode.
     *
     * Codes found here carry no `previewRect` — their boxes come back in
     * image space rather than the preview space CameraX hands the codes
     * mode, so the reticle stays unanchored in Live Text.
     *
     * [ImageProxy.getImage] is CameraX's `@ExperimentalGetImage` API — the
     * documented way to hand an analysis frame to ML Kit without a copy, and
     * the one `MlKitAnalyzer` uses internally. The opt-in is scoped to this
     * function, and the proxy stays owned (and closed) by the caller, which
     * is the contract the experimental marker is about.
     */
    @OptIn(ExperimentalGetImage::class)
    suspend fun recognize(imageProxy: ImageProxy, barcodeScanner: BarcodeScanner? = null): TextFrame {
        val image = imageProxy.image ?: return TextFrame()
        val input = InputImage.fromMediaImage(image, imageProxy.imageInfo.rotationDegrees)
        val blocks = client().awaitBlocks(input)
        val codes = barcodeScanner?.awaitBarcodes(input).orEmpty()
        return TextFrame(blocks = blocks, codes = codes)
    }

    /** Release the native model. Called when the scanner screen goes away. */
    fun close() {
        runCatching { recognizer?.close() }
        recognizer = null
    }

    private fun client(): TextRecognizer =
        recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .also { recognizer = it }

    private suspend fun TextRecognizer.awaitBlocks(input: InputImage): List<RecognizedTextBlock> =
        suspendCancellableCoroutine { cont ->
            process(input)
                .addOnSuccessListener { text ->
                    cont.resume(
                        text.textBlocks
                            .map { it.text.trim() }
                            .filter { it.isNotEmpty() }
                            .map { RecognizedTextBlock(it) }
                    )
                }
                .addOnFailureListener { cont.resume(emptyList()) }
        }

    private suspend fun BarcodeScanner.awaitBarcodes(input: InputImage): List<ScannedCode> =
        suspendCancellableCoroutine { cont ->
            val ts = System.currentTimeMillis()
            process(input)
                .addOnSuccessListener { barcodes ->
                    val seen = HashSet<String>()
                    cont.resume(
                        barcodes.mapNotNull { b ->
                            val v = b.rawValue
                            if (v.isNullOrEmpty() || !seen.add(v)) return@mapNotNull null
                            ScannedCode(
                                value = v,
                                symbology = Symbology.fromMlKit(b.format),
                                timestampMillis = ts,
                            )
                        }
                    )
                }
                .addOnFailureListener { cont.resume(emptyList()) }
        }
}
