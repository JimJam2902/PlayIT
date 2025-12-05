package com.example.playit

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import android.util.Log
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    subtitleRepository: SubtitleRepository,
    onBack: () -> Unit
) {
    var username by remember { mutableStateOf(settingsRepository.getOpenSubtitlesUsername() ?: "") }
    var password by remember { mutableStateOf(settingsRepository.getOpenSubtitlesPassword() ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }
    var savedMessage by remember { mutableStateOf("") }
    val loginStatus by subtitleRepository.loginStatus.collectAsState()

    // Focus handling
    val usernameFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { usernameFocusRequester.requestFocus() }

    Scaffold(
        topBar = {
            // ...existing code...
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text("Back") }
                Spacer(modifier = Modifier.weight(1f))
                Text("Settings", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.weight(1f))
                // Empty spacer for alignment
                Box(modifier = Modifier.size(48.dp))
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(24.dp).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("OpenSubtitles Credentials", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(usernameFocusRequester)
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password"
                        )
                    }
                }
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = {
                    Log.d("SettingsScreen", "Clearing credentials")
                    settingsRepository.clearOpenSubtitlesCredentials()
                    username = ""
                    password = ""
                    savedMessage = "Cleared"
                }) { Text("Clear") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    if (username.isEmpty() || password.isEmpty()) {
                        savedMessage = "Please enter username and password"
                        Log.w("SettingsScreen", "Save button clicked but fields empty")
                        return@Button
                    }
                    Log.d("SettingsScreen", "Saving credentials for user: $username")
                    settingsRepository.saveOpenSubtitlesCredentials(username, password)
                    savedMessage = "Saved ✓"
                }) { Text("Save") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (savedMessage.isNotEmpty()) {
                Text(
                    savedMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (savedMessage.contains("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("Login Status: $loginStatus", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            val coroutineScope = rememberCoroutineScope()
            Button(onClick = {
                // Validate before testing
                if (username.isEmpty() || password.isEmpty()) {
                    savedMessage = "Please enter and Save credentials first"
                    Log.w("SettingsScreen", "Test login clicked but credentials empty")
                    return@Button
                }

                // Check if credentials are saved
                val savedUsername = settingsRepository.getOpenSubtitlesUsername()
                val savedPassword = settingsRepository.getOpenSubtitlesPassword()
                if (savedUsername != username || savedPassword != password) {
                    savedMessage = "Please Save credentials first before testing"
                    Log.w("SettingsScreen", "Test login clicked but credentials not saved")
                    return@Button
                }

                // Run test login in coroutine and show result
                savedMessage = "Testing login..."
                Log.d("SettingsScreen", "Starting login test for user: $username")
                coroutineScope.launch {
                    val ok = subtitleRepository.testLogin()
                    savedMessage = if (ok) "✓ Login successful" else "✗ Login failed - check credentials"
                    Log.d("SettingsScreen", "Login test completed: success=$ok")
                }
            }) {
                Text("Test Login")
            }
        }
    }
}
