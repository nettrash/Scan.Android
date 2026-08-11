package me.nettrash.scan.chess

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.concurrent.thread
import org.junit.Test

/**
 * Search, evaluation, SAN, and the both-sides analysis the Chess tab calls.
 *
 * Where [ChessEngineTest] proves the *rules* are right (via perft), this
 * file proves the engine *plays* — that it finds forced mates, takes free
 * material, and reports what it found honestly.
 *
 * ## Mates are verified by playing them out
 *
 * Rather than trusting hand-picked puzzle solutions, the mate tests assert
 * two things together: that the engine reports a mate, and that its own
 * principal variation, played move by move on a real board, ends in
 * checkmate after exactly the number of moves it claimed. A search that
 * hallucinates a mate fails the playout; one that finds a mate but
 * misreports the distance fails the comparison. Neither can pass by
 * accident.
 *
 * ## Determinism
 *
 * Tests search to a fixed **depth**, never a fixed time. A time-limited
 * search returns whatever it reached before the clock ran out, which varies
 * with machine load and would make these flaky in CI.
 *
 * ## Cross-platform parity
 *
 * [evaluationMatchesTheSwiftPort] and [bestMoveMatchesTheSwiftPortAtFixedDepth]
 * pin values produced by the iOS engine. Two apps that read the same photo
 * must suggest the same move; these are what stop the ports drifting.
 */
class ChessSearchTest {

    // MARK: - Helpers

    /**
     * Search to a fixed depth. The generous think time is a safety valve,
     * not the limit — depth is what makes the result reproducible.
     */
    private fun search(fen: String, depth: Int): Pair<ChessPosition, ChessSearch.Result>? {
        val position = ChessPosition.fromFen(fen)
        assertWithMessage("could not parse FEN: %s", fen).that(position).isNotNull()
        val result = ChessSearch().search(
            position!!,
            ChessSearch.Limits(thinkTimeMillis = 60_000, maximumDepth = depth),
        ) ?: return null
        return position to result
    }

    /**
     * Play [line] from [position] and return how many plies it took to
     * reach checkmate, or null if it never does.
     */
    private fun pliesToMate(position: ChessPosition, line: List<ChessMove>): Int? {
        val board = position.copy()
        var plies = 0
        for (move in line) {
            val legal = ChessMoveGenerator.generateLegal(board.copy()).toList()
            if (move !in legal) {
                assertWithMessage("PV contains an illegal move: %s", move.uci).fail()
                return null
            }
            board.make(move)
            plies += 1
            if (board.outcome == ChessOutcome.CHECKMATE) return plies
        }
        return null
    }

    /**
     * Assert the engine finds a forced mate here, and that its own line
     * delivers exactly that mate.
     */
    private fun assertFindsMate(fen: String, inMoves: Int, depth: Int) {
        val (position, result) = search(fen, depth)
            ?: return assertWithMessage("no legal moves in %s", fen).fail()

        assertWithMessage("reported mate distance for %s", fen)
            .that(result.mateInMoves).isEqualTo(inMoves)

        val plies = pliesToMate(position, result.principalVariation)
        assertWithMessage(
            "PV %s does not mate in %s",
            result.principalVariation.map { it.uci }, fen,
        ).that(plies).isNotNull()

        assertWithMessage("played-out mate distance for %s", fen)
            .that((plies!! + 1) / 2).isEqualTo(inMoves)
    }

    // MARK: - Mate finding

    /** Back-rank mate: the king is sealed in by its own pawns. */
    @Test fun findsBackRankMateInOne() {
        assertFindsMate("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1", inMoves = 1, depth = 3)
    }

    /** Rook mate supported by the enemy king covering the escape squares. */
    @Test fun findsSupportedRookMateInOne() {
        assertFindsMate("4k3/8/4K3/8/8/8/8/7R w - - 0 1", inMoves = 1, depth = 3)
    }

    /**
     * Black to move, mating White — makes sure mate scoring isn't written
     * from White's point of view somewhere.
     */
    @Test fun findsMateInOneForBlack() {
        assertFindsMate("r5k1/8/8/8/8/8/5PPP/6K1 b - - 0 1", inMoves = 1, depth = 3)
    }

    /**
     * Two moves deep: the black rook can interpose on b8, which buys
     * exactly one move. 1.Ra8+ Rb8 2.Rxb8#.
     */
    @Test fun findsMateInTwo() {
        assertFindsMate("6k1/1r3ppp/8/8/8/8/5PPP/R5K1 w - - 0 1", inMoves = 2, depth = 6)
    }

    /**
     * A mate must outrank any amount of material.
     *
     * White can win a whole queen with Rxh6, or mate at once with Ra8#. The
     * black queen on h6 is deliberately placed where it can neither reach
     * the a8-g8 checking ray (its f8 diagonal is blocked by its own g7 pawn)
     * nor capture on a8 — so Ra8 really is mate.
     */
    @Test fun prefersMateOverMaterial() {
        val (_, result) = search("6k1/5ppp/7q/8/8/8/7R/R5K1 w - - 0 1", depth = 5)!!
        assertThat(result.mateInMoves).isEqualTo(1)
        assertWithMessage("mate beats winning a queen")
            .that(result.bestMove.uci).isEqualTo("a1a8")
    }

    // MARK: - Material

    /** The simplest sanity check there is: a queen is hanging, take it. */
    @Test fun takesAFreeQueen() {
        val (_, result) = search("4k3/8/8/3q4/8/8/8/3RK3 w - - 0 1", depth = 6)!!
        assertWithMessage("Rxd5 wins a queen for nothing")
            .that(result.bestMove.uci).isEqualTo("d1d5")
        assertThat(result.score).isGreaterThan(500)
    }

    /**
     * Quiescence's whole job: don't grab a defended pawn with the queen and
     * call it winning. Without a quiescence search the engine evaluates one
     * ply after the capture and thinks it is a pawn up.
     */
    @Test fun doesNotGrabADefendedPawnWithTheQueen() {
        // Queens are level; the black pawn on d5 is defended by c6.
        val (_, result) = search("3qk3/8/2p5/3p4/8/8/3Q4/4K3 w - - 0 1", depth = 6)!!
        assertWithMessage("Qxd5 loses the queen to cxd5")
            .that(result.bestMove.uci).isNotEqualTo("d2d5")
        assertThat(result.score).isLessThan(200)
    }

    /**
     * The evaluation is symmetric: mirroring a position vertically and
     * swapping colours must produce the same score for the side to move. An
     * asymmetry here would mean the engine plays one colour better than the
     * other — exactly what a "best move for White and for Black" feature
     * cannot afford.
     */
    @Test fun evaluationIsColourSymmetric() {
        val pairs = listOf(
            "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 0 1" to
                "rnbqk2r/pppp1ppp/5n2/2b1p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 0 1",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1" to
                "8/4p1p1/8/1r3P1K/kp5R/3P4/2P5/8 b - - 0 1",
        )
        for ((white, black) in pairs) {
            assertWithMessage("evaluation asymmetric between %s and %s", white, black)
                .that(ChessEvaluation.evaluate(ChessPosition.fromFen(white)!!))
                .isEqualTo(ChessEvaluation.evaluate(ChessPosition.fromFen(black)!!))
        }
    }

    @Test fun startingPositionEvaluatesAsBalanced() {
        assertThat(ChessEvaluation.evaluate(ChessPosition.standard())).isEqualTo(0)
    }

    // MARK: - Cross-platform parity

    /**
     * Evaluations produced by the iOS engine. The evaluation is a pure
     * function of the position, so these must match exactly — if they
     * don't, the two apps will grade the same board differently and, at
     * some depth, suggest different moves for the same photo.
     */
    @Test fun evaluationMatchesTheSwiftPort() {
        val expected = mapOf(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1" to 0,
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1" to 115,
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1" to -60,
            "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 0 1" to -22,
            "4k3/8/8/8/8/8/8/4K3 w - - 0 1" to 0,
            "6k1/5ppp/8/8/8/8/5PPP/6K1 w - - 0 1" to 0,
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10" to 0,
        )
        for ((fen, score) in expected) {
            assertWithMessage("evaluation of %s", fen)
                .that(ChessEvaluation.evaluate(ChessPosition.fromFen(fen)!!))
                .isEqualTo(score)
        }
    }

    /**
     * Whole-search parity: the same position searched to the same depth
     * must yield the same move and the same score on both platforms.
     *
     * This is the strongest guarantee the two ports agree — it exercises
     * move ordering, the transposition table (both use the same low-bit
     * index into a same-sized table), pruning and evaluation together. If
     * it goes red, compare the shallower depths first to find where they
     * diverge.
     */
    @Test fun bestMoveMatchesTheSwiftPortAtFixedDepth() {
        val expected = listOf(
            Triple("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", "g1f3", 0),
            Triple(
                "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
                "e2a6", 38,
            ),
            Triple("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", "b4f4", 7),
            Triple(
                "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 0 1",
                "g8f6", 5,
            ),
        )
        for ((fen, move, score) in expected) {
            val (_, result) = search(fen, depth = 6)!!
            assertWithMessage("best move for %s", fen).that(result.bestMove.uci).isEqualTo(move)
            assertWithMessage("score for %s", fen).that(result.score).isEqualTo(score)
        }
    }

    // MARK: - Search behaviour

    @Test fun searchIsDeterministicAtFixedDepth() {
        val fen = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
        val first = search(fen, depth = 5)!!
        val second = search(fen, depth = 5)!!
        assertThat(first.second.bestMove).isEqualTo(second.second.bestMove)
        assertThat(first.second.score).isEqualTo(second.second.score)
    }

    /**
     * The principal variation must be playable from the root. A PV with an
     * illegal move means the triangular table is being written wrong — and
     * the UI draws arrows from this line.
     */
    @Test fun principalVariationIsFullyLegal() {
        val fen = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
        val (position, result) = search(fen, depth = 6)!!
        assertThat(result.principalVariation).isNotEmpty()
        assertThat(result.principalVariation.first()).isEqualTo(result.bestMove)

        val board = position.copy()
        for (move in result.principalVariation) {
            val legal = ChessMoveGenerator.generateLegal(board.copy()).toList()
            assertWithMessage("PV move %s is illegal at %s", move.uci, board.fen)
                .that(legal).contains(move)
            board.make(move)
        }
    }

    @Test fun searchReturnsNullWhenThereAreNoLegalMoves() {
        val mate = ChessPosition.fromFen(
            "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3"
        )!!
        assertThat(
            ChessSearch().search(mate, ChessSearch.Limits(1000, 4))
        ).isNull()

        val stalemate = ChessPosition.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")!!
        assertThat(
            ChessSearch().search(stalemate, ChessSearch.Limits(1000, 4))
        ).isNull()
    }

    /**
     * The time budget is the contract the UI relies on. Generous slack for
     * a loaded CI machine, but catch a search that ignores the clock.
     */
    @Test fun respectsTheTimeBudget() {
        val position = ChessPosition.fromFen(
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
        )!!
        val started = System.nanoTime()
        val result = ChessSearch().search(position, ChessSearch.Limits(thinkTimeMillis = 300))
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000L
        assertThat(result).isNotNull()
        assertWithMessage("300ms budget overshot badly").that(elapsedMillis).isLessThan(3000L)
    }

    /**
     * `stop()` from another thread must cut an in-flight search short and
     * still hand back a usable move — this is what the UI does when the
     * user edits a square mid-analysis.
     */
    @Test fun stopCutsAnInFlightSearchShort() {
        val position = ChessPosition.fromFen(
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
        )!!
        val engine = ChessSearch()

        thread {
            Thread.sleep(200)
            engine.stop()
        }

        val started = System.nanoTime()
        // A 30s budget it must not come close to spending.
        val result = engine.search(position, ChessSearch.Limits(thinkTimeMillis = 30_000))
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000L

        assertThat(result).isNotNull()
        assertWithMessage("stop() did not interrupt the search")
            .that(elapsedMillis).isLessThan(5000L)
        assertThat(result!!.bestMove.isNone).isFalse()
        assertThat(ChessMoveGenerator.legalMoves(position)).contains(result.bestMove)
    }

    /**
     * Even with a budget too short to finish depth 1, the search must return
     * a legal move rather than NONE — the UI has no fallback.
     */
    @Test fun returnsALegalMoveOnAVanishingBudget() {
        val position = ChessPosition.fromFen(
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
        )!!
        val result = ChessSearch().search(position, ChessSearch.Limits(thinkTimeMillis = 1))!!
        assertThat(result.bestMove.isNone).isFalse()
        assertThat(ChessMoveGenerator.legalMoves(position)).contains(result.bestMove)
    }

    // MARK: - SAN

    @Test fun sanBasics() {
        val position = ChessPosition.standard()
        assertThat(position.san(position.moveFromUci("e2e4")!!)).isEqualTo("e4")
        assertThat(position.san(position.moveFromUci("g1f3")!!)).isEqualTo("Nf3")
    }

    @Test fun sanPawnCapture() {
        val position = ChessPosition.fromFen("4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1")!!
        assertThat(position.san(position.moveFromUci("e4d5")!!)).isEqualTo("exd5")
    }

    @Test fun sanFileDisambiguation() {
        // Knights on c3 and g1 both reach e2.
        val position = ChessPosition.fromFen("4k3/8/8/8/8/2N5/8/4K1N1 w - - 0 1")!!
        assertThat(position.san(position.moveFromUci("c3e2")!!)).isEqualTo("Nce2")
        assertThat(position.san(position.moveFromUci("g1e2")!!)).isEqualTo("Nge2")
    }

    @Test fun sanRankDisambiguation() {
        // Rooks on a1 and a8 share a file, so the rank disambiguates. The
        // black king sits off the 8th rank and off the a-file so neither
        // rook is giving check.
        val position = ChessPosition.fromFen("R7/8/4k3/8/8/8/8/R3K3 w - - 0 1")!!
        assertThat(position.san(position.moveFromUci("a1a4")!!)).isEqualTo("R1a4")
        assertThat(position.san(position.moveFromUci("a8a4")!!)).isEqualTo("R8a4")
    }

    @Test fun sanCastling() {
        val position = ChessPosition.fromFen("4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1")!!
        assertThat(position.san(position.moveFromUci("e1g1")!!)).isEqualTo("O-O")
        assertThat(position.san(position.moveFromUci("e1c1")!!)).isEqualTo("O-O-O")
    }

    @Test fun sanPromotionAndCheck() {
        val position = ChessPosition.fromFen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1")!!
        assertThat(position.san(position.moveFromUci("a7a8q")!!)).isEqualTo("a8=Q+")
        assertThat(position.san(position.moveFromUci("a7a8n")!!)).isEqualTo("a8=N")
    }

    @Test fun sanMateSuffix() {
        val position = ChessPosition.fromFen("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1")!!
        assertThat(position.san(position.moveFromUci("a1a8")!!)).isEqualTo("Ra8#")
    }

    /**
     * A pinned piece cannot reach the square, so it is not a rival and must
     * not force a disambiguation hint onto the piece that can.
     *
     * Knights on b3 and f3 both attack d4, but the f3 knight is pinned
     * against the king on f1 by the rook on f8. So "Nd4" is correct and
     * "Nbd4" would be wrong.
     */
    @Test fun sanIgnoresPinnedRivals() {
        val position = ChessPosition.fromFen("5r1k/8/8/8/8/1N3N2/8/5K2 w - - 0 1")!!
        val legal = ChessMoveGenerator.legalMoves(position).map { position.san(it) }
        assertWithMessage("the unpinned knight needs no hint; got %s", legal)
            .that(legal).contains("Nd4")
        assertThat(legal).doesNotContain("Nbd4")
        assertThat(legal).doesNotContain("Nfd4")
    }

    @Test fun sanLineNumbering() {
        val position = ChessPosition.standard()
        val first = position.moveFromUci("e2e4")!!
        val next = position.copy()
        next.make(first)
        val reply = next.moveFromUci("e7e5")!!

        assertThat(position.sanLine(listOf(first, reply))).isEqualTo("1. e4 e5")
        // A line beginning on Black's move gets the "1..." form.
        assertThat(next.sanLine(listOf(reply))).isEqualTo("1... e5")
    }

    @Test fun moveFromUciRejectsIllegalMoves() {
        val position = ChessPosition.standard()
        assertThat(position.moveFromUci("e2e4")).isNotNull()
        assertThat(position.moveFromUci("e2e5")).isNull()
        assertThat(position.moveFromUci("a1a5")).isNull()
    }

    /**
     * A UCI string carries no capture flag, so the returned move must be the
     * *generated* one with its flags intact — otherwise `make` would move
     * the piece without removing the captured one.
     */
    @Test fun moveFromUciCarriesTheRightFlags() {
        assertThat(
            ChessPosition.fromFen("4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1")!!
                .moveFromUci("e4d5")!!.flagBits
        ).isEqualTo(ChessMove.CAPTURE)

        assertThat(
            ChessPosition.fromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1")!!
                .moveFromUci("e5d6")!!.flagBits
        ).isEqualTo(ChessMove.EN_PASSANT)

        assertThat(
            ChessPosition.fromFen("4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1")!!
                .moveFromUci("e1g1")!!.flagBits
        ).isEqualTo(ChessMove.CASTLE_KINGSIDE)
    }

    // MARK: - The both-sides API

    /** The headline call on an ordinary position: both sides get a move. */
    @Test fun bothSidesAnalysisOnALegalPosition() {
        val analysis = ChessEngine().analyseBothSides(ChessPosition.standard(), 200L)

        val white = analysis.white
        val black = analysis.black
        assertThat(white).isInstanceOf(ChessAnalysisOutcome.Move::class.java)
        assertThat(black).isInstanceOf(ChessAnalysisOutcome.Move::class.java)

        val whiteMove = (white as ChessAnalysisOutcome.Move).analysis
        val blackMove = (black as ChessAnalysisOutcome.Move).analysis
        assertThat(whiteMove.side).isEqualTo(ChessColor.WHITE)
        assertThat(blackMove.side).isEqualTo(ChessColor.BLACK)
        assertThat(whiteMove.san).isNotEmpty()
        assertThat(blackMove.san).isNotEmpty()
        assertThat(analysis.placementFen)
            .isEqualTo("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")
    }

    /**
     * The case that makes "both sides" interesting: a board legal for only
     * one of them. White cannot be to move while Black stands in check, so
     * that side reports why instead of inventing a move.
     */
    @Test fun bothSidesAnalysisWhenOnlyOneSideMayMove() {
        // White rook on e2 checks the black king down the e-file.
        val position = ChessPosition.fromFen("4k3/8/8/8/8/8/4R3/K7 w - - 0 1")!!
        val analysis = ChessEngine().analyseBothSides(position, 200L)

        assertThat(analysis.white).isInstanceOf(ChessAnalysisOutcome.NotLegal::class.java)
        assertThat((analysis.white as ChessAnalysisOutcome.NotLegal).problems)
            .contains(ChessLegalityProblem.OpponentInCheck(ChessColor.BLACK))

        assertWithMessage("Black to move is legal — it must escape the check")
            .that(analysis.black).isInstanceOf(ChessAnalysisOutcome.Move::class.java)
    }

    /**
     * A misread board reports its problems rather than searching garbage.
     * This is what the analysis screen shows the user.
     */
    @Test fun analysisRefusesAnIllegalPosition() {
        val noKing = ChessPosition.fromFen("4k3/8/8/8/8/8/8/8 w - - 0 1")!!
        val outcome = ChessEngine().analyse(noKing, 100L)
        assertThat(outcome).isInstanceOf(ChessAnalysisOutcome.NotLegal::class.java)
        assertThat((outcome as ChessAnalysisOutcome.NotLegal).problems)
            .contains(ChessLegalityProblem.MissingKing(ChessColor.WHITE))
    }

    @Test fun analysisReportsCheckmateAsTerminal() {
        val mate = ChessPosition.fromFen(
            "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3"
        )!!
        val outcome = ChessEngine().analyse(mate, 100L)
        assertThat(outcome).isInstanceOf(ChessAnalysisOutcome.Terminal::class.java)
        assertThat((outcome as ChessAnalysisOutcome.Terminal).outcome)
            .isEqualTo(ChessOutcome.CHECKMATE)
    }

    /**
     * Scores are reported from each side's own perspective internally, but
     * `displayScore` is White-relative — the convention every chess UI uses.
     */
    @Test fun displayScoreIsWhiteRelative() {
        // Black is a queen up, so both sides should agree the evaluation
        // favours Black, i.e. a negative display score.
        val position = ChessPosition.fromFen("3qk3/8/8/8/8/8/8/4K3 w - - 0 1")!!
        val analysis = ChessEngine().analyseBothSides(position, 200L)

        val white = analysis.white
        assertThat(white).isInstanceOf(ChessAnalysisOutcome.Move::class.java)
        val whiteMove = (white as ChessAnalysisOutcome.Move).analysis
        assertWithMessage("White is a queen down")
            .that(whiteMove.whiteRelativePawns).isLessThan(0.0)
        assertThat(whiteMove.displayScore).startsWith("-")
    }
}
