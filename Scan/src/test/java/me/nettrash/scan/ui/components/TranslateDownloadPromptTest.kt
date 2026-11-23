package me.nettrash.scan.ui.components

import com.google.common.truth.Truth.assertThat
import me.nettrash.scan.data.payload.ScanTranslator
import me.nettrash.scan.ui.settings.insightsFooter
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The consent dialog in front of the Translate feature's network traffic: it
 * must name what is downloaded, how big it is, that it comes from Google, what
 * else Google's ML Kit sends Google (an installation ID, a settings fetch,
 * usage statistics) and that the scanned text is never sent — and say so when
 * the phone is on mobile data.
 */
@RunWith(RobolectricTestRunner::class)
class TranslateDownloadPromptTest {

    private val germanToCroatian = ScanTranslator.Plan.NeedsDownload("de", "hr", missing = listOf("de", "hr"))
    private val swedishToEnglish = ScanTranslator.Plan.NeedsDownload("sv", "en", missing = listOf("sv"))

    @Test fun promptNamesTheModelsTheSizeAndTheSource() {
        val prompt = downloadPrompt(germanToCroatian, NetworkHint.UNMETERED)
        assertThat(prompt.title).isEqualTo("Download translation models?")
        assertThat(prompt.body).contains("Translating German into Croatian")
        assertThat(prompt.body).contains("German and Croatian language models (about 30 MB each)")
        assertThat(prompt.body).contains("from Google once; after that, this pair works offline.")
        assertThat(prompt.body).contains("The text you scanned is never sent")
        assertThat(prompt.body).doesNotContain("Only the language models are downloaded")
        assertThat(prompt.body).doesNotContain("mobile data")
        assertThat(prompt.confirmLabel).isEqualTo("Download")
    }

    @Test fun promptSaysWhatElseMlKitSendsGoogle() {
        val body = downloadPrompt(swedishToEnglish, NetworkHint.UNMETERED).body
        assertThat(body).contains("registers an app-installation ID with Google (Firebase)")
        assertThat(body).contains("fetches its translation settings at most about twice a day")
        assertThat(body).contains("device language and region, time zone, and Android and app version")
        assertThat(body).contains("usage and diagnostic statistics, including the language pair")
    }

    @Test fun theSettingsFooterSaysTheSame() {
        val footer = insightsFooter(assistantEnabled = true)
        assertThat(footer).contains("Scan's own safety check, explanations and translations")
        assertThat(footer).contains("works offline once downloaded")
        assertThat(footer).contains("registers an app-installation ID with Google (Firebase)")
        assertThat(footer).contains("usage and diagnostic statistics, including the language pair")
        assertThat(footer).endsWith("never the text you scanned.")
    }

    @Test fun singleModelIsSingular() {
        val prompt = downloadPrompt(swedishToEnglish, NetworkHint.UNKNOWN)
        assertThat(prompt.title).isEqualTo("Download a translation model?")
        assertThat(prompt.body).contains("Translating Swedish into English")
        assertThat(prompt.body).contains("Swedish language model (about 30 MB)")
        assertThat(prompt.body).contains("downloads it from Google")
    }

    @Test fun mobileDataAndOfflineAreSpelledOut() {
        val metered = downloadPrompt(germanToCroatian, NetworkHint.METERED)
        assertThat(metered.body).endsWith("You're on mobile data.")
        assertThat(metered.confirmLabel).isEqualTo("Download over mobile data")

        val offline = downloadPrompt(germanToCroatian, NetworkHint.OFFLINE)
        assertThat(offline.body).contains("You're offline right now")
        assertThat(offline.confirmLabel).isEqualTo("Download")
    }

    @Test fun languageListsReadNaturally() {
        assertThat(listOfLanguages(emptyList())).isEmpty()
        assertThat(listOfLanguages(listOf("de"))).isEqualTo("German")
        assertThat(listOfLanguages(listOf("de", "en"))).isEqualTo("German and English")
        assertThat(listOfLanguages(listOf("sv", "de", "en"))).isEqualTo("Swedish, German and English")
    }
}
