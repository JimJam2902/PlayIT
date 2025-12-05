package com.example.playit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.media3.common.util.UnstableApi
import com.example.playit.ui.theme.PlayITTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Minimal PlayerActivity:
 * Correct MPV-style JSON-RPC Stremio reporting.
 * Now waits for valid duration > 0 before sending "time" events.
 */
@UnstableApi
class PlayerActivityMinimal : ComponentActivity() {

    private val playbackViewModel: PlaybackViewModel by viewModels()

    private var stremioCallbackUrl: String? = null
    private var reporterJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dataUri = intent?.data
        val mediaUrl = dataUri?.toString()

        stremioCallbackUrl = try {
            dataUri?.getQueryParameter("callback")
        } catch (_: Exception) { null }

        if (!stremioCallbackUrl.isNullOrEmpty()) {
            android.util.Log.d("PlayerActivityMinimal", "Callback = $stremioCallbackUrl")
            startReporter()
        }

        setContent {
            PlayITTheme {
                Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
                    PlayerScreen(
                        viewModel = playbackViewModel,
                        mediaUrl = mediaUrl,
                        mediaUri = null,
                        onExit = { finish() }
                    )
                }
            }
        }
    }

    override fun finish() {
        try { reporterJob?.cancel() } catch (_: Exception) {}

        val url = stremioCallbackUrl
        if (!url.isNullOrEmpty()) {
            val p = playbackViewModel.player
            val posMs = p?.currentPosition ?: 0L
            val durMs = p?.duration ?: 0L

            // final event in background thread
            Thread {
                try {
                    sendJsonRpc(url, "stopped", posMs, durMs, paused = false)
                } catch (_: Exception) {}
            }.start()
        }

        super.finish()
    }

    private fun startReporter() {
        val url = stremioCallbackUrl ?: return

        reporterJob = lifecycleScope.launch {
            while (isActive) {

                try {
                    val p = playbackViewModel.player

                    val posMs = p?.currentPosition ?: 0L
                    val durMs = p?.duration ?: 0L

                    // ❗ Critical fix: Wait for valid duration
                    if (durMs <= 0L) {
                        delay(500)
                        continue
                    }

                    val paused = !(p?.isPlaying ?: false)

                    withContext(Dispatchers.IO) {
                        sendJsonRpc(url, "time", posMs, durMs, paused)
                    }

                } catch (e: Exception) {
                    android.util.Log.d("PlayerActivityMinimal", "Reporter error: ${e.message}")
                }

                delay(1000)
            }
        }
    }

    /**
     * MPV-style JSON-RPC POST
     */
    private fun sendJsonRpc(
        urlString: String,
        event: String,
        posMs: Long,
        durMs: Long,
        paused: Boolean
    ) {
        var conn: HttpURLConnection? = null

        try {
            val connUrl = URL(urlString)
            conn = connUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")

            val posSec = posMs.toDouble() / 1000.0
            val durSec = durMs.toDouble() / 1000.0

            val payload = String.format(
                Locale.US,
                """{"jsonrpc":"2.0","method":"playerEvent","params":{"event":"%s","position":%.3f,"duration":%.3f,"paused":%b}}""",
                event, posSec, durSec, paused
            )

            val out = BufferedOutputStream(conn.outputStream)
            val bytes = payload.toByteArray(StandardCharsets.UTF_8)
            out.write(bytes)
            out.flush()
            out.close()

            val code = conn.responseCode
            android.util.Log.d("PlayerActivityMinimal", "POST → $urlString  code=$code")

            try {
                val br = BufferedReader(InputStreamReader(conn.inputStream))
                br.forEachLine { }  // Consume all lines from response
                br.close()
            } catch (_: Exception) {}

        } catch (e: Exception) {
            android.util.Log.d("PlayerActivityMinimal", "sendJsonRpc error: ${e.message}")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
