package com.example.playit

import android.os.Bundle
import android.content.Intent
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
    private var resumePositionMs: Long = 0L
    private var resumeDurationMs: Long = 0L
    private var shouldReturnResult: Boolean = false

    // TV Show metadata
    private var imdbId: String? = null
    private var season: Int? = null
    private var episode: Int? = null
    private var isTvShow: Boolean = false

    // Playback completion tracking
    private var playbackCompletionJob: Job? = null
    private var hasCompletionHandled: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dataUri = intent?.data
        val mediaUrl = dataUri?.toString()

        android.util.Log.d("PlayerActivityMinimal", "=== INTENT DATA ===")
        android.util.Log.d("PlayerActivityMinimal", "Intent URI: $dataUri")
        android.util.Log.d("PlayerActivityMinimal", "Intent Action: ${intent?.action}")

        stremioCallbackUrl = try {
            dataUri?.getQueryParameter("callback")
        } catch (_: Exception) { null }

        // Check for return_result flag - Stremio uses this instead of callback URL
        shouldReturnResult = intent?.getBooleanExtra("return_result", false) ?: false
        android.util.Log.d("PlayerActivityMinimal", "return_result flag: $shouldReturnResult")

        // Extract TV show metadata from intent extras/uri
        imdbId = intent?.getStringExtra("imdbId") ?: try {
            dataUri?.getQueryParameter("imdbId")
        } catch (_: Exception) { null }

        season = intent?.getIntExtra("season", -1).let { if (it == -1) null else it }
            ?: try {
                dataUri?.getQueryParameter("season")?.toIntOrNull()
            } catch (_: Exception) { null }

        episode = intent?.getIntExtra("episode", -1).let { if (it == -1) null else it }
            ?: try {
                dataUri?.getQueryParameter("episode")?.toIntOrNull()
            } catch (_: Exception) { null }

        // Determine if this is a TV show (has season and episode info)
        isTvShow = (season != null && episode != null) ||
                   (!imdbId.isNullOrEmpty() && season != null && episode != null)

        android.util.Log.d("PlayerActivityMinimal", "TV Show metadata: imdbId=$imdbId, season=$season, episode=$episode, isTvShow=$isTvShow")

        // Extract resume position and duration from intent extras (Stremio integration)
        // Try multiple formats: Long, Int, String
        val positionLong = intent?.getLongExtra("position", 0L) ?: 0L
        val positionInt = intent?.getIntExtra("position", 0)
        val positionStr = intent?.getStringExtra("position")
        resumePositionMs = when {
            positionInt != null && positionInt > 0 -> positionInt.toLong()
            positionLong > 0L -> positionLong
            !positionStr.isNullOrEmpty() && (positionStr.toLongOrNull() ?: 0L) > 0L -> positionStr.toLong()
            else -> 0L
        }

        resumeDurationMs = intent?.getLongExtra("duration", 0L) ?: 0L

        // Log all extras for debugging
        @Suppress("DEPRECATION")
        intent?.extras?.let { extras ->
            android.util.Log.d("PlayerActivityMinimal", "=== ALL EXTRAS ===")
            for (key in extras.keySet()) {
                val value = extras.get(key)
                android.util.Log.d("PlayerActivityMinimal", "  $key: $value (${value?.javaClass?.simpleName})")
            }
        } ?: run {
            android.util.Log.d("PlayerActivityMinimal", "NO EXTRAS in intent")
        }

        if (resumePositionMs > 0L) {
            android.util.Log.d("PlayerActivityMinimal", "✓ Resume position: ${resumePositionMs}ms (duration: ${resumeDurationMs}ms)")
        } else {
            android.util.Log.d("PlayerActivityMinimal", "ℹ No resume position (first playthrough)")
        }

        if (!stremioCallbackUrl.isNullOrEmpty()) {
            android.util.Log.d("PlayerActivityMinimal", "✓ Callback URL: $stremioCallbackUrl")
            startReporter()
        } else if (shouldReturnResult) {
            android.util.Log.d("PlayerActivityMinimal", "✓ Will return result via setResult() on exit")
        } else {
            android.util.Log.d("PlayerActivityMinimal", "✗ Neither callback URL nor return_result found")
        }

        setContent {
            PlayITTheme {
                Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
                    PlayerScreen(
                        viewModel = playbackViewModel,
                        mediaUrl = mediaUrl,
                        mediaUri = null,
                        resumePositionMs = resumePositionMs,
                        onExit = { finish() }
                    )
                }
            }
        }

        // Start monitoring for playback completion
        startPlaybackCompletionMonitor()
    }

    override fun finish() {
        try { reporterJob?.cancel() } catch (_: Exception) {}
        try { playbackCompletionJob?.cancel() } catch (_: Exception) {}

        val posMs = playbackViewModel.player?.currentPosition ?: 0L
        val durMs = playbackViewModel.player?.duration ?: 0L

        // If Stremio asked for result via return_result flag, return it via setResult
        if (shouldReturnResult) {
            android.util.Log.d("PlayerActivityMinimal", "Returning result: position=${posMs}ms, duration=${durMs}ms")

            // Follow MPV-Android pattern: create result intent with original data preserved
            val resultIntent = Intent().apply {
                // Preserve the original intent's data (the video URL)
                data = intent?.data
                // Add position and duration as INTEGERS (in milliseconds), matching MPV-Android
                putExtra("position", posMs.toInt())
                putExtra("duration", durMs.toInt())
                // Also preserve other extras Stremio might expect
                putExtra("startfrom", intent?.getIntExtra("startfrom", 0) ?: 0)
                putExtra("return_result", true)
            }

            android.util.Log.d("PlayerActivityMinimal", "Result intent data: ${resultIntent.data}")
            android.util.Log.d("PlayerActivityMinimal", "Result extras: position=${posMs.toInt()}, duration=${durMs.toInt()}, startfrom=${intent?.getIntExtra("startfrom", 0)}")

            setResult(RESULT_OK, resultIntent)

            // Give Stremio a moment to receive and process the result
            try {
                Thread.sleep(100)
            } catch (_: Exception) {}
        }

        // If callback URL exists, also send final "stopped" event via JSON-RPC
        val url = stremioCallbackUrl
        if (!url.isNullOrEmpty()) {
            android.util.Log.d("PlayerActivityMinimal", "Sending stopped event via callback")
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
     * Monitor playback state and handle auto-exit (movies) or auto-play next episode (TV shows)
     */
    private fun startPlaybackCompletionMonitor() {
        playbackCompletionJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    val p = playbackViewModel.player
                    if (p != null) {
                        val currentPos = p.currentPosition
                        val duration = p.duration
                        val isPlaying = p.isPlaying

                        // Check if playback has ended (current position near end of duration)
                        if (duration > 0 && currentPos > 0 && isPlaying) {
                            val percentWatched = (currentPos.toDouble() / duration.toDouble()) * 100

                            // If 95% or more of the video is watched, consider it completed
                            if (percentWatched >= 95 && !hasCompletionHandled) {
                                hasCompletionHandled = true
                                android.util.Log.d("PlayerActivityMinimal", "✓ Playback completion detected ($percentWatched% watched)")

                                if (isTvShow && season != null && episode != null) {
                                    android.util.Log.d("PlayerActivityMinimal", "→ TV Show detected: S${season}E${episode}, attempting next episode...")
                                    attemptPlayNextEpisode()
                                } else {
                                    android.util.Log.d("PlayerActivityMinimal", "→ Movie completed, auto-exiting in 2 seconds...")
                                    delay(2000)
                                    finish()
                                }
                                return@launch
                            }

                            // Reset completion flag if user seeks back (in case they want to continue watching)
                            if (percentWatched < 85) {
                                hasCompletionHandled = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.d("PlayerActivityMinimal", "Completion monitor error: ${e.message}")
                }

                delay(500) // Check every 500ms
            }
        }
    }

    /**
     * Attempt to play the next episode (for TV shows)
     * Sends a request back to Stremio to load the next episode
     */
    private fun attemptPlayNextEpisode() {
        lifecycleScope.launch {
            try {
                val currentSeason = season ?: return@launch
                val currentEpisode = episode ?: return@launch
                val showImdbId = imdbId ?: return@launch

                android.util.Log.d("PlayerActivityMinimal", "Requesting next episode: S${currentSeason}E${currentEpisode + 1}")

                // Send JSON-RPC call to Stremio to load next episode
                if (!stremioCallbackUrl.isNullOrEmpty()) {
                    withContext(Dispatchers.IO) {
                        try {
                            val payload = String.format(
                                Locale.US,
                                """{"jsonrpc":"2.0","method":"nextEpisode","params":{"imdbId":"%s","season":%d,"episode":%d}}""",
                                showImdbId, currentSeason, currentEpisode + 1
                            )

                            val connUrl = URL(stremioCallbackUrl)
                            val conn = connUrl.openConnection() as HttpURLConnection
                            conn.connectTimeout = 5000
                            conn.readTimeout = 5000
                            conn.requestMethod = "POST"
                            conn.doOutput = true
                            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")

                            val out = BufferedOutputStream(conn.outputStream)
                            out.write(payload.toByteArray(StandardCharsets.UTF_8))
                            out.flush()
                            out.close()

                            val responseCode = conn.responseCode
                            android.util.Log.d("PlayerActivityMinimal", "Next episode request response: $responseCode")
                            conn.disconnect()

                            // Give Stremio time to process and load the next episode
                            delay(2000)
                            finish()
                        } catch (e: Exception) {
                            android.util.Log.e("PlayerActivityMinimal", "Error requesting next episode: ${e.message}")
                            // Fallback: just finish the activity and let user manually select next episode
                            delay(1000)
                            finish()
                        }
                    }
                } else {
                    // No callback URL, just exit and let user handle next episode manually
                    android.util.Log.d("PlayerActivityMinimal", "No callback URL available, exiting player")
                    delay(1000)
                    finish()
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivityMinimal", "attemptPlayNextEpisode error: ${e.message}")
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
            android.util.Log.d("PlayerActivityMinimal", "sendJsonRpc: sending $event to $urlString")

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

            android.util.Log.d("PlayerActivityMinimal", "JSON-RPC payload: $payload")

            val out = BufferedOutputStream(conn.outputStream)
            val bytes = payload.toByteArray(StandardCharsets.UTF_8)
            out.write(bytes)
            out.flush()
            out.close()

            val code = conn.responseCode
            android.util.Log.d("PlayerActivityMinimal", "POST response code: $code")

            if (code == 200 || code == 201) {
                android.util.Log.d("PlayerActivityMinimal", "✓ JSON-RPC $event sent successfully")
            } else {
                android.util.Log.w("PlayerActivityMinimal", "⚠ JSON-RPC returned code $code")
            }

            try {
                val br = BufferedReader(InputStreamReader(conn.inputStream))
                br.forEachLine { line ->
                    android.util.Log.d("PlayerActivityMinimal", "Response: $line")
                }
                br.close()
            } catch (_: Exception) {}

        } catch (e: Exception) {
            android.util.Log.e("PlayerActivityMinimal", "✗ sendJsonRpc error: ${e.message}", e)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
