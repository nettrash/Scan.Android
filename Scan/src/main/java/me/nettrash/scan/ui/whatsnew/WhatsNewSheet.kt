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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Widgets
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
    const val VERSION = "1.10"
    const val HEADLINE = "What's new in 1.10"

    data class Item(val icon: ImageVector, val title: String, val detail: String)

    /**
     * The same 1.10 items as iOS (Scan/WhatsNew.swift), in the same order and
     * wording, adjusted only where Android genuinely differs: the explainer is
     * always the built-in one, Live Text taps text blocks (no frozen-frame word
     * selection) and reads Latin script, a translation model is downloaded
     * from Google after Scan asks, blank NFC tags are formatted. The widget /
     * tile / shortcuts item stands in for the iOS "Shortcuts and Siri" item.
     */
    val items: List<Item> = listOf(
        Item(
            icon = Icons.Filled.AutoAwesome,
            title = "Ask what a code means",
            detail = "A \u201CWhat is this code?\u201D button explains an unfamiliar code in plain language with Scan\u2019s built-in explainer, right on your phone. What you scanned is never uploaded, and passwords, keys and secrets are blanked out first. Switch the assistant off any time in Settings \u2192 Insights.",
        ),
        Item(
            icon = Icons.Filled.GppGood,
            title = "QR safety check",
            detail = "Every code is checked before you are offered an action. Look-alike and disguised addresses, address-bar tricks, risky links, preset payment amounts and two codes stuck on top of each other get a plain-language warning, and a dangerous link offers Copy instead of Open. An ordinary link stays quiet.",
        ),
        Item(
            icon = Icons.Filled.DocumentScanner,
            title = "Live Text and Bulk modes",
            detail = "The Scan tab has three modes now. Live Text reads printed text (Latin script) alongside codes: tap a text block to copy, share or translate it. Bulk keeps scanning, counts every distinct code, and saves the list to History or exports it as CSV.",
        ),
        Item(
            icon = Icons.Filled.Badge,
            title = "Digital ID, passport and invoice codes",
            detail = "Scan decodes digital-ID codes \u2014 mobile driving licence and EU wallet connection codes, OpenID4VP requests and credential offers \u2014 showing who is asking and, when the code itself carries the request, which credential and details it names. Scan never answers a request. It also reads EU Digital Product Passports and EN 16931 e-invoices, so you can check the payee and total before you pay.",
        ),
        Item(
            icon = Icons.Filled.Key,
            title = "Passkey sign-in codes",
            detail = "The \u201Csign in with your phone\u201D QR a website shows for passkeys now says what it is for and when it was made, with a phishing caution. Its one-time secret is never shown.",
        ),
        Item(
            icon = Icons.Filled.Palette,
            title = "Styled codes, described in a sentence",
            detail = "Generated QR codes can use shaped modules and eyes, gradient inks, a logo and presets, and PNG, SVG and PDF keep the style. Or type what you want \u2014 \u201CWi-Fi for network BlueHouse, password hunter2\u201D \u2014 and the form fills itself.",
        ),
        Item(
            icon = Icons.Filled.Translate,
            title = "Translation",
            detail = "Text results and Live Text can be translated into the language you pick in Settings \u2192 Insights. Each language model (about 30 MB) is downloaded from Google once, after Scan asks, and Google\u2019s ML Kit then also checks its translation settings with Google while you are online. The text you scanned is never sent.",
        ),
        Item(
            icon = Icons.Filled.Nfc,
            title = "NFC tags",
            detail = "Read an NFC tag from the Scan tab, or write a generated code to a writable tag \u2014 links, phone numbers, Wi-Fi networks and contacts in their own NFC formats. Blank tags are formatted for you.",
        ),
        Item(
            icon = Icons.Filled.Widgets,
            title = "Widget, tile and shortcuts",
            detail = "A home-screen widget lists your latest scans \u2014 the kind of code, plus the site or network name, never a password or secret \u2014 a Quick Settings tile opens the camera from anywhere, and a long press on the Scan icon jumps straight to scanning, generating or your history.",
        ),
        Item(
            icon = Icons.Filled.Settings,
            title = "Carried over from 1.2 \u2014 1.9",
            detail = "WalletConnect, Nostr, Google Authenticator export, Plus Codes, what3words, IBAN and payment handles, reliable \u201COpen in \u2026\u201D hand-offs, History favourites + CSV export, QR colours / logos / SVG + PDF export, multi-code disambiguation, WPA3 + Passpoint, App Links, Backup status, Share to Scan + PDF \u2014 all here.",
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
