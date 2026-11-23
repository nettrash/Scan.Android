package me.nettrash.scan.widget

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import me.nettrash.scan.MainActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The static long-press shortcuts land on [ShortcutTrampolineActivity],
 * which the system starts with `NEW_TASK | CLEAR_TASK | TASK_ON_HOME` (no app
 * can change that). It must hand the request to `MainActivity` with the
 * warm-launch flags the widget and the tile use — so a running app gets
 * `onNewIntent` and keeps its Bulk list — and get out of the way.
 */
@RunWith(RobolectricTestRunner::class)
class ShortcutTrampolineActivityTest {

    private val context: android.content.Context = RuntimeEnvironment.getApplication()

    /** What ShortcutParser adds to the first intent of every static shortcut. */
    private val staticShortcutFlags = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_CLEAR_TASK or
        Intent.FLAG_ACTIVITY_TASK_ON_HOME

    private fun launch(intent: Intent): Pair<ShortcutTrampolineActivity, Intent> {
        val activity = Robolectric.buildActivity(
            ShortcutTrampolineActivity::class.java,
            intent.setClass(context, ShortcutTrampolineActivity::class.java).addFlags(staticShortcutFlags),
        ).create().get()
        return activity to shadowOf(activity).nextStartedActivity
    }

    @Test fun everyShortcutReachesMainActivityWithTheWarmLaunchFlags() {
        assertThat(staticShortcutFlags).isEqualTo(0x1000c000)
        for (entry in ScanEntry.entries) {
            val (activity, forwarded) = launch(Intent(ScanIntents.actionFor(entry)))
            assertWithMessage("$entry").that(forwarded.component?.className).isEqualTo(MainActivity::class.java.name)
            assertWithMessage("$entry").that(forwarded.action).isEqualTo(ScanIntents.actionFor(entry))
            assertWithMessage("$entry").that(forwarded.getStringExtra(ScanEntryDispatcher.EXTRA_ENTRY)).isEqualTo(entry.name)
            assertWithMessage("$entry").that(forwarded.flags and ScanIntents.LAUNCH_FLAGS).isEqualTo(ScanIntents.LAUNCH_FLAGS)
            // The CLEAR_TASK that would finish a running MainActivity stays behind.
            assertWithMessage("$entry").that(forwarded.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK).isEqualTo(0)
            assertWithMessage("$entry").that(ScanEntryDispatcher.entryFor(forwarded)).isEqualTo(entry)
            assertWithMessage("$entry").that(activity.isFinishing).isTrue()
        }
    }

    @Test fun anUnknownLaunchStillOpensTheCamera() {
        val (activity, forwarded) = launch(Intent())
        assertThat(forwarded.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(ScanEntryDispatcher.entryFor(forwarded)).isEqualTo(ScanEntry.Scanner)
        assertThat(activity.isFinishing).isTrue()
    }

    @Test fun manifestKeepsItPrivateInvisibleAndInItsOwnTask() {
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, ShortcutTrampolineActivity::class.java), 0,
        )
        assertThat(info.exported).isFalse()
        // android:taskAffinity="" — no affinity, so CLEAR_TASK clears only
        // the trampoline's own task, never the app's.
        assertThat(info.taskAffinity.isNullOrEmpty()).isTrue()
        assertThat(info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS).isNotEqualTo(0)
        assertThat(info.flags and ActivityInfo.FLAG_NO_HISTORY).isNotEqualTo(0)
        assertThat(info.theme).isEqualTo(android.R.style.Theme_NoDisplay)
    }
}
