package me.nettrash.scan.ui.scanner

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import me.nettrash.scan.data.settings.ScanModePreference
import me.nettrash.scan.testutil.composeRoot
import me.nettrash.scan.testutil.node
import me.nettrash.scan.testutil.renderToBitmap
import me.nettrash.scan.ui.theme.ScanTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The top row of the camera chrome, measured and drawn with real text
 * metrics (NATIVE graphics):
 *
 *  - the bulk HUD — the only way into the bulk list — keeps its full width
 *    on a narrow phone and at large font sizes, the mode footnote wrapping
 *    beside it instead (iOS `fixedSize(horizontal: false, vertical: true)`);
 *  - the footnote stays legible over a white page;
 *  - TalkBack hears the current mode as the picker's state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScanModeTopRowTest {

    /** The HUD's width inside the top row, and on its own. */
    private fun hudWidths(fontScale: Float, isFull: Boolean): Pair<Int, Int> {
        RuntimeEnvironment.setFontScale(fontScale)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var inRow = IntSize.Zero
        var alone = IntSize.Zero
        activity.setContent {
            ScanTheme {
                Column(Modifier.fillMaxWidth()) {
                    ScanModeTopRow(
                        mode = ScanModePreference.BULK,
                        availableModes = ScanModePreference.entries,
                        onSelect = {},
                        hud = {
                            BulkCaptureHud(
                                count = 3, isFull = isFull, tamperWarning = true, onClick = {},
                                modifier = Modifier.onGloballyPositioned { inRow = it.size },
                            )
                        },
                    )
                    Box(Modifier.wrapContentSize()) {
                        BulkCaptureHud(
                            count = 3, isFull = isFull, tamperWarning = true, onClick = {},
                            modifier = Modifier.onGloballyPositioned { alone = it.size },
                        )
                    }
                }
            }
        }
        ShadowLooper.idleMainLooper()
        return inRow.width to alone.width
    }

    private fun assertHudKeepsItsWidth(fontScale: Float) {
        for (isFull in listOf(false, true)) {
            val (inRow, alone) = hudWidths(fontScale, isFull)
            assertWithMessage("standalone HUD width, isFull=$isFull").that(alone).isGreaterThan(0)
            assertWithMessage("HUD width in the top row, isFull=$isFull").that(inRow).isEqualTo(alone)
        }
    }

    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    @Test fun hudKeepsItsWidthOnA360dpPhone() = assertHudKeepsItsWidth(1.0f)

    @Config(qualifiers = "w411dp-h914dp-560dpi")
    @Test fun hudKeepsItsWidthAtFontScale130() = assertHudKeepsItsWidth(1.3f)

    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    @Test fun hudKeepsItsWidthOnA320dpPhoneAtFontScale200() = assertHudKeepsItsWidth(2.0f)

    // ---- Footnote contrast -------------------------------------------------

    private fun luminance(argb: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(AndroidColor.red(argb)) +
            0.7152 * channel(AndroidColor.green(argb)) +
            0.0722 * channel(AndroidColor.blue(argb))
    }

    @Config(qualifiers = "w411dp-h914dp-560dpi")
    @Test fun footnoteStaysLegibleOverAWhitePage() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            ScanTheme {
                // A white sheet of paper filling the viewfinder.
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    ScanModeSelector(
                        current = ScanModePreference.BULK,
                        availableModes = ScanModePreference.entries,
                        onSelect = {},
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        ShadowLooper.idleMainLooper()
        val decor = activity.window.decorView
        val bitmap = renderToBitmap(decor)
        val text = decor.composeRoot()!!.node(ScanModePreference.BULK.footnote, merged = false).boundsInWindow
        val density = activity.resources.displayMetrics.density

        // The backing, just left of the text (inside its 10 dp padding).
        val backing = bitmap.getPixel((text.left - 5 * density).roundToInt(), text.center.y.roundToInt())
        // The text: the brightest pixel inside the text's own box.
        var brightest = backing
        for (y in text.top.roundToInt() until text.bottom.roundToInt()) {
            for (x in text.left.roundToInt() until text.right.roundToInt()) {
                val p = bitmap.getPixel(x, y)
                if (luminance(p) > luminance(brightest)) brightest = p
            }
        }
        val contrast = (luminance(brightest) + 0.05) / (luminance(backing) + 0.05)
        assertWithMessage(
            "footnote contrast, text #%08X on #%08X".format(brightest, backing)
        ).that(contrast).isAtLeast(4.5)
    }

    // ---- TalkBack: the picker's state is the mode --------------------------

    @Config(qualifiers = "w411dp-h914dp-560dpi")
    @Test fun pickerAnnouncesTheCurrentModeAsItsState() {
        for (mode in listOf(ScanModePreference.CODES, ScanModePreference.BULK)) {
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            activity.setContent {
                ScanTheme {
                    Box(Modifier.fillMaxSize()) {
                        ScanModeSelector(current = mode, availableModes = ScanModePreference.entries, onSelect = {})
                    }
                }
            }
            ShadowLooper.idleMainLooper()
            val root = activity.window.decorView.composeRoot()!!
            val picker = root.node("Scan mode")
            assertThat(picker.config.getOrNull(SemanticsProperties.StateDescription)).isEqualTo(mode.label)
            // The visible label is not read a second time beside the state.
            assertThat(picker.config.getOrNull(SemanticsProperties.Text)).isNull()

            // What the accessibility service is handed: "Scan mode", the
            // mode as its state, and a node to activate — iOS's picker label
            // and value.
            val info = root.view.accessibilityNodeProvider!!.createAccessibilityNodeInfo(picker.id)!!
            assertThat(info.contentDescription?.toString()).isEqualTo("Scan mode")
            assertThat(info.stateDescription?.toString()).isEqualTo(mode.label)
            assertThat(info.isClickable).isTrue()
            assertThat(info.childCount).isEqualTo(0)
        }
    }
}
