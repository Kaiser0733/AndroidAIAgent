package com.kaiser.aiagent.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kaiser.aiagent.ui.navigation.Destinations
import com.kaiser.aiagent.ui.screens.about.AboutScreen
import com.kaiser.aiagent.ui.screens.chat.ChatScreen
import com.kaiser.aiagent.ui.screens.debug.DebugScreen
import com.kaiser.aiagent.ui.screens.home.HomeScreen
import com.kaiser.aiagent.ui.screens.launch.LaunchScreen
import com.kaiser.aiagent.ui.screens.models.ModelsScreen
import com.kaiser.aiagent.ui.screens.settings.SettingsScreen
import com.kaiser.aiagent.ui.theme.AndroidAIAgentTheme

/**
 * Single-activity host. v0.3 nav graph adds CHAT and DEBUG
 * destinations. The Debug screen is intentionally not surfaced in the
 * Home screen UI — it's reachable from Settings → "Debug" button.
 */
class MainActivity : ComponentActivity() {

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
                onOpenAbout = { navController.navigate(Destinations.ABOUT.route) },
                onOpenChat = { navController.navigate(Destinations.CHAT.route) }
            )
        }
        composable(Destinations.SETTINGS.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenAbout = { navController.navigate(Destinations.ABOUT.route) },
                onOpenDebug = { navController.navigate(Destinations.DEBUG.route) },
                onOpenModels = { navController.navigate(Destinations.MODELS.route) }
            )
        }
        composable(Destinations.ABOUT.route) {
            AboutScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Destinations.CHAT.route) {
            ChatScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Destinations.DEBUG.route) {
            DebugScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Destinations.MODELS.route) {
            ModelsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
