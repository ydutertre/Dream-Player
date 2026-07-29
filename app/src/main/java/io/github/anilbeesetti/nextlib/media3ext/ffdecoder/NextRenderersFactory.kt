package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import io.github.anilbeesetti.nextlib.media3ext.renderer.NextTextRenderer


@UnstableApi
open class NextRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    private var preferFfmpegForDts = false
    private var ffmpegVideoEnabled = true

    fun setPreferFfmpegForDts(prefer: Boolean): NextRenderersFactory = apply {
        preferFfmpegForDts = prefer
    }

    fun setFfmpegVideoEnabled(enabled: Boolean): NextRenderersFactory = apply {
        ffmpegVideoEnabled = enabled
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )

        if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF) return

        // Some Android DTS MediaCodec implementations expose an input buffer that is smaller
        // than valid DTS access units. Put a DTS-only FFmpeg renderer immediately ahead of the
        // platform audio renderer, while leaving every other codec on its normal path.
        if (preferFfmpegForDts && extensionRendererMode != EXTENSION_RENDERER_MODE_PREFER) {
            try {
                val renderer = DtsOnlyFfmpegAudioRenderer(eventHandler, eventListener, audioSink)
                out.add((out.size - 1).coerceAtLeast(0), renderer)
                Log.i(TAG, "Loaded preferred DTS FfmpegAudioRenderer.")
            } catch (e: Exception) {
                throw RuntimeException("Error instantiating DTS Ffmpeg extension", e)
            }
        }

        var extensionRendererIndex = out.size
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER) {
            extensionRendererIndex--
        }

        try {
            val renderer = FfmpegAudioRenderer(eventHandler, eventListener, audioSink)
            out.add(extensionRendererIndex++, renderer)
            Log.i(TAG, "Loaded FfmpegAudioRenderer.")
        } catch (e: Exception) {
            // The extension is present, but instantiation failed.
            throw RuntimeException("Error instantiating Ffmpeg extension", e)
        }
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out
        )

        if (
            extensionRendererMode == EXTENSION_RENDERER_MODE_OFF ||
            !ffmpegVideoEnabled
        ) {
            if (!ffmpegVideoEnabled) {
                Log.i(TAG, "FfmpegVideoRenderer disabled; hardware-only video path.")
            }
            return
        }

        var extensionRendererIndex = out.size
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER) {
            extensionRendererIndex--
        }

        try {
            val renderer = FfmpegVideoRenderer(allowedVideoJoiningTimeMs, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
            out.add(extensionRendererIndex++, renderer)
            Log.i(TAG, "Loaded FfmpegVideoRenderer.")
        } catch (e: java.lang.Exception) {
            // The extension is present, but instantiation failed.
            throw java.lang.RuntimeException("Error instantiating Ffmpeg extension", e)
        }
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: java.util.ArrayList<Renderer>
    ) {
        out.add(NextTextRenderer(output, outputLooper))
    }

    companion object {
        const val TAG = "NextRenderersFactory"
    }
}
