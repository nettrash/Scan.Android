package me.nettrash.scan.ui.deeplink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.nettrash.scan.data.payload.ScanPayload
import me.nettrash.scan.data.payload.ScanPayloadParser
import me.nettrash.scan.scanner.ScannedCode
import me.nettrash.scan.ui.components.PayloadActions
import me.nettrash.scan.ui.scanner.ScannerViewModel

/**
 * Result sheet for App-Links arrivals. Mirrors iOS's
 * `DeepLinkResultSheet` — the parser + actions are exactly the same as
 * a freshly-scanned code, with a single Save button instead of the
 * notes flow because deep-link arrivals are typically one-offs.
 *
 * Re-uses [ScannerViewModel.saveScan] for persistence so the row goes
 * through the same Room insert path as a live camera capture. We
 * manually push a synthetic [ScannedCode] into the ViewModel via
 * `onScan(...)` so `saveScan` can find a "last scan" to persist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeepLinkResultSheet(
    payload: DeepLink.Payload,
    onDismiss: () -> Unit,
    scannerViewModel: ScannerViewModel = hiltViewModel(),
) {
    // Synthetic ScannedCode — symbology comes from the URL's `?t=`
    // query when present (post-1.6 mints from the Share Extension /
    // share-intake), defaulting to UNKNOWN for vanilla shareable
    // Universal Links from outside the app. The parser dispatches
    // on payload-prefix patterns first and only falls back to
    // symbology hints for pure-numeric retail codes, so the
    // UNKNOWN fallback is fine for those.
    val code = remember(payload) {
        ScannedCode(
            value = payload.value,
            symbology = payload.symbology,
            timestampMillis = System.currentTimeMillis(),
            previewRect = null,
        )
    }
    val parsed: ScanPayload = remember(code) {
        runCatching { ScanPayloadParser.parse(code.value, code.symbology) }
            .getOrElse { ScanPayload.Text(code.value) }
    }
    var saved by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    parsed.kindLabel,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    color = Color.White,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Universal Link",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Text(code.value, fontFamily = FontFamily.Monospace, maxLines = 6)

            PayloadActions(payload = parsed, raw = code.value)

            Button(
                onClick = {
                    // Direct save via [saveDeepLinkScan] — bypasses
                    // the camera-flow's dedupe / continuous-scan
                    // banner so a deep-link save never competes with
                    // a live capture for state.lastScan.
                    scannerViewModel.saveDeepLinkScan(
                        code,
                        notes = "Opened via Universal Link",
                    )
                    saved = true
                },
                enabled = !saved,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Inbox, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (saved) "Saved to History" else "Save to History")
            }

            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        }
    }
}
