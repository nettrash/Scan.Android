package me.nettrash.scan.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.nettrash.scan.data.payload.LabelledField
import me.nettrash.scan.data.payload.ScanPayload

/**
 * Smart-action buttons + labelled fields for a parsed [ScanPayload]. Mirrors
 * the iOS PayloadActionsView — each subtype gets a tailored set of intents
 * (call, mail, browse, open in maps, add to contacts, add to calendar, etc.)
 * plus the common Copy / Share affordances.
 */
@Composable
fun PayloadActions(payload: ScanPayload, raw: String) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmartActions(payload, context)

        HorizontalDivider()

        OutlinedButton(onClick = { copyToClipboard(context, raw) }) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Copy")
        }
        OutlinedButton(onClick = { shareText(context, raw) }) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Share")
        }
    }
}

@Composable
private fun SmartActions(payload: ScanPayload, context: Context) {
    when (payload) {
        is ScanPayload.Url -> {
            ActionButton(Icons.Filled.OpenInBrowser, "Open in browser") {
                openUri(context, payload.url)
            }
            Text(payload.url, fontFamily = FontFamily.Monospace, overflow = TextOverflow.Ellipsis, maxLines = 2)
        }

        is ScanPayload.Email -> {
            ActionButton(Icons.Filled.Email, "Compose email to ${payload.address}") {
                val q = mutableListOf<String>()
                payload.subject?.let { q += "subject=" + Uri.encode(it) }
                payload.body?.let { q += "body=" + Uri.encode(it) }
                val tail = if (q.isEmpty()) "" else "?" + q.joinToString("&")
                val uri = Uri.parse("mailto:${payload.address}$tail")
                context.startActivity(Intent(Intent.ACTION_SENDTO, uri))
            }
        }

        is ScanPayload.Phone -> {
            ActionButton(Icons.Filled.Call, "Call ${payload.number}") {
                openUri(context, "tel:${payload.number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }}")
            }
        }

        is ScanPayload.Sms -> {
            ActionButton(Icons.AutoMirrored.Filled.Message, "Send SMS to ${payload.number}") {
                val uri = StringBuilder("sms:${payload.number}")
                payload.body?.let { uri.append("?body=").append(Uri.encode(it)) }
                openUri(context, uri.toString())
            }
        }

        is ScanPayload.Wifi -> {
            Text("Wi-Fi network: ${payload.ssid}", fontWeight = FontWeight.Bold)
            payload.security?.takeIf { it.isNotEmpty() }?.let { Text("Security: $it") }
            if (payload.hidden) Text("Hidden network")
            payload.password?.takeIf { it.isNotEmpty() }?.let { pw ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Password: $pw", fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { copyToClipboard(context, pw) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy password")
                    }
                }
            }
            ActionButton(Icons.Filled.Settings, "Open Wi-Fi Settings") {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        }

        is ScanPayload.Geo -> {
            ActionButton(Icons.Filled.Map, "Open in Maps") {
                val uri = buildString {
                    append("geo:${payload.latitude},${payload.longitude}")
                    payload.query?.let { append("?q=").append(Uri.encode(it)) }
                }
                openUri(context, uri)
            }
            Text(
                String.format(java.util.Locale.US, "%.5f, %.5f", payload.latitude, payload.longitude),
                fontFamily = FontFamily.Monospace
            )
            payload.query?.takeIf { it.isNotEmpty() }?.let { Text(it) }
        }

        is ScanPayload.Contact -> {
            val c = payload.payload
            c.fullName?.takeIf { it.isNotEmpty() }?.let { Text(it, fontWeight = FontWeight.Bold) }
            c.phones.forEach { phone ->
                ActionButton(Icons.Filled.Call, phone) { openUri(context, "tel:$phone") }
            }
            c.emails.forEach { email ->
                ActionButton(Icons.Filled.Email, email) {
                    context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")))
                }
            }
            c.urls.forEach { u ->
                ActionButton(Icons.Filled.OpenInBrowser, u) { openUri(context, u) }
            }
            ActionButton(Icons.Filled.Person, "Add to Contacts") {
                addToContacts(context, c)
            }
        }

        is ScanPayload.Calendar -> {
            LabelledFieldsList(payload.payload.labelledFields())
            ActionButton(Icons.Filled.CalendarToday, "Add to Calendar") {
                addToCalendar(context, payload.payload)
            }
        }

        is ScanPayload.Otp -> {
            Text("One-time password URI")
            Text("Open with your authenticator app of choice via Share.")
        }

        is ScanPayload.ProductCode -> {
            Text("${payload.system}: ${payload.code}", fontFamily = FontFamily.Monospace)
            ActionButton(Icons.Filled.Search, "Look up product") {
                openUri(context, "https://www.google.com/search?q=${Uri.encode(payload.code)}")
            }
        }

        is ScanPayload.Crypto -> {
            LabelledFieldsList(payload.payload.labelledFields())
            ActionButton(Icons.Filled.CreditCard, "Open in Wallet") { openUri(context, payload.payload.raw) }
        }

        is ScanPayload.EpcPayment -> LabelledFieldsList(payload.payload.labelledFields())
        is ScanPayload.SwissQRBill -> LabelledFieldsList(payload.payload.labelledFields())
        is ScanPayload.RuPayment -> LabelledFieldsList(payload.payload.labelledFields())
        is ScanPayload.FnsReceipt -> LabelledFieldsList(payload.payload.labelledFields())
        is ScanPayload.EmvPayment -> LabelledFieldsList(payload.payload.labelledFields())

        is ScanPayload.SufReceipt -> {
            LabelledFieldsList(payload.payload.labelledFields())
            ActionButton(Icons.Filled.Calculate, "Verify Receipt") {
                openUri(context, payload.payload.url)
            }
        }

        is ScanPayload.IpsPayment -> LabelledFieldsList(payload.payload.labelledFields())

        is ScanPayload.UpiPayment -> {
            LabelledFieldsList(payload.payload.labelledFields())
            ActionButton(Icons.Filled.OpenInBrowser, "Open in UPI app") {
                openUri(context, payload.payload.raw)
            }
        }

        is ScanPayload.CzechSPD -> LabelledFieldsList(payload.payload.labelledFields())
        is ScanPayload.PaBySquare -> LabelledFieldsList(payload.payload.labelledFields())

        is ScanPayload.Regional -> {
            LabelledFieldsList(payload.payload.labelledFields())
            ActionButton(Icons.Filled.OpenInBrowser, "Open in ${payload.payload.scheme.displayName}") {
                openUri(context, payload.payload.raw)
            }
        }

        is ScanPayload.Text -> Unit
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun LabelledFieldsList(fields: List<LabelledField>) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        fields.forEach { f ->
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(f.label, fontWeight = FontWeight.SemiBold)
                    Text(f.value, fontFamily = FontFamily.Monospace, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { copyToClipboard(context, f.value) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy ${f.label}")
                }
            }
        }
    }
}

// ---- Helpers --------------------------------------------------------------

internal fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Scan", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

internal fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share"))
}

internal fun shareImage(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share"))
}

private fun openUri(context: Context, uriString: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString))
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "No app to handle that link.", Toast.LENGTH_SHORT).show() }
}

private fun addToContacts(context: Context, contact: me.nettrash.scan.data.payload.ContactPayload) {
    val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE
        contact.fullName?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
        contact.phones.firstOrNull()?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
        contact.emails.firstOrNull()?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
        contact.organization?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
        contact.note?.let { putExtra(ContactsContract.Intents.Insert.NOTES, it) }
    }
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "No Contacts app available.", Toast.LENGTH_SHORT).show() }
}

private fun addToCalendar(context: Context, event: me.nettrash.scan.data.payload.CalendarPayload) {
    val intent = Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        event.summary?.let { putExtra(CalendarContract.Events.TITLE, it) }
        event.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        event.description?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
        event.startMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
        event.endMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
        if (event.allDay) putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "No Calendar app available.", Toast.LENGTH_SHORT).show() }
}

/**
 * Save a bitmap to MediaStore Pictures and return its content Uri. Used by
 * the generator's "Save to Photos" / "Share image" actions.
 */
internal fun saveBitmapToPictures(context: Context, bitmap: android.graphics.Bitmap, displayName: String): Uri? {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Scan")
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    return runCatching {
        resolver.openOutputStream(uri)?.use { os ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os)
        }
        uri
    }.getOrNull()
}
