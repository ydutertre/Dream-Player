# Edge264 Android MVC proof of concept

This proof remains isolated from Dream Player's live Media3 renderer. Its purpose
is to establish that Edge264 can compile for the PfD headset and decode both
views from an untouched MakeMKV MVC stream before playback integration begins.

## Pinned source

- Project: `tvlabs/edge264`
- Commit: `4a193012adea0ad2f7436bfbaf8ecda2bc66093a`
- License: BSD-3-Clause (`third_party/edge264/LICENSE_BSD.txt`)
- Target: Android API 29, `arm64-v8a`, ARM NEON

The source was compiled with Android NDK 28.2.13676358. The resulting isolated
shared library is:

`build/edge264-android/arm64-v8a/libedge264.so`

Reproduce the Android library and test executable:

```powershell
.\tools\build_edge264_android.ps1 `
  -NdkRoot C:\path\to\Android\Sdk\ndk\28.2.13676358
```

## Android portability limitation

Upstream Edge264 uses `pthread_cancel()`, which Android's Bionic libc does not
provide. The vendored proof build therefore rejects any `edge264_alloc()` call
whose thread count is not zero. It does not substitute a no-op cancellation
function, because that could free decoder state while worker threads still use
it.

This is suitable for correctness testing. Live playback will need either:

1. a cooperative upstream-compatible worker shutdown implementation; or
2. a deliberately single-threaded renderer, if performance testing proves that
   sufficient.

## Trusted MakeMKV fixture

`tools/MkvMvcProbe.java` reads the original Matroska structure directly and can
write a bounded Annex-B fixture without remuxing:

```powershell
javac -d build\tools tools\MkvMvcProbe.java
java -cp build\tools MkvMvcProbe `
  "C:\path\to\original-makemkv-file.mkv" `
  "build\edge264-android\avatar-mvc-240-blocks.264" 240
```

For the tested file, extraction produced:

- 2 AVC (`avcC`) parameter sets
- 4 MVC (`mvcC`) parameter sets
- 240 original video access units
- 3,694 Annex-B NAL units
- MVC NAL type 20 in the first access unit

## PfD headset result

The offline executable was run directly on the headset CPU:

```text
FIRST_MVC_PAIR width=1920 height=1080 baseFrameId=214 mvcFrameId=215
baseHash=ac0a9be4b6e30f79 mvcHash=85716fa52052f415 distinct=true

RESULT nals=3694 frames=240 mvcFrames=240 distinctMvcFrames=133
warnings=0 elapsedMs=3524.997 pairImages=true
```

This establishes:

- Edge264 runs as native Android arm64 code on the PfD headset.
- All 240 access units produced paired MVC output frames.
- 133 pairs contained different left/right luma images.
- The decoder reported no unsupported-stream or malformed-stream warnings.
- Single-thread decoding ran faster than the fixture's approximate presentation
  duration, although this short correctness test is not a sustained performance
  benchmark.

The first distinct decoded luma planes are retained under
`build/edge264-android/` as PNG and PGM files for visual inspection.
