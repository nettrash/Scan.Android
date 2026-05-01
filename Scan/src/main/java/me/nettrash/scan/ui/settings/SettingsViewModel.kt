package me.nettrash.scan.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.nettrash.scan.data.settings.SettingsRepository
import me.nettrash.scan.data.settings.SettingsState
import javax.inject.Inject

/**
 * Owns the Settings screen's state. A thin shim over [SettingsRepository]
 * — Compose `collectAsState`s the StateFlow, the toggle handlers fire
 * suspend writes back via the repository.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository
) : ViewModel() {

    val state: StateFlow<SettingsState> =
        repo.state.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

    fun setHapticOnScan(value: Boolean) {
        viewModelScope.launch { repo.setHapticOnScan(value) }
    }
    fun setSoundOnScan(value: Boolean) {
        viewModelScope.launch { repo.setSoundOnScan(value) }
    }
    fun setContinuousScan(value: Boolean) {
        viewModelScope.launch { repo.setContinuousScan(value) }
    }
}
