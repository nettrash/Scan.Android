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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.nettrash.scan.data.payload.LabelledField
import me.nettrash.scan.data.payload.RegionalPaymentPayload
import me.nettrash.scan.data.payload.RichURLPayload
import me.nettrash.scan.data.payload.ScanPayload

/**
 * Smart-action buttons + labelled fields for a parsed [ScanPayload]. Mirrors
 * the iOS PayloadActionsView — each subtype gets a tailored set of intents
 * (call, mail, browse, open in maps, add to contacts, add to calendar, etc.)
 * plus the common Copy / Share affordances.
 */
@Composable
fun PayloadActions(
    payload: ScanPayload,
    raw: String,
    /// New in 1.4. When non-null, surfaces a "Save as loyalty card"
    /// affordance under product-code payloads; tapping it opens a
    /// merchant-name prompt and then invokes the callback with the
    /// trimmed merchant string. The callback is responsible for
    /// persisting (typically by calling `ScannerViewModel.saveAsLoyaltyCard`).
    /// Pass null when no save side-effect is appropriate (e.g. when
    /// viewing an already-saved scan in the History detail dialog).
    onSaveAsLoyaltyCard: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmartActions(payload, context, onSaveAsLoyaltyCard)

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
private fun SmartActions(
    payload: ScanPayload,
    context: Context,
    onSaveAsLoyaltyCard: ((String) -> Unit)? = null,
) {
    // "No app installed" fallback. Populated when an `ACTION_VIEW` for a
    // custom-scheme payment / wallet / magnet URI finds no handler, so the
    // user gets a Play Store search + Copy link instead of a button that
    // only shows a brief toast. Mirrors iOS's `appFallback` alert.
    var appFallback by remember { mutableStateOf<AppFallback?>(null) }

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
                // ACTION_SENDTO mailto: throws ActivityNotFoundException on
                // devices/emulators without a mail app — wrap so we just
                // toast instead of crashing the app.
                runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, uri)) }
                    .onFailure {
                        Toast.makeText(context, "No email app available.", Toast.LENGTH_SHORT).show()
                    }
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
            payload.security?.takeIf { it.isNotEmpty() }?.let { sec ->
                Text("Security: ${friendlyWifiSecurity(sec)}")
                if (sec.equals("HS20", ignoreCase = true)) {
                    Text(
                        "Passpoint profiles must be installed manually — pass this QR's contents to your IT team or the venue's portal.",
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
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
                runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                    .onFailure {
                        Toast.makeText(context, "Wi-Fi settings unavailable.", Toast.LENGTH_SHORT).show()
                    }
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
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")))
                    }.onFailure {
                        Toast.makeText(context, "No email app available.", Toast.LENGTH_SHORT).show()
                    }
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
            // Loyalty-card affordance — Google Wallet's loyalty-pass
            // API needs a server-side JWT signed with a Wallet
            // service-account key, which we can't do client-side.
            // Instead we save a favourited History row tagged with
            // the merchant name; the user re-finds the code via
            // History → Favourites and the merchant tag makes
            // search trivial.
            if (onSaveAsLoyaltyCard != null) {
                LoyaltyCardSaveButton(onSave = onSaveAsLoyaltyCard)
            }
        }

        is ScanPayload.Crypto -> {
            LabelledFieldsList(payload.payload.labelledFields())
            ActionButton(Icons.Filled.CreditCard, "Open in Wallet") {
                openExternally(context, payload.payload.walletUri()) {
                    appFallback = AppFallback(
                        itemLabel = "${payload.payload.chain.displayName} wallet",
                        searchTerm = payload.payload.walletSearchTerm(),
                        raw = payload.payload.raw,
                    )
                }
            }
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
            // A plain ACTION_VIEW on `upi://pay?…` goes straight to whatever
            // app is set as the default UPI handler — frequently WhatsApp Pay,
            // which is why "Open in UPI app" was opening WhatsApp. Forcing a
            // chooser lets the user pick the UPI app every time (the Android
            // equivalent of iOS's manual app picker, which iOS needs because
            // it has no system chooser for custom schemes).
            ActionButton(Icons.Filled.OpenInBrowser, "Open in UPI app") {
                openWithChooser(context, payload.payload.raw, "Pay with…") {
                    appFallback = AppFallback("UPI app", "UPI payment", payload.payload.raw)
                }
            }
        }

        is ScanPayload.CzechSPD -> LabelledFieldsList(payload.payload.labelledFields())
        is ScanPayload.PaBySquare -> LabelledFieldsList(payload.payload.labelledFields())

        is ScanPayload.Regional -> {
            LabelledFieldsList(payload.payload.labelledFields())
            ActionButton(Icons.Filled.OpenInBrowser, "Open in ${payload.payload.scheme.displayName}") {
                openExternally(context, payload.payload.raw) {
                    appFallback = AppFallback(
                        itemLabel = regionalItemLabel(payload.payload.scheme),
                        searchTerm = regionalSearchTerm(payload.payload.scheme),
                        raw = payload.payload.raw,
                    )
                }
            }
        }

        is ScanPayload.Magnet -> {
            LabelledFieldsList(payload.payload.labelledFields())
            ActionButton(Icons.Filled.OpenInBrowser, "Open in torrent client") {
                openExternally(context, payload.payload.raw) {
                    appFallback = AppFallback("torrent client", "torrent client", payload.payload.raw)
                }
            }
        }

        is ScanPayload.RichUrl -> {
            LabelledFieldsList(payload.payload.labelledFields())
            // Digital identity flows can be coerced into impersonation:
            // a stranger's QR will *try* to log you in to *their*
            // session as *you*. Always make the user confirm.
            if (payload.payload.kind == RichURLPayload.Kind.DIGITAL_IDENTITY) {
                Text(
                    "Identity flow — only continue if you started this login yourself.",
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            val (label, _) = richUrlAction(payload.payload.kind)
            ActionButton(Icons.Filled.OpenInBrowser, label) {
                // Personal-payment brands can hand off to a custom-scheme app
                // (alipays://, weixin://, twint://) that may not be installed;
                // route them through the Play Store fallback. The https-backed
                // kinds open the browser and never hit it.
                if (payload.payload.kind.isPayment) {
                    openExternally(context, payload.payload.url) {
                        appFallback = AppFallback(
                            "${payload.payload.kind.displayName} app",
                            payload.payload.kind.displayName,
                            payload.payload.url,
                        )
                    }
                } else {
                    openUri(context, payload.payload.url)
                }
            }
        }

        is ScanPayload.WalletConnect -> {
            LabelledFieldsList(payload.payload.labelledFields())
            ActionButton(Icons.Filled.CreditCard, "Open in wallet") {
                openExternally(context, payload.payload.raw) {
                    appFallback = AppFallback(
                        "WalletConnect-compatible wallet", "crypto wallet", payload.payload.raw
                    )
                }
            }
        }

        is ScanPayload.Nostr -> {
            LabelledFieldsList(payload.payload.labelledFields())
            if (payload.payload.isPrivateKey) {
                Text(
                    "This is a secret key (nsec) — never share it or enter it into an app you don't trust.",
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else {
                val uri = if (payload.payload.raw.lowercase(java.util.Locale.ROOT).startsWith("nostr:"))
                    payload.payload.raw else "nostr:${payload.payload.id}"
                ActionButton(Icons.Filled.Person, "Open in Nostr client") {
                    openExternally(context, uri) {
                        appFallback = AppFallback("Nostr client", "nostr", uri)
                    }
                }
            }
        }

        is ScanPayload.OtpMigration -> {
            LabelledFieldsList(payload.payload.labelledFields())
            Text(
                "Bulk 2FA export. Use Share to hand it to an authenticator app — there's no universal import URL, and the codes' secrets are never displayed here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }

        is ScanPayload.PlusCode -> {
            LabelledFieldsList(payload.payload.labelledFields())
            ActionButton(Icons.Filled.Map, "Open in Maps") {
                openUri(context, "geo:0,0?q=${Uri.encode(payload.payload.mapsQuery)}")
            }
        }

        is ScanPayload.What3Words -> {
            LabelledFieldsList(payload.payload.labelledFields())
            ActionButton(Icons.Filled.Map, "Open in what3words") {
                openUri(context, "https://what3words.com/${payload.payload.words}")
            }
        }

        is ScanPayload.Iban -> {
            LabelledFieldsList(payload.payload.labelledFields())
            Text(
                "Checksum-valid ${payload.payload.countryCode} IBAN. Copy it into your bank app to pay or add a payee — there's no universal \"open\" for a bare account number.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }

        is ScanPayload.GS1 -> {
            LabelledFieldsList(payload.payload.labelledFields())
            payload.payload.gtin?.let { gtin ->
                ActionButton(Icons.Filled.Search, "Look up GTIN $gtin") {
                    openUri(context, "https://www.google.com/search?q=GTIN+${Uri.encode(gtin)}")
                }
            }
        }

        is ScanPayload.BoardingPass -> LabelledFieldsList(payload.payload.labelledFields())
        is ScanPayload.DrivingLicense -> LabelledFieldsList(payload.payload.labelledFields())

        is ScanPayload.Text -> Unit
    }

    appFallback?.let { fb ->
        AppFallbackDialog(fallback = fb, onDismiss = { appFallback = null })
    }
}

/** Metadata for the "no app installed" fallback dialog. [itemLabel] is a noun
 *  phrase that reads naturally after "No … is installed" (e.g. "Bitcoin
 *  wallet", "torrent client", "Swish app"). Mirrors iOS's `AppFallback`. */
data class AppFallback(
    val itemLabel: String,
    val searchTerm: String,
    val raw: String,
)

/**
 * Shown when `ACTION_VIEW` for a payment / wallet / magnet URI found no
 * handler. Offers a Play Store search for a compatible app or a Copy-link
 * escape hatch, instead of dead-ending. Dismissing (tap-away / back) is the
 * cancel path. Mirrors iOS's "No app installed" alert.
 */
@Composable
private fun AppFallbackDialog(fallback: AppFallback, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("No app installed") },
        text = {
            Text(
                "No ${fallback.itemLabel} is installed to open this. " +
                    "Find one on the Play Store, or copy the link to use it elsewhere."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                openPlayStoreSearch(context, fallback.searchTerm)
                onDismiss()
            }) { Text("Find on Play Store") }
        },
        dismissButton = {
            TextButton(onClick = {
                copyToClipboard(context, fallback.raw)
                onDismiss()
            }) { Text("Copy link") }
        },
    )
}

private fun regionalItemLabel(scheme: RegionalPaymentPayload.Scheme): String = when (scheme) {
    RegionalPaymentPayload.Scheme.BEZAHLCODE -> "compatible banking app"
    RegionalPaymentPayload.Scheme.SWISH -> "Swish app"
    RegionalPaymentPayload.Scheme.VIPPS -> "Vipps app"
    RegionalPaymentPayload.Scheme.MOBILEPAY -> "MobilePay app"
    RegionalPaymentPayload.Scheme.BIZUM -> "Bizum-enabled banking app"
    RegionalPaymentPayload.Scheme.IDEAL -> "iDEAL-enabled banking app"
}

private fun regionalSearchTerm(scheme: RegionalPaymentPayload.Scheme): String = when (scheme) {
    RegionalPaymentPayload.Scheme.BEZAHLCODE -> "banking"
    RegionalPaymentPayload.Scheme.SWISH -> "swish"
    RegionalPaymentPayload.Scheme.VIPPS -> "vipps"
    RegionalPaymentPayload.Scheme.MOBILEPAY -> "mobilepay"
    RegionalPaymentPayload.Scheme.BIZUM -> "bizum"
    RegionalPaymentPayload.Scheme.IDEAL -> "ideal banking"
}

/**
 * Two-step UI for "save this product code as a loyalty card":
 * first shows a button, then on tap an [AlertDialog] with a merchant
 * TextField. The save itself is delegated to the caller — typically
 * `ScannerViewModel.saveAsLoyaltyCard(merchant)`, which writes a
 * favourited `ScanRecord` with `notes = "Loyalty: <merchant>"`.
 */
@Composable
private fun LoyaltyCardSaveButton(onSave: (String) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    var merchant by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { merchant = ""; showDialog = true },
        enabled = !saved,
    ) {
        Icon(Icons.Filled.CreditCard, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (saved) "Saved as loyalty card" else "Save as loyalty card")
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Save as loyalty card") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = merchant,
                        onValueChange = { merchant = it },
                        label = { Text("Merchant (e.g. Tesco, IKEA)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Saved scans tagged \"Loyalty: …\" are favourited so they pin to the top of History.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSave(merchant.trim())
                    saved = true
                    showDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun richUrlAction(kind: RichURLPayload.Kind): Pair<String, String> = when (kind) {
    RichURLPayload.Kind.WHATS_APP    -> "Open in WhatsApp" to "WhatsApp"
    RichURLPayload.Kind.TELEGRAM     -> "Open in Telegram" to "Telegram"
    RichURLPayload.Kind.APPLE_WALLET -> "Add to Wallet" to "Wallet"
    RichURLPayload.Kind.APP_STORE    -> "Open in App Store" to "App Store"
    RichURLPayload.Kind.PLAY_STORE   -> "Open Play Store listing" to "Play Store"
    RichURLPayload.Kind.YOUTUBE      -> "Watch on YouTube" to "YouTube"
    RichURLPayload.Kind.SPOTIFY      -> "Open in Spotify" to "Spotify"
    RichURLPayload.Kind.APPLE_MUSIC  -> "Open in Apple Music" to "Apple Music"
    RichURLPayload.Kind.GOOGLE_MAPS,
    RichURLPayload.Kind.APPLE_MAPS   -> "Open in Maps" to "Maps"
    RichURLPayload.Kind.DIGITAL_IDENTITY -> "Continue in browser" to "Identity"
    RichURLPayload.Kind.PAY_PAL      -> "Open in PayPal" to "PayPal"
    RichURLPayload.Kind.VENMO        -> "Open in Venmo" to "Venmo"
    RichURLPayload.Kind.CASH_APP     -> "Open in Cash App" to "Cash App"
    RichURLPayload.Kind.REVOLUT      -> "Open in Revolut" to "Revolut"
    RichURLPayload.Kind.TWINT        -> "Open in TWINT" to "TWINT"
    RichURLPayload.Kind.ALI_PAY      -> "Open in Alipay" to "Alipay"
    RichURLPayload.Kind.WE_CHAT_PAY  -> "Open in WeChat Pay" to "WeChat Pay"
    RichURLPayload.Kind.SIGNAL       -> "Open in Signal" to "Signal"
    RichURLPayload.Kind.MATRIX       -> "Open in Matrix" to "Matrix"
    RichURLPayload.Kind.MEETING      -> "Join meeting" to "Meeting"
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

/**
 * Open a custom-scheme URI (`bitcoin:`, `magnet:`, `swish:`, …), invoking
 * [onNoApp] when nothing on the device can handle it instead of just
 * toasting. `startActivity` — not `PackageManager.resolveActivity` — is the
 * reliable signal here: on Android 11+ package-visibility filtering can make
 * `resolveActivity` return null even when a handler exists, but launching the
 * intent always works (or throws `ActivityNotFoundException`, which we catch).
 */
private fun openExternally(context: Context, uriString: String, onNoApp: () -> Unit) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString))
    runCatching { context.startActivity(intent) }.onFailure { onNoApp() }
}

/**
 * Open a URI through an explicit app chooser, so the user picks the target
 * app every time rather than being silently routed to a previously-set
 * default. Used for UPI, where the default handler is frequently WhatsApp
 * Pay. `createChooser` shows all installed handlers (the system resolver has
 * full package visibility), and surfaces its own "no apps" message when none
 * exist; [onNoApp] is a defensive fallback for the rare launch failure.
 */
private fun openWithChooser(context: Context, uriString: String, title: String, onNoApp: () -> Unit) {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(uriString))
    val chooser = Intent.createChooser(view, title)
    runCatching { context.startActivity(chooser) }.onFailure { onNoApp() }
}

/**
 * Open the Play Store to a search for [term]. Tries the `market://` deep link
 * first (opens the Play Store app directly) and falls back to the web
 * storefront if the Play Store app is absent (e.g. on de-Googled devices).
 */
private fun openPlayStoreSearch(context: Context, term: String) {
    val q = Uri.encode(term)
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$q&c=apps"))
    runCatching { context.startActivity(market) }.onFailure {
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=$q&c=apps"))
        runCatching { context.startActivity(web) }
            .onFailure { Toast.makeText(context, "Couldn't open the Play Store.", Toast.LENGTH_SHORT).show() }
    }
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

/**
 * Map the raw `T:` field of a `WIFI:` payload to a user-friendly
 * label. Unknown tokens pass through verbatim so a future security
 * tag still shows *something* rather than being silently dropped.
 * Mirrors `friendlyWifiSecurity` on iOS exactly.
 */
internal fun friendlyWifiSecurity(raw: String): String = when (raw.uppercase()) {
    "WPA", "WPA2"     -> "WPA / WPA2"
    "WEP"             -> "WEP"
    "SAE", "WPA3"     -> "WPA3 (SAE)"
    "HS20", "PASSPOINT", "OSU" -> "Passpoint (HS20)"
    "NOPASS", "NONE", "" -> "None"
    else              -> raw
}
