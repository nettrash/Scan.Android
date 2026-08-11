package me.nettrash.scan.chess

/**
 * Move generation. Port of `Scan/Chess/ChessMoveGenerator.swift`.
 *
 * ## Pseudo-legal, then filtered
 *
 * Generation happens in two passes: build every move that obeys the
 * *movement* rules, then drop the ones that leave your own king en prise
 * by playing each and asking. That is the slower of the two standard
 * designs — the faster one computes pins and check masks up front and
 * only ever emits legal moves.
 *
 * We take the slow one because the failure modes are so lopsided.
 * Pin-aware generation is where engines hide their subtlest bugs (a rook
 * pinned along the rank that captures en passant and exposes the king is
 * the famous one), and those bugs surface as a perft mismatch six plies
 * deep with no clue attached. Play-and-check cannot be wrong: it asks the
 * same `isAttacked` the rest of the engine trusts.
 *
 * The cost is roughly one extra make/unmake per generated move, and move
 * generation is not what limits this engine at a 1-3 s/move budget. If
 * that changes, [generateLegal] is the single function to replace, and
 * perft is standing by to prove the replacement equivalent.
 */
object ChessMoveGenerator {

    /** What to generate. */
    enum class Mode {
        /** Everything legal. */
        ALL,

        /**
         * Captures, en passant and promotions only — the quiescence
         * search's diet.
         *
         * Quiet promotions are included because a pawn reaching the
         * eighth rank changes the material balance as violently as a
         * capture does, and leaving them out makes quiescence blind to
         * the most forcing move on the board.
         */
        CAPTURES_AND_PROMOTIONS,
    }

    /**
     * Every legal move in [position].
     *
     * Mutates [position] during the filter (it plays each candidate and
     * takes it back) but leaves it **exactly as it found it**, which
     * avoids copying the board per node.
     */
    fun generateLegal(position: ChessPosition, mode: Mode = Mode.ALL): ChessMoveList {
        val pseudoLegal = generatePseudoLegal(position, mode)
        val us = position.sideToMove
        val them = us.opposite

        val legal = ChessMoveList()
        for (index in 0 until pseudoLegal.count) {
            val move = pseudoLegal[index]
            position.make(move)
            val king = position.kingSquare(us)
            // A kingless side has nothing to expose. That position is
            // illegal and the engine refuses to search it, but the editor
            // can hold one and still wants a move list to highlight.
            if (king < 0 || !position.isAttacked(king, them)) {
                legal.add(move)
            }
            position.unmake(move)
        }
        return legal
    }

    /**
     * Convenience for callers holding a position they must not disturb —
     * the UI, mostly. Copies, so don't use it inside the search.
     */
    fun legalMoves(position: ChessPosition, mode: Mode = Mode.ALL): List<ChessMove> =
        generateLegal(position.copy(), mode).toList()

    /**
     * Every move that obeys the movement rules, ignoring whether it leaves
     * your own king in check.
     *
     * Castling is the exception: its "you may not castle out of, through
     * or into check" conditions are resolved here rather than left to the
     * filter, because the filter only tests the king's *final* square and
     * would happily let a king walk through a checked f1.
     */
    fun generatePseudoLegal(position: ChessPosition, mode: Mode = Mode.ALL): ChessMoveList {
        val moves = ChessMoveList()
        val us = position.sideToMove
        val them = us.opposite
        val ourPieces = position.pieces(us)
        val theirPieces = position.pieces(them)
        val occupancy = position.occupied

        // Non-captures are only wanted in ALL mode; the target mask does
        // that filtering once instead of at every emit site.
        val quietTargets = if (mode == Mode.ALL) occupancy.inv() else 0L

        generatePawnMoves(position, us, mode, moves)

        for (kind in SLIDERS_AND_KNIGHT) {
            var from = position.pieces(us, kind)
            while (from.isNotEmpty()) {
                val square = from.lsbIndex()
                from = from.withoutLsb()
                val attacks = if (kind == ChessPieceKind.KNIGHT) {
                    ChessTables.knightAttacks[square]
                } else {
                    ChessTables.sliderAttacks(kind, square, occupancy)
                }
                emit(square, attacks and ourPieces.inv() and (theirPieces or quietTargets),
                    theirPieces, moves)
            }
        }

        val kingSquare = position.kingSquare(us)
        if (kingSquare >= 0) {
            emit(
                kingSquare,
                ChessTables.kingAttacks[kingSquare] and ourPieces.inv() and
                    (theirPieces or quietTargets),
                theirPieces,
                moves,
            )
            if (mode == Mode.ALL) generateCastles(position, us, kingSquare, moves)
        }

        return moves
    }

    private val SLIDERS_AND_KNIGHT = arrayOf(
        ChessPieceKind.KNIGHT,
        ChessPieceKind.BISHOP,
        ChessPieceKind.ROOK,
        ChessPieceKind.QUEEN,
    )

    /** Turn a target bitboard into quiet/capture moves from one origin. */
    private fun emit(from: Int, targets: Bitboard, theirPieces: Bitboard, moves: ChessMoveList) {
        var remaining = targets
        while (remaining.isNotEmpty()) {
            val to = remaining.lsbIndex()
            remaining = remaining.withoutLsb()
            val flag = if (theirPieces.contains(to)) ChessMove.CAPTURE else ChessMove.QUIET
            moves.add(ChessMove.of(from, to, flag))
        }
    }

    // MARK: - Pawns

    private fun generatePawnMoves(
        position: ChessPosition,
        us: ChessColor,
        mode: Mode,
        moves: ChessMoveList,
    ) {
        val pawns = position.pieces(us, ChessPieceKind.PAWN)
        if (pawns.isEmpty()) return

        val white = us == ChessColor.WHITE
        val push = us.pawnPush
        val empty = position.occupied.inv()
        val theirPieces = position.pieces(us.opposite)
        val promotionRank = if (white) ChessMasks.RANK_8 else ChessMasks.RANK_1
        // Squares a *single* push lands on when the pawn started on its
        // home rank — the springboard for the double push.
        val springboard = if (white) ChessMasks.rank(2) else ChessMasks.rank(5)

        // --- Pushes ---
        val singlePushes = (if (white) pawns shl 8 else pawns ushr 8) and empty
        var pending = singlePushes
        while (pending.isNotEmpty()) {
            val to = pending.lsbIndex()
            pending = pending.withoutLsb()
            val from = to - push
            if (promotionRank.contains(to)) {
                appendPromotions(from, to, capture = false, moves = moves)
            } else if (mode == Mode.ALL) {
                moves.add(ChessMove.of(from, to, ChessMove.QUIET))
            }
        }

        if (mode == Mode.ALL) {
            val launched = singlePushes and springboard
            var doublePushes = (if (white) launched shl 8 else launched ushr 8) and empty
            while (doublePushes.isNotEmpty()) {
                val to = doublePushes.lsbIndex()
                doublePushes = doublePushes.withoutLsb()
                moves.add(ChessMove.of(to - 2 * push, to, ChessMove.DOUBLE_PAWN_PUSH))
            }
        }

        // --- Captures ---
        var from = pawns
        while (from.isNotEmpty()) {
            val square = from.lsbIndex()
            from = from.withoutLsb()
            var targets = ChessTables.pawnAttacks[us.ordinal * 64 + square] and theirPieces
            while (targets.isNotEmpty()) {
                val to = targets.lsbIndex()
                targets = targets.withoutLsb()
                if (promotionRank.contains(to)) {
                    appendPromotions(square, to, capture = true, moves = moves)
                } else {
                    moves.add(ChessMove.of(square, to, ChessMove.CAPTURE))
                }
            }
        }

        // --- En passant ---
        //
        // One of our pawns can capture onto the target square exactly when
        // it stands on a square an *enemy* pawn on the target would attack
        // — the same index flip `isAttacked` uses.
        val enPassant = position.enPassantSquare
        if (enPassant >= 0) {
            var capturers =
                ChessTables.pawnAttacks[us.opposite.ordinal * 64 + enPassant] and pawns
            while (capturers.isNotEmpty()) {
                val square = capturers.lsbIndex()
                capturers = capturers.withoutLsb()
                moves.add(ChessMove.of(square, enPassant, ChessMove.EN_PASSANT))
            }
        }
    }

    /**
     * Queen first: it is the right answer almost always, so putting it at
     * the head means the search's move ordering starts from a good guess
     * even before scoring.
     */
    private val PROMOTION_ORDER = arrayOf(
        ChessPieceKind.QUEEN,
        ChessPieceKind.KNIGHT,
        ChessPieceKind.ROOK,
        ChessPieceKind.BISHOP,
    )

    private fun appendPromotions(from: Int, to: Int, capture: Boolean, moves: ChessMoveList) {
        for (kind in PROMOTION_ORDER) {
            moves.add(ChessMove.of(from, to, ChessMove.promotionFlag(kind, capture)))
        }
    }

    // MARK: - Castling

    private fun generateCastles(
        position: ChessPosition,
        us: ChessColor,
        kingSquare: Int,
        moves: ChessMoveList,
    ) {
        val them = us.opposite
        val home = if (us == ChessColor.WHITE) ChessSquare.E1.index else ChessSquare.E8.index
        // Rights can outlive the pieces they describe when the position
        // came from a hand-written FEN or the editor, so verify the king
        // is actually home before trusting them.
        if (kingSquare != home) return
        if (position.isAttacked(kingSquare, them)) return

        val backRank = if (us == ChessColor.WHITE) 0 else 56
        val ourRook = ChessPiece(us, ChessPieceKind.ROOK)

        if (position.castlingRights.contains(ChessCastlingRights.kingside(us))) {
            val rook = backRank + 7
            val through = backRank + 5 // f1 / f8
            val landing = backRank + 6 // g1 / g8
            if (position.pieceAt(rook) == ourRook &&
                !position.occupied.contains(through) &&
                !position.occupied.contains(landing) &&
                !position.isAttacked(through, them) &&
                !position.isAttacked(landing, them)
            ) {
                moves.add(ChessMove.of(kingSquare, landing, ChessMove.CASTLE_KINGSIDE))
            }
        }

        if (position.castlingRights.contains(ChessCastlingRights.queenside(us))) {
            val rook = backRank // a1 / a8
            // b1 / b8 must be empty, but may be attacked: the king never
            // sets foot on it.
            val knightSquare = backRank + 1
            val through = backRank + 3 // d1 / d8
            val landing = backRank + 2 // c1 / c8
            if (position.pieceAt(rook) == ourRook &&
                !position.occupied.contains(knightSquare) &&
                !position.occupied.contains(through) &&
                !position.occupied.contains(landing) &&
                !position.isAttacked(through, them) &&
                !position.isAttacked(landing, them)
            ) {
                moves.add(ChessMove.of(kingSquare, landing, ChessMove.CASTLE_QUEENSIDE))
            }
        }
    }
}

// MARK: - Game state

/** How the game stands for the side to move. */
enum class ChessOutcome {
    ONGOING,
    CHECKMATE,
    STALEMATE,

    /** Fifty full moves without a capture or a pawn move. */
    FIFTY_MOVE_RULE,

    /** Neither side has enough material to force mate. */
    INSUFFICIENT_MATERIAL,
}

/**
 * Evaluate the position's terminal state.
 *
 * Copies the position (it needs move generation), so this is a UI and
 * test entry point — the search tracks the same facts inline.
 */
val ChessPosition.outcome: ChessOutcome
    get() {
        val moves = ChessMoveGenerator.generateLegal(copy())
        if (moves.isEmpty) return if (isInCheck) ChessOutcome.CHECKMATE else ChessOutcome.STALEMATE
        if (hasInsufficientMaterial) return ChessOutcome.INSUFFICIENT_MATERIAL
        if (halfmoveClock >= 100) return ChessOutcome.FIFTY_MOVE_RULE
        return ChessOutcome.ONGOING
    }

/**
 * The uncontroversial half of the insufficient-material rule: lone kings,
 * king + minor, and any number of bishops all standing on one square
 * colour.
 *
 * Deliberately conservative. King + two knights is *not* included — mate
 * is unforceable but not impossible, and FIDE only draws it when no mate
 * exists in the actual position. For a move-suggestion feature, calling a
 * drawn-ish position "ongoing" is the harmless error; calling a winnable
 * one "dead drawn" is not.
 */
val ChessPosition.hasInsufficientMaterial: Boolean
    get() {
        if (pieces(ChessPieceKind.PAWN).isNotEmpty() ||
            pieces(ChessPieceKind.ROOK).isNotEmpty() ||
            pieces(ChessPieceKind.QUEEN).isNotEmpty()
        ) {
            return false
        }
        val knights = pieces(ChessPieceKind.KNIGHT).count()
        val bishops = pieces(ChessPieceKind.BISHOP)

        if (knights == 0 && bishops.isEmpty()) return true // K v K
        if (knights + bishops.count() == 1) return true // K+minor v K
        if (knights == 0) {
            // Any number of bishops, provided they all stand on one square
            // colour — neither side can ever control the other.
            val light = bishops.squares().count { it.isLight }
            return light == 0 || light == bishops.count()
        }
        return false
    }
