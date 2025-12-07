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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay

/**
 * A floating popup-style subtitle menu for TV playback.
 * This appears as a contained menu window, not a full-screen overlay.
 */
@OptIn(UnstableApi::class)
@Composable
fun SubtitleMenuPopup(
    viewModel: PlaybackViewModel,
    onDismiss: () -> Unit
) {
    val embeddedSubtitles by viewModel.availableSubtitles.collectAsState()
    var searchResults by remember { mutableStateOf<List<SubtitleEntry>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    val firstItemFocusRequester = remember { FocusRequester() }
    val searchFieldFocusRequester = remember { FocusRequester() }
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
        // Actual menu card - use scrollable column to handle keyboard
        Surface(
            modifier = Modifier
                .width(550.dp)
                .heightIn(min = 250.dp, max = 700.dp)
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
                // Header
                Text(
                    "Subtitles",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )

                Spacer(Modifier.height(12.dp))

                // Search row at TOP - stays visible above keyboard
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search subtitles") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(searchFieldFocusRequester),
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 14.sp
                        )
                    )

                    Button(
                        onClick = {
                            if (searchQuery.isNotBlank()) {
                                isSearching = true
                                viewModel.searchSubtitles(searchQuery, "en", null, null) { results ->
                                    @Suppress("ConvertMapNotNullToMapNotNull")
                                    val mapped = results.mapNotNull { s ->
                                        val fileId = s.attributes.files.firstOrNull()?.fileId
                                        SubtitleEntry(
                                            fileId = fileId,
                                            language = s.attributes.language,
                                            downloadCount = s.attributes.downloadCount,
                                            source = "opensubtitles",
                                            localFilePath = null,
                                            displayLabel = "${s.attributes.language} (${s.attributes.downloadCount} DLs)",
                                            groupIndex = null,
                                            trackIndex = null,
                                            isEmbedded = false
                                        )
                                    }
                                    searchResults = mapped
                                    isSearching = false
                                }
                            }
                        },
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0366D6))
                    ) {
                        SearchButtonIcon()
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Combined list (embedded + search results) - scrollable, takes up available space
                LazyColumn(modifier = Modifier.weight(1f)) {
                    val combined = (embeddedSubtitles + searchResults).distinctBy { it.displayLabel }
                    if (combined.isEmpty() && !isSearching) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No subtitles found. Try searching.", color = Color.Gray)
                            }
                        }
                    } else if (isSearching) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = Color.White)
                            }
                        }
                    } else {
                        itemsIndexed(combined) { index, entry ->
                            SubtitleItem(
                                label = entry.displayLabel,
                                focusRequester = if (index == 0) firstItemFocusRequester else null,
                                onClick = {
                                    when {
                                        entry.isEmbedded -> {
                                            viewModel.selectSubtitle(entry)
                                            isOpen = false
                                            onDismiss()
                                        }
                                        entry.fileId != null -> {
                                            isSearching = true
                                            viewModel.downloadAndApplySubtitleByFileId(entry.fileId) { success ->
                                                isSearching = false
                                                if (success) {
                                                    isOpen = false
                                                    onDismiss()
                                                }
                                            }
                                        }
                                        entry.localFilePath != null -> {
                                            viewModel.selectSubtitle(entry)
                                            isOpen = false
                                            onDismiss()
                                        }
                                    }
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
private fun SubtitleItem(
    label: String,
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
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = Color.Gray,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun SearchButtonIcon() {
    Icon(
        Icons.Default.Search,
        contentDescription = "Search",
        tint = Color.White,
        modifier = Modifier.size(24.dp)
    )
}

