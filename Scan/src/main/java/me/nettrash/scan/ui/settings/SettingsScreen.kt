package me.nettrash.scan.ui.settings

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.nettrash.scan.BuildConfig
import me.nettrash.scan.util.Haptics

/**
 * Three-toggle Settings screen + an About block. Compose-only; the
 * heavy lifting lives in [SettingsViewModel] / [me.nettrash.scan.data.settings.SettingsRepository].
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

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
}

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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
