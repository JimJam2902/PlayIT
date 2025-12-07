package com.example.playit

import androidx.media3.common.util.UnstableApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// Brand Colors
val NetflixRed = Color(0xFFE50914)
val ChipBackground = Color(0xFF202020)

@UnstableApi
@Composable
fun PlayerControls(
    viewModel: PlaybackViewModel,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    playPauseFocusRequester: FocusRequester,
    // Whether the controls are currently visible (used to avoid re-show during exit animation)
    controlsVisible: Boolean = true,
    // Notifies the parent that the user interacted (useful to reset autohide timer)
    onUserInteraction: () -> Unit = {},
    // Notifies parent whether any control is currently focused
    onControlsFocusChanged: (Boolean) -> Unit = {},
    // Notify parent when any dialog opens/closes so parent can avoid consuming BACK
    onDialogsOpenChanged: (Boolean) -> Unit = {}
) {
    val isPlaying by viewModel.isPlaying.collectAsState()
    val title by viewModel.title.collectAsState()
    val positionFraction by viewModel.positionFraction.collectAsState()
    val bufferedFraction by viewModel.bufferedFraction.collectAsState()
    val durationSeconds = viewModel.totalSeconds
    var showAudioDialog by remember { mutableStateOf(false) }
    var showSubtitleFull by remember { mutableStateOf(false) }

    // Compute whether any dialog from the parent is currently showing so the child can avoid
    // intercepting keys when a dialog needs to receive D-pad input.
    val dialogsOpen = showAudioDialog || showSubtitleFull

    // Notify parent when dialogsOpen changes
    LaunchedEffect(dialogsOpen) { onDialogsOpenChanged(dialogsOpen) }

    // PlayerControlsContent now just renders the UI.
    PlayerControlsContent(
        modifier = modifier,
        isPlaying = isPlaying,
        title = title,
        positionFraction = positionFraction,
        bufferedFraction = bufferedFraction,
        durationSeconds = durationSeconds,
        onPlayPause = {
            viewModel.playPause()
            onUserInteraction()
        },
        onSeek = { viewModel.seekToFraction(it) },
        onAudioSettings = { showAudioDialog = true; onUserInteraction() },
        onDownloadSubtitle = { showSubtitleFull = true; onUserInteraction() },
        onExit = onExit,
        playPauseFocusRequester = playPauseFocusRequester,
        onInteraction = onUserInteraction,
        onControlsFocusChanged = onControlsFocusChanged,
        controlsVisible = controlsVisible // Pass down the visibility state
    )

    // ------------------ AUDIO MENU POPUP ------------------
    if (showAudioDialog) {
        AudioMenuPopup(
            viewModel = viewModel,
            onDismiss = {
                showAudioDialog = false
                onUserInteraction()
            }
        )
    }

    // ------------------ SUBTITLE MENU POPUP ------------------
    if (showSubtitleFull) {
        SubtitleMenuPopup(
            viewModel = viewModel,
            onDismiss = {
                showSubtitleFull = false
                onUserInteraction()
            }
        )
    }
}

@Composable
fun PlayerControlsContent(
    modifier: Modifier = Modifier,
    isPlaying: Boolean,
    title: String,
    positionFraction: Float,
    bufferedFraction: Float,
    durationSeconds: Int,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onAudioSettings: () -> Unit,
    onDownloadSubtitle: () -> Unit,
    onExit: () -> Unit,
    playPauseFocusRequester: FocusRequester,
    // Whether the controls are currently visible (used to avoid re-show during exit animation)
    controlsVisible: Boolean = true,
    onInteraction: () -> Unit = {},
    onControlsFocusChanged: (Boolean) -> Unit = {}
) {
    // Interaction sources and focus requesters for controls we want to track
    val playPauseInteraction = remember { MutableInteractionSource() }
    val seekInteraction = remember { MutableInteractionSource() }
    val chip1Interaction = remember { MutableInteractionSource() }
    val chip2Interaction = remember { MutableInteractionSource() }

    val seekFocusRequester = remember { FocusRequester() }

    // Observe focus state for each tracked control and report up to parent
    val playPauseFocused by playPauseInteraction.collectIsFocusedAsState()
    val seekFocused by seekInteraction.collectIsFocusedAsState()
    val chip1Focused by chip1Interaction.collectIsFocusedAsState()
    val chip2Focused by chip2Interaction.collectIsFocusedAsState()

    LaunchedEffect(playPauseFocused, seekFocused, chip1Focused, chip2Focused) {
        val anyFocused = playPauseFocused || seekFocused || chip1Focused || chip2Focused
        onControlsFocusChanged(anyFocused)
        // Do NOT treat focus-as-interaction here. Focus changes happen during composable lifecycle
        // transitions (AnimatedVisibility) and can spam the autohide timer. Only explicit events
        // (key-downs, clicks, seek) should call onInteraction.
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        // --- 1. Top Bar with Gradient ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.8f),
                            Color.Transparent
                        )
                    )
                )
                .padding(top = 20.dp, start = 40.dp, end = 40.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Back Button
            IconButton(
                onClick = {
                    onExit()
                    onInteraction()
                },
                modifier = Modifier
                    .focusable()
                    .background(Color.Transparent, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            // Title
            Text(
                text = title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // --- 2. Bottom Controls Section ---
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.8f)
                        )
                    )
                )
                .padding(bottom = 20.dp, start = 40.dp, end = 40.dp, top = 20.dp)
        ) {
            // Row: [Play/Pause] [Time] [SeekBar] [TotalTime]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play/Pause Circle Button: focused = white circle with black icon, unfocused = grey circle with white icon
                val playBgColor = if (playPauseFocused) Color.White else Color.Transparent.copy(alpha = 0.6f)
                val playIconTint = if (playPauseFocused) Color.Black else Color.White

                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .then(if (playPauseFocused) Modifier.border(2.dp, Color.Black.copy(alpha = 0.08f), CircleShape) else Modifier)
                        .clip(CircleShape)
                        .background(playBgColor)
                        .clickable(
                            interactionSource = playPauseInteraction,
                            indication = null,
                            onClick = {
                                onPlayPause()
                            }
                        )
                        .focusRequester(playPauseFocusRequester)
                        .focusProperties { next = seekFocusRequester }
                        .focusable(interactionSource = playPauseInteraction),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = playIconTint,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Spacer(modifier = Modifier.width(20.dp))

                // Current Time
                Text(
                    text = formatTimeInline((positionFraction * durationSeconds).roundToInt()),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.widthIn(min = 50.dp)
                )

                Spacer(modifier = Modifier.width(16.dp))

                // Custom SeekBar
                Box(modifier = Modifier.weight(1f)) {
                    SeekBar(
                        position = positionFraction,
                        buffered = bufferedFraction,
                        onSeek = {
                            onSeek(it)
                            if (controlsVisible) onInteraction()
                        },
                        durationSeconds = durationSeconds,
                        focusRequester = seekFocusRequester,
                        interactionSource = seekInteraction,
                        onUserInteraction = onInteraction
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Total Duration
                Text(
                    text = formatTimeInline(durationSeconds),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.widthIn(min = 50.dp),
                    textAlign = TextAlign.End
                )
            }

            Spacer(modifier = Modifier.height(0.dp))

            // Row: [Audio] [Subtitles]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerChip(
                    text = "Audio",
                    onClick = { onAudioSettings(); if (controlsVisible) onInteraction() },
                    interactionSource = chip1Interaction,
                    onFocusChange = { focused -> if (controlsVisible) onControlsFocusChanged(focused) },
                    focusedBackground = Color.White.copy(alpha = 0.2f),
                    unfocusedBackground = Color.Transparent
                )
                Spacer(modifier = Modifier.width(16.dp))
                PlayerChip(
                    text = "Subtitles",
                    onClick = { onDownloadSubtitle(); if (controlsVisible) onInteraction() },
                    interactionSource = chip2Interaction,
                    onFocusChange = { focused -> if (controlsVisible) onControlsFocusChanged(focused) },
                    focusedBackground = Color.White.copy(alpha = 0.2f),
                    unfocusedBackground = Color.Transparent
                )
            }
        }
    }
}

// --- Helper Composable for the Chips ---
@Composable
fun PlayerChip(
    text: String,
    onClick: () -> Unit,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onFocusChange: (Boolean) -> Unit = {},
    focusedBackground: Color = ChipBackground,
    unfocusedBackground: Color = ChipBackground
) {
    val isFocused by interactionSource.collectIsFocusedAsState()

    // Notify parent when focus changes
    LaunchedEffect(isFocused) { onFocusChange(isFocused) }

    // Visual State Logic: focused/unfocused backgrounds are configurable
    val backgroundColor = if (isFocused) focusedBackground else unfocusedBackground
    // When unfocused we use a transparent border to match the transparent background requirement
    val borderColor = if (isFocused) Color.White else Color.Transparent

    Box(
        modifier = Modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null, // Custom highlight handled by background color
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .background(backgroundColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// --- Modified SeekBar (kept identical to your original) ---
@Composable
private fun SeekBar(
    position: Float,
    buffered: Float,
    onSeek: (Float) -> Unit,
    durationSeconds: Int,
    focusRequester: FocusRequester,
    interactionSource: MutableInteractionSource,
    onUserInteraction: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        var trackWidthPx by remember { mutableFloatStateOf(0f) }
        val trackHeight = 3.dp
        val thumbSizeFocused = 20.dp
        val thumbSize = 16.dp

        var internalPos by remember { mutableFloatStateOf(position) }
        LaunchedEffect(position) { if (internalPos != position) internalPos = position }

        val isFocused by interactionSource.collectIsFocusedAsState()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(2.dp))
                .onSizeChanged { trackWidthPx = it.width.toFloat() }
                .background(Color.Gray.copy(alpha = 0.5f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = buffered)
                    .height(trackHeight)
                    .background(Color.Gray)
            )

            val displayPos = if (isFocused) internalPos else position
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = displayPos)
                    .height(trackHeight)
                    .background(NetflixRed)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .focusRequester(focusRequester)
                .focusable(interactionSource = interactionSource)
                .onKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action != android.view.KeyEvent.ACTION_DOWN) return@onKeyEvent false

                    val stepSeconds = 5
                    val deltaFraction = if (durationSeconds > 0) {
                        (stepSeconds.toFloat() / durationSeconds.toFloat()).coerceIn(0f, 1f)
                    } else 0.02f

                    when (native.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                            val new = (internalPos - deltaFraction).coerceIn(0f, 1f)
                            internalPos = new
                            onSeek(new)
                            onUserInteraction()
                            true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val new = (internalPos + deltaFraction).coerceIn(0f, 1f)
                            internalPos = new
                            onSeek(new)
                            onUserInteraction()
                            true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> {
                            false
                        }
                        else -> false
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (trackWidthPx <= 0f) return@detectTapGestures
                        val new = (offset.x / trackWidthPx).coerceIn(0f, 1f)
                        internalPos = new
                        onSeek(new)
                        onUserInteraction()
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, _ ->
                            if (trackWidthPx <= 0f) return@detectDragGestures
                            val new = (change.position.x / trackWidthPx).coerceIn(0f, 1f)
                            internalPos = new
                            onSeek(new)
                            onUserInteraction()
                        }
                    )
                },
            contentAlignment = Alignment.CenterStart
        ) {
            val offsetPx = (internalPos * (trackWidthPx.coerceAtLeast(1f))).roundToInt()
            val offsetDp = with(density) { offsetPx.toDp() }

            Box(
                modifier = Modifier
                    .offset(x = offsetDp - (if (isFocused) thumbSizeFocused else thumbSize) / 2)
                    .size(if (isFocused) thumbSizeFocused else thumbSize)
                    .zIndex(10f)
                    .then(if (isFocused) Modifier.border(2.dp, NetflixRed, CircleShape) else Modifier)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

private fun formatTimeInline(sec: Int): String {
    if (sec < 0) return "00:00"
    val hours = sec / 3600
    val remainder = sec % 3600
    val minutes = remainder / 60
    val seconds = remainder % 60
    return if (hours > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    }
}

@Preview(device = "id:tv_1080p", showBackground = true, widthDp = 960, heightDp = 540)
@Composable
fun PlayerControlsPreview() {
    var isPlaying by remember { mutableStateOf(false) }
    val title = "Video Title"
    val durationSeconds = 15 * 60 + 45
    var positionFraction by remember { mutableFloatStateOf(0.12f) }
    var bufferedFraction by remember { mutableFloatStateOf(0.35f) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isPlaying) {
        while (true) {
            if (isPlaying) {
                delay(1000L)
                positionFraction = (positionFraction + 1f / durationSeconds).coerceAtMost(1f)
            } else delay(200L)
        }
    }

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            PlayerControlsContent(
                modifier = Modifier,
                isPlaying = isPlaying,
                title = title,
                positionFraction = positionFraction,
                bufferedFraction = bufferedFraction,
                durationSeconds = durationSeconds,
                onPlayPause = { isPlaying = !isPlaying },
                onSeek = { positionFraction = it },
                onAudioSettings = { /* Preview doesn't need actual implementation */ },
                onDownloadSubtitle = { },
                onExit = { },
                playPauseFocusRequester = focusRequester,
                onInteraction = {},
                onControlsFocusChanged = {}
            )
        }
    }
}
