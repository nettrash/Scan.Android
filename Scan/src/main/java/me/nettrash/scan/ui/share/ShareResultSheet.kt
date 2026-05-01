package me.nettrash.scan.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.nettrash.scan.data.payload.ScanPayload
import me.nettrash.scan.data.payload.ScanPayloadParser
import me.nettrash.scan.scanner.ScannedCode
import me.nettrash.scan.ui.components.PayloadActions
import me.nettrash.scan.ui.history.iconForPayload
import me.nettrash.scan.ui.scanner.ScannerViewModel

/**
 * Result sheet for the `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intake
 * path. Presents the dispatcher's [ShareIntakeDispatcher.State] —
 * loading spinner, single-result detail, or N-result list. Picking a
 * row drills into a per-code detail with the same `PayloadActions`
 * surface used by camera scans, so the user can act on the payload
 * (open URL, add to contacts, save Wi-Fi, …) without leaving the
 * sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareResultSheet(
    state: ShareIntakeDispatcher.State,
    onDismiss: () -> Unit,
    scannerViewModel: ScannerViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                is ShareIntakeDispatcher.State.Loading -> LoadingBlock()
                is ShareIntakeDispatcher.State.Failed -> FailedBlock(state.message)
                is ShareIntakeDispatcher.State.Ready -> ReadyBlock(
                    codes = state.codes,
                    scannerViewModel = scannerViewModel,
                )
                ShareIntakeDispatcher.State.Idle -> {
                    // The dispatcher never holds Idle while the sheet
                    // is up — onDismiss already nil'd it. Defensive.
                }
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Done") }
        }
    }
}

@Composable
private fun LoadingBlock() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
    ) {
        CircularProgressIndicator()
        Text(
            "Reading shared file…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun FailedBlock(message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
    ) {
        Icon(
            Icons.Filled.QrCode,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        )
        Text(
            "No barcodes found",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun ReadyBlock(
    codes: List<ScannedCode>,
    scannerViewModel: ScannerViewModel,
) {
    // Auto-select the single result on first composition so the user
    // lands directly on the detail view without an extra tap.
    // Re-keying the `remember` on `codes` resets the selection when
    // a fresh share-intake arrives (otherwise selecting one code,
    // dismissing, and sharing again would carry the stale selection
    // forward into the new sheet).
    //
    // Earlier this branch did `selected = codes.first()` *inside*
    // the composable body when `codes.size == 1` — a statement, not a
    // composable, which caused the result to render as an empty
    // sheet. Seeding the initial state at remember-time fixes it.
    var selected by remember(codes) {
        mutableStateOf<ScannedCode?>(codes.singleOrNull())
    }

    if (selected != null) {
        // Detail mode — show the parser's labelled-fields + actions
        // for the picked code. A back-to-list button restores the
        // overview when there's more than one code.
        val code = selected!!
        val payload = remember(code) {
            runCatching { ScanPayloadParser.parse(code.value, code.symbology) }
                .getOrElse { ScanPayload.Text(code.value) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    payload.kindLabel,
                    color = Color.White,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    code.symbology.displayName,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Shared file",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Text(
                code.value,
                fontFamily = FontFamily.Monospace,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            PayloadActions(
                payload = payload,
                raw = code.value,
                // Loyalty-card save callback works for share-intake
                // results just as well as live scans.
                onSaveAsLoyaltyCard = { merchant ->
                    scannerViewModel.saveDeepLinkScan(
                        code,
                        notes = if (merchant.isBlank()) "Loyalty"
                                else "Loyalty: $merchant",
                    )
                },
            )
            if (codes.size > 1) {
                Button(
                    onClick = { selected = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("← Back to ${codes.size} results") }
            }
        }
    } else {
        // List mode — N codes, show a row for each. (We never reach
        // this branch with `codes.size == 1` because of the
        // `codes.singleOrNull()` initial-state seed above.)
        Text(
            "${codes.size} codes found",
            style = MaterialTheme.typography.titleMedium,
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.height(360.dp),
        ) {
            items(codes, key = { it.value }) { code ->
                ShareResultRow(
                    code = code,
                    onClick = { selected = code },
                )
            }
        }
    }
}

@Composable
private fun ShareResultRow(code: ScannedCode, onClick: () -> Unit) {
    val payload = remember(code.value, code.symbology) {
        runCatching { ScanPayloadParser.parse(code.value, code.symbology) }
            .getOrElse { ScanPayload.Text(code.value) }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Icon(
            iconForPayload(payload),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                .padding(6.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                code.value,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${payload.kindLabel} · ${code.symbology.displayName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}
