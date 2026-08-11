package me.nettrash.scan.ui.chess

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.nettrash.scan.chess.ChessAnalysisOutcome
import me.nettrash.scan.chess.ChessCastlingRights
import me.nettrash.scan.chess.ChessColor
import me.nettrash.scan.chess.ChessEngine
import me.nettrash.scan.chess.ChessOutcome
import me.nettrash.scan.chess.ChessPiece
import me.nettrash.scan.chess.ChessPieceKind
import me.nettrash.scan.chess.ChessSquare

/**
 * The Chess tab: read a position, fix it, get a move for each side.
 * Counterpart of the iOS app's `ChessScreen.swift`.
 *
 * ## One screen, not a wizard
 *
 * Capture, edit and analyse sit on one scrolling screen because the
 * middle stage is not optional and users skip optional steps.
 * Recognition will misread a square sometimes — that is arithmetic, not
 * pessimism: at 99.5% per square only 73% of whole boards come back right
 * — so the board is *always* editable, with the shakiest squares ringed,
 * before any move is suggested.
 *
 * ## Side to move is asked, never guessed
 *
 * A photograph cannot show whose turn it is, so the screen answers for
 * both sides. For most positions only one side is even legal to move (if
 * Black stands in check it cannot be White's turn), which the analysis
 * section says plainly rather than inventing a move.
 */
@Composable
fun ChessScreen(viewModel: ChessViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            ChessBoardView(
                placement = state.placement,
                whiteAtBottom = state.whiteAtBottom,
                arrow = state.arrow,
                decorations = state.decorations,
                accentColor = MaterialTheme.colorScheme.primary,
                onTap = viewModel::tapped,
            )
        }

        state.paletteSquare?.let { square ->
            item {
                PiecePalette(
                    square = square,
                    current = state.placement[square.index],
                    onPick = { viewModel.setPiece(it, square) },
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = viewModel::loadStartingPosition,
                        modifier = Modifier.weight(1f),
                    ) { Text("Starting position") }
                    OutlinedButton(
                        onClick = viewModel::clearBoard,
                        modifier = Modifier.weight(1f),
                    ) { Text("Clear") }
                    OutlinedButton(onClick = viewModel::toggleOrientation) { Text("Flip") }
                }

                if (!state.recognizerAvailable) {
                    Text(
                        "Board recognition isn't in this build yet — set the position up by " +
                            "tapping squares.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (state.repairs.isNotEmpty()) {
                    Text(
                        "Adjusted ${state.repairs.size} thing" +
                            (if (state.repairs.size == 1) "" else "s") +
                            " to make this a legal position",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    state.repairs.forEach {
                        Text(
                            "• $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Position", style = MaterialTheme.typography.titleMedium)

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val options = listOf(
                        "No castling" to ChessCastlingRights.NONE,
                        "Both sides" to ChessCastlingRights.ALL,
                    )
                    options.forEachIndexed { index, (label, rights) ->
                        SegmentedButton(
                            selected = state.castlingRights == rights,
                            onClick = { viewModel.setCastlingRights(rights) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) { Text(label) }
                    }
                }

                Text("FEN", style = MaterialTheme.typography.labelLarge)
                Text(
                    state.placementFen,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Best move", style = MaterialTheme.typography.titleMedium)
                    if (state.isAnalysing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }

                listOf(ChessColor.WHITE, ChessColor.BLACK).forEach { side ->
                    SideAnalysisCard(
                        side = side,
                        outcome = state.outcomeFor(side),
                        isHighlighted = state.highlightedSide == side,
                        onSelect = { viewModel.highlight(side) },
                    )
                }

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ChessEngine.ThinkTime.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = state.thinkTime == option,
                            onClick = { viewModel.setThinkTime(option) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index, ChessEngine.ThinkTime.entries.size
                            ),
                        ) { Text(option.displayName) }
                    }
                }
            }
        }
    }
}

/** One side's answer. */
@Composable
private fun SideAnalysisCard(
    side: ChessColor,
    outcome: ChessAnalysisOutcome?,
    isHighlighted: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onSelect,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                side.displayName,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
                color = if (isHighlighted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )

            when (outcome) {
                null -> Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)

                is ChessAnalysisOutcome.Move -> {
                    val analysis = outcome.analysis
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            analysis.san,
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            analysis.displayScore,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (analysis.principalVariationSan.isNotEmpty()) {
                        Text(
                            analysis.principalVariationSan,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    Text(
                        "depth ${analysis.depth} · ${analysis.nodes} positions",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is ChessAnalysisOutcome.Terminal -> Text(
                    describe(outcome.outcome),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // The common, informative case — not an error. Most boards
                // are legal for exactly one side to move.
                is ChessAnalysisOutcome.NotLegal -> outcome.problems.take(3).forEach {
                    Text(
                        it.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private fun describe(outcome: ChessOutcome): String = when (outcome) {
    ChessOutcome.CHECKMATE -> "Checkmated — no move to make."
    ChessOutcome.STALEMATE -> "Stalemate — no legal move, but not in check."
    ChessOutcome.FIFTY_MOVE_RULE -> "Drawn by the fifty-move rule."
    ChessOutcome.INSUFFICIENT_MATERIAL -> "Drawn — neither side can force mate."
    ChessOutcome.ONGOING -> "—"
}

/**
 * The editor's piece picker, shown under the board when a square is
 * tapped. Inline rather than a dialog: correcting three squares should be
 * three taps and a glance, not three modal presentations.
 */
@Composable
private fun PiecePalette(
    square: ChessSquare,
    current: ChessPiece?,
    onPick: (ChessPiece?) -> Unit,
) {
    val options: List<ChessPiece?> = buildList {
        add(null)
        for (color in listOf(ChessColor.WHITE, ChessColor.BLACK)) {
            for (kind in ChessPieceKind.ALL) add(ChessPiece(color, kind))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("What's on ${square.algebraic}?", style = MaterialTheme.typography.labelLarge)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 52.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(options) { piece ->
                FilterChip(
                    selected = piece == current,
                    onClick = { onPick(piece) },
                    label = {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (piece == null) {
                                Text("empty", style = MaterialTheme.typography.labelSmall)
                            } else {
                                Text(
                                    glyphFor(piece.kind),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = if (piece.color == ChessColor.WHITE) Color.White
                                    else Color.Black,
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}
