package me.nettrash.scan.chess

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.abs

/**
 * A point in image pixels, origin top-left.
 *
 * Deliberately **not** `android.graphics.PointF`. The maths below is the
 * part worth unit-testing, and these tests run on the plain JVM; with the
 * project's `isReturnDefaultValues = true`, constructing a real `PointF`
 * against the stub `android.jar` silently yields (0, 0) rather than
 * throwing, so the tests would pass while measuring nothing. Compose uses
 * its own `Offset`, so nothing actually wanted `PointF`.
 */
data class ChessPoint(val x: Float, val y: Float)

/**
 * Rectifying a photographed board into the square image `PieceNet` reads.
 *
 * Port of `Scan/Chess/ChessBoardWarp.swift` and, behind it,
 * `chess-vision/src/chess_vision/preprocess.py` — which is the reference
 * and is tested there. All three must agree: this is the step between the
 * two models, and a warp that differs by half a square between training
 * and inference feeds the model something it never saw.
 *
 * ## The margin, and why it is not zero
 *
 * The obvious warp maps the playing surface onto the output exactly. That
 * is wrong for a photographed board, because a standing piece is *taller
 * than its square*: a king on e8 seen from a low angle occupies a tall
 * sliver of image extending well past the surface's far edge. Warp the
 * surface exactly and every back-rank piece is decapitated.
 *
 * So the crop is expanded by [MARGIN_SQUARES] on each side, and the model
 * is trained on that framing. The output is therefore **not** an 8x8 grid:
 * it is a (8 + 2*margin) square region with the surface inset, which is
 * what [cellBounds] exists to account for.
 *
 * ## Why the homography is computed here rather than by `setPolyToPoly`
 *
 * Android's [Matrix] *is* a 3x3 projective matrix, and [Matrix.setValues]
 * takes exactly the nine row-major coefficients this file solves for. So
 * the maths stays in plain Kotlin — unit-testable on the JVM with no
 * device and no Robolectric — and only the final pixel resample touches
 * the framework. `setPolyToPoly` would work too, but it would move the
 * one part worth testing inside an API that a unit test cannot reach.
 *
 * Unlike Core Image on iOS, Android bitmaps are **origin top-left**, which
 * is the same convention corners arrive in. No flip is needed here; the
 * Swift port's `flipped(_:in:)` has no counterpart.
 */
object ChessBoardWarp {

    /** Must match `chess_vision.preprocess.MARGIN_SQUARES`. */
    const val MARGIN_SQUARES = 1.0f

    /** The resolution `PieceNet` consumes. Must match `models/nets.py`. */
    const val PIECENET_INPUT = 288

    /**
     * Where the playing surface's corners land in the warped output.
     *
     * With a one-square margin the board occupies 8/10 of the output,
     * inset by 1/10. Board order (a8, h8, h1, a1), origin top-left.
     */
    fun outputCorners(size: Int = PIECENET_INPUT, margin: Float = MARGIN_SQUARES): List<ChessPoint> {
        val span = 8.0f + 2.0f * margin
        val low = margin / span * size
        val high = (margin + 8.0f) / span * size
        return listOf(
            ChessPoint(low, low),   // a8
            ChessPoint(high, low),  // h8
            ChessPoint(high, high), // h1
            ChessPoint(low, high),  // a1
        )
    }

    /**
     * Pixel bounds of one square's cell in the warped output, as
     * `(left, top, right, bottom)`.
     *
     * Board-order corners mean the warp always puts a8 at the output's
     * top-left, so [whiteAtBottom] is true for anything coming out of
     * [warp]. The parameter exists for callers laying out a board for
     * display, where orientation is a free choice.
     */
    fun cellBounds(
        square: ChessSquare,
        size: Int = PIECENET_INPUT,
        margin: Float = MARGIN_SQUARES,
        whiteAtBottom: Boolean = true,
    ): FloatArray {
        val row = if (whiteAtBottom) 7 - square.rank else square.rank
        val column = if (whiteAtBottom) square.file else 7 - square.file
        val span = 8.0f + 2.0f * margin
        val cell = size / span
        val left = (margin + column) * cell
        val top = (margin + row) * cell
        return floatArrayOf(left, top, left + cell, top + cell)
    }

    /**
     * Solve the 3x3 homography with `destination ~ H * source`, returning
     * the nine row-major coefficients, or null if degenerate.
     *
     * Direct linear transform: each correspondence contributes two rows of
     * an 8x8 system with `h33` fixed at 1. Four non-collinear points
     * determine it exactly.
     */
    fun homography(source: List<ChessPoint>, destination: List<ChessPoint>): FloatArray? {
        if (source.size != 4 || destination.size != 4) return null

        val a = DoubleArray(64)
        val b = DoubleArray(8)
        for (i in 0 until 4) {
            val x = source[i].x.toDouble()
            val y = source[i].y.toDouble()
            val u = destination[i].x.toDouble()
            val v = destination[i].y.toDouble()
            val r0 = 2 * i
            val r1 = 2 * i + 1
            a[r0 * 8 + 0] = x; a[r0 * 8 + 1] = y; a[r0 * 8 + 2] = 1.0
            a[r0 * 8 + 6] = -u * x; a[r0 * 8 + 7] = -u * y
            a[r1 * 8 + 3] = x; a[r1 * 8 + 4] = y; a[r1 * 8 + 5] = 1.0
            a[r1 * 8 + 6] = -v * x; a[r1 * 8 + 7] = -v * y
            b[r0] = u; b[r1] = v
        }

        val solution = solve(a, b, 8) ?: return null
        return FloatArray(9) { if (it < 8) solution[it].toFloat() else 1.0f }
    }

    /** Map a point through a homography, or null if it goes to infinity. */
    fun apply(h: FloatArray, point: ChessPoint): ChessPoint? {
        val x = point.x.toDouble()
        val y = point.y.toDouble()
        val w = h[6] * x + h[7] * y + h[8]
        if (abs(w) < 1e-12) return null
        return ChessPoint(
            ((h[0] * x + h[1] * y + h[2]) / w).toFloat(),
            ((h[3] * x + h[4] * y + h[5]) / w).toFloat(),
        )
    }

    /**
     * Gaussian elimination with partial pivoting.
     *
     * Eight unknowns — small enough that pulling in a linear-algebra
     * dependency would cost more in ceremony than it saves in cycles.
     */
    private fun solve(matrix: DoubleArray, rhs: DoubleArray, n: Int): DoubleArray? {
        val a = matrix.copyOf()
        val b = rhs.copyOf()

        for (column in 0 until n) {
            var pivot = column
            for (row in column + 1 until n) {
                if (abs(a[row * n + column]) > abs(a[pivot * n + column])) pivot = row
            }
            if (abs(a[pivot * n + column]) < 1e-12) return null
            if (pivot != column) {
                for (k in 0 until n) {
                    val tmp = a[column * n + k]
                    a[column * n + k] = a[pivot * n + k]
                    a[pivot * n + k] = tmp
                }
                val tmp = b[column]; b[column] = b[pivot]; b[pivot] = tmp
            }
            val diagonal = a[column * n + column]
            for (row in column + 1 until n) {
                val factor = a[row * n + column] / diagonal
                if (factor == 0.0) continue
                for (k in column until n) a[row * n + k] -= factor * a[column * n + k]
                b[row] -= factor * b[column]
            }
        }

        val solution = DoubleArray(n)
        for (row in n - 1 downTo 0) {
            var sum = b[row]
            for (k in row + 1 until n) sum -= a[row * n + k] * solution[k]
            solution[row] = sum / a[row * n + row]
        }
        return solution
    }

    /**
     * Rectify the board, with headroom for standing pieces.
     *
     * @param source the photograph.
     * @param corners the playing surface's four corners in **bitmap
     *   pixels, origin top-left**, board order (a8, h8, h1, a1).
     */
    fun warp(
        source: Bitmap,
        corners: List<ChessPoint>,
        size: Int = PIECENET_INPUT,
        margin: Float = MARGIN_SQUARES,
    ): Bitmap? {
        val h = homography(corners, outputCorners(size, margin)) ?: return null

        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val matrix = Matrix().apply { setValues(h) }
        Canvas(output).drawBitmap(
            source,
            matrix,
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        return output
    }

    /**
     * Are these four points usable as a board?
     *
     * Rejects the degenerate quads a drag gesture can produce — a
     * collapsed edge, and a **bow-tie** from crossing two corners. The
     * bow-tie matters: it has area, so an area check alone passes it, and
     * the warp then folds the image.
     */
    fun isUsableQuad(corners: List<ChessPoint>, minimumArea: Float = 100f): Boolean {
        if (corners.size != 4) return false
        if (area(corners) < minimumArea) return false
        return isConvex(corners)
    }

    /** Shoelace area, always positive. */
    fun area(corners: List<ChessPoint>): Float {
        var total = 0f
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            total += a.x * b.y - b.x * a.y
        }
        return abs(total) / 2f
    }

    private fun isConvex(corners: List<ChessPoint>): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val c = corners[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-6f) continue
            val current = if (cross > 0) 1 else -1
            if (sign == 0) sign = current else if (sign != current) return false
        }
        return sign != 0
    }

    /**
     * Four corners covering the middle of a frame — the starting handles
     * for manual placement, so the user drags rather than taps blind.
     */
    fun defaultCorners(width: Float, height: Float, inset: Float = 0.15f): List<ChessPoint> {
        val x0 = width * inset
        val x1 = width * (1 - inset)
        val y0 = height * inset
        val y1 = height * (1 - inset)
        return listOf(ChessPoint(x0, y0), ChessPoint(x1, y0), ChessPoint(x1, y1), ChessPoint(x0, y1))
    }
}
