# Architecture

```text
Local SAF / HTTP / SMB
          |
          v
 Media3 + MediaCodec/FFmpeg ---> stereo downmix ---> Android audio output
          |
          v
 SurfaceTexture (external-OES texture)
          |
          v
 OpenXR GLES renderer
    |             |
 left eye      right eye
 2D/Full/Half SBS/OU UV selection
```

Media3 owns demuxing, codec selection, audio synchronization, embedded subtitle parsing, and seeking. Android hardware codecs remain preferred; the FFmpeg extension supplies unsupported audio formats such as TrueHD. Multichannel decoded PCM is downmixed to a headset-safe stereo stream. The renderer owns XR lifecycle, pose/view projection, stereo UV selection, the video screen, controller pointing, and a simplified subtitle/menu texture.

Keeping the decoder outside the XR compatibility layer is intentional. It reduces the risk of the process-wide media teardown behavior observed in the ported SKYBOX build, while preserving Android hardware decoding and standard OpenXR portability.
