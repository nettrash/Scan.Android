package me.nettrash.scan.ui.scanner

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.nettrash.scan.data.db.ScanRecord
import me.nettrash.scan.data.db.ScanRecordDao
import me.nettrash.scan.data.settings.SettingsRepository
import me.nettrash.scan.scanner.RecognizedTextBlock
import me.nettrash.scan.scanner.ScannedCode
import me.nettrash.scan.scanner.Symbology
import me.nettrash.scan.widget.ScanEntry
import me.nettrash.scan.widget.ScanEntryDispatcher
import me.nettrash.scan.widget.ScanIntents
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

/**
 * "Scan a code" from the tile, the widget or a shortcut must land on a camera
 * the user can see — iOS Start Scanning's `revealCamera()`. The request
 * travels through [ScanEntryDispatcher]; the scanner's ViewModel closes what
 * it shows over the preview and leaves the bulk list alone.
 */
@RunWith(RobolectricTestRunner::class)
class ScannerRevealCameraTest {

    private val context: android.content.Context = RuntimeEnvironment.getApplication()

    /** Nothing here reads History; the scanner only ever inserts. */
    private class NoHistory : ScanRecordDao {
        override suspend fun insert(record: ScanRecord): Long = 0
        override suspend fun update(record: ScanRecord) = Unit
        override suspend fun delete(record: ScanRecord) = Unit
        override fun observeAll(): Flow<List<ScanRecord>> = flowOf(emptyList())
        override suspend fun getById(id: Long): ScanRecord? = null
        override suspend fun setFavourite(id: Long, favourite: Boolean) = Unit
        override suspend fun deleteAll() = Unit
    }

    private fun code(value: String) =
        ScannedCode(value = value, symbology = Symbology.QR, timestampMillis = 1_700_000_000_000)

    @Test fun scanLaunchClosesWhatCoversTheCameraAndKeepsTheBulkList() {
        val dispatcher = ScanEntryDispatcher()
        // A launch that happened before the screen existed is not a request
        // to it: a fresh screen has nothing to close.
        dispatcher.handle(ScanIntents.launchIntent(context, ScanEntry.Scanner))
        val viewModel = ScannerViewModel(NoHistory(), SettingsRepository(context), dispatcher)

        viewModel.bulk.start()
        viewModel.bulk.ingest(code("A"))
        viewModel.onScan(code("https://nettrash.me"), dedupe = false)
        viewModel.selectRecognizedText(RecognizedTextBlock("Menu"))
        assertThat(viewModel.state.value.lastScan?.value).isEqualTo("https://nettrash.me")

        // Another tab's launch leaves the scanner as it is.
        dispatcher.handle(ScanIntents.launchIntent(context, ScanEntry.RecentScans))
        ShadowLooper.idleMainLooper()
        assertThat(viewModel.state.value.lastScan).isNotNull()

        dispatcher.handle(ScanIntents.launchIntent(context, ScanEntry.Scanner))
        ShadowLooper.idleMainLooper()
        val state = viewModel.state.value
        assertThat(state.lastScan).isNull()
        assertThat(state.selectedText).isNull()
        assertThat(state.multiCodeChoices).isEmpty()
        assertThat(viewModel.bulk.entriesSnapshot().map { it.value }).containsExactly("A")
        assertThat(viewModel.bulk.isRunning).isTrue()
    }
}
