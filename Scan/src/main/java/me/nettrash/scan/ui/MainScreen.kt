package me.nettrash.scan.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import me.nettrash.scan.ui.generator.GeneratorScreen
import me.nettrash.scan.ui.history.HistoryScreen
import me.nettrash.scan.ui.scanner.ScannerScreen

/** Three-tab bottom navigation host: Scan / Generate / History. */
@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val items = listOf(BottomTab.Scan, BottomTab.Generate, BottomTab.History)

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
            composable(BottomTab.Scan.route) { ScannerScreen() }
            composable(BottomTab.Generate.route) { GeneratorScreen() }
            composable(BottomTab.History.route) { HistoryScreen() }
        }
    }
}

private sealed class BottomTab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    data object Scan : BottomTab("scan", "Scan", Icons.Filled.QrCodeScanner)
    data object Generate : BottomTab("generate", "Generate", Icons.Filled.QrCode)
    data object History : BottomTab("history", "History", Icons.Filled.History)
}
