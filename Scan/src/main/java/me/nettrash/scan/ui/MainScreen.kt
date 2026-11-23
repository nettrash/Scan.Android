package me.nettrash.scan.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import me.nettrash.scan.BuildConfig
import me.nettrash.scan.ui.components.LocalScanSettings
import me.nettrash.scan.ui.deeplink.DeepLinkResultSheet
import me.nettrash.scan.ui.generator.GeneratorScreen
import me.nettrash.scan.ui.history.HistoryScreen
import me.nettrash.scan.ui.scanner.ScannerScreen
import me.nettrash.scan.ui.settings.SettingsScreen
import me.nettrash.scan.ui.share.ShareIntakeDispatcher
import me.nettrash.scan.ui.share.ShareResultSheet
import me.nettrash.scan.ui.whatsnew.WhatsNew
import me.nettrash.scan.ui.whatsnew.WhatsNewSheet
import me.nettrash.scan.widget.ScanEntry

/** Four-tab bottom navigation host: Scan / Generate / History / Settings. */
@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val items = listOf(BottomTab.Scan, BottomTab.Generate, BottomTab.History, BottomTab.Settings)

    val settings by viewModel.state.collectAsState()
    val pendingDeepLink by viewModel.pendingDeepLink.collectAsState()
    val shareIntakeState by viewModel.shareIntakeState.collectAsState()
    val pendingEntry by viewModel.pendingEntry.collectAsState()
    var whatsNewVisible by remember { mutableStateOf(false) }

    // Decide whether to show the What's-New sheet on launch. We only
    // show it when (a) the running build's versionName matches the
    // bundled WhatsNew copy *and* (b) the user hasn't already seen
    // that version. Mismatches silently catch the storage up so the
    // sheet shows for the version it was *written* for, not whichever
    // build the user happens to install first.
    //
    // `LaunchedEffect(Unit)` runs exactly once per MainScreen lifetime.
    // It calls `awaitInitialSettings()` which suspends until DataStore
    // has emitted the actual on-disk value — *not* the StateFlow's
    // synthetic `SettingsState()` initial-default. Earlier this gate
    // was keyed on `state.lastSeenVersion` and re-fired on the
    // default `""` value before the real `"1.6"` arrived from disk,
    // which is why the sheet showed every launch.
    LaunchedEffect(Unit) {
        val initial = viewModel.awaitInitialSettings()
        val current = BuildConfig.VERSION_NAME
        if (initial.lastSeenVersion == current) return@LaunchedEffect
        if (current == WhatsNew.VERSION) {
            // A launch from "Scan a code" is for the camera. The version
            // stays unacknowledged, so the sheet shows on the next ordinary
            // launch instead — iOS skips it for Start Scanning the same way.
            if (viewModel.scannerRequests.value == 0) whatsNewVisible = true
        } else {
            viewModel.acknowledgeVersion(current)
        }
    }

    // Widget / Quick Settings tile / app-shortcut launches ask for a tab
    // through ScanEntryDispatcher — the counterpart of iOS's
    // `ScanIntentRouter`, which moves `selectedTab` for the Start Scanning
    // intent. Keyed on the request, so the cold-start value (stashed by
    // MainActivity before setContent) and every warm-start `onNewIntent`
    // both land here; consuming afterwards means the next request — even
    // for the same tab — is a fresh change.
    LaunchedEffect(pendingEntry) {
        val entry = pendingEntry ?: return@LaunchedEffect
        // On a cold start the NavHost only gets its graph when the
        // Scaffold composes its content, which can be after this effect
        // first runs. Navigating before the start destination is on the
        // back stack would throw, so wait for it.
        navController.currentBackStackEntryFlow.first()
        navController.navigateToTab(BottomTab.forEntry(entry).route)
        if (entry == ScanEntry.Scanner) {
            // "Scan a code" means a camera the user can see, so the sheets
            // this host shows over it go too: an open What's New counts as
            // seen (as its own dismissal does), an App-Links or share result
            // is closed. The scanner screen clears its own result sheet, the
            // bulk list and the Live Text card off the same request.
            if (whatsNewVisible) {
                viewModel.acknowledgeVersion(BuildConfig.VERSION_NAME)
                whatsNewVisible = false
            }
            viewModel.consumePendingDeepLink()
            viewModel.consumeShareIntake()
        }
        viewModel.consumePendingEntry()
    }

    // Every result surface below — the scanner sheet, History, App Links,
    // Share to Scan — reads Settings → Insights from here.
    CompositionLocalProvider(LocalScanSettings provides settings) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    val backStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = backStackEntry?.destination?.route
                    items.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route ||
                                backStackEntry?.destination?.hierarchy?.any { it.route == tab.route } == true,
                            onClick = { navController.navigateToTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = BottomTab.Scan.route,
                modifier = Modifier.padding(padding)
            ) {
                composable(BottomTab.Scan.route)     { ScannerScreen() }
                composable(BottomTab.Generate.route) { GeneratorScreen() }
                composable(BottomTab.History.route)  { HistoryScreen() }
                composable(BottomTab.Settings.route) { SettingsScreen() }
            }
        }

        if (whatsNewVisible) {
            WhatsNewSheet(onDismiss = {
                viewModel.acknowledgeVersion(BuildConfig.VERSION_NAME)
                whatsNewVisible = false
            })
        }

        // App-Links result sheet. Triggered by the StateFlow that
        // `MainActivity.handleDeepLinkIntent` feeds via DeepLinkDispatcher.
        // We `consumePendingDeepLink()` once on entry so a recomposition
        // doesn't re-trigger; if the user dismisses and re-opens the same
        // link, MainActivity's onNewIntent fires `handle()` again and the
        // flow flips back to non-null.
        if (pendingDeepLink != null) {
            DeepLinkResultSheet(
                payload = pendingDeepLink!!,
                onDismiss = { viewModel.consumePendingDeepLink() },
            )
        }

        // Share-to-Scan result sheet. Triggered by ShareIntakeDispatcher
        // whenever `ACTION_SEND` / `ACTION_SEND_MULTIPLE` lands on
        // MainActivity. The sheet handles all three non-Idle states
        // (Loading, Ready, Failed) internally.
        if (shareIntakeState !is ShareIntakeDispatcher.State.Idle) {
            ShareResultSheet(
                state = shareIntakeState,
                onDismiss = { viewModel.consumeShareIntake() },
            )
        }
    }
}

/**
 * Switch tabs the way the bottom bar does: one copy of each tab on the back
 * stack, its state saved and restored, so a widget launch onto History and
 * a tap on the History tab end up in exactly the same place.
 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

private sealed class BottomTab(val route: String, val label: String, val icon: ImageVector) {
    data object Scan : BottomTab("scan", "Scan", Icons.Filled.QrCodeScanner)
    data object Generate : BottomTab("generate", "Generate", Icons.Filled.QrCode)
    data object History : BottomTab("history", "History", Icons.Filled.History)
    data object Settings : BottomTab("settings", "Settings", Icons.Filled.Settings)

    companion object {
        /** The tab a platform entry point asked for. */
        fun forEntry(entry: ScanEntry): BottomTab = when (entry) {
            ScanEntry.Scanner -> Scan
            ScanEntry.Generator -> Generate
            ScanEntry.RecentScans -> History
        }
    }
}
