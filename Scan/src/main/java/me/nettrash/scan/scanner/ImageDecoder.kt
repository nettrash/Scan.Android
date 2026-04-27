package me.nettrash.scan.scanner

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Decode every barcode ML Kit can find in an image. Used by the "import
 * from photo / files" flow.
 */
object ImageDecoder {

    sealed class DecodeError(message: String) : Exception(message) {
        data object LoadFailed : DecodeError("Couldn't read that file as an image.")
        data object NoBarcodeFound : DecodeError("No barcodes were found in that image.")
        class MlKitFailed(cause: Throwable) :
            DecodeError("Couldn't analyze the image: ${cause.message}")
    }

    /** Decode from a content `Uri`. Caller already obtained read permission. */
    suspend fun decode(context: Context, uri: Uri): List<ScannedCode> {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: throw DecodeError.LoadFailed
        return decode(bytes)
    }

    /** Decode raw bytes. */
    suspend fun decode(bytes: ByteArray): List<ScannedCode> {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw DecodeError.LoadFailed
        val image = InputImage.fromBitmap(bitmap, 0)
        val scanner = BarcodeScanning.getClient()

        val barcodes: List<Barcode> = suspendCancellableCoroutine { cont ->
            scanner.process(image)
                .addOnSuccessListener { result -> cont.resume(result) }
                .addOnFailureListener { e -> cont.resumeWithException(DecodeError.MlKitFailed(e)) }
        }

        val out = barcodes.mapNotNull { barcode ->
            val raw = barcode.rawValue ?: barcode.displayValue ?: return@mapNotNull null
            if (raw.isEmpty()) return@mapNotNull null
            ScannedCode(
                value = raw,
                symbology = Symbology.fromMlKit(barcode.format),
                timestampMillis = System.currentTimeMillis(),
                previewRect = null
            )
        }
        if (out.isEmpty()) throw DecodeError.NoBarcodeFound
        return out
    }
}
