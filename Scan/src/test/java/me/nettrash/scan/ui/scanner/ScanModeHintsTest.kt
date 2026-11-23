package me.nettrash.scan.ui.scanner

import com.google.common.truth.Truth.assertThat
import me.nettrash.scan.data.settings.ScanModePreference
import org.junit.Test

/**
 * The Scan tab's modes as stored and as explained under the picker. Pins the
 * same strings as iOS `ScannerQuickEntryTests`: the stored keys and labels
 * are shared, so are the Codes and Bulk hints word for word; the Live Text
 * hint says what this port does — a list of recognised blocks to tap, where
 * iOS has VisionKit's highlights and a frozen frame — and ends on the same
 * sentence about codes.
 */
class ScanModeHintsTest {

    @Test fun storedKeysAndLabelsAreTheSharedOnes() {
        assertThat(ScanModePreference.entries.map { it.key })
            .containsExactly("codes", "text", "bulk").inOrder()
        assertThat(ScanModePreference.entries.map { it.label })
            .containsExactly("Codes", "Live Text", "Bulk").inOrder()
    }

    @Test fun liveTextHintSaysWhatThisPortDoes() {
        assertThat(ScanModePreference.TEXT.footnote).isEqualTo(
            "Tap a recognised text block to copy, share or translate it. " +
                "A single code is read straight away; with several, tap one."
        )
        // The card offers Copy / Share / Translate; there is no Look Up and
        // no word-level selection on the live camera here.
        assertThat(ScanModePreference.TEXT.footnote.lowercase()).doesNotContain("look up")
        assertThat(ScanModePreference.TEXT.footnote.lowercase()).doesNotContain("words")
        assertThat(ScanModePreference.TEXT.footnote)
            .endsWith(" A single code is read straight away; with several, tap one.")
    }

    @Test fun codesAndBulkHintsMatchIos() {
        assertThat(ScanModePreference.CODES.footnote).isEqualTo("Point at a barcode or QR to decode it.")
        assertThat(ScanModePreference.BULK.footnote)
            .isEqualTo("Keep scanning: every distinct code is collected and counted.")
    }
}
