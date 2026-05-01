package me.nettrash.scan.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Decode every barcode ML Kit can find in an image, a PDF, or a batch
 * of either. Used by the "import from photo / files" flow inside the
 * app *and* by the share-intake flow ([me.nettrash.scan.MainActivity]
 * `ACTION_SEND` / `ACTION_SEND_MULTIPLE` filters added in 1.6).
 *
 * 1.6 additions:
 *  - PDF input via [decodePdf]: walks every page through `PdfRenderer`,
 *    rasterises at 2× density, runs the existing image path on each.
 *  - Multi-input batch via [decodeBatch]: aggregates results across N
 *    URIs (mixed images and PDFs), de-duplicating on `value`.
 */
object ImageDecoder {

    sealed class DecodeError(message: String) : Exception(message) {
        data object LoadFailed : DecodeError("Couldn't read that file.")
        data object NoBarcodeFound : DecodeError("No barcodes were found.")
        class MlKitFailed(cause: Throwable) :
            DecodeError("Couldn't analyze the image: ${cause.message}")
    }

    /** Decode a single image from a content `Uri`. Caller already
     *  obtained read permission via the share intent's grant or the
     *  Photo Picker's auto-grant. */
    suspend fun decode(context: Context, uri: Uri): List<ScannedCode> {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: throw DecodeError.LoadFailed
        return decode(bytes)
    }

    /** Decode raw image bytes. */
    suspend fun decode(bytes: ByteArray): List<ScannedCode> {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw DecodeError.LoadFailed
        val codes = decode(bitmap)
        if (codes.isEmpty()) throw DecodeError.NoBarcodeFound
        return codes
    }

    /** Decode a single Bitmap. Internal helper — does *not* throw on
     *  empty results so the caller can decide whether "nothing on
     *  this page" is fatal (single-image: yes; PDF page: no). */
    private suspend fun decode(bitmap: Bitmap): List<ScannedCode> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val scanner = BarcodeScanning.getClient()

        val barcodes: List<Barcode> = suspendCancellableCoroutine { cont ->
            scanner.process(image)
                .addOnSuccessListener { result -> cont.resume(result) }
                .addOnFailureListener { e -> cont.resumeWithException(DecodeError.MlKitFailed(e)) }
        }

        return barcodes.mapNotNull { barcode ->
            val raw = barcode.rawValue ?: barcode.displayValue ?: return@mapNotNull null
            if (raw.isEmpty()) return@mapNotNull null
            ScannedCode(
                value = raw,
                symbology = Symbology.fromMlKit(barcode.format),
                timestampMillis = System.currentTimeMillis(),
                previewRect = null
            )
        }
    }

    /** Decode every page of a PDF served via a content `Uri`. Each
     *  page is rasterised at 2× the page's natural point size — high
     *  enough that micro-QR / dense Aztec codes print-resolution-sized
     *  inside boarding-pass PDFs are still recognisable, low enough
     *  that a 20-page PDF doesn't OOM a low-end device. Results are
     *  flattened across pages and de-duplicated on `value`. */
    suspend fun decodePdf(context: Context, uri: Uri): List<ScannedCode> {
        // PdfRenderer requires a seekable file descriptor, which a
        // content:// URI doesn't always provide directly — copy the
        // bytes into a temp file first if openFileDescriptor returns
        // a non-seekable PFD (typical for cloud-storage URIs).
        val pfd = openSeekablePfd(context, uri) ?: throw DecodeError.LoadFailed
        val codes = mutableListOf<ScannedCode>()
        val seen = HashSet<String>()
        try {
            PdfRenderer(pfd).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val scale = 2
                        val bitmap = Bitmap.createBitmap(
                            page.width * scale,
                            page.height * scale,
                            Bitmap.Config.ARGB_8888,
                        )
                        // PdfRenderer doesn't paint a background — fill
                        // white first so dark scanners don't mis-read the
                        // empty alpha as black ink.
                        bitmap.eraseColor(Color.WHITE)
                        page.render(
                            bitmap, null, null,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                        )
                        val pageCodes = runCatching { decode(bitmap) }.getOrDefault(emptyList())
                        for (c in pageCodes) {
                            if (seen.add(c.value)) codes += c
                        }
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            runCatching { pfd.close() }
        }
        if (codes.isEmpty()) throw DecodeError.NoBarcodeFound
        return codes
    }

    /** Decode N inputs (images and/or PDFs) and aggregate. Each URI's
     *  MIME type comes from the system content resolver. Failures
     *  on individual entries are *swallowed* so one bad input doesn't
     *  poison the whole batch — the caller sees at most one
     *  [DecodeError.NoBarcodeFound] when nothing was readable across
     *  the whole list. */
    suspend fun decodeBatch(context: Context, uris: List<Uri>): List<ScannedCode> {
        val codes = mutableListOf<ScannedCode>()
        val seen = HashSet<String>()
        for (uri in uris) {
            val mime = context.contentResolver.getType(uri).orEmpty()
            val outcome = runCatching {
                if (mime == "application/pdf") decodePdf(context, uri)
                else decode(context, uri)
            }
            outcome.onFailure { e ->
                android.util.Log.w(
                    "ImageDecoder",
                    "decode failed for $uri (mime=$mime): ${e.message}"
                )
            }
            val partial = outcome.getOrDefault(emptyList())
            android.util.Log.i(
                "ImageDecoder",
                "decoded ${partial.size} from $uri (mime=$mime)"
            )
            for (c in partial) if (seen.add(c.value)) codes += c
        }
        if (codes.isEmpty()) throw DecodeError.NoBarcodeFound
        return codes
    }

    /** Open a [ParcelFileDescriptor] suitable for [PdfRenderer]. PFDs
     *  from `content://` providers backed by network storage are
     *  occasionally non-seekable; in that case we copy the bytes
     *  into the cache directory and reopen the local file. */
    private fun openSeekablePfd(context: Context, uri: Uri): ParcelFileDescriptor? {
        val direct = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")
        }.getOrNull()
        if (direct != null && direct.statSize >= 0) return direct
        // Fall back to a local copy.
        runCatching { direct?.close() }

        val cacheDir = File(context.cacheDir, "share-intake").apply { mkdirs() }
        val target = File(cacheDir, "share-${System.currentTimeMillis()}.pdf")
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } != null
        }.getOrDefault(false)
        if (!ok) return null
        return runCatching {
            ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull()
    }
}
