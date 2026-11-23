package me.nettrash.scan.ui.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.first
import me.nettrash.scan.data.settings.SettingsRepository
import me.nettrash.scan.testutil.awaitWhilePumpingMainLooper
import me.nettrash.scan.testutil.composeRoot
import me.nettrash.scan.testutil.nodes
import me.nettrash.scan.testutil.texts
import me.nettrash.scan.ui.theme.ScanTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Every Settings switch is labelled for TalkBack: the whole row is one
 * switch node carrying its title (and description), not a bare "On, switch"
 * beside separate text — iOS's `Toggle(isOn:) { Label(…) }`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h4000dp-560dpi")
class ToggleRowAccessibilityTest {

    private val context: android.content.Context = RuntimeEnvironment.getApplication()

    private fun switches(activity: ComponentActivity): List<SemanticsNode> =
        activity.window.decorView.composeRoot()!!.nodes(merged = true)
            .filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch }

    @Test fun everySwitchCarriesItsTitleAndState() {
        val repository = SettingsRepository(context)
        val defaults = awaitWhilePumpingMainLooper { repository.state.first() }
        val viewModel = SettingsViewModel(repository)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { ScanTheme { SettingsScreen(viewModel = viewModel) } }
        ShadowLooper.idleMainLooper()

        val expected = linkedMapOf(
            "Haptic on scan" to defaults.hapticOnScan,
            "Sound on scan" to defaults.soundOnScan,
            "Continuous scanning" to defaults.continuousScan,
            "On-device assistant" to defaults.assistantEnabled,
            "QR safety check" to defaults.riskChecksEnabled,
        )
        val found = switches(activity)
        assertThat(found.map { it.texts().first() }).containsExactlyElementsIn(expected.keys).inOrder()
        for (node in found) {
            val title = node.texts().first()
            val on = expected.getValue(title)
            assertWithMessage(title).that(node.config.getOrNull(SemanticsProperties.ToggleableState))
                .isEqualTo(if (on) ToggleableState.On else ToggleableState.Off)
            assertWithMessage(title).that(node.config.getOrNull(SemanticsActions.OnClick)).isNotNull()
        }
    }

    @Test fun activatingTheSafetyCheckRowFlipsTheSetting() {
        val repository = SettingsRepository(context)
        val before = awaitWhilePumpingMainLooper { repository.state.first().riskChecksEnabled }
        val viewModel = SettingsViewModel(repository)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { ScanTheme { SettingsScreen(viewModel = viewModel) } }
        ShadowLooper.idleMainLooper()

        try {
            val row = switches(activity).single { it.texts().first() == "QR safety check" }
            // TalkBack's double-tap.
            row.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke()
            ShadowLooper.idleMainLooper()

            // The write goes through the ViewModel on the main looper; poll
            // the stored value while it runs.
            val deadline = System.nanoTime() + 10_000_000_000L
            var stored = before
            while (stored == before && System.nanoTime() < deadline) {
                ShadowLooper.idleMainLooper()
                Thread.sleep(20)
                stored = awaitWhilePumpingMainLooper { repository.state.first().riskChecksEnabled }
            }
            assertThat(stored).isEqualTo(!before)
        } finally {
            // The preference store outlives the test; leave it as found.
            awaitWhilePumpingMainLooper { repository.setRiskChecksEnabled(before) }
        }
    }
}
