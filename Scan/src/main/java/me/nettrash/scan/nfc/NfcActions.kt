package me.nettrash.scan.nfc

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.nettrash.scan.data.payload.ScanPayload

/**
 * Ready-to-place UI for the NFC session, mirroring the two gated controls in
 * the iOS app:
 *
 *  - `GeneratorScreen.swift`'s `nfcSection` — "Write to NFC tag" for whatever
 *    the form currently encodes ([NfcWriteAction]);
 *  - `ScannerScreen.swift`'s read button next to the import menu — tap a tag
 *    and get a normal decoded code ([NfcReadAction] + [NfcReadStatusBanner]).
 *
 * Both render nothing at all on a device without NFC hardware, exactly like
 * the iOS `if NFCTagWriter.isSupported` wrappers.
 */

/**
 * Own the writer's lifetime alongside the composable that shows the control.
 *
 * Reader mode must never outlive the screen that asked for it: leaving
 * composition disposes the writer, and `ON_PAUSE` — the activity going to the
 * background, or the Scan/Generate destination being navigated away from —
 * cancels a session that is still waiting for a tag. That is the "only in the
 * foreground, only while the sheet is up" rule iOS gets from its system NFC
 * sheet.
 */
@Composable
fun rememberNfcTagWriter(): NfcTagWriter {
    val writer = remember { NfcTagWriter() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, writer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) writer.cancel()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(writer) {
        onDispose { writer.dispose() }
    }
    return writer
}

/**
 * The Generate tab's "NFC" section: writes the same string the code renders,
 * so tag and QR stay interchangeable. The record kind is inferred from the
 * content (iOS `NFCTagPayloadKind.inferred(from:)`), so a Wi-Fi form writes a
 * Wi-Fi tag, a contact form a contact card and a phone, email, SMS or map
 * link a URI record.
 */
@Composable
fun NfcWriteAction(
    value: String,
    modifier: Modifier = Modifier,
    writer: NfcTagWriter = rememberNfcTagWriter(),
) {
    val context = LocalContext.current
    val hasNfc = remember(context) { NfcTagWriter.isSupported(context) }
    if (!hasNfc) return
    val status by writer.status.collectAsStateWithLifecycle()
    val busy = status is NfcTagWriter.Status.Scanning || status is NfcTagWriter.Status.Writing
    val activity = remember(context) { context.findActivity() }
    // What a tap writes, so the button is off whenever the write would be
    // refused — blank content included, trimmed by the same rule as on iOS.
    val request = NfcTagWriteRequest(kind = NfcTagPayloadKind.inferred(value), value = value)
    // An outcome belongs to the content it was for: editing the form clears
    // "Tag written" (or a failure) and stops a session still waiting for a
    // tag, which would otherwise write the old content.
    LaunchedEffect(value) { writer.contentChanged() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("NFC", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    val target = activity ?: return@Button
                    writer.write(target, request)
                },
                enabled = activity != null && request.isValid && !busy,
            ) {
                Icon(Icons.Filled.Nfc, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(text = "Write to NFC tag", modifier = Modifier.padding(start = 8.dp))
            }
            if (busy) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { writer.cancel() }) { Text("Cancel") }
            }
        }
        NfcStatusLabel(status, scanningText = NfcTagWriter.HOLD_TO_WRITE_PROMPT)
        NfcTurnOnButton(status)
        // iOS's footer, plus the one thing only Android does: a blank tag that
        // was never NDEF-formatted is formatted on the way (Core NFC can't).
        Text(
            text = "Writes the same content to a writable NFC tag, formatting a blank one first — " +
                "a web, phone, email, SMS or map link, a Wi-Fi network or a contact card in its own " +
                "NFC format, anything else (WPA3, WEP and hidden Wi-Fi included) as text — so " +
                "reading the tag in Scan gives the same result as scanning the code.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The Scanner tab's "Read an NFC tag" button. [onResult] receives the decoded
 * payload and the raw string on the main thread — the same pair a camera scan
 * produces, so the caller can present it through the normal result sheet.
 * Pair it with [NfcReadStatusBanner] for the "hold near a tag" prompt Android
 * has no system sheet for.
 */
@Composable
fun NfcReadAction(
    onResult: (ScanPayload, String) -> Unit,
    writer: NfcTagWriter,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    val context = LocalContext.current
    val hasNfc = remember(context) { NfcTagWriter.isSupported(context) }
    if (!hasNfc) return
    val activity = remember(context) { context.findActivity() } ?: return
    val status by writer.status.collectAsStateWithLifecycle()
    val scanning = status is NfcTagWriter.Status.Scanning
    IconButton(
        onClick = { if (scanning) writer.cancel() else writer.read(activity, onResult) },
        modifier = modifier.background(
            if (scanning) MaterialTheme.colorScheme.primary else Color(0x80000000),
            CircleShape,
        ),
    ) {
        Icon(
            imageVector = Icons.Filled.Nfc,
            contentDescription = if (scanning) "Stop reading the NFC tag" else "Read an NFC tag",
            tint = tint,
        )
    }
}

/**
 * The scanner's NFC prompt: "Hold near an NFC tag" with a Cancel while the
 * reader waits, and a failure that clears itself after a few seconds. A
 * successful read needs no banner — the result sheet opens.
 */
@Composable
fun NfcReadStatusBanner(writer: NfcTagWriter, modifier: Modifier = Modifier) {
    val status by writer.status.collectAsStateWithLifecycle()
    LaunchedEffect(status) {
        if (status is NfcTagWriter.Status.Failed) {
            delay(FAILURE_NOTE_MILLIS)
            writer.cancel()
        }
    }
    val message = when (val current = status) {
        NfcTagWriter.Status.Scanning -> NfcTagWriter.HOLD_TO_READ_PROMPT
        NfcTagWriter.Status.Writing -> "Reading…"
        is NfcTagWriter.Status.Failed -> current.message
        NfcTagWriter.Status.Idle, is NfcTagWriter.Status.Wrote -> null
    } ?: return
    val failed = status is NfcTagWriter.Status.Failed
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC1C1C1E), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (failed) Icons.Filled.Warning else Icons.Filled.Nfc,
            contentDescription = null,
            tint = if (failed) Color(0xFFFFB74D) else Color.White,
            modifier = Modifier.size(18.dp),
        )
        Text(
            message,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        if (message == NfcSupport.NFC_OFF_MESSAGE) {
            NfcTurnOnButton(status, color = Color.White)
        } else if (!failed) {
            TextButton(onClick = { writer.cancel() }) { Text("Cancel", color = Color.White) }
        }
    }
}

/** The one-line progress / result text under an NFC control. */
@Composable
fun NfcStatusLabel(
    status: NfcTagWriter.Status,
    modifier: Modifier = Modifier,
    scanningText: String = NfcTagWriter.HOLD_TO_WRITE_PROMPT,
) {
    when (status) {
        NfcTagWriter.Status.Idle -> Unit
        NfcTagWriter.Status.Scanning -> NfcLine(
            scanningText,
            Icons.Filled.HourglassEmpty,
            MaterialTheme.colorScheme.onSurfaceVariant,
            modifier,
        )
        NfcTagWriter.Status.Writing -> NfcLine(
            "Writing…",
            Icons.Filled.HourglassEmpty,
            MaterialTheme.colorScheme.onSurfaceVariant,
            modifier,
        )
        is NfcTagWriter.Status.Wrote -> NfcLine(
            status.message,
            Icons.Filled.CheckCircle,
            Color(0xFF2E7D32),
            modifier,
        )
        is NfcTagWriter.Status.Failed -> NfcLine(
            status.message,
            Icons.Filled.Warning,
            Color(0xFFF57C00),
            modifier,
        )
    }
}

/** "Turn on NFC" — shown only for the NFC-is-off failure, which only Android has. */
@Composable
private fun NfcTurnOnButton(status: NfcTagWriter.Status, color: Color = Color.Unspecified) {
    val failure = status as? NfcTagWriter.Status.Failed ?: return
    if (failure.message != NfcSupport.NFC_OFF_MESSAGE) return
    val context = LocalContext.current
    TextButton(onClick = {
        runCatching { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
            .recoverCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
    }) {
        Text("Turn on NFC", color = color)
    }
}

@Composable
private fun NfcLine(
    text: String,
    imageVector: ImageVector,
    color: Color,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(imageVector, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** How long a failed read stays on the scanner before the banner clears itself. */
private const val FAILURE_NOTE_MILLIS = 4_000L

/**
 * The activity behind a Compose [Context]. `LocalContext` is the activity in
 * a plain screen but a `ContextThemeWrapper` inside dialogs and sheets, and
 * reader mode needs the activity itself.
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
