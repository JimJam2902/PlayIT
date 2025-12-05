package com.example.playit

import android.util.Log
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

@UnstableApi
class VolumeAudioProcessor : BaseAudioProcessor() {

    private var volume = 1.0f

    fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0.5f, 3.0f)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // FIX 1: Always return the input format.
        // We will decide in queueInput whether to process it or just copy it.
        Log.d("VolumeAudioProcessor", "onConfigure: encoding=${inputAudioFormat.encoding}, sampleRate=${inputAudioFormat.sampleRate}, channelCount=${inputAudioFormat.channelCount}")
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        // FIX 2: Check if this is a format we can actually process (16-bit or Float)
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            // Passthrough: Just copy the data directly without touching it
            Log.d("VolumeAudioProcessor", "queueInput: passthrough non-PCM encoding=${inputAudioFormat.encoding} bytes=$remaining")
            val outputBuffer = replaceOutputBuffer(remaining)
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        // Processing Logic for supported formats
        Log.d("VolumeAudioProcessor", "queueInput: processing PCM encoding=${inputAudioFormat.encoding} bytes=$remaining volume=$volume")
        val outputBuffer = replaceOutputBuffer(remaining)

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                while (inputBuffer.hasRemaining()) {
                    val sample = inputBuffer.getShort()
                    val processed = (sample * volume).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    outputBuffer.putShort(processed.toShort())
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                while (inputBuffer.hasRemaining()) {
                    val sample = inputBuffer.getFloat()
                    val processed = (sample * volume).coerceIn(-1.0f, 1.0f)
                    outputBuffer.putFloat(processed)
                }
            }
            else -> {
                // Should not be reached due to the check above, but safe fallback
                outputBuffer.put(inputBuffer)
            }
        }
        outputBuffer.flip()
    }
}