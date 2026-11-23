package me.nettrash.scan.ui.settings

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.nettrash.scan.BuildConfig
import me.nettrash.scan.data.payload.ScanAssistant
import me.nettrash.scan.data.payload.ScanTranslator
import me.nettrash.scan.data.payload.TranslationCatalog
import me.nettrash.scan.util.Haptics

/**
 * Settings: Feedback toggles, Insights (on-device assistant, QR safety check,
 * translate language), Sync and About. Compose-only; the heavy lifting lives
 * in [SettingsViewModel] / [me.nettrash.scan.data.settings.SettingsRepository].
 * Section order and wording follow iOS `ScanSettings.swift`.
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionHeader("Feedback")

        ToggleRow(
            icon = Icons.Filled.Vibration,
            title = "Haptic on scan",
            description = "Pulse the haptic engine when a code is recognised.",
            checked = state.hapticOnScan,
            onCheckedChange = viewModel::setHapticOnScan,
        )
        ToggleRow(
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            title = "Sound on scan",
            description = "Play a short beep when a code is recognised.",
            checked = state.soundOnScan,
            onCheckedChange = viewModel::setSoundOnScan,
        )
        ToggleRow(
            icon = Icons.AutoMirrored.Filled.ViewQuilt,
            title = "Continuous scanning",
            description = "Suppress the result sheet — recognised codes save straight to History and a banner shows the latest one.",
            checked = state.continuousScan,
            onCheckedChange = viewModel::setContinuousScan,
        )

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (state.soundOnScan) ScanSound.playScanned()
                if (state.hapticOnScan) Haptics.success(context)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.PlayCircle, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text("Test feedback")
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionHeader("Insights")

        ToggleRow(
            icon = Icons.Filled.AutoAwesome,
            title = "On-device assistant",
            description = "\u201CWhat is this code?\u201D on results and the \u201CDescribe the code\u201D box on Generate.",
            checked = state.assistantEnabled,
            onCheckedChange = viewModel::setAssistantEnabled,
        )
        ToggleRow(
            icon = Icons.Filled.GppGood,
            title = "QR safety check",
            description = "Flags lookalike domains, address-bar tricks and tampered codes before you open them.",
            checked = state.riskChecksEnabled,
            onCheckedChange = viewModel::setRiskChecksEnabled,
        )
        TranslateLanguageRow(
            stored = state.translateLanguage,
            onSelect = viewModel::setTranslateLanguage,
        )
        // iOS's Insights footer: one honest line naming the engine *this*
        // device will use, then what stays on the device and what doesn't.
        Text(
            insightsFooter(state.assistantEnabled),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionHeader("Sync")

        BackupStatusRow()

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionHeader("About")

        AboutRow(label = "Version", value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        AboutRow(label = "Source",  value = "github.com/nettrash/Scan.Android")
        AboutRow(label = "Privacy", value = "nettrash.me/play/scan/privacy.html")
    }
}

/**
 * Surfaces what we know about Android Auto Backup. We can't read the
 * user's *actual* backup-now-enabled state without `BACKUP_AGENT_ID`
 * privileges (which apps don't get), so we report what the app
 * declares (`android:allowBackup="true"` is on by default for Scan)
 * and link the user to the OS-level Backup settings to confirm.
 *
 * Mirrors the iOS Settings → Sync block conceptually: tell the user
 * whether their History *can* sync, plus what they need to do to
 * enable it system-wide.
 */
@Composable
private fun BackupStatusRow() {
    val context = LocalContext.current
    val info = remember {
        // ApplicationInfo.FLAG_ALLOW_BACKUP is set when the manifest
        // declares android:allowBackup="true". On API 23+ the system
        // also factors in the user's per-app backup toggle, but the
        // bit returned here only reflects the manifest declaration —
        // accurate enough for the "the app side is set up correctly"
        // signal we're showing.
        val ai = context.applicationInfo
        (ai.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP) != 0
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (info) "Auto Backup ready" else "Auto Backup disabled",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                if (info)
                    "Your scan history will back up to your Google account when system backup is enabled. Open Android Settings → System → Backup to confirm."
                else
                    "This build of Scan opted out of Auto Backup; history stays local-only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.size(12.dp))
        Button(
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_PRIVACY_SETTINGS)
                    )
                }.recoverCatching {
                    // Older devices route Backup under generic
                    // Settings instead of the privacy hub.
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                    )
                }
            },
        ) { Text("Open") }
    }
}

/**
 * The Insights footer, mirroring iOS `insightsFooter`: the assistant status
 * line, then the privacy sentence — adjusted for ML Kit, whose language
 * models are downloaded from Google (once per language, after Scan asks)
 * where iOS's come with the system, and which also talks to Google on its
 * own account (see the privacy note in `TranslationKit.kt`).
 */
internal fun insightsFooter(assistantEnabled: Boolean): String =
    ScanAssistant.statusLine(assistantEnabled) +
        " Scan's own safety check, explanations and translations run entirely on this device and " +
        "never upload what you scan. A translation language needs a one-time download of its model " +
        "(about ${ScanTranslator.APPROXIMATE_MODEL_MEGABYTES} MB) from Google, which Scan asks you about " +
        "first, and works offline once downloaded. Google's ML Kit, which translates, also registers an " +
        "app-installation ID with Google (Firebase), fetches its translation settings while you are online " +
        "(sending your device language and region, time zone, and Android and app version) and sends " +
        "Google usage and diagnostic statistics, including the language pair — never the text you scanned."

/**
 * "Translate to" — the target for every Translate row. Offers "Device
 * language (…)" plus the catalog languages ML Kit can translate into, and
 * keeps the current choice listed even if this build can't produce it, so
 * the picker never holds a selection it has no row for (iOS
 * `translatePickerLanguages`).
 */
@Composable
private fun TranslateLanguageRow(stored: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val deviceLabel = TranslationCatalog.deviceLanguageLabel()
    val options = remember(stored) {
        val targets = TranslationCatalog.targets.toMutableList()
        val chosen = TranslationCatalog.languages.firstOrNull { it.identifier == stored }
        if (chosen != null && chosen !in targets) targets += chosen
        targets
    }
    val current = options.firstOrNull { it.identifier == stored }?.label ?: deviceLabel

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 4.dp),
        ) {
            Icon(Icons.Filled.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Translate to", style = MaterialTheme.typography.bodyLarge)
                Text(
                    current,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
            }
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(deviceLabel) },
                trailingIcon = {
                    if (options.none { it.identifier == stored }) {
                        Icon(Icons.Filled.Check, contentDescription = null)
                    }
                },
                onClick = {
                    expanded = false
                    onSelect(TranslationCatalog.SYSTEM_TAG)
                },
            )
            options.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.label) },
                    trailingIcon = {
                        if (language.identifier == stored) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(language.identifier)
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
}

/**
 * A titled switch. The whole row is the toggle — one switch node carrying
 * the title and description — so TalkBack names what is being switched
 * instead of a bare "On, switch", and a tap anywhere on the row flips it:
 * iOS's `Toggle(isOn:) { Label(…) }`. The [Switch] itself is display-only.
 */
@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
    }
}

/**
 * Short "scanned" beep. We use [ToneGenerator] with the system
 * notification stream so it respects the user's ringer / DnD state
 * — playing a raw asset would bypass that. Volume `25` (out of 100)
 * is roughly equivalent to the iOS "Tink" system sound's loudness.
 */
internal object ScanSound {
    fun playScanned() {
        runCatching {
            // Each call gets a fresh generator so we can release()
            // immediately and keep tone playback fire-and-forget.
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 25)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            // Releasing instantly cuts the tone off — instead, post
            // a delayed release on the main looper. We don't need a
            // handle to the runnable; if the activity dies first
            // Android cleans up the native resource for us.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { runCatching { tone.release() } },
                200
            )
        }
    }
}
