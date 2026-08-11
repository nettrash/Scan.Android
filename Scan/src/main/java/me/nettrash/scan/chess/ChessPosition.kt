package me.nettrash.scan.chess

/**
 * The board: piece placement, side to move, castling rights, en-passant
 * target, the two clocks, and the incrementally-maintained Zobrist key.
 * Port of `Scan/Chess/ChessPosition.swift`.
 *
 * ## Storage
 *
 * Redundant on purpose — twelve piece bitboards *and* a 64-entry mailbox.
 * The bitboards answer "where are all the white knights" in one load
 * (move generation); the mailbox answers "what is standing on e4" in one
 * load (make/unmake, captures, SAN). Keeping both in sync costs a couple
 * of instructions per piece movement and saves a scan on every one.
 *
 * ## Two deliberate departures from the Swift port
 *
 * **This is a class, not a value type.** Swift's `ChessPosition` is a
 * struct, so `var scratch = position` copies. Here that would alias, so
 * every place the Swift code relies on an implicit copy calls [copy]
 * explicitly. Those call sites are the ones to check first if the two
 * ports ever disagree.
 *
 * **Undo lives in an internal stack**, not in a token handed back from
 * `make`. Swift can return an `Undo` struct for free; on the JVM that
 * would be an allocation per move, and the search makes tens of millions
 * of them. So `make(move)` pushes the irreversible state and
 * `unmake(move)` pops it, and the two must be paired by the caller —
 * exactly as with the Swift token, just implicit.
 *
 * `unmake` restores the Zobrist key wholesale from the stack rather than
 * XOR-ing every component back out. Reversing an incremental hash by hand
 * is a classic source of "the transposition table is subtly poisoned"
 * bugs, and the stored key costs eight bytes we already pay for.
 *
 * ## Validity
 *
 * The constructors accept anything *structurally* well-formed, including
 * positions that are illegal chess (no king, nine pawns, a pawn on the
 * back rank). That is deliberate: the position editor must be able to
 * hold a half-finished board while the user fixes a misread square, and
 * the recogniser's raw output is frequently illegal before anyone touches
 * it. Chess-level problems are reported by `legalityProblems`, and
 * `ChessEngine` refuses to search a position that has any.
 */
class ChessPosition {

    // MARK: - Storage

    /** One bitboard per (colour, kind), indexed by `ChessPiece.index`. */
    private val pieceBB = LongArray(12)

    /** Union of each colour's pieces, indexed by `ChessColor.ordinal`. */
    private val colorBB = LongArray(2)

    /** Union of both colours. */
    var occupied: Bitboard = 0L
        private set

    /** Mailbox: dense piece index per square, or -1 for empty. */
    private val board = ByteArray(64) { -1 }

    var sideToMove: ChessColor = ChessColor.WHITE
        private set

    var castlingRights: ChessCastlingRights = ChessCastlingRights.NONE
        private set

    /**
     * En-passant target square index, or -1 when there is none.
     *
     * This is the square *behind* the pawn that just double-pushed (the
     * square a capturing pawn moves **to**), matching FEN. Stored exactly
     * as given rather than cleared when no capture is actually available —
     * the standard perft positions depend on that literal reading.
     */
    var enPassantSquare: Int = -1
        private set

    var halfmoveClock: Int = 0
        private set

    var fullmoveNumber: Int = 1
        private set

    var key: Long = 0L
        private set

    // MARK: - Undo stack

    private val undoKey = LongArray(UNDO_CAPACITY)
    private val undoState = IntArray(UNDO_CAPACITY)
    private var undoDepth = 0

    // MARK: - Construction

    /** An empty board, White to move, no rights. */
    constructor()

    /**
     * Build from a 64-entry placement array, a1 first (LERF).
     *
     * This is the constructor the recogniser uses: constrained decoding
     * hands back exactly this shape — one nullable piece per square — and
     * the position editor edits it in place. FEN parsing routes through
     * here too, so one path establishes the invariants and computes the
     * key.
     */
    constructor(
        placement: Array<ChessPiece?>,
        sideToMove: ChessColor = ChessColor.WHITE,
        castlingRights: ChessCastlingRights = ChessCastlingRights.NONE,
        enPassantSquare: ChessSquare? = null,
        halfmoveClock: Int = 0,
        fullmoveNumber: Int = 1,
    ) {
        require(placement.size == 64) { "placement must describe all 64 squares" }
        for (square in 0 until 64) {
            placement[square]?.let { put(it.index, square) }
        }
        this.sideToMove = sideToMove
        this.castlingRights = castlingRights
        this.enPassantSquare = enPassantSquare?.index ?: -1
        this.halfmoveClock = halfmoveClock
        this.fullmoveNumber = fullmoveNumber
        recomputeKey()
    }

    /**
     * An independent copy. Stands in for the implicit struct copy the
     * Swift port gets for free — see the class documentation.
     *
     * The undo stack is deliberately *not* copied: a copy starts with an
     * empty history, which is what every caller wants (they make and
     * unmake their own moves on it).
     */
    fun copy(): ChessPosition {
        val other = ChessPosition()
        pieceBB.copyInto(other.pieceBB)
        colorBB.copyInto(other.colorBB)
        board.copyInto(other.board)
        other.occupied = occupied
        other.sideToMove = sideToMove
        other.castlingRights = castlingRights
        other.enPassantSquare = enPassantSquare
        other.halfmoveClock = halfmoveClock
        other.fullmoveNumber = fullmoveNumber
        other.key = key
        return other
    }

    /** The 64-entry placement array, a1 first — inverse of the constructor. */
    val placement: Array<ChessPiece?>
        get() = Array(64) { if (board[it] < 0) null else ChessPiece(board[it].toInt()) }

    // MARK: - Piece access

    fun pieces(color: ChessColor, kind: ChessPieceKind): Bitboard =
        pieceBB[color.ordinal * 6 + kind.ordinal]

    /** Both colours' pieces of one kind. */
    fun pieces(kind: ChessPieceKind): Bitboard = pieceBB[kind.ordinal] or pieceBB[6 + kind.ordinal]

    fun pieces(color: ChessColor): Bitboard = colorBB[color.ordinal]

    fun pieceAt(square: Int): ChessPiece? {
        val index = board[square].toInt()
        return if (index < 0) null else ChessPiece(index)
    }

    fun pieceAt(square: ChessSquare): ChessPiece? = pieceAt(square.index)

    /**
     * Square index of [color]'s king, or -1 if that side has no king.
     *
     * A kingless position is illegal but representable (see the class
     * documentation), so this returns -1 rather than throwing. Callers on
     * the search path run after validation and can treat -1 as
     * unreachable; the UI checks for it.
     */
    fun kingSquare(color: ChessColor): Int {
        val bb = pieces(color, ChessPieceKind.KING)
        return if (bb.isEmpty()) -1 else bb.lsbIndex()
    }

    // MARK: - Board mutation primitives
    //
    // Each keeps the bitboards, the mailbox and the Zobrist key in step.
    // Nothing else in the file touches pieceBB / colorBB / board directly,
    // so there is exactly one place for a desync bug to live.

    private fun put(pieceIndex: Int, square: Int) {
        val bit = 1L shl square
        pieceBB[pieceIndex] = pieceBB[pieceIndex] or bit
        val colorIndex = if (pieceIndex < 6) 0 else 1
        colorBB[colorIndex] = colorBB[colorIndex] or bit
        occupied = occupied or bit
        board[square] = pieceIndex.toByte()
        key = key xor ChessZobrist.piece(pieceIndex, square)
    }

    private fun removeAt(square: Int) {
        val pieceIndex = board[square].toInt()
        val bit = 1L shl square
        pieceBB[pieceIndex] = pieceBB[pieceIndex] xor bit
        val colorIndex = if (pieceIndex < 6) 0 else 1
        colorBB[colorIndex] = colorBB[colorIndex] xor bit
        occupied = occupied xor bit
        board[square] = -1
        key = key xor ChessZobrist.piece(pieceIndex, square)
    }

    private fun movePiece(from: Int, to: Int) {
        val pieceIndex = board[from].toInt()
        val mask = (1L shl from) or (1L shl to)
        pieceBB[pieceIndex] = pieceBB[pieceIndex] xor mask
        val colorIndex = if (pieceIndex < 6) 0 else 1
        colorBB[colorIndex] = colorBB[colorIndex] xor mask
        occupied = occupied xor mask
        board[from] = -1
        board[to] = pieceIndex.toByte()
        key = key xor ChessZobrist.piece(pieceIndex, from)
        key = key xor ChessZobrist.piece(pieceIndex, to)
    }

    /**
     * Place or clear a square wholesale. For the position editor and FEN
     * parsing — *not* for making moves, which must go through [make] so
     * clocks, rights and the key stay coherent.
     */
    fun setPiece(piece: ChessPiece?, at: ChessSquare) {
        if (board[at.index] >= 0) removeAt(at.index)
        if (piece != null) put(piece.index, at.index)
    }

    /** Rebuild the key from scratch, after bulk edits. */
    private fun recomputeKey() {
        var fresh = 0L
        for (square in 0 until 64) {
            val index = board[square].toInt()
            if (index >= 0) fresh = fresh xor ChessZobrist.piece(index, square)
        }
        if (sideToMove == ChessColor.BLACK) fresh = fresh xor ChessZobrist.sideToMove
        fresh = fresh xor ChessZobrist.castling(castlingRights)
        if (enPassantSquare >= 0) {
            fresh = fresh xor ChessZobrist.enPassantFile(enPassantSquare and 7)
        }
        key = fresh
    }

    /**
     * Re-derive the key after direct edits via [setPiece] and the setters
     * below. The editor calls this once when the user is done, not after
     * every tap.
     */
    fun refreshKey() = recomputeKey()

    fun setSideToMove(color: ChessColor) {
        sideToMove = color
        recomputeKey()
    }

    fun setCastlingRights(rights: ChessCastlingRights) {
        castlingRights = rights
        recomputeKey()
    }

    /** @param square the square a capturing pawn would move *to*, or null. */
    fun setEnPassantSquare(square: ChessSquare?) {
        enPassantSquare = square?.index ?: -1
        recomputeKey()
    }

    fun setClocks(halfmove: Int, fullmove: Int) {
        halfmoveClock = halfmove
        fullmoveNumber = fullmove
    }

    // MARK: - Make / unmake

    fun make(move: ChessMove) {
        val from = move.fromIndex
        val to = move.toIndex
        val mover = sideToMove

        var capturedPlusOne = 0

        // Snapshot everything `make` is about to destroy, *before* the
        // first mutation — `key` especially, since the en-passant XOR
        // below is already a change to it.
        val savedKey = key
        val savedCastling = castlingRights
        val savedEnPassant = enPassantSquare
        val savedHalfmove = halfmoveClock

        // The old en-passant key leaves as soon as a move is played,
        // whatever the move is.
        if (enPassantSquare >= 0) key = key xor ChessZobrist.enPassantFile(enPassantSquare and 7)

        halfmoveClock += 1

        if (move.isEnPassant) {
            // The captured pawn is not on `to` — it is one push behind it.
            val capturedSquare = to - mover.pawnPush
            capturedPlusOne = board[capturedSquare].toInt() + 1
            removeAt(capturedSquare)
            halfmoveClock = 0
        } else if (move.isCapture) {
            capturedPlusOne = board[to].toInt() + 1
            removeAt(to)
            halfmoveClock = 0
        }

        val movingKind = ChessPiece(board[from].toInt()).kind
        movePiece(from, to)
        if (movingKind == ChessPieceKind.PAWN) halfmoveClock = 0

        move.promotionKind?.let { promotion ->
            removeAt(to)
            put(ChessPiece(mover, promotion).index, to)
        }

        if (move.isCastle) {
            val rookFrom = rookCastleFrom(to)
            val rookTo = rookCastleTo(to)
            movePiece(rookFrom, rookTo)
        }

        key = key xor ChessZobrist.castling(castlingRights)
        castlingRights = castlingRights
            .removing(CASTLING_CLEARED[from])
            .removing(CASTLING_CLEARED[to])
        key = key xor ChessZobrist.castling(castlingRights)

        enPassantSquare = if (move.isDoublePawnPush) from + mover.pawnPush else -1
        if (enPassantSquare >= 0) key = key xor ChessZobrist.enPassantFile(enPassantSquare and 7)

        if (mover == ChessColor.BLACK) fullmoveNumber += 1
        sideToMove = mover.opposite
        key = key xor ChessZobrist.sideToMove

        pushUndo(savedKey, capturedPlusOne, savedCastling, savedEnPassant, savedHalfmove)
    }

    fun unmake(move: ChessMove) {
        val from = move.fromIndex
        val to = move.toIndex

        undoDepth -= 1
        val savedKey = undoKey[undoDepth]
        val packed = undoState[undoDepth]

        sideToMove = sideToMove.opposite
        val mover = sideToMove
        if (mover == ChessColor.BLACK) fullmoveNumber -= 1

        // Turn the promoted piece back into the pawn that walks home.
        if (move.isPromotion) {
            removeAt(to)
            put(ChessPiece(mover, ChessPieceKind.PAWN).index, to)
        }

        if (move.isCastle) {
            movePiece(rookCastleTo(to), rookCastleFrom(to))
        }

        movePiece(to, from)

        val capturedPlusOne = packed and 0x1F
        if (capturedPlusOne != 0) {
            val capturedSquare = if (move.isEnPassant) to - mover.pawnPush else to
            put(capturedPlusOne - 1, capturedSquare)
        }

        castlingRights = ChessCastlingRights((packed ushr 5) and 0xF)
        enPassantSquare = ((packed ushr 9) and 0x7F) - 1
        halfmoveClock = (packed ushr 16) and 0xFFFF
        // Restored, not un-XOR-ed — see the class documentation.
        key = savedKey
    }

    /**
     * Play a "null move": hand the turn over without touching a piece.
     * Used by the search's null-move pruning.
     *
     * Clears the en-passant target (the opponent cannot capture en passant
     * on a move that never happened) and leaves the clocks alone.
     */
    fun makeNull() {
        val savedKey = key
        val savedCastling = castlingRights
        val savedEnPassant = enPassantSquare
        val savedHalfmove = halfmoveClock

        if (enPassantSquare >= 0) key = key xor ChessZobrist.enPassantFile(enPassantSquare and 7)
        enPassantSquare = -1
        if (sideToMove == ChessColor.BLACK) fullmoveNumber += 1
        sideToMove = sideToMove.opposite
        key = key xor ChessZobrist.sideToMove

        pushUndo(savedKey, 0, savedCastling, savedEnPassant, savedHalfmove)
    }

    fun unmakeNull() {
        undoDepth -= 1
        val savedKey = undoKey[undoDepth]
        val packed = undoState[undoDepth]

        if (sideToMove == ChessColor.WHITE) fullmoveNumber -= 1
        sideToMove = sideToMove.opposite
        enPassantSquare = ((packed ushr 9) and 0x7F) - 1
        key = savedKey
    }

    private fun pushUndo(
        savedKey: Long,
        capturedPlusOne: Int,
        rights: ChessCastlingRights,
        enPassant: Int,
        halfmove: Int,
    ) {
        check(undoDepth < UNDO_CAPACITY) { "undo stack overflow" }
        undoKey[undoDepth] = savedKey
        undoState[undoDepth] = (capturedPlusOne and 0x1F) or
            ((rights.rawValue and 0xF) shl 5) or
            (((enPassant + 1) and 0x7F) shl 9) or
            ((halfmove and 0xFFFF) shl 16)
        undoDepth += 1
    }

    // MARK: - Attacks

    /**
     * Every piece of either colour attacking [square], given [occupancy].
     *
     * Taking occupancy as a parameter rather than reading [occupied] lets
     * the king-safety check remove the king from the board first —
     * otherwise a king "blocks" the very ray checking it, and stepping
     * backwards along that ray looks safe.
     */
    fun attackersTo(square: Int, occupancy: Bitboard): Bitboard {
        var attackers = 0L
        attackers = attackers or
            (ChessTables.pawnAttacks[square] and pieces(ChessColor.BLACK, ChessPieceKind.PAWN))
        attackers = attackers or
            (ChessTables.pawnAttacks[64 + square] and pieces(ChessColor.WHITE, ChessPieceKind.PAWN))
        attackers = attackers or (ChessTables.knightAttacks[square] and pieces(ChessPieceKind.KNIGHT))
        attackers = attackers or (ChessTables.kingAttacks[square] and pieces(ChessPieceKind.KING))
        attackers = attackers or (
            ChessTables.bishopAttacks(square, occupancy) and
                (pieces(ChessPieceKind.BISHOP) or pieces(ChessPieceKind.QUEEN))
            )
        attackers = attackers or (
            ChessTables.rookAttacks(square, occupancy) and
                (pieces(ChessPieceKind.ROOK) or pieces(ChessPieceKind.QUEEN))
            )
        return attackers
    }

    /**
     * Is [square] attacked by any piece of [color]?
     *
     * Short-circuits kind by kind rather than building the full
     * [attackersTo] set, because the overwhelmingly common caller is "am I
     * in check", which usually answers no after the first two tests.
     */
    fun isAttacked(square: Int, color: ChessColor, occupancy: Bitboard = occupied): Boolean {
        // A pawn of `color` attacks `square` exactly when it stands on a
        // square that a pawn of the *opposite* colour on `square` would
        // attack — hence the flipped index.
        if (ChessTables.pawnAttacks[color.opposite.ordinal * 64 + square] and
            pieces(color, ChessPieceKind.PAWN) != 0L
        ) {
            return true
        }
        if (ChessTables.knightAttacks[square] and pieces(color, ChessPieceKind.KNIGHT) != 0L) return true
        if (ChessTables.kingAttacks[square] and pieces(color, ChessPieceKind.KING) != 0L) return true

        val bishopsQueens = pieces(color, ChessPieceKind.BISHOP) or pieces(color, ChessPieceKind.QUEEN)
        if (ChessTables.bishopAttacks(square, occupancy) and bishopsQueens != 0L) return true

        val rooksQueens = pieces(color, ChessPieceKind.ROOK) or pieces(color, ChessPieceKind.QUEEN)
        if (ChessTables.rookAttacks(square, occupancy) and rooksQueens != 0L) return true

        return false
    }

    /**
     * Is [color]'s king in check? False when that side has no king at all
     * (an illegal position the editor may transiently hold).
     */
    fun isInCheck(color: ChessColor): Boolean {
        val king = kingSquare(color)
        if (king < 0) return false
        return isAttacked(king, color.opposite)
    }

    /** Is the side to move in check? */
    val isInCheck: Boolean get() = isInCheck(sideToMove)

    /**
     * True when the position is one the side *not* to move could never
     * have left behind: their king stands in check on our turn.
     *
     * A recognised photo has no move history, so the user picks who is to
     * move — and for most positions only one choice is legal. This is the
     * test behind the analysis screen's per-side "not a legal position
     * with this side to move" message.
     */
    val opponentIsInCheck: Boolean get() = isInCheck(sideToMove.opposite)

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        /** The standard starting array. */
        fun standard(): ChessPosition = fromFen(START_FEN)!!

        /**
         * Deep enough for the search's maximum ply plus quiescence, plus
         * any moves the UI plays on the same object.
         */
        private const val UNDO_CAPACITY = 512

        /**
         * `CASTLING_CLEARED[square]` is removed from the rights whenever a
         * piece leaves *or* lands on `square`.
         *
         * Handling it as a from/to pair rather than as explicit "if the
         * king moved… if a rook moved…" branches covers rook *captures*
         * for free: capturing on h8 clears Black's kingside right without
         * anyone having to remember that case.
         */
        private val CASTLING_CLEARED: Array<ChessCastlingRights> =
            Array(64) { ChessCastlingRights.NONE }.also { table ->
                table[ChessSquare.A1.index] = ChessCastlingRights.WHITE_QUEENSIDE
                table[ChessSquare.H1.index] = ChessCastlingRights.WHITE_KINGSIDE
                table[ChessSquare.E1.index] = ChessCastlingRights.both(ChessColor.WHITE)
                table[ChessSquare.A8.index] = ChessCastlingRights.BLACK_QUEENSIDE
                table[ChessSquare.H8.index] = ChessCastlingRights.BLACK_KINGSIDE
                table[ChessSquare.E8.index] = ChessCastlingRights.both(ChessColor.BLACK)
            }

        /** Rook origin for a castling move, keyed off where the king lands. */
        private fun rookCastleFrom(kingTo: Int): Int = when (kingTo) {
            ChessSquare.G1.index -> ChessSquare.H1.index
            ChessSquare.C1.index -> ChessSquare.A1.index
            ChessSquare.G8.index -> ChessSquare.H8.index
            else -> ChessSquare.A8.index
        }

        /** Rook destination for a castling move. */
        private fun rookCastleTo(kingTo: Int): Int = when (kingTo) {
            ChessSquare.G1.index -> ChessSquare.F1.index
            ChessSquare.C1.index -> ChessSquare.D1.index
            ChessSquare.G8.index -> ChessSquare.F8.index
            else -> ChessSquare.D8.index
        }
    }
}
