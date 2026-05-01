package me.nettrash.scan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.nettrash.scan.data.settings.SettingsRepository
import me.nettrash.scan.data.settings.SettingsState
import me.nettrash.scan.ui.deeplink.DeepLinkDispatcher
import me.nettrash.scan.ui.share.ShareIntakeDispatcher
import javax.inject.Inject

/**
 * Cross-screen state owned at the bottom-nav root. Three strands:
 *
 *  - Settings flow ([SettingsRepository.state]) — drives the
 *    What's-New gate, which has to be checked *before* any tab has
 *    been mounted.
 *  - Pending Universal/App-Links payload from [DeepLinkDispatcher] (1.5).
 *  - Pending share-intake result from [ShareIntakeDispatcher] (1.6).
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val deepLinks: DeepLinkDispatcher,
    private val shareIntake: ShareIntakeDispatcher,
) : ViewModel() {

    val state: StateFlow<SettingsState> =
        repo.state.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

    /// Pending App-Links payload — value + optional symbology
    /// (`https://nettrash.me/scan/<base64url>?t=<symbology>`). The
    /// symbology is dropped through `Symbology.UNKNOWN` for vanilla
    /// shareable URLs minted before 1.6.
    val pendingDeepLink: StateFlow<me.nettrash.scan.ui.deeplink.DeepLink.Payload?> = deepLinks.pending

    /// Share-intake state — `Idle` when nothing pending, `Loading`
    /// while the decoder runs, `Ready(codes)` on success, `Failed`
    /// on whole-batch failure. MainScreen presents the share result
    /// sheet whenever this is non-Idle.
    val shareIntakeState: StateFlow<ShareIntakeDispatcher.State> = shareIntake.state

    /**
     * Suspend until DataStore has actually delivered the first
     * settings snapshot, then return it.
     *
     * Why this exists: [state] is a `StateFlow` with an initial
     * value of `SettingsState()` (all defaults, including
     * `lastSeenVersion = ""`). DataStore reads asynchronously, so
     * any composable that gates on `state.lastSeenVersion` at
     * first composition sees the *default* "" — not the actual
     * stored value. The What's-New gate previously triggered on
     * that default and showed the sheet on every launch, even
     * after the user had dismissed it.
     *
     * `repo.state.first()` collects exactly the first emission of
     * the underlying flow. DataStore Preferences guarantees the
     * first emission is the loaded snapshot (it doesn't synthesise
     * a default emission), so this returns the value the user
     * actually has on disk.
     */
    suspend fun awaitInitialSettings(): SettingsState = repo.state.first()

    /// Stamp the freshly-seen marketing version.
    fun acknowledgeVersion(version: String) {
        viewModelScope.launch { repo.setLastSeenVersion(version) }
    }

    /// Read-and-clear the pending deep-link payload.
    fun consumePendingDeepLink(): me.nettrash.scan.ui.deeplink.DeepLink.Payload? =
        deepLinks.consumePending()

    /// Read-and-clear the share-intake state. Called when the user
    /// dismisses the share-result sheet.
    fun consumeShareIntake() {
        shareIntake.consume()
    }
}
