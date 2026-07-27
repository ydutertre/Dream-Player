package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;

/**
 * FFmpeg audio renderer that advertises support only for DTS-family tracks.
 *
 * <p>This can be placed ahead of Android's MediaCodec audio renderer without moving AAC, AC-3,
 * or other well-behaved hardware formats away from their normal decoder.
 */
@UnstableApi
public final class DtsOnlyFfmpegAudioRenderer extends FfmpegAudioRenderer {
  public DtsOnlyFfmpegAudioRenderer(
      @Nullable Handler eventHandler,
      @Nullable AudioRendererEventListener eventListener,
      AudioSink audioSink) {
    super(eventHandler, eventListener, audioSink);
  }

  @Override
  protected @C.FormatSupport int supportsFormatInternal(Format format) {
    String mimeType = format.sampleMimeType;
    if (!MimeTypes.AUDIO_DTS.equals(mimeType) && !MimeTypes.AUDIO_DTS_HD.equals(mimeType)) {
      return C.FORMAT_UNSUPPORTED_SUBTYPE;
    }
    return super.supportsFormatInternal(format);
  }
}
