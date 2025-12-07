package com.example.playit

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay

/**
 * A floating popup-style audio menu for TV playback.
 * Uses Dialog composable to avoid fullscreen overlays that block navigation.
 */
@OptIn(UnstableApi::class)
@Composable
fun AudioMenuPopup(
    viewModel: PlaybackViewModel,
    onDismiss: () -> Unit
) {
    val audioTracks by viewModel.availableAudioTracks.collectAsState()
    val selectedIndex by viewModel.selectedAudioTrackIndex.collectAsState()
    val isBoostEnabled by viewModel.isAudioBoostEnabled.collectAsState()

    val firstItemFocusRequester = remember { FocusRequester() }
    var isOpen by remember { mutableStateOf(true) }

    BackHandler(enabled = isOpen) {
        isOpen = false
        onDismiss()
    }

    if (!isOpen) return

    // Use Dialog to avoid fullscreen Box that blocks navigation
    Dialog(
        onDismissRequest = {
            isOpen = false
            onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        // Menu card - positioned in the center without fullscreen wrapper
        Surface(
            modifier = Modifier
                .width(500.dp)
                .heightIn(min = 200.dp, max = 600.dp)
                .clip(RoundedCornerShape(12.dp))
                .onKeyEvent { event ->
                    if (event.nativeKeyEvent.action != AndroidKeyEvent.ACTION_DOWN) {
                        return@onKeyEvent false
                    }
                    when (event.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_BACK, AndroidKeyEvent.KEYCODE_ESCAPE -> {
                            isOpen = false
                            onDismiss()
                            true
                        }
                        else -> false
                    }
                },
            color = Color(0xFF1E1E1E),
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header with title and boost toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Audio Tracks",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )

                    BoostToggle(
                        isBoostEnabled = isBoostEnabled,
                        onToggle = { viewModel.toggleAudioBoost() }
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Audio tracks list
                LazyColumn(modifier = Modifier.weight(1f)) {
                    if (audioTracks.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No audio tracks", color = Color.Gray)
                            }
                        }
                    } else {
                        itemsIndexed(audioTracks) { index, track ->
                            AudioTrackItem(
                                label = track.label,
                                isSelected = index == selectedIndex,
                                focusRequester = if (index == 0) firstItemFocusRequester else null,
                                onClick = {
                                    viewModel.selectAudioTrack(index)
                                    isOpen = false
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(50)
        try {
            firstItemFocusRequester.requestFocus()
        } catch (_: Exception) {
            // Menu will handle focus naturally
        }
    }
}

@Composable
private fun AudioTrackItem(
    label: String,
    isSelected: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    isFocused -> Color.White.copy(alpha = 0.2f)
                    isSelected -> Color(0xFF0366D6)
                    else -> Color.Transparent
                }
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action != AndroidKeyEvent.ACTION_DOWN) {
                    return@onKeyEvent false
                }
                when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> {
                        onClick()
                        true
                    }
                    else -> false
                }
            }
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            color = if (isSelected) Color.White else Color.Gray,
            fontSize = 16.sp,
            fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
        )

        if (isSelected) {
            Text("✓", color = Color(0xFF0366D6), fontSize = 20.sp)
        }
    }
}

@Composable
private fun BoostToggle(
    isBoostEnabled: Boolean,
    onToggle: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isFocused) Color.White.copy(alpha = 0.1f) else Color.Transparent)
            .padding(8.dp)
            .clickable(interactionSource = interactionSource, indication = null) { onToggle() }
            .focusable(interactionSource = interactionSource)
    ) {
        Text(
            "Boost",
            color = Color.White,
            fontSize = 12.sp
        )
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = isBoostEnabled,
            onCheckedChange = { onToggle() }
        )
    }
}

