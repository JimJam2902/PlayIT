package com.example.playit


import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared dialog components used by both Audio and Subtitle full-screen dialogs.
 * Put this single source-of-truth file in your project and delete other duplicates.
 */

/**
 * Row used for each selectable item in a full-screen dialog list.
 *
 * @param text label shown
 * @param selected whether the entry is currently selected
 * @param onClick callback when row is clicked/activated
 * @param onKeyCode callback receiving Android key codes; should return true if consumed
 */
@Composable
fun FullScreenDialogRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    onKeyCode: (Int) -> Boolean = { false }
) {
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    focused -> Color.White.copy(alpha = 0.12f)
                    selected -> Color.White.copy(alpha = 0.06f)
                    else -> Color.Transparent
                }
            )
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                // Call the callback but don't necessarily consume here; callback returns whether it handled it.
                val consumed = onKeyCode(ev.nativeKeyEvent.keyCode)
                consumed
            }
            .focusable()
            .clickable { onClick() }
            .padding(vertical = 16.dp, horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = text, color = Color.White, modifier = Modifier.weight(1f), fontSize = 18.sp)
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = "selected", tint = Color(0xFFD32F2F))
        }
    }
}

/**
 * Simple labeled switch row used in dialogs (e.g., Audio Boost, Subtitles On/Off).
 */
@Composable
fun FullScreenDialogSwitch(
    text: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = Color.White)
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

/**
 * Close button used in top-right of dialogs.
 */
@Composable
fun FullScreenCloseButton(
    text: String,
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .focusRequester(focusRequester)
            .padding(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A6ED8))
    ) {
        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
        Spacer(Modifier.width(8.dp))
        Text(text = text, color = Color.White)
    }
}
