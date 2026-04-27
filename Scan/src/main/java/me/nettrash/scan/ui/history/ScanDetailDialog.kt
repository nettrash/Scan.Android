package me.nettrash.scan.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.nettrash.scan.data.db.ScanRecord
import me.nettrash.scan.data.payload.ScanPayloadParser
import me.nettrash.scan.scanner.Symbology
import me.nettrash.scan.ui.components.PayloadActions
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Bottom-sheet detail view for a saved [ScanRecord]. Mirrors the iOS
 * ScanDetailView — kind badge, raw value, smart actions, copy/share, an
 * editable note that persists on change, and a destructive delete button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanDetailDialog(
    record: ScanRecord,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onNotesChange: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val payload = remember(record.value, record.symbology) {
        ScanPayloadParser.parse(record.value, Symbology.fromDisplayName(record.symbology))
    }
    var notes by remember { mutableStateOf(record.notes.orEmpty()) }
    LaunchedEffect(notes) {
        if (notes != (record.notes ?: "")) onNotesChange(notes)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    payload.kindLabel,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    color = Color.White
                )
                Spacer(Modifier.width(12.dp))
                Text(record.symbology, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
                        .format(Date(record.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Text(record.value, fontFamily = FontFamily.Monospace, maxLines = 8)

            PayloadActions(payload = payload, raw = record.value)

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 6
            )

            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Delete scan")
            }
        }
    }
}

