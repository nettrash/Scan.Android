package me.nettrash.scan.ui.scanner

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.nettrash.scan.data.db.ScanRecord
import me.nettrash.scan.data.db.ScanRecordDao
import me.nettrash.scan.data.settings.SettingsRepository
import me.nettrash.scan.scanner.ScannedCode
import me.nettrash.scan.scanner.Symbology
import me.nettrash.scan.widget.ScanEntryDispatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

/**
 * The bulk sheet's "Save all to History" writes each collected code once:
 * saving the kept list again — a second tap, or after reopening the sheet —
 * adds only what was collected since, and reports that count ("N saved to
 * History"). The rule iOS's bulk sheet follows.
 */
@RunWith(RobolectricTestRunner::class)
class BulkSaveAllTest {

    private class RecordingHistory : ScanRecordDao {
        val inserted = mutableListOf<ScanRecord>()
        override suspend fun insert(record: ScanRecord): Long {
            inserted += record
            return inserted.size.toLong()
        }
        override suspend fun update(record: ScanRecord) = Unit
        override suspend fun delete(record: ScanRecord) = Unit
        override fun observeAll(): Flow<List<ScanRecord>> = flowOf(emptyList())
        override suspend fun getById(id: Long): ScanRecord? = null
        override suspend fun setFavourite(id: Long, favourite: Boolean) = Unit
        override suspend fun deleteAll() = Unit
    }

    private fun code(value: String) =
        ScannedCode(value = value, symbology = Symbology.QR, timestampMillis = 1_700_000_000_000)

    @Test fun savingAgainAddsOnlyTheNewCodes() {
        val history = RecordingHistory()
        val vm = ScannerViewModel(history, SettingsRepository(RuntimeEnvironment.getApplication()), ScanEntryDispatcher())
        ShadowLooper.idleMainLooper()
        val reported = mutableListOf<Int>()
        vm.bulk.start()
        vm.bulk.ingest(code("A"))
        vm.bulk.ingest(code("B"))

        vm.saveBulkToHistory { reported += it }
        ShadowLooper.idleMainLooper()
        assertThat(history.inserted.map { it.value }).containsExactly("A", "B").inOrder()
        assertThat(history.inserted.map { it.notes }).containsExactly("Bulk capture", "Bulk capture")
        assertThat(reported).containsExactly(2)
        assertThat(vm.bulk.hasUnsavedEntries).isFalse()

        // Nothing new: nothing written, nothing reported.
        vm.saveBulkToHistory { reported += it }
        ShadowLooper.idleMainLooper()
        assertThat(history.inserted).hasSize(2)
        assertThat(reported).containsExactly(2)

        // One more code: only it goes in, and the sheet says "1 saved".
        vm.bulk.ingest(code("A")) // a repeat sighting, already saved
        vm.bulk.ingest(code("C"))
        vm.saveBulkToHistory { reported += it }
        ShadowLooper.idleMainLooper()
        assertThat(history.inserted.map { it.value }).containsExactly("A", "B", "C").inOrder()
        assertThat(reported).containsExactly(2, 1).inOrder()
    }

    /** History that refuses every insert after the first [okInserts]. */
    private class FailingHistory(var okInserts: Int) : ScanRecordDao {
        val inserted = mutableListOf<ScanRecord>()
        override suspend fun insert(record: ScanRecord): Long {
            if (inserted.size >= okInserts) throw IllegalStateException("disk full")
            inserted += record
            return inserted.size.toLong()
        }
        override suspend fun update(record: ScanRecord) = Unit
        override suspend fun delete(record: ScanRecord) = Unit
        override fun observeAll(): Flow<List<ScanRecord>> = flowOf(emptyList())
        override suspend fun getById(id: Long): ScanRecord? = null
        override suspend fun setFavourite(id: Long, favourite: Boolean) = Unit
        override suspend fun deleteAll() = Unit
    }

    @Test fun aFailedSaveLeavesTheUnwrittenCodesForARetry() {
        val history = FailingHistory(okInserts = 1)
        val vm = ScannerViewModel(history, SettingsRepository(RuntimeEnvironment.getApplication()), ScanEntryDispatcher())
        ShadowLooper.idleMainLooper()
        val reported = mutableListOf<Int>()
        val failures = mutableListOf<String>()
        vm.bulk.start()
        vm.bulk.ingest(code("A"))
        vm.bulk.ingest(code("B"))
        vm.bulk.ingest(code("C"))

        // The second insert fails: A is in History, B and C are not — and
        // the failure reaches the sheet instead of escaping the scope.
        vm.saveBulkToHistory(onFailed = { failures += it }) { reported += it }
        ShadowLooper.idleMainLooper()
        assertThat(history.inserted.map { it.value }).containsExactly("A")
        assertThat(reported).containsExactly(1)
        assertThat(failures).containsExactly("disk full")
        assertThat(vm.bulk.hasUnsavedEntries).isTrue()

        // The retry writes exactly the two that were left, once each.
        history.okInserts = Int.MAX_VALUE
        vm.saveBulkToHistory(onFailed = { failures += it }) { reported += it }
        ShadowLooper.idleMainLooper()
        assertThat(history.inserted.map { it.value }).containsExactly("A", "B", "C").inOrder()
        assertThat(reported).containsExactly(1, 2).inOrder()
        assertThat(failures).hasSize(1)
        assertThat(vm.bulk.hasUnsavedEntries).isFalse()
    }
}
