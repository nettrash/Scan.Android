package me.nettrash.scan.ui.history

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.nettrash.scan.data.db.ScanRecord
import me.nettrash.scan.data.payload.ScanPayload
import me.nettrash.scan.data.payload.ScanPayloadParser
import me.nettrash.scan.scanner.Symbology
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val records by viewModel.records.collectAsState()
    var search by remember { mutableStateOf("") }
    var favouritesOnly by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ScanRecord?>(null) }
    val context = LocalContext.current

    val term = search.trim().lowercase(Locale.ROOT)
    val filtered = records
        .let { if (favouritesOnly) it.filter(ScanRecord::isFavorite) else it }
        .let {
            if (term.isEmpty()) it else it.filter { r ->
                r.value.lowercase(Locale.ROOT).contains(term) ||
                    r.symbology.lowercase(Locale.ROOT).contains(term) ||
                    (r.notes ?: "").lowercase(Locale.ROOT).contains(term)
            }
        }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Search history") },
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true
        )
        Spacer(Modifier.size(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = favouritesOnly,
                onClick = { favouritesOnly = !favouritesOnly },
                label = { Text("Favourites only") },
                leadingIcon = {
                    Icon(
                        if (favouritesOnly) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = null,
                    )
                },
            )
            Spacer(Modifier.weight(1f))
            // Export button only meaningful when there's something
            // to export. Greying it out is friendlier than letting
            // the share sheet open over an empty CSV.
            TextButton(
                onClick = {
                    val rows = if (term.isEmpty() && !favouritesOnly) records else filtered
                    val uri = viewModel.exportCsvUri(rows)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        Intent.createChooser(send, "Export ${rows.size} scans").apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    )
                },
                enabled = records.isNotEmpty(),
            ) {
                Icon(Icons.Filled.IosShare, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("Export CSV")
            }
        }

        Spacer(Modifier.size(4.dp))

        if (records.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(filtered, key = { it.id }) { record ->
                    HistoryRow(
                        record = record,
                        onClick = { selected = record },
                        onToggleFavourite = { viewModel.toggleFavourite(record) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    selected?.let { rec ->
        ScanDetailDialog(
            record = rec,
            onDismiss = { selected = null },
            onDelete = { viewModel.delete(rec); selected = null },
            onNotesChange = { newNotes ->
                viewModel.update(rec.copy(notes = newNotes.ifEmpty { null }))
            }
        )
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(48.dp))
            Spacer(Modifier.size(8.dp))
            Text("No scans yet", style = MaterialTheme.typography.titleMedium)
            Text("Saved scans will appear here.", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun HistoryRow(
    record: ScanRecord,
    onClick: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    val payload = remember(record.value, record.symbology) {
        ScanPayloadParser.parse(record.value, Symbology.fromDisplayName(record.symbology))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Icon(
            iconForPayload(payload),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape)
                .padding(6.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(record.value, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                Text(
                    record.symbology,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
                        .format(Date(record.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
        }
        IconButton(onClick = onToggleFavourite) {
            Icon(
                if (record.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (record.isFavorite) "Unstar" else "Star",
                tint = if (record.isFavorite)
                    MaterialTheme.colorScheme.tertiary
                else
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
            )
        }
    }
}

internal fun iconForPayload(payload: ScanPayload): ImageVector = when (payload) {
    is ScanPayload.Url -> Icons.Filled.OpenInBrowser
    is ScanPayload.Email -> Icons.Filled.Email
    is ScanPayload.Phone -> Icons.Filled.Call
    is ScanPayload.Sms -> Icons.AutoMirrored.Filled.Message
    is ScanPayload.Wifi -> Icons.Filled.Wifi
    is ScanPayload.Geo -> Icons.Filled.Map
    is ScanPayload.Contact -> Icons.Filled.Person
    is ScanPayload.Calendar -> Icons.Filled.CalendarToday
    is ScanPayload.Otp -> Icons.Filled.Key
    is ScanPayload.ProductCode -> Icons.Filled.Numbers
    is ScanPayload.Crypto -> Icons.Filled.CreditCard
    is ScanPayload.EpcPayment -> Icons.Filled.CreditCard
    is ScanPayload.SwissQRBill -> Icons.Filled.CreditCard
    is ScanPayload.RuPayment -> Icons.Filled.CreditCard
    is ScanPayload.FnsReceipt -> Icons.Filled.Receipt
    is ScanPayload.EmvPayment -> Icons.Filled.CreditCard
    is ScanPayload.SufReceipt -> Icons.Filled.Receipt
    is ScanPayload.IpsPayment -> Icons.Filled.CreditCard
    is ScanPayload.UpiPayment -> Icons.Filled.CreditCard
    is ScanPayload.CzechSPD -> Icons.Filled.Receipt
    is ScanPayload.PaBySquare -> Icons.Filled.GridView
    is ScanPayload.Regional -> Icons.Filled.OpenInBrowser
    is ScanPayload.Magnet -> Icons.Filled.OpenInBrowser
    is ScanPayload.RichUrl ->
        if (payload.payload.kind == me.nettrash.scan.data.payload.RichURLPayload.Kind.DIGITAL_IDENTITY)
            Icons.Filled.Person
        else
            Icons.Filled.OpenInBrowser
    is ScanPayload.GS1 -> Icons.Filled.Numbers
    is ScanPayload.BoardingPass -> Icons.Filled.CalendarToday
    is ScanPayload.DrivingLicense -> Icons.Filled.Person
    is ScanPayload.Text -> Icons.Filled.QrCode
}
