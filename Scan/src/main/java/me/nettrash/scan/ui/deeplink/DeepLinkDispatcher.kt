package me.nettrash.scan.ui.deeplink

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton bridge between [me.nettrash.scan.MainActivity]'s
 * `onNewIntent` / `onCreate` paths and the Compose tree. Mirrors
 * `DeepLinkDispatcher` on iOS — same single-slot semantics so a
 * cold-start link survives until the UI is ready to consume it.
 *
 * Hilt-injected as a singleton; consumers reach it via
 * [me.nettrash.scan.ui.MainViewModel] which collects [pending] as
 * a StateFlow.
 */
@Singleton
class DeepLinkDispatcher @Inject constructor() {

    private val _pending = MutableStateFlow<DeepLink.Payload?>(null)
    val pending: StateFlow<DeepLink.Payload?> = _pending.asStateFlow()

    /** Decode the URI and stash the payload. Called from MainActivity
     *  on every relevant intent (cold-start `onCreate`, warm-start
     *  `onNewIntent`). Non-Scan URIs are silently ignored. */
    fun handle(uri: android.net.Uri) {
        DeepLink.decode(uri)?.let { payload ->
            _pending.update { payload }
        }
    }

    /** Read-and-clear. UI calls this once it has presented the
     *  result sheet so a re-collection of the flow doesn't re-trigger
     *  the sheet. */
    fun consumePending(): DeepLink.Payload? {
        val p = _pending.value
        if (p != null) _pending.update { null }
        return p
    }
}
