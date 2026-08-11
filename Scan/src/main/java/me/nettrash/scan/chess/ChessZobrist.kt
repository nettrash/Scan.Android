package me.nettrash.scan.chess

/**
 * Zobrist hashing — the incremental 64-bit position key used by the
 * transposition table and repetition detection. Port of
 * `Scan/Chess/ChessZobrist.swift`.
 *
 * The constants come from a **fixed-seed xorshift64\*** generator rather
 * than a system RNG, for two reasons that matter more than entropy
 * quality:
 *
 * 1. The Swift port must produce byte-identical keys, so a key printed on
 *    one platform means something on the other. A named algorithm with a
 *    named seed is reproducible; a system RNG is not. `ChessEngineTest`
 *    pins the first few constants for exactly this reason.
 * 2. A run-to-run stable key makes any hash collision reproducible rather
 *    than a heisenbug.
 *
 * xorshift64\* is more than good enough: we need ~800 well-spread 64-bit
 * constants, not cryptographic randomness. Kotlin's `Long` arithmetic
 * wraps identically to Swift's `UInt64` `&*`, so the two generators emit
 * the same sequence.
 */
object ChessZobrist {

    /**
     * The seed. Arbitrary, but *fixed* — nothing is persisted, yet
     * changing it would silently break cross-platform key comparison, so
     * treat it as a constant of the format rather than a tunable.
     */
    private const val SEED = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15

    private const val MULTIPLIER = 2685821657736338717L

    /** 12 x 64 piece-square keys, piece index major. */
    private val pieceSquare = LongArray(12 * 64)

    /** XOR-ed in whenever it is Black's turn. */
    var sideToMove: Long = 0L
        private set

    /** One key per castling-rights combination, indexed by the raw 4 bits. */
    private val castlingKeys = LongArray(16)

    /** En-passant keys, indexed by **file**. */
    private val enPassantFileKeys = LongArray(8)

    init {
        // One deterministic pass, so the draw order is part of the spec.
        var state = SEED

        fun next(): Long {
            state = state xor (state ushr 12)
            state = state xor (state shl 25)
            state = state xor (state ushr 27)
            return state * MULTIPLIER
        }

        for (i in pieceSquare.indices) pieceSquare[i] = next()
        sideToMove = next()
        for (i in castlingKeys.indices) castlingKeys[i] = next()
        for (i in enPassantFileKeys.indices) enPassantFileKeys[i] = next()
    }

    /** Key contribution of a piece standing on a square. */
    fun piece(pieceIndex: Int, square: Int): Long = pieceSquare[pieceIndex * 64 + square]

    fun castling(rights: ChessCastlingRights): Long = castlingKeys[rights.rawValue]

    /**
     * Key contribution of an en-passant target, keyed by **file** only.
     *
     * The rank is implied by the side to move, so including it would be a
     * constant offset; file-only keeps the table at 8 entries and matches
     * how FEN-derived positions compare.
     */
    fun enPassantFile(file: Int): Long = enPassantFileKeys[file]
}
