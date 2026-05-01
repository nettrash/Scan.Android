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
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ZoomIn
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
    const val VERSION = "1.7"
    const val HEADLINE = "What's new in 1.7"

    data class Item(val icon: ImageVector, val title: String, val detail: String)

    val items: List<Item> = listOf(
        Item(
            icon = Icons.Filled.ZoomIn,
            title = "Pinch to zoom",
            detail = "Pinch the camera preview to zoom in on far-away or small codes. Posters, warehouse labels, fridge magnets — anything you couldn't quite frame before now snaps into focus.",
        ),
        Item(
            icon = Icons.Filled.CenterFocusStrong,
            title = "Centred-frame scanning",
            detail = "The recogniser now only acts on codes inside the centred reticle area, so a stray code at the edge of the frame won't compete with the one you're aiming at. Aim the reticle at what you want; everything else is ignored.",
        ),
        Item(
            icon = Icons.Filled.Settings,
            title = "Carried over from 1.2 — 1.6",
            detail = "Settings tab, History favourites + CSV export, QR colours / logos / SVG + PDF export, multi-code disambiguation, WPA3 + Passpoint, stablecoin tokens, identity-flow detection, loyalty cards, App Links, Backup status, Share to Scan + PDF — all here.",
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
