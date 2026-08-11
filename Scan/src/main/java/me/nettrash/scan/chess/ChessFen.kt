package me.nettrash.scan.chess

/**
 * Forsyth–Edwards Notation, plus the legality checks that decide whether
 * a position is safe to hand to the search. Port of
 * `Scan/Chess/ChessFEN.swift`.
 *
 * These two live together because they exist for the same reason: every
 * position this feature sees comes from *outside* the engine — a FEN the
 * user pasted, or 64 squares a neural network guessed at — and neither
 * source can be trusted to describe legal chess. Parsing establishes that
 * the input is structurally a board; validation establishes that it is a
 * board the rules permit. Move generation assumes both.
 */

// MARK: - Encoding

/** The full six-field FEN for this position. */
val ChessPosition.fen: String
    get() {
        val sb = StringBuilder()
        // FEN is written rank 8 down to rank 1 — the opposite of the
        // engine's LERF index order, hence the reversed loop.
        for (rank in 7 downTo 0) {
            var emptyRun = 0
            for (file in 0..7) {
                val piece = pieceAt(rank * 8 + file)
                if (piece == null) {
                    emptyRun += 1
                } else {
                    if (emptyRun > 0) { sb.append(emptyRun); emptyRun = 0 }
                    sb.append(piece.fenChar)
                }
            }
            if (emptyRun > 0) sb.append(emptyRun)
            if (rank > 0) sb.append('/')
        }

        sb.append(' ').append(sideToMove.fenChar)
        sb.append(' ').append(castlingRights.fenField)
        sb.append(' ').append(
            if (enPassantSquare >= 0) ChessSquare(enPassantSquare).algebraic else "-"
        )
        sb.append(' ').append(halfmoveClock)
        sb.append(' ').append(fullmoveNumber)
        return sb.toString()
    }

/**
 * The placement field alone — the part a photo can actually tell us.
 *
 * The analysis screen shows this when it wants to identify a position
 * without implying we know whose turn it is.
 */
val ChessPosition.placementFen: String
    get() = fen.substringBefore(' ')

// MARK: - Decoding

/**
 * Parse a FEN string, or return null.
 *
 * Tolerant in the ways that matter for a scanner app and strict in the
 * ways that would corrupt the engine:
 *
 * - The placement field must describe exactly 8 ranks of exactly 8 files,
 *   with only recognised piece letters. Anything else returns null.
 * - The other five fields are optional and default to "white to move, no
 *   rights, no en passant, clocks at 0/1". Placement-only FENs are common
 *   in puzzle sources and in our own `placementFen`.
 * - An unparseable clock falls back to its default rather than failing
 *   the whole string.
 *
 * Chess-level legality is **not** checked here — see [legalityProblems].
 */
fun ChessPosition.Companion.fromFen(fen: String): ChessPosition? {
    val fields = fen.trim().split(' ').filter { it.isNotEmpty() }
    val placementField = fields.firstOrNull() ?: return null

    val rankFields = placementField.split('/')
    if (rankFields.size != 8) return null

    val placement = arrayOfNulls<ChessPiece>(64)
    for ((offset, rankField) in rankFields.withIndex()) {
        // rankFields[0] is rank 8, which is LERF rank index 7.
        val rank = 7 - offset
        var file = 0
        for (ch in rankField) {
            val skip = ch - '0'
            if (skip in 1..8) {
                file += skip
            } else {
                val piece = ChessPiece.fromFenChar(ch) ?: return null
                if (file >= 8) return null
                placement[rank * 8 + file] = piece
                file += 1
            }
            if (file > 8) return null
        }
        if (file != 8) return null
    }

    val sideToMove =
        if (fields.size > 1 && fields[1].lowercase() == "b") ChessColor.BLACK else ChessColor.WHITE
    val rights = ChessCastlingRights.parse(if (fields.size > 2) fields[2] else "-")
    val enPassant =
        if (fields.size > 3 && fields[3] != "-") ChessSquare.parse(fields[3]) else null
    val halfmove = if (fields.size > 4) (fields[4].toIntOrNull() ?: 0) else 0
    val fullmove = if (fields.size > 5) (fields[5].toIntOrNull() ?: 1) else 1

    return ChessPosition(
        placement = placement,
        sideToMove = sideToMove,
        castlingRights = rights,
        enPassantSquare = enPassant,
        halfmoveClock = maxOf(0, halfmove),
        fullmoveNumber = maxOf(1, fullmove),
    )
}

// MARK: - Legality

/**
 * A reason a position cannot be analysed, in words the analysis screen can
 * show verbatim.
 */
sealed class ChessLegalityProblem {
    data class MissingKing(val color: ChessColor) : ChessLegalityProblem()
    data class TooManyKings(val color: ChessColor) : ChessLegalityProblem()
    data class TooManyPawns(val color: ChessColor, val count: Int) : ChessLegalityProblem()
    data class TooManyPieces(val color: ChessColor, val count: Int) : ChessLegalityProblem()
    data class PawnOnBackRank(val square: ChessSquare) : ChessLegalityProblem()
    data class ImpossiblePromotionCount(val color: ChessColor) : ChessLegalityProblem()
    data class OpponentInCheck(val color: ChessColor) : ChessLegalityProblem()

    /**
     * User-facing text. Deliberately says what to *do*, because every one
     * of these is reachable by a misread square the user can fix in the
     * editor.
     */
    val message: String
        get() = when (this) {
            is MissingKing ->
                "${color.displayName} has no king — tap an empty square to add one."
            is TooManyKings ->
                "${color.displayName} has more than one king — remove the extras."
            is TooManyPawns ->
                "${color.displayName} has $count pawns; a side can have at most 8."
            is TooManyPieces ->
                "${color.displayName} has $count pieces; a side can have at most 16."
            is PawnOnBackRank ->
                "A pawn can't stand on ${square.algebraic} — pawns never occupy rank 1 or 8."
            is ImpossiblePromotionCount ->
                "${color.displayName} has more promoted pieces than missing pawns to explain them."
            is OpponentInCheck ->
                "${color.displayName} is in check, so it can't be " +
                    "${color.opposite.displayName}'s turn."
        }
}

/**
 * Everything wrong with this position, chess-wise. Empty means the engine
 * can search it.
 *
 * The checks are the ones a misread board actually trips — a third king, a
 * ninth pawn, a pawn hallucinated onto rank 8 — plus the side-to-move
 * check that decides whether "best move for White" and "best move for
 * Black" are *both* answerable for this board.
 *
 * Note what is deliberately **not** checked: whether the position is
 * reachable from the starting array. That is undecidable in general and
 * irrelevant here — a photo of a puzzle set up by hand is a perfectly good
 * thing to analyse.
 */
val ChessPosition.legalityProblems: List<ChessLegalityProblem>
    get() {
        val problems = mutableListOf<ChessLegalityProblem>()

        for (color in arrayOf(ChessColor.WHITE, ChessColor.BLACK)) {
            val kings = pieces(color, ChessPieceKind.KING).count()
            if (kings == 0) problems.add(ChessLegalityProblem.MissingKing(color))
            if (kings > 1) problems.add(ChessLegalityProblem.TooManyKings(color))

            val pawns = pieces(color, ChessPieceKind.PAWN).count()
            if (pawns > 8) problems.add(ChessLegalityProblem.TooManyPawns(color, pawns))

            val total = pieces(color).count()
            if (total > 16) problems.add(ChessLegalityProblem.TooManyPieces(color, total))

            // Every piece beyond the starting complement has to be a
            // promotion, and every promotion costs a pawn. Extra queens
            // are the usual culprit in a misread — a queen and a bishop
            // look alike from a low angle.
            val extras = maxOf(0, pieces(color, ChessPieceKind.QUEEN).count() - 1) +
                maxOf(0, pieces(color, ChessPieceKind.ROOK).count() - 2) +
                maxOf(0, pieces(color, ChessPieceKind.BISHOP).count() - 2) +
                maxOf(0, pieces(color, ChessPieceKind.KNIGHT).count() - 2)
            if (extras > 8 - pawns) {
                problems.add(ChessLegalityProblem.ImpossiblePromotionCount(color))
            }
        }

        val backRanks = ChessMasks.RANK_1 or ChessMasks.RANK_8
        var strandedPawns = pieces(ChessPieceKind.PAWN) and backRanks
        while (strandedPawns.isNotEmpty()) {
            problems.add(ChessLegalityProblem.PawnOnBackRank(ChessSquare(strandedPawns.lsbIndex())))
            strandedPawns = strandedPawns.withoutLsb()
        }

        // The side that just moved cannot have left its own king in check.
        if (opponentIsInCheck) {
            problems.add(ChessLegalityProblem.OpponentInCheck(sideToMove.opposite))
        }

        return problems
    }

/** True when the engine will accept this position. */
val ChessPosition.isAnalysable: Boolean
    get() = legalityProblems.isEmpty()
