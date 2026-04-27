package me.nettrash.scan.ui.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nettrash.scan.data.db.ScanRecord
import me.nettrash.scan.data.db.ScanRecordDao
import me.nettrash.scan.scanner.ScannedCode
import javax.inject.Inject

/**
 * Backs ScannerScreen. Owns the latest scan, debounces duplicate decodes
 * (so the camera analyzer can fire at full frame-rate without spamming the
 * UI), and persists saved scans to Room.
 */
@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val dao: ScanRecordDao
) : ViewModel() {

    data class State(
        val lastScan: ScannedCode? = null,
        val importError: String? = null,
        val isDecodingImage: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val dedupeWindowMs = 1500L
    private var lastValue: String? = null
    private var lastValueAtMs: Long = 0L

    fun onScan(code: ScannedCode, dedupe: Boolean) {
        if (dedupe) {
            val now = System.currentTimeMillis()
            if (code.value == lastValue && now - lastValueAtMs < dedupeWindowMs) return
            lastValue = code.value
            lastValueAtMs = now
        } else {
            lastValue = code.value
            lastValueAtMs = System.currentTimeMillis()
        }
        _state.update { it.copy(lastScan = code, importError = null, isDecodingImage = false) }
    }

    fun decodingImage() {
        _state.update { it.copy(isDecodingImage = true, importError = null) }
    }

    fun onImportError(message: String) {
        _state.update { it.copy(isDecodingImage = false, importError = message) }
    }

    fun dismissResult() {
        _state.update { it.copy(lastScan = null) }
    }

    fun saveScan(notes: String?) {
        val code = _state.value.lastScan ?: return
        viewModelScope.launch {
            dao.insert(
                ScanRecord(
                    value = code.value,
                    symbology = code.symbology.displayName,
                    timestamp = code.timestampMillis,
                    notes = notes
                )
            )
        }
    }
}
