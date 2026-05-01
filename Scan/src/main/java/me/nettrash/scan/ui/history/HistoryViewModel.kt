package me.nettrash.scan.ui.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.nettrash.scan.data.HistoryCsv
import me.nettrash.scan.data.db.ScanRecord
import me.nettrash.scan.data.db.ScanRecordDao
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: ScanRecordDao
) : ViewModel() {

    val records: StateFlow<List<ScanRecord>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun delete(record: ScanRecord) {
        viewModelScope.launch { dao.delete(record) }
    }

    fun update(record: ScanRecord) {
        viewModelScope.launch { dao.update(record) }
    }

    /// Star / unstar — uses the targeted UPDATE query so we don't
    /// have to round-trip the entire row.
    fun toggleFavourite(record: ScanRecord) {
        viewModelScope.launch { dao.setFavourite(record.id, !record.isFavorite) }
    }

    /// Synchronous wrapper around [HistoryCsv.write]. The export is
    /// fast for any reasonable history size (CSV serialisation of a
    /// few thousand rows takes single-digit milliseconds), so doing
    /// it on the calling thread is fine and avoids the share-sheet
    /// race where the URI would not yet be valid.
    fun exportCsvUri(records: List<ScanRecord>): android.net.Uri =
        HistoryCsv.write(context, records)
}
