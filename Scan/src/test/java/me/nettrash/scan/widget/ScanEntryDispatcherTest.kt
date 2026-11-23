package me.nettrash.scan.widget

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import me.nettrash.scan.MainActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.xmlpull.v1.XmlPullParser

/**
 * The quick-entry plumbing: what the widget, the Quick Settings tile and the
 * static shortcuts send, and what the dispatcher makes of it. MainScreen's
 * navigation is the consumer; these pin the contract it relies on.
 */
@RunWith(RobolectricTestRunner::class)
class ScanEntryDispatcherTest {

    private val context: android.content.Context = RuntimeEnvironment.getApplication()

    @Test fun launchIntentCarriesActionExtraAndWarmStartFlags() {
        for (entry in ScanEntry.entries) {
            val intent = ScanIntents.launchIntent(context, entry)
            assertThat(intent.component?.className).isEqualTo(MainActivity::class.java.name)
            assertThat(intent.action).isEqualTo(ScanIntents.actionFor(entry))
            assertThat(intent.getStringExtra(ScanEntryDispatcher.EXTRA_ENTRY)).isEqualTo(entry.name)
            // SINGLE_TOP + CLEAR_TOP is what makes a warm app receive the
            // request through onNewIntent instead of dropping or recreating.
            for (flag in listOf(
                Intent.FLAG_ACTIVITY_NEW_TASK,
                Intent.FLAG_ACTIVITY_CLEAR_TOP,
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )) {
                assertThat(intent.flags and flag).isEqualTo(flag)
            }
            assertThat(ScanEntryDispatcher.entryFor(intent)).isEqualTo(entry)
        }
    }

    @Test fun dispatcherHoldsOneRequestUntilConsumed() {
        val dispatcher = ScanEntryDispatcher()
        assertThat(dispatcher.handle(ScanIntents.launchIntent(context, ScanEntry.Generator)))
            .isEqualTo(ScanEntry.Generator)
        assertThat(dispatcher.pending.value).isEqualTo(ScanEntry.Generator)
        assertThat(dispatcher.consumePending()).isEqualTo(ScanEntry.Generator)
        assertThat(dispatcher.pending.value).isNull()
        assertThat(dispatcher.consumePending()).isNull()
    }

    @Test fun everyScanLaunchIsACameraRequest() {
        // iOS `ScanIntentRouter.scannerRequests`: a counter, so two "Scan a
        // code" launches in a row are two changes the screens react to.
        val dispatcher = ScanEntryDispatcher()
        assertThat(dispatcher.scannerRequests.value).isEqualTo(0)
        dispatcher.handle(ScanIntents.launchIntent(context, ScanEntry.Scanner))
        dispatcher.consumePending()
        dispatcher.handle(Intent(ScanIntents.ACTION_SCANNER))
        assertThat(dispatcher.scannerRequests.value).isEqualTo(2)

        // Other tabs and ordinary launches don't ask for the camera.
        dispatcher.handle(ScanIntents.launchIntent(context, ScanEntry.Generator))
        dispatcher.handle(ScanIntents.launchIntent(context, ScanEntry.RecentScans))
        dispatcher.handle(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        dispatcher.handle(null)
        assertThat(dispatcher.scannerRequests.value).isEqualTo(2)
    }

    @Test fun ordinaryLaunchesAreNotEntryRequests() {
        val dispatcher = ScanEntryDispatcher()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        assertThat(dispatcher.handle(launcher)).isNull()
        assertThat(dispatcher.handle(Intent(Intent.ACTION_VIEW))).isNull()
        assertThat(dispatcher.handle(null)).isNull()
        assertThat(dispatcher.pending.value).isNull()
        // A junk extra from another app is simply not a request.
        assertThat(dispatcher.handle(Intent().putExtra(ScanEntryDispatcher.EXTRA_ENTRY, "Settings"))).isNull()
    }

    @Test fun staticShortcutsFireActionsTheDispatcherKnows() {
        // res/xml/shortcuts.xml can't reference the Kotlin constants, so read
        // it back and check every shortcut targets the trampoline — not
        // MainActivity, which the system's CLEAR_TASK would finish — with an
        // action that maps onto a tab.
        val parser = context.resources.getXml(me.nettrash.scan.R.xml.shortcuts)
        val android = "http://schemas.android.com/apk/res/android"
        val actions = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "intent") {
                assertThat(parser.getAttributeValue(android, "targetClass"))
                    .isEqualTo(ShortcutTrampolineActivity::class.java.name)
                assertThat(parser.getAttributeValue(android, "targetPackage")).isEqualTo(context.packageName)
                actions += parser.getAttributeValue(android, "action")
            }
        }
        assertThat(actions.map(ScanIntents::fromAction))
            .containsExactly(ScanEntry.Scanner, ScanEntry.Generator, ScanEntry.RecentScans)
            .inOrder()
    }
}
