package me.nettrash.scan

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import me.nettrash.scan.ui.MainScreen
import me.nettrash.scan.ui.deeplink.DeepLinkDispatcher
import me.nettrash.scan.ui.share.ShareIntakeDispatcher
import me.nettrash.scan.ui.theme.ScanTheme
import me.nettrash.scan.widget.ScanEntryDispatcher
import javax.inject.Inject

/**
 * Single-activity host — Compose owns navigation between Scan / Generate /
 * History / Settings. Camera permission is requested lazily by the Scanner
 * screen so users that only want to generate codes don't see a prompt on
 * first launch.
 *
 * Four intent shapes routed through this activity:
 *  - ACTION_VIEW with `https://nettrash.me/scan/<base64url-payload>` —
 *    Universal/App-Links arrival (since 1.5).
 *  - ACTION_SEND with image (any subtype) or application/pdf —
 *    share-to-Scan single image / PDF (1.6).
 *  - ACTION_SEND_MULTIPLE with the same MIME types — share-to-Scan
 *    multi-input batch (1.6).
 *  - A widget / Quick Settings tile / app-shortcut launch, which asks for a
 *    specific tab through [ScanEntryDispatcher] (1.10).
 *
 * Cold-start (`onCreate`) and warm-start (`onNewIntent`) paths are both
 * covered. The respective dispatcher [DeepLinkDispatcher] /
 * [ShareIntakeDispatcher] / [ScanEntryDispatcher] holds the pending payload
 * until the Compose tree is mounted and ready to consume it.
 *
 * KDoc note: avoid writing `image/<asterisk>` or any other token where
 * an asterisk sits next to a slash inside this comment — Kotlin's
 * lexer reads the resulting `*` followed by `/` as the end of the KDoc
 * block, even inside backticks.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var deepLinks: DeepLinkDispatcher
    @Inject lateinit var shareIntake: ShareIntakeDispatcher
    @Inject lateinit var quickEntry: ScanEntryDispatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cold-start path: route the launching intent through whichever
        // dispatcher matches its action *before* setContent so MainScreen
        // sees the pending payload on its first composition. A recreation
        // after process death replays the last intent, and the restored
        // back stack already shows where the user went after it — so a
        // tab request is only honoured on a genuinely fresh start.
        routeIntent(intent, routeTabRequest = savedInstanceState == null)
        setContent {
            ScanTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Warm-start path: forward to the dispatchers and update
        // `intent` so any future getIntent() reads also see the new
        // value (per the standard onNewIntent contract).
        setIntent(intent)
        routeIntent(intent, routeTabRequest = true)
    }

    private fun routeIntent(intent: Intent?, routeTabRequest: Boolean) {
        if (intent == null) return
        // Platform-hook launches (widget, QS tile, app shortcuts) ask for a tab
        // by extra or by `me.nettrash.scan.action.*`; anything else returns
        // null here and falls through to the dispatchers below untouched.
        if (routeTabRequest) quickEntry.handle(intent)
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { deepLinks.handle(it) }
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                shareIntake.handle(intent)
            }
            // Plain MAIN/LAUNCHER — no payload to route.
        }
    }
}
