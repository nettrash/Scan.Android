package me.nettrash.scan.chess

/**
 * Static evaluation: how good is this position, in centipawns, for the
 * side to move? Port of `Scan/Chess/ChessEvaluation.swift`.
 *
 * ## Scale and sign
 *
 * One pawn = 100. The returned score is **always from the perspective of
 * the side to move** (negamax convention) — a position where the mover is
 * a rook up scores around +500 whether the mover is White or Black. The
 * UI converts to White's perspective for display, because "+2.4" is
 * conventionally read as "White is better".
 *
 * ## Tapering
 *
 * A king belongs behind a pawn wall in the middlegame and in the centre in
 * the endgame; a pawn on the seventh is worth far more once the queens are
 * gone. So the king and pawn tables come in two versions and the score
 * interpolates between them by *game phase*, computed from the remaining
 * non-pawn material. Without this the engine walks its king into the open
 * at move 15, or refuses to activate it at move 60 — both of which look
 * obviously wrong to a user reading a suggestion.
 *
 * ## What is deliberately missing
 *
 * No king-attack scoring, no piece-coordination terms, no endgame
 * tablebase knowledge. This eval aims at "suggest a good move to a club
 * player", not at competing with anything. Search depth does most of the
 * tactical work; the eval only has to rank quiet positions sensibly. Each
 * term below is one an engine can be *wrong* about cheaply — more terms
 * would need tuning against each other, and untuned terms make an engine
 * worse, not better.
 *
 * Piece values and the piece-square tables follow Tomasz Michniewski's
 * "Simplified Evaluation Function", the standard published starting point
 * for exactly this kind of engine.
 *
 * **Parity matters here.** This must score identically to the Swift port,
 * or the two apps suggest different moves for the same photo.
 * `ChessSearchTest.evaluationMatchesTheSwiftPort` pins that down.
 */
object ChessEvaluation {

    // MARK: - Material

    /**
     * Centipawn value per kind, indexed by `ChessPieceKind.ordinal`.
     *
     * The king's value is zero, not a huge number: kings are never
     * captured, legality guarantees both are on the board, and giving it a
     * value would only add a constant to every score.
     */
    val PIECE_VALUE = intArrayOf(100, 320, 330, 500, 900, 0)

    /**
     * Material for *move ordering and pruning* decisions, where a king
     * does need a finite large value (MVV-LVA sorts by victim).
     */
    val CAPTURE_VALUE = intArrayOf(100, 320, 330, 500, 900, 20_000)

    // MARK: - Game phase

    /**
     * Phase weight per kind — how much each piece keeps the game in the
     * middlegame. Pawns and kings contribute nothing; they are on the
     * board in every phase.
     */
    private val PHASE_WEIGHT = intArrayOf(0, 1, 1, 2, 4, 0)

    /** 4 knights + 4 bishops + 4 rooks x2 + 2 queens x4 = 24. */
    private const val MAX_PHASE = 24

    /** 24 (full middlegame) down to 0 (bare kings and pawns). */
    fun phase(position: ChessPosition): Int {
        var total = 0
        for (kind in PHASE_KINDS) {
            total += position.pieces(kind).count() * PHASE_WEIGHT[kind.ordinal]
        }
        return minOf(total, MAX_PHASE)
    }

    private val PHASE_KINDS = arrayOf(
        ChessPieceKind.KNIGHT, ChessPieceKind.BISHOP,
        ChessPieceKind.ROOK, ChessPieceKind.QUEEN,
    )

    // MARK: - Piece-square tables
    //
    // Written rank 8 first, so each table reads as a board seen from
    // White's side. `tableIndex` flips that into LERF indexing.

    /**
     * Map a LERF square to a table index.
     *
     * White flips the rank (`square xor 56`) because the tables are
     * written with rank 8 at the top; Black reads them directly, which
     * mirrors them vertically — exactly the symmetry the tables assume.
     */
    private fun tableIndex(square: Int, color: ChessColor): Int =
        if (color == ChessColor.WHITE) square xor 56 else square

    private val PAWN_TABLE = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        50, 50, 50, 50, 50, 50, 50, 50,
        10, 10, 20, 30, 30, 20, 10, 10,
        5, 5, 10, 25, 25, 10, 5, 5,
        0, 0, 0, 20, 20, 0, 0, 0,
        5, -5, -10, 0, 0, -10, -5, 5,
        5, 10, 10, -20, -20, 10, 10, 5,
        0, 0, 0, 0, 0, 0, 0, 0,
    )

    /**
     * In the endgame a pawn's only real virtue is how close it is to
     * promoting, so the file-based shaping above gives way to a pure rank
     * gradient.
     */
    private val PAWN_ENDGAME_TABLE = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        90, 90, 90, 90, 90, 90, 90, 90,
        55, 55, 55, 55, 55, 55, 55, 55,
        33, 33, 33, 33, 33, 33, 33, 33,
        20, 20, 20, 20, 20, 20, 20, 20,
        12, 12, 12, 12, 12, 12, 12, 12,
        8, 8, 8, 8, 8, 8, 8, 8,
        0, 0, 0, 0, 0, 0, 0, 0,
    )

    private val KNIGHT_TABLE = intArrayOf(
        -50, -40, -30, -30, -30, -30, -40, -50,
        -40, -20, 0, 0, 0, 0, -20, -40,
        -30, 0, 10, 15, 15, 10, 0, -30,
        -30, 5, 15, 20, 20, 15, 5, -30,
        -30, 0, 15, 20, 20, 15, 0, -30,
        -30, 5, 10, 15, 15, 10, 5, -30,
        -40, -20, 0, 5, 5, 0, -20, -40,
        -50, -40, -30, -30, -30, -30, -40, -50,
    )

    private val BISHOP_TABLE = intArrayOf(
        -20, -10, -10, -10, -10, -10, -10, -20,
        -10, 0, 0, 0, 0, 0, 0, -10,
        -10, 0, 5, 10, 10, 5, 0, -10,
        -10, 5, 5, 10, 10, 5, 5, -10,
        -10, 0, 10, 10, 10, 10, 0, -10,
        -10, 10, 10, 10, 10, 10, 10, -10,
        -10, 5, 0, 0, 0, 0, 5, -10,
        -20, -10, -10, -10, -10, -10, -10, -20,
    )

    private val ROOK_TABLE = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        5, 10, 10, 10, 10, 10, 10, 5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        0, 0, 0, 5, 5, 0, 0, 0,
    )

    private val QUEEN_TABLE = intArrayOf(
        -20, -10, -10, -5, -5, -10, -10, -20,
        -10, 0, 0, 0, 0, 0, 0, -10,
        -10, 0, 5, 5, 5, 5, 0, -10,
        -5, 0, 5, 5, 5, 5, 0, -5,
        0, 0, 5, 5, 5, 5, 0, -5,
        -10, 5, 5, 5, 5, 5, 0, -10,
        -10, 0, 5, 0, 0, 0, 0, -10,
        -20, -10, -10, -5, -5, -10, -10, -20,
    )

    /** Middlegame king: stay home, behind the pawns, preferably castled. */
    private val KING_TABLE = intArrayOf(
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -20, -30, -30, -40, -40, -30, -30, -20,
        -10, -20, -20, -20, -20, -20, -20, -10,
        20, 20, 0, 0, 0, 0, 20, 20,
        20, 30, 10, 0, 0, 10, 30, 20,
    )

    /**
     * Endgame king: march to the centre. The sign flip against the
     * middlegame table is the single most valuable thing tapering buys.
     */
    private val KING_ENDGAME_TABLE = intArrayOf(
        -50, -40, -30, -20, -20, -30, -40, -50,
        -30, -20, -10, 0, 0, -10, -20, -30,
        -30, -10, 20, 30, 30, 20, -10, -30,
        -30, -10, 30, 40, 40, 30, -10, -30,
        -30, -10, 30, 40, 40, 30, -10, -30,
        -30, -10, 20, 30, 30, 20, -10, -30,
        -30, -30, 0, 0, 0, 0, -30, -30,
        -50, -30, -30, -30, -30, -30, -30, -50,
    )

    private val MIDDLEGAME_TABLES = arrayOf(
        PAWN_TABLE, KNIGHT_TABLE, BISHOP_TABLE, ROOK_TABLE, QUEEN_TABLE, KING_TABLE,
    )
    private val ENDGAME_TABLES = arrayOf(
        PAWN_ENDGAME_TABLE, KNIGHT_TABLE, BISHOP_TABLE, ROOK_TABLE, QUEEN_TABLE,
        KING_ENDGAME_TABLE,
    )

    // MARK: - Bonuses

    private const val BISHOP_PAIR_BONUS = 30
    private const val ROOK_OPEN_FILE_BONUS = 20
    private const val ROOK_SEMI_OPEN_FILE_BONUS = 10
    private const val DOUBLED_PAWN_PENALTY = -12
    private const val ISOLATED_PAWN_PENALTY = -18

    /**
     * Indexed by how far the pawn has advanced (its rank from its own
     * side's view). A passer on the seventh is nearly a queen.
     */
    private val PASSED_PAWN_BONUS = intArrayOf(0, 5, 15, 30, 55, 90, 140, 0)
    private const val MOBILITY_WEIGHT = 2

    /** Per missing pawn in front of a castled king. */
    private const val KING_SHIELD_PENALTY = -14

    // MARK: - Evaluate

    /** Static evaluation in centipawns, from the side to move's view. */
    fun evaluate(position: ChessPosition): Int {
        val phaseNow = phase(position)
        val white = score(position, ChessColor.WHITE, phaseNow)
        val black = score(position, ChessColor.BLACK, phaseNow)
        val whiteRelative = white - black
        return if (position.sideToMove == ChessColor.WHITE) whiteRelative else -whiteRelative
    }

    /** One side's total, always positive-is-good-for-that-side. */
    private fun score(position: ChessPosition, color: ChessColor, phaseNow: Int): Int {
        var middlegame = 0
        var endgame = 0

        val ourPawns = position.pieces(color, ChessPieceKind.PAWN)
        val theirPawns = position.pieces(color.opposite, ChessPieceKind.PAWN)
        val occupancy = position.occupied
        val ourPieces = position.pieces(color)

        for (kind in ChessPieceKind.ALL) {
            var bb = position.pieces(color, kind)
            val value = PIECE_VALUE[kind.ordinal]
            val middlegameTable = MIDDLEGAME_TABLES[kind.ordinal]
            val endgameTable = ENDGAME_TABLES[kind.ordinal]

            while (bb.isNotEmpty()) {
                val square = bb.lsbIndex()
                bb = bb.withoutLsb()
                val index = tableIndex(square, color)
                middlegame += value + middlegameTable[index]
                endgame += value + endgameTable[index]

                when (kind) {
                    ChessPieceKind.PAWN -> {
                        val bonus = pawnStructure(square, color, ourPawns, theirPawns)
                        middlegame += bonus
                        endgame += bonus
                    }

                    ChessPieceKind.KING -> {
                        // Pawn shield is a middlegame-only concern, which
                        // the taper handles by not adding it to `endgame`.
                        middlegame += kingShield(square, color, ourPawns)
                    }

                    else -> {
                        // Mobility: how many squares this piece can reach.
                        // Cheap, and it captures "my bishop is buried
                        // behind my own pawns" better than any static
                        // table can.
                        val attacks = if (kind == ChessPieceKind.KNIGHT) {
                            ChessTables.knightAttacks[square]
                        } else {
                            ChessTables.sliderAttacks(kind, square, occupancy)
                        }
                        val moves = (attacks and ourPieces.inv()).count() * MOBILITY_WEIGHT
                        middlegame += moves
                        endgame += moves

                        if (kind == ChessPieceKind.ROOK) {
                            val file = ChessMasks.file(square and 7)
                            if ((ourPawns and file).isEmpty()) {
                                val bonus = if ((theirPawns and file).isEmpty()) {
                                    ROOK_OPEN_FILE_BONUS
                                } else {
                                    ROOK_SEMI_OPEN_FILE_BONUS
                                }
                                middlegame += bonus
                                endgame += bonus
                            }
                        }
                    }
                }
            }
        }

        if (position.pieces(color, ChessPieceKind.BISHOP).count() >= 2) {
            middlegame += BISHOP_PAIR_BONUS
            endgame += BISHOP_PAIR_BONUS
        }

        // Interpolate. `phaseNow` is 24 at the start and 0 with bare kings,
        // so this weights the middlegame score by how much material is on.
        return (middlegame * phaseNow + endgame * (MAX_PHASE - phaseNow)) / MAX_PHASE
    }

    // MARK: - Pawn structure

    private fun pawnStructure(
        square: Int,
        color: ChessColor,
        ourPawns: Bitboard,
        theirPawns: Bitboard,
    ): Int {
        var total = 0
        val file = square and 7
        val rank = square shr 3
        val fileMask = ChessMasks.file(file)

        // Doubled: another friendly pawn on the same file. Counted once per
        // pawn, so a doubled pair is penalised twice — intentionally.
        if ((ourPawns and fileMask).count() > 1) total += DOUBLED_PAWN_PENALTY

        // Isolated: no friendly pawn on either adjacent file.
        var neighbours = 0L
        if (file > 0) neighbours = neighbours or ChessMasks.file(file - 1)
        if (file < 7) neighbours = neighbours or ChessMasks.file(file + 1)
        if ((ourPawns and neighbours).isEmpty()) total += ISOLATED_PAWN_PENALTY

        // Passed: no enemy pawn ahead on this file or the two beside it.
        val ahead = squaresAhead(rank, color)
        if ((theirPawns and (fileMask or neighbours) and ahead).isEmpty()) {
            val advanced = if (color == ChessColor.WHITE) rank else 7 - rank
            total += PASSED_PAWN_BONUS[advanced]
        }

        return total
    }

    /**
     * Every square strictly in front of [rank] from [color]'s point of
     * view — the region a passed pawn needs clear of enemy pawns.
     */
    private fun squaresAhead(rank: Int, color: ChessColor): Bitboard =
        if (color == ChessColor.WHITE) {
            if (rank >= 7) 0L else (-1L) shl ((rank + 1) * 8)
        } else {
            if (rank <= 0) 0L else (-1L) ushr ((8 - rank) * 8)
        }

    /**
     * Penalise missing pawns on the three files in front of a king that has
     * gone to a corner. A king in the centre is already punished by the
     * middlegame table, so this only looks at castled positions.
     */
    private fun kingShield(square: Int, color: ChessColor, ourPawns: Bitboard): Int {
        val file = square and 7
        if (file in 3..4) return 0

        val rank = square shr 3
        val shieldRank = if (color == ChessColor.WHITE) rank + 1 else rank - 1
        if (shieldRank !in 0..7) return 0

        var missing = 0
        for (shieldFile in maxOf(0, file - 1)..minOf(7, file + 1)) {
            val mask = ChessMasks.file(shieldFile) and ChessMasks.rank(shieldRank)
            if ((ourPawns and mask).isEmpty()) missing += 1
        }
        return missing * KING_SHIELD_PENALTY
    }
}
