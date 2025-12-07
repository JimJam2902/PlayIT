package com.example.playit

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@UnstableApi
@Composable
fun PlayerScreen(
    viewModel: PlaybackViewModel,
    mediaUrl: String?,
    mediaUri: String?,
    resumePositionMs: Long = 0L,
    onExit: () -> Unit
) {
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var areControlsVisible by remember { mutableStateOf(true) }
    val playPauseFocusRequester = remember { FocusRequester() }

    // Track last user interaction to reset autohide timer
    var lastInteractionMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var controlsHaveFocus by remember { mutableStateOf(false) }

    // Focus requester for the root composable so it can intercept remote key presses when controls are hidden
    val rootFocusRequester = remember { FocusRequester() }

    // Timestamp for the last time we hid controls; used to debounce immediate re-showing
    var lastHideMs by remember { mutableStateOf(0L) }
    // Track last processed key to filter duplicate key events (some remotes double-send)
    var lastProcessedKeyCode by remember { mutableStateOf(-1) }
    var lastProcessedKeyMs by remember { mutableStateOf(0L) }

    // Track whether any child dialog is open (updated by PlayerControls via callback)
    var dialogsOpenState by remember { mutableStateOf(false) }

    fun onUserInteraction() {
        val now = System.currentTimeMillis()
        // If we recently hid the controls (via Back or auto-hide) or we've set a suppression flag,
        // ignore immediate interaction requests to avoid hide/show loops caused by double-sent remote events.
        if (!areControlsVisible && now - lastHideMs < 2000L) {
            val delta = now - lastHideMs
            Log.d("PlayerScreen", "onUserInteraction: ignored due to recent hide (delta=${delta}ms)")
            return
        }

        // Debounce very frequent calls (some remotes or focus events can fire repeatedly)
        val MIN_UPDATE_MS = 300L
        if (areControlsVisible) {
            if (now - lastInteractionMs < MIN_UPDATE_MS) {
                // Too soon since last interaction; ignore to avoid preventing auto-hide
                Log.d("PlayerScreen", "onUserInteraction: debounced (delta=${now-lastInteractionMs}ms)")
                return
            }
        }

        lastInteractionMs = now
        Log.d("PlayerScreen", "onUserInteraction: updated lastInteractionMs=$lastInteractionMs")
        if (!areControlsVisible) {
            areControlsVisible = true
            Log.d("PlayerScreen", "onUserInteraction: showing controls")
        }
    }

    // Background auto-hide loop: checks periodically and hides controls after timeout
    LaunchedEffect(Unit) {
        val timeoutMs = 5000L
        val tick = 200L
        while (true) {
            delay(tick)
            if (!areControlsVisible) continue
            // Don't auto-hide if a dialog is open (user is interacting with it)
            if (dialogsOpenState) {
                Log.d("PlayerScreen", "auto-hide: skipped because dialog is open")
                continue
            }
            val age = System.currentTimeMillis() - lastInteractionMs
            if (age >= timeoutMs) {
                areControlsVisible = false
                lastHideMs = System.currentTimeMillis()
                Log.d("PlayerScreen", "auto-hide triggered; hiding controls at $lastHideMs (age=${age}ms)")
            }
        }
    }

    // Request focus on the play/pause button when controls become visible
    LaunchedEffect(areControlsVisible) {
        Log.d("PlayerScreen", "areControlsVisible changed: $areControlsVisible")
        if (areControlsVisible) {
            playPauseFocusRequester.requestFocus()
        } else {
            // When controls hide, ensure the root can capture the next remote key to reveal controls
            rootFocusRequester.requestFocus()
        }
    }

    // Handle media loading
    LaunchedEffect(mediaUrl, mediaUri, resumePositionMs) {
        val source = mediaUrl ?: mediaUri
        viewModel.playMedia(source, resumePositionMs)
    }

    // Clean up player on exit
    DisposableEffect(Unit) {
        onDispose {
            viewModel.release()
        }
    }

    // Note: Back handling used to always exit. Now if a child dialog is open we delegate to children so they can dismiss.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                Log.d("PlayerScreen", "onPreviewKeyEvent: keyCode=${native.keyCode}")

                val now = System.currentTimeMillis()

                // Filter duplicate key events (some remotes send the same key multiple times very quickly)
                // Use a shorter window (50ms) so legitimate quick single presses aren't delayed while
                // still filtering most hardware double-sends.
                val DUPLICATE_KEY_MS = 5L
                val keyCode = native.keyCode
                if (lastProcessedKeyCode == keyCode && (now - lastProcessedKeyMs) < DUPLICATE_KEY_MS) {
                    Log.d("PlayerScreen", "onPreviewKeyEvent: ignoring duplicate key $keyCode (delta=${now-lastProcessedKeyMs}ms)")
                    // Consume duplicate
                    lastProcessedKeyMs = now
                    return@onPreviewKeyEvent true
                }
                // remember this key as processed
                lastProcessedKeyCode = keyCode
                lastProcessedKeyMs = now

                // BACK: if child dialog is open, let the child handle it (so the dialog dismisses instead of exiting)
                if (native.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    if (dialogsOpenState) {
                        Log.d("PlayerScreen", "onPreviewKeyEvent: Back pressed — delegating to child dialog")
                        // Return false so child Dialog/BackHandler receives the event
                        return@onPreviewKeyEvent false
                    }
                    Log.d("PlayerScreen", "onPreviewKeyEvent: Back pressed — exiting")
                    onExit()
                    return@onPreviewKeyEvent true
                }

                // Helper seek amount (ms)
                val SEEK_MS = 15000L

                // 2) If controls are hidden, handle certain D-Pad keys at the root level so remote works without showing controls
                if (!areControlsVisible) {
                    when (native.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP,
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            // Show controls on Up/Down
                            areControlsVisible = true
                            lastInteractionMs = now
                            Log.d("PlayerScreen", "onPreviewKeyEvent: Up/Down pressed while hidden — showing controls")
                            return@onPreviewKeyEvent true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            // Fast forward even when controls are hidden
                            viewModel.player?.let { p ->
                                val newPos = (p.currentPosition + SEEK_MS).coerceAtMost(p.duration)
                                p.seekTo(newPos)
                                Log.d("PlayerScreen", "onPreviewKeyEvent: hidden -> DPAD_RIGHT seek to $newPos")
                            }
                            // Also show controls to give feedback
                            areControlsVisible = true
                            lastInteractionMs = now
                            return@onPreviewKeyEvent true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                            // Rewind even when controls are hidden
                            viewModel.player?.let { p ->
                                val newPos = (p.currentPosition - SEEK_MS).coerceAtLeast(0L)
                                p.seekTo(newPos)
                                Log.d("PlayerScreen", "onPreviewKeyEvent: hidden -> DPAD_LEFT seek to $newPos")
                            }
                            areControlsVisible = true
                            lastInteractionMs = now
                            return@onPreviewKeyEvent true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> {
                            // Toggle play/pause even when hidden
                            viewModel.playPause()
                            Log.d("PlayerScreen", "onPreviewKeyEvent: hidden -> CENTER toggled play/pause")
                            // Show controls to provide feedback
                            areControlsVisible = true
                            lastInteractionMs = now
                            return@onPreviewKeyEvent true
                        }
                        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                        android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            viewModel.playPause()
                            Log.d("PlayerScreen", "onPreviewKeyEvent: hidden -> media key toggled play/pause")
                            areControlsVisible = true
                            lastInteractionMs = now
                            return@onPreviewKeyEvent true
                        }
                        else -> {
                            // Other keys: show controls and consume
                            areControlsVisible = true
                            lastInteractionMs = now
                            Log.d("PlayerScreen", "onPreviewKeyEvent: hidden -> other key, showing controls")
                            return@onPreviewKeyEvent true
                        }
                    }
                } else {
                    // 3) Controls are visible: delegate to child focus system so D-pad navigates controls.
                    // The focused child (SeekBar / Play button) will handle left/right/center as appropriate.
                    return@onPreviewKeyEvent false
                }
            }
    ) {
        // Video Player View
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = viewModel.player
                    useController = false // We use our own custom controls
                    // Prevent the underlying PlayerView from stealing focus from Compose so the root can
                    // intercept remote key presses and show controls when hidden.
                    this.isFocusable = false
                    this.isFocusableInTouchMode = false
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Loading Overlay
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(60.dp)
                )
            }
        }

        // Animated Controls Overlay
        AnimatedVisibility(
            visible = areControlsVisible && !isLoading,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            PlayerControls(
                viewModel = viewModel,
                onExit = onExit,
                modifier = Modifier.align(Alignment.BottomCenter),
                playPauseFocusRequester = playPauseFocusRequester,
                controlsVisible = areControlsVisible,
                onUserInteraction = { onUserInteraction() },
                onControlsFocusChanged = { controlsHaveFocus = it },
                onDialogsOpenChanged = { open -> dialogsOpenState = open }
            )
        }
    }
}
