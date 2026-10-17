package com.lumilink.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lumilink.model.ConnectionState
import com.lumilink.ui.connect.ConnectScreen
import com.lumilink.ui.control.ControlScreen
import com.lumilink.ui.gallery.GalleryScreen

/** Navigation route ids in one place, so a typo is a compile error, not a silent bad link. */
object Routes {
    const val CONNECT = "connect"
    const val CONTROL = "control"
    const val GALLERY = "gallery"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val needsConnection: Boolean)

private val TABS = listOf(
    Tab(Routes.CONNECT, "Connect", Icons.Default.Wifi, needsConnection = false),
    Tab(Routes.CONTROL, "Control", Icons.Default.CenterFocusStrong, needsConnection = true),
    Tab(Routes.GALLERY, "Photos", Icons.Default.PhotoLibrary, needsConnection = true),
)

/**
 * Top-level UI: a persistent three-tab bottom nav (Connect · Control · Photos) matching the v1
 * wireframe. Control and Photos are disabled until the camera link is up. Connecting jumps to
 * Control; disconnecting returns to Connect. Each tab keeps its own state across switches, and
 * every tab enters the camera mode it needs (record vs playback) on its own.
 */
@Composable
fun LumiLinkNavHost() {
    val navController = rememberNavController()
    val container = appContainer()
    val connState by container.cameraNetworkManager.state.collectAsStateWithLifecycle()
    val connected = connState is ConnectionState.Connected

    // Log every route change while the nav host is alive — the CLI dev loop reads these to
    // verify navigation without seeing the screen.
    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            Log.i("NavHost", "route -> ${destination.route}")
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    // React to connection *transitions* only — not to a tab re-entering while already connected,
    // which is exactly the bounce that plagued the old linear flow.
    var wasConnected by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(connected) {
        if (connected && !wasConnected) {
            navController.navigate(Routes.CONTROL) { launchSingleTop = true }
        } else if (!connected && wasConnected) {
            navController.navigate(Routes.CONNECT) {
                popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                launchSingleTop = true
            }
        }
        wasConnected = connected
    }

    Scaffold(
        bottomBar = { BottomNav(navController, connected) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.CONNECT,
            // consumeWindowInsets first: without it, the per-screen Scaffolds also apply the
            // system navigation-bar inset on top of this padding, double-counting ~200px at
            // the bottom and crushing/clipping the lowest elements (measured on-device).
            modifier = Modifier
                .consumeWindowInsets(padding)
                .padding(padding),
        ) {
            composable(Routes.CONNECT) { ConnectScreen() }
            composable(Routes.CONTROL) { ControlScreen() }
            composable(Routes.GALLERY) { GalleryScreen() }
        }
    }
}

@Composable
private fun BottomNav(navController: NavController, connected: Boolean) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationBar {
        TABS.forEach { tab ->
            val enabled = !tab.needsConnection || connected
            NavigationBarItem(
                selected = currentRoute == tab.route,
                enabled = enabled,
                onClick = {
                    Log.i("NavHost", "tap ${tab.label} (currentRoute=$currentRoute)")
                    if (currentRoute != tab.route) {
                        val startRoute = navController.graph.findStartDestination().route
                        navController.navigate(tab.route) {
                            // Preserve/restore each tab's back stack & state across switches.
                            // The start destination (Connect) is the popUpTo target, so
                            // restoreState there would re-restore the sibling stack we just
                            // saved (lands you straight back on the old tab) — never restore it.
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = tab.route != startRoute
                        }
                    }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
            )
        }
    }
}
