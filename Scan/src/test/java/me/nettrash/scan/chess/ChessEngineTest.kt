package me.nettrash.scan.chess

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The chess engine's correctness gate, and the parity gate against the
 * iOS port.
 *
 * ## Perft is the whole point of this file
 *
 * `perft(depth)` counts the leaf nodes of the legal-move tree. Its value
 * is that the counts for a handful of standard positions are *published
 * and independently verified* — every serious engine reports the same
 * numbers. So a single integer comparison exercises castling rights,
 * en-passant timing, promotion, pinned pieces, discovered check, check
 * evasion and the fifty other rules interactions at once, and tells you
 * the moment any of them is wrong.
 *
 * The six positions are the standard set from the Chess Programming Wiki,
 * chosen because each breaks a different naive generator:
 *
 * 1. the initial array — the baseline
 * 2. "Kiwipete" — dense middlegame, every castling and pin case
 * 3. a sparse endgame that punishes en-passant and promotion errors
 * 4. promotion-heavy, and mirrored to catch colour asymmetry
 * 5. a known trap for buggy castling-rights bookkeeping
 * 6. a quiet position that catches off-by-one in move ordering
 *
 * **These are the same numbers `Scan/Tools/run-perft.sh` checks on the
 * iOS side.** They are not ours to adjust: if one fails, the engine is
 * wrong, not the expectation.
 *
 * Deep counts (startpos depth 6 at 119M nodes, Kiwipete depth 5 at 194M)
 * are gated behind `-PdeepPerft`, since they take minutes rather than
 * seconds. See [runsDeepPerft].
 *
 * No Robolectric here: the engine is pure Kotlin with no Android
 * dependencies, so these run on the plain JVM and stay fast.
 */
class ChessEngineTest {

    // MARK: - Perft driver

    /** Count leaf nodes of the legal-move tree to [depth]. */
    private fun perft(position: ChessPosition, depth: Int): Long {
        // Bulk counting at depth 1: the move list length *is* the answer,
        // which saves a make/unmake pair per leaf. Standard, and it does
        // not change the counts.
        val moves = ChessMoveGenerator.generateLegal(position)
        if (depth <= 1) return if (depth == 0) 1L else moves.count.toLong()

        var nodes = 0L
        for (index in 0 until moves.count) {
            val move = moves[index]
            position.make(move)
            nodes += perft(position, depth - 1)
            position.unmake(move)
        }
        return nodes
    }

    /**
     * Per-root-move node counts.
     *
     * Not used by any assertion — it is the debugging tool for when a
     * count is wrong. Compare against any reference engine's `divide` and
     * the first differing move names the broken rule, instead of leaving
     * you with "8 nodes too many somewhere in 4 million".
     */
    @Suppress("unused")
    private fun perftDivide(position: ChessPosition, depth: Int): List<Pair<String, Long>> {
        if (depth < 1) return emptyList()
        val moves = ChessMoveGenerator.generateLegal(position)
        val out = mutableListOf<Pair<String, Long>>()
        for (index in 0 until moves.count) {
            val move = moves[index]
            position.make(move)
            out.add(move.uci to if (depth == 1) 1L else perft(position, depth - 1))
            position.unmake(move)
        }
        return out.sortedBy { it.first }
    }

    /**
     * Assert a position's perft counts one depth at a time, so a failure
     * names the shallowest broken depth — the one worth debugging.
     */
    private fun assertPerft(fen: String, expected: LongArray) {
        val position = ChessPosition.fromFen(fen)
        assertWithMessage("could not parse FEN: %s", fen).that(position).isNotNull()
        for ((offset, count) in expected.withIndex()) {
            val depth = offset + 1
            assertWithMessage("perft(%s) for %s", depth, fen)
                .that(perft(position!!, depth))
                .isEqualTo(count)
        }
    }

    private val runsDeepPerft: Boolean
        get() = System.getProperty("deepPerft") == "1" ||
            System.getenv("SCAN_CHESS_DEEP_PERFT") == "1"

    // MARK: - The standard positions

    private val startPosition = ChessPosition.START_FEN
    private val kiwipete = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
    private val position3 = "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1"
    private val position4 = "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1"
    private val position5 = "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8"
    private val position6 =
        "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10"

    @Test fun perftStartPosition() {
        assertPerft(startPosition, longArrayOf(20, 400, 8902, 197_281, 4_865_609))
    }

    @Test fun perftKiwipete() {
        assertPerft(kiwipete, longArrayOf(48, 2039, 97_862, 4_085_603))
    }

    @Test fun perftPosition3() {
        assertPerft(position3, longArrayOf(14, 191, 2812, 43_238, 674_624))
    }

    @Test fun perftPosition4() {
        assertPerft(position4, longArrayOf(6, 264, 9467, 422_333))
    }

    /**
     * Position 4 mirrored top-to-bottom with the colours swapped. The node
     * counts are identical by symmetry, so a mismatch between this and
     * [perftPosition4] means something in the generator treats White and
     * Black differently — pawn direction, promotion rank, or a castling
     * mask.
     */
    @Test fun perftPosition4Mirrored() {
        assertPerft(
            "r2q1rk1/pP1p2pp/Q4n2/bbp1p3/Np6/1B3NBn/pPPP1PPP/R3K2R b KQ - 0 1",
            longArrayOf(6, 264, 9467, 422_333),
        )
    }

    @Test fun perftPosition5() {
        assertPerft(position5, longArrayOf(44, 1486, 62_379, 2_103_487))
    }

    @Test fun perftPosition6() {
        assertPerft(position6, longArrayOf(46, 2079, 89_890, 3_894_594))
    }

    // MARK: - Deep perft (opt-in)

    @Test fun deepPerft() {
        if (!runsDeepPerft) return
        assertPerft(
            startPosition,
            longArrayOf(20, 400, 8902, 197_281, 4_865_609, 119_060_324),
        )
        assertPerft(kiwipete, longArrayOf(48, 2039, 97_862, 4_085_603, 193_690_690))
        assertPerft(position3, longArrayOf(14, 191, 2812, 43_238, 674_624, 11_030_083))
        assertPerft(position4, longArrayOf(6, 264, 9467, 422_333, 15_833_292))
        assertPerft(position5, longArrayOf(44, 1486, 62_379, 2_103_487, 89_941_194))
        assertPerft(position6, longArrayOf(46, 2079, 89_890, 3_894_594, 164_075_551))
    }

    // MARK: - FEN

    @Test fun startPositionRoundTrips() {
        assertThat(ChessPosition.standard().fen).isEqualTo(ChessPosition.START_FEN)
    }

    @Test fun fenRoundTripsForEveryTestPosition() {
        for (fen in listOf(startPosition, kiwipete, position3, position4, position5, position6)) {
            assertThat(ChessPosition.fromFen(fen)!!.fen).isEqualTo(fen)
        }
    }

    @Test fun placementOnlyFenDefaultsTheRest() {
        val position = ChessPosition.fromFen("8/8/8/4k3/8/8/4K3/8")!!
        assertThat(position.sideToMove).isEqualTo(ChessColor.WHITE)
        assertThat(position.castlingRights).isEqualTo(ChessCastlingRights.NONE)
        assertThat(position.enPassantSquare).isEqualTo(-1)
        assertThat(position.halfmoveClock).isEqualTo(0)
        assertThat(position.fullmoveNumber).isEqualTo(1)
        assertThat(position.fen).isEqualTo("8/8/8/4k3/8/8/4K3/8 w - - 0 1")
    }

    @Test fun malformedFensAreRejected() {
        val bad = listOf(
            "",
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP", // 7 ranks
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR/8", // 9 ranks
            "rnbqkbnr/ppppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR", // 9 files
            "rnbqkbnr/7/8/8/8/8/PPPPPPPP/RNBQKBNR", // rank sums to 7
            "rnbqkbXr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR", // bogus letter
        )
        for (fen in bad) {
            assertThat(ChessPosition.fromFen(fen)).isNull()
        }
    }

    @Test fun squareIndexingMatchesAlgebraic() {
        assertThat(ChessSquare.parse("a1")!!.index).isEqualTo(0)
        assertThat(ChessSquare.parse("h1")!!.index).isEqualTo(7)
        assertThat(ChessSquare.parse("a8")!!.index).isEqualTo(56)
        assertThat(ChessSquare.parse("h8")!!.index).isEqualTo(63)
        assertThat(ChessSquare.parse("e4")!!.index).isEqualTo(28)
        for (index in 0 until 64) {
            val square = ChessSquare(index)
            assertThat(ChessSquare.parse(square.algebraic)).isEqualTo(square)
        }
    }

    /**
     * h1 and a8 are light; a1 and h8 are dark. This parity is shared with
     * the recogniser's light/dark square baselines, so it is worth pinning
     * down rather than leaving implicit.
     */
    @Test fun squareColourParity() {
        assertThat(ChessSquare.H1.isLight).isTrue()
        assertThat(ChessSquare.A8.isLight).isTrue()
        assertThat(ChessSquare.A1.isLight).isFalse()
        assertThat(ChessSquare.H8.isLight).isFalse()
    }

    // MARK: - Make / unmake invariants

    /**
     * Making and then unmaking any legal move must restore the position
     * *exactly* — placement, clocks, rights, en-passant target and the
     * Zobrist key. This is the invariant perft silently depends on, so
     * when it breaks it is worth failing here with a readable message
     * rather than as a node-count mismatch.
     */
    @Test fun makeUnmakeRestoresPositionExactly() {
        for (fen in listOf(startPosition, kiwipete, position3, position4, position5, position6)) {
            val position = ChessPosition.fromFen(fen)!!
            val before = position.fen
            val keyBefore = position.key

            val moves = ChessMoveGenerator.generateLegal(position)
            for (index in 0 until moves.count) {
                val move = moves[index]
                position.make(move)
                position.unmake(move)
                assertWithMessage("after %s in %s", move.uci, fen)
                    .that(position.fen).isEqualTo(before)
                assertWithMessage("key after %s in %s", move.uci, fen)
                    .that(position.key).isEqualTo(keyBefore)
            }
        }
    }

    /**
     * The incrementally-updated Zobrist key must equal a from-scratch
     * recomputation after every move. Drift here would not break move
     * generation at all — it would quietly corrupt the transposition table
     * and make the search return moves for a different position.
     */
    @Test fun incrementalZobristMatchesRecomputed() {
        for (fen in listOf(startPosition, kiwipete, position4, position5)) {
            val position = ChessPosition.fromFen(fen)!!
            val moves = ChessMoveGenerator.generateLegal(position)
            for (index in 0 until moves.count) {
                val move = moves[index]
                position.make(move)

                val incremental = position.key
                val recomputed = position.copy()
                recomputed.refreshKey()
                assertWithMessage("key drifted after %s in %s", move.uci, fen)
                    .that(incremental).isEqualTo(recomputed.key)

                position.unmake(move)
            }
        }
    }

    @Test fun nullMoveRoundTrips() {
        val position = ChessPosition.fromFen(kiwipete)!!
        val before = position.fen
        val key = position.key
        position.makeNull()
        assertThat(position.sideToMove).isEqualTo(ChessColor.BLACK)
        assertThat(position.enPassantSquare).isEqualTo(-1)
        position.unmakeNull()
        assertThat(position.fen).isEqualTo(before)
        assertThat(position.key).isEqualTo(key)
    }

    // MARK: - Move encoding

    @Test fun moveEncodingRoundTrips() {
        val flags = intArrayOf(
            ChessMove.QUIET, ChessMove.CAPTURE, ChessMove.EN_PASSANT,
            ChessMove.DOUBLE_PAWN_PUSH, ChessMove.CASTLE_KINGSIDE,
            ChessMove.CASTLE_QUEENSIDE, ChessMove.PROMOTE_QUEEN,
            ChessMove.CAPTURE_PROMOTE_KNIGHT,
        )
        for (from in 0 until 64) {
            for (to in 0 until 64) {
                if (to == from) continue
                for (flag in flags) {
                    val move = ChessMove.of(from, to, flag)
                    assertThat(move.fromIndex).isEqualTo(from)
                    assertThat(move.toIndex).isEqualTo(to)
                    assertThat(move.flagBits).isEqualTo(flag)
                }
            }
        }
    }

    @Test fun moveFlagPredicates() {
        assertThat(ChessMove.of(0, 1, ChessMove.QUIET).isCapture).isFalse()
        assertThat(ChessMove.of(0, 1, ChessMove.DOUBLE_PAWN_PUSH).isCapture).isFalse()
        assertThat(ChessMove.of(0, 1, ChessMove.CASTLE_KINGSIDE).isCapture).isFalse()
        assertThat(ChessMove.of(0, 1, ChessMove.CASTLE_QUEENSIDE).isCapture).isFalse()
        assertThat(ChessMove.of(0, 1, ChessMove.CAPTURE).isCapture).isTrue()
        assertThat(ChessMove.of(0, 1, ChessMove.EN_PASSANT).isCapture).isTrue()
        assertThat(ChessMove.of(0, 1, ChessMove.CAPTURE_PROMOTE_ROOK).isCapture).isTrue()

        assertThat(ChessMove.of(0, 1, ChessMove.PROMOTE_QUEEN).isPromotion).isTrue()
        assertThat(ChessMove.of(0, 1, ChessMove.PROMOTE_QUEEN).promotionKind)
            .isEqualTo(ChessPieceKind.QUEEN)
        assertThat(ChessMove.of(0, 1, ChessMove.PROMOTE_KNIGHT).promotionKind)
            .isEqualTo(ChessPieceKind.KNIGHT)
        assertThat(ChessMove.of(0, 1, ChessMove.CAPTURE_PROMOTE_BISHOP).promotionKind)
            .isEqualTo(ChessPieceKind.BISHOP)
        assertThat(ChessMove.of(0, 1, ChessMove.CAPTURE_PROMOTE_ROOK).promotionKind)
            .isEqualTo(ChessPieceKind.ROOK)
        assertThat(ChessMove.of(0, 1, ChessMove.CAPTURE).promotionKind).isNull()
    }

    @Test fun uciRoundTrips() {
        assertThat(
            ChessMove.of(
                ChessSquare.parse("e2")!!, ChessSquare.parse("e4")!!,
                ChessMove.DOUBLE_PAWN_PUSH,
            ).uci
        ).isEqualTo("e2e4")
        assertThat(
            ChessMove.of(
                ChessSquare.parse("e7")!!, ChessSquare.parse("e8")!!,
                ChessMove.PROMOTE_QUEEN,
            ).uci
        ).isEqualTo("e7e8q")
        assertThat(ChessMove.fromUci("e2e4")!!.fromIndex)
            .isEqualTo(ChessSquare.parse("e2")!!.index)
        assertThat(ChessMove.fromUci("e7e8n")!!.promotionKind).isEqualTo(ChessPieceKind.KNIGHT)
        assertThat(ChessMove.fromUci("e2e")).isNull()
        assertThat(ChessMove.fromUci("e7e8k")).isNull() // can't promote to a king
    }

    // MARK: - Rules corner cases

    /**
     * A rook capture on h8 must strip Black's kingside castling right even
     * though no black piece moved. This is the case the from/to
     * castling-mask design exists to get right for free.
     */
    @Test fun capturingARookClearsTheirCastlingRight() {
        val position = ChessPosition.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")!!
        val capture = ChessMoveGenerator.generateLegal(position).toList()
            .first { it.uci == "h1h8" }
        position.make(capture)
        assertThat(position.castlingRights.contains(ChessCastlingRights.BLACK_KINGSIDE)).isFalse()
        assertThat(position.castlingRights.contains(ChessCastlingRights.BLACK_QUEENSIDE)).isTrue()
        assertThat(position.castlingRights.contains(ChessCastlingRights.WHITE_KINGSIDE)).isFalse()
    }

    /**
     * You may not castle *through* an attacked square, even though the
     * king's final square is safe. The play-and-check legality filter only
     * tests the destination, which is exactly why castling legality is
     * resolved during generation instead.
     */
    @Test fun cannotCastleThroughCheck() {
        // Black rook on f8 attacks f1, the square the white king crosses.
        assertThat(
            ChessMoveGenerator.legalMoves(ChessPosition.fromFen("5r2/8/8/8/8/8/8/4K2R w K - 0 1")!!)
                .any { it.isCastle }
        ).isFalse()

        // Rook on g8 attacks the king's landing square.
        assertThat(
            ChessMoveGenerator.legalMoves(ChessPosition.fromFen("6r1/8/8/8/8/8/8/4K2R w K - 0 1")!!)
                .any { it.isCastle }
        ).isFalse()

        // Rook on h8 attacks only h1, so castling is fine.
        assertThat(
            ChessMoveGenerator.legalMoves(ChessPosition.fromFen("7r/8/8/8/8/8/8/4K2R w K - 0 1")!!)
                .any { it.isCastle }
        ).isTrue()
    }

    @Test fun cannotCastleOutOfCheck() {
        assertThat(
            ChessMoveGenerator.legalMoves(ChessPosition.fromFen("4r3/8/8/8/8/8/8/4K2R w K - 0 1")!!)
                .any { it.isCastle }
        ).isFalse()
    }

    /**
     * Rights claiming a rook that isn't there must not produce a castle — a
     * hand-edited or recognised position can easily say "KQkq" with an
     * empty h1.
     */
    @Test fun castlingRightsWithoutARookGenerateNothing() {
        assertThat(
            ChessMoveGenerator.legalMoves(ChessPosition.fromFen("4k3/8/8/8/8/8/8/4K3 w KQkq - 0 1")!!)
                .any { it.isCastle }
        ).isFalse()
    }

    /**
     * The en-passant capture that exposes the capturing side's own king
     * along the fifth rank — two pawns leave the rank in one move, which no
     * other move in chess does.
     */
    @Test fun enPassantThatWouldExposeTheKingIsIllegal() {
        val position = ChessPosition.fromFen("8/8/8/K1Pp3r/8/8/8/7k w - d6 0 1")!!
        assertThat(
            ChessMoveGenerator.generateLegal(position).toList().any { it.isEnPassant }
        ).isFalse()
    }

    @Test fun enPassantIsGeneratedWhenLegal() {
        val position = ChessPosition.fromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1")!!
        val enPassant = ChessMoveGenerator.generateLegal(position).toList().filter { it.isEnPassant }
        assertThat(enPassant).hasSize(1)
        assertThat(enPassant.first().uci).isEqualTo("e5d6")

        // And it removes the pawn on d5, not the empty d6.
        position.make(enPassant.first())
        assertThat(position.pieceAt(ChessSquare.parse("d5")!!)).isNull()
        assertThat(position.pieceAt(ChessSquare.parse("d6")!!))
            .isEqualTo(ChessPiece(ChessColor.WHITE, ChessPieceKind.PAWN))
    }

    @Test fun promotionGeneratesAllFourKinds() {
        val position = ChessPosition.fromFen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1")!!
        val promotions = ChessMoveGenerator.legalMoves(position)
            .filter { it.isPromotion }
            .map { it.uci }
        assertThat(promotions).containsExactly("a7a8q", "a7a8r", "a7a8b", "a7a8n")
    }

    @Test fun checkmateAndStalemateAreDistinguished() {
        val mate = ChessPosition.fromFen(
            "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3"
        )!!
        assertThat(mate.outcome).isEqualTo(ChessOutcome.CHECKMATE)
        assertThat(mate.isInCheck).isTrue()

        val stalemate = ChessPosition.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")!!
        assertThat(stalemate.outcome).isEqualTo(ChessOutcome.STALEMATE)
        assertThat(stalemate.isInCheck).isFalse()
    }

    @Test fun insufficientMaterial() {
        assertThat(ChessPosition.fromFen("4k3/8/8/8/8/8/8/4K3 w - - 0 1")!!.hasInsufficientMaterial)
            .isTrue()
        assertThat(ChessPosition.fromFen("4k3/8/8/8/8/8/8/4KB2 w - - 0 1")!!.hasInsufficientMaterial)
            .isTrue()
        assertThat(ChessPosition.fromFen("4k3/8/8/8/8/8/8/4KN2 w - - 0 1")!!.hasInsufficientMaterial)
            .isTrue()
        // Bishops of both colours, both on dark squares (f8 and c1).
        assertThat(ChessPosition.fromFen("4kb2/8/8/8/8/8/8/2B1K3 w - - 0 1")!!.hasInsufficientMaterial)
            .isTrue()
        // Opposite square colours (c8 light, c1 dark) — a mate exists in
        // contrived positions, so it is not dead.
        assertThat(ChessPosition.fromFen("2b1k3/8/8/8/8/8/8/2B1K3 w - - 0 1")!!.hasInsufficientMaterial)
            .isFalse()
        assertThat(ChessPosition.fromFen("4k3/8/8/8/8/8/P7/4K3 w - - 0 1")!!.hasInsufficientMaterial)
            .isFalse()
        // Deliberately not a draw by our conservative rule.
        assertThat(ChessPosition.fromFen("4k3/8/8/8/8/8/8/3NKN2 w - - 0 1")!!.hasInsufficientMaterial)
            .isFalse()
    }

    // MARK: - Legality reporting

    @Test fun legalPositionHasNoProblems() {
        assertThat(ChessPosition.standard().isAnalysable).isTrue()
        assertThat(ChessPosition.fromFen(kiwipete)!!.legalityProblems).isEmpty()
    }

    /**
     * The misreads the recogniser actually produces, each of which the
     * analysis screen has to explain rather than silently search.
     */
    @Test fun recogniserMisreadsAreReported() {
        assertThat(ChessPosition.fromFen("4k3/8/8/8/8/8/8/3KK3 w - - 0 1")!!.legalityProblems)
            .contains(ChessLegalityProblem.TooManyKings(ChessColor.WHITE))

        assertThat(ChessPosition.fromFen("4k3/8/8/8/8/8/8/8 w - - 0 1")!!.legalityProblems)
            .contains(ChessLegalityProblem.MissingKing(ChessColor.WHITE))

        assertThat(ChessPosition.fromFen("4k3/8/8/8/8/PPPPPPPP/P7/4K3 w - - 0 1")!!.legalityProblems)
            .contains(ChessLegalityProblem.TooManyPawns(ChessColor.WHITE, 9))

        assertThat(ChessPosition.fromFen("4k3/8/8/8/8/8/8/P3K3 w - - 0 1")!!.legalityProblems)
            .contains(ChessLegalityProblem.PawnOnBackRank(ChessSquare.A1))

        assertThat(
            ChessPosition.fromFen("4k3/pppppppp/8/8/QQQQ4/8/PPPPPPPP/4K3 w - - 0 1")!!
                .legalityProblems
        ).contains(ChessLegalityProblem.ImpossiblePromotionCount(ChessColor.WHITE))
    }

    /**
     * The position that decides whether the analysis screen can offer a
     * move for *both* sides: if Black is already in check while White is to
     * move, "best move for Black" is not a question about a legal position.
     */
    @Test fun sideToMoveWithOpponentInCheckIsReported() {
        val position = ChessPosition.fromFen("4k3/8/8/8/8/8/4R3/K7 w - - 0 1")!!
        assertThat(position.opponentIsInCheck).isTrue()
        assertThat(position.legalityProblems)
            .contains(ChessLegalityProblem.OpponentInCheck(ChessColor.BLACK))
        assertThat(position.isAnalysable).isFalse()

        val flipped = position.copy()
        flipped.setSideToMove(ChessColor.BLACK)
        assertThat(flipped.isAnalysable).isTrue()
    }

    // MARK: - Placement round-trip (the recogniser's entry point)

    @Test fun placementArrayRoundTrips() {
        val original = ChessPosition.fromFen(kiwipete)!!
        val rebuilt = ChessPosition(
            placement = original.placement,
            sideToMove = original.sideToMove,
            castlingRights = original.castlingRights,
            enPassantSquare = null,
            halfmoveClock = original.halfmoveClock,
            fullmoveNumber = original.fullmoveNumber,
        )
        assertThat(rebuilt.fen).isEqualTo(original.fen)
        assertThat(rebuilt.key).isEqualTo(original.key)
    }

    @Test fun editingASquareUpdatesTheKey() {
        val position = ChessPosition.standard()
        val before = position.key
        position.setPiece(null, ChessSquare.parse("e2")!!)
        position.refreshKey()
        assertThat(position.key).isNotEqualTo(before)

        position.setPiece(
            ChessPiece(ChessColor.WHITE, ChessPieceKind.PAWN),
            ChessSquare.parse("e2")!!,
        )
        position.refreshKey()
        assertThat(position.key).isEqualTo(before)
    }

    /**
     * ## Cross-platform hash parity
     *
     * These constants must match the Swift port byte for byte. They were
     * produced by the iOS engine (`ChessZobrist` with the fixed
     * xorshift64* seed) and printed as signed 64-bit, which is how Kotlin
     * renders a `Long`.
     *
     * They are not free to change. If this test goes red, either the
     * generator's draw order drifted or the two ports have diverged — and
     * a Zobrist key that means different things on iOS and Android makes
     * every recorded transposition fixture and every debug key useless
     * across the pair.
     *
     * The two whole-position keys at the end are the stronger check: they
     * fold in placement, side to move, castling rights and the
     * en-passant field, so they verify the entire hashing pipeline rather
     * than the raw constants alone.
     */
    @Test fun zobristHashingMatchesTheSwiftPort() {
        assertThat(ChessZobrist.piece(0, 0)).isEqualTo(973819730272012410L)
        assertThat(ChessZobrist.piece(0, 1)).isEqualTo(6108091081255984487L)
        assertThat(ChessZobrist.piece(11, 63)).isEqualTo(-730575979534874952L)
        assertThat(ChessZobrist.sideToMove).isEqualTo(4619031559307515888L)
        assertThat(ChessZobrist.castling(ChessCastlingRights.ALL))
            .isEqualTo(7647340881211156683L)
        assertThat(ChessZobrist.enPassantFile(0)).isEqualTo(6110025575839353664L)

        assertThat(ChessPosition.standard().key).isEqualTo(4198882946414090897L)
        assertThat(ChessPosition.fromFen(kiwipete)!!.key).isEqualTo(-7722695589351285772L)
    }
}
