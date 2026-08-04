package dev.dreamplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AudioSyncAudioProcessorTest {
    @Test
    fun positiveDelayIsAppliedWithoutAFlush() {
        val processor = processor(delayMs = 2)
        assertArrayEquals(shortArrayOf(0, 0, 1, 2), process(processor, 1, 2))

        processor.setDelayMs(4)

        assertArrayEquals(shortArrayOf(0, 0, 3, 4), process(processor, 3, 4))
    }

    @Test
    fun reducingDelayDropsOnlyTheRequiredDelta() {
        val processor = processor(delayMs = 4)
        process(processor, 1, 2)

        processor.setDelayMs(1)

        assertArrayEquals(shortArrayOf(6, 7), process(processor, 3, 4, 5, 6, 7))
    }

    @Test
    fun negativeDelayDropsInitialFrames() {
        val processor = processor(delayMs = -2)

        assertArrayEquals(shortArrayOf(3, 4), process(processor, 1, 2, 3, 4))
    }

    @Test
    fun flushReappliesTheFullSelectedDelay() {
        val processor = processor(delayMs = 2)
        process(processor, 1)

        processor.flush()

        assertArrayEquals(shortArrayOf(0, 0, 2), process(processor, 2))
    }

    private fun processor(delayMs: Int): AudioSyncAudioProcessor =
        AudioSyncAudioProcessor(delayMs).also {
            // A 1 kHz mono stream makes one frame equal one millisecond.
            it.configure(AudioFormat(1_000, 1, C.ENCODING_PCM_16BIT))
            it.flush()
        }

    private fun process(
        processor: AudioSyncAudioProcessor,
        vararg samples: Short,
    ): ShortArray {
        val input = ByteBuffer.allocateDirect(samples.size * 2)
            .order(ByteOrder.nativeOrder())
        samples.forEach(input::putShort)
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        return ShortArray(output.remaining() / 2) { output.short }
    }
}
