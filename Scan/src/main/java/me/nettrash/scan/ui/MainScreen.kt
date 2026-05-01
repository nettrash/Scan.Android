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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import me.nettrash.scan.BuildConfig
import me.nettrash.scan.ui.deeplink.DeepLinkResultSheet
import me.nettrash.scan.ui.generator.GeneratorScreen
import me.nettrash.scan.ui.history.HistoryScreen
import me.nettrash.scan.ui.scanner.ScannerScreen
import me.nettrash.scan.ui.settings.SettingsScreen
import me.nettrash.scan.ui.share.ShareIntakeDispatcher
import me.nettrash.scan.ui.share.ShareResultSheet
import me.nettrash.scan.ui.whatsnew.WhatsNew
import me.nettrash.scan.ui.whatsnew.WhatsNewSheet

/** Four-tab bottom navigation host: Scan / Generate / History / Settings. */
@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val items = listOf(BottomTab.Scan, BottomTab.Generate, BottomTab.History, BottomTab.Settings)

    val pendingDeepLink by viewModel.pendingDeepLink.collectAsState()
    val shareIntakeState by viewModel.shareIntakeState.collectAsState()
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
            whatsNewVisible = true
        } else {
            viewModel.acknowledgeVersion(current)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route
                items.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route ||
                            backStackEntry?.destination?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
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

private sealed class BottomTab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    data object Scan : BottomTab("scan", "Scan", Icons.Filled.QrCodeScanner)
    data object Generate : BottomTab("generate", "Generate", Icons.Filled.QrCode)
    data object History : BottomTab("history", "History", Icons.Filled.History)
    data object Settings : BottomTab("settings", "Settings", Icons.Filled.Settings)
}
