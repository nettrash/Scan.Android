package me.nettrash.scan.chess

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Alpha-beta search with iterative deepening. Port of
 * `Scan/Chess/ChessSearch.swift`.
 *
 * ## Shape
 *
 * Negamax with a fail-soft alpha-beta window, driven by iterative
 * deepening: search to depth 1, then 2, then 3… until the time budget runs
 * out. That sounds wasteful — the re-searches really do repeat work — but
 * it pays for itself twice over. The shallow passes fill the transposition
 * table with good moves, so each deeper pass orders its moves far better
 * than it could from scratch; and the clock can stop at *any* moment and
 * still leave a complete, usable answer from the last finished depth.
 *
 * That second property is what the feature needs. A user pointing a phone
 * at a board wants an answer in about a second, and "the best move found
 * in the time available" is exactly the right contract.
 *
 * ## The trimmings, and why each is here
 *
 * - **Transposition table** — the same position arrives by many move
 *   orders; without it the branching factor stays near 35.
 * - **Move ordering** (TT move → captures by MVV-LVA → killers → history)
 *   — alpha-beta's whole power comes from searching the best move first.
 *   This matters far more than any evaluation term.
 * - **Quiescence** — stopping mid-capture-sequence produces wildly wrong
 *   scores ("I'm a queen up!" one ply before it is recaptured).
 * - **Null-move pruning** and **late-move reductions** — the two cheapest
 *   ways to buy depth. Both are heuristics that can miss things, so both
 *   are disabled where they are known to be unsound (in check, and in
 *   zugzwang-prone pawn endings).
 *
 * ## Cancellation
 *
 * [stop] is safe from any thread. The search checks it, and the deadline,
 * every 2048 nodes. Once aborted the in-flight iteration's result is
 * **discarded** — a half-finished depth has searched only some root moves
 * and its "best" is meaningless.
 */
class ChessSearch {

    // MARK: - Public shape

    data class Limits(
        /**
         * Wall-clock budget in milliseconds. The search returns as soon
         * after this as it notices; it does not overshoot by more than a
         * few milliseconds.
         */
        val thinkTimeMillis: Long = 1000L,
        /** Hard depth cap, mostly for tests that want determinism. */
        val maximumDepth: Int = MAX_PLY - 8,
    )

    data class Result(
        val bestMove: ChessMove,
        /** Centipawns from the searched side's point of view. */
        val score: Int,
        /**
         * Non-null when the score is a forced mate. Positive = the side to
         * move delivers mate in this many *moves*; negative = gets mated.
         */
        val mateInMoves: Int?,
        /** The expected continuation, starting with [bestMove]. */
        val principalVariation: List<ChessMove>,
        val depth: Int,
        val nodes: Long,
        val elapsedMillis: Long,
    )

    // MARK: - Transposition table

    private enum class Bound {
        /** The score is exact — this node's true value. */
        EXACT,

        /** The search failed high; the true score is at least this. */
        LOWER,

        /** The search failed low; the true score is at most this. */
        UPPER,
    }

    /**
     * Entries are held in parallel primitive arrays rather than as objects.
     * 2^18 entries of a 4-field class would be a quarter of a million
     * allocations sitting in the old generation for the life of the app;
     * four flat arrays are the same data with no GC involvement.
     */
    private val ttKey = LongArray(TABLE_SIZE)
    private val ttMoveRaw = IntArray(TABLE_SIZE)
    private val ttScore = IntArray(TABLE_SIZE)

    /** Packs depth (low 16 bits) and bound ordinal (next 2). */
    private val ttMeta = IntArray(TABLE_SIZE)

    // MARK: - Search state

    private var nodes = 0L
    private var deadlineNanos = Long.MAX_VALUE
    private var aborted = false
    private val stopRequested = AtomicBoolean(false)

    /**
     * Two killer moves per ply: quiet moves that caused a cutoff at this
     * depth elsewhere in the tree. Cheap, and startlingly effective.
     */
    private val killers = IntArray(MAX_PLY * 2)

    /**
     * `history[colour][from * 64 + to]` — how often a quiet move has caused
     * a cutoff anywhere. Orders the quiet moves killers miss.
     */
    private val history = Array(2) { IntArray(64 * 64) }

    /**
     * Triangular principal-variation table. Reading the PV back out of the
     * transposition table is the usual shortcut, but entries get
     * overwritten mid-search and the PV comes back truncated or, worse,
     * containing an illegal move from a colliding position. The UI
     * displays this line, so it needs to be real.
     */
    private val pvTable = IntArray(MAX_PLY * MAX_PLY)
    private val pvLength = IntArray(MAX_PLY)

    /** Zobrist keys along the current path, for repetition detection. */
    private val pathKeys = LongArray(MAX_PLY + 256)

    /** Ask the search to stop. Safe from any thread. */
    fun stop() {
        stopRequested.set(true)
    }

    // MARK: - Entry point

    /**
     * Search [position] and return the best move found within [limits].
     *
     * Returns null only when the side to move has no legal move at all —
     * checkmate or stalemate. Callers distinguish the two via
     * `position.isInCheck`.
     *
     * The position is copied, so the caller's object is untouched.
     */
    fun search(position: ChessPosition, limits: Limits = Limits()): Result? {
        val root = position.copy()
        val rootMoves = ChessMoveGenerator.generateLegal(root)
        if (rootMoves.count == 0) return null

        stopRequested.set(false)
        aborted = false
        nodes = 0
        val startedNanos = System.nanoTime()
        deadlineNanos = startedNanos + limits.thinkTimeMillis * 1_000_000L

        // History and killers persist across the iterative-deepening passes
        // of a single search (that is the point of them) but must not leak
        // between two unrelated positions.
        killers.fill(0)
        history[0].fill(0)
        history[1].fill(0)

        // A legal move to fall back on if we are stopped before depth 1.
        var best = Result(
            bestMove = rootMoves[0],
            score = 0,
            mateInMoves = null,
            principalVariation = listOf(rootMoves[0]),
            depth = 0,
            nodes = 0,
            elapsedMillis = 0,
        )

        val maximumDepth = minOf(limits.maximumDepth, MAX_PLY - 8)
        for (depth in 1..maxOf(1, maximumDepth)) {
            pathKeys[0] = root.key
            val score = alphaBeta(root, depth, -INFINITY, INFINITY, 0, allowNull = true)

            // A cut-off iteration has only looked at some root moves; its
            // "best" is not comparable with the completed depth below it.
            if (aborted) break

            val line = principalVariation()
            best = Result(
                bestMove = line.firstOrNull() ?: best.bestMove,
                score = score,
                mateInMoves = mateDistance(score),
                principalVariation = line,
                depth = depth,
                nodes = nodes,
                elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L,
            )

            // A forced mate ends the useful search — but only once the main
            // search is deep enough to have actually walked the mating
            // line.
            //
            // Quiescence finds mates too (it generates every evasion when
            // in check), so a mate can surface at depth 1 with a correct
            // *score* and no line behind it: quiescence keeps no principal
            // variation. Stopping there would report "M3" next to a
            // one-move line and a search depth of 1.
            if (isMateScore(score)) {
                val pliesToMate = MATE_SCORE - abs(score)
                if (depth >= pliesToMate) break
            }
        }

        return best.copy(
            nodes = nodes,
            elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L,
        )
    }

    private fun principalVariation(): List<ChessMove> {
        if (pvLength[0] <= 0) return emptyList()
        return (0 until pvLength[0]).map { ChessMove(pvTable[it]) }
    }

    private fun checkTime() {
        if (aborted) return
        if (System.nanoTime() >= deadlineNanos || stopRequested.get()) aborted = true
    }

    // MARK: - Alpha-beta

    private fun alphaBeta(
        position: ChessPosition,
        depthIn: Int,
        alphaIn: Int,
        beta: Int,
        ply: Int,
        allowNull: Boolean,
    ): Int {
        var alpha = alphaIn
        pvLength[ply] = ply

        if (aborted) return 0
        nodes += 1
        if (nodes and 2047L == 0L) checkTime()

        val isRoot = ply == 0
        val inCheck = position.isInCheck
        // A "PV node" is one searched with a real window rather than the
        // null window PVS uses for moves it expects to refute — these are
        // the nodes whose moves reach the principal variation.
        val isPrincipalVariationNode = beta - alpha > 1

        if (!isRoot) {
            // Draws. Checked before anything else so the search never
            // reports a won position it can only reach by repeating.
            if (isRepetition(position, ply) ||
                position.halfmoveClock >= 100 ||
                position.hasInsufficientMaterial
            ) {
                return 0
            }

            // Mate-distance pruning: if we already have a mate faster than
            // anything this subtree could produce, stop looking.
            val mateAlpha = maxOf(alpha, -MATE_SCORE + ply)
            val mateBeta = minOf(beta, MATE_SCORE - ply - 1)
            if (mateAlpha >= mateBeta) return mateAlpha
            alpha = mateAlpha
        }

        // Being in check is never a quiet position, so extend rather than
        // dropping into quiescence — otherwise the search happily walks
        // into forced mates it never sees.
        var depth = depthIn
        if (inCheck) depth += 1

        if (depth <= 0) return quiescence(position, alpha, beta, ply)

        // --- Transposition table probe ---
        //
        // Take the score cutoff only at non-PV nodes, but always keep the
        // stored move for ordering. Since the table is filled by the
        // shallower iterative-deepening passes, an unrestricted cutoff here
        // truncates the displayed line on nearly every deep search. PV
        // nodes are a vanishing fraction of the tree, so the lost prunes
        // cost very little.
        // Masking the low bits rather than taking a remainder: the table
        // size is a power of two, so the two are equivalent — and the mask
        // is both always non-negative (a `%` on a negative Long is not)
        // and bit-for-bit what the Swift port computes, so both engines
        // resolve collisions the same way and pick the same move.
        val index = (position.key and (TABLE_SIZE - 1).toLong()).toInt()
        var ttMove = ChessMove.NONE
        if (ttKey[index] == position.key) {
            ttMove = ChessMove(ttMoveRaw[index])
            val entryDepth = ttMeta[index] and 0xFFFF
            if (!isRoot && !isPrincipalVariationNode && entryDepth >= depth) {
                val score = scoreFromTable(ttScore[index], ply)
                when (Bound.entries[(ttMeta[index] ushr 16) and 0x3]) {
                    Bound.EXACT -> return score
                    Bound.LOWER -> if (score >= beta) return score
                    Bound.UPPER -> if (score <= alpha) return score
                }
            }
        }

        // --- Null-move pruning ---
        //
        // "If I pass and you still can't hurt me, this position is good
        // enough to cut." Unsound in zugzwang, where passing beats every
        // real move — so it is disabled when the side to move has only
        // pawns left, which is where zugzwang lives.
        if (allowNull && !inCheck && !isRoot && depth >= 3 && beta < MATE_SCORE - MAX_PLY) {
            val us = position.sideToMove
            val heavyMaterial = position.pieces(us).count() -
                position.pieces(us, ChessPieceKind.PAWN).count() - 1
            if (heavyMaterial > 0) {
                val reduction = 2 + depth / 6
                position.makeNull()
                pathKeys[ply + 1] = position.key
                val score = -alphaBeta(
                    position, depth - 1 - reduction, -beta, -beta + 1, ply + 1, allowNull = false,
                )
                position.unmakeNull()
                if (aborted) return 0
                if (score >= beta) {
                    // Don't return a mate score discovered through a null
                    // move — the "mate" may depend on the free tempo.
                    return if (isMateScore(score)) beta else score
                }
            }
        }

        val moves = ChessMoveGenerator.generateLegal(position)
        if (moves.count == 0) {
            // Mate scores shrink with depth so a mate in 2 beats a mate in
            // 4; stalemate is a plain draw.
            return if (inCheck) -MATE_SCORE + ply else 0
        }

        val scores = IntArray(moves.count)
        for (i in 0 until moves.count) {
            scores[i] = orderingScore(moves[i], position, ttMove, ply)
        }

        var bestScore = -INFINITY
        var bestMove = ChessMove.NONE
        var bound = Bound.UPPER
        val mover = position.sideToMove

        for (i in 0 until moves.count) {
            selectNext(i, moves, scores)
            val move = moves[i]

            position.make(move)
            pathKeys[ply + 1] = position.key

            var score: Int
            if (i == 0) {
                // The first move gets the full window — it is our best
                // guess at the PV and deserves an exact score.
                score = -alphaBeta(position, depth - 1, -beta, -alpha, ply + 1, allowNull = true)
            } else {
                // --- Late-move reductions ---
                //
                // Moves this far down a well-ordered list are rarely best.
                // Search them shallower first; if one surprises us by
                // beating alpha, re-search it properly.
                var reduction = 0
                if (depth >= 3 && i >= 4 && !inCheck &&
                    !move.isCapture && !move.isPromotion && !position.isInCheck
                ) {
                    reduction = 1 + (if (depth >= 6 && i >= 8) 1 else 0)
                }

                score = -alphaBeta(
                    position, depth - 1 - reduction, -alpha - 1, -alpha, ply + 1, allowNull = true,
                )

                // Re-search at full depth/window if the null-window probe
                // suggests this move is actually good.
                if (score > alpha && reduction > 0) {
                    score = -alphaBeta(
                        position, depth - 1, -alpha - 1, -alpha, ply + 1, allowNull = true,
                    )
                }
                if (score > alpha && score < beta) {
                    score = -alphaBeta(position, depth - 1, -beta, -alpha, ply + 1, allowNull = true)
                }
            }

            position.unmake(move)
            if (aborted) return 0

            if (score > bestScore) {
                bestScore = score
                bestMove = move

                if (score > alpha) {
                    alpha = score
                    bound = Bound.EXACT
                    updatePrincipalVariation(move, ply)

                    if (score >= beta) {
                        // Beta cutoff. Remember quiet moves that cause
                        // cutoffs — they tend to do it again elsewhere.
                        if (!move.isCapture) {
                            if (killers[ply * 2] != move.rawValue) {
                                killers[ply * 2 + 1] = killers[ply * 2]
                                killers[ply * 2] = move.rawValue
                            }
                            history[mover.ordinal][move.fromIndex * 64 + move.toIndex] +=
                                depth * depth
                        }
                        bound = Bound.LOWER
                        break
                    }
                }
            }
        }

        store(index, position.key, bestMove, bestScore, depth, bound, ply)
        return bestScore
    }

    // MARK: - Quiescence

    /**
     * Search only the "loud" moves until the position is quiet enough to
     * evaluate — otherwise every score is an artefact of where the clock
     * happened to stop.
     */
    private fun quiescence(position: ChessPosition, alphaIn: Int, beta: Int, ply: Int): Int {
        var alpha = alphaIn

        if (aborted) return 0
        nodes += 1
        if (nodes and 2047L == 0L) checkTime()
        if (ply >= MAX_PLY - 1) return ChessEvaluation.evaluate(position)

        val inCheck = position.isInCheck

        // In check there is no such thing as standing pat — every evasion
        // must be considered or the search can miss being mated.
        if (!inCheck) {
            val standPat = ChessEvaluation.evaluate(position)
            if (standPat >= beta) return standPat
            if (standPat > alpha) alpha = standPat
        }

        val moves = ChessMoveGenerator.generateLegal(
            position,
            if (inCheck) ChessMoveGenerator.Mode.ALL
            else ChessMoveGenerator.Mode.CAPTURES_AND_PROMOTIONS,
        )

        if (moves.count == 0) {
            // Only meaningful when in check: no evasions means mate.
            return if (inCheck) -MATE_SCORE + ply else ChessEvaluation.evaluate(position)
        }

        val scores = IntArray(moves.count)
        for (i in 0 until moves.count) {
            scores[i] = orderingScore(moves[i], position, ChessMove.NONE, ply)
        }

        var bestScore = if (inCheck) -INFINITY else ChessEvaluation.evaluate(position)

        for (i in 0 until moves.count) {
            selectNext(i, moves, scores)
            val move = moves[i]

            position.make(move)
            val score = -quiescence(position, -beta, -alpha, ply + 1)
            position.unmake(move)
            if (aborted) return 0

            if (score > bestScore) {
                bestScore = score
                if (score > alpha) alpha = score
                if (score >= beta) break
            }
        }

        return bestScore
    }

    // MARK: - Move ordering

    /**
     * Rank a move without searching it. The single highest-leverage
     * function in the file: alpha-beta prunes in proportion to how often
     * the best move is tried first.
     */
    private fun orderingScore(
        move: ChessMove,
        position: ChessPosition,
        ttMove: ChessMove,
        ply: Int,
    ): Int {
        // The table's move already proved good in a previous iteration.
        if (move == ttMove) return 10_000_000

        if (move.isPromotion) {
            val kind = move.promotionKind ?: ChessPieceKind.QUEEN
            return 8_000_000 + ChessEvaluation.CAPTURE_VALUE[kind.ordinal]
        }

        if (move.isCapture) {
            // MVV-LVA: grab the most valuable victim with the least
            // valuable attacker. Multiplying the victim by 16 keeps it
            // dominant while the attacker breaks ties.
            val victim = if (move.isEnPassant) {
                ChessEvaluation.CAPTURE_VALUE[ChessPieceKind.PAWN.ordinal]
            } else {
                position.pieceAt(move.toIndex)
                    ?.let { ChessEvaluation.CAPTURE_VALUE[it.kind.ordinal] } ?: 0
            }
            val attacker = position.pieceAt(move.fromIndex)
                ?.let { ChessEvaluation.CAPTURE_VALUE[it.kind.ordinal] } ?: 0
            return 5_000_000 + victim * 16 - attacker
        }

        if (move.rawValue == killers[ply * 2]) return 4_000_000
        if (move.rawValue == killers[ply * 2 + 1]) return 3_900_000

        return history[position.sideToMove.ordinal][move.fromIndex * 64 + move.toIndex]
    }

    /**
     * Selection sort, one move at a time.
     *
     * Cheaper than sorting the whole list up front, because a beta cutoff
     * usually arrives within the first few moves and the rest are never
     * looked at.
     */
    private fun selectNext(index: Int, moves: ChessMoveList, scores: IntArray) {
        var best = index
        for (candidate in (index + 1) until moves.count) {
            if (scores[candidate] > scores[best]) best = candidate
        }
        if (best != index) {
            moves.swap(index, best)
            val tmp = scores[index]
            scores[index] = scores[best]
            scores[best] = tmp
        }
    }

    // MARK: - Bookkeeping

    private fun updatePrincipalVariation(move: ChessMove, ply: Int) {
        pvTable[ply * MAX_PLY + ply] = move.rawValue
        if (pvLength[ply + 1] > ply + 1) {
            for (next in (ply + 1) until pvLength[ply + 1]) {
                pvTable[ply * MAX_PLY + next] = pvTable[(ply + 1) * MAX_PLY + next]
            }
        }
        pvLength[ply] = maxOf(pvLength[ply + 1], ply + 1)
        // The root's line needs no special handling: at ply 0 the row
        // offset is zero, so it already lives at pvTable[0...], which is
        // where `principalVariation()` reads it from.
    }

    private fun isRepetition(position: ChessPosition, ply: Int): Boolean {
        if (ply <= 0) return false
        // Only positions since the last irreversible move can repeat, and
        // the halfmove clock says exactly how far back that is.
        val reach = minOf(ply, position.halfmoveClock)
        if (reach < 2) return false
        var back = 2
        while (back <= reach) {
            if (pathKeys[ply - back] == position.key) return true
            back += 2 // only same-side-to-move positions can repeat
        }
        return false
    }

    /**
     * Mate scores are stored relative to the node they were found at, so
     * the same entry means the same thing wherever it is probed from.
     */
    private fun scoreForTable(score: Int, ply: Int): Int = when {
        score >= MATE_SCORE - MAX_PLY -> score + ply
        score <= -MATE_SCORE + MAX_PLY -> score - ply
        else -> score
    }

    private fun scoreFromTable(score: Int, ply: Int): Int = when {
        score >= MATE_SCORE - MAX_PLY -> score - ply
        score <= -MATE_SCORE + MAX_PLY -> score + ply
        else -> score
    }

    private fun store(
        index: Int,
        key: Long,
        move: ChessMove,
        score: Int,
        depth: Int,
        bound: Bound,
        ply: Int,
    ) {
        // Depth-preferred replacement: a deeper result is worth more than a
        // more recent one, but always take the slot if it belongs to a
        // different position.
        if (ttKey[index] == key && (ttMeta[index] and 0xFFFF) > depth) return
        ttKey[index] = key
        ttMoveRaw[index] = move.rawValue
        ttScore[index] = scoreForTable(score, ply)
        ttMeta[index] = (depth and 0xFFFF) or (bound.ordinal shl 16)
    }

    companion object {
        const val MAX_PLY = 64

        /** Comfortably beyond any real evaluation, so it can act as ±∞. */
        const val INFINITY = 32_000

        /**
         * Mate at ply 0. Mates found deeper score slightly less, so the
         * search prefers the quickest one.
         */
        const val MATE_SCORE = 30_000

        /**
         * 2^18 entries across four arrays ≈ 5 MB. Big enough to matter at
         * these depths, small enough that a phone analysing in the
         * background is not a problem.
         */
        private const val TABLE_SIZE = 1 shl 18

        /** Scores at or beyond this magnitude encode a forced mate. */
        fun isMateScore(score: Int): Boolean = abs(score) >= MATE_SCORE - MAX_PLY

        /** Convert a mate-flavoured score into a move count, or null. */
        fun mateDistance(score: Int): Int? {
            if (!isMateScore(score)) return null
            val plies = MATE_SCORE - abs(score)
            // Plies to moves, rounding up: mate delivered on ply 1 is
            // "mate in 1", plies 2 and 3 are both "mate in 2".
            val moves = (plies + 1) / 2
            return if (score > 0) moves else -moves
        }
    }
}
