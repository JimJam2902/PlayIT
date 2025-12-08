package com.example.playit

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class AudioTrackInfo(
    val id: String, // Unique identifier for the track
    val label: String, // User-friendly name like "English (DTS 5.1)"
    val language: String?
)

// Represents an available subtitle candidate (either discovered remotely or downloaded locally)
data class SubtitleEntry(
    val fileId: Long?,
    val language: String?,
    val downloadCount: Int,
    val source: String, // e.g., "opensubtitles" or "embedded"
    var localFilePath: String?, // file path when downloaded to cache
    val displayLabel: String,
    val groupIndex: Int? = null,
    val trackIndex: Int? = null,
    val isEmbedded: Boolean = false
)

@UnstableApi
class PlaybackViewModel(application: Application) : AndroidViewModel(application) {

    private val resumeRepository = ResumeRepository(application)
    private val playbackRepository = PlaybackRepository(application)
    private val subtitleRepository = SubtitleRepository(application)

    // Player State
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionFraction = MutableStateFlow(0f)
    val positionFraction: StateFlow<Float> = _positionFraction.asStateFlow()

    private val _bufferedFraction = MutableStateFlow(0f)
    val bufferedFraction: StateFlow<Float> = _bufferedFraction.asStateFlow()
    // ADD THIS: Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _title = MutableStateFlow("Video Title")
    val title: StateFlow<String> = _title.asStateFlow()

    // Diagnostics state exposed to UI
    private val _diagnosticsText = MutableStateFlow("No diagnostics yet")
    val diagnosticsText: StateFlow<String> = _diagnosticsText.asStateFlow()

    // Audio Boost State
    private val _isAudioBoostEnabled = MutableStateFlow(false)
    val isAudioBoostEnabled: StateFlow<Boolean> = _isAudioBoostEnabled.asStateFlow()

    // Audio Track State
    private val _availableAudioTracks = MutableStateFlow<List<AudioTrackInfo>>(emptyList())
    val availableAudioTracks: StateFlow<List<AudioTrackInfo>> = _availableAudioTracks.asStateFlow()

    private val _selectedAudioTrackIndex = MutableStateFlow(0)
    val selectedAudioTrackIndex: StateFlow<Int> = _selectedAudioTrackIndex.asStateFlow()

    // Subtitles state exposed to UI
    private val _availableSubtitles = MutableStateFlow<List<SubtitleEntry>>(emptyList())
    val availableSubtitles: StateFlow<List<SubtitleEntry>> = _availableSubtitles.asStateFlow()

    var totalSeconds: Int = 0
        private set

    var player: ExoPlayer? = null
        private set
    private val trackSelector: DefaultTrackSelector = DefaultTrackSelector(application)

    // Network error retry properties
    private var currentMediaUrl: String? = null
    private var currentResumePosition: Long = 0L
    private var retryCount: Int = 0
    private var retryJob: Job? = null

    // Track last known good duration for completion detection on activity stop
    private var lastKnownDurationMs: Long = 0L

    private val scope = CoroutineScope(Dispatchers.Main)
    private var playerStateJob: Job? = null
    private var resumeSaveJob: Job? = null  // Periodic save of resume position while playing
    // Track which mediaId we've already launched subtitle search for, avoid duplicate searches
    private var subtitleSearchLaunchedForMediaId: String? = null

    // Callback for PlayerActivity to handle completion triggered by error scenarios
    var onPlaybackCompletionCallback: (() -> Unit)? = null

    // Track last Matroska EOF error position to detect if we're stuck in a retry loop
    private var lastMatroskaEOFPosition: Long = 0L
    private var matroskaEOFRetryCount: Int = 0

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
        private const val RESUME_SAVE_INTERVAL_MS = 5000L  // Save resume position every 5 seconds while playing
        private const val MATROSKA_RETRY_LOOP_THRESHOLD_MS = 10000L  // If 2nd EOF within 10 seconds of first, it's a corrupt stream
    }

    init {
        initializePlayer()
    }

    private fun initializePlayer() {
        if (player == null) {
            player = playbackRepository.initializePlayer(trackSelector, _isAudioBoostEnabled.value)
            attachPlayer(player!!)
            // Update diagnostics with a local snapshot (avoid depending on repository method resolution)
            _diagnosticsText.value = buildLocalDiagnosticsSnapshot()
        }
    }
    private fun attachPlayer(p: ExoPlayer) {
        player = p
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                _isPlaying.value = isPlayingNow
                if (isPlayingNow) {
                    startPlayerObserver()
                } else {
                    stopPlayerObserver()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Update loading state based on playback state
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        _isLoading.value = true
                        Log.d("PlaybackViewModel", "Player is buffering (loading)")
                    }
                    Player.STATE_READY -> {
                        _isLoading.value = false
                        totalSeconds = (p.duration / 1000).toInt().coerceAtLeast(0)
                        // Store the last known good duration for use in onActivityStop
                        if (p.duration > 0) {
                            lastKnownDurationMs = p.duration
                            Log.d("PlaybackViewModel", "Player is ready, duration=$lastKnownDurationMs ms")
                        }
                        // Trigger background subtitle search / auto-apply when ready
                        maybePerformSubtitleAutoSearch()
                    }
                    Player.STATE_IDLE -> {
                        _isLoading.value = false
                    }
                    Player.STATE_ENDED -> {
                        _isLoading.value = false
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e("PlaybackViewModel", "Player error occurred: ${error.message}", error)

                val p = player
                val currentPos = p?.currentPosition ?: 0L
                val duration = p?.duration ?: 0L

                // Get full error stack trace as string for analysis
                val errorStackTrace = Log.getStackTraceString(error)
                Log.d("PlaybackViewModel", "Error detection - checking: Matroska EOF first, then network errors")

                // FIRST PRIORITY: Check if this is a Matroska/Subtitle EOF error
                // These are common near end of file and should NOT be retried
                val isMatroskaEOF = errorStackTrace.contains("EOFException") &&
                    (errorStackTrace.contains("MatroskaExtractor") ||
                     errorStackTrace.contains("VarintReader") ||
                     errorStackTrace.contains("DefaultEbmlReader") ||
                     errorStackTrace.contains("Matroska"))

                if (isMatroskaEOF) {
                    Log.d("PlaybackViewModel", "✓ Detected Matroska/Subtitle EOF error (expected at end of file)")

                    if (duration > 0) {
                        val timeToEnd = duration - currentPos
                        val percentComplete = (currentPos.toFloat() / duration) * 100
                        Log.d("PlaybackViewModel", "→ Playback progress: $percentComplete% ($currentPos/$duration ms, ${timeToEnd}ms to end)")

                        // Check if we're in a retry loop (getting EOF errors in quick succession)
                        val isRetryLoop = if (lastMatroskaEOFPosition > 0L) {
                            val timeSinceLastEOF = currentPos - lastMatroskaEOFPosition
                            timeSinceLastEOF < MATROSKA_RETRY_LOOP_THRESHOLD_MS && timeSinceLastEOF >= 0L
                        } else {
                            false
                        }

                        if (isRetryLoop) {
                            matroskaEOFRetryCount++
                            Log.d("PlaybackViewModel", "⚠ Retry loop detected! EOF errors occurring in rapid succession (${matroskaEOFRetryCount}x)")
                            Log.d("PlaybackViewModel", "  Last EOF at: $lastMatroskaEOFPosition ms, Current EOF at: $currentPos ms, Delta: ${currentPos - lastMatroskaEOFPosition}ms")
                        } else {
                            matroskaEOFRetryCount = 0  // Reset counter if we've advanced significantly
                        }
                        lastMatroskaEOFPosition = currentPos

                        // Use TIME-BASED threshold, not percentage-based
                        // Only treat as completion if within last 5 seconds of video AND no retry loop
                        if (timeToEnd <= 5000L && !isRetryLoop) {
                            Log.d("PlaybackViewModel", "→ Matroska EOF with only ${timeToEnd}ms remaining (no retry loop) - treating as normal episode completion (NOT retrying)")
                            _isLoading.value = false
                            matroskaEOFRetryCount = 0  // Reset for next media
                            // Trigger completion callback for PlayerActivity
                            onPlaybackCompletionCallback?.invoke()
                            return
                        } else if (isRetryLoop && matroskaEOFRetryCount >= 2) {
                            // We've hit EOF twice in quick succession - the stream tail is corrupted
                            // Instead of retrying from the same position, just skip to the very end
                            // and let playback finish naturally
                            Log.d("PlaybackViewModel", "✗ Stream tail corrupted (${matroskaEOFRetryCount} rapid EOF errors), skipping to end to let episode finish")

                            if (retryCount < MAX_RETRIES) {
                                retryCount++
                                // Skip to near the very end (99.9%) to bypass the corrupted tail completely
                                val retryPosition = (duration * 0.999f).toLong().coerceAtMost(duration - 100L)
                                Log.d("PlaybackViewModel", "  - Skipping to near-end position: $retryPosition ms (remaining=${duration - retryPosition}ms)")

                                // Cancel any existing retry job
                                retryJob?.cancel()

                                // Schedule retry with delay
                                retryJob = scope.launch {
                                    delay(RETRY_DELAY_MS)
                                    if (isActive && currentMediaUrl != null) {
                                        Log.d("PlaybackViewModel", "→ Skipping corrupted tail, resuming at 99.9%: $currentMediaUrl at position $retryPosition ms")
                                        try {
                                            playbackRepository.prepareAndPlay(currentMediaUrl!!, retryPosition)
                                        } catch (e: Exception) {
                                            Log.e("PlaybackViewModel", "✗ Retry failed: ${e.message}")
                                        }
                                    }
                                }
                            } else {
                                Log.d("PlaybackViewModel", "→ Max retries reached, allowing playback to end gracefully")
                                _isLoading.value = false
                                // Don't trigger completion yet - let player reach STATE_ENDED naturally
                            }
                            return
                        } else {
                            // Still significant content remaining OR first EOF - skip ahead progressively
                            val skipMs = if (isRetryLoop) 15000L else 5000L  // Skip 15 seconds if in retry loop, else 5 seconds
                            Log.d("PlaybackViewModel", "→ Matroska EOF but ${timeToEnd}ms remaining - retrying to skip corrupted section (skip=${skipMs}ms, retryLoop=$isRetryLoop)")

                            if (retryCount < MAX_RETRIES) {
                                retryCount++
                                val retryPosition = (currentPos + skipMs).coerceAtMost(duration)
                                Log.d("PlaybackViewModel", "  - Retrying from position: $retryPosition ms (skip=${skipMs}ms, timeRemaining=${(duration - retryPosition)}ms after skip, retryCount=$retryCount/$MAX_RETRIES)")

                                // Cancel any existing retry job
                                retryJob?.cancel()

                                // Schedule retry with delay
                                retryJob = scope.launch {
                                    delay(RETRY_DELAY_MS)
                                    if (isActive && currentMediaUrl != null) {
                                        Log.d("PlaybackViewModel", "→ Retrying playback of: $currentMediaUrl at position $retryPosition ms")
                                        try {
                                            playbackRepository.prepareAndPlay(currentMediaUrl!!, retryPosition)
                                        } catch (e: Exception) {
                                            Log.e("PlaybackViewModel", "✗ Retry failed: ${e.message}")
                                        }
                                    }
                                }
                            } else {
                                Log.d("PlaybackViewModel", "→ Max retries ($MAX_RETRIES) reached for Matroska EOF, allowing playback to end naturally")
                                _isLoading.value = false
                                // Let player reach STATE_ENDED naturally rather than forcing completion
                            }
                            return
                        }

                    }
                }

                // SECOND PRIORITY: Check if error occurred near the end of the video
                val isNearEnd = duration > 0 && (duration - currentPos) <= 5000L // Within 5 seconds of end
                if (isNearEnd) {
                    Log.d("PlaybackViewModel", "✓ Error near end of video ($currentPos/$duration), not retrying to allow episode completion")
                    _isLoading.value = false
                    return
                }

                // THIRD PRIORITY: Check if this is a network error
                val isNetworkError = when {
                    error.cause is java.net.SocketException -> true
                    error.cause is java.net.SocketTimeoutException -> true
                    error.cause is java.io.IOException -> true
                    error.cause is java.net.ConnectException -> true
                    error.cause is java.net.UnknownHostException -> true
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> true
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> true
                    error.toString().lowercase().contains("network") -> true
                    error.toString().lowercase().contains("connection") -> true
                    error.toString().lowercase().contains("socket") -> true
                    error.toString().lowercase().contains("timeout") -> true
                    else -> false
                }

                if (isNetworkError && retryCount < MAX_RETRIES) {
                    // Automatically retry on network errors
                    retryCount++

                    // IMPORTANT: Use current playback position at time of error, not the initial resume position
                    // This ensures we resume from where the user was watching, not from the beginning
                    val retryPosition = currentPos  // Use position at error time, not initial resume position
                    Log.d("PlaybackViewModel", "✓ Network error detected at $currentPos ms, retrying ($retryCount/$MAX_RETRIES) after ${RETRY_DELAY_MS}ms")
                    Log.d("PlaybackViewModel", "  - Will resume from: $retryPosition ms (current position at error)")
                    Log.d("PlaybackViewModel", "  - NOT using initial resume position: $currentResumePosition ms")

                    // Cancel any existing retry job
                    retryJob?.cancel()

                    // Schedule retry with delay
                    retryJob = scope.launch {
                        delay(RETRY_DELAY_MS)
                        if (isActive && currentMediaUrl != null) {
                            Log.d("PlaybackViewModel", "→ Retrying playback of: $currentMediaUrl at position $retryPosition ms")
                            try {
                                playbackRepository.prepareAndPlay(currentMediaUrl!!, retryPosition)
                            } catch (e: Exception) {
                                Log.e("PlaybackViewModel", "✗ Retry failed: ${e.message}")
                            }
                        }
                    }
                } else {
                    // Too many retries or not a network error, don't retry
                    _isLoading.value = false
                    Log.e("PlaybackViewModel", "✗ Playback error not retryable (isNetwork=$isNetworkError, retryCount=$retryCount/$MAX_RETRIES)")
                }
            }

            // This is now correctly inside the Player.Listener
            override fun onTracksChanged(tracks: Tracks) {
                updateTrackLists(tracks)
                // Update diagnostics whenever tracks change
                try {
                    val sb = StringBuilder()
                    sb.append(buildLocalDiagnosticsSnapshot())
                    sb.append("onTracksChanged: groups=${tracks.groups.size}\n")
                    tracks.groups.forEachIndexed { groupIndex, group ->
                        sb.append("  Group[${groupIndex}]: type=${group.type} length=${group.length}\n")
                        for (i in 0 until group.length) {
                            val format = group.getTrackFormat(i)
                            sb.append("    Format[${i}]: containerMime=${format.containerMimeType} sampleMime=${format.sampleMimeType} codecs=${format.codecs} sampleRate=${format.sampleRate} channels=${format.channelCount} language=${format.language} label=${format.label}\n")
                        }
                    }

                    // Add renderer and mapping information (which renderer has which track groups)
                    try {
                        // Reflect player's renderers (if available)
                        val renderersInfo = StringBuilder()
                        try {
                            val renderersField = player?.javaClass?.getDeclaredField("renderers")
                            renderersField?.isAccessible = true
                            val renderersArr = renderersField?.get(player) as? Array<*>
                            if (renderersArr != null) {
                                renderersInfo.append("Registered renderers count=${renderersArr.size}\n")
                                renderersArr.forEachIndexed { idx, r ->
                                    renderersInfo.append("  Renderer[${idx}]: ${r?.javaClass?.name}\n")
                                }
                            }
                        } catch (_: Exception) {
                            // ignore reflection issues
                        }
                        sb.append(renderersInfo.toString())

                        // Use trackSelector's mapped info to show renderer->trackGroups mapping
                        val mappedInfo = trackSelector.currentMappedTrackInfo
                        if (mappedInfo != null) {
                            sb.append("MappedTrackInfo rendererCount=${mappedInfo.rendererCount}\n")
                            for (ri in 0 until mappedInfo.rendererCount) {
                                val tga = mappedInfo.getTrackGroups(ri)
                                sb.append("  MappedRenderer[${ri}]: groups=${tga.length}\n")
                                for (g in 0 until tga.length) {
                                    val tg = tga.get(g)
                                    for (fi in 0 until tg.length) {
                                        val f = tg.getFormat(fi)
                                        sb.append("    RG[${g}] Format[${fi}]: mime=${f.sampleMimeType} codecs=${f.codecs} channels=${f.channelCount} sampleRate=${f.sampleRate}\n")
                                    }
                                }
                            }
                        } else {
                            sb.append("MappedTrackInfo: null\n")
                        }
                    } catch (e: Exception) {
                        sb.append("Error collecting mapped renderer info: ${e.message}\n")
                    }

                    _diagnosticsText.value = sb.toString()
                    Log.d("PlaybackViewModel", "updated diagnostics after onTracksChanged")
                } catch (e: Exception) {
                    Log.d("PlaybackViewModel", "error generating diagnostics: ${e.message}")
                }

                // Publish embedded subtitle tracks immediately so the UI shows them even if no remote search ran.
                try {
                    val embeddedEntries = tracks.groups.flatMapIndexed { groupIndex, group ->
                        if (group.type != C.TRACK_TYPE_TEXT) emptyList<SubtitleEntry>()
                        else {
                            (0 until group.length).map { trackIndex ->
                                val format = group.getTrackFormat(trackIndex)
                                SubtitleEntry(
                                    fileId = null,
                                    language = format.language,
                                    downloadCount = 0,
                                    source = "embedded",
                                    localFilePath = null,
                                    displayLabel = format.label ?: format.language
                                    ?: "Embedded Subtitle",
                                    groupIndex = groupIndex,
                                    trackIndex = trackIndex,
                                    isEmbedded = true
                                )
                            }
                        }
                    }


                    // Merge with existing remote candidates (keep remote ones alongside embedded)
                    viewModelScope.launch(Dispatchers.Main) {
                        try {
                            val remote = _availableSubtitles.value.filter { !it.isEmbedded }
                            // Prefer embedded first, then remote, unique by displayLabel+source
                            val merged =
                                (embeddedEntries + remote).distinctBy { it.displayLabel + "|" + it.source }
                            _availableSubtitles.value = merged
                        } catch (e: Exception) {
                            Log.e(
                                "PlaybackViewModel",
                                "publishing embedded subtitles failed: ${e.message}"
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PlaybackViewModel", "error collecting embedded subtitles: ${e.message}")
                }
            }
        }) // <--- Listener closes HERE
    }



    private fun startPlayerObserver() {
        stopPlayerObserver()
        playerStateJob = scope.launch {
            while (isActive) {
                player?.let { p ->
                    val durationMs = p.duration.coerceAtLeast(0L)
                    if (durationMs > 0) {
                        _positionFraction.value = (p.currentPosition.toFloat() / durationMs).coerceIn(0f, 1f)
                        _bufferedFraction.value = (p.bufferedPosition.toFloat() / durationMs).coerceIn(0f, 1f)
                    }
                }
                delay(500) // Update every 500ms
            }
        }

        // Start periodic resume position saving while playing
        startResumeSaveJob()
    }

    private fun startResumeSaveJob() {
        resumeSaveJob?.cancel()
        resumeSaveJob = scope.launch {
            while (isActive) {
                delay(RESUME_SAVE_INTERVAL_MS)
                // Save resume position periodically while playing
                player?.let { p ->
                    val mediaId = p.currentMediaItem?.mediaId ?: ""
                    val currentPos = p.currentPosition
                    val duration = p.duration

                    // Only save if we have valid position and not near completion
                    if (mediaId.isNotEmpty() && currentPos > 0L && duration > 0) {
                        val percentComplete = (currentPos.toFloat() / duration) * 100
                        // Don't save if very close to end (let onActivityStop handle it)
                        if (percentComplete < 95f) {
                            try {
                                viewModelScope.launch(Dispatchers.IO) {
                                    resumeRepository.saveResumePosition(mediaId, currentPos)
                                    Log.d("PlaybackViewModel", "Periodic save: position=$currentPos for $mediaId")
                                }
                            } catch (e: Exception) {
                                Log.e("PlaybackViewModel", "Error saving resume position: ${e.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun stopPlayerObserver() {
        playerStateJob?.cancel()
        playerStateJob = null
        resumeSaveJob?.cancel()
        resumeSaveJob = null
    }

    fun playMedia(url: String?, externalResumePositionMs: Long = 0L) {
        if (url == null) return

        // Store current media info for potential retries
        currentMediaUrl = url
        _isAudioBoostEnabled.value = false // Reset boost on new media
        _isLoading.value = true // Start loading
        retryCount = 0 // Reset retry counter for new media
        matroskaEOFRetryCount = 0 // Reset Matroska EOF retry loop counter
        lastMatroskaEOFPosition = 0L // Reset last EOF position
        retryJob?.cancel() // Cancel any pending retries

        initializePlayer() // Re-initialize if released
        _title.value = url.substringAfterLast('/') // Simple title extraction
        scope.launch {
            // Prefer external resume position (from Stremio), fallback to local repository
            val resumePosition = if (externalResumePositionMs > 0L) {
                Log.d("PlaybackViewModel", "playMedia: using external resume position $externalResumePositionMs from Stremio")
                externalResumePositionMs
            } else {
                // Try best-match resume position (handles URL variants, filename-only keys, hashes)
                val localResume = try {
                    resumeRepository.getBestResumePosition(url)
                } catch (_: Exception) {
                    resumeRepository.getResumePosition(url)
                }
                // Only use local resume if it's non-zero (0 means it was cleared because episode completed)
                if (localResume > 0L) {
                    Log.d("PlaybackViewModel", "playMedia: url=$url using local resume position=$localResume ms")
                    localResume
                } else {
                    Log.d("PlaybackViewModel", "playMedia: url=$url resume position was 0 (cleared), starting from beginning")
                    0L
                }
            }

            // Store resume position for potential retries
            currentResumePosition = resumePosition

            Log.d("PlaybackViewModel", "playMedia: final resumePosition=$resumePosition ms, preparing playback")
            playbackRepository.prepareAndPlay(url, resumePosition)
            // Reset subtitles state for new media
            _availableSubtitles.value = emptyList()
            subtitleSearchLaunchedForMediaId = null
        }
    }
    // Parse season/episode if present in a string like "S01E02" or "1x02" etc.
    @Suppress("unused")
    private fun parseSeasonEpisode(title: String): Pair<Int, Int>? {
        val sERegex = Regex("(?i)S(\\d{1,2})E(\\d{1,2})")
        val m1 = sERegex.find(title)
        if (m1 != null) {
            val (s, e) = m1.destructured
            return Pair(s.toIntOrNull() ?: 0, e.toIntOrNull() ?: 0)
        }
        val xRegex = Regex("(?i)(\\d{1,2})x(\\d{1,2})")
        val m2 = xRegex.find(title)
        if (m2 != null) {
            val (s, e) = m2.destructured
            return Pair(s.toIntOrNull() ?: 0, e.toIntOrNull() ?: 0)
        }
        return null
    }

    // Checks conditions and triggers background subtitle search once per media item
    private fun maybePerformSubtitleAutoSearch() {
        val p = player ?: return
        val mediaId = p.currentMediaItem?.mediaId ?: p.currentMediaItem?.mediaId ?: ""
        if (mediaId.isEmpty()) return
        if (subtitleSearchLaunchedForMediaId == mediaId) return
        subtitleSearchLaunchedForMediaId = mediaId

        // Build query from title and media info
        val titleHint = _title.value.ifBlank { mediaId }
        // season/episode extraction available if needed; not used currently
        val tmdbId: String? = null
        val year: Int? = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val results = subtitleRepository.searchSubtitles(titleHint, "en", tmdbId, year)
                if (results.isEmpty()) return@launch

                // Check if player already has text tracks and whether any English subtitle exists
                val textGroups = p.currentTracks.groups.filter { g -> g.type == C.TRACK_TYPE_TEXT }
                val hasTextTracks = textGroups.isNotEmpty()
                val hasEnglishSubtitle = textGroups.any { g ->
                    // Check formats in the group for language hint 'en'
                    (0 until g.length).any { i ->
                        val f = g.getTrackFormat(i)
                        val lang = f.language?.lowercase() ?: ""
                        lang.startsWith("en") || f.label?.lowercase()?.contains("eng") == true
                    }
                }

                // Build a list of SubtitleEntry for UI listing (all remote candidates)
                val allEntries = results.map { s ->
                    val fid = s.attributes.files.firstOrNull()?.fileId
                    SubtitleEntry(
                        fileId = fid,
                        language = s.attributes.language,
                        downloadCount = s.attributes.downloadCount,
                        source = "opensubtitles",
                        localFilePath = null,
                        displayLabel = "${s.attributes.language} (downloads=${s.attributes.downloadCount})",
                        groupIndex = null,
                        trackIndex = null,
                        isEmbedded = false
                    )
                }.toMutableList()

                // If there are no subtitles or no English subtitles, auto-download+apply the best candidate
                if (!hasTextTracks || !hasEnglishSubtitle) {
                    val best = results.maxByOrNull { it.attributes.downloadCount }
                    val fileId = best?.attributes?.files?.firstOrNull()?.fileId
                    if (fileId != null) {
                        val file = try { subtitleRepository.downloadSubtitleByFileId(fileId) } catch (_: Exception) { null }
                        file?.let { downloadedFile ->
                            // Set local path on corresponding entry
                            allEntries.indexOfFirst { entry -> entry.fileId == fileId }.takeIf { idx -> idx >= 0 }?.let { idx ->
                                allEntries[idx].localFilePath = downloadedFile.path
                            }
                            // Apply on main thread
                            launch(Dispatchers.Main) { applySubtitle(downloadedFile) }
                        }
                    }
                } else {
                    // Stream already has English subtitles: pre-download top N candidates and inject them into MediaItem as subtitle tracks
                    // Publish candidates immediately (no local paths yet)
                    launch(Dispatchers.Main) { _availableSubtitles.value = allEntries }

                    // Pre-download top candidate(s)
                    val topCandidates = results.sortedByDescending { it.attributes.downloadCount }.take(2)
                    val downloadedFiles = mutableListOf<Pair<SubtitleEntry, File>>()
                    topCandidates.forEach { s ->
                        val fid = s.attributes.files.firstOrNull()?.fileId
                        if (fid != null) {
                            val file = try { subtitleRepository.downloadSubtitleByFileId(fid) } catch (_: Exception) { null }
                            if (file != null) {
                                val idx = allEntries.indexOfFirst { entry -> entry.fileId == fid }
                                if (idx >= 0) {
                                    allEntries[idx].localFilePath = file.path
                                }
                                val entry = allEntries.getOrNull(idx) ?: SubtitleEntry(fid, s.attributes.language, s.attributes.downloadCount, "opensubtitles", file.path, s.attributes.language, null, null, false)
                                downloadedFiles.add(Pair(entry, file))
                                launch(Dispatchers.Main) { _availableSubtitles.value = allEntries.toList() }
                            }
                        }
                    }

                    // If we downloaded any files, inject them into the MediaItem's subtitle configurations so they appear as subtitle tracks
                    if (downloadedFiles.isNotEmpty()) {
                        launch(Dispatchers.Main) {
                            val current = p.currentMediaItem ?: return@launch
                            // Try to obtain existing subtitle configurations via a getter (API varies by version)
                            val existing = try {
                                val m = current.javaClass.getMethod("getSubtitleConfigurations")
                                @Suppress("UNCHECKED_CAST")
                                m.invoke(current) as? List<MediaItem.SubtitleConfiguration>
                            } catch (_: Exception) {
                                null
                            } ?: emptyList()
                            val newConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()
                            newConfigs.addAll(existing)
                            downloadedFiles.forEach { (entry, file) ->
                                try {
                                    val uri = Uri.fromFile(file)
                                    val mime = if (file.name.lowercase().endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
                                    val subConfig = MediaItem.SubtitleConfiguration.Builder(uri)
                                        .setMimeType(mime)
                                        .setLanguage(entry.language ?: "")
                                        .setLabel(entry.displayLabel)
                                        .build()
                                    // Avoid duplicates based on uri
                                    if (existing.none { it.uri == uri }) newConfigs.add(subConfig)
                                } catch (_: Exception) { /* ignore config building errors */ }
                            }
                            // Rebuild media item with combined subtitle configurations
                            try {
                                val newMediaItem = current.buildUpon().setSubtitleConfigurations(newConfigs).build()
                                p.setMediaItem(newMediaItem, p.currentPosition)
                            } catch (ex: Exception) {
                                Log.e("PlaybackViewModel", "inject subtitles failed: ${ex.message}")
                            }
                        }
                    }
                }

                // Merge in any embedded subtitles (gather from player's currentTracks)
                val embeddedEntries = p.currentTracks.groups
                    .mapIndexed { groupIndex, group ->
                        if (group.type != C.TRACK_TYPE_TEXT) emptyList<SubtitleEntry>()
                        else {
                            (0 until group.length).map { trackIndex ->
                                val format = group.getTrackFormat(trackIndex)
                                SubtitleEntry(
                                    fileId = null,
                                    language = format.language,
                                    downloadCount = 0,
                                    source = "embedded",
                                    localFilePath = null,
                                    displayLabel = format.label ?: format.language ?: "Embedded Subtitle",
                                    groupIndex = groupIndex,
                                    trackIndex = trackIndex,
                                    isEmbedded = true
                                )
                            }
                        }
                    }
                    .flatten()
                    .filterNot { embedded -> allEntries.any { remote -> remote.language == embedded.language && remote.isEmbedded } }

                // Publish combined list (remote + embedded)
                launch(Dispatchers.Main) {
                    _availableSubtitles.value = (allEntries + embeddedEntries).distinctBy { it.language }
                }
            } catch (e: Exception) {
                Log.e("PlaybackViewModel", "background subtitle search failed", e)
            }
        }
    }

    // Helper: create a SubtitleConfiguration from a downloaded file (used elsewhere if needed)
    @Suppress("unused")
    private fun subtitleConfigFromFile(file: File, language: String?, label: String?): MediaItem.SubtitleConfiguration {
        val uri = Uri.fromFile(file)
        val mime = if (file.name.lowercase().endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
        return MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLanguage(language ?: "")
            .setLabel(label ?: file.name)
            .build()
    }

    private fun applySubtitle(subtitleFile: File) {
        player?.let { p ->
            val currentMediaItem = p.currentMediaItem ?: return
            val subtitleUri = Uri.fromFile(subtitleFile)

            val subConfig = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                .setMimeType(MimeTypes.APPLICATION_SUBRIP) // Assuming SRT
                .setLanguage("en")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()

            val newMediaItem = currentMediaItem.buildUpon()
                .setSubtitleConfigurations(listOf(subConfig))
                .build()

            p.setMediaItem(newMediaItem, p.currentPosition)
        }
    }

    fun toggleAudioBoost() {
        _isAudioBoostEnabled.value = !_isAudioBoostEnabled.value
        playbackRepository.setAudioBoost(_isAudioBoostEnabled.value)
    }
    @OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun updateTrackLists(tracks: Tracks) {
        val audioTracks = mutableListOf<AudioTrackInfo>()
        var selectedIndex = 0

        Log.d("PlaybackViewModel", "onTracksChanged: groups=${tracks.groups.size}")

        tracks.groups.forEachIndexed { groupIndex, trackGroup ->
            if (trackGroup.type == C.TRACK_TYPE_AUDIO) {
                Log.d("PlaybackViewModel", "Found audio group index=$groupIndex, formats=${trackGroup.mediaTrackGroup.length}")
                for (i in 0 until trackGroup.length) {
                    val format = trackGroup.getTrackFormat(i) // FIXED: Use getTrackFormat instead of getFormat
                    Log.d(
                        "PlaybackViewModel",
                        "audio format: group=$groupIndex index=$i containerMime=${format.containerMimeType} sampleMime=${format.sampleMimeType} codecs=${format.codecs} sampleRate=${format.sampleRate} channels=${format.channelCount} language=${format.language} label=${format.label}"
                    )
                    val trackId = "$groupIndex:$i"
                    audioTracks.add(
                        AudioTrackInfo(
                            id = trackId,
                            label = format.label ?: format.language ?: "Track ${i + 1}",
                            language = format.language
                        )
                    )
                    if (trackGroup.isTrackSelected(i)) {
                        selectedIndex = audioTracks.size - 1
                    }
                }
            }
        }
        _availableAudioTracks.value = audioTracks
        _selectedAudioTrackIndex.value = selectedIndex
    }

    fun selectAudioTrack(trackIndex: Int) {
        val trackInfo = _availableAudioTracks.value.getOrNull(trackIndex) ?: return
        val player = this.player ?: return

        val (groupIndexStr, trackIndexInGroupStr) = trackInfo.id.split(":")
        val groupIndex = groupIndexStr.toInt()
        val trackIndexInGroup = trackIndexInGroupStr.toInt()

        val trackGroup = player.currentTracks.groups[groupIndex].mediaTrackGroup
        val override = TrackSelectionOverride(trackGroup, ImmutableList.of(trackIndexInGroup))

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(override)
            .build()
        _selectedAudioTrackIndex.value = trackIndex
    }

    /**
     * Select a subtitle entry. If it's an embedded track, select via player's track override.
     * If it's a downloaded file, apply it. If it's a remote candidate with fileId, download+apply.
     */
    fun selectSubtitle(entry: SubtitleEntry) {
        val p = player ?: return
        // If embedded, use TrackSelectionOverride similar to audio selection
        if (entry.isEmbedded && entry.groupIndex != null && entry.trackIndex != null) {
            try {
                val groupIndex = entry.groupIndex
                val trackIndex = entry.trackIndex
                val trackGroup = p.currentTracks.groups[groupIndex].mediaTrackGroup
                val override = TrackSelectionOverride(trackGroup, ImmutableList.of(trackIndex))
                p.trackSelectionParameters = p.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(override)
                    .build()
            } catch (e: Exception) {
                Log.e("PlaybackViewModel", "selectSubtitle embedded failed: ${e.message}")
            }
            return
        }

        // If we already have a local file path, apply it
        entry.localFilePath?.let { path ->
            try {
                val file = File(path)
                if (file.exists()) {
                    applySubtitle(file)
                    return
                }
            } catch (e: Exception) {
                Log.e("PlaybackViewModel", "apply subtitle failed: ${e.message}")
            }
        }

        // If we have a fileId (remote), download and apply
        entry.fileId?.let { fid ->
            downloadAndApplySubtitleByFileId(fid) { success ->
                if (!success) Log.d("PlaybackViewModel", "downloadAndApplySubtitle failed for id=$fid")
            }
        }
    }

    // Expose subtitle search functionality for the UI: performs the search on IO and returns via callback on main
    fun searchSubtitles(
        query: String,
        language: String = "en",
        tmdbId: String? = null,
        year: Int? = null,
        onResult: (List<Subtitle>) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val results = try {
                subtitleRepository.searchSubtitles(query, language, tmdbId, year)
            } catch (e: Exception) {
                Log.e("PlaybackViewModel", "searchSubtitles failed", e)
                emptyList()
            }
            launch(Dispatchers.Main) { onResult(results) }
        }
    }

    // Download a subtitle by fileId and apply it to the current player when ready. Reports success via callback.
    fun downloadAndApplySubtitleByFileId(fileId: Long, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = try {
                subtitleRepository.downloadSubtitleByFileId(fileId)
            } catch (e: Exception) {
                Log.e("PlaybackViewModel", "downloadSubtitleByFileId failed", e)
                null
            }

            if (file != null) {
                launch(Dispatchers.Main) {
                    applySubtitle(file)
                    onComplete(true)
                }
            } else {
                launch(Dispatchers.Main) { onComplete(false) }
            }
        }
    }

    fun release() {
        // ...existing code...
        retryJob?.cancel()
        retryJob = null
        onActivityStop()
        playbackRepository.release()
        this.player = null
        stopPlayerObserver()
    }

    override fun onCleared() {
        super.onCleared()
        release()
    }

    fun onActivityStop() {
        player?.let {
            viewModelScope.launch {
                val mediaId = it.currentMediaItem?.mediaId ?: ""
                val currentPos = it.currentPosition
                val currentDuration = it.duration

                // Use current duration if valid, otherwise fallback to last known good duration
                val duration = if (currentDuration > 0) currentDuration else lastKnownDurationMs

                Log.d("PlaybackViewModel", "onActivityStop: mediaId=$mediaId, currentPos=$currentPos, duration=$duration (current=$currentDuration, lastKnown=$lastKnownDurationMs)")

                // Detect if playback is near or at completion:
                // 1. Position is within 2 minutes of the end (for episodes/movies you skipped ahead on)
                // 2. Position is >= 95% of duration (normal completion)
                val NEAR_END_THRESHOLD_MS = 120_000L // 2 minutes
                val completionThreshold = 0.95f

                val isNearEnd = duration > 0 && (duration - currentPos) <= NEAR_END_THRESHOLD_MS
                val isCompleted = duration > 0 && currentPos >= (duration * completionThreshold)
                val shouldClear = isNearEnd || isCompleted

                if (shouldClear) {
                    Log.d("PlaybackViewModel", "onActivityStop: Near/at end ($currentPos/$duration, nearEnd=$isNearEnd, completed=$isCompleted), clearing resume position")
                    // Save position 0 to clear the resume point for this media
                    resumeRepository.saveResumePosition(mediaId, 0L)
                } else {
                    Log.d("PlaybackViewModel", "onActivityStop: Saving resume position $currentPos for $mediaId")
                    resumeRepository.saveResumePosition(mediaId, currentPos)
                }
            }
        }
    }

    // Build a lightweight diagnostics snapshot locally so the view model can show repo status even
    // if referencing repository helper methods causes resolution issues during compilation in some setups.
    private fun buildLocalDiagnosticsSnapshot(): String {
        val sb = StringBuilder()
        val ffmpegDetected = try {
            Class.forName("org.jellyfin.media3.ffmpeg.FfmpegDecoderFactory")
            true
        } catch (_: Throwable) {
            false
        }
        sb.append("PlaybackRepository Diagnostics:\n")
        sb.append("  FFmpeg extension present: $ffmpegDetected\n")
        sb.append("  ExoPlayer present: ${player != null}\n")
        return sb.toString()
    }

    // Toggles play/pause state
    fun playPause() {
        player?.let {
            if (it.isPlaying) {
                it.pause()
                _isPlaying.value = false
            } else {
                it.play()
                _isPlaying.value = true
            }
        }
    }

    // Seeks to a specific fraction of the total duration
    fun seekToFraction(fraction: Float) {
        player?.let {
            val seekPosition = (fraction * it.duration).toLong()
            it.seekTo(seekPosition)

            // Save the new position immediately after seeking
            val mediaId = it.currentMediaItem?.mediaId ?: ""
            if (mediaId.isNotEmpty()) {
                scope.launch(Dispatchers.IO) {
                    try {
                        resumeRepository.saveResumePosition(mediaId, seekPosition)
                        Log.d("PlaybackViewModel", "Seek position saved: position=$seekPosition for $mediaId")
                    } catch (e: Exception) {
                        Log.e("PlaybackViewModel", "Error saving seek position: ${e.message}")
                    }
                }
            }
        }
    }
}