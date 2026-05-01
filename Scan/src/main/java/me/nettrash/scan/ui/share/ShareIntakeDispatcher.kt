package me.nettrash.scan.ui.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.nettrash.scan.scanner.ImageDecoder
import me.nettrash.scan.scanner.ScannedCode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton bridge between [me.nettrash.scan.MainActivity]'s
 * `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intent paths and the Compose
 * tree. Mirrors `DeepLinkDispatcher` for Universal Links — same
 * single-slot semantics, same cold-start vs. warm-start dance.
 *
 * Decoding runs on a background scope ([Dispatchers.IO]) because
 * `ImageDecoder.decodePdf` walks every page through `PdfRenderer`
 * and a 20-page PDF on a low-end device is well into "blocks the
 * UI thread" territory. The state machine surfaces `Loading` while
 * that runs so the result-sheet can show a spinner.
 */
@Singleton
class ShareIntakeDispatcher @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    sealed interface State {
        /** Nothing pending. Default. */
        data object Idle : State
        /** Decoder is running. Sheet shows a spinner. */
        data object Loading : State
        /** Done — `codes` is the deduped flatten across all inputs. */
        data class Ready(val codes: List<ScannedCode>) : State
        /** Decoder failed for the entire batch (no code in any input). */
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Process a fresh intent. Called from [me.nettrash.scan.MainActivity]
     *  on every `ACTION_SEND` / `ACTION_SEND_MULTIPLE` arrival (cold
     *  start `onCreate` and warm start `onNewIntent`). */
    fun handle(intent: Intent) {
        val uris = collectUris(intent)
        android.util.Log.i(
            "ShareIntake",
            "handle action=${intent.action} type=${intent.type} uris=${uris.size}: ${uris.joinToString()}"
        )
        if (uris.isEmpty()) {
            // Nothing decodable in this intent — surface a friendly
            // failure sheet instead of silently doing nothing, so the
            // user doesn't see the previous "empty bottom sheet"
            // failure mode (state stuck at Idle, only the Done button
            // visible). Most often this means the share source's
            // EXTRA_STREAM was packaged in a way our compat extractor
            // couldn't unwrap — log it so logcat is diagnostic.
            _state.update {
                State.Failed("This share didn't include an image or PDF Scan can read.")
            }
            return
        }
        _state.update { State.Loading }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ImageDecoder.decodeBatch(context, uris)
                }
            }
            result.fold(
                onSuccess = { codes ->
                    android.util.Log.i("ShareIntake", "decoded ${codes.size} codes")
                    _state.update { State.Ready(codes) }
                },
                onFailure = { e ->
                    android.util.Log.w("ShareIntake", "decode failed", e)
                    _state.update {
                        State.Failed(e.message ?: "Couldn't decode the shared file.")
                    }
                },
            )
        }
    }

    /** Read-and-clear. UI calls this after presenting the result so a
     *  re-collection of the flow doesn't re-trigger the sheet. */
    fun consume() {
        _state.update { State.Idle }
    }

    /** Pull URIs out of an `ACTION_SEND` (single) or
     *  `ACTION_SEND_MULTIPLE` (list) intent. Falls back to
     *  `intent.data` for the rare source app that emits a VIEW-style
     *  intent for image-share. */
    private fun collectUris(intent: Intent): List<Uri> {
        val out = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) out += uri
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtraCompat<Uri>(Intent.EXTRA_STREAM)
                if (uris != null) out += uris
            }
        }
        // Fallback for VIEW-style image opens.
        if (out.isEmpty() && intent.data != null) out += intent.data!!
        return out
    }

    /**
     * Pull a Parcelable extra in a backwards-compatible way. Tries the
     * typed Tiramisu+ API first, then falls back to the deprecated
     * untyped version — which is necessary because of a regression
     * in Android 13+: when the source app (Google Photos in
     * particular) wraps `EXTRA_STREAM` in a non-strict-`Uri`
     * container, the typed API's `Class.isInstance(value)` check
     * returns false and the typed call yields `null`. The deprecated
     * overload skips the strict check and casts at the call site,
     * which works for every share source we've seen in the wild.
     *
     * If both lookups miss the extra is genuinely absent.
     */
    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(name: String): T? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, T::class.java)?.let { return it }
        }
        @Suppress("DEPRECATION")
        return getParcelableExtra(name) as? T
    }

    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableArrayListExtraCompat(name: String): ArrayList<T>? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(name, T::class.java)?.let { return it }
        }
        @Suppress("DEPRECATION")
        return getParcelableArrayListExtra(name)
    }
}
