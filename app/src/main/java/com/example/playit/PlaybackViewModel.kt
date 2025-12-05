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

    private val scope = CoroutineScope(Dispatchers.Main)
    private var playerStateJob: Job? = null
    // Track which mediaId we've already launched subtitle search for, avoid duplicate searches
    private var subtitleSearchLaunchedForMediaId: String? = null

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
                        Log.d("PlaybackViewModel", "Player is ready, loading complete")
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
    }

    private fun stopPlayerObserver() {
        playerStateJob?.cancel()
        playerStateJob = null
    }

    fun playMedia(url: String?) {
        if (url == null) return
        _isAudioBoostEnabled.value = false // Reset boost on new media
        _isLoading.value = true // Start loading
        initializePlayer() // Re-initialize if released
        _title.value = url.substringAfterLast('/') // Simple title extraction
        scope.launch {
            // Try best-match resume position (handles URL variants, filename-only keys, hashes)
            val resumePosition = try {
                resumeRepository.getBestResumePosition(url)
            } catch (_: Exception) {
                resumeRepository.getResumePosition(url)
            }
            Log.d("PlaybackViewModel", "playMedia: url=$url resumePosition=$resumePosition")
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
                resumeRepository.saveResumePosition(it.currentMediaItem?.mediaId ?: "", it.currentPosition)
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
        }
    }
}