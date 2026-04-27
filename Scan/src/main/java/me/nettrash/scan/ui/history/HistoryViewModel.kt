package me.nettrash.scan.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.nettrash.scan.data.db.ScanRecord
import me.nettrash.scan.data.db.ScanRecordDao
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
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
}
