package me.nettrash.scan.data.payload

import android.os.LocaleList
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import me.nettrash.scan.nfc.NfcPayloadCodec
import com.google.mlkit.nl.translate.TranslateLanguage as MlKitLanguage

//
//  On-device translation for scanned text — menus, signs, foreign payment
//  instructions.
//
//  iOS builds this on the iOS 26 `Translation` framework. The Android
//  counterpart is ML Kit's on-device translation (`com.google.mlkit:translate`)
//  plus ML Kit's on-device language identification
//  (`com.google.mlkit:language-id`, bundled model) — the second one because
//  ML Kit's translator, unlike Apple's, needs to be told what language the text
//  is in, and the phone's own language is exactly the wrong guess for a menu
//  read abroad. Identification answers the question NaturalLanguage answers on
//  iOS.
//
//  THE PRIVACY INVARIANT: the scanned text is handed to the on-device
//  identifier and translator and never to a network. No HTTP client of ours
//  is involved, and no translate REST endpoint exists anywhere in the app.
//  What does reach Google, all of it ML Kit's own doing:
//   - the one-time download of a language model (~30 MB, the pair's
//     languages only). Scan never starts one silently:
//     [ScanTranslator.translate] works with installed models only, and
//     [ScanTranslator.download] is called by the UI only after the user
//     agreed in Scan's own dialog — the counterpart of the consent sheet the
//     iOS system shows before it fetches a language pack;
//   - building a translator (`Translation.getClient`, which every download
//     and every translation does) registers an app-installation ID with
//     Firebase Installations and, when ML Kit's cached settings are older
//     than 12 hours and the phone is online, fetches its Firebase Remote
//     Config — sending the device's language and region, time zone, Android
//     and app version, and that installation ID;
//   - usage and diagnostic statistics (Firelog): the configured language
//     pair, the identified language, model downloads, latency, errors.
//  None of it is the text, and none of it can be switched off from here —
//  which is why the download dialog and the Settings footer say so
//  (`downloadPrompt`, `insightsFooter`). Google documents it on ML Kit's
//  Android data-disclosure page.
//
//  WHY NOT `android.translation`? The platform Text Translation API
//  (`TranslationManager` / `TranslationRequest` / `TranslationCallback`) is not
//  in the public SDK: `android/translation` appears zero times in the
//  android.jar of every installed platform (android-35 … android-37), i.e. it
//  was never opened to third-party apps. ML Kit is the only on-device route a
//  third-party app can actually call.
//
//  Languages ML Kit does not ship a model for — Serbian (Latin) and Bosnian,
//  which neither platform can translate into today; they stay in the shared
//  catalog so both ports keep one list and the same stored Settings values —
//  resolve to the honest [ScanTranslator.NOT_AVAILABLE_MESSAGE] rather than a
//  wrong-language guess, and the "device language" default skips them.
//

/** One selectable language in the translate UI. Mirrors iOS `TranslateLanguage`. */
data class TranslateLanguage(val identifier: String, val label: String) {
    val id: String get() = identifier
    val locale: Locale get() = Locale.forLanguageTag(identifier)
}

object TranslationCatalog {

    /** The stored Settings value meaning "whatever language the phone is in". */
    const val SYSTEM_TAG: String = "system"

    /** The target when nothing else matches. */
    val english: TranslateLanguage = TranslateLanguage("en", "English")

    /**
     * Languages a barcode scanner realistically meets — including the app's
     * own regional formats (Serbian, Czech, Slovak, Russian, German). The same
     * list, in the same order, as iOS `TranslationCatalog.languages`.
     */
    val languages: List<TranslateLanguage> = listOf(
        english,
        TranslateLanguage("de", "German"),
        TranslateLanguage("fr", "French"),
        TranslateLanguage("es", "Spanish"),
        TranslateLanguage("it", "Italian"),
        TranslateLanguage("pt-BR", "Portuguese (Brazil)"),
        TranslateLanguage("nl", "Dutch"),
        TranslateLanguage("pl", "Polish"),
        TranslateLanguage("cs", "Czech"),
        TranslateLanguage("sk", "Slovak"),
        TranslateLanguage("sr-Latn", "Serbian (Latin)"),
        TranslateLanguage("hr", "Croatian"),
        TranslateLanguage("sl", "Slovenian"),
        TranslateLanguage("bs", "Bosnian"),
        TranslateLanguage("bg", "Bulgarian"),
        TranslateLanguage("ro", "Romanian"),
        TranslateLanguage("el", "Greek"),
        TranslateLanguage("hu", "Hungarian"),
        TranslateLanguage("ru", "Russian"),
        TranslateLanguage("uk", "Ukrainian"),
        TranslateLanguage("tr", "Turkish"),
        TranslateLanguage("ar", "Arabic"),
        TranslateLanguage("he", "Hebrew"),
        TranslateLanguage("hi", "Hindi"),
        TranslateLanguage("zh-Hans", "Chinese (Simplified)"),
        TranslateLanguage("ja", "Japanese"),
        TranslateLanguage("ko", "Korean"),
        TranslateLanguage("id", "Indonesian"),
        TranslateLanguage("th", "Thai"),
        TranslateLanguage("vi", "Vietnamese"),
    )

    /**
     * The catalog languages ML Kit can translate *into* on this build — what
     * the Settings picker and the Live Text card offer, the way iOS narrows the
     * catalog to `LanguageAvailability().supportedLanguages`.
     */
    val targets: List<TranslateLanguage> get() = languages.filter { mlKitTag(it) != null }

    /**
     * The language the user reads, used as the default target: the first of
     * [preferred] (the user's language list, most preferred first) that is in
     * the catalog and that ML Kit can translate into, like iOS walks
     * `Locale.preferredLanguages` and skips what its Translation cannot
     * produce. Scan ships in English only, and someone whose list is
     * "Serbian, German" still wants German rather than the English fallback —
     * Serbian (Latin), which ML Kit has no model for, is skipped. Null when
     * none qualifies.
     */
    fun deviceLanguage(preferred: List<Locale>): TranslateLanguage? =
        preferred.firstNotNullOfOrNull { locale -> entryFor(locale)?.takeIf { mlKitTag(it) != null } }

    /**
     * [deviceLanguage] for this device's language list ([LocaleList.getDefault],
     * then the default locale), English when nothing in it qualifies — so
     * never null in practice.
     */
    val deviceLanguage: TranslateLanguage?
        get() = deviceLanguage(preferredLocales()) ?: english

    /**
     * Settings' name for the "system" choice: "Device language (German)", or,
     * when the phone's own language is one ML Kit cannot translate into, what
     * is used instead and why — "Device language (English — Serbian (Latin)
     * isn't available)". iOS words it the same.
     */
    fun deviceLanguageLabel(preferred: List<Locale> = preferredLocales()): String {
        val resolved = deviceLanguage(preferred) ?: english
        val first = preferred.firstNotNullOfOrNull { entryFor(it) }
        return if (first != null && first != resolved) {
            "Device language (${resolved.label} — ${first.label} isn't available)"
        } else {
            "Device language (${resolved.label})"
        }
    }

    /** The user's language list, most preferred first, then the default locale. */
    private fun preferredLocales(): List<Locale> {
        val list = runCatching { LocaleList.getDefault() }.getOrNull()
        val locales = if (list == null) emptyList() else (0 until list.size()).map { list[it] }
        return locales + Locale.getDefault()
    }

    /**
     * Free-text-ish payloads are the ones where a translation actually helps —
     * a menu, a sign, a foreign payment reference. Text needs eight
     * characters as a person counts them, past white space — iOS counts Swift
     * `Character`s and trims its `whitespacesAndNewlines`, so this counts
     * grapheme clusters ([SwiftText]) after the same trim. Mirrors iOS
     * `TranslationCatalog.isTranslatable(_:)`.
     */
    fun isTranslatable(payload: ScanPayload): Boolean = when (payload) {
        is ScanPayload.Text -> SwiftText.count(NfcPayloadCodec.trimWhitespace(payload.text)) >= 8
        is ScanPayload.Url, is ScanPayload.RichUrl -> true
        is ScanPayload.Email -> payload.body?.isNotEmpty() == true
        else -> false
    }

    /** The stored `SettingsState.translateLanguage` value ("system" = device language). */
    fun languageFor(stored: String): TranslateLanguage? {
        if (stored.isBlank() || stored == SYSTEM_TAG) return deviceLanguage
        return languages.firstOrNull { it.identifier == stored }
    }

    /**
     * The Settings value resolved to a language the way iOS
     * `language(forStored:)` does: anything no longer in the catalog means the
     * device language, then English.
     */
    fun resolved(stored: String): TranslateLanguage = languageFor(stored) ?: deviceLanguage ?: english

    /**
     * The catalog entry for a system locale: same script and region when the
     * entry names one, otherwise the same language at all ("pt-PT" reads
     * Brazilian Portuguese rather than falling back to English). Mirrors iOS
     * `entry(for:)`.
     */
    internal fun entryFor(locale: Locale): TranslateLanguage? {
        val language = canonicalLanguage(locale.language)
        if (language.isEmpty()) return null
        val sameLanguage = languages.filter { canonicalLanguage(it.locale.language) == language }
        val script = locale.script.ifEmpty { likelyScript(language, locale.country) }
        return sameLanguage.firstOrNull { entry ->
            val wanted = entry.locale
            (wanted.script.isEmpty() || wanted.script == script) &&
                (wanted.country.isEmpty() || locale.country.isEmpty() || wanted.country == locale.country)
        } ?: sameLanguage.firstOrNull()
    }

    /** Java still reports a few ISO 639 codes by their withdrawn names on some releases. */
    private fun canonicalLanguage(code: String): String = when (val lower = code.lowercase(Locale.ROOT)) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        else -> lower
    }

    /** "zh" is written in Hans and "sr" in Cyrl unless they say otherwise. */
    private fun likelyScript(language: String, region: String): String = when (language) {
        "zh" -> if (region in setOf("TW", "HK", "MO")) "Hant" else "Hans"
        "sr" -> "Cyrl"
        else -> ""
    }

    /**
     * The tag ML Kit's translator expects for one of our catalog languages, or
     * null when it has no model for it. Deliberately an explicit table rather
     * than "pass the BCP-47 tag through": ML Kit uses its own identifiers
     * (`pt`, `zh`) and rejects anything off its supported list.
     */
    private val mlKitTags: Map<String, String> = mapOf(
        "en" to "en", "de" to "de", "fr" to "fr", "es" to "es", "it" to "it",
        "pt-BR" to "pt", "nl" to "nl", "pl" to "pl", "cs" to "cs", "sk" to "sk",
        "hr" to "hr", "sl" to "sl", "bg" to "bg", "ro" to "ro", "el" to "el",
        "hu" to "hu", "ru" to "ru", "uk" to "uk", "tr" to "tr", "ar" to "ar",
        "he" to "he", "hi" to "hi", "zh-Hans" to "zh", "ja" to "ja", "ko" to "ko",
        "id" to "id", "th" to "th", "vi" to "vi",
    )

    /** Null means ML Kit cannot translate this language on device. */
    fun mlKitTag(language: TranslateLanguage): String? = mlKitTags[language.identifier]

    /**
     * The English name of an ML Kit language tag, for the download prompt and
     * the "Translate from" picker — the catalog label when the tag is one of
     * ours, the platform's English display name otherwise (a Swedish menu is a
     * perfectly good source even though Swedish is not a catalog target).
     */
    fun label(mlKitTag: String): String {
        languages.firstOrNull { mlKitTag(it) == mlKitTag }?.let { return it.label }
        val name = Locale.forLanguageTag(mlKitTag).getDisplayLanguage(Locale.ENGLISH)
        return name.ifBlank { mlKitTag }
    }
}

/**
 * Thin wrapper over ML Kit's on-device language identification and
 * translation. Every entry point is total — a device without the ML Kit
 * component, a missing model, or a language ML Kit does not ship ends in a
 * [TranslationUnavailable] with a message the UI shows verbatim, never in a
 * crash or a button that quietly does nothing.
 *
 * The flow the UI runs (see `ui/components/TranslatePanel.kt`):
 *  1. [identifyLanguage] — on device, so "which language is this?" never
 *     leaves the phone either;
 *  2. [plan] — pure: same language, unsupported pair, models missing, or
 *     ready;
 *  3. only when models are missing and the user said yes: [download];
 *  4. [translate] — installed models only, never a download.
 */
object ScanTranslator {

    /** What the UI says when nothing can translate this pair here. */
    const val NOT_AVAILABLE_MESSAGE: String = "This language pair isn't available on this device yet."

    /** iOS `Failure.unknownSourceLanguage`, word for word. */
    const val UNKNOWN_SOURCE_MESSAGE: String = "Scan couldn't tell which language this text is in."

    /** iOS `Failure.nothingToTranslate`, word for word. */
    const val NOTHING_TO_TRANSLATE_MESSAGE: String = "Nothing to translate."

    /** A model download that did not complete — usually no connection. */
    const val DOWNLOAD_FAILED_MESSAGE: String =
        "Couldn't download the language model. Check your connection and try again."

    /**
     * What one ML Kit translation model weighs, as Google documents it ("around
     * 30MB"; the packages listed in the SDK's `translate_models_metadata.json`
     * are 31–36 MB to download). Shown in the download prompt so the user knows
     * what they agree to fetch.
     */
    const val APPROXIMATE_MODEL_MEGABYTES: Int = 30

    /**
     * ML Kit's own tag for English. Every ML Kit model is a language *paired
     * with English* (`de_en`, `hr_en`, … in the SDK's model metadata), so
     * English needs no model of its own and every other pair goes through it.
     */
    const val ENGLISH_TAG: String = "en"

    /**
     * ML Kit's identifier reports anything it is less sure of than this as
     * undetermined. 0.5 is the bar iOS applies to NaturalLanguage's best
     * hypothesis, so short or mixed text is treated the same on both ports.
     */
    private const val IDENTIFICATION_CONFIDENCE = 0.5f

    /** Why a translator could not be built / ran out of road. Shown verbatim. */
    class TranslationUnavailable(message: String) : Exception(message)

    /**
     * What a tap on Translate should do next, decided from facts alone so it
     * can be tested without ML Kit in the picture.
     */
    sealed interface Plan {
        /** The text is already in the target language: nothing to do. */
        data class AlreadyInTarget(val target: TranslateLanguage) : Plan

        /** The identifier could not name the language (or ML Kit cannot read it). */
        data object UnknownSource : Plan

        /** ML Kit has no model for the target. */
        data object Unsupported : Plan

        /** These models (ML Kit tags) have to be downloaded first — ask the user. */
        data class NeedsDownload(
            val sourceTag: String,
            val targetTag: String,
            val missing: List<String>,
        ) : Plan

        /** Every model is on the device: translate now. */
        data class Ready(val sourceTag: String, val targetTag: String) : Plan
    }

    /**
     * The models a pair needs, by language: each side that isn't English. A
     * German menu into English needs the German model; German into Croatian
     * needs both, because ML Kit translates non-English pairs through English.
     */
    fun requiredModels(sourceTag: String, targetTag: String): List<String> =
        listOf(sourceTag, targetTag).filter { it != ENGLISH_TAG }.distinct()

    /**
     * Decide the next step. [sourceTag] is what [identifyLanguage] said (or
     * the user picked), [downloaded] the ML Kit tags already on the device.
     */
    fun plan(sourceTag: String?, target: TranslateLanguage, downloaded: Set<String>): Plan {
        val targetTag = TranslationCatalog.mlKitTag(target) ?: return Plan.Unsupported
        val source = sourceTag?.let(::supportedTag) ?: return Plan.UnknownSource
        if (source == targetTag) return Plan.AlreadyInTarget(target)
        val missing = requiredModels(source, targetTag).filterNot { it in downloaded }
        return if (missing.isEmpty()) {
            Plan.Ready(source, targetTag)
        } else {
            Plan.NeedsDownload(source, targetTag, missing)
        }
    }

    /**
     * Every language ML Kit can translate from, as (tag, English name) pairs
     * sorted by name — the "Translate from" picker for text the identifier
     * could not place.
     */
    fun sourceLanguages(): List<Pair<String, String>> =
        runCatching { MlKitLanguage.getAllLanguages() }.getOrDefault(emptyList())
            .map { it to TranslationCatalog.label(it) }
            .sortedBy { it.second }

    /**
     * Whether the ML Kit translator component can be talked to at all. Cheap
     * and synchronous, so the row can render disabled rather than missing.
     */
    fun isAvailable(): Boolean = runCatching {
        // Touching the model manager is the cheapest "ML Kit booted" probe: it
        // throws rather than returning null when ML Kit could not initialise.
        RemoteModelManager.getInstance()
        true
    }.getOrDefault(false)

    /**
     * The language [text] is written in, as an ML Kit translation tag, or
     * null when the on-device identifier is unsure or names a language ML Kit
     * cannot translate (a romanised "ja-Latn", say). Runs entirely on the
     * device — the bundled language-id model, no download, no network.
     */
    suspend fun identifyLanguage(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val identifier = attempt {
            LanguageIdentification.getClient(
                LanguageIdentificationOptions.Builder()
                    .setConfidenceThreshold(IDENTIFICATION_CONFIDENCE)
                    .build()
            )
        }.getOrNull() ?: return null
        return try {
            val tag = attempt { identifier.identifyLanguage(trimmed).awaitResult() }.getOrNull()
            tag?.takeUnless { it == LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG }
                ?.let(::supportedTag)
        } finally {
            runCatching { identifier.close() }
        }
    }

    /** The ML Kit translation models already on the device, by tag. */
    suspend fun downloadedModels(): Set<String> = attempt {
        RemoteModelManager.getInstance()
            .getDownloadedModels(TranslateRemoteModel::class.java)
            .awaitResult()
            .map { it.language }
            .toSet()
    }.getOrDefault(emptySet())

    /**
     * Fetch the models a pair needs. The **only** call in the app that makes
     * ML Kit download anything, and the UI makes it only after the user agreed
     * to the download it describes. Throws [TranslationUnavailable] with
     * [DOWNLOAD_FAILED_MESSAGE] when the download does not complete.
     */
    suspend fun download(sourceTag: String, targetTag: String) {
        val translator = client(sourceTag, targetTag)
        try {
            attempt { translator.downloadModelIfNeeded().awaitResult() }
                .onFailure { throw TranslationUnavailable(DOWNLOAD_FAILED_MESSAGE) }
        } finally {
            runCatching { translator.close() }
        }
    }

    /**
     * Translate [text] on device with installed models only — never a
     * download, so a missing model can only ever be fetched through
     * [download]. Throws [TranslationUnavailable] when this device cannot do
     * it; returns "" for blank input without touching ML Kit.
     */
    suspend fun translate(text: String, sourceTag: String, targetTag: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        if (supportedTag(targetTag) == null || supportedTag(sourceTag) == null) {
            throw TranslationUnavailable(NOT_AVAILABLE_MESSAGE)
        }
        val translator = client(sourceTag, targetTag)
        try {
            val translated = attempt { translator.translate(trimmed).awaitResult() }
                .getOrElse { throw TranslationUnavailable(NOT_AVAILABLE_MESSAGE) }
                .trim()
            if (translated.isEmpty()) throw TranslationUnavailable(NOTHING_TO_TRANSLATE_MESSAGE)
            return translated
        } finally {
            runCatching { translator.close() }
        }
    }

    /** ML Kit's own tag for [tag], or null when it has no model for it. */
    private fun supportedTag(tag: String): String? =
        runCatching { MlKitLanguage.fromLanguageTag(tag) }.getOrNull()

    private fun client(sourceTag: String, targetTag: String): Translator = runCatching {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(sourceTag)
                .setTargetLanguage(targetTag)
                .build()
        )
    }.getOrElse { throw TranslationUnavailable(NOT_AVAILABLE_MESSAGE) }

    /**
     * `runCatching` that lets cancellation through: a row that leaves the
     * screen mid-translation cancels its coroutine, and that must not be
     * reported as "this pair isn't available".
     */
    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    /** `Task` → coroutine, without pulling in the coroutines-play-services artefact. */
    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
        addOnFailureListener { error -> if (cont.isActive) cont.resumeWithException(error) }
        addOnCanceledListener { if (cont.isActive) cont.cancel() }
    }
}
