package me.nettrash.scan.chess

/**
 * A move, packed into 16 bits. Port of `Scan/Chess/ChessMove.swift`.
 *
 * Layout (the encoding most bitboard engines use, kept verbatim so the
 * two ports stay transliterations of each other):
 *
 * ```
 * bits  0..5   from square (0..63)
 * bits  6..11  to square   (0..63)
 * bits 12..15  flags
 * ```
 *
 * Flag values are a small bit-field of their own, which is what makes
 * [isCapture] / [isPromotion] single-instruction tests:
 *
 * ```
 * bit 12 (1) ── promotion kind, low bit   ┐ together: 0=N 1=B 2=R 3=Q
 * bit 13 (2) ── promotion kind, high bit  ┘
 * bit 14 (4) ── capture
 * bit 15 (8) ── promotion
 *
 * 0  quiet                 8  promote to knight
 * 1  double pawn push      9  promote to bishop
 * 2  castle kingside      10  promote to rook
 * 3  castle queenside     11  promote to queen
 * 4  capture              12  capture + promote to knight
 * 5  en-passant capture   13  capture + promote to bishop
 *                         14  capture + promote to rook
 *                         15  capture + promote to queen
 * ```
 *
 * Flags 6 and 7 are unused. Note that flags 1, 2 and 3 are *not* captures
 * despite sitting below bit 14 — the capture bit is only meaningful for
 * values >= 4, which is why [isCapture] tests the bit rather than
 * comparing ranges.
 *
 * Stored in an `Int` rather than a `Short`: the JVM widens shorts to ints
 * for every arithmetic operation anyway, so a short would cost conversions
 * and save nothing.
 */
@JvmInline
value class ChessMove(val rawValue: Int) {

    val fromIndex: Int get() = rawValue and 0x3F
    val toIndex: Int get() = (rawValue ushr 6) and 0x3F
    val flagBits: Int get() = (rawValue ushr 12) and 0xF

    val from: ChessSquare get() = ChessSquare(fromIndex)
    val to: ChessSquare get() = ChessSquare(toIndex)

    val isNone: Boolean get() = rawValue == 0

    /** True for ordinary captures, en passant, and capture-promotions. */
    val isCapture: Boolean get() = flagBits and 0b0100 != 0

    /** True for all four promotion flags, with or without capture. */
    val isPromotion: Boolean get() = flagBits and 0b1000 != 0

    val isEnPassant: Boolean get() = flagBits == EN_PASSANT

    val isCastle: Boolean get() = flagBits == CASTLE_KINGSIDE || flagBits == CASTLE_QUEENSIDE

    val isDoublePawnPush: Boolean get() = flagBits == DOUBLE_PAWN_PUSH

    /** The piece a promoting pawn becomes, or null for non-promotions. */
    val promotionKind: ChessPieceKind?
        get() {
            if (!isPromotion) return null
            // The low two flag bits select N/B/R/Q, which are contiguous
            // in ChessPieceKind starting at KNIGHT = 1.
            return ChessPieceKind.ALL[(flagBits and 0b0011) + 1]
        }

    /**
     * Long algebraic / UCI form: "e2e4", "e7e8q", "e1g1" for castling.
     *
     * Castling is emitted king-from/king-to rather than the
     * king-takes-rook form some engines use, because that is what the UI
     * draws as an arrow.
     */
    val uci: String
        get() {
            val sb = StringBuilder(5)
            sb.append(from.algebraic).append(to.algebraic)
            promotionKind?.let { sb.append(it.fenLetter.lowercaseChar()) }
            return sb.toString()
        }

    override fun toString(): String = if (isNone) "(none)" else uci

    companion object {
        const val QUIET = 0
        const val DOUBLE_PAWN_PUSH = 1
        const val CASTLE_KINGSIDE = 2
        const val CASTLE_QUEENSIDE = 3
        const val CAPTURE = 4
        const val EN_PASSANT = 5
        const val PROMOTE_KNIGHT = 8
        const val PROMOTE_BISHOP = 9
        const val PROMOTE_ROOK = 10
        const val PROMOTE_QUEEN = 11
        const val CAPTURE_PROMOTE_KNIGHT = 12
        const val CAPTURE_PROMOTE_BISHOP = 13
        const val CAPTURE_PROMOTE_ROOK = 14
        const val CAPTURE_PROMOTE_QUEEN = 15

        /**
         * The null move — a1a1, which is never legal, so it doubles as
         * "no move" in the transposition table and search bookkeeping.
         */
        val NONE = ChessMove(0)

        fun of(from: Int, to: Int, flag: Int = QUIET): ChessMove =
            ChessMove(from or (to shl 6) or (flag shl 12))

        fun of(from: ChessSquare, to: ChessSquare, flag: Int = QUIET): ChessMove =
            of(from.index, to.index, flag)

        /** The promotion flag for [kind], capturing or not. */
        fun promotionFlag(kind: ChessPieceKind, capture: Boolean): Int {
            val base = when (kind) {
                ChessPieceKind.KNIGHT -> 0
                ChessPieceKind.BISHOP -> 1
                ChessPieceKind.ROOK -> 2
                ChessPieceKind.QUEEN -> 3
                else -> return QUIET
            }
            return base or 0b1000 or (if (capture) 0b0100 else 0)
        }

        /**
         * Parse a UCI move *without* a position to check it against.
         *
         * Recovers the squares and the promotion piece, but cannot know
         * whether the move is a capture, en passant or castling — those
         * depend on the position. The result is only suitable for
         * matching against generated legal moves by from/to/promotion,
         * which is what `ChessPosition.moveFromUci` does.
         */
        fun fromUci(uci: String): ChessMove? {
            val text = uci.lowercase()
            if (text.length != 4 && text.length != 5) return null
            val from = ChessSquare.parse(text.substring(0, 2)) ?: return null
            val to = ChessSquare.parse(text.substring(2, 4)) ?: return null

            var flag = QUIET
            if (text.length == 5) {
                val kind = ChessPieceKind.fromFenLetter(text[4]) ?: return null
                if (kind == ChessPieceKind.PAWN || kind == ChessPieceKind.KING) return null
                flag = promotionFlag(kind, capture = false)
            }
            return of(from, to, flag)
        }
    }
}

/**
 * A fixed-capacity move buffer backed by an `IntArray`.
 *
 * 256 is comfortably above the most moves any legal position can produce
 * (the known maximum is 218), so generation never tests for overflow on
 * the hot path. The `check` catches a generator bug in debug scenarios;
 * beyond capacity the extra move is dropped rather than throwing in a
 * user's hands mid-analysis.
 */
class ChessMoveList {
    private val storage = IntArray(CAPACITY)
    var count: Int = 0
        private set

    fun add(move: ChessMove) {
        if (count >= CAPACITY) return
        storage[count++] = move.rawValue
    }

    operator fun get(index: Int): ChessMove = ChessMove(storage[index])

    operator fun set(index: Int, move: ChessMove) {
        storage[index] = move.rawValue
    }

    fun clear() {
        count = 0
    }

    val isEmpty: Boolean get() = count == 0

    /**
     * Swap two entries. Used by the search's selection-sort move picker,
     * which pulls the best-scoring move to the front one ply at a time
     * rather than sorting the whole list up front.
     */
    fun swap(i: Int, j: Int) {
        val tmp = storage[i]
        storage[i] = storage[j]
        storage[j] = tmp
    }

    /** Copy out as an ordinary list — for tests and UI, not the search. */
    fun toList(): List<ChessMove> = (0 until count).map { ChessMove(storage[it]) }

    private companion object {
        const val CAPACITY = 256
    }
}
