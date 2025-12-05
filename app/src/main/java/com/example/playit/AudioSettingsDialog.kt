package com.example.playit

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay

/**
 * Full-screen audio settings that uses PlaybackViewModel APIs.
 * Expects PlaybackViewModel.availableAudioTracks: StateFlow<List<AudioTrackInfo>>
 * and PlaybackViewModel.selectedAudioTrackIndex: StateFlow<Int>
 * and PlaybackViewModel.isAudioBoostEnabled: StateFlow<Boolean>
 * and functions selectAudioTrack(index), toggleAudioBoost()
 */
@OptIn(UnstableApi::class)
@Composable
fun AudioSettingsFullScreen(
    viewModel: PlaybackViewModel,
    onDismiss: () -> Unit
) {
    val audioTracks by viewModel.availableAudioTracks.collectAsState()
    val selectedIndex by viewModel.selectedAudioTrackIndex.collectAsState()
    val isBoostEnabled by viewModel.isAudioBoostEnabled.collectAsState()

    val closeFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    BackHandler { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable { /* consume outside clicks */ },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(Color(0xFF161616))
                .padding(36.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Audio Settings", color = Color.White, fontSize = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Choose an audio track", color = Color.Gray)
                }

                FullScreenDialogSwitch(text = "Boost", checked = isBoostEnabled, onToggle = { viewModel.toggleAudioBoost() })
                Spacer(Modifier.width(12.dp))

                FullScreenCloseButton(text = "Close", focusRequester = closeFocusRequester, onClick = onDismiss)
            }

            Spacer(Modifier.height(20.dp))

            Text("Available Tracks", color = Color.Gray)
            Spacer(Modifier.height(8.dp))

            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                if (audioTracks.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text("No audio tracks found", color = Color.Gray)
                        }
                    }
                } else {
                    itemsIndexed(audioTracks) { index, track ->
                        // The viewModel's audio track type usually exposes 'label' — adapt if needed.
                        val label = track.label
                        FullScreenDialogRow(
                            text = label,
                            selected = index == selectedIndex,
                            onClick = { viewModel.selectAudioTrack(index) },
                            onKeyCode = { key ->
                                when (key) {
                                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                                    android.view.KeyEvent.KEYCODE_ENTER -> {
                                        viewModel.selectAudioTrack(index); true
                                    }
                                    else -> false
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(40)
        closeFocusRequester.requestFocus()
    }
}
