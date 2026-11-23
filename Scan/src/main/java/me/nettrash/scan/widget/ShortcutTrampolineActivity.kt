package me.nettrash.scan.widget

import android.app.Activity
import android.os.Bundle

/**
 * Where the static long-press shortcuts in `res/xml/shortcuts.xml` land —
 * "Scan a code", "Generate a code", "Recent scans".
 *
 * Android starts the first intent of a static shortcut with
 * `NEW_TASK | CLEAR_TASK | TASK_ON_HOME` and lets no app change that
 * (developer.android.com, Manage shortcuts → "Start one activity from
 * another"). Aimed straight at `MainActivity`, a shortcut used while Scan was
 * running finished the activity and built a new one, and everything that
 * lives only in memory went with it: the Bulk capture list, an open result
 * sheet, a typed note.
 *
 * So the shortcuts aim here instead. This activity has an empty task
 * affinity (manifest), so `CLEAR_TASK` clears only its own throwaway task;
 * it shows nothing (`Theme.NoDisplay`, finished in [onCreate]) and forwards
 * the request with [ScanIntents.launchIntent] — the `NEW_TASK | CLEAR_TOP |
 * SINGLE_TOP` launch the widget and the Quick Settings tile already use — so
 * a warm app gets it through `MainActivity.onNewIntent` with its state
 * intact, and a cold start simply creates the activity.
 *
 * Not exported: the launcher starts a shortcut's intent on behalf of the app
 * that published it, so no other app can reach this.
 */
class ShortcutTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A shortcut always carries one of the ScanIntents actions; anything
        // else still opens the camera, the app's front door.
        val entry = ScanEntryDispatcher.entryFor(intent) ?: ScanEntry.Scanner
        startActivity(ScanIntents.launchIntent(this, entry))
        finish()
    }
}
