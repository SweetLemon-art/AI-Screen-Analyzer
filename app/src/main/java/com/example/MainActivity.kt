package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Settings
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
import com.example.ui.ContextScreen
import com.example.ui.HomeScreen
import com.example.ui.MainViewModel
import com.example.ui.MonitorScreen
import com.example.ui.ScreenRoute
import com.example.ui.SettingsScreen
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainAppScaffold(viewModel = viewModel)
            }
        }
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
        NavDestination(
            route = ScreenRoute.HOME,
            label = "Home",
            selectedIcon = Icons.Filled.Home,
            unselectedIcon = Icons.Outlined.Home,
            testTag = "nav_home"
        ),
        NavDestination(
            route = ScreenRoute.MONITOR,
            label = "Monitor",
            selectedIcon = Icons.Filled.Monitor,
            unselectedIcon = Icons.Outlined.Monitor,
            testTag = "nav_monitor"
        ),
        NavDestination(
            route = ScreenRoute.CONTEXT,
            label = "Context",
            selectedIcon = Icons.Filled.Description,
            unselectedIcon = Icons.Outlined.Description,
            testTag = "nav_context"
        ),
        NavDestination(
            route = ScreenRoute.SETTINGS,
            label = "Settings",
            selectedIcon = Icons.Filled.Settings,
            unselectedIcon = Icons.Outlined.Settings,
            testTag = "nav_settings"
        )
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Slate950,
        bottomBar = {
            NavigationBar(
                containerColor = Slate900,
                contentColor = Color.White,
                tonalElevation = 8.dp,
                modifier = Modifier
                    .border(
                        width = 1.dp,
                        color = Slate700.copy(alpha = 0.5f)
                    )
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .testTag("main_navigation_bar")
            ) {
                navDestinations.forEach { destination ->
                    val isSelected = currentRoute == destination.route

                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { viewModel.navigateTo(destination.route) },
                        modifier = Modifier.testTag(destination.testTag),
                        icon = {
                            if (destination.route == ScreenRoute.MONITOR && isMonitoring) {
                                BadgedBox(
                                    badge = {
                                        Badge(
                                            containerColor = EmeraldSuccess,
                                            modifier = Modifier.size(8.dp)
                                        )
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) destination.selectedIcon else destination.unselectedIcon,
                                        contentDescription = destination.label
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = if (isSelected) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = destination.label
                                )
                            }
                        },
                        label = {
                            Text(
                                text = destination.label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Crossfade(
                targetState = currentRoute,
                label = "ScreenTransition"
            ) { route ->
                when (route) {
                    ScreenRoute.HOME -> HomeScreen(viewModel = viewModel)
                    ScreenRoute.MONITOR -> MonitorScreen(viewModel = viewModel)
                    ScreenRoute.CONTEXT -> ContextScreen(viewModel = viewModel)
                    ScreenRoute.SETTINGS -> SettingsScreen(viewModel = viewModel)
                }
            }
        }
    }
}
