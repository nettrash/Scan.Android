package me.nettrash.scan.chess

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Constrained decoding — the Kotlin half of a three-way port.
 *
 * The cases mirror `chess-vision/tests/test_decode.py` (the
 * specification) and `ScanTests/ChessDecoderTests.swift`. Each is a
 * misread the recogniser genuinely produces: a third king, a ninth pawn,
 * a pawn hallucinated onto the back rank, more queens than missing pawns
 * can pay for. The assertion is always the same shape — whatever went in,
 * what comes out is a position [legalityProblems] accepts — because the
 * engine refuses to search anything else.
 *
 * The adversarial cases matter more than the happy path. A decoder that
 * only works when the classifier was already right is doing nothing.
 */
class ChessDecoderTest {

    private val classes = ChessPositionDecoder.CLASS_COUNT

    /** A distribution that argmaxes to [labels], with the rest spread flat. */
    private fun oneHot(labels: IntArray, confidence: Double = 0.99): Array<DoubleArray> {
        val spread = (1.0 - confidence) / (classes - 1)
        return Array(64) { square ->
            DoubleArray(classes) { if (it == labels[square]) confidence else spread }
        }
    }

    private fun labelsOf(fen: String): IntArray {
        val position = ChessPosition.fromFen(fen)!!
        return IntArray(64) { square ->
            position.pieceAt(square)?.let { ChessPositionDecoder.labelFor(it) } ?: 0
        }
    }

    private fun sq(name: String) = ChessSquare.parse(name)!!.index

    /**
     * The decoder's whole contract in one assertion.
     *
     * `OpponentInCheck` is filtered out: it depends on whose turn it is,
     * which the decoder has no opinion about because a photo cannot show
     * it. Everything else is the decoder's responsibility.
     */
    private fun assertLegal(decoded: ChessRecognizedPosition) {
        val structural = decoded.position().legalityProblems
            .filterNot { it is ChessLegalityProblem.OpponentInCheck }
        assertWithMessage("expected a legal placement, got %s in %s",
            structural, decoded.position().placementFen)
            .that(structural).isEmpty()
    }

    private fun countOf(decoded: ChessRecognizedPosition, piece: ChessPiece) =
        decoded.placement.count { it == piece }

    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"

    // MARK: - The happy path

    @Test fun aCleanReadIsLeftAlone() {
        val decoded = ChessPositionDecoder.decode(oneHot(labelsOf(startFen)))
        assertThat(decoded.position().placementFen).isEqualTo(startFen)
        assertThat(decoded.repairs).isEmpty()
        assertLegal(decoded)
    }

    @Test fun confidenceIsReportedPerSquare() {
        val decoded = ChessPositionDecoder.decode(oneHot(labelsOf(startFen), confidence = 0.9))
        assertThat(decoded.confidence.size).isEqualTo(64)
        assertThat(decoded.confidence.all { it > 0.85 && it <= 1.0 }).isTrue()
    }

    @Test fun weakestSquaresAreOrderedLeastConfidentFirst() {
        val probabilities = oneHot(labelsOf(startFen))
        val shaky = sq("d4")
        probabilities[shaky] = DoubleArray(classes) { 1.0 / classes }
        val decoded = ChessPositionDecoder.decode(probabilities)
        assertThat(decoded.weakestSquares.first().index).isEqualTo(shaky)
    }

    // MARK: - The misreads

    @Test fun threeKingsIsReducedToOne() {
        val labels = labelsOf(startFen)
        labels[sq("d4")] = ChessPositionDecoder.labelFor(
            ChessPiece(ChessColor.WHITE, ChessPieceKind.KING)
        )
        val probabilities = oneHot(labels)
        // Make the spurious king less confident than the real one on e1.
        probabilities[sq("d4")][labels[sq("d4")]] = 0.55

        val decoded = ChessPositionDecoder.decode(probabilities)
        val whiteKing = ChessPiece(ChessColor.WHITE, ChessPieceKind.KING)
        assertThat(decoded.placement[sq("e1")]).isEqualTo(whiteKing)
        assertThat(decoded.placement[sq("d4")]).isNotEqualTo(whiteKing)
        assertThat(decoded.repairs).isNotEmpty()
        assertLegal(decoded)
    }

    @Test fun aMissingKingIsRestored() {
        val labels = labelsOf(startFen)
        labels[sq("e1")] = ChessPositionDecoder.EMPTY
        val probabilities = oneHot(labels)
        // e1 is where the model was second-guessing a king.
        probabilities[sq("e1")][
            ChessPositionDecoder.labelFor(ChessPiece(ChessColor.WHITE, ChessPieceKind.KING))
        ] = 0.30

        val decoded = ChessPositionDecoder.decode(probabilities)
        assertThat(decoded.placement[sq("e1")])
            .isEqualTo(ChessPiece(ChessColor.WHITE, ChessPieceKind.KING))
        assertLegal(decoded)
    }

    @Test fun ninePawnsIsReducedToEight() {
        val labels = labelsOf(startFen)
        val whitePawn = ChessPiece(ChessColor.WHITE, ChessPieceKind.PAWN)
        labels[sq("d4")] = ChessPositionDecoder.labelFor(whitePawn)
        val probabilities = oneHot(labels)
        probabilities[sq("d4")][labels[sq("d4")]] = 0.40

        val decoded = ChessPositionDecoder.decode(probabilities)
        assertThat(countOf(decoded, whitePawn)).isEqualTo(8)
        assertThat(decoded.placement[sq("d4")]).isNotEqualTo(whitePawn)
        assertLegal(decoded)
    }

    @Test fun aPawnOnTheBackRankIsRelabelled() {
        val labels = labelsOf("4k3/8/8/8/8/8/8/4K3")
        labels[sq("a1")] =
            ChessPositionDecoder.labelFor(ChessPiece(ChessColor.WHITE, ChessPieceKind.PAWN))
        labels[sq("h8")] =
            ChessPositionDecoder.labelFor(ChessPiece(ChessColor.BLACK, ChessPieceKind.PAWN))

        val decoded = ChessPositionDecoder.decode(oneHot(labels))
        assertThat(decoded.placement[sq("a1")])
            .isNotEqualTo(ChessPiece(ChessColor.WHITE, ChessPieceKind.PAWN))
        assertThat(decoded.placement[sq("h8")])
            .isNotEqualTo(ChessPiece(ChessColor.BLACK, ChessPieceKind.PAWN))
        assertThat(decoded.repairs.size).isAtLeast(2)
        assertLegal(decoded)
    }

    /**
     * A full pawn set plus four extra queens: no pawns could have promoted
     * to make them, so some queens have to go.
     */
    @Test fun impossiblePromotionCountIsPaidFor() {
        val labels = labelsOf(startFen)
        val whiteQueen = ChessPiece(ChessColor.WHITE, ChessPieceKind.QUEEN)
        for (name in listOf("a4", "b4", "c4", "d4")) {
            labels[sq(name)] = ChessPositionDecoder.labelFor(whiteQueen)
        }
        val probabilities = oneHot(labels)
        for (name in listOf("a4", "b4", "c4", "d4")) {
            probabilities[sq(name)][ChessPositionDecoder.labelFor(whiteQueen)] = 0.45
        }

        val decoded = ChessPositionDecoder.decode(probabilities)
        assertThat(countOf(decoded, whiteQueen)).isAtMost(1)
        assertLegal(decoded)
    }

    @Test fun tooManyPiecesIsTrimmed() {
        val labels = labelsOf("4k3/8/8/8/8/8/8/4K3")
        val whiteRook = ChessPiece(ChessColor.WHITE, ChessPieceKind.ROOK)
        for (square in 16 until 36) labels[square] = ChessPositionDecoder.labelFor(whiteRook)

        val decoded = ChessPositionDecoder.decode(oneHot(labels))
        assertThat(decoded.placement.count { it?.color == ChessColor.WHITE }).isAtMost(16)
        assertLegal(decoded)
    }

    /** Every square claimed to be a white queen — the worst realistic collapse. */
    @Test fun aPathologicalReadStillProducesALegalBoard() {
        val label = ChessPositionDecoder.labelFor(ChessPiece(ChessColor.WHITE, ChessPieceKind.QUEEN))
        assertLegal(ChessPositionDecoder.decode(oneHot(IntArray(64) { label })))
    }

    /**
     * No signal at all. This is the case that caught the bug where White
     * and Black promoted their kings onto the *same* square, because with
     * a flat distribution every square costs exactly the same.
     */
    @Test fun uniformNoiseProducesALegalBoard() {
        val flat = Array(64) { DoubleArray(classes) { 1.0 / classes } }
        val decoded = ChessPositionDecoder.decode(flat)
        assertThat(countOf(decoded, ChessPiece(ChessColor.WHITE, ChessPieceKind.KING))).isEqualTo(1)
        assertThat(countOf(decoded, ChessPiece(ChessColor.BLACK, ChessPieceKind.KING))).isEqualTo(1)
        assertLegal(decoded)
    }

    /**
     * Genuinely random per-class probabilities — what a confused
     * classifier actually emits, as opposed to a flat distribution.
     *
     * Regression test for a real bug: [ChessPositionDecoder] settles the
     * king count first, but the *later* repairs then relabelled squares
     * via a "next best class" lookup that was free to pick a king, so a
     * noisy read came back with seven white kings. Uniform noise never
     * caught it — with every class equal the lookup lands on `empty`.
     */
    @Test fun randomNoiseProducesALegalBoard() {
        val whiteKing = ChessPiece(ChessColor.WHITE, ChessPieceKind.KING)
        val blackKing = ChessPiece(ChessColor.BLACK, ChessPieceKind.KING)

        for (trial in 0 until 25) {
            val random = java.util.Random(trial.toLong())
            val probabilities = Array(64) {
                val raw = DoubleArray(classes) { random.nextDouble() + 1e-6 }
                val total = raw.sum()
                DoubleArray(classes) { raw[it] / total }
            }

            val decoded = ChessPositionDecoder.decode(probabilities)
            assertWithMessage("trial %s: exactly one white king", trial)
                .that(countOf(decoded, whiteKing)).isEqualTo(1)
            assertWithMessage("trial %s: exactly one black king", trial)
                .that(countOf(decoded, blackKing)).isEqualTo(1)
            assertLegal(decoded)
        }
    }

    // MARK: - Plumbing

    @Test fun softmaxNormalises() {
        val row = DoubleArray(classes)
        row[1] = 1.0
        val probabilities = ChessPositionDecoder.probabilitiesFromLogits(arrayOf(row))[0]
        assertThat(probabilities.sum()).isWithin(1e-9).of(1.0)
        assertThat(probabilities.max()).isEqualTo(probabilities[1])
    }

    @Test fun labelSchemeMatchesChessPieceIndex() {
        // The apps decode a square as `ChessPiece(label - 1)`, and the
        // models are trained against exactly this numbering.
        assertThat(ChessPositionDecoder.labelFor(ChessPiece(ChessColor.WHITE, ChessPieceKind.PAWN)))
            .isEqualTo(1)
        assertThat(ChessPositionDecoder.labelFor(ChessPiece(ChessColor.WHITE, ChessPieceKind.KING)))
            .isEqualTo(6)
        assertThat(ChessPositionDecoder.labelFor(ChessPiece(ChessColor.BLACK, ChessPieceKind.PAWN)))
            .isEqualTo(7)
        assertThat(ChessPositionDecoder.labelFor(ChessPiece(ChessColor.BLACK, ChessPieceKind.KING)))
            .isEqualTo(12)
        assertThat(ChessPositionDecoder.pieceFor(0)).isNull()
        for (label in 1..12) {
            assertThat(ChessPositionDecoder.labelFor(ChessPositionDecoder.pieceFor(label)!!))
                .isEqualTo(label)
        }
    }

    // MARK: - Castling inference

    @Test fun plausibleCastlingRightsAreBounded() {
        assertThat(plausibleCastlingRights(ChessPosition.standard().placement))
            .isEqualTo(ChessCastlingRights.ALL)

        // King off e1: White has no rights, Black keeps both.
        val movedKing = plausibleCastlingRights(
            ChessPosition.fromFen("r3k2r/8/8/8/8/8/8/R4RK1")!!.placement
        )
        assertThat(movedKing.contains(ChessCastlingRights.WHITE_KINGSIDE)).isFalse()
        assertThat(movedKing.contains(ChessCastlingRights.WHITE_QUEENSIDE)).isFalse()
        assertThat(movedKing.contains(ChessCastlingRights.BLACK_KINGSIDE)).isTrue()
        assertThat(movedKing.contains(ChessCastlingRights.BLACK_QUEENSIDE)).isTrue()

        // Missing h1 rook: kingside impossible, queenside still open.
        val missingRook = plausibleCastlingRights(
            ChessPosition.fromFen("4k3/8/8/8/8/8/8/R3K3")!!.placement
        )
        assertThat(missingRook.contains(ChessCastlingRights.WHITE_KINGSIDE)).isFalse()
        assertThat(missingRook.contains(ChessCastlingRights.WHITE_QUEENSIDE)).isTrue()
    }
}
