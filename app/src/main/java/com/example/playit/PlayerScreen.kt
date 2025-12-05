package com.example.playit

import android.net.Uri
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    mediaUrl: String?,
    mediaUri: Uri?,
    onExit: () -> Unit,
    viewModel: PlaybackViewModel
) {

    // 🔥 Start playback automatically
    LaunchedEffect(mediaUrl, mediaUri) {
        val urlToPlay = mediaUrl ?: mediaUri?.toString()
        if (urlToPlay != null) {
            viewModel.playMedia(urlToPlay)
        }
    }

    val isLoading by viewModel.isLoading.collectAsState()

    // Auto-hide controls
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableStateOf(System.currentTimeMillis()) }
    var lastHide by remember { mutableStateOf(0L) }

    // Required for new PlayerControls()
    val playPauseFocusRequester = remember { FocusRequester() }

    // Track if any dialog from PlayerControls is open
    var dialogsOpen by remember { mutableStateOf(false) }

    // Auto-hide timer
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) return@LaunchedEffect

        val timeout = 5000L
        while (controlsVisible) {
            delay(250)
            if (System.currentTimeMillis() - lastInteraction >= timeout) {
                controlsVisible = false
                lastHide = System.currentTimeMillis()
            }
        }
    }

    // Stremio external-player rule: BACK must exit
    BackHandler { onExit() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                val nk = ev.nativeKeyEvent
                if (nk.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false

                if (nk.keyCode == AndroidKeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false

                // Reveal controls first when hidden
                if (!controlsVisible && !dialogsOpen) {
                    controlsVisible = true
                    lastInteraction = System.currentTimeMillis()
                    return@onPreviewKeyEvent true
                }

                false
            }
    ) {
        // VIDEO VIEW
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { useController = false } },
            update = { it.player = viewModel.player },
            modifier = Modifier.fillMaxSize()
        )

        // BUFFERING UI
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        // CONTROLS UI
        if (controlsVisible && !isLoading) {
            PlayerControls(
                viewModel = viewModel,
                onExit = onExit,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 36.dp),
                playPauseFocusRequester = playPauseFocusRequester,
                controlsVisible = controlsVisible,
                onUserInteraction = {
                    lastInteraction = System.currentTimeMillis()
                },
                onControlsFocusChanged = { /* no-op */ },
                onShowDiagnostics = { /* no-op or show diagnostics */ },
                onDialogsOpenChanged = { dialogsOpen = it }
            )
        }

        // TAP ANYWHERE TO TOGGLE
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable {
                    val now = System.currentTimeMillis()
                    if (now - lastHide > 150) {
                        controlsVisible = !controlsVisible
                        lastInteraction = now
                    }
                }
        )
    }
}
