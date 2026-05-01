package me.nettrash.scan.ui.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nettrash.scan.data.db.ScanRecord
import me.nettrash.scan.data.db.ScanRecordDao
import me.nettrash.scan.data.settings.SettingsRepository
import me.nettrash.scan.data.settings.SettingsState
import me.nettrash.scan.scanner.ScannedCode
import javax.inject.Inject

/**
 * Backs ScannerScreen. Owns the latest scan, debounces duplicate decodes
 * (so the camera analyzer can fire at full frame-rate without spamming the
 * UI), persists saved scans to Room, and exposes the user's feedback /
 * continuous-scan preferences.
 */
@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val dao: ScanRecordDao,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    data class State(
        val lastScan: ScannedCode? = null,
        val importError: String? = null,
        val isDecodingImage: Boolean = false,
        /// Most recent code auto-saved while continuous-scan mode is
        /// on. Drives the on-screen banner so the user has feedback
        /// without a sheet interruption. `null` clears the banner.
        val lastContinuous: ScannedCode? = null,
        /// When the camera reports more than one code in a single
        /// frame, we don't auto-pick — we render numbered chips and
        /// let the user choose. The chooser stays up until the user
        /// taps a chip, taps to dismiss, or the frame goes back to
        /// having ≤ 1 code.
        val multiCodeChoices: List<ScannedCode> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /// Surfaced to the Composable so ScannerScreen can flip-flop the
    /// haptic / sound feedback and route to either the result sheet
    /// or the auto-save path without re-reading DataStore on every
    /// frame.
    val settings: StateFlow<SettingsState> =
        settingsRepo.state.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

    private val dedupeWindowMs = 1500L
    private var lastValue: String? = null
    private var lastValueAtMs: Long = 0L
    /// Value we last took action on (saved to history in
    /// continuous-scan mode, or surfaced via lastScan in normal
    /// mode). Cleared by [onBatch] when the camera frame is empty
    /// — i.e. when the user has visibly moved the camera off the
    /// code. This is what stops the same code being saved over and
    /// over while it sits in view; the older 1.5 s time window kept
    /// resetting and re-firing every dedupeWindow seconds.
    private var lastHandledValue: String? = null

    /// Multi-code analyzer hook. The MlKitAnalyzer in ScannerScreen
    /// passes *every* recognised code in the current frame here.
    /// Single-code path: route straight to [onScan]. Multi-code path:
    /// stash the list and let the UI render a chooser.
    fun onBatch(codes: List<ScannedCode>) {
        if (codes.isEmpty()) {
            // Frame is empty — clear the multi-code chooser if it
            // was up so the overlay doesn't linger after the user
            // moves the camera off.
            //
            // NOTE: deliberately does *not* clear `lastHandledValue`.
            // Same-value dedupe is now strict — once a value has
            // been handled, the only way to re-handle it is to
            // dismiss the result sheet (sheet mode) or the
            // continuous-scan banner (continuous mode). Pointing the
            // camera elsewhere and back is no longer enough; the
            // user has to explicitly say "I'm done with this scan"
            // first. This is what the user asked for: literal
            // same-value avoidance.
            if (_state.value.multiCodeChoices.isNotEmpty()) {
                _state.update { it.copy(multiCodeChoices = emptyList()) }
            }
            return
        }
        if (codes.size == 1) {
            // Drop the chooser if it was up and route normally.
            if (_state.value.multiCodeChoices.isNotEmpty()) {
                _state.update { it.copy(multiCodeChoices = emptyList()) }
            }
            onScan(codes.first(), dedupe = true)
            return
        }
        // ≥ 2 codes: hold for the user to pick. We deliberately do
        // *not* fire haptic / sound here; that happens once the user
        // commits to one of the choices.
        _state.update { it.copy(multiCodeChoices = codes) }
    }

    /// User tapped a chip in the multi-code chooser. Bypass the
    /// dedupe window — the user explicitly picked this — and clear
    /// both dedupe states so the chosen code goes through.
    fun pickFromChoices(code: ScannedCode) {
        lastValue = null
        lastValueAtMs = 0L
        lastHandledValue = null
        _state.update { it.copy(multiCodeChoices = emptyList()) }
        onScan(code, dedupe = false)
    }

    fun dismissMultiCodeChooser() {
        _state.update { it.copy(multiCodeChoices = emptyList()) }
    }

    fun onScan(code: ScannedCode, dedupe: Boolean) {
        if (dedupe) {
            // Primary dedupe: don't re-handle the *same value* until
            // the camera has visibly moved off the code (onBatch
            // clears `lastHandledValue` on an empty frame). Stops a
            // single code held in view from being saved every 1.5 s
            // in continuous mode or re-popping the sheet each time
            // the time window expired.
            if (code.value == lastHandledValue) return
            // Secondary safety net: a transient flip "code A → code
            // B → code A" (oscillating recogniser) would otherwise
            // keep re-saving once per pair of frames. Keep the
            // original 1.5 s time-window dedupe as a guard for
            // that pathology.
            val now = System.currentTimeMillis()
            if (code.value == lastValue && now - lastValueAtMs < dedupeWindowMs) return
            lastValue = code.value
            lastValueAtMs = now
        } else {
            lastValue = code.value
            lastValueAtMs = System.currentTimeMillis()
        }
        lastHandledValue = code.value
        if (settings.value.continuousScan) {
            // Auto-save and surface a banner instead of presenting
            // the result sheet. ScanRecord is constructed inline
            // so the persistence path doesn't depend on `lastScan`
            // — the user might dismiss the banner before Room's
            // insert finishes.
            viewModelScope.launch {
                dao.insert(
                    ScanRecord(
                        value = code.value,
                        symbology = code.symbology.displayName,
                        timestamp = code.timestampMillis,
                        notes = null,
                        isFavorite = false,
                    )
                )
            }
            _state.update {
                it.copy(
                    lastScan = null,
                    lastContinuous = code,
                    importError = null,
                    isDecodingImage = false,
                )
            }
        } else {
            _state.update {
                it.copy(
                    lastScan = code,
                    importError = null,
                    isDecodingImage = false,
                )
            }
        }
    }

    /// Tap target for the continuous-scan banner — open the result
    /// sheet for the most recently auto-saved code without re-running
    /// the recogniser.
    fun openLastContinuous() {
        val last = _state.value.lastContinuous ?: return
        _state.update { it.copy(lastScan = last) }
    }

    fun decodingImage() {
        _state.update { it.copy(isDecodingImage = true, importError = null) }
    }

    fun onImportError(message: String) {
        _state.update { it.copy(isDecodingImage = false, importError = message) }
    }

    fun dismissResult() {
        // Releasing the dedupe lock alongside the sheet dismissal
        // is the user-facing equivalent of "I'm done with this
        // scan" — the next sight of any code, including the one
        // just shown, registers as a fresh scan.
        lastHandledValue = null
        _state.update { it.copy(lastScan = null) }
    }

    /// Continuous-scan banner dismiss — same release-the-lock
    /// semantics as [dismissResult] but for the in-camera banner
    /// rather than the result sheet.
    fun dismissContinuousBanner() {
        lastHandledValue = null
        _state.update { it.copy(lastContinuous = null) }
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

    /**
     * Persist a scan that arrived via App-Links rather than the camera.
     * Direct DAO insert — bypasses the dedupe / continuous-scan / banner
     * machinery in [onScan] entirely so a deep-link save doesn't
     * accidentally show up in the continuous-scan banner or compete
     * with a live capture for the result sheet slot.
     */
    fun saveDeepLinkScan(code: ScannedCode, notes: String? = null) {
        viewModelScope.launch {
            dao.insert(
                ScanRecord(
                    value = code.value,
                    symbology = code.symbology.displayName,
                    timestamp = code.timestampMillis,
                    notes = notes,
                )
            )
        }
    }

    /**
     * Save the current scan as a favourited loyalty-card row. Tagged
     * via `notes = "Loyalty: <merchant>"` so the History search field
     * can find it by merchant name; favourited so it pins to the top
     * of the list. Mirrors iOS `saveAsLoyaltyCard` in
     * `PayloadActionsView.swift`.
     */
    fun saveAsLoyaltyCard(merchant: String) {
        val code = _state.value.lastScan ?: return
        val trimmed = merchant.trim()
        viewModelScope.launch {
            dao.insert(
                ScanRecord(
                    value = code.value,
                    // Override the symbology label when the user
                    // explicitly tags it as a loyalty card — this
                    // makes the History row read "Loyalty" instead
                    // of "EAN-13" and keeps the tagging legible.
                    symbology = "Loyalty",
                    timestamp = code.timestampMillis,
                    notes = if (trimmed.isEmpty()) "Loyalty" else "Loyalty: $trimmed",
                    isFavorite = true,
                )
            )
        }
    }
}
