package me.nettrash.scan.chess

/**
 * Core chess value types — the Kotlin half of the engine shared with the
 * iOS app's `Scan/Chess/ChessTypes.swift`.
 *
 * The two ports are kept deliberately line-for-line similar. They are
 * verified against the same published perft counts, so when one of them
 * is wrong the other is the reference implementation; that only works if
 * you can read them side by side.
 *
 * ## Square indexing — the convention everything else assumes
 *
 * Squares are **LERF** (Little-Endian Rank-File): `index = rank * 8 + file`,
 * so a1 = 0, b1 = 1, … h1 = 7, a2 = 8, … h8 = 63.
 *
 * ```
 * 8 | 56 57 58 59 60 61 62 63
 * 7 | 48 49 50 51 52 53 54 55
 * 6 | 40 41 42 43 44 45 46 47
 * 5 | 32 33 34 35 36 37 38 39
 * 4 | 24 25 26 27 28 29 30 31
 * 3 | 16 17 18 19 20 21 22 23
 * 2 |  8  9 10 11 12 13 14 15
 * 1 |  0  1  2  3  4  5  6  7
 *   +------------------------
 *      a  b  c  d  e  f  g  h
 * ```
 *
 * This is what makes bitboard shifts mean what they look like: `bb shl 8`
 * is "one rank towards rank 8". Note it is the *opposite* vertical order
 * from how FEN is written (FEN starts at rank 8), which is why the FEN
 * code walks ranks downwards.
 *
 * ## Why value classes rather than data classes
 *
 * `ChessSquare`, `ChessPiece`, `ChessMove` and `ChessCastlingRights` are
 * `@JvmInline value class`es over a primitive. Passed as function
 * arguments they compile down to a bare `int` with no allocation, which
 * matters because the search touches them millions of times a second.
 * (They *do* box inside generic containers, so the hot paths store raw
 * `Int`/`Long` in primitive arrays instead — see `ChessPosition`.)
 */

// MARK: - Colour

/**
 * The side to move, or the owner of a piece.
 *
 * `ordinal` is used as a table index throughout (white = 0, black = 1),
 * so the declaration order is load-bearing.
 */
enum class ChessColor {
    WHITE,
    BLACK;

    val opposite: ChessColor
        get() = if (this == WHITE) BLACK else WHITE

    /** Single-character side identifier as used in FEN ("w" / "b"). */
    val fenChar: Char
        get() = if (this == WHITE) 'w' else 'b'

    /**
     * Direction a pawn of this colour travels, in *square indices*:
     * +8 for white (up the board), -8 for black.
     */
    val pawnPush: Int
        get() = if (this == WHITE) 8 else -8

    /** Capitalised side name for user-facing strings. */
    val displayName: String
        get() = if (this == WHITE) "White" else "Black"
}

// MARK: - Piece kind

/**
 * One of the six piece kinds.
 *
 * `ordinal` indexes every `[6]`-sized table in the engine (material
 * values, piece-square tables, per-kind bitboards) — do not reorder.
 */
enum class ChessPieceKind {
    PAWN,
    KNIGHT,
    BISHOP,
    ROOK,
    QUEEN,
    KING;

    /** FEN letter, uppercase. Lowercase represents black. */
    val fenLetter: Char
        get() = when (this) {
            PAWN -> 'P'
            KNIGHT -> 'N'
            BISHOP -> 'B'
            ROOK -> 'R'
            QUEEN -> 'Q'
            KING -> 'K'
        }

    companion object {
        fun fromFenLetter(ch: Char): ChessPieceKind? = when (ch) {
            'P', 'p' -> PAWN
            'N', 'n' -> KNIGHT
            'B', 'b' -> BISHOP
            'R', 'r' -> ROOK
            'Q', 'q' -> QUEEN
            'K', 'k' -> KING
            else -> null
        }

        /** Cached — `values()` allocates a fresh array on every call. */
        val ALL: Array<ChessPieceKind> = entries.toTypedArray()
    }
}

// MARK: - Piece

/**
 * A coloured piece, stored as its dense 0…11 index (`colour * 6 + kind`).
 *
 * That index addresses the twelve piece bitboards and the Zobrist
 * piece-square table directly, so it is the natural representation
 * rather than a derived one.
 */
@JvmInline
value class ChessPiece(val index: Int) {

    constructor(color: ChessColor, kind: ChessPieceKind) :
        this(color.ordinal * 6 + kind.ordinal)

    val color: ChessColor
        get() = if (index < 6) ChessColor.WHITE else ChessColor.BLACK

    val kind: ChessPieceKind
        get() = ChessPieceKind.ALL[index % 6]

    /** FEN representation — uppercase white, lowercase black. */
    val fenChar: Char
        get() {
            val letter = kind.fenLetter
            return if (color == ChessColor.WHITE) letter else letter.lowercaseChar()
        }

    override fun toString(): String = fenChar.toString()

    companion object {
        fun fromFenChar(ch: Char): ChessPiece? {
            val kind = ChessPieceKind.fromFenLetter(ch) ?: return null
            return ChessPiece(if (ch.isUpperCase()) ChessColor.WHITE else ChessColor.BLACK, kind)
        }
    }
}

// MARK: - Square

/**
 * A square, stored as a raw 0…63 LERF index. See the file header.
 */
@JvmInline
value class ChessSquare(val index: Int) {

    /** 0 = a … 7 = h */
    val file: Int get() = index and 7

    /** 0 = rank 1 … 7 = rank 8 */
    val rank: Int get() = index shr 3

    /** The single-bit bitboard for this square. */
    val bit: Long get() = 1L shl index

    /**
     * True for light squares (h1, a8, … are light).
     *
     * Matches the prototype's `(file + rank) % 2 == 1` parity so that
     * recognition code reasoning about light/dark square baselines agrees
     * with the engine about which squares are which.
     */
    val isLight: Boolean get() = (file + rank) % 2 == 1

    val algebraic: String get() = "${FILES[file]}${rank + 1}"

    override fun toString(): String = algebraic

    companion object {
        private const val FILES = "abcdefgh"

        fun of(file: Int, rank: Int): ChessSquare {
            require(file in 0..7 && rank in 0..7) { "file/rank out of range: $file/$rank" }
            return ChessSquare(rank * 8 + file)
        }

        /**
         * Parse algebraic notation such as "e4". Case-insensitive on the
         * file letter; returns null for anything that is not exactly one
         * file letter followed by one rank digit.
         */
        fun parse(algebraic: String): ChessSquare? {
            if (algebraic.length != 2) return null
            val file = FILES.indexOf(algebraic[0].lowercaseChar())
            if (file < 0) return null
            val rank = algebraic[1] - '1'
            if (rank !in 0..7) return null
            return ChessSquare(rank * 8 + file)
        }

        val A1 = ChessSquare(0)
        val B1 = ChessSquare(1)
        val C1 = ChessSquare(2)
        val D1 = ChessSquare(3)
        val E1 = ChessSquare(4)
        val F1 = ChessSquare(5)
        val G1 = ChessSquare(6)
        val H1 = ChessSquare(7)
        val A8 = ChessSquare(56)
        val B8 = ChessSquare(57)
        val C8 = ChessSquare(58)
        val D8 = ChessSquare(59)
        val E8 = ChessSquare(60)
        val F8 = ChessSquare(61)
        val G8 = ChessSquare(62)
        val H8 = ChessSquare(63)
    }
}

// MARK: - Castling rights

/**
 * The four castling rights as a 4-bit set.
 *
 * Bit order matches FEN's "KQkq" so the writer can walk the options in
 * declaration order and produce a canonical string.
 */
@JvmInline
value class ChessCastlingRights(val rawValue: Int) {

    operator fun contains(other: ChessCastlingRights): Boolean =
        rawValue and other.rawValue == other.rawValue

    infix fun or(other: ChessCastlingRights) = ChessCastlingRights(rawValue or other.rawValue)

    infix fun and(other: ChessCastlingRights) = ChessCastlingRights(rawValue and other.rawValue)

    fun removing(other: ChessCastlingRights) = ChessCastlingRights(rawValue and other.rawValue.inv())

    /** FEN field: "KQkq", a subset thereof, or "-" when empty. */
    val fenField: String
        get() {
            val out = StringBuilder()
            if (contains(WHITE_KINGSIDE)) out.append('K')
            if (contains(WHITE_QUEENSIDE)) out.append('Q')
            if (contains(BLACK_KINGSIDE)) out.append('k')
            if (contains(BLACK_QUEENSIDE)) out.append('q')
            return if (out.isEmpty()) "-" else out.toString()
        }

    companion object {
        val NONE = ChessCastlingRights(0)
        val WHITE_KINGSIDE = ChessCastlingRights(1 shl 0)
        val WHITE_QUEENSIDE = ChessCastlingRights(1 shl 1)
        val BLACK_KINGSIDE = ChessCastlingRights(1 shl 2)
        val BLACK_QUEENSIDE = ChessCastlingRights(1 shl 3)
        val ALL = ChessCastlingRights(0b1111)

        fun kingside(color: ChessColor) =
            if (color == ChessColor.WHITE) WHITE_KINGSIDE else BLACK_KINGSIDE

        fun queenside(color: ChessColor) =
            if (color == ChessColor.WHITE) WHITE_QUEENSIDE else BLACK_QUEENSIDE

        /** Both rights of one side — cleared wholesale when its king moves. */
        fun both(color: ChessColor) =
            if (color == ChessColor.WHITE) ChessCastlingRights(0b0011)
            else ChessCastlingRights(0b1100)

        /**
         * Parse a FEN castling field. "-" and the empty string mean no
         * rights; unrecognised characters are ignored rather than
         * rejected, because this parser is also fed fields assembled by
         * the position editor from user toggles.
         */
        fun parse(fenField: String): ChessCastlingRights {
            var raw = 0
            for (ch in fenField) {
                when (ch) {
                    'K' -> raw = raw or WHITE_KINGSIDE.rawValue
                    'Q' -> raw = raw or WHITE_QUEENSIDE.rawValue
                    'k' -> raw = raw or BLACK_KINGSIDE.rawValue
                    'q' -> raw = raw or BLACK_QUEENSIDE.rawValue
                }
            }
            return ChessCastlingRights(raw)
        }
    }
}
