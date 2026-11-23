package me.nettrash.scan.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.compose
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import me.nettrash.scan.data.db.ScanRecord
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The Recent-scans widget as Glance renders it: what reaches the home screen
 * (and the launcher's process), and how it keeps up with History while a
 * Glance session runs.
 */
@RunWith(RobolectricTestRunner::class)
class RecentScansWidgetTest {

    private val app = RuntimeEnvironment.getApplication()

    private fun textViews(root: View?): List<TextView> {
        val out = mutableListOf<TextView>()
        fun walk(v: View) {
            if (v is TextView && v.visibility == View.VISIBLE) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        if (root != null) walk(root)
        return out
    }

    private fun texts(root: View?) = textViews(root).map { it.text.toString() }

    @Test fun rowsNameTheKindAndNeverPrintTheValue() = runBlocking {
        val dao = ScanWidgetEntryPoint.resolve(app)
        dao.deleteAll()
        val now = System.currentTimeMillis()
        listOf(
            "WIFI:T:WPA;S:HomeNet;P:hunter2-correct-horse;;",
            "otpauth://totp/Acme:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Acme",
            "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5",
            "FIDO:/005640586143991420254210104918118704520152650222346064982042512635050847625585230466405085311340191775070633618515609790059716415128245854177999084978946107096654083332",
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ana Lima\r\nTEL:+15551234\r\nEND:VCARD",
        ).forEachIndexed { i, value ->
            dao.insert(ScanRecord(value = value, symbology = "QR", timestamp = now - i * 60_000L))
        }

        val views = RecentScansWidget().compose(app, size = DpSize(320.dp, 520.dp))
            .apply(app, FrameLayout(app))
        val shown = texts(views)

        val all = shown.joinToString("\n")
        for (secret in listOf("hunter2", "P:", "JBSWY3DPEHPK3PXP", "secret=", "alice", "nsec1", "0056405861", "FIDO:", "Ana Lima", "+15551234")) {
            assertWithMessage("widget text").that(all).doesNotContain(secret)
        }
        assertThat(shown).containsAtLeast("Wi-Fi · HomeNet", "OTP", "Nostr", "Passkey", "Contact").inOrder()
        // All five rows fit with the button below them.
        assertThat(shown.last()).isEqualTo("Scan a code")

        // Each row's two lines stay one line apiece, like a History row.
        val rowViews = textViews(views).filter { tv ->
            val t = tv.text.toString()
            t in setOf("Wi-Fi · HomeNet", "OTP", "Nostr", "Passkey", "Contact") || t.startsWith("QR · ")
        }
        assertThat(rowViews).hasSize(10)
        for (tv in rowViews) assertWithMessage("maxLines of \"${tv.text}\"").that(tv.maxLines).isEqualTo(1)
    }

    /**
     * Runs the main looper (Robolectric's clock) in [stepMs] steps, giving
     * Room's and WorkManager's threads a moment between steps, until
     * [condition] holds or [fakeMs] of looper time passed.
     */
    private fun pump(fakeMs: Long, stepMs: Long = 100, condition: () -> Boolean): Boolean {
        var waited = 0L
        while (waited < fakeMs) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(stepMs))
            if (condition()) return true
            Thread.sleep(20)
            waited += stepMs
        }
        return condition()
    }

    @Test fun aLiveSessionShowsScansSavedWhileItRuns() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val widgets = AppWidgetManager.getInstance(app)
        val widgetId = 4242
        shadowOf(widgets).addBoundWidget(
            widgetId,
            AppWidgetProviderInfo().apply {
                provider = ComponentName(app, RecentScansWidgetReceiver::class.java)
                minWidth = 250
                minHeight = 110
            },
        )
        val dao = ScanWidgetEntryPoint.resolve(app)
        dao.deleteAll()
        val t0 = System.currentTimeMillis()
        dao.insert(ScanRecord(value = "https://alpha.example/a", symbology = "QR", timestamp = t0))
        val glanceId = GlanceAppWidgetManager(app).getGlanceIdBy(widgetId)
        fun shown() = texts(shadowOf(widgets).getViewFor(widgetId))
        fun sessionRunning() = WorkManager.getInstance(app)
            .getWorkInfos(WorkQuery.fromStates(WorkInfo.State.RUNNING)).get().isNotEmpty()

        // What RecentScansWidgetSync and Refresh call.
        RecentScansWidget().update(app, glanceId)
        assertThat(pump(15_000) { "URL · alpha.example" in shown() }).isTrue()
        assertThat(sessionRunning()).isTrue()

        // A scan saved while that session runs shows up in it — update()
        // on a live session only recomposes, so the rows must come from the
        // composition's own collection of History.
        dao.insert(ScanRecord(value = "https://bravo.example/b", symbology = "QR", timestamp = t0 + 5_000))
        RecentScansWidget().update(app, glanceId)
        assertWithMessage("rows ${shown()}").that(pump(5_000) { "URL · bravo.example" in shown() }).isTrue()
        assertThat(shown()).containsAtLeast("URL · bravo.example", "URL · alpha.example").inOrder()
        assertThat(sessionRunning()).isTrue()
        // (Not waited for: the session's natural end. Glance measures its
        // 45 s window on the wall clock, which no looper pumping speeds up.)
    }
}
