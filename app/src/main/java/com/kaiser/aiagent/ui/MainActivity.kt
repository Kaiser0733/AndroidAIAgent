package com.kaiser.aiagent.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kaiser.aiagent.data.remote.RemoteConfigRepository
import com.kaiser.aiagent.ui.navigation.Destinations
import com.kaiser.aiagent.ui.screens.about.AboutScreen
import com.kaiser.aiagent.ui.screens.home.HomeScreen
import com.kaiser.aiagent.ui.screens.launch.LaunchScreen
import com.kaiser.aiagent.ui.screens.settings.SettingsScreen
import com.kaiser.aiagent.ui.theme.AndroidAIAgentTheme
import kotlinx.coroutines.delay
import org.koin.android.ext.android.inject

/**
 * Single-activity host for the entire app. v0.1 uses a flat Compose
 * Navigation graph; future versions can split this into multiple
 * `NavGraph` helpers per feature module.
 */
class MainActivity : ComponentActivity() {

    private val remoteConfigRepo: RemoteConfigRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidAIAgentTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavGraph()
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun AppNavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Destinations.startDestination.route
    ) {
        composable(Destinations.LAUNCH.route) {
            LaunchScreen(
                onTimeout = {
                    navController.navigate(Destinations.HOME.route) {
                        popUpTo(Destinations.LAUNCH.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Destinations.HOME.route) {
            HomeScreen(
                onOpenSettings = { navController.navigate(Destinations.SETTINGS.route) },
                onOpenAbout = { navController.navigate(Destinations.ABOUT.route) }
            )
        }
        composable(Destinations.SETTINGS.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenAbout = { navController.navigate(Destinations.ABOUT.route) }
            )
        }
        composable(Destinations.ABOUT.route) {
            AboutScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
