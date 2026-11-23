package me.nettrash.scan.ui.scanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.nettrash.scan.data.HistoryCsv
import me.nettrash.scan.scanner.BulkScanSession

/**
 * What Bulk mode collected: every distinct code, how many times the camera
 * saw it, and the two things an inventory flow needs next — save the lot to
 * History, or hand a CSV to a spreadsheet. Port of iOS `BulkCaptureSheet`.
 *
 * iOS uses a `List` with `.onDelete` swipe and a `ShareLink`; the Android
 * equivalents are a per-row delete button and an `ACTION_SEND` chooser over
 * the [HistoryCsv.writeBulk] file. The strings are the iOS ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkCaptureSheet(
    session: BulkScanSession,
    onSaveAll: (onSaved: (Int) -> Unit, onFailed: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var savedCount by remember { mutableStateOf<Int?>(null) }
    var exportUri by remember { mutableStateOf<Uri?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

    val entries = session.entriesSnapshot()

    // The camera keeps collecting under the sheet; an exported file that no
    // longer matches the list — a new code, another sighting, a removal —
    // is re-made on the next export (iOS resets `exportURL` on
    // `bulk.csvRevision` the same way). Saving to History doesn't count:
    // the CSV doesn't show it.
    LaunchedEffect(session.csvRevision) { exportUri = null }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bulk capture", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (session.isFull) {
                    Text(
                        "Limit reached",
                        color = Color(0xFFFFB74D),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        enabled = !session.isEmpty,
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Bulk capture options")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        // Saves only what isn't in History yet; with every
                        // entry saved there is nothing left to save.
                        DropdownMenuItem(
                            text = { Text("Save all to History") },
                            enabled = session.hasUnsavedEntries,
                            onClick = {
                                menuExpanded = false
                                onSaveAll(
                                    { added -> savedCount = added },
                                    { reason -> exportError = "Couldn't save to History: $reason" },
                                )
                            },
                        )
                        if (exportUri != null) {
                            DropdownMenuItem(
                                text = { Text("Share CSV") },
                                onClick = {
                                    menuExpanded = false
                                    exportUri?.let { shareCsv(context, it) }
                                },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Export CSV") },
                                enabled = !session.isEmpty,
                                onClick = {
                                    menuExpanded = false
                                    runCatching {
                                        HistoryCsv.writeBulk(context, session.csvLines())
                                    }.onSuccess { uri ->
                                        exportUri = uri
                                        shareCsv(context, uri)
                                    }.onFailure { exportError = "Export failed: ${it.message ?: "unknown error"}" }
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Clear") },
                            enabled = !session.isEmpty,
                            onClick = {
                                menuExpanded = false
                                session.reset()
                                savedCount = null
                                exportUri = null
                            },
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Done")
                }
            }

            if (entries.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                ) {
                    Text("Nothing captured yet", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Keep the camera moving over the codes — each distinct one lands here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                ) {
                    Text(
                        "${session.count} codes",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${session.count}/${BulkScanSession.CAPACITY}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // `weight(fill = false)` rather than fillMaxSize: the list
                // takes what it needs and leaves room for the "saved" line
                // and the export error under it.
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(entries, key = { it.id }) { entry ->
                        BulkEntryRow(
                            entry = entry,
                            onCopy = { copyToClipboard(context, entry.value) },
                            onDelete = { session.remove(entry) },
                        )
                    }
                }
            }

            if (savedCount != null) {
                Text(
                    "${savedCount} saved to History",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
            exportError?.let { message ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .background(Color(0x33FF5252), RoundedCornerShape(8.dp))
                        .clickable { exportError = null }
                        .padding(12.dp),
                ) {
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

/** One collected code: value, what it is, and how many frames saw it. */
@Composable
private fun BulkEntryRow(
    entry: BulkScanSession.Entry,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.value,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                "${entry.symbology} · ${entry.kindLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "×${entry.sightings}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .align(Alignment.CenterVertically),
        )
        IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove captured code")
        }
    }
}

// ---- Share / clipboard helpers ------------------------------------------
//
// Small enough to live beside the sheet rather than in their own file; the
// scanner screen reuses them for the Live Text card.

internal fun shareCsv(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Share CSV")) }
}

internal fun sharePlainText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Share")) }
}

internal fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("Scan", text)) }
}
