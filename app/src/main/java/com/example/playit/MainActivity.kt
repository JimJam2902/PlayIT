package com.example.playit

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.tooling.preview.Preview
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.playit.ui.theme.PlayITTheme
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi

@UnstableApi
class MainActivity : ComponentActivity() {
    private val playbackViewModel: PlaybackViewModel by viewModels()

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PlayITTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape
                ) {
                    AppNavHost(viewModel = playbackViewModel)
                }
            }
        }
    }
}

@UnstableApi
@Composable
fun AppNavHost(viewModel: PlaybackViewModel) {
    val navController = rememberNavController()
    val settingsRepository = SettingsRepository(LocalContext.current)
    val subtitleRepository = SubtitleRepository(LocalContext.current)

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpenUrl = { url ->
                    // URL encode the URL for safe navigation
                    val encodedUrl = Uri.encode(url)
                    navController.navigate("player?url=$encodedUrl")
                },
                onOpenFile = { uriString ->
                    // Pass the URI string directly
                    val encodedUri = Uri.encode(uriString)
                    navController.navigate("player?uri=$encodedUri")
                },
                onSettings = { navController.navigate("settings") }
            )
        }
        composable("settings") {
            SettingsScreen(settingsRepository = settingsRepository, subtitleRepository = subtitleRepository, onBack = { navController.popBackStack() })
        }
        composable(
            "player?url={url}&uri={uri}",
            arguments = listOf(
                navArgument("url") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("uri") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val url = backStackEntry.arguments?.getString("url")
            // FIX #1: Convert the URI string back to a Uri? object
            val uriString = backStackEntry.arguments?.getString("uri")
            val uri = uriString?.let { Uri.parse(it) }

            PlayerScreen(
                viewModel = viewModel,
                mediaUrl = url,
                mediaUri = uri,
                onExit = { navController.popBackStack() }
            )
        }
    }
}


@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    PlayITTheme {
        // Simple preview of the home screen placeholder
        HomeScreen(onOpenUrl = {}, onOpenFile = {}, onSettings = {})
    }
}