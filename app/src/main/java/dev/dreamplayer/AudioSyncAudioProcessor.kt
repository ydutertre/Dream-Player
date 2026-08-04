package dev.dreamplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Applies a small, dynamically adjustable audio offset to decoded PCM.
 *
 * Positive values insert silence so audio is heard later. Negative values trim
 * PCM so audio is heard earlier. Changes are applied as a delta on the next
 * audio buffer, so they do not depend on a same-position seek being treated as
 * a decoder discontinuity.
 */
class AudioSyncAudioProcessor(initialDelayMs: Int = 0) : BaseAudioProcessor() {
    @Volatile
    var delayMs: Int = initialDelayMs.coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)
        private set

    // Accessed only by Media3's audio thread. delayMs is volatile because it is
    // changed by the UI thread.
    private var appliedDelayFrames = 0L
    private var scratch = ByteArray(0)

    fun setDelayMs(value: Int) {
        delayMs = value.coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        // A real seek/reload starts a fresh timeline, so the complete requested
        // offset must be applied again to the first decoded PCM buffer.
        appliedDelayFrames = 0L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytesPerFrame = inputAudioFormat.channelCount * 2
        if (bytesPerFrame <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val sampleRate = inputAudioFormat.sampleRate
        val requestedDelayFrames =
            if (sampleRate > 0) delayMs.toLong() * sampleRate / 1_000L else 0L
        var adjustmentFrames = requestedDelayFrames - appliedDelayFrames

        if (adjustmentFrames < 0L) {
            val availableFrames = inputBuffer.remaining() / bytesPerFrame
            val framesToDrop = minOf(-adjustmentFrames, availableFrames.toLong()).toInt()
            inputBuffer.position(inputBuffer.position() + framesToDrop * bytesPerFrame)
            appliedDelayFrames -= framesToDrop
            adjustmentFrames += framesToDrop
            if (!inputBuffer.hasRemaining()) return
        }

        val audioBytes = inputBuffer.remaining()
        if (scratch.size < audioBytes) scratch = ByteArray(audioBytes)
        inputBuffer.get(scratch, 0, audioBytes)

        val silenceFrames = adjustmentFrames.coerceAtLeast(0L)
        val silenceBytes = if (silenceFrames > 0L) {
            (silenceFrames * bytesPerFrame).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else 0
        // Media3 may recycle this processor's previous direct output as the
        // next source buffer. Stage PCM first so replaceOutputBuffer() cannot
        // turn output.put(input) into an illegal self-copy.
        val output = replaceOutputBuffer(silenceBytes + audioBytes)
        repeat(silenceBytes) { output.put(0) }
        if (silenceFrames > 0L) appliedDelayFrames += silenceFrames
        output.put(scratch, 0, audioBytes)
        output.flip()
    }

    companion object {
        const val MAX_DELAY_MS = 500
    }
}
