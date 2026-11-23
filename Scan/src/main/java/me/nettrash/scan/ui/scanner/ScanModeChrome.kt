package me.nettrash.scan.ui.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import kotlinx.coroutines.delay
import me.nettrash.scan.data.payload.TranslateLanguage
import me.nettrash.scan.data.settings.ScanModePreference
import me.nettrash.scan.ui.components.TranslatePanel
import me.nettrash.scan.ui.components.TranslatePanelDefaults

/**
 * The chrome that sits on top of the live preview: the mode switch, the
 * bulk-capture HUD, and the Live Text result card. The counterpart of the
 * iOS `modeChrome` view in `ScannerScreen.swift` — same controls, same
 * labels. The footnotes live on [ScanModePreference]: Codes and Bulk word
 * for word as iOS, Live Text saying what this port does (a list of text
 * blocks to tap, where iOS has VisionKit's highlights and a frozen frame).
 */

private val capsuleShape = RoundedCornerShape(18.dp)
private val capsuleColor = Color(0x80000000)

/**
 * The footnote's own backing — the iOS `.ultraThinMaterial` rounded rect —
 * so the hint stays legible over a white page or a bright label, where bare
 * white text would vanish. White at 90 % on 70 % black keeps well above
 * WCAG's 4.5:1 even over a pure white scene.
 */
private val footnoteBackground = Color(0xB3000000)
private val footnoteTextColor = Color.White.copy(alpha = 0.9f)
private val footnoteShape = RoundedCornerShape(10.dp)

/**
 * The top row of the camera chrome: the mode picker, and — in Bulk, once a
 * code is in — the bulk HUD ([hud]) at the trailing edge.
 *
 * The HUD is the row's only unweighted child, so it is measured first at
 * its full width and the picker column takes what is left, its footnote
 * wrapping inside it. That is iOS's `HStack { VStack { picker; footnote
 * .fixedSize(horizontal: false, vertical: true) }; Spacer(minLength: 8);
 * bulkHUD }`. Laid out the other way round (picker unweighted, a weighted
 * spacer, then the HUD), a one-line footnote claimed the whole width on a
 * 360 dp phone or at a larger font size and squeezed the HUD — the only way
 * into the bulk list — down to nothing.
 */
@Composable
internal fun ScanModeTopRow(
    mode: ScanModePreference,
    availableModes: List<ScanModePreference>,
    onSelect: (ScanModePreference) -> Unit,
    modifier: Modifier = Modifier,
    hud: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        ScanModeSelector(
            current = mode,
            availableModes = availableModes,
            onSelect = onSelect,
            modifier = Modifier.weight(1f).padding(start = 16.dp),
        )
        if (hud != null) {
            // 8 dp leading gap: iOS's `Spacer(minLength: 8)`.
            Box(modifier = Modifier.padding(start = 8.dp, end = 24.dp)) { hud() }
        }
    }
}

/**
 * Menu-style mode picker in a translucent capsule, mirroring iOS's
 * `.pickerStyle(.menu)` + `.background(.ultraThinMaterial, in: Capsule())`.
 * Only the modes the device can actually run are offered — Live Text
 * disappears when the text recogniser is unavailable, the way iOS filters
 * on `ScanMode.isAvailable`.
 *
 * TalkBack reads the capsule as "Scan mode" with the current mode as its
 * state ("Scan mode, Codes") — the iOS picker's label and value. The
 * capsule replaces its children's semantics: left merged, the visible
 * "Codes" would be read as well, after the label and again as the state.
 */
@Composable
fun ScanModeSelector(
    current: ScanModePreference,
    availableModes: List<ScanModePreference>,
    onSelect: (ScanModePreference) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(capsuleColor, capsuleShape)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clearAndSetSemantics {
                    contentDescription = "Scan mode"
                    stateDescription = current.label
                },
        ) {
            Text(
                current.label,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        // Footnote under the switch — the per-mode hint the iOS enum
        // carries ("Keep scanning: every distinct code is collected and
        // counted.") — on its own dark backing with the iOS metrics
        // (10 × 5 padding, 10-radius corners), wrapping within the width
        // the top row leaves it.
        Text(
            current.footnote,
            color = footnoteTextColor,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .padding(top = 4.dp)
                .background(footnoteBackground, footnoteShape)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )

        Box {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                availableModes.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m.label) },
                        trailingIcon = {
                            if (m == current) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(m)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The bulk-capture counter that keeps the camera running while it counts.
 * Tap opens the collected list. Shows the "Limit reached" flag rather than
 * silently truncating once `BulkScanSession`
 * hits its capacity, and flags a multi-code frame — the tamper signal the
 * risk assessor reported.
 */
@Composable
fun BulkCaptureHud(
    count: Int,
    isFull: Boolean,
    tamperWarning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .background(capsuleColor, capsuleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = "$count codes captured, open list" },
    ) {
        Icon(
            Icons.Filled.Layers,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
        Text(
            count.toString(),
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
        )
        if (isFull) {
            Text(
                "Limit reached",
                color = Color(0xFFFFB74D),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (tamperWarning) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = "More than one code in this frame",
                tint = Color(0xFFFFB74D),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * The text the user tapped in Live Text mode, with the things worth doing
 * with it. Everything here is local: copy, share, and the on-device
 * translation. Mirrors iOS `RecognizedTextCard`, which embeds the same
 * `TranslateRow` as the result sheets — here the shared [TranslatePanel].
 *
 * The translate target starts at the Settings → Insights language
 * ([initialTarget], resolved from `SettingsState.translateLanguage` the way
 * iOS reads `@AppStorage(translateLanguage)`); the card's own Language menu
 * changes it for this text only and never writes the setting back.
 */
@Composable
fun RecognizedTextCard(
    text: String,
    onDismiss: () -> Unit,
    initialTarget: TranslateLanguage,
    modifier: Modifier = Modifier,
    onCopy: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
) {
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }
    // A new block — or a new Settings choice — starts from the Settings
    // language again; a pick in the card's menu lasts for this text.
    var target by remember(text, initialTarget) { mutableStateOf(initialTarget) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xE61C1C1E), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Recognised text",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Dismiss recognised text",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        // Selectable text, the way iOS turns on `.textSelection(.enabled)`.
        // A long paragraph scrolls inside the card instead of pushing it up
        // over the viewfinder (iOS caps it at 160 points the same way).
        SelectionContainer(
            modifier = Modifier
                .heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clickable {
                        copied = true
                        onCopy(text)
                    }
                    .semantics { contentDescription = if (copied) "Copied" else "Copy" },
            ) {
                Icon(
                    if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    if (copied) "Copied" else "Copy",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clickable { onShare(text) }
                    .semantics { contentDescription = "Share" },
            ) {
                Icon(
                    Icons.Filled.IosShare,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
                Text("Share", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }

        TranslatePanel(
            text = text,
            target = target,
            colors = TranslatePanelDefaults.onDark,
            onChangeTarget = { target = it },
        )
    }
}
