package me.nettrash.scan.chess

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Date
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Writing a rectified board plus its position as a fine-tuning sample.
 *
 * This is the path that breaks the chicken-and-egg: with corners placed by
 * hand, the app produces labelled training data before either model
 * exists. A bug here does not crash — it shows up months later as a
 * training set that cannot be used. So the parts pinned are the ones a
 * reader would otherwise have to trust: labels round-tripping to the same
 * position, the schema `chess-vision` expects, and files actually on disk.
 *
 * Robolectric, unlike the rest of the chess tests, because writing a JPEG
 * needs a real `Bitmap.compress`. The export takes its directory as a
 * constructor parameter precisely so nothing else here needs a device.
 */
@RunWith(RobolectricTestRunner::class)
class ChessTrainingExportTest {

    private lateinit var directory: File
    private lateinit var export: ChessTrainingExport

    private val corners = listOf(
        ChessPoint(184f, 271f), ChessPoint(783f, 282f),
        ChessPoint(864f, 772f), ChessPoint(160f, 750f),
    )

    // Robolectric shadows Bitmap, so the real factory works here.
    private fun board() =
        android.graphics.Bitmap.createBitmap(288, 288, android.graphics.Bitmap.Config.ARGB_8888)

    @Before fun setUp() {
        directory = File.createTempFile("chess-training", "").let {
            it.delete(); it.mkdirs(); it
        }
        export = ChessTrainingExport(directory)
    }

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    private fun writeSample(
        position: ChessPosition = ChessPosition.standard(),
        source: ChessTrainingExport.CornerSource = ChessTrainingExport.CornerSource.MANUAL,
        now: Date = Date(),
    ) = export.write(
        warped = board(),
        position = position,
        corners = corners,
        cornerSource = source,
        sourceWidth = 1024,
        sourceHeight = 768,
        appVersion = "1.10",
        now = now,
    )

    private fun sampleFor(image: File): ChessTrainingExport.Sample =
        Json.decodeFromString(File(image.parentFile, "${image.nameWithoutExtension}.json").readText())

    @Test fun writingASampleProducesAnImageAndItsLabels() {
        val image = writeSample()
        assertThat(image.exists()).isTrue()
        assertThat(File(directory, "${image.nameWithoutExtension}.json").exists()).isTrue()
        assertThat(export.sampleCount()).isEqualTo(1)
    }

    /**
     * The labels must reconstruct the exact position they were made from.
     * This is the whole value of a sample; if it does not hold, the set is
     * training the model on the wrong answer.
     */
    @Test fun labelsRoundTripToTheSamePosition() {
        val original = ChessPosition.fromFen(
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
        )!!
        val sample = sampleFor(writeSample(position = original))

        assertThat(sample.labels).hasSize(64)
        assertThat(sample.placementFen).isEqualTo(original.placementFen)

        val rebuilt = ChessPosition(
            placement = Array(64) { ChessPositionDecoder.pieceFor(sample.labels[it]) }
        )
        assertThat(rebuilt.placementFen).isEqualTo(original.placementFen)
    }

    /**
     * The label scheme is the contract with `chess-vision`: `0` empty,
     * otherwise `1 + ChessPiece.index`. A silent change here would be
     * discovered only after a training run.
     */
    @Test fun labelSchemeMatchesTheModels() {
        val sample = sampleFor(writeSample())
        // a1 is a white rook: kind rook (3) -> index 3 -> label 4.
        assertThat(sample.labels[ChessSquare.A1.index]).isEqualTo(4)
        // e8 is a black king: index 11 -> label 12.
        assertThat(sample.labels[ChessSquare.E8.index]).isEqualTo(12)
        assertThat(sample.labels[ChessSquare.parse("e4")!!.index]).isEqualTo(0)
    }

    @Test fun cornersAndProvenanceAreRecorded() {
        val sample = sampleFor(
            writeSample(source = ChessTrainingExport.CornerSource.PREDICTED_THEN_ADJUSTED)
        )
        assertThat(sample.corners).hasSize(4)
        assertThat(sample.corners[0][0]).isWithin(1e-3f).of(184f)
        assertThat(sample.cornerSource).isEqualTo("PREDICTED_THEN_ADJUSTED")
        assertThat(sample.sourceSize).containsExactly(1024, 768).inOrder()
    }

    /**
     * The FEN is in the filename so a directory listing is browsable. The
     * slashes have to go — they are path separators.
     */
    @Test fun filenameCarriesTheFenWithoutSlashes() {
        val name = writeSample().name
        assertThat(name).contains("rnbqkbnr-pppppppp")
        assertThat(name).doesNotContain("/")
    }

    @Test fun samplesAccumulate() {
        // The stem is second-resolution, so distinct writes need distinct
        // timestamps or they overwrite — which is itself worth knowing.
        for (offset in 0 until 3) {
            writeSample(now = Date(1_700_000_000_000L + offset * 1000L))
        }
        assertThat(export.sampleCount()).isEqualTo(3)
    }

    @Test fun removeAllClearsTheDirectory() {
        writeSample()
        assertThat(export.sampleCount()).isEqualTo(1)
        export.removeAll()
        assertThat(export.sampleCount()).isEqualTo(0)
    }
}
