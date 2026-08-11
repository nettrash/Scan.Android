package me.nettrash.scan.ui.chess

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.nettrash.scan.chess.ChessAnalysisOutcome
import me.nettrash.scan.chess.ChessCastlingRights
import me.nettrash.scan.chess.ChessColor
import me.nettrash.scan.chess.ChessEngine
import me.nettrash.scan.chess.ChessPiece
import me.nettrash.scan.chess.ChessPosition
import me.nettrash.scan.chess.ChessRecognizedPosition
import me.nettrash.scan.chess.ChessSquare
import me.nettrash.scan.chess.placementFen
import me.nettrash.scan.chess.plausibleCastlingRights

/**
 * State for the Chess tab. Counterpart of the iOS app's
 * `ChessScreenModel.swift`.
 *
 * ## Analysis is debounced and cancellable
 *
 * Every edit invalidates the analysis, and a user correcting three
 * misread squares makes three edits in about a second. Kicking off a
 * search per tap would leave stale searches burning CPU behind the app.
 * So each change cancels the running search, waits a beat, and starts one
 * — and because the engine's `stop()` is safe from any thread and its
 * iterative deepening always leaves a usable answer, cancelling is cheap
 * and never loses a move.
 *
 * ## Both sides, every time
 *
 * `analyseBothSides` runs two searches back to back, so a `STANDARD`
 * think time costs about two seconds of wall clock. Budgeted for
 * deliberately: the feature's promise is a move for each side, and
 * answering only the side we guessed would be the wrong kind of fast.
 */
@HiltViewModel
class ChessViewModel @Inject constructor() : ViewModel() {

    data class UiState(
        val placement: Array<ChessPiece?> = arrayOfNulls(64),
        /**
         * Per-square confidence from the last recognition. Empty after a
         * manual edit, because a square the user set is not a guess.
         */
        val confidence: DoubleArray = DoubleArray(0),
        val repairs: List<String> = emptyList(),
        val paletteSquare: ChessSquare? = null,
        val castlingRights: ChessCastlingRights = ChessCastlingRights.NONE,
        val white: ChessAnalysisOutcome? = null,
        val black: ChessAnalysisOutcome? = null,
        val isAnalysing: Boolean = false,
        val highlightedSide: ChessColor = ChessColor.WHITE,
        val whiteAtBottom: Boolean = true,
        val thinkTime: ChessEngine.ThinkTime = ChessEngine.ThinkTime.STANDARD,
        /** False until the Core ML/LiteRT models are bundled. */
        val recognizerAvailable: Boolean = false,
    ) {
        val placementFen: String
            get() = ChessPosition(placement = placement, castlingRights = castlingRights).placementFen

        fun outcomeFor(side: ChessColor): ChessAnalysisOutcome? =
            if (side == ChessColor.WHITE) white else black

        /** The highlighted side's suggested move, as an arrow. */
        val arrow: Pair<ChessSquare, ChessSquare>?
            get() = (outcomeFor(highlightedSide) as? ChessAnalysisOutcome.Move)
                ?.analysis?.move?.let { it.from to it.to }

        /**
         * Square decorations: the move's endpoints, the open palette, and
         * the squares the recogniser was least sure about.
         */
        val decorations: Map<Int, ChessSquareDecoration>
            get() {
                val result = mutableMapOf<Int, ChessSquareDecoration>()
                fun edit(index: Int, block: (ChessSquareDecoration) -> ChessSquareDecoration) {
                    result[index] = block(result[index] ?: ChessSquareDecoration())
                }

                // Ring only the genuinely shaky squares, and only a few.
                // Ringing twenty is the same as ringing none.
                if (confidence.isNotEmpty()) {
                    (0 until 64)
                        .filter { confidence[it] < 0.80 && placement[it] != null || confidence[it] < 0.65 }
                        .sortedBy { confidence[it] }
                        .take(5)
                        .forEach { edit(it) { d -> d.copy(isUncertain = true) } }
                }
                arrow?.let { (from, to) ->
                    edit(from.index) { it.copy(isMoveEndpoint = true) }
                    edit(to.index) { it.copy(isMoveEndpoint = true) }
                }
                paletteSquare?.let { edit(it.index) { d -> d.copy(isSelected = true) } }
                return result
            }

        // Arrays don't get structural equality for free, and Compose
        // compares state to decide whether to recompose.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UiState) return false
            return placement.contentEquals(other.placement) &&
                confidence.contentEquals(other.confidence) &&
                repairs == other.repairs &&
                paletteSquare == other.paletteSquare &&
                castlingRights == other.castlingRights &&
                white == other.white && black == other.black &&
                isAnalysing == other.isAnalysing &&
                highlightedSide == other.highlightedSide &&
                whiteAtBottom == other.whiteAtBottom &&
                thinkTime == other.thinkTime &&
                recognizerAvailable == other.recognizerAvailable
        }

        override fun hashCode(): Int {
            var result = placement.contentHashCode()
            result = 31 * result + confidence.contentHashCode()
            result = 31 * result + repairs.hashCode()
            result = 31 * result + (paletteSquare?.hashCode() ?: 0)
            result = 31 * result + castlingRights.hashCode()
            result = 31 * result + (white?.hashCode() ?: 0)
            result = 31 * result + (black?.hashCode() ?: 0)
            result = 31 * result + isAnalysing.hashCode()
            result = 31 * result + highlightedSide.hashCode()
            result = 31 * result + whiteAtBottom.hashCode()
            result = 31 * result + thinkTime.hashCode()
            result = 31 * result + recognizerAvailable.hashCode()
            return result
        }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val engine = ChessEngine()
    private var analysisJob: Job? = null

    // MARK: - Editing

    fun tapped(square: ChessSquare) {
        _state.value = _state.value.let {
            it.copy(paletteSquare = if (it.paletteSquare == square) null else square)
        }
    }

    fun setPiece(piece: ChessPiece?, at: ChessSquare) {
        val current = _state.value
        val placement = current.placement.copyOf()
        placement[at.index] = piece
        // A square the user set is not a guess, so it should stop being
        // flagged as uncertain.
        val confidence = current.confidence.copyOf()
        if (confidence.isNotEmpty()) confidence[at.index] = 1.0

        _state.value = current.copy(
            placement = placement,
            confidence = confidence,
            paletteSquare = null,
        )
        scheduleAnalysis()
    }

    fun loadStartingPosition() {
        val standard = ChessPosition.standard()
        _state.value = _state.value.copy(
            placement = standard.placement,
            confidence = DoubleArray(0),
            repairs = emptyList(),
            paletteSquare = null,
            castlingRights = ChessCastlingRights.ALL,
        )
        scheduleAnalysis()
    }

    fun clearBoard() {
        cancelAnalysis()
        _state.value = UiState(
            whiteAtBottom = _state.value.whiteAtBottom,
            thinkTime = _state.value.thinkTime,
            recognizerAvailable = _state.value.recognizerAvailable,
        )
    }

    fun highlight(side: ChessColor) {
        _state.value = _state.value.copy(highlightedSide = side)
    }

    fun setCastlingRights(rights: ChessCastlingRights) {
        _state.value = _state.value.copy(castlingRights = rights)
        scheduleAnalysis()
    }

    fun toggleOrientation() {
        _state.value = _state.value.copy(whiteAtBottom = !_state.value.whiteAtBottom)
    }

    fun setThinkTime(thinkTime: ChessEngine.ThinkTime) {
        _state.value = _state.value.copy(thinkTime = thinkTime)
        scheduleAnalysis()
    }

    /** Adopt a recognised board. */
    fun apply(recognized: ChessRecognizedPosition) {
        _state.value = _state.value.copy(
            placement = recognized.placement,
            confidence = recognized.confidence,
            repairs = recognized.repairs,
            paletteSquare = null,
            // Rights can't be seen, but they can be bounded.
            castlingRights = plausibleCastlingRights(recognized.placement),
        )
        scheduleAnalysis()
    }

    // MARK: - Analysis

    private fun cancelAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        engine.stop()
    }

    private fun scheduleAnalysis() {
        cancelAnalysis()
        val current = _state.value

        val board = ChessPosition(
            placement = current.placement,
            castlingRights = current.castlingRights,
        )
        // Nothing to say about an empty board.
        if (current.placement.all { it == null }) {
            _state.value = current.copy(white = null, black = null, isAnalysing = false)
            return
        }

        _state.value = current.copy(isAnalysing = true)
        val budget = current.thinkTime.millis

        analysisJob = viewModelScope.launch {
            // Settle first: a burst of edits should produce one search,
            // not one per tap.
            delay(250)
            val result = withContext(Dispatchers.Default) {
                engine.analyseBothSides(board, budget)
            }
            val latest = _state.value
            _state.value = latest.copy(
                white = result.white,
                black = result.black,
                isAnalysing = false,
                // Point the arrow at whichever side can actually move, so
                // the board isn't blank on a position legal for only one.
                highlightedSide = when {
                    result.white is ChessAnalysisOutcome.Move -> latest.highlightedSide
                    result.black is ChessAnalysisOutcome.Move -> ChessColor.BLACK
                    else -> latest.highlightedSide
                },
            )
        }
    }

    override fun onCleared() {
        cancelAnalysis()
        super.onCleared()
    }
}
