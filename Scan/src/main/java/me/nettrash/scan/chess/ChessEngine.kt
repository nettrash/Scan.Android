package me.nettrash.scan.chess

import java.util.Locale

/**
 * The engine's public face — what the Chess tab talks to. Port of
 * `Scan/Chess/ChessEngine.swift`.
 *
 * ## Why "both sides" is the primary API
 *
 * A scanned board has no move history, so nothing in the photo says whose
 * turn it is. The feature's promise is "here's the best move for White,
 * and the best move for Black", which means running two searches over the
 * same placement with the side-to-move flipped between them.
 *
 * The wrinkle is that a given board is often legal for only *one* side to
 * move: if Black is standing in check, it cannot be White's turn, because
 * Black would have had to leave its king en prise. So each side gets its
 * own outcome, and "not a legal position with this side to move" is a
 * first-class answer rather than an error — it is the correct, informative
 * response for roughly half of all real positions.
 *
 * ## Threading
 *
 * [analyse] is synchronous and CPU-bound; call it off the main thread (the
 * ViewModel wraps it in `Dispatchers.Default`). [stop] is safe from any
 * thread, so a user editing a square mid-search doesn't wait for the old
 * one.
 */

// MARK: - Result types

/** What the engine can say about one side's best move. */
sealed class ChessAnalysisOutcome {
    /** A move was found. */
    data class Move(val analysis: ChessAnalysis) : ChessAnalysisOutcome()

    /** The side to move has no legal move — the game is already over. */
    data class Terminal(val outcome: ChessOutcome) : ChessAnalysisOutcome()

    /**
     * The board is not a legal chess position with this side to move.
     * Carries the specific problems so the UI can tell the user what to fix
     * in the editor.
     */
    data class NotLegal(val problems: List<ChessLegalityProblem>) : ChessAnalysisOutcome()
}

data class ChessAnalysis(
    /** The move to play. */
    val move: ChessMove,
    /**
     * SAN for [move], e.g. "Nxe5+". Precomputed because rendering it needs
     * the position, which the UI no longer has by then.
     */
    val san: String,
    /**
     * Centipawns, **from this side's point of view** — positive is good for
     * the side that was asked. See [displayScore] for the White-relative
     * form conventionally shown to users.
     */
    val score: Int,
    /**
     * Set when the score is a forced mate: positive = this side mates in N
     * moves, negative = this side gets mated in N.
     */
    val mateInMoves: Int?,
    /** The expected continuation, starting with [move]. */
    val principalVariation: List<ChessMove>,
    /** The line in readable form: "15. Nf3 Bd6 16. O-O". */
    val principalVariationSan: String,
    val depth: Int,
    val nodes: Long,
    val elapsedMillis: Long,
    /** The side this analysis is for. */
    val side: ChessColor,
) {
    /**
     * Score in pawns from **White's** perspective, the convention every
     * chess UI uses: "+1.4" means White is better regardless of who is to
     * move.
     */
    val whiteRelativePawns: Double
        get() = (if (side == ChessColor.WHITE) score else -score) / 100.0

    /** Ready-to-display evaluation: "+1.4", "-0.3", "M3", "-M2". */
    val displayScore: String
        get() {
            mateInMoves?.let { mate ->
                // Flip to White's perspective for display, same as the
                // numeric score.
                val whiteRelative = if (side == ChessColor.WHITE) mate else -mate
                return if (whiteRelative > 0) "M$whiteRelative" else "-M${-whiteRelative}"
            }
            return String.format(Locale.US, "%+.1f", whiteRelativePawns)
        }
}

/** Both sides' answers for one board. */
data class ChessBothSidesAnalysis(
    val white: ChessAnalysisOutcome,
    val black: ChessAnalysisOutcome,
    /** The placement analysed, without a side to move. */
    val placementFen: String,
) {
    fun outcomeFor(side: ChessColor): ChessAnalysisOutcome =
        if (side == ChessColor.WHITE) white else black
}

// MARK: - Engine

class ChessEngine {

    /**
     * How long to think per side. The UI exposes these three; the default
     * matches the "point the phone and get an answer" feel the feature is
     * aiming for.
     */
    enum class ThinkTime(val millis: Long, val displayName: String) {
        FAST(200L, "Fast"),
        STANDARD(1000L, "Standard"),
        DEEP(3000L, "Deep"),
    }

    private val search = ChessSearch()

    /** Stop any in-flight search. Safe from any thread. */
    fun stop() = search.stop()

    /**
     * Analyse [position] for whichever side it says is to move.
     *
     * Synchronous and CPU-bound — do not call on the main thread.
     */
    fun analyse(
        position: ChessPosition,
        thinkTimeMillis: Long = ThinkTime.STANDARD.millis,
    ): ChessAnalysisOutcome {
        val problems = position.legalityProblems
        if (problems.isNotEmpty()) return ChessAnalysisOutcome.NotLegal(problems)

        val result = search.search(position, ChessSearch.Limits(thinkTimeMillis = thinkTimeMillis))
            ?: return ChessAnalysisOutcome.Terminal(
                if (position.isInCheck) ChessOutcome.CHECKMATE else ChessOutcome.STALEMATE
            )

        // Terminal-but-legal states the search doesn't report as "no move"
        // — it will still hand back a move in a dead-drawn ending.
        if (position.hasInsufficientMaterial) {
            return ChessAnalysisOutcome.Terminal(ChessOutcome.INSUFFICIENT_MATERIAL)
        }
        if (position.halfmoveClock >= 100) {
            return ChessAnalysisOutcome.Terminal(ChessOutcome.FIFTY_MOVE_RULE)
        }

        return ChessAnalysisOutcome.Move(
            ChessAnalysis(
                move = result.bestMove,
                san = position.san(result.bestMove),
                score = result.score,
                mateInMoves = result.mateInMoves,
                principalVariation = result.principalVariation,
                principalVariationSan = position.sanLine(result.principalVariation),
                depth = result.depth,
                nodes = result.nodes,
                elapsedMillis = result.elapsedMillis,
                side = position.sideToMove,
            )
        )
    }

    /**
     * Analyse [position] as if [side] were to move.
     *
     * Setting the side to move can make the board illegal (the opponent
     * would be standing in check), which is reported rather than searched —
     * see the file header.
     */
    fun analyse(
        position: ChessPosition,
        side: ChessColor,
        thinkTimeMillis: Long = ThinkTime.STANDARD.millis,
    ): ChessAnalysisOutcome {
        val flipped = position.copy()
        if (flipped.sideToMove != side) {
            flipped.setSideToMove(side)
            // The en-passant target belonged to the other side's last move;
            // it cannot survive handing the turn over, and leaving it would
            // generate a capture of a pawn that never moved.
            flipped.setEnPassantSquare(null)
        }
        return analyse(flipped, thinkTimeMillis)
    }

    /**
     * The feature's headline call: best move for White *and* for Black from
     * the same board.
     *
     * Runs the two searches back to back on the calling thread. Budget
     * accordingly — a `STANDARD` think time means this takes about two
     * seconds, not one.
     */
    fun analyseBothSides(
        position: ChessPosition,
        thinkTimeMillis: Long = ThinkTime.STANDARD.millis,
    ): ChessBothSidesAnalysis = ChessBothSidesAnalysis(
        white = analyse(position, ChessColor.WHITE, thinkTimeMillis),
        black = analyse(position, ChessColor.BLACK, thinkTimeMillis),
        placementFen = position.placementFen,
    )
}
