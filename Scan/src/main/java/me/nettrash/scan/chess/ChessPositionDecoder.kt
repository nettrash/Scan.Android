package me.nettrash.scan.chess

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Turns 64 per-square probability distributions into the most probable
 * *legal* chess position.
 *
 * ## Why this is not just argmax per square
 *
 * Per-square accuracy compounds over 64 squares, and the compounding is
 * brutal:
 *
 * | per-square | whole board correct |
 * |---|---|
 * | 99.0% | 52% |
 * | 99.5% | 73% |
 * | 99.9% | 94% |
 *
 * So a classifier that looks excellent in a confusion matrix still gets
 * the *whole board* wrong about half the time — and a board that is wrong
 * anywhere is a wrong position and a wrong suggested move. Taking the
 * per-square argmax throws away the strongest prior available: the answer
 * has to be legal chess.
 *
 * This spends that prior, enforcing exactly what [legalityProblems]
 * checks, so anything decoded here is a position the engine will accept:
 * one king a side, at most 8 pawns, no pawn on rank 1 or 8, at most 16
 * pieces, and promotions paid for by missing pawns.
 *
 * ## Approach
 *
 * Not an exact optimiser — that would be a small integer program, slower
 * and much harder to keep identical across three languages. Instead:
 * start from the per-square argmax, then repair each violated constraint
 * with the cheapest single-square changes that fix it, where "cheapest"
 * is the loss in log-probability.
 *
 * Repair order is deliberate: kings first (tightest constraint, most
 * often violated), then back-rank pawns (a local fix that never
 * cascades), then the counting constraints.
 *
 * ## This is a port
 *
 * `chess-vision/src/chess_vision/decode.py` is the reference and
 * `chess-vision/tests/test_decode.py` is the specification. This file,
 * that one, and `Scan/Chess/ChessPositionDecoder.swift` must agree square
 * for square. Change behaviour in all three or in none.
 */
object ChessPositionDecoder {

    /**
     * The decoder's alphabet: `0` = empty, otherwise `1 + ChessPiece.index`,
     * giving `wP=1 … wK=6, bP=7 … bK=12`.
     *
     * The `1 +` offset makes decoding a square a one-liner, and it is the
     * same numbering the models are trained against — see
     * `chess_vision/squares.py`.
     */
    const val EMPTY = 0
    const val CLASS_COUNT = 13

    private const val WHITE_PAWN = 1
    private const val WHITE_KING = 6
    private const val BLACK_PAWN = 7
    private const val BLACK_KING = 12

    /**
     * Upper bound on repair rounds. Two is enough for every input tested;
     * the cap exists so a future change cannot turn a bug into a hang.
     */
    private const val MAX_REPAIR_ROUNDS = 4

    /** Starting complement per kind, indexed by `ChessPieceKind.ordinal`. */
    private val STARTING_COUNT = intArrayOf(8, 2, 2, 2, 1, 1)

    fun pieceFor(label: Int): ChessPiece? =
        if (label == EMPTY) null else ChessPiece(label - 1)

    fun labelFor(piece: ChessPiece): Int = piece.index + 1

    /**
     * Decode 64 per-square distributions into a legal position.
     *
     * @param probabilities 64 arrays of [CLASS_COUNT] probabilities, in
     *   LERF order (a1 first).
     */
    fun decode(probabilities: Array<DoubleArray>): ChessRecognizedPosition {
        require(probabilities.size == 64) { "expected 64 squares, got ${probabilities.size}" }

        val logs = Array(64) { square ->
            val row = probabilities[square]
            require(row.size == CLASS_COUNT) { "expected $CLASS_COUNT classes, got ${row.size}" }
            DoubleArray(CLASS_COUNT) { ln(max(row[it], 1e-12)) }
        }

        val labels = IntArray(64) { square ->
            (0 until CLASS_COUNT).maxByOrNull { logs[square][it] } ?: EMPTY
        }
        val repairs = mutableListOf<String>()

        // Repair to a fixed point. Each pass is designed not to create
        // work for the others, but that is an argument, not a proof — and
        // the cost of being wrong is an illegal position reaching the
        // engine. Iterating until nothing changes turns the argument into
        // a guarantee; in practice the second round is a no-op.
        // A `for` loop, not `repeat`: `return@repeat` returns from the
        // *iteration*, not the loop, so it would never break early.
        for (round in 0 until MAX_REPAIR_ROUNDS) {
            val before = labels.copyOf()
            repairKings(labels, logs, repairs)
            repairBackRankPawns(labels, logs, repairs)
            repairCounts(labels, logs, repairs)
            if (labels.contentEquals(before)) break
        }

        return ChessRecognizedPosition(
            placement = Array(64) { pieceFor(labels[it]) },
            confidence = DoubleArray(64) { probabilities[it][labels[it]] },
            repairs = repairs,
        )
    }

    /** Softmax, for callers holding raw model output. */
    fun probabilitiesFromLogits(logits: Array<DoubleArray>): Array<DoubleArray> =
        Array(logits.size) { row ->
            val values = logits[row]
            val peak = values.max()
            val exponentials = DoubleArray(values.size) { exp(values[it] - peak) }
            val total = exponentials.sum()
            DoubleArray(values.size) { exponentials[it] / total }
        }

    // MARK: - Repairs

    /** Log-probability given up by relabelling one square. */
    private fun cost(logs: Array<DoubleArray>, square: Int, from: Int, to: Int): Double =
        logs[square][from] - logs[square][to]

    /**
     * Best class for [square] that isn't excluded.
     *
     * **Kings are always excluded**, on top of whatever the caller asks
     * for. This is not a nicety: every caller runs *after* [repairKings]
     * has settled the king count, so relabelling a square as a king here
     * would silently break that invariant — nothing re-checks it, and a
     * board comes out with seven white kings.
     *
     * Uniform-noise input never exposes this (with all classes equal the
     * maximum lands on `empty`); genuinely random per-class probabilities
     * expose it immediately, which is what a real classifier produces.
     */
    private fun cheapestOther(logs: Array<DoubleArray>, square: Int, excluding: Set<Int>): Int {
        val forbidden = excluding + setOf(WHITE_KING, BLACK_KING)
        return (0 until CLASS_COUNT).filter { it !in forbidden }
            .maxByOrNull { logs[square][it] } ?: EMPTY
    }

    /**
     * Force exactly one king per colour.
     *
     * The two directions are asymmetric. Too many kings: demote the extras
     * to their next-best non-king class. Too few: promote whichever square
     * is *least* reluctant to become one.
     */
    private fun repairKings(
        labels: IntArray,
        logs: Array<DoubleArray>,
        repairs: MutableList<String>,
    ) {
        for ((kingClass, name) in listOf(WHITE_KING to "White", BLACK_KING to "Black")) {
            val squares = (0 until 64).filter { labels[it] == kingClass }

            if (squares.size > 1) {
                // Keep the most confident; demote the rest.
                squares.sortedByDescending { logs[it][kingClass] }.drop(1).forEach { square ->
                    labels[square] = cheapestOther(logs, square, setOf(kingClass))
                }
                repairs.add("$name had ${squares.size} kings — kept the most confident.")
            } else if (squares.isEmpty()) {
                // Never take the square the *other* king is standing on.
                // Without this, a read with no kings at all promotes
                // White's king onto the cheapest square and then Black's
                // onto the same one — the cost function has no idea it was
                // just claimed — and the board comes back with one king.
                // Uniform-noise input hits this every time, because every
                // square costs exactly the same.
                val otherKing = if (kingClass == WHITE_KING) BLACK_KING else WHITE_KING
                val best = (0 until 64)
                    .filter { labels[it] != otherKing }
                    .minByOrNull { cost(logs, it, labels[it], kingClass) }
                if (best != null) {
                    labels[best] = kingClass
                    repairs.add("$name had no king — added one where it fit best.")
                }
            }
        }
    }

    /**
     * No pawn may stand on rank 1 or 8.
     *
     * A purely local fix, and done *before* the counting pass: a
     * hallucinated back-rank pawn would otherwise be counted against the
     * pawn budget and push a legitimate pawn out instead.
     */
    private fun repairBackRankPawns(
        labels: IntArray,
        logs: Array<DoubleArray>,
        repairs: MutableList<String>,
    ) {
        for (square in 0 until 64) {
            val rank = square shr 3
            if (rank != 0 && rank != 7) continue
            if (labels[square] != WHITE_PAWN && labels[square] != BLACK_PAWN) continue

            labels[square] = cheapestOther(logs, square, setOf(WHITE_PAWN, BLACK_PAWN))
            repairs.add(
                "A pawn on ${ChessSquare(square).algebraic} can't exist — relabelled it."
            )
        }
    }

    /**
     * Pawn, piece and promotion budgets, per colour.
     *
     * Surplus squares are set to **empty**, never relabelled to the
     * next-best class. Relabelling looks smarter and is not: the
     * replacement is free to be a back-rank pawn, or a piece of the
     * *other* colour whose budget was settled on the previous pass, so
     * each fix quietly creates work for a pass that has already run.
     * Emptying is monotone — it can only reduce counts — which is what
     * makes this pass end in a legal position rather than merely a
     * different one.
     */
    private fun repairCounts(
        labels: IntArray,
        logs: Array<DoubleArray>,
        repairs: MutableList<String>,
    ) {
        for (colour in 0 until 2) {
            val base = 1 + colour * 6 // this colour's pawn class
            val name = if (colour == 0) "White" else "Black"

            // Squares holding `kind`, least confident first.
            fun squaresOf(kind: Int): List<Int> {
                val target = base + kind
                return (0 until 64).filter { labels[it] == target }
                    .sortedBy { logs[it][labels[it]] }
            }

            // 1. at most 8 pawns
            val pawns = squaresOf(0).toMutableList()
            while (pawns.size > 8) {
                val square = pawns.removeAt(0)
                labels[square] = EMPTY
                repairs.add("$name had too many pawns — removed the least certain.")
            }

            // 2. promotions must be affordable: every piece above the
            //    starting complement costs one missing pawn.
            for (kind in intArrayOf(4, 3, 2, 1)) { // queens first: commonest misread
                while (true) {
                    val pawnCount = squaresOf(0).size
                    val extras = intArrayOf(1, 2, 3, 4).sumOf {
                        max(0, squaresOf(it).size - STARTING_COUNT[it])
                    }
                    if (extras <= 8 - pawnCount) break

                    val candidates = squaresOf(kind)
                    if (candidates.size <= STARTING_COUNT[kind]) break
                    val square = candidates[0]
                    labels[square] = EMPTY
                    repairs.add(
                        "$name had more promoted pieces than missing pawns — removed one."
                    )
                }
            }

            // 3. at most 16 pieces
            while (true) {
                val owned = (0 until 64).filter { labels[it] in base..(base + 5) }
                if (owned.size <= 16) break
                // Never remove the king — mandatory, and already unique.
                val square = owned.filter { labels[it] != base + 5 }
                    .minByOrNull { logs[it][labels[it]] } ?: break
                labels[square] = EMPTY
                repairs.add("$name had too many pieces — cleared the least certain square.")
            }
        }
    }
}

/** What the recogniser produced, after decoding. */
data class ChessRecognizedPosition(
    /**
     * 64 entries in LERF order, a1 first — the shape the [ChessPosition]
     * placement constructor takes.
     */
    val placement: Array<ChessPiece?>,
    /** Probability of the chosen class, per square. */
    val confidence: DoubleArray,
    /**
     * One note per repair applied. Shown in the editor's "what was
     * adjusted" disclosure so a surprising board is explainable rather
     * than mysterious.
     */
    val repairs: List<String>,
) {
    /**
     * Squares ordered least-confident first — the editor rings the first
     * few so the user proof-reads the shakiest rather than all sixty-four.
     */
    val weakestSquares: List<ChessSquare>
        get() = (0 until 64).sortedBy { confidence[it] }.map { ChessSquare(it) }

    /**
     * Build a position. Side to move, castling rights and the en-passant
     * file are **not** set — a photograph cannot show them, so they stay
     * user input.
     */
    fun position(sideToMove: ChessColor = ChessColor.WHITE): ChessPosition =
        ChessPosition(placement = placement, sideToMove = sideToMove)

    // Arrays don't get structural equality for free, and this type is held
    // in Compose state where identity comparisons decide recomposition.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChessRecognizedPosition) return false
        return placement.contentEquals(other.placement) &&
            confidence.contentEquals(other.confidence) &&
            repairs == other.repairs
    }

    override fun hashCode(): Int =
        (placement.contentHashCode() * 31 + confidence.contentHashCode()) * 31 + repairs.hashCode()
}

/**
 * Rights that are at least *possible* given where the kings and rooks
 * stand. Not proof they exist — only that they aren't ruled out.
 *
 * A photo cannot show castling rights, but it can bound them: offering
 * "both sides" when the king has clearly moved is a worse default than
 * offering none.
 */
fun plausibleCastlingRights(placement: Array<ChessPiece?>): ChessCastlingRights {
    var rights = ChessCastlingRights.NONE
    fun at(square: ChessSquare) = placement[square.index]

    if (at(ChessSquare.E1) == ChessPiece(ChessColor.WHITE, ChessPieceKind.KING)) {
        if (at(ChessSquare.H1) == ChessPiece(ChessColor.WHITE, ChessPieceKind.ROOK)) {
            rights = rights or ChessCastlingRights.WHITE_KINGSIDE
        }
        if (at(ChessSquare.A1) == ChessPiece(ChessColor.WHITE, ChessPieceKind.ROOK)) {
            rights = rights or ChessCastlingRights.WHITE_QUEENSIDE
        }
    }
    if (at(ChessSquare.E8) == ChessPiece(ChessColor.BLACK, ChessPieceKind.KING)) {
        if (at(ChessSquare.H8) == ChessPiece(ChessColor.BLACK, ChessPieceKind.ROOK)) {
            rights = rights or ChessCastlingRights.BLACK_KINGSIDE
        }
        if (at(ChessSquare.A8) == ChessPiece(ChessColor.BLACK, ChessPieceKind.ROOK)) {
            rights = rights or ChessCastlingRights.BLACK_QUEENSIDE
        }
    }
    return rights
}
