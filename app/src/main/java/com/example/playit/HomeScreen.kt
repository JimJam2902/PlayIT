package com.example.playit

import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.launch
import android.view.KeyEvent as AndroidKeyEvent


// *****************************************************************************************
//  TV BUTTON COMPONENT (Focusable + DPAD navigation)
// *****************************************************************************************

@Composable
private fun TvButton(
    text: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    onDpadDown: () -> Boolean = { false },
    onDpadUp: () -> Boolean = { false },
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .padding(8.dp)
            .size(width = 380.dp, height = 72.dp)
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event: ComposeKeyEvent ->
                if (event.nativeKeyEvent.action != AndroidKeyEvent.ACTION_DOWN) {
                    return@onKeyEvent false
                }
                val keyCode = event.nativeKeyEvent.keyCode
                when (keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        onDpadDown()
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        onDpadUp()
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                    AndroidKeyEvent.KEYCODE_ENTER -> {
                        onClick()
                        true
                    }
                    else -> false
                }
            }
            .background(
                if (isFocused) Color.White else Color(0xFF3A3A3A),
                RoundedCornerShape(8.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isFocused) Color.Black else Color.Gray,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
    }
}



// *****************************************************************************************
//  HOME SCREEN
// *****************************************************************************************

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(
    onOpenUrl: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var showUrlDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }

    val urlButtonFocusRequester = remember { FocusRequester() }
    val fileButtonFocusRequester = remember { FocusRequester() }
    val settingsButtonFocusRequester = remember { FocusRequester() }

    val readPermissionState = rememberPermissionState(
        permission = Manifest.permission.READ_EXTERNAL_STORAGE
    )

    // In HomeScreen.kt, make sure the file picker callback is correct:
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            // Pass the URI string to the callback
            val uriString = uri.toString()
            onOpenFile(uriString)
        }
    }

    LaunchedEffect(Unit) {
        urlButtonFocusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // ----------------------------------------------------------------------------------
        // Open URL Button
        // ----------------------------------------------------------------------------------
        TvButton(
            text = "Open URL",
            onClick = {
                showUrlDialog = true
                urlInput = ""
            },
            focusRequester = urlButtonFocusRequester,
            onDpadDown = {
                fileButtonFocusRequester.requestFocus()
                true
            },
            onDpadUp = {
                settingsButtonFocusRequester.requestFocus()
                true
            }
        )

        // ----------------------------------------------------------------------------------
        // Open File Button
        // ----------------------------------------------------------------------------------
        TvButton(
            text = "Open File",
            onClick = {
                if (readPermissionState.status.isGranted) {
                    filePickerLauncher.launch("*/*")
                } else {
                    scope.launch { readPermissionState.launchPermissionRequest() }
                }
            },
            focusRequester = fileButtonFocusRequester,
            onDpadDown = {
                settingsButtonFocusRequester.requestFocus()
                true
            },
            onDpadUp = {
                urlButtonFocusRequester.requestFocus()
                true
            }
        )

        // ----------------------------------------------------------------------------------
        // Settings Button
        // ----------------------------------------------------------------------------------
        TvButton(
            text = "Settings",
            onClick = onSettings,
            focusRequester = settingsButtonFocusRequester,
            onDpadDown = {
                urlButtonFocusRequester.requestFocus()
                true
            },
            onDpadUp = {
                fileButtonFocusRequester.requestFocus()
                true
            }
        )
    }


    // --------------------------------------------------------------------------------------
    // URL INPUT DIALOG
    // --------------------------------------------------------------------------------------
    if (showUrlDialog) {
        UrlInputDialog(
            currentUrl = urlInput,
            onUrlChange = { urlInput = it },
            onConfirm = {
                if (urlInput.isNotBlank()) onOpenUrl(urlInput)
                showUrlDialog = false
            },
            onDismiss = { showUrlDialog = false }
        )
    }
}



// *****************************************************************************************
//  URL INPUT DIALOG
// *****************************************************************************************

@Composable
fun UrlInputDialog(
    currentUrl: String,
    onUrlChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val urlFocusRequester = remember { FocusRequester() }
    val okFocusRequester = remember { FocusRequester() }
    val cancelFocusRequester = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {

                Text(
                    text = "Enter Video URL",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                OutlinedTextField(
                    value = currentUrl,
                    onValueChange = onUrlChange,
                    label = { Text("Video URL") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(urlFocusRequester),
                    singleLine = true,
                    placeholder = { Text("https://example.com/video.mp4") }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(cancelFocusRequester),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                    ) { Text("Cancel") }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.focusRequester(okFocusRequester),
                        enabled = currentUrl.isNotBlank()
                    ) { Text("Open") }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        urlFocusRequester.requestFocus()
    }
}

