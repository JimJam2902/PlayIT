package com.example.playit

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.AudioSink
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipFile

@UnstableApi
class PlaybackRepository(private val context: Context) {
    var player: ExoPlayer? = null
    private var volumeProcessor: VolumeAudioProcessor? = null

    // Diagnostics state
    private var ffmpegDetected: Boolean = false
    private var ffmpegClassName: String? = null
    private var extensionModePreferred: Boolean = false
    private var ffmpegProviderCandidates: List<String> = emptyList()

    @OptIn(UnstableApi::class)
    fun initializePlayer(
        trackSelector: DefaultTrackSelector,
        audioBoostEnabled: Boolean
    ): ExoPlayer {
        release()

        // Create volume processor
        volumeProcessor = VolumeAudioProcessor().apply {
            setVolume(if (audioBoostEnabled) 2.0f else 1.0f)
        }

        val renderersFactory = object : DefaultRenderersFactory(context) {
            // Correct signature for newer media3 versions
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? {
                Log.d("PlaybackRepository", "buildAudioSink: enableFloatOutput=$enableFloatOutput, enablePlaybackParams=$enableAudioTrackPlaybackParams, audioProcessorPresent=${volumeProcessor != null}")
                val audioProcessors = if (volumeProcessor != null) arrayOf<AudioProcessor>(volumeProcessor!!) else arrayOf()
                val audioSink = DefaultAudioSink.Builder()
                    .setAudioProcessors(audioProcessors)
                    .build()
                Log.d("PlaybackRepository", "buildAudioSink: created DefaultAudioSink with ${audioProcessors.size} audio processors")
                return audioSink
            }
        }.apply {
            // Prefer extension renderers (e.g., FFmpeg-based decoders) when available so codecs like AC3/E-AC3 can be
            // decoded in software on devices that lack hardware decoders.
            try {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                extensionModePreferred = true
                Log.d("PlaybackRepository", "renderersFactory: setExtensionRendererMode=EXTENSION_RENDERER_MODE_PREFER")
            } catch (e: Throwable) {
                extensionModePreferred = false
                Log.d("PlaybackRepository", "renderersFactory: unable to set extension renderer mode: ${e.message}")
            }
        }

        // Use a safe classloader (prefer app classloader) for class/resource checks
        val loader = javaClass.classLoader ?: Thread.currentThread().contextClassLoader

        // Detect FFmpeg extension presence by checking several known class names
        val candidateClassNames = listOf(
            // Classes included in the Jellyfin/community AAR we packaged
            "androidx.media3.decoder.ffmpeg.FfmpegLibrary",
            "androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder",
            "androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer",
            "androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer",
            // Older/other forks
            "org.jellyfin.media3.ffmpeg.FfmpegDecoderFactory",
            "org.jellyfin.media3.ffmpeg.decoder.FfmpegDecoderFactory",
            "org.jellyfin.media3.ffmpeg.ffmpeg.FfmpegDecoderFactory",
            "org.jellyfin.media3.ffmpeg.FfmpegDecoderFactoryImpl",
            // Legacy / other forks
            "com.google.android.exoplayer2.ext.ffmpeg.FfmpegLibrary",
            "com.google.android.exoplayer2.ext.ffmpeg.FfmpegDecoder"
        )
        ffmpegDetected = false
        ffmpegClassName = null
        for (name in candidateClassNames) {
            try {
                val clazz = loader.loadClass(name)
                ffmpegDetected = true
                ffmpegClassName = clazz.name
                Log.d("PlaybackRepository", "FFmpeg extension detected via class: ${clazz.name}")
                break
            } catch (_: ClassNotFoundException) {
                // try next
            }
        }

        // Additionally, inspect META-INF/services entries for DecoderFactory providers (more reliable)
        try {
            val providers = mutableListOf<String>()
            val resEnum = loader.getResources("META-INF/services/androidx.media3.decoder.DecoderFactory")
            while (resEnum.hasMoreElements()) {
                val url = resEnum.nextElement()
                try {
                    val stream = url.openStream()
                    val reader = BufferedReader(InputStreamReader(stream))
                    reader.useLines { lines ->
                        lines.map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                            .forEach { providers.add(it) }
                    }
                } catch (e: Exception) {
                    Log.d("PlaybackRepository", "error reading service file ${url}: ${e.message}")
                }
            }
            if (providers.isNotEmpty()) {
                ffmpegProviderCandidates = providers
                // If any provider mentions ffmpeg or jellyfin, mark detected
                for (prov in providers) {
                    if (prov.contains("ffmpeg", ignoreCase = true) || prov.contains("jellyfin", ignoreCase = true)) {
                        ffmpegDetected = true
                        if (ffmpegClassName == null) ffmpegClassName = prov
                    }
                }
                Log.d("PlaybackRepository", "DecoderFactory providers found: ${providers.joinToString(", ")}")
            } else {
                Log.d("PlaybackRepository", "No DecoderFactory service providers found in META-INF/services")
            }
        } catch (e: Exception) {
            Log.d("PlaybackRepository", "error scanning service providers: ${e.message}")
        }

        // Fallback: if no Java provider found, check for the native library packaged in the app's native lib dir
        if (!ffmpegDetected) {
            try {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                val libFile = File(nativeLibDir, "libffmpegJNI.so")
                if (libFile.exists()) {
                    ffmpegDetected = true
                    ffmpegClassName = "(native lib present: libffmpegJNI.so)"
                    Log.d("PlaybackRepository", "FFmpeg native lib present at ${libFile.absolutePath}")
                } else {
                    // If not found on filesystem, inspect the APK for packaged native libs (lib/<abi>/libffmpegJNI.so)
                    try {
                        val apkPath = context.applicationInfo.sourceDir
                        val zip = ZipFile(apkPath)
                        try {
                            val abis = Build.SUPPORTED_ABIS.takeIf { it.isNotEmpty() } ?: arrayOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                            var found = false
                            for (abi in abis) {
                                val entryName = "lib/$abi/libffmpegJNI.so"
                                if (zip.getEntry(entryName) != null) {
                                    found = true
                                    break
                                }
                            }
                            if (found) {
                                ffmpegDetected = true
                                ffmpegClassName = "(native lib packaged in APK: libffmpegJNI.so)"
                                Log.d("PlaybackRepository", "FFmpeg native lib packaged inside APK: $apkPath")
                            }
                        } finally {
                            zip.close()
                        }
                    } catch (e: Exception) {
                        Log.d("PlaybackRepository", "error inspecting APK for native libs: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.d("PlaybackRepository", "error checking native lib presence: ${e.message}")
            }
        }

        if (!ffmpegDetected) Log.d("PlaybackRepository", "FFmpeg extension not detected on classpath (tried ${candidateClassNames.size} names)")

        player = ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .build()

        // Log registered renderers for diagnostics (use reflection because renderers is internal)
        try {
            val renderersField = player!!.javaClass.getDeclaredField("renderers")
            renderersField.isAccessible = true
            val renderers = renderersField.get(player) as? Array<*>
            if (renderers != null) {
                Log.d("PlaybackRepository", "Registered renderers count=${renderers.size}")
                renderers.forEachIndexed { i, r ->
                    Log.d("PlaybackRepository", "Renderer[$i]: ${r?.javaClass?.name}")
                }
            } else {
                Log.d("PlaybackRepository", "No renderers array found on player instance")
            }
        } catch (e: Exception) {
            Log.d("PlaybackRepository", "Failed to inspect renderers via reflection: ${e.message}")
        }

        Log.d("PlaybackRepository", "initializePlayer: ExoPlayer initialized (audioBoostEnabled=$audioBoostEnabled)")

        return player!!
    }

    @OptIn(UnstableApi::class)
    fun setAudioBoost(enabled: Boolean) {
        volumeProcessor?.setVolume(if (enabled) 2.0f else 1.0f)
    }

    fun prepareAndPlay(url: String, startPosition: Long) {
        val p = player ?: throw IllegalStateException("Player is not initialized. Call initializePlayer first.")

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMediaId(url)
            .build()

        p.setMediaItem(mediaItem, startPosition)
        p.prepare()
        p.play()
    }

    fun release() {
        player?.release()
        player = null
        volumeProcessor = null
    }

    // Diagnostics snapshot for UI (use real interpolation so runtime shows values)
    fun getDiagnosticsSnapshot(): String {
        return buildString {
            appendLine("PlaybackRepository Diagnostics:")
            appendLine("  FFmpeg extension present: $ffmpegDetected")
            appendLine("  FFmpeg class name: ${ffmpegClassName ?: "(none detected)"}")
            appendLine("  Extension renderer mode preferred: $extensionModePreferred")
            appendLine("  ExoPlayer present: ${player != null}")
            if (ffmpegProviderCandidates.isNotEmpty()) {
                appendLine("  DecoderFactory providers:")
                ffmpegProviderCandidates.forEach { appendLine("    - $it") }
            }
            appendLine()
            appendLine("To add FFmpeg if missing, add a dependency like (one of):")
            appendLine("  implementation(\"org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1\")  // Jellyfin fork on MavenCentral/JitPack")
            appendLine("Or, if using JitPack, add JitPack repo in settings.gradle.kts:")
            appendLine("  pluginManagement { repositories { maven { url = uri(\"https://jitpack.io\") } } }")
            appendLine("Then rebuild and reinstall the app.")
        }
    }
}