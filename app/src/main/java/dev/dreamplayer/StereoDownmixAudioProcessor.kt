package dev.dreamplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Converts decoded multichannel PCM to a headset-safe stereo stream. */
class StereoDownmixAudioProcessor : BaseAudioProcessor() {
    private var sampleScratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount <= 2) return AudioFormat.NOT_SET
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val frameCount = inputBuffer.remaining() / (channels * 2)
        val output = replaceOutputBuffer(frameCount * 4)
        if (sampleScratch.size < channels) sampleScratch = FloatArray(channels)
        repeat(frameCount) {
            for (channel in 0 until channels) {
                sampleScratch[channel] = inputBuffer.short / 32768f
            }
            val left = mix(sampleScratch, channels, left = true)
            val right = mix(sampleScratch, channels, left = false)
            output.putShort((left.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
            output.putShort((right.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
        }
        output.flip()
    }

    private fun mix(samples: FloatArray, channelCount: Int, left: Boolean): Float {
        // Android's decoded PCM order is FL, FR, FC, LFE, BL, BR, SL, SR.
        var value = sample(samples, channelCount, if (left) 0 else 1)
        value += sample(samples, channelCount, 2) * 0.707f
        value += sample(samples, channelCount, 3) * 0.35f
        value += sample(samples, channelCount, if (left) 4 else 5) * 0.5f
        value += sample(samples, channelCount, if (left) 6 else 7) * 0.5f
        return value * 0.58f
    }

    private fun sample(samples: FloatArray, channelCount: Int, index: Int): Float =
        if (index < channelCount) samples[index] else 0f
}
