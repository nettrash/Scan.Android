package me.nettrash.scan.ui.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Mirrors `Scan/Scan/WhatsNew.swift` on iOS. Bundled release notes
 * presented as a one-shot ModalBottomSheet on the first launch after
 * an update.
 *
 * Keep [VERSION] in sync with `versionName` in `Scan/build.gradle.kts`
 * — the gate in MainScreen.kt only fires when both the running build
 * and the WhatsNew copy agree on the version.
 */
object WhatsNew {
    const val VERSION = "1.6"
    const val HEADLINE = "What's new in 1.6"

    data class Item(val icon: ImageVector, val title: String, val detail: String)

    val items: List<Item> = listOf(
        Item(
            icon = Icons.Filled.Share,
            title = "Share to Scan",
            detail = "Scan now appears in the Android share sheet for images and PDFs. From any app — Photos, Drive, Gmail — pick Scan and the result sheet pops up directly.",
        ),
        Item(
            icon = Icons.Filled.PictureAsPdf,
            title = "PDF support",
            detail = "Multi-page boarding passes and receipts that arrive as PDFs are now walked page-by-page through PdfRenderer. Both the share sheet and the in-app Files importer route through the same path.",
        ),
        Item(
            icon = Icons.Filled.PhotoLibrary,
            title = "Multi-image batches",
            detail = "Share up to N images or PDFs in one go. Scan aggregates the recognised codes into a single list and lets you act on each one individually.",
        ),
        Item(
            icon = Icons.Filled.Settings,
            title = "Carried over from 1.2 — 1.5",
            detail = "Settings, History favourites + CSV export, custom QR colours / logos / SVG + PDF export, multi-code disambiguation, WPA3 + Passpoint, stablecoin tokens, identity-flow detection, loyalty cards, App Links, Backup status — all here.",
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                WhatsNew.HEADLINE,
                style = MaterialTheme.typography.headlineSmall,
            )
            for (item in WhatsNew.items) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        item.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            item.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        )
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continue") }
        }
    }
}
