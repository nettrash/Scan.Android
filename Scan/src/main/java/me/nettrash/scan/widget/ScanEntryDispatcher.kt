package me.nettrash.scan.widget

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import me.nettrash.scan.MainActivity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which tab a platform entry point wants the user to land on.
 *
 * The bottom-nav host (`ui/MainScreen.kt`) owns the `NavHost`, so an
 * external launch — home-screen widget, Quick Settings tile, long-press app
 * shortcut — can only *request* a destination. This is that request, held in
 * the same single-slot way [me.nettrash.scan.ui.deeplink.DeepLinkDispatcher]
 * holds a pending App-Links payload: [me.nettrash.scan.MainActivity] feeds it
 * on cold start and warm start, and the nav host consumes it once it is
 * mounted.
 */
enum class ScanEntry {
    /** The camera scanner — what iOS's `StartScanningIntent` opens. */
    Scanner,

    /** The code generator — the Generate tab (iOS `GenerateCodeIntent`). */
    Generator,

    /** Recent scans — the History tab (iOS `CopyRecentScanIntent`'s home). */
    RecentScans;

    companion object {
        fun fromName(raw: String?): ScanEntry? =
            entries.firstOrNull { it.name == raw }
    }
}

/**
 * Singleton bridge between the external launch surfaces and the Compose tree.
 *
 * Two intent shapes carry an entry request:
 *  - an explicit extra ([EXTRA_ENTRY]) — set by [ScanIntents.launchIntent],
 *    which the widget, the QS tile and [ShortcutTrampolineActivity] use;
 *  - an action from [ScanIntents] alone — what the static app shortcuts in
 *    `res/xml/shortcuts.xml` fire at [ShortcutTrampolineActivity], which
 *    reads it with [entryFor] and forwards it as a [ScanIntents.launchIntent]
 *    (shortcut XML could carry an extra too, but the action alone is enough
 *    and keeps the XML short).
 *
 * Anything else (plain `MAIN`/`LAUNCHER`, an App-Links `ACTION_VIEW`, a
 * share `ACTION_SEND`) is ignored and returns null so the caller's other
 * dispatchers still see it.
 */
@Singleton
class ScanEntryDispatcher @Inject constructor() {

    private val _pending = MutableStateFlow<ScanEntry?>(null)
    val pending: StateFlow<ScanEntry?> = _pending.asStateFlow()

    /**
     * Every "Scan a code" launch so far — iOS `ScanIntentRouter.scannerRequests`.
     * Landing on the Scan tab is not enough when a sheet covers the camera,
     * so `MainScreen` and the scanner screen each take down what they show
     * over it whenever this moves. A counter rather than a flag, so two
     * launches in a row are two changes; a non-zero value also tells a
     * screen composed afterwards that this launch came from one of them.
     */
    private val _scannerRequests = MutableStateFlow(0)
    val scannerRequests: StateFlow<Int> = _scannerRequests.asStateFlow()

    /** Decode the entry request and stash it. Non-entry intents are ignored. */
    fun handle(intent: Intent?): ScanEntry? {
        if (intent == null) return null
        val entry = entryFor(intent) ?: return null
        _pending.value = entry
        if (entry == ScanEntry.Scanner) _scannerRequests.update { it + 1 }
        return entry
    }

    /** Read-and-clear, so a recomposition doesn't navigate twice. */
    fun consumePending(): ScanEntry? = _pending.getAndUpdate { null }

    companion object {
        /** Extra key carrying a [ScanEntry] name. Namespaced to avoid
         *  colliding with framework or third-party extras. */
        const val EXTRA_ENTRY: String = "me.nettrash.scan.extra.ENTRY"

        /**
         * The entry an intent asks for, or null. The extra wins over the
         * action; `getStringExtra` is guarded because `MainActivity` is
         * exported and anyone can hand it a malformed extras bundle.
         */
        fun entryFor(intent: Intent): ScanEntry? {
            val named = runCatching { intent.getStringExtra(EXTRA_ENTRY) }.getOrNull()
            return ScanEntry.fromName(named) ?: ScanIntents.fromAction(intent.action)
        }
    }
}

/**
 * The custom actions the static shortcuts in `res/xml/shortcuts.xml` fire,
 * and the one launch-intent builder every entry surface (widget, QS tile,
 * and the shortcuts by way of [ShortcutTrampolineActivity]) uses to reach
 * `MainActivity`. Kept here so the XML, the dispatcher and the launchers
 * share one list.
 */
object ScanIntents {
    const val ACTION_SCANNER: String = "me.nettrash.scan.action.SCANNER"
    const val ACTION_GENERATOR: String = "me.nettrash.scan.action.GENERATOR"
    const val ACTION_RECENT_SCANS: String = "me.nettrash.scan.action.RECENT_SCANS"

    /**
     * How an entry launch reaches a running app. `NEW_TASK` because the
     * widget host, the QS tile and the shortcut trampoline (alone in its own
     * empty-affinity task) start us from outside the app's task;
     * `CLEAR_TOP | SINGLE_TOP` so a warm app delivers the request to the
     * existing `MainActivity` through `onNewIntent` instead of finishing and
     * recreating it (standard launch mode + `CLEAR_TOP` alone recreates).
     * Without `SINGLE_TOP` an intent that matches the task's root launcher
     * intent would merely bring the task forward and the request would be
     * dropped — which is why [launchIntent] also uses its own action rather
     * than `MAIN`/`LAUNCHER`.
     *
     * Static shortcuts can't carry these flags — the system starts them with
     * `NEW_TASK | CLEAR_TASK`, which would finish a running `MainActivity` —
     * so they go through [ShortcutTrampolineActivity], which re-launches
     * with them.
     */
    const val LAUNCH_FLAGS: Int = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_CLEAR_TOP or
        Intent.FLAG_ACTIVITY_SINGLE_TOP

    fun fromAction(action: String?): ScanEntry? = when (action) {
        ACTION_SCANNER -> ScanEntry.Scanner
        ACTION_GENERATOR -> ScanEntry.Generator
        ACTION_RECENT_SCANS -> ScanEntry.RecentScans
        else -> null
    }

    fun actionFor(entry: ScanEntry): String = when (entry) {
        ScanEntry.Scanner -> ACTION_SCANNER
        ScanEntry.Generator -> ACTION_GENERATOR
        ScanEntry.RecentScans -> ACTION_RECENT_SCANS
    }

    /** An explicit `MainActivity` intent asking for [entry] — action and extra agree. */
    fun launchIntent(context: Context, entry: ScanEntry): Intent =
        Intent(actionFor(entry))
            .setClass(context, MainActivity::class.java)
            .putExtra(ScanEntryDispatcher.EXTRA_ENTRY, entry.name)
            .addFlags(LAUNCH_FLAGS)
}
