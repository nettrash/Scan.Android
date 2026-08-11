package me.nettrash.scan.chess

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The warp between the two models — Kotlin third of a three-language port.
 *
 * `chess-vision/src/chess_vision/preprocess.py` is the reference;
 * `Scan/ScanTests/ChessBoardWarpTests.swift` is the Swift half. The
 * numbers pinned here are the same ones, so a drift in any one of the
 * three shows up as a failure rather than as a model quietly being fed
 * something it was never trained on.
 *
 * Runs on the plain JVM — no Robolectric — because the geometry uses
 * [ChessPoint] rather than `android.graphics.PointF`. Only [ChessBoardWarp.warp]
 * itself touches the framework, and that is a single `Canvas.drawBitmap`
 * over a matrix this file has already verified.
 */
class ChessBoardWarpTest {

    private val size = 288

    // MARK: - The margin

    @Test fun boardIsInsetByTheMargin() {
        val corners = ChessBoardWarp.outputCorners(size, margin = 1.0f)
        // 8 squares of board inside 10 squares of output: inset one tenth.
        assertThat(corners[0].x).isWithin(1e-4f).of(size * 0.1f)
        assertThat(corners[0].y).isWithin(1e-4f).of(size * 0.1f)
        assertThat(corners[2].x).isWithin(1e-4f).of(size * 0.9f)
        assertThat(corners[2].y).isWithin(1e-4f).of(size * 0.9f)
    }

    @Test fun zeroMarginFillsTheOutput() {
        val corners = ChessBoardWarp.outputCorners(size, margin = 0f)
        assertThat(corners[0].x).isWithin(1e-4f).of(0f)
        assertThat(corners[2].x).isWithin(1e-4f).of(size.toFloat())
    }

    @Test fun cellsTileTheBoardRegion() {
        val span = 8.0f + 2.0f * ChessBoardWarp.MARGIN_SQUARES
        val cell = size / span
        val a8 = ChessBoardWarp.cellBounds(ChessSquare.parse("a8")!!, size)
        val h1 = ChessBoardWarp.cellBounds(ChessSquare.parse("h1")!!, size)

        assertThat(a8[0]).isWithin(1e-3f).of(ChessBoardWarp.MARGIN_SQUARES * cell)
        assertThat(a8[1]).isWithin(1e-3f).of(ChessBoardWarp.MARGIN_SQUARES * cell)
        assertThat(h1[2]).isWithin(1e-3f).of((ChessBoardWarp.MARGIN_SQUARES + 8) * cell)
        assertThat(h1[3]).isWithin(1e-3f).of((ChessBoardWarp.MARGIN_SQUARES + 8) * cell)
    }

    @Test fun cellsAreContiguous() {
        val a8 = ChessBoardWarp.cellBounds(ChessSquare.parse("a8")!!, size)
        val b8 = ChessBoardWarp.cellBounds(ChessSquare.parse("b8")!!, size)
        assertWithMessage("no gap between adjacent files")
            .that(a8[2]).isWithin(1e-3f).of(b8[0])
        val a7 = ChessBoardWarp.cellBounds(ChessSquare.parse("a7")!!, size)
        assertWithMessage("no gap between adjacent ranks")
            .that(a8[3]).isWithin(1e-3f).of(a7[1])
    }

    // MARK: - Homography

    @Test fun homographyMapsItsCorrespondencesExactly() {
        val source = listOf(
            ChessPoint(0f, 0f), ChessPoint(1f, 0f),
            ChessPoint(1f, 1f), ChessPoint(0f, 1f),
        )
        val destination = listOf(
            ChessPoint(10f, 20f), ChessPoint(110f, 5f),
            ChessPoint(130f, 90f), ChessPoint(0f, 100f),
        )
        val h = ChessBoardWarp.homography(source, destination)
        assertThat(h).isNotNull()
        for (i in source.indices) {
            val mapped = ChessBoardWarp.apply(h!!, source[i])!!
            assertThat(mapped.x).isWithin(1e-2f).of(destination[i].x)
            assertThat(mapped.y).isWithin(1e-2f).of(destination[i].y)
        }
    }

    @Test fun homographyComposesWithItsInverse() {
        val unit = listOf(
            ChessPoint(0f, 0f), ChessPoint(1f, 0f),
            ChessPoint(1f, 1f), ChessPoint(0f, 1f),
        )
        val quad = listOf(
            ChessPoint(3f, 7f), ChessPoint(200f, 11f),
            ChessPoint(190f, 150f), ChessPoint(-5f, 160f),
        )
        val forward = ChessBoardWarp.homography(unit, quad)!!
        val back = ChessBoardWarp.homography(quad, unit)!!

        for (point in listOf(ChessPoint(0.25f, 0.5f), ChessPoint(0.75f, 0.125f))) {
            val round = ChessBoardWarp.apply(back, ChessBoardWarp.apply(forward, point)!!)!!
            assertThat(round.x).isWithin(1e-3f).of(point.x)
            assertThat(round.y).isWithin(1e-3f).of(point.y)
        }
    }

    /**
     * A board-order quad maps a8 to the output's top-left. This is what
     * makes orientation fall out of the corner ordering, so it is worth
     * asserting directly rather than inferring.
     */
    @Test fun aBoardOrderQuadPutsA8TopLeft() {
        // A board photographed from Black's side: a8 is nearest the
        // camera, bottom-right in the image.
        val corners = listOf(
            ChessPoint(340f, 330f), // a8
            ChessPoint(60f, 322f),  // h8
            ChessPoint(90f, 70f),   // h1
            ChessPoint(310f, 74f),  // a1
        )
        val h = ChessBoardWarp.homography(corners, ChessBoardWarp.outputCorners(size))!!
        val a8 = ChessBoardWarp.apply(h, corners[0])!!
        val expected = ChessBoardWarp.outputCorners(size)[0]
        assertThat(a8.x).isWithin(1e-2f).of(expected.x)
        assertThat(a8.y).isWithin(1e-2f).of(expected.y)
    }

    @Test fun degenerateCorrespondencesAreRejected() {
        val unit = listOf(
            ChessPoint(0f, 0f), ChessPoint(1f, 0f),
            ChessPoint(1f, 1f), ChessPoint(0f, 1f),
        )
        val collinear = listOf(
            ChessPoint(0f, 0f), ChessPoint(1f, 1f),
            ChessPoint(2f, 2f), ChessPoint(3f, 3f),
        )
        assertThat(ChessBoardWarp.homography(unit, collinear)).isNull()
    }

    // MARK: - Quad validation

    @Test fun usableQuadAcceptsASensibleBoard() {
        assertThat(
            ChessBoardWarp.isUsableQuad(
                listOf(
                    ChessPoint(10f, 10f), ChessPoint(110f, 12f),
                    ChessPoint(115f, 108f), ChessPoint(8f, 105f),
                )
            )
        ).isTrue()
    }

    /**
     * A drag that crosses two corners makes a bow-tie. It has area, so an
     * area check alone passes it, and the warp then folds the image.
     */
    @Test fun usableQuadRejectsABowTie() {
        assertThat(
            ChessBoardWarp.isUsableQuad(
                listOf(
                    ChessPoint(0f, 0f), ChessPoint(100f, 0f),
                    ChessPoint(0f, 100f), ChessPoint(100f, 100f),
                )
            )
        ).isFalse()
    }

    @Test fun usableQuadRejectsACollapsedBoard() {
        assertThat(
            ChessBoardWarp.isUsableQuad(
                listOf(
                    ChessPoint(0f, 0f), ChessPoint(3f, 0f),
                    ChessPoint(3f, 3f), ChessPoint(0f, 3f),
                )
            )
        ).isFalse()
    }

    @Test fun areaIsOrientationIndependent() {
        val square = listOf(
            ChessPoint(0f, 0f), ChessPoint(10f, 0f),
            ChessPoint(10f, 10f), ChessPoint(0f, 10f),
        )
        assertThat(ChessBoardWarp.area(square)).isWithin(1e-3f).of(100f)
        assertThat(ChessBoardWarp.area(square.reversed())).isWithin(1e-3f).of(100f)
    }

    @Test fun defaultCornersAreUsable() {
        assertThat(ChessBoardWarp.isUsableQuad(ChessBoardWarp.defaultCorners(400f, 300f))).isTrue()
    }

    // MARK: - Cross-port parity

    /**
     * The same correspondence solved on all three platforms must give the
     * same mapping. Values taken from the Swift and Python implementations.
     *
     * Checked as *mapped points* rather than raw coefficients: a
     * homography is only defined up to scale, so comparing the nine
     * numbers directly would fail on a harmless normalisation difference
     * while missing a real one.
     */
    @Test fun mappingMatchesTheOtherPorts() {
        val source = listOf(
            ChessPoint(184f, 271f), ChessPoint(783f, 282f),
            ChessPoint(864f, 772f), ChessPoint(160f, 750f),
        )
        val h = ChessBoardWarp.homography(source, ChessBoardWarp.outputCorners(288))!!

        // Each board corner must land on its output corner.
        val expected = ChessBoardWarp.outputCorners(288)
        for (i in source.indices) {
            val mapped = ChessBoardWarp.apply(h, source[i])!!
            assertWithMessage("corner %s", i).that(mapped.x).isWithin(1e-2f).of(expected[i].x)
            assertWithMessage("corner %s", i).that(mapped.y).isWithin(1e-2f).of(expected[i].y)
        }

        // And the quad's centroid must land in the middle of the board
        // region — a check the corner assertions alone cannot make, since
        // they say nothing about the interior.
        val centroid = ChessPoint(
            source.map { it.x }.average().toFloat(),
            source.map { it.y }.average().toFloat(),
        )
        val mappedCentre = ChessBoardWarp.apply(h, centroid)!!
        assertThat(mappedCentre.x).isWithin(20f).of(144f)
        assertThat(mappedCentre.y).isWithin(20f).of(144f)
    }
}
