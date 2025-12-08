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
import androidx.core.net.toUri
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
    private var mediaUrl: String? = null

    // Playback completion tracking
    private var hasCompletionHandled: Boolean = false
    private var completionListener: androidx.media3.common.Player.Listener? = null
    private var resultIntentAlreadySet: Boolean = false  // Track if we've explicitly set result for next episode

    // TMDB fallback for next episode lookup
    private val tmdbClient = TMDBClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dataUri = intent?.data
        mediaUrl = dataUri?.toString()

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

        // If season/episode still not found, try to extract from filename
        // Supports patterns like "S03E02", "3x02", "Season 3 Episode 2"
        if (season == null || episode == null) {
            val filename = mediaUrl?.substringAfterLast('/')?.substringBefore('?') ?: ""
            val seEpisode = parseSeasonEpisodeFromFilename(filename)
            if (seEpisode != null) {
                season = seEpisode.first
                episode = seEpisode.second
                android.util.Log.d("PlayerActivityMinimal", "✓ Extracted from filename: S${season}E${episode}")
            }
        }

        // Determine if this is a TV show (has season and episode info)
        isTvShow = season != null && episode != null

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

        // Set up callback for ViewModel to trigger completion (for error scenarios like subtitle EOF)
        playbackViewModel.onPlaybackCompletionCallback = {
            if (!hasCompletionHandled) {
                hasCompletionHandled = true
                android.util.Log.d("PlayerActivityMinimal", "✓ Completion triggered from error handler (subtitle EOF)")
                handlePlaybackCompletion()
            }
        }
    }

    override fun finish() {
        try { reporterJob?.cancel() } catch (_: Exception) {}

        // Clean up playback completion listener
        try {
            completionListener?.let { listener ->
                playbackViewModel.player?.removeListener(listener)
            }
        } catch (_: Exception) {}

        val posMs = playbackViewModel.player?.currentPosition ?: 0L
        val durMs = playbackViewModel.player?.duration ?: 0L

        // If Stremio asked for result via return_result flag, return it via setResult
        // BUT: Don't overwrite if we've already explicitly set result for next episode
        if (shouldReturnResult && !resultIntentAlreadySet) {
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
        } else if (resultIntentAlreadySet) {
            android.util.Log.d("PlayerActivityMinimal", "Result already explicitly set for next episode, not overwriting")
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
     * Uses Media3 Player.Listener to detect actual playback completion (STATE_ENDED)
     */
    private fun startPlaybackCompletionMonitor() {
        val player = playbackViewModel.player ?: return

        // Create the listener object
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // Only handle STATE_ENDED, which is the actual playback completion event
                if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    if (hasCompletionHandled) {
                        android.util.Log.d("PlayerActivityMinimal", "⚠ STATE_ENDED already handled, ignoring duplicate")
                        return // Already handled completion
                    }

                    val posMs = player.currentPosition
                    val durMs = player.duration
                    android.util.Log.d("PlayerActivityMinimal", "✓ Playback STATE_ENDED detected at $posMs / $durMs ms")

                    // Safeguard: only treat as true completion if position is within 1 second of duration
                    // This prevents false STATE_ENDED from streaming/buffering issues
                    val isReallyAtEnd = durMs > 0 && (durMs - posMs) <= 1000L
                    if (!isReallyAtEnd) {
                        android.util.Log.d("PlayerActivityMinimal", "⚠ STATE_ENDED detected but position not truly at end ($posMs/$durMs), ignoring")
                        return
                    }

                    hasCompletionHandled = true
                    handlePlaybackCompletion()
                }
            }
        }

        // Store listener reference for cleanup
        completionListener = listener

        // Add the listener to the player
        player.addListener(listener)
    }

    /**
     * Handle playback completion - can be called from STATE_ENDED or error handlers
     */
    private fun handlePlaybackCompletion() {
        if (isTvShow && season != null && episode != null) {
            android.util.Log.d("PlayerActivityMinimal", "→ TV Show detected: imdbId=$imdbId, S${season}E${episode}")
            android.util.Log.d("PlayerActivityMinimal", "→ Callback URL available: ${!stremioCallbackUrl.isNullOrEmpty()}")
            attemptPlayNextEpisode()
        } else {
            android.util.Log.d("PlayerActivityMinimal", "→ Movie detected (isTvShow=$isTvShow, season=$season, episode=$episode)")
            android.util.Log.d("PlayerActivityMinimal", "→ Auto-exiting after movie playback...")
            lifecycleScope.launch {
                delay(1000) // Brief delay before exit
                finish()
            }
        }
    }

    /**
     * Attempt to play the next episode (for TV shows)
     * 1. First tries: Stremio callback URL (if available)
     * 2. Fallback: TMDB-based next episode lookup + auto-play
     * 3. Last resort: Return to Stremio for manual selection
     */
    private fun attemptPlayNextEpisode() {
        lifecycleScope.launch {
            try {
                val currentSeason = season ?: return@launch
                val currentEpisode = episode ?: return@launch

                android.util.Log.d("PlayerActivityMinimal", "Episode ended: S${currentSeason}E${currentEpisode}, attempting to load next episode")

                // If callback URL exists, notify Stremio that episode has finished
                if (!stremioCallbackUrl.isNullOrEmpty()) {
                    android.util.Log.d("PlayerActivityMinimal", "✓ Callback URL available, sending notification for next episode")
                    notifyStremioViaCallback(currentSeason, currentEpisode)
                    delay(1000)
                    finish()
                } else {
                    // No callback URL - try TMDB fallback for independent next episode lookup
                    android.util.Log.d("PlayerActivityMinimal", "✗ No Stremio callback URL, attempting TMDB fallback auto-play")
                    attemptTMDBAutoNextEpisode(currentSeason, currentEpisode)
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivityMinimal", "attemptPlayNextEpisode error: ${e.message}")
                delay(500)
                finish()
            }
        }
    }

    /**
     * Attempt to auto-play next episode using TMDB lookup
     * This is a fallback when Stremio doesn't provide a callback URL
     */
    private suspend fun attemptTMDBAutoNextEpisode(currentSeason: Int, currentEpisode: Int) {
        try {
            android.util.Log.d("PlayerActivityMinimal", "→ TMDB Fallback: Looking up next episode S${currentSeason}E${currentEpisode + 1}")

            // Extract show name from URL/filename
            val showName: String? = if (mediaUrl != null && mediaUrl!!.isNotEmpty()) {
                tmdbClient.extractShowNameFromUrl(mediaUrl!!)
            } else null

            if (showName == null || showName.isEmpty()) {
                android.util.Log.w("PlayerActivityMinimal", "⚠ Could not extract show name from URL, falling back to standard return")
                returnToStremioWithCompletion(currentSeason, currentEpisode)
                return
            }

            android.util.Log.d("PlayerActivityMinimal", "→ Extracted show name: '$showName'")

            // Attempt to fetch next episode stream from TMDB
            val nextEpisodeStream: String? = withContext(Dispatchers.IO) {
                tmdbClient.fetchNextEpisodeStreamUrl(showName, currentSeason, currentEpisode + 1)
            }

            if (nextEpisodeStream != null && nextEpisodeStream.isNotEmpty()) {
                android.util.Log.d("PlayerActivityMinimal", "✓ TMDB: Found next episode stream, auto-playing S${currentSeason}E${currentEpisode + 1}")
                playNextEpisodeDirectly(nextEpisodeStream, currentSeason, currentEpisode + 1)
            } else {
                android.util.Log.w("PlayerActivityMinimal", "⚠ TMDB: Could not find next episode stream, returning to Stremio")
                returnToStremioWithCompletion(currentSeason, currentEpisode)
            }
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivityMinimal", "TMDB auto-play error: ${e.message}, falling back to standard return")
            returnToStremioWithCompletion(currentSeason, currentEpisode)
        }
    }

    /**
     * Play next episode directly without returning to Stremio
     * Simulates Stremio's auto-play behavior
     */
    private suspend fun playNextEpisodeDirectly(
        nextEpisodeUrl: String,
        nextSeason: Int,
        nextEpisode: Int
    ) {
        try {
            android.util.Log.d("PlayerActivityMinimal", "🚀 Auto-playing next episode: S${nextSeason}E${nextEpisode}")
            android.util.Log.d("PlayerActivityMinimal", "   URL: $nextEpisodeUrl")

            // Create an intent to play the next episode
            val nextIntent = Intent(Intent.ACTION_VIEW).apply {
                data = nextEpisodeUrl.toUri()
                putExtra("season", nextSeason)
                putExtra("episode", nextEpisode)
                putExtra("return_result", shouldReturnResult)
                // Start from beginning of next episode
                putExtra("startfrom", 0)
            }

            // Return this intent as result to launch next episode
            setResult(RESULT_OK, nextIntent)
            resultIntentAlreadySet = true

            delay(500)
            finish()
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivityMinimal", "Error playing next episode directly: ${e.message}")
            returnToStremioWithCompletion(nextSeason - 1, nextEpisode - 1)
        }
    }

    /**
     * Return to Stremio with completion signal
     * Lets Stremio handle next episode selection/playback
     */
    private suspend fun returnToStremioWithCompletion(lastSeason: Int, lastEpisode: Int) {
        try {
            android.util.Log.d("PlayerActivityMinimal", "↩ Returning to Stremio with completion signal for S${lastSeason}E${lastEpisode}")

            if (shouldReturnResult) {
                val durMs = playbackViewModel.player?.duration ?: 0L

                val resultIntent = Intent().apply {
                    data = intent?.data
                    putExtra("position", durMs.toInt())  // Signal completion
                    putExtra("duration", durMs.toInt())
                    putExtra("startfrom", intent?.getIntExtra("startfrom", 0) ?: 0)
                    putExtra("return_result", true)
                }

                android.util.Log.d("PlayerActivityMinimal", "Returning result with completion: position=$durMs (=duration)")
                setResult(RESULT_OK, resultIntent)
                resultIntentAlreadySet = true
            }

            delay(500)
            finish()
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivityMinimal", "Error returning to Stremio: ${e.message}")
            delay(500)
            finish()
        }
    }

    /**
     * Notify Stremio via callback URL that episode has ended
     */
    private suspend fun notifyStremioViaCallback(season: Int, episode: Int) {
        try {
            withContext(Dispatchers.IO) {
                val payload = String.format(
                    Locale.US,
                    """{"jsonrpc":"2.0","method":"episodeEnded","params":{"season":%d,"episode":%d}}""",
                    season, episode
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
                android.util.Log.d("PlayerActivityMinimal", "Callback notification response: $responseCode")
                conn.disconnect()
            }
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivityMinimal", "Error notifying callback: ${e.message}")
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

    /**
     * Parse season and episode from filename patterns like "S03E02" or "3x02"
     * Returns Pair<season, episode> or null if not found
     */
    private fun parseSeasonEpisodeFromFilename(filename: String): Pair<Int, Int>? {
        // Try pattern: S03E02 or s03e02
        val sERegex = Regex("(?i)[Ss](\\d{1,2})[Ee](\\d{1,2})")
        sERegex.find(filename)?.let { match ->
            val (s, e) = match.destructured
            val season = s.toIntOrNull()
            val episode = e.toIntOrNull()
            if (season != null && episode != null) {
                return Pair(season, episode)
            }
        }

        // Try pattern: 3x02 or 03x02
        val xRegex = Regex("(\\d{1,2})[Xx](\\d{1,2})")
        xRegex.find(filename)?.let { match ->
            val (s, e) = match.destructured
            val season = s.toIntOrNull()
            val episode = e.toIntOrNull()
            if (season != null && episode != null) {
                return Pair(season, episode)
            }
        }

        return null
    }
}
