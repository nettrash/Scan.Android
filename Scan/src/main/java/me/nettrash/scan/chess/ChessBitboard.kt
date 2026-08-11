package me.nettrash.scan.chess

/**
 * 64-bit board sets and the attack tables the move generator runs on.
 * Port of the iOS app's `Scan/Chess/ChessBitboard.swift`.
 *
 * ## Long, not ULong
 *
 * The Swift side wraps `UInt64` in a `ChessBitboard` struct. Here a
 * bitboard is a plain `Long`, because a wrapper `value class` boxes the
 * moment it enters a generic container — and every attack table is an
 * array of thousands of them. `LongArray` stores primitives; an
 * `Array<Bitboard>` would allocate 4096 objects for the `between` table
 * alone.
 *
 * Signedness never matters: every operation used here is bitwise, and the
 * one place it would (right shift) uses Kotlin's `ushr` explicitly.
 *
 * ## Why classical ray attacks and not magic bitboards
 *
 * Sliding attacks use the **classical** method: a precomputed ray per
 * (direction, square), masked against occupancy, with the first blocker's
 * own ray XOR-ed back off. Perhaps 2-3x slower than magic bitboards, and
 * chosen deliberately:
 *
 * - Magics need a magic-number search, and a wrong constant produces
 *   attacks that are correct for *most* occupancies — the failure mode
 *   perft catches late and painfully.
 * - The engine exists twice, here and in Swift, and the two must agree
 *   move for move. Classical rays port across with no reinterpretation.
 * - At the 1-3 s/move budget this feature uses, move generation is not
 *   the bottleneck; move ordering and pruning are.
 *
 * Measured at ~39M nodes/s on the Swift side, which is far more than the
 * feature needs.
 */

/** A set of squares, one bit per square, indexed LERF (bit 0 = a1). */
typealias Bitboard = Long

// MARK: - Bitboard operations
//
// Extension functions rather than methods, so they stay zero-cost on a
// primitive `Long`.

/** Number of squares in the set. */
fun Bitboard.count(): Int = java.lang.Long.bitCount(this)

fun Bitboard.isEmpty(): Boolean = this == 0L

fun Bitboard.isNotEmpty(): Boolean = this != 0L

/**
 * Index of the least-significant set bit. Undefined (64) when empty —
 * callers must check first.
 */
fun Bitboard.lsbIndex(): Int = java.lang.Long.numberOfTrailingZeros(this)

/** Index of the most-significant set bit. Undefined when empty. */
fun Bitboard.msbIndex(): Int = 63 - java.lang.Long.numberOfLeadingZeros(this)

/** Clear the least-significant set bit. */
fun Bitboard.withoutLsb(): Bitboard = this and (this - 1L)

fun Bitboard.contains(index: Int): Boolean = this and (1L shl index) != 0L

fun Bitboard.setting(index: Int): Bitboard = this or (1L shl index)

/**
 * Every square in the set, a1-first. Allocates — for debugging, tests and
 * UI only. The search iterates with `lsbIndex` / `withoutLsb` instead:
 *
 * ```
 * var bb = position.pieces(us, KNIGHT)
 * while (bb.isNotEmpty()) {
 *     val from = bb.lsbIndex()
 *     bb = bb.withoutLsb()
 *     …
 * }
 * ```
 */
fun Bitboard.squares(): List<ChessSquare> {
    val out = ArrayList<ChessSquare>(count())
    var bb = this
    while (bb.isNotEmpty()) {
        out.add(ChessSquare(bb.lsbIndex()))
        bb = bb.withoutLsb()
    }
    return out
}

// MARK: - Masks

object ChessMasks {
    // The two high-bit masks are written as negative literals because
    // 0x8080…80 and 0xFF00…00 exceed Long's positive range; the bit
    // patterns are identical, and nothing here depends on the sign.
    const val FILE_A: Bitboard = 0x0101010101010101L
    const val FILE_H: Bitboard = -0x7f7f7f7f7f7f7f80L // 0x8080808080808080
    const val RANK_1: Bitboard = 0xFFL
    const val RANK_8: Bitboard = -0x100000000000000L // 0xFF00000000000000

    /** Mask of one file, 0 = a … 7 = h. */
    fun file(index: Int): Bitboard = FILE_A shl index

    /** Mask of one rank, 0 = rank 1 … 7 = rank 8. */
    fun rank(index: Int): Bitboard = 0xFFL shl (index * 8)
}

// MARK: - Attack tables

/**
 * Precomputed attack tables, built once when the object is first touched
 * (the JVM makes class initialisation thread-safe) and read-only after.
 */
object ChessTables {

    /**
     * The eight ray directions as (file delta, rank delta).
     *
     * Split conceptually into positive (index grows) and negative (index
     * shrinks) halves, because the blocker scan differs: positive rays
     * take the *least*-significant blocker, negative rays the *most*.
     */
    private val DIRECTION_FILE = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
    private val DIRECTION_RANK = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)

    private const val NORTH = 0
    private const val NORTH_EAST = 1
    private const val EAST = 2
    private const val SOUTH_EAST = 3
    private const val SOUTH = 4
    private const val SOUTH_WEST = 5
    private const val WEST = 6
    private const val NORTH_WEST = 7

    /**
     * `rays[direction * 64 + square]` — every square from `square` outward
     * in `direction`, exclusive of `square`, ignoring occupancy.
     *
     * Built by walking file/rank rather than by shifting indices, which
     * makes edge wraparound structurally impossible instead of something
     * a mask has to catch.
     */
    private val rays: LongArray = LongArray(8 * 64).also { table ->
        for (direction in 0 until 8) {
            val df = DIRECTION_FILE[direction]
            val dr = DIRECTION_RANK[direction]
            for (square in 0 until 64) {
                var bb = 0L
                var file = (square and 7) + df
                var rank = (square shr 3) + dr
                while (file in 0..7 && rank in 0..7) {
                    bb = bb.setting(rank * 8 + file)
                    file += df
                    rank += dr
                }
                table[direction * 64 + square] = bb
            }
        }
    }

    /** Ray attacks in a direction whose index *increases* (N, NE, E, NW). */
    private fun positiveRay(direction: Int, square: Int, occupancy: Bitboard): Bitboard {
        val ray = rays[direction * 64 + square]
        val blockers = ray and occupancy
        if (blockers == 0L) return ray
        return ray xor rays[direction * 64 + blockers.lsbIndex()]
    }

    /** Ray attacks in a direction whose index *decreases* (S, SW, W, SE). */
    private fun negativeRay(direction: Int, square: Int, occupancy: Bitboard): Bitboard {
        val ray = rays[direction * 64 + square]
        val blockers = ray and occupancy
        if (blockers == 0L) return ray
        return ray xor rays[direction * 64 + blockers.msbIndex()]
    }

    /**
     * Rook attacks from `square` given `occupancy`.
     *
     * Includes the blocking squares themselves — callers mask off their
     * own pieces. Every `…Attacks` function shares that convention, which
     * is what lets `attackersTo` treat all kinds uniformly.
     */
    fun rookAttacks(square: Int, occupancy: Bitboard): Bitboard =
        positiveRay(NORTH, square, occupancy) or
            positiveRay(EAST, square, occupancy) or
            negativeRay(SOUTH, square, occupancy) or
            negativeRay(WEST, square, occupancy)

    fun bishopAttacks(square: Int, occupancy: Bitboard): Bitboard =
        positiveRay(NORTH_EAST, square, occupancy) or
            positiveRay(NORTH_WEST, square, occupancy) or
            negativeRay(SOUTH_EAST, square, occupancy) or
            negativeRay(SOUTH_WEST, square, occupancy)

    fun queenAttacks(square: Int, occupancy: Bitboard): Bitboard =
        rookAttacks(square, occupancy) or bishopAttacks(square, occupancy)

    /** Attacks for a slider. Non-sliders return empty. */
    fun sliderAttacks(kind: ChessPieceKind, square: Int, occupancy: Bitboard): Bitboard =
        when (kind) {
            ChessPieceKind.BISHOP -> bishopAttacks(square, occupancy)
            ChessPieceKind.ROOK -> rookAttacks(square, occupancy)
            ChessPieceKind.QUEEN -> queenAttacks(square, occupancy)
            else -> 0L
        }

    /** `knightAttacks[square]` — the up-to-8 knight destinations. */
    val knightAttacks: LongArray = leaperTable(
        intArrayOf(1, 2, 2, 1, -1, -2, -2, -1),
        intArrayOf(2, 1, -1, -2, -2, -1, 1, 2),
    )

    /** `kingAttacks[square]` — the up-to-8 adjacent squares. */
    val kingAttacks: LongArray = leaperTable(
        intArrayOf(0, 1, 1, 1, 0, -1, -1, -1),
        intArrayOf(1, 1, 0, -1, -1, -1, 0, 1),
    )

    /**
     * `pawnAttacks[colour * 64 + square]` — the two diagonal capture
     * squares (one on the a/h files).
     *
     * Note this is what a pawn *attacks*, which is not where it can move;
     * pushes are generated separately.
     */
    val pawnAttacks: LongArray = LongArray(2 * 64).also { table ->
        for (color in 0 until 2) {
            val dr = if (color == 0) 1 else -1
            for (square in 0 until 64) {
                var bb = 0L
                val file = square and 7
                val rank = square shr 3
                for (df in intArrayOf(-1, 1)) {
                    val f = file + df
                    val r = rank + dr
                    if (f in 0..7 && r in 0..7) bb = bb.setting(r * 8 + f)
                }
                table[color * 64 + square] = bb
            }
        }
    }

    private fun leaperTable(fileDeltas: IntArray, rankDeltas: IntArray): LongArray =
        LongArray(64).also { table ->
            for (square in 0 until 64) {
                var bb = 0L
                val file = square and 7
                val rank = square shr 3
                for (i in fileDeltas.indices) {
                    val f = file + fileDeltas[i]
                    val r = rank + rankDeltas[i]
                    if (f in 0..7 && r in 0..7) bb = bb.setting(r * 8 + f)
                }
                table[square] = bb
            }
        }
}
