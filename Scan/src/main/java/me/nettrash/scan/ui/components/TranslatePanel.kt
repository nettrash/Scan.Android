package me.nettrash.scan.ui.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.nettrash.scan.data.payload.ScanTranslator
import me.nettrash.scan.data.payload.TranslateLanguage
import me.nettrash.scan.data.payload.TranslationCatalog

/**
 * Translate action + result as one block, shared by the result sheets
 * (`PayloadActions`) and the Live Text card — the Android counterpart of
 * iOS `TranslateRow`.
 *
 * Everything that touches the text happens on the device: the language is
 * identified by ML Kit's bundled model and the translation runs on installed
 * models only. A language-model download never starts on its own — when a
 * model is missing the panel says which ones, how big they are, and whether
 * the phone is on mobile data, and waits for the user's yes. That dialog is
 * this port's equivalent of the consent sheet the iOS system shows before it
 * fetches a language pack, and it also says what else ML Kit sends Google —
 * an installation ID, its settings fetch and usage statistics, never the text
 * (see the privacy note in `TranslationKit.kt`).
 */
@Composable
fun TranslatePanel(
    text: String,
    target: TranslateLanguage,
    modifier: Modifier = Modifier,
    colors: TranslatePanelColors = TranslatePanelDefaults.themed(),
    /** Non-null shows a target menu next to the button (the Live Text card). */
    onChangeTarget: ((TranslateLanguage) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A new text or target is a new translation: nothing carries over.
    var step by remember(text, target) { mutableStateOf<TranslateStep>(TranslateStep.Idle) }
    var targetMenuOpen by remember { mutableStateOf(false) }
    var sourceMenuOpen by remember(text) { mutableStateOf(false) }
    val available = remember { ScanTranslator.isAvailable() }
    val working = step is TranslateStep.Working || step is TranslateStep.Downloading

    fun start(source: String?) {
        scope.launch { step = runTranslation(context, text, target, source) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Translate,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
            TextButton(
                onClick = {
                    step = TranslateStep.Working
                    start(source = null)
                },
                enabled = available && !working && text.isNotBlank(),
            ) {
                Text(
                    if (working) "Translating to ${target.label}…" else "Translate to ${target.label}",
                    color = if (available) colors.accent else colors.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (working) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = colors.accent,
                )
            }
            if (onChangeTarget != null) {
                Box {
                    TextButton(onClick = { targetMenuOpen = true }, enabled = !working) {
                        Text("Language", color = colors.secondary)
                    }
                    DropdownMenu(
                        expanded = targetMenuOpen,
                        onDismissRequest = { targetMenuOpen = false },
                    ) {
                        TranslationCatalog.targets.forEach { language ->
                            DropdownMenuItem(
                                text = { Text(language.label) },
                                trailingIcon = {
                                    if (language == target) {
                                        Icon(Icons.Filled.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    targetMenuOpen = false
                                    onChangeTarget(language)
                                },
                            )
                        }
                    }
                }
            }
        }

        if (!available) {
            Text(
                ScanTranslator.NOT_AVAILABLE_MESSAGE,
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondary,
            )
        }

        when (val current = step) {
            is TranslateStep.Downloading -> Text(
                "Downloading the ${current.languages} " +
                    (if (current.count == 1) "language model" else "language models") +
                    "… Translation starts when it is done.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondary,
            )

            is TranslateStep.Done -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Translation from ${current.sourceLabel}",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.secondary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { copyToClipboard(context, current.text) }) {
                            Text("Copy", color = colors.accent)
                        }
                    }
                    Text(
                        current.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.content,
                        modifier = Modifier.heightIn(max = 240.dp),
                    )
                }
                SourcePicker(
                    label = "Not ${current.sourceLabel}? Choose the language",
                    expanded = sourceMenuOpen,
                    colors = colors,
                    onOpen = { sourceMenuOpen = true },
                    onDismiss = { sourceMenuOpen = false },
                    onPick = { tag ->
                        sourceMenuOpen = false
                        step = TranslateStep.Working
                        start(source = tag)
                    },
                )
            }

            is TranslateStep.Failed -> {
                Text(
                    current.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.warning,
                )
                if (current.offerSourcePicker) {
                    SourcePicker(
                        label = "Choose the language it is in",
                        expanded = sourceMenuOpen,
                        colors = colors,
                        onOpen = { sourceMenuOpen = true },
                        onDismiss = { sourceMenuOpen = false },
                        onPick = { tag ->
                            sourceMenuOpen = false
                            step = TranslateStep.Working
                            start(source = tag)
                        },
                    )
                }
            }

            TranslateStep.Idle, TranslateStep.Working, is TranslateStep.Consent -> Unit
        }
    }

    (step as? TranslateStep.Consent)?.let { consent ->
        val prompt = downloadPrompt(consent.plan, consent.network)
        AlertDialog(
            onDismissRequest = { step = TranslateStep.Idle },
            icon = { Icon(Icons.Filled.Translate, contentDescription = null) },
            title = { Text(prompt.title) },
            text = { Text(prompt.body) },
            confirmButton = {
                TextButton(onClick = {
                    val plan = consent.plan
                    step = TranslateStep.Downloading(listOfLanguages(plan.missing), plan.missing.size)
                    scope.launchDownloadThenTranslate(plan, text) { step = it }
                }) { Text(prompt.confirmLabel) }
            },
            dismissButton = {
                TextButton(onClick = { step = TranslateStep.Idle }) { Text("Not now") }
            },
        )
    }
}

/** The "Translate from …" menu, for text the identifier could not place or placed wrongly. */
@Composable
private fun SourcePicker(
    label: String,
    expanded: Boolean,
    colors: TranslatePanelColors,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val languages = remember { ScanTranslator.sourceLanguages() }
    if (languages.isEmpty()) return
    Box {
        TextButton(onClick = onOpen) {
            Text(label, color = colors.accent, style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            languages.forEach { (tag, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onPick(tag) })
            }
        }
    }
}

/** Colours for a [TranslatePanel]: the result sheets use the theme, the Live Text card is always dark. */
@Immutable
data class TranslatePanelColors(
    val content: Color,
    val secondary: Color,
    val accent: Color,
    val warning: Color,
    val surface: Color,
)

object TranslatePanelDefaults {
    @Composable
    fun themed(): TranslatePanelColors = TranslatePanelColors(
        content = MaterialTheme.colorScheme.onSurface,
        secondary = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        accent = MaterialTheme.colorScheme.primary,
        warning = Color(0xFFF57C00),
        surface = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
    )

    /** For the translucent dark Live Text card over the camera. */
    val onDark: TranslatePanelColors = TranslatePanelColors(
        content = Color.White,
        secondary = Color.White.copy(alpha = 0.7f),
        accent = Color.White,
        warning = Color(0xFFFFB74D),
        surface = Color.White.copy(alpha = 0.08f),
    )
}

// ---- Flow -----------------------------------------------------------------

/** Where a [TranslatePanel] is in its flow. */
internal sealed interface TranslateStep {
    data object Idle : TranslateStep
    data object Working : TranslateStep
    data class Consent(val plan: ScanTranslator.Plan.NeedsDownload, val network: NetworkHint) : TranslateStep
    data class Downloading(val languages: String, val count: Int) : TranslateStep
    data class Done(val text: String, val sourceLabel: String) : TranslateStep
    data class Failed(val message: String, val offerSourcePicker: Boolean = false) : TranslateStep
}

/**
 * Identify (unless the user named the language), plan, and either translate
 * at once or stop at the consent dialog. Never downloads anything itself.
 */
private suspend fun runTranslation(
    context: Context,
    text: String,
    target: TranslateLanguage,
    pickedSource: String?,
): TranslateStep {
    val source = pickedSource ?: ScanTranslator.identifyLanguage(text)
    return when (val plan = ScanTranslator.plan(source, target, ScanTranslator.downloadedModels())) {
        is ScanTranslator.Plan.AlreadyInTarget ->
            TranslateStep.Failed("This text is already in ${plan.target.label}.")
        ScanTranslator.Plan.UnknownSource ->
            TranslateStep.Failed(ScanTranslator.UNKNOWN_SOURCE_MESSAGE, offerSourcePicker = true)
        ScanTranslator.Plan.Unsupported ->
            TranslateStep.Failed(ScanTranslator.NOT_AVAILABLE_MESSAGE)
        is ScanTranslator.Plan.NeedsDownload ->
            TranslateStep.Consent(plan, networkHint(context))
        is ScanTranslator.Plan.Ready ->
            translateNow(text, plan.sourceTag, plan.targetTag)
    }
}

private suspend fun translateNow(text: String, sourceTag: String, targetTag: String): TranslateStep =
    try {
        TranslateStep.Done(
            text = ScanTranslator.translate(text, sourceTag, targetTag),
            sourceLabel = TranslationCatalog.label(sourceTag),
        )
    } catch (failure: ScanTranslator.TranslationUnavailable) {
        TranslateStep.Failed(failure.message ?: ScanTranslator.NOT_AVAILABLE_MESSAGE)
    }

/** The user said yes: fetch the pair's models, then translate. */
private fun CoroutineScope.launchDownloadThenTranslate(
    plan: ScanTranslator.Plan.NeedsDownload,
    text: String,
    onStep: (TranslateStep) -> Unit,
) {
    launch {
        val next = try {
            ScanTranslator.download(plan.sourceTag, plan.targetTag)
            translateNow(text, plan.sourceTag, plan.targetTag)
        } catch (failure: ScanTranslator.TranslationUnavailable) {
            TranslateStep.Failed(failure.message ?: ScanTranslator.DOWNLOAD_FAILED_MESSAGE)
        }
        onStep(next)
    }
}

// ---- Download prompt ------------------------------------------------------

/** What the connection looks like, for the download prompt's mobile-data line. */
internal enum class NetworkHint { UNMETERED, METERED, OFFLINE, UNKNOWN }

/**
 * Best-effort read of the active network. `ACCESS_NETWORK_STATE` is declared
 * in the manifest for exactly this; if a locked-down profile still refuses
 * the call, the prompt simply omits the network line.
 */
internal fun networkHint(context: Context): NetworkHint = runCatching {
    val manager = context.getSystemService(ConnectivityManager::class.java)
        ?: return@runCatching NetworkHint.UNKNOWN
    val network = manager.activeNetwork ?: return@runCatching NetworkHint.OFFLINE
    val capabilities = manager.getNetworkCapabilities(network)
        ?: return@runCatching NetworkHint.OFFLINE
    when {
        !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkHint.OFFLINE
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> NetworkHint.UNMETERED
        else -> NetworkHint.METERED
    }
}.getOrDefault(NetworkHint.UNKNOWN)

/** The consent dialog's copy, split out so its wording is testable. */
internal data class DownloadPrompt(val title: String, val body: String, val confirmLabel: String)

internal fun downloadPrompt(plan: ScanTranslator.Plan.NeedsDownload, network: NetworkHint): DownloadPrompt {
    val count = plan.missing.size
    val models = listOfLanguages(plan.missing)
    val pair = "${TranslationCatalog.label(plan.sourceTag)} into ${TranslationCatalog.label(plan.targetTag)}"
    val size = "about ${ScanTranslator.APPROXIMATE_MODEL_MEGABYTES} MB" + if (count == 1) "" else " each"
    val body = buildString {
        append("Translating $pair needs Google's on-device $models ")
        append(if (count == 1) "language model" else "language models")
        append(" ($size). Scan downloads ")
        append(if (count == 1) "it" else "them")
        append(" from Google once; after that, this pair works offline. ")
        append("Google's ML Kit, which translates, also registers an app-installation ID with Google ")
        append("(Firebase) and, while you are online, fetches its translation settings at most about ")
        append("twice a day, sending your device language and region, time zone, and Android and app ")
        append("version. ML Kit also sends Google usage and diagnostic statistics, including the ")
        append("language pair. The text you scanned is never sent — it stays on this device.")
        when (network) {
            NetworkHint.METERED -> append("\n\nYou're on mobile data.")
            NetworkHint.OFFLINE -> append("\n\nYou're offline right now — connect to a network first.")
            NetworkHint.UNMETERED, NetworkHint.UNKNOWN -> Unit
        }
    }
    return DownloadPrompt(
        title = if (count == 1) "Download a translation model?" else "Download translation models?",
        body = body,
        confirmLabel = if (network == NetworkHint.METERED) "Download over mobile data" else "Download",
    )
}

/** "German", "German and English", "Swedish, German and English". */
internal fun listOfLanguages(tags: List<String>): String {
    val names = tags.map(TranslationCatalog::label)
    return when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
}
