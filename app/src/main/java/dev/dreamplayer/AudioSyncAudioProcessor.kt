package dev.dreamplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Applies a small, seek-reset audio offset to decoded PCM.
 *
 * Positive values insert silence so audio is heard later. Negative values trim
 * initial PCM so audio is heard earlier. The player is re-seeked when the value
 * changes, which flushes the processor and starts the new adjustment cleanly.
 */
class AudioSyncAudioProcessor(initialDelayMs: Int = 0) : BaseAudioProcessor() {
    @Volatile
    var delayMs: Int = initialDelayMs.coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)
        private set

    private var pendingFrames = 0L
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
        val sampleRate = inputAudioFormat.sampleRate
        pendingFrames = if (sampleRate > 0) delayMs.toLong() * sampleRate / 1_000L else 0L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytesPerFrame = inputAudioFormat.channelCount * 2
        if (bytesPerFrame <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        if (pendingFrames < 0L) {
            val availableFrames = inputBuffer.remaining() / bytesPerFrame
            val framesToDrop = minOf(-pendingFrames, availableFrames.toLong()).toInt()
            inputBuffer.position(inputBuffer.position() + framesToDrop * bytesPerFrame)
            pendingFrames += framesToDrop
            if (!inputBuffer.hasRemaining()) return
        }

        val audioBytes = inputBuffer.remaining()
        if (scratch.size < audioBytes) scratch = ByteArray(audioBytes)
        inputBuffer.get(scratch, 0, audioBytes)

        val silenceBytes = if (pendingFrames > 0L) {
            (pendingFrames * bytesPerFrame).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else 0
        // Media3 may recycle this processor's previous direct output as the
        // next source buffer. Stage PCM first so replaceOutputBuffer() cannot
        // turn output.put(input) into an illegal self-copy.
        val output = replaceOutputBuffer(silenceBytes + audioBytes)
        repeat(silenceBytes) { output.put(0) }
        pendingFrames = 0L
        output.put(scratch, 0, audioBytes)
        output.flip()
    }

    companion object {
        const val MAX_DELAY_MS = 500
    }
}
