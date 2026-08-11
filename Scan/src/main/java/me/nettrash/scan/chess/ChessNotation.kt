package me.nettrash.scan.chess

/**
 * Standard Algebraic Notation — "Nf3", "exd5", "O-O", "Qxh7#". Port of
 * `Scan/Chess/ChessNotation.swift`.
 *
 * The engine speaks UCI internally ("g1f3") because it is unambiguous
 * without a position. SAN is what a person reads, so every move the UI
 * shows goes through here.
 *
 * SAN cannot be produced from a move alone: "Nf3" is only correct if no
 * *other* knight could also reach f3, and the check/mate suffix depends on
 * the resulting position. So each function takes the position the move is
 * played in.
 */

private const val FILES = "abcdefgh"

/**
 * SAN for [move], which must be legal in this position.
 *
 * Follows the usual rules: piece letter (omitted for pawns), the minimum
 * disambiguation needed, "x" for captures, the destination, "=Q" for
 * promotions, and "+" / "#" for check and mate.
 */
fun ChessPosition.san(move: ChessMove): String {
    val moving = pieceAt(move.fromIndex) ?: return move.uci

    if (move.isCastle) {
        return decorate(move, if (move.flagBits == ChessMove.CASTLE_KINGSIDE) "O-O" else "O-O-O")
    }

    val sb = StringBuilder()
    val destination = ChessSquare(move.toIndex).algebraic

    if (moving.kind == ChessPieceKind.PAWN) {
        // A capturing pawn is identified by its file: "exd5".
        if (move.isCapture) sb.append(FILES[move.fromIndex and 7]).append('x')
        sb.append(destination)
        move.promotionKind?.let { sb.append('=').append(it.fenLetter) }
    } else {
        sb.append(moving.kind.fenLetter)
        sb.append(disambiguation(move, moving))
        if (move.isCapture) sb.append('x')
        sb.append(destination)
    }

    return decorate(move, sb.toString())
}

/**
 * The smallest file/rank hint that identifies which piece moved.
 *
 * Standard precedence: if the origin file alone is unique use that, else
 * the rank, else both. The candidates considered are only *legal* movers —
 * a pinned knight cannot reach the square, so it does not create ambiguity
 * and must not force a hint.
 */
private fun ChessPosition.disambiguation(move: ChessMove, moving: ChessPiece): String {
    val rivals = mutableListOf<Int>()
    val legal = ChessMoveGenerator.generateLegal(copy())
    for (index in 0 until legal.count) {
        val other = legal[index]
        if (other.toIndex != move.toIndex) continue
        if (other.fromIndex == move.fromIndex) continue
        if (pieceAt(other.fromIndex) != moving) continue
        rivals.add(other.fromIndex)
    }
    if (rivals.isEmpty()) return ""

    val fromFile = move.fromIndex and 7
    val fromRank = move.fromIndex shr 3

    if (rivals.none { (it and 7) == fromFile }) return FILES[fromFile].toString()
    if (rivals.none { (it shr 3) == fromRank }) return (fromRank + 1).toString()
    return "${FILES[fromFile]}${fromRank + 1}"
}

/** Append "+" or "#" by playing the move and looking. */
private fun ChessPosition.decorate(move: ChessMove, base: String): String {
    val next = copy()
    next.make(move)
    if (!next.isInCheck) return base
    val replies = ChessMoveGenerator.generateLegal(next.copy())
    return base + if (replies.count == 0) "#" else "+"
}

/**
 * SAN for a whole line, numbered as it would appear in a game score:
 * "15. Nf3 Bd6 16. O-O".
 *
 * Stops early at the first illegal move rather than producing nonsense,
 * which matters because a truncated principal variation is a normal thing
 * to be handed.
 */
fun ChessPosition.sanLine(line: List<ChessMove>): String {
    val position = copy()
    val parts = mutableListOf<String>()

    for (move in line) {
        val legal = ChessMoveGenerator.generateLegal(position.copy()).toList()
        if (move !in legal) break

        if (position.sideToMove == ChessColor.WHITE) {
            parts.add("${position.fullmoveNumber}.")
        } else if (parts.isEmpty()) {
            // A line beginning on Black's move needs the "15..." form so
            // the numbering reads correctly.
            parts.add("${position.fullmoveNumber}...")
        }
        parts.add(position.san(move))
        position.make(move)
    }

    return parts.joinToString(" ")
}

/**
 * Find the legal move matching a UCI string, or null.
 *
 * Goes through the legal move list rather than trusting
 * `ChessMove.fromUci` directly, because the UCI text carries no
 * capture/en-passant/castling flags — only the position knows those, and a
 * move with the wrong flags would corrupt `make`.
 */
fun ChessPosition.moveFromUci(uci: String): ChessMove? {
    val parsed = ChessMove.fromUci(uci) ?: return null
    val legal = ChessMoveGenerator.generateLegal(copy())
    for (index in 0 until legal.count) {
        val candidate = legal[index]
        if (candidate.fromIndex == parsed.fromIndex &&
            candidate.toIndex == parsed.toIndex &&
            candidate.promotionKind == parsed.promotionKind
        ) {
            return candidate
        }
    }
    return null
}
