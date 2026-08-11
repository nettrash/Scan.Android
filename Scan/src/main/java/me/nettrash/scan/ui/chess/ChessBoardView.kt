package me.nettrash.scan.ui.chess

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import me.nettrash.scan.chess.ChessColor
import me.nettrash.scan.chess.ChessPiece
import me.nettrash.scan.chess.ChessPieceKind
import me.nettrash.scan.chess.ChessSquare

/**
 * Draws a position, optionally with a move arrow, optionally editable.
 * Compose counterpart of the iOS app's `ChessBoardView.swift`.
 *
 * ## Pieces are the *solid* Unicode glyphs, tinted
 *
 * Two traps here, both of which produce a board that looks almost right:
 *
 * 1. **Outline vs solid.** U+2654-2659 are nominally the "white" pieces
 *    and U+265A-265F the "black" ones, but that names ink, not chess: the
 *    first set are *outline* drawings. Used literally, a white king is a
 *    thin outline that disappears on a light square. So both colours are
 *    drawn from the solid set and tinted.
 * 2. **U+265F is also an emoji** — ♟️. Without the text-presentation
 *    selector the platform renders it in *colour*, which ignores the tint
 *    entirely, so every white pawn comes out black while the rooks and
 *    queens tint correctly. Every glyph therefore carries **U+FE0E**.
 *
 * The second one bit on iOS and would have bitten identically here;
 * Android's emoji font resolves U+265F the same way.
 *
 * Board orientation is a display concern only: [whiteAtBottom] flips
 * which squares are drawn where. The underlying position is always LERF
 * and never mirrors — [layoutFor] is the one place the two conventions
 * meet.
 */

/** One square's visual state beyond the piece standing on it. */
data class ChessSquareDecoration(
    /** Ring the square to flag a low-confidence recognition. */
    val isUncertain: Boolean = false,
    /** Highlight as the current editing target. */
    val isSelected: Boolean = false,
    /** Tint as part of the suggested move. */
    val isMoveEndpoint: Boolean = false,
)

private val LIGHT_SQUARE = Color(0xFFEDE3CC)
private val DARK_SQUARE = Color(0xFF8C7054)

@Composable
fun ChessBoardView(
    placement: Array<ChessPiece?>,
    modifier: Modifier = Modifier,
    whiteAtBottom: Boolean = true,
    arrow: Pair<ChessSquare, ChessSquare>? = null,
    decorations: Map<Int, ChessSquareDecoration> = emptyMap(),
    accentColor: Color = Color(0xFF2B7FFF),
    onTap: ((ChessSquare) -> Unit)? = null,
) {
    val textMeasurer = rememberTextMeasurer()

    // The square size is derived inside each scope from its own pixel
    // size rather than measured up front with BoxWithConstraints. That
    // keeps the two consumers — drawing and hit testing — reading the
    // *same* geometry, so a tap can never land on a different square from
    // the one under the finger.
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .then(
                if (onTap == null) Modifier
                else Modifier.pointerInput(whiteAtBottom) {
                    detectTapGestures { offset ->
                        val square = size.width / 8f
                        val column = (offset.x / square).toInt().coerceIn(0, 7)
                        val row = (offset.y / square).toInt().coerceIn(0, 7)
                        onTap(squareAt(row, column, whiteAtBottom))
                    }
                }
            )
            .semantics {
                contentDescription = boardDescription(placement)
            },
    ) {
        val square = size.minDimension / 8f
        drawSquares(placement, whiteAtBottom, decorations, square, accentColor)
        drawPieces(placement, whiteAtBottom, square, textMeasurer)
        arrow?.let { (from, to) ->
            drawMoveArrow(
                centreOf(layoutFor(from, whiteAtBottom), square),
                centreOf(layoutFor(to, whiteAtBottom), square),
                square,
                accentColor,
            )
        }
    }
}

// MARK: - Layers

private fun DrawScope.drawSquares(
    placement: Array<ChessPiece?>,
    whiteAtBottom: Boolean,
    decorations: Map<Int, ChessSquareDecoration>,
    square: Float,
    accent: Color,
) {
    for (index in 0 until 64) {
        val chessSquare = ChessSquare(index)
        val (row, column) = layoutFor(chessSquare, whiteAtBottom)
        val origin = Offset(column * square, row * square)
        val size = Size(square, square)

        drawRect(
            color = if (chessSquare.isLight) LIGHT_SQUARE else DARK_SQUARE,
            topLeft = origin,
            size = size,
        )

        val decoration = decorations[index] ?: continue
        if (decoration.isMoveEndpoint) {
            drawRect(accent.copy(alpha = 0.35f), topLeft = origin, size = size)
        }
        if (decoration.isSelected) {
            drawRect(accent, topLeft = origin, size = size,
                style = Stroke(width = square * 0.07f))
        }
        if (decoration.isUncertain) {
            // A ring rather than a fill: it has to survive being drawn
            // over a piece.
            drawRect(Color(0xFFFF9500), topLeft = origin, size = size,
                style = Stroke(width = square * 0.06f))
        }
    }
}

private fun DrawScope.drawPieces(
    placement: Array<ChessPiece?>,
    whiteAtBottom: Boolean,
    square: Float,
    textMeasurer: TextMeasurer,
) {
    for (index in 0 until 64) {
        val piece = placement[index] ?: continue
        val (row, column) = layoutFor(ChessSquare(index), whiteAtBottom)
        val isWhite = piece.color == ChessColor.WHITE

        val style = TextStyle(
            fontSize = (square * 0.76f / density).sp,
            color = if (isWhite) Color.White else Color.Black,
        )
        val measured = textMeasurer.measure(glyphFor(piece.kind), style)

        val x = column * square + (square - measured.size.width) / 2f
        val y = row * square + (square - measured.size.height) / 2f

        // Rim in the opposite tone, faked by drawing the glyph four times
        // offset by a pixel. Compose has no text stroke, and without a rim
        // a white piece on a light square loses its silhouette entirely.
        val rim = if (isWhite) Color.Black.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.7f)
        val rimStyle = style.copy(color = rim)
        val nudge = (square * 0.018f).coerceAtLeast(1f)
        for (dx in listOf(-nudge, nudge)) {
            for (dy in listOf(-nudge, nudge)) {
                drawText(textMeasurer, glyphFor(piece.kind), Offset(x + dx, y + dy), rimStyle)
            }
        }
        drawText(textMeasurer, glyphFor(piece.kind), Offset(x, y), style)
    }
}

/**
 * The suggested move, drawn over the board.
 *
 * A tapered arrow rather than a plain line: at board sizes on a phone a
 * uniform stroke reads as a highlight, not a direction, and the whole
 * point is showing *which way* the piece goes.
 */
private fun DrawScope.drawMoveArrow(start: Offset, end: Offset, square: Float, accent: Color) {
    val angle = atan2(end.y - start.y, end.x - start.x)
    val headLength = square * 0.44f
    val headWidth = square * 0.34f
    val shaftWidth = square * 0.13f

    // Stop the shaft short so the head's point lands on the destination
    // square's centre rather than past it.
    val shaftEnd = Offset(end.x - cos(angle) * headLength, end.y - sin(angle) * headLength)
    val normal = Offset(-sin(angle), cos(angle))
    fun offset(point: Offset, distance: Float) =
        Offset(point.x + normal.x * distance, point.y + normal.y * distance)

    val path = Path().apply {
        moveTo(offset(start, shaftWidth / 2).x, offset(start, shaftWidth / 2).y)
        lineTo(offset(shaftEnd, shaftWidth / 2).x, offset(shaftEnd, shaftWidth / 2).y)
        lineTo(offset(shaftEnd, headWidth / 2).x, offset(shaftEnd, headWidth / 2).y)
        lineTo(end.x, end.y)
        lineTo(offset(shaftEnd, -headWidth / 2).x, offset(shaftEnd, -headWidth / 2).y)
        lineTo(offset(shaftEnd, -shaftWidth / 2).x, offset(shaftEnd, -shaftWidth / 2).y)
        lineTo(offset(start, -shaftWidth / 2).x, offset(start, -shaftWidth / 2).y)
        close()
    }
    drawPath(path, accent.copy(alpha = 0.85f))
}

// MARK: - Geometry

/**
 * LERF square -> the grid cell it occupies on screen.
 *
 * The single place the board's storage order and the screen's top-left
 * origin meet. Everything else — arrows, decorations, taps — routes
 * through it, so flipping the board is one change.
 */
private fun layoutFor(square: ChessSquare, whiteAtBottom: Boolean): Pair<Int, Int> =
    if (whiteAtBottom) (7 - square.rank) to square.file
    else square.rank to (7 - square.file)

/** Inverse of [layoutFor], for hit testing. */
private fun squareAt(row: Int, column: Int, whiteAtBottom: Boolean): ChessSquare =
    if (whiteAtBottom) ChessSquare.of(column, 7 - row) else ChessSquare.of(7 - column, row)

private fun centreOf(cell: Pair<Int, Int>, square: Float) =
    Offset((cell.second + 0.5f) * square, (cell.first + 0.5f) * square)

/**
 * The solid Unicode glyph for a kind, with the text-presentation selector
 * — see the file header for why U+FE0E is load-bearing.
 *
 * Applied to all six rather than just the pawn, so a future Unicode
 * revision promoting another of these to emoji cannot reintroduce the bug.
 */
fun glyphFor(kind: ChessPieceKind): String {
    // Escapes rather than literal glyphs: the pieces and the variation
    // selector are both invisible-or-ambiguous in a source listing, and
    // U+FE0E in particular renders as nothing at all.
    val base = when (kind) {
        ChessPieceKind.KING -> "♚"
        ChessPieceKind.QUEEN -> "♛"
        ChessPieceKind.ROOK -> "♜"
        ChessPieceKind.BISHOP -> "♝"
        ChessPieceKind.KNIGHT -> "♞"
        ChessPieceKind.PAWN -> "♟"
    }
    return base + "︎"
}

/** Whole-board description for TalkBack, since the Canvas is one node. */
private fun boardDescription(placement: Array<ChessPiece?>): String {
    val occupied = (0 until 64).mapNotNull { index ->
        placement[index]?.let {
            "${ChessSquare(index).algebraic} ${it.color.displayName} ${it.kind.displayName}"
        }
    }
    return if (occupied.isEmpty()) "Empty chess board"
    else "Chess board: " + occupied.joinToString(", ")
}

/** Spoken and written name, for TalkBack and the piece palette. */
val ChessPieceKind.displayName: String
    get() = when (this) {
        ChessPieceKind.PAWN -> "pawn"
        ChessPieceKind.KNIGHT -> "knight"
        ChessPieceKind.BISHOP -> "bishop"
        ChessPieceKind.ROOK -> "rook"
        ChessPieceKind.QUEEN -> "queen"
        ChessPieceKind.KING -> "king"
    }
