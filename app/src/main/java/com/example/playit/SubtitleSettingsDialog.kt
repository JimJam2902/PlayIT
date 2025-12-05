package com.example.playit

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay

/**
 * Unified subtitles full-screen dialog. Uses PlaybackViewModel APIs:
 * - availableSubtitles: StateFlow<List<SubtitleEntry>>
 * - selectSubtitle(entry: SubtitleEntry)
 * - searchSubtitles(query, lang, ... , onResult)
 * - downloadAndApplySubtitleByFileId(fileId, onComplete)
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SubtitleSettingsFullScreen(
    viewModel: PlaybackViewModel,
    onDismiss: () -> Unit
) {
    val liveEntries by viewModel.availableSubtitles.collectAsState()
    var localSearchResults by remember { mutableStateOf<List<SubtitleEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val closeFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }

    BackHandler { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable { /* consume */ }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1C1C1C))
                .padding(36.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Subtitles", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(6.dp))
                    Text("Embedded + OpenSubtitles search", color = Color.Gray)
                }

                FullScreenCloseButton(text = "Close", focusRequester = closeFocusRequester, onClick = onDismiss)
            }

            Spacer(Modifier.height(20.dp))

            // Search row
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search OpenSubtitles") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(searchFocusRequester)
                )

                Spacer(Modifier.width(12.dp))

                Button(onClick = {
                    // call viewModel.searchSubtitles(...) and map results via callback
                    viewModel.searchSubtitles(query, "en", null, null) { results ->
                        // Map results to SubtitleEntry — adapt to your model; this is a safe best-effort mapping
                        val mapped = results.mapNotNull { s ->
                            val fileId = s.attributes.files.firstOrNull()?.fileId
                            SubtitleEntry(
                                fileId = fileId,
                                language = s.attributes.language,
                                downloadCount = s.attributes.downloadCount,
                                source = "opensubtitles",
                                localFilePath = null,
                                displayLabel = "${s.attributes.language} (downloads=${s.attributes.downloadCount})",
                                groupIndex = null,
                                trackIndex = null,
                                isEmbedded = false
                            )
                        }
                        localSearchResults = mapped
                    }
                }) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                    Spacer(Modifier.width(8.dp))
                    Text("Search")
                }
            }

            Spacer(Modifier.height(18.dp))

            Text("Available Subtitles", color = Color.Gray)
            Spacer(Modifier.height(8.dp))

            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                val combined = (liveEntries + localSearchResults).distinctBy { it.displayLabel + "|" + (it.fileId ?: -1L) }
                if (combined.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text("No subtitles found. Try searching.", color = Color.Gray)
                        }
                    }
                } else {
                    itemsIndexed(combined) { index, entry ->
                        FullScreenDialogRow(
                            text = entry.displayLabel,
                            selected = false,
                            onClick = {
                                if (entry.isEmbedded) {
                                    viewModel.selectSubtitle(entry)
                                    onDismiss()
                                } else if (entry.fileId != null) {
                                    viewModel.downloadAndApplySubtitleByFileId(entry.fileId) { success ->
                                        if (success) onDismiss()
                                    }
                                } else if (entry.localFilePath != null) {
                                    viewModel.selectSubtitle(entry)
                                    onDismiss()
                                }
                            },
                            onKeyCode = { key ->
                                when (key) {
                                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> {
                                        if (entry.isEmbedded) {
                                            viewModel.selectSubtitle(entry)
                                        } else if (entry.fileId != null) {
                                            viewModel.downloadAndApplySubtitleByFileId(entry.fileId) { _ -> }
                                        } else if (entry.localFilePath != null) {
                                            viewModel.selectSubtitle(entry)
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Tip: Press center to select", color = Color.Gray)
            }
        }
    }

    LaunchedEffect(Unit) { delay(40); searchFocusRequester.requestFocus() }
}
