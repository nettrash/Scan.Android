package me.nettrash.scan.data.payload

import android.os.LocaleList
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * Catalog, planning and graceful-degradation coverage for the ML Kit
 * translate wrapper. Identification and translation themselves need ML Kit's
 * native runtime, so what is asserted here is the contract the UI depends on:
 * the decision of what a Translate tap does next (and in particular that a
 * missing model is *asked about*, never fetched on the side), honest "not
 * available" answers, and never a crash.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationKitTest {

    private val english = TranslationCatalog.english
    private val croatian = TranslationCatalog.languages.first { it.identifier == "hr" }
    private val serbian = TranslationCatalog.languages.first { it.identifier == "sr-Latn" }

    // ---- Catalog -----------------------------------------------------------

    @Test fun catalogCarriesTheRegionalLanguages() {
        assertThat(TranslationCatalog.languages).hasSize(30)
        val ids = TranslationCatalog.languages.map { it.identifier }
        assertThat(ids).containsAtLeast("sr-Latn", "cs", "sk", "ru", "de")
        // The names both systems give these languages, not "Türkiye Turkish" / "Slovene".
        assertThat(TranslationCatalog.languages.first { it.identifier == "tr" }.label).isEqualTo("Turkish")
        assertThat(TranslationCatalog.languages.first { it.identifier == "sl" }.label).isEqualTo("Slovenian")
    }

    @Test fun catalogIsTheIosListInTheIosOrder() {
        assertThat(TranslationCatalog.languages.map { "${it.identifier}=${it.label}" }).containsExactly(
            "en=English",
            "de=German",
            "fr=French",
            "es=Spanish",
            "it=Italian",
            "pt-BR=Portuguese (Brazil)",
            "nl=Dutch",
            "pl=Polish",
            "cs=Czech",
            "sk=Slovak",
            "sr-Latn=Serbian (Latin)",
            "hr=Croatian",
            "sl=Slovenian",
            "bs=Bosnian",
            "bg=Bulgarian",
            "ro=Romanian",
            "el=Greek",
            "hu=Hungarian",
            "ru=Russian",
            "uk=Ukrainian",
            "tr=Turkish",
            "ar=Arabic",
            "he=Hebrew",
            "hi=Hindi",
            "zh-Hans=Chinese (Simplified)",
            "ja=Japanese",
            "ko=Korean",
            "id=Indonesian",
            "th=Thai",
            "vi=Vietnamese",
        ).inOrder()
    }

    @Test fun everyLanguageHasADisplayLabel() {
        TranslationCatalog.languages.forEach {
            assertThat(it.label).isNotEmpty()
            assertThat(it.id).isEqualTo(it.identifier)
        }
    }

    @Test fun targetsAreTheLanguagesMlKitCanProduce() {
        val targets = TranslationCatalog.targets.map { it.identifier }
        assertThat(targets).hasSize(28)
        assertThat(targets).containsNoneOf("sr-Latn", "bs")
        assertThat(targets).containsAtLeast("en", "de", "hr", "zh-Hans", "pt-BR")
    }

    @Test fun settingsValueResolvesOrFallsBackToDeviceLanguage() {
        assertThat(TranslationCatalog.languageFor("cs")?.label).isEqualTo("Czech")
        assertThat(TranslationCatalog.languageFor(TranslationCatalog.SYSTEM_TAG)).isNotNull()
        assertThat(TranslationCatalog.languageFor("")).isEqualTo(TranslationCatalog.deviceLanguage)
        assertThat(TranslationCatalog.languageFor("xx")).isNull()
        // iOS `language(forStored:)`: something no longer in the catalog
        // means the device language, never "no target at all".
        assertThat(TranslationCatalog.resolved("xx")).isEqualTo(TranslationCatalog.deviceLanguage)
        assertThat(TranslationCatalog.resolved("cs").identifier).isEqualTo("cs")
    }

    @Test fun systemLocalesMapOntoCatalogEntriesLikeIos() {
        fun entry(tag: String) = TranslationCatalog.entryFor(Locale.forLanguageTag(tag))?.identifier
        assertThat(entry("de-AT")).isEqualTo("de")
        // Same language, other region: Brazilian Portuguese rather than English.
        assertThat(entry("pt-PT")).isEqualTo("pt-BR")
        assertThat(entry("pt-BR")).isEqualTo("pt-BR")
        // Traditional Chinese still lands on the one Chinese entry there is.
        assertThat(entry("zh-TW")).isEqualTo("zh-Hans")
        assertThat(entry("zh-Hans-CN")).isEqualTo("zh-Hans")
        // Java's withdrawn ISO 639 codes.
        assertThat(entry("iw")).isEqualTo("he")
        assertThat(entry("in")).isEqualTo("id")
        // Not in the catalog at all.
        assertThat(entry("sv")).isNull()
    }

    @Test fun mlKitTagMapsItsOwnIdentifiersAndRejectsTheRest() {
        assertThat(TranslationCatalog.mlKitTag(english)).isEqualTo("en")
        assertThat(TranslationCatalog.mlKitTag(TranslateLanguage("pt-BR", "Portuguese (Brazil)"))).isEqualTo("pt")
        assertThat(TranslationCatalog.mlKitTag(TranslateLanguage("zh-Hans", "Chinese (Simplified)"))).isEqualTo("zh")
        // ML Kit ships no on-device model for these; the row must say so.
        assertThat(TranslationCatalog.mlKitTag(serbian)).isNull()
        assertThat(TranslationCatalog.mlKitTag(TranslateLanguage("bs", "Bosnian"))).isNull()
    }

    @Test fun labelsNameCatalogAndNonCatalogSources() {
        assertThat(TranslationCatalog.label("pt")).isEqualTo("Portuguese (Brazil)")
        assertThat(TranslationCatalog.label("zh")).isEqualTo("Chinese (Simplified)")
        // A Swedish menu is a fine source even though Swedish isn't a target.
        assertThat(TranslationCatalog.label("sv")).isEqualTo("Swedish")
    }

    // ---- Planning ----------------------------------------------------------

    @Test fun everyNonEnglishSideNeedsItsModelAndEnglishNeedsNone() {
        // ML Kit's models are language-to-English packages, so English never
        // has to be downloaded and a pair through English needs both sides.
        assertThat(ScanTranslator.requiredModels("de", "en")).containsExactly("de")
        assertThat(ScanTranslator.requiredModels("en", "ja")).containsExactly("ja")
        assertThat(ScanTranslator.requiredModels("de", "hr")).containsExactly("de", "hr").inOrder()
    }

    @Test fun installedModelsTranslateAtOnce() {
        assertThat(ScanTranslator.plan("de", english, downloaded = setOf("de")))
            .isEqualTo(ScanTranslator.Plan.Ready(sourceTag = "de", targetTag = "en"))
    }

    @Test fun missingModelsAreAskedAboutNotFetched() {
        assertThat(ScanTranslator.plan("de", english, downloaded = emptySet()))
            .isEqualTo(ScanTranslator.Plan.NeedsDownload("de", "en", missing = listOf("de")))
        // Only what is actually missing is named in the prompt.
        assertThat(ScanTranslator.plan("de", croatian, downloaded = setOf("de")))
            .isEqualTo(ScanTranslator.Plan.NeedsDownload("de", "hr", missing = listOf("hr")))
    }

    @Test fun textAlreadyInTheTargetIsItsOwnTranslation() {
        assertThat(ScanTranslator.plan("en", english, downloaded = emptySet()))
            .isEqualTo(ScanTranslator.Plan.AlreadyInTarget(english))
    }

    @Test fun unknownOrUnreadableSourcesAskForTheLanguage() {
        assertThat(ScanTranslator.plan(null, english, downloaded = setOf("en")))
            .isEqualTo(ScanTranslator.Plan.UnknownSource)
        // Serbian has no ML Kit model, so a Serbian sign can't be a source.
        assertThat(ScanTranslator.plan("sr", english, downloaded = setOf("en")))
            .isEqualTo(ScanTranslator.Plan.UnknownSource)
    }

    @Test fun targetsMlKitCannotProduceAreUnsupported() {
        assertThat(ScanTranslator.plan("de", serbian, downloaded = setOf("de", "en")))
            .isEqualTo(ScanTranslator.Plan.Unsupported)
    }

    @Test fun sourceLanguagesCoverMoreThanTheTargetCatalog() {
        val sources = ScanTranslator.sourceLanguages()
        assertThat(sources.map { it.first }).containsAtLeast("sv", "de", "en")
        assertThat(sources.map { it.second }).isInOrder()
    }

    // ---- Graceful degradation ----------------------------------------------

    @Test fun translateRejectsEmptyTextWithoutTouchingTheModel() = runBlocking {
        assertThat(ScanTranslator.translate("   ", sourceTag = "de", targetTag = "en")).isEmpty()
    }

    @Test fun unsupportedPairReportsUnavailable() = runBlocking {
        val failure = runCatching {
            ScanTranslator.translate("Dobar dan", sourceTag = "hr", targetTag = "sr")
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(ScanTranslator.TranslationUnavailable::class.java)
        assertThat(failure).hasMessageThat().isEqualTo(ScanTranslator.NOT_AVAILABLE_MESSAGE)
    }

    @Test fun blankTextHasNoLanguage() = runBlocking {
        assertThat(ScanTranslator.identifyLanguage("   ")).isNull()
    }

    // ---- Device language (iOS SafetyParityTests) -----------------------------

    @Test fun theDeviceLanguageIsOneMlKitCanTranslateInto() {
        fun resolved(vararg tags: String) = TranslationCatalog.deviceLanguage(tags.map(Locale::forLanguageTag))?.identifier
        assertThat(resolved("sr-Latn-RS", "en-US")).isEqualTo("en")
        assertThat(resolved("bs-BA", "de-DE")).isEqualTo("de")
        assertThat(resolved("sr-RS", "hr-HR")).isEqualTo("hr")
        assertThat(resolved("sr-Latn-RS", "sv-SE")).isNull()
        assertThat(resolved("cs-CZ")).isEqualTo("cs")
        fun label(vararg tags: String) = TranslationCatalog.deviceLanguageLabel(tags.map(Locale::forLanguageTag))
        assertThat(label("sr-Latn-RS", "en-US")).isEqualTo("Device language (English — Serbian (Latin) isn't available)")
        assertThat(label("bs-BA", "de-DE")).isEqualTo("Device language (German — Bosnian isn't available)")
        assertThat(label("sv-SE", "bs-BA")).isEqualTo("Device language (English — Bosnian isn't available)")
        assertThat(label("de-AT")).isEqualTo("Device language (German)")
        assertThat(label("sv-SE")).isEqualTo("Device language (English)")
    }

    @Test fun aSerbianLatinPhoneTranslatesIntoItsNextLanguage() {
        val previous = LocaleList.getDefault()
        val previousLocale = Locale.getDefault()
        try {
            LocaleList.setDefault(LocaleList.forLanguageTags("sr-Latn-RS,en-US"))
            val target = TranslationCatalog.resolved(TranslationCatalog.SYSTEM_TAG)
            assertThat(target.identifier).isEqualTo("en")
            assertThat(ScanTranslator.plan("de", target, downloaded = setOf("de")))
                .isNotEqualTo(ScanTranslator.Plan.Unsupported)
            LocaleList.setDefault(LocaleList.forLanguageTags("cs-CZ"))
            assertThat(TranslationCatalog.resolved(TranslationCatalog.SYSTEM_TAG).identifier).isEqualTo("cs")
        } finally {
            LocaleList.setDefault(previous)
            Locale.setDefault(previousLocale)
        }
    }

    // ---- When the Translate row shows (iOS `isTranslatable`) -----------------

    @Test fun translateIsOfferedForTheSameTextAsOnIos() {
        fun text(value: String) = TranslationCatalog.isTranslatable(ScanPayload.Text(value))
        assertThat(text("สวัสดีครับ")).isFalse()
        assertThat(text("👍🏽👍🏽")).isFalse()
        assertThat(text("  short  \n")).isFalse()
        assertThat(text("cafés ok")).isTrue()
        assertThat(text("Guten Tag")).isTrue()
        assertThat(TranslationCatalog.isTranslatable(ScanPayload.Url("https://example.com/"))).isTrue()
        assertThat(TranslationCatalog.isTranslatable(ScanPayload.Email("a@example.com", "Hi", ""))).isFalse()
        assertThat(TranslationCatalog.isTranslatable(ScanPayload.Email("a@example.com", null, "Hallo"))).isTrue()
    }
}
