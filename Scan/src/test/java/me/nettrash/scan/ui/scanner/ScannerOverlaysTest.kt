package me.nettrash.scan.ui.scanner

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import com.google.common.truth.Truth.assertThat
import me.nettrash.scan.data.payload.TranslateLanguage
import me.nettrash.scan.data.settings.ScanModePreference
import me.nettrash.scan.scanner.BulkScanSession
import me.nettrash.scan.scanner.RecognizedTextBlock
import me.nettrash.scan.scanner.ScannedCode
import me.nettrash.scan.scanner.Symbology
import me.nettrash.scan.testutil.composeRoot
import me.nettrash.scan.testutil.node
import me.nettrash.scan.testutil.pressAndRelease
import me.nettrash.scan.testutil.tap
import me.nettrash.scan.ui.theme.ScanTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The layer over the camera with two or more codes in view. The chooser sits
 * *under* the chrome (iOS draws `multiCodeOverlay` before `modeChrome`), so
 * the mode switch, the banner and the controls keep their taps and their
 * TalkBack nodes; only a tap on the bare camera dismisses the chooser. In
 * Live Text the codes are numbered rows above the text, with no backdrop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp-560dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScannerOverlaysTest {

    /** Every callback the layer fires, in order. */
    private val calls = mutableListOf<String>()

    private val callbacks = ScannerOverlayCallbacks(
        onSelectMode = { calls += "selectMode:${it.key}" },
        onOpenBulkList = { calls += "openBulkList" },
        onPickCode = { calls += "pick:${it.value}" },
        onDismissChooser = { calls += "dismissChooser" },
        onOpenContinuous = { calls += "openContinuous" },
        onDismissContinuous = { calls += "dismissContinuous" },
        onSelectText = { calls += "selectText:${it.text}" },
        onDismissText = { calls += "dismissText" },
    )

    private fun code(value: String, rect: Rect? = null) =
        ScannedCode(value = value, symbology = Symbology.QR, timestampMillis = 0, previewRect = rect)

    private val blocks = listOf(RecognizedTextBlock("Cafe Menu"), RecognizedTextBlock("Bitte zahlen an der Kasse"))

    private fun show(state: ScannerViewModel.State, mode: ScanModePreference): ComponentActivity {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            ScanTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    ScannerOverlays(
                        state = state,
                        mode = mode,
                        availableModes = ScanModePreference.entries,
                        bulk = BulkScanSession(),
                        continuousScan = true,
                        translateTarget = TranslateLanguage("en", "English"),
                        callbacks = callbacks,
                    ) {
                        // Stands in for the controls row (import, NFC, torch).
                        Text("Torch", color = Color.White, modifier = Modifier.clickable { calls += "controls" })
                    }
                }
            }
        }
        ShadowLooper.idleMainLooper()
        return activity
    }

    /** Two codes in a Codes-mode frame, with the continuous banner up. */
    private fun codesModeWithChooser(): ComponentActivity = show(
        ScannerViewModel.State(
            multiCodeChoices = listOf(
                code("https://a.example", Rect(100f, 1400f, 300f, 1600f)),
                code("https://b.example", Rect(500f, 1400f, 700f, 1600f)),
            ),
            lastContinuous = code("https://saved.example"),
        ),
        ScanModePreference.CODES,
    )

    @Test fun modeSwitchTakesItsTapWhileTheChooserIsUp() {
        val activity = codesModeWithChooser()
        val decor = activity.window.decorView
        val picker = decor.composeRoot()!!.node("Scan mode")

        // The full-screen backdrop is beneath everything; a press on the
        // switch is the switch's, so the chooser stays and nothing else
        // fires. (The run stops short of drawing the menu: Robolectric
        // takes minutes to lay out a DropdownMenu popup window.)
        pressAndRelease(decor, picker)
        assertThat(calls).isEmpty()

        // The switch is a live, clickable control, as for TalkBack's
        // double-tap.
        assertThat(picker.config.getOrNull(SemanticsActions.OnClick)).isNotNull()
    }

    @Test fun bannerAndControlsKeepTheirTapsWhileTheChooserIsUp() {
        val activity = codesModeWithChooser()
        val decor = activity.window.decorView
        val root = decor.composeRoot()!!

        tap(decor, root.node("Dismiss saved-scan banner"))
        tap(decor, root.node("Open ›"))
        tap(decor, root.node("Torch"))

        assertThat(calls).containsExactly("dismissContinuous", "openContinuous", "controls").inOrder()
    }

    @Test fun chipsPickAndTheBareCameraDismisses() {
        val activity = codesModeWithChooser()
        val decor = activity.window.decorView

        tap(decor, 200f, 1500f) // chip 1, over the first code
        tap(decor, decor.width * 0.75f, decor.height * 0.65f) // nothing but camera

        assertThat(calls).containsExactly("pick:https://a.example", "dismissChooser").inOrder()
    }

    @Test fun liveTextKeepsTheTextTappableAndListsTheCodesAboveIt() {
        val activity = show(
            ScannerViewModel.State(
                multiCodeChoices = listOf(code("https://a.example"), code("https://b.example")),
                recognizedBlocks = blocks,
            ),
            ScanModePreference.TEXT,
        )
        val decor = activity.window.decorView
        val root = decor.composeRoot()!!

        // The codes come first in the bottom stack, so the text can't cover them.
        val lastCode = root.node("https://b.example").boundsInWindow
        val firstBlock = root.node("Cafe Menu").boundsInWindow
        assertThat(lastCode.bottom).isAtMost(firstBlock.top)

        tap(decor, root.node("Bitte zahlen an der Kasse"))
        tap(decor, root.node("https://b.example"))
        // No backdrop in Live Text (iOS draws none): the camera area is the camera's.
        tap(decor, decor.width * 0.5f, decor.height * 0.4f)

        assertThat(calls).containsExactly("selectText:Bitte zahlen an der Kasse", "pick:https://b.example").inOrder()
    }

    @Test fun talkBackStillReachesTheSwitchAndTheText() {
        val activity = show(
            ScannerViewModel.State(
                multiCodeChoices = listOf(code("https://a.example"), code("https://b.example")),
                recognizedBlocks = blocks,
            ),
            ScanModePreference.TEXT,
        )
        val root = activity.window.decorView.composeRoot()!!
        val provider = root.view.accessibilityNodeProvider!!

        val picker = provider.createAccessibilityNodeInfo(root.node("Scan mode").id)!!
        assertThat(picker.contentDescription?.toString()).isEqualTo("Scan mode")
        assertThat(picker.isClickable).isTrue()
        assertThat(picker.isVisibleToUser).isTrue()

        val text = provider.createAccessibilityNodeInfo(root.node("Bitte zahlen an der Kasse").id)!!
        assertThat(text.text?.toString()).isEqualTo("Bitte zahlen an der Kasse")
        assertThat(text.isClickable).isTrue()
        assertThat(text.isVisibleToUser).isTrue()
    }

    @Test fun talkBackReachesTheSwitchOverTheCodesModeBackdrop() {
        val root = codesModeWithChooser().window.decorView.composeRoot()!!
        val provider = root.view.accessibilityNodeProvider!!

        val picker = provider.createAccessibilityNodeInfo(root.node("Scan mode").id)!!
        assertThat(picker.contentDescription?.toString()).isEqualTo("Scan mode")
        assertThat(picker.isClickable).isTrue()
        assertThat(picker.isVisibleToUser).isTrue()

        val dismiss = provider.createAccessibilityNodeInfo(root.node("Dismiss saved-scan banner").id)!!
        assertThat(dismiss.isClickable).isTrue()
    }
}
