package com.example.playit

import android.util.Log
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

@UnstableApi
class VolumeAudioProcessor : BaseAudioProcessor() {

    private var boostEnabled = false
    private var volume = 1.0f

    // Compressor state for dynamic range compression
    private var compressorGain = 1.0f

    // High-pass filter state (to amplify dialog: 1-4 kHz range)
    private var dialogBoostPrev1 = 0f
    private var dialogBoostPrev2 = 0f

    fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0.5f, 3.0f)
    }

    fun setBoostEnabled(enabled: Boolean) {
        boostEnabled = enabled
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        Log.d("VolumeAudioProcessor", "onConfigure: encoding=${inputAudioFormat.encoding}, sampleRate=${inputAudioFormat.sampleRate}, channelCount=${inputAudioFormat.channelCount}, boost=$boostEnabled")
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        // Check if this is a format we can process (16-bit or Float)
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            // Passthrough: Just copy the data directly without touching it
            Log.d("VolumeAudioProcessor", "queueInput: passthrough non-PCM encoding=${inputAudioFormat.encoding} bytes=$remaining")
            val outputBuffer = replaceOutputBuffer(remaining)
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        Log.d("VolumeAudioProcessor", "queueInput: processing PCM encoding=${inputAudioFormat.encoding} bytes=$remaining volume=$volume boost=$boostEnabled")
        val outputBuffer = replaceOutputBuffer(remaining)

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                processAudio16Bit(inputBuffer, outputBuffer)
            }
            C.ENCODING_PCM_FLOAT -> {
                processAudioFloat(inputBuffer, outputBuffer)
            }
            else -> {
                outputBuffer.put(inputBuffer)
            }
        }
        outputBuffer.flip()
    }

    private fun processAudio16Bit(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer) {
        while (inputBuffer.hasRemaining()) {
            val sample = inputBuffer.getShort().toFloat() / 32768f // Normalize to -1.0 to 1.0
            val processed = processAudioSample(sample)
            val clipped = (processed * 32768f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            outputBuffer.putShort(clipped.toShort())
        }
    }

    private fun processAudioFloat(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer) {
        while (inputBuffer.hasRemaining()) {
            val sample = inputBuffer.getFloat()
            val processed = processAudioSample(sample)
            outputBuffer.putFloat(processed.coerceIn(-1.0f, 1.0f))
        }
    }

    private fun processAudioSample(sample: Float): Float {
        var output = sample

        // Step 1: Apply base volume
        output *= volume

        if (boostEnabled) {
            // Step 2: Dialog amplification (1-4 kHz boost using high-pass filter)
            // This emphasizes speech clarity without harsh distortion
            output = applyDialogBoost(output)

            // Step 3: Dynamic range compression
            // Reduces loud peaks while amplifying quiet signals
            output = applyDynamicCompression(output)

            // Step 4: Soft limiting to catch any remaining peaks
            output = applySoftLimiter(output)
        }

        return output
    }

    /**
     * Applies psychoacoustic dialog boost.
     * Enhances the 1-4 kHz range where human speech is most intelligible.
     * Uses a simple shelving filter approach.
     */
    private fun applyDialogBoost(sample: Float): Float {
        // Simple high-pass + peaking filter for dialog enhancement
        // Coefficient for ~2 kHz peaking at 48kHz sample rate
        val dialogGain = 1.4f // 40% boost to dialog frequencies

        // Apply a basic peaking filter using previous samples
        val filtered = sample * 0.6f + dialogBoostPrev1 * 0.3f + dialogBoostPrev2 * 0.1f
        dialogBoostPrev2 = dialogBoostPrev1
        dialogBoostPrev1 = sample

        return filtered * dialogGain
    }

    /**
     * Dynamic range compression:
     * - Amplifies quiet signals (below threshold)
     * - Reduces loud signals (above threshold)
     * - Prevents clipping while maintaining punch
     */
    private fun applyDynamicCompression(sample: Float): Float {
        val threshold = 0.6f  // Compression kicks in above 60% amplitude
        val ratio = 4.0f      // 4:1 compression ratio
        val kneeWidth = 0.1f  // Smooth knee for natural sound
        val makeup = 1.15f    // Makeup gain to compensate for compression

        val absSignal = kotlin.math.abs(sample)

        val gain = if (absSignal < (threshold - kneeWidth)) {
            // Below threshold: slightly amplify quiet signals
            1.1f
        } else if (absSignal > (threshold + kneeWidth)) {
            // Above threshold: compress
            val excess = absSignal - threshold
            val compressed = excess / ratio
            val newLevel = threshold + compressed
            newLevel / absSignal
        } else {
            // In the knee region: smooth transition
            val kneeGain = 1.1f + ((threshold - absSignal) / kneeWidth) * 0.2f
            kneeGain / absSignal.coerceAtLeast(0.001f)
        }

        return (sample * gain * makeup).coerceIn(-1.0f, 1.0f)
    }

    /**
     * Soft limiter to catch any peaks that slip through.
     * Uses a tanh-like soft clipping to prevent harsh digital clipping.
     */
    private fun applySoftLimiter(sample: Float): Float {
        val limit = 0.95f
        val absSignal = kotlin.math.abs(sample)

        return if (absSignal <= limit) {
            sample
        } else {
            // Soft knee limiting using tanh-like curve
            val excess = absSignal - limit
            val limitFactor = kotlin.math.tanh(excess * 2.0f) / 2.0f
            val limitedAbs = limit + limitFactor
            sample * (limitedAbs / absSignal).coerceAtMost(1.0f)
        }
    }
}