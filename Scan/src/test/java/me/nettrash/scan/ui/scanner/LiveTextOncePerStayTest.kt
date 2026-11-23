package me.nettrash.scan.ui.scanner

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.nettrash.scan.data.db.ScanRecord
import me.nettrash.scan.data.db.ScanRecordDao
import me.nettrash.scan.data.settings.ScanModePreference
import me.nettrash.scan.data.settings.SettingsRepository
import me.nettrash.scan.scanner.RecognizedTextBlock
import me.nettrash.scan.scanner.ScannedCode
import me.nettrash.scan.scanner.Symbology
import me.nettrash.scan.scanner.TextFrame
import me.nettrash.scan.testutil.awaitWhilePumpingMainLooper
import me.nettrash.scan.widget.ScanEntryDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

/**
 * Live Text opens a code once per stay in the frame (iOS
 * `VisionTextScanner.openedItemIDs`): dismissing the sheet of a loyalty QR
 * printed beside a menu hands the menu back, instead of the next frame
 * re-opening the QR. Codes mode keeps re-handling a code after its sheet is
 * dismissed, which is its documented design. Time runs on the ViewModel's
 * injectable clock, with frames every 100 ms like a real camera.
 */
@RunWith(RobolectricTestRunner::class)
class LiveTextOncePerStayTest {

    private class NoHistory : ScanRecordDao {
        override suspend fun insert(record: ScanRecord): Long = 0
        override suspend fun update(record: ScanRecord) = Unit
        override suspend fun delete(record: ScanRecord) = Unit
        override fun observeAll(): Flow<List<ScanRecord>> = flowOf(emptyList())
        override suspend fun getById(id: Long): ScanRecord? = null
        override suspend fun setFavourite(id: Long, favourite: Boolean) = Unit
        override suspend fun deleteAll() = Unit
    }

    private val context: android.content.Context = RuntimeEnvironment.getApplication()
    private var clock = 1_700_000_000_000L
    private lateinit var vm: ScannerViewModel

    private val menuQr = "https://example.org/menu"
    private val otherQr = "https://example.org/stamp-card"
    private val blocks = listOf(RecognizedTextBlock("Cafe Menu"), RecognizedTextBlock("Bitte zahlen an der Kasse"))

    @Before fun setUp() {
        vm = ScannerViewModel(NoHistory(), SettingsRepository(context), ScanEntryDispatcher())
        ShadowLooper.idleMainLooper()
        vm.now = { clock }
    }

    @After fun restoreStoredMode() {
        // applyScanMode persists the mode; leave the shared store as found
        // (after any write still queued behind the main looper).
        awaitWhilePumpingMainLooper { SettingsRepository(context).setScanMode(ScanModePreference.CODES) }
    }

    private fun frame(vararg values: String) = TextFrame(
        blocks = blocks,
        codes = values.map { ScannedCode(value = it, symbology = Symbology.QR, timestampMillis = clock) },
    )

    /** Camera frames every 100 ms for [ms], each showing [values]. */
    private fun frames(ms: Long, vararg values: String) {
        val end = clock + ms
        while (clock < end) {
            clock += 100
            vm.onTextFrame(frame(*values))
        }
    }

    private val shown: String? get() = vm.state.value.lastScan?.value

    /** The QR opens, the user reads its sheet for 2 s and dismisses it. */
    private fun openAndDismiss() {
        vm.onTextFrame(frame(menuQr))
        assertThat(shown).isEqualTo(menuQr)
        frames(2_000, menuQr)
        vm.dismissResult()
    }

    @Test fun aDismissedCodeStillInViewLeavesTheTextToTheUser() {
        openAndDismiss()
        frames(5_000, menuQr)
        assertThat(shown).isNull()

        // The user taps the menu text the QR sits beside; it stays up.
        vm.selectRecognizedText(blocks[1])
        frames(25_000, menuQr) // to 30 s after the dismissal
        assertThat(shown).isNull()
        assertThat(vm.state.value.selectedText).isEqualTo("Bitte zahlen an der Kasse")
    }

    @Test fun dismissingRightAwayDoesNotBringItBackEither() {
        vm.onTextFrame(frame(menuQr))
        vm.dismissResult()
        // Past the 1.5 s same-value window that used to re-open it.
        frames(3_000, menuQr)
        assertThat(shown).isNull()
    }

    @Test fun aCodeThatLeftTheFrameOpensAgain() {
        openAndDismiss()
        frames(1_500) // the QR is out of view
        vm.onTextFrame(frame(menuQr))
        assertThat(shown).isEqualTo(menuQr)
    }

    @Test fun aFrameThatMissesTheCodeIsStillTheSameStay() {
        openAndDismiss()
        clock += 100
        vm.onTextFrame(frame()) // one frame where recognition dropped it
        frames(200, menuQr)
        assertThat(shown).isNull()
    }

    @Test fun pickingACodeAlreadyOpenedThisStayOpensIt() {
        openAndDismiss()
        frames(500, menuQr, otherQr)
        assertThat(vm.state.value.multiCodeChoices.map { it.value }).containsExactly(menuQr, otherQr)

        vm.pickFromChoices(vm.state.value.multiCodeChoices.first())
        assertThat(shown).isEqualTo(menuQr)

        // The pick was this stay's opening: dismissed, it stays closed.
        vm.dismissResult()
        frames(3_000, menuQr)
        assertThat(shown).isNull()
    }

    @Test fun aNewSessionStartsFresh() {
        openAndDismiss()
        frames(1_000, menuQr)
        vm.onCameraStopped() // the screen went away and came back
        vm.onTextFrame(frame(menuQr))
        assertThat(shown).isEqualTo(menuQr)

        vm.dismissResult()
        frames(2_000, menuQr)
        vm.applyScanMode(ScanModePreference.TEXT) // re-entering Live Text
        vm.onTextFrame(frame(menuQr))
        assertThat(shown).isEqualTo(menuQr)
    }

    // Regression guard: Codes mode re-handles a code after its sheet is dismissed.
    @Test fun codesModeStillReopensAfterDismissal() {
        val qr = ScannedCode(value = menuQr, symbology = Symbology.QR, timestampMillis = clock)
        vm.onBatch(listOf(qr))
        assertThat(shown).isEqualTo(menuQr)
        vm.dismissResult()
        clock += 1_600
        vm.onBatch(listOf(qr))
        assertThat(shown).isEqualTo(menuQr)
    }
}
