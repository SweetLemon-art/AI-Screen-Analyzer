package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.AskAiScreen
import com.example.ui.ContextScreen
import com.example.ui.HomeScreen
import com.example.ui.LocalModelsScreen
import com.example.ui.MainViewModel
import com.example.ui.MonitorScreen
import com.example.ui.ScreenRoute
import com.example.ui.SettingsScreen
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MyApplicationTheme { MainAppScaffold(viewModel) } }
    }
}

data class NavDestination(
    val route: ScreenRoute,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

@Composable
fun MainAppScaffold(viewModel: MainViewModel) {
    val currentRoute by viewModel.currentRoute.collectAsState()
    val isMonitoring = viewModel.controller.isMonitoring
    val navDestinations = listOf(
        NavDestination(ScreenRoute.HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home, "nav_home"),
        NavDestination(ScreenRoute.MONITOR, "Monitor", Icons.Filled.Monitor, Icons.Outlined.Monitor, "nav_monitor"),
        NavDestination(ScreenRoute.ASK_AI, "Ask AI", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome, "nav_ask_ai"),
        NavDestination(ScreenRoute.CONTEXT, "Context", Icons.Filled.Description, Icons.Outlined.Description, "nav_context"),
        NavDestination(ScreenRoute.LOCAL_AI, "Local AI", Icons.Filled.Storage, Icons.Outlined.Storage, "nav_local_ai"),
        NavDestination(ScreenRoute.SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings")
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Slate950,
        bottomBar = {
            NavigationBar(
                containerColor = Slate900,
                contentColor = Color.White,
                tonalElevation = 8.dp,
                modifier = Modifier.border(1.dp, Slate700.copy(alpha = 0.5f)).windowInsetsPadding(WindowInsets.navigationBars).testTag("main_navigation_bar")
            ) {
                navDestinations.forEach { destination ->
                    val selected = currentRoute == destination.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = { viewModel.navigateTo(destination.route) },
                        modifier = Modifier.testTag(destination.testTag),
                        icon = {
                            if (destination.route == ScreenRoute.MONITOR && isMonitoring) {
                                BadgedBox(badge = { Badge(containerColor = EmeraldSuccess, modifier = Modifier.size(8.dp)) }) {
                                    Icon(if (selected) destination.selectedIcon else destination.unselectedIcon, destination.label)
                                }
                            } else Icon(if (selected) destination.selectedIcon else destination.unselectedIcon, destination.label)
                        },
                        label = { Text(destination.label, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Slate950,
                            selectedTextColor = NeonCyan,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = NeonCyan
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            Crossfade(targetState = currentRoute, label = "ScreenTransition") { route ->
                when (route) {
                    ScreenRoute.HOME -> HomeScreen(viewModel)
                    ScreenRoute.MONITOR -> MonitorScreen(viewModel)
                    ScreenRoute.ASK_AI -> AskAiScreen(viewModel)
                    ScreenRoute.CONTEXT -> ContextScreen(viewModel)
                    ScreenRoute.LOCAL_AI -> LocalModelsScreen()
                    ScreenRoute.SETTINGS -> SettingsScreen(viewModel)
                }
            }
        }
    }
}
