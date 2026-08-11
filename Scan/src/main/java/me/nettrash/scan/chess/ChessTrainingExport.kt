package me.nettrash.scan.chess

import android.graphics.Bitmap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Saving a rectified board plus its true position, as fine-tuning data.
 * Port of `Scan/Chess/ChessTrainingExport.swift`.
 *
 * ## Why the app collects its own training data
 *
 * `chess-vision` trains on synthetic boards, and its model card is
 * explicit about where that distribution falls short: procedural piece
 * silhouettes rather than real sets, no cast shadows between pieces, blob
 * occluders rather than hands. Closing those gaps needs photographs of
 * real boards — and the most valuable ones come through *this* camera and
 * *this* warp, because that is the distribution the model meets at
 * inference.
 *
 * The chicken-and-egg is broken by manual corner placement: the user
 * supplies what `BoardNet` would, the warp runs, and the position they
 * name in the editor is the label. No model required to produce data for
 * training the model.
 *
 * ## What is written
 *
 * Two files per sample, `<timestamp>-<fen>.jpg` and `.json`. The FEN is in
 * the filename as well as the JSON so a directory listing is browsable and
 * a mislabelled sample can be spotted and deleted without opening
 * anything.
 *
 * ## Off by default, and hidden
 *
 * This builds a training set; it is not a user feature. The directory is a
 * constructor parameter rather than a hard-coded path so the whole thing
 * is testable on the JVM without a device.
 */
class ChessTrainingExport(private val directory: File) {

    /**
     * How the corners were obtained. Recorded because a hand-placed corner
     * and a predicted one have very different error characteristics, and a
     * fine-tune wants to know which it is looking at.
     */
    enum class CornerSource { MANUAL, PREDICTED, PREDICTED_THEN_ADJUSTED }

    @Serializable
    data class Sample(
        /**
         * 64 class labels in LERF order, a1 first — the scheme the models
         * are trained against (`0` empty, else `1 + piece index`).
         */
        val labels: List<Int>,
        val placementFen: String,
        /**
         * Board corners in source-image pixels, board order, so the
         * original can be re-warped later with a different margin if the
         * pipeline changes.
         */
        val corners: List<List<Float>>,
        val cornerSource: String,
        val sourceSize: List<Int>,
        val capturedAt: String,
        val appVersion: String,
    )

    private val json = Json { prettyPrint = true }

    /** Write one sample; returns the JPEG. */
    fun write(
        warped: Bitmap,
        position: ChessPosition,
        corners: List<ChessPoint>,
        cornerSource: CornerSource,
        sourceWidth: Int,
        sourceHeight: Int,
        appVersion: String,
        now: Date = Date(),
    ): File {
        directory.mkdirs()

        val fen = position.placementFen
        val stem = "${now.time / 1000}-${fen.replace('/', '-')}"

        val imageFile = File(directory, "$stem.jpg")
        imageFile.outputStream().use {
            // 95 rather than lossless: the model sees camera JPEGs at
            // inference, so training on perfect crops would be a small
            // domain mismatch in the wrong direction.
            warped.compress(Bitmap.CompressFormat.JPEG, 95, it)
        }

        val sample = Sample(
            labels = position.placement.map { piece ->
                piece?.let { ChessPositionDecoder.labelFor(it) } ?: 0
            },
            placementFen = fen,
            corners = corners.map { listOf(it.x, it.y) },
            cornerSource = cornerSource.name,
            sourceSize = listOf(sourceWidth, sourceHeight),
            capturedAt = ISO8601.format(now),
            appVersion = appVersion,
        )
        File(directory, "$stem.json").writeText(json.encodeToString(sample))
        return imageFile
    }

    /**
     * How many samples have been collected. Shown in the debug UI so the
     * count toward a usable fine-tune set is visible.
     */
    fun sampleCount(): Int =
        directory.listFiles()?.count { it.extension == "json" } ?: 0

    fun removeAll() {
        directory.deleteRecursively()
    }

    companion object {
        private val ISO8601 =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

        /** Where samples land in the installed app. */
        fun defaultDirectory(filesDir: File) = File(filesDir, "ChessTraining")
    }
}
