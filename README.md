# Dream Player

Dream Player is a VR media player developed and tested on **Play For Dream MR**.
Watch local, USB, and network videos on a movable, resizable screen, or play
immersive VR180 and VR360 videos. It supports SDR, HDR10/PQ, HLG, stereoscopic
3D, multiple audio tracks, and embedded or external subtitles.

The app uses Android Media3 with hardware and FFmpeg decoding, and a native
OpenGL ES/OpenXR renderer. It is independently developed and does not include
code or assets from SKYBOX, 4XVR, or Dream Cinema.

## Browse and play

- **Local Storage** for headset videos and a separate **USB Media** browser for
  connected drives, with an optional folder-access grant when needed.
- **SMB network storage:** server discovery, hostname display, share/folder
  browsing, and optional encrypted saved credentials with automatic login.
- **SFTP, FTP, and explicit FTPS:** saved connection profiles, in-VR browsing,
  seeking, network buffering, reconnection attempts, and playback error messages.
  SFTP verifies server host keys. Plain FTP is unencrypted.
- HTTP and HTTPS video URLs.
- Filename search within an opened folder/share, folders-first browsing, and
  ascending/descending sorting by name, date, duration, or recently played.
- Smooth joystick scrolling, a draggable scrollbar, and folder navigation that
  remembers the previous scroll position.
- **Recent files** restore their containing folder in the background, so Library
  returns there and previous/next video buttons have a folder to navigate.
- Previous/next video follows the browser ordering; the current filename appears
  in the playback toolbar.
- Per-file resume position and saved playback, picture, screen, and subtitle
  settings. Hostname-based SMB identities preserve these when a server's IP changes.

## Screen, 3D, and immersive video

- **2D, Full SBS, Half SBS, Full OU/TB, and Half OU/TB**, with eye swapping and
  filename-based layout guesses that can be overridden.
- Independent projection and stereo-layout settings: **Cinema, equirectangular
  VR180, and equirectangular VR360**, with mono or SBS/OU layouts.
- Move the cinema screen around the viewer, resize it, adjust curvature, and
  rotate the picture left/right by 90 degrees or reset its rotation.
- Immersive orientation/recentering controls and horizontal trigger-drag
  repositioning. The playback panel stays level and fixed in space; hiding and
  showing it places it in front of you again.
- A dark background with adjustable scene brightness, or **passthrough** with
  its own brightness control when supported by the headset runtime.
- Render resolution scale up to **1.6×**, trading rendering cost for sharper
  scene/UI rendering. It requires an app restart and does not add source-video detail.
- Optional **Meta glasses FOV simulator** in Settings → Environment: a shared
  head-relative 70° × 66° window. This approximates nominal field of view, not
  the target device's optics, resolution, or lens shape.

## Picture controls and HDR

Dream Player has its own SDR/HDR rendering and HDR-to-display tone mapping.
**Auto** selects the transfer function from video metadata; manual SDR, HDR/PQ,
and HLG overrides are available.

- Brightness, exposure, contrast, saturation, and vibrance controls.
- **Cinema tuning:** tone depth, midtone brightness, and color intensity. Tuning
  is saved globally, separately for SDR, HDR/PQ, and HLG.
- A static **HDR target** control up to 5,000 nits. This is a tone mapping
  parameter, not a measurement or setting of headset panel brightness.
- HDR10 metadata handling: Auto uses validated MaxCLL/mastering peak and MaxFALL
  where available. Ignore and Manual options handle unreliable/missing metadata;
  the source-peak fallback is 1,000 nits.
- BT.2020 gamut mapping with **Clip** (default) and **Perceptual** options.
- Draggable sliders and individual resets. Picture reset leaves the selected
  SDR/HDR/HLG mode and scene brightness alone.

### Optional adaptive HDR tone mapping

Adaptive HDR analyzes the video's brightness distribution and changes the
**effective HDR target** as the scene changes, reducing the need to retune
between dark and bright scenes.

Open the **Adaptive HDR** panel in playback settings:

| Control | What it does | Default |
| --- | --- | --- |
| On / Off | Enables scene analysis for HDR10/PQ | **Off** |
| Range minimum / maximum | Bounds the automatically selected target | **100–600 nits** |
| Tone priority | Biases high-contrast scenes toward shadow visibility or highlight protection | **Mid / 50%** |
| Adaptation | Chooses Smooth or Cinema response | **Smooth** |
| Effective target | Shows the value currently used by the renderer | Live readout |

The algorithm uses temporal smoothing within scenes and detects substantial
scene changes to adjust promptly. It analyzes current decoded frames:
**there is no lookahead**. It is global tone mapping, so it cannot adjust
different regions independently, and cut detection is not perfect for every video.

**Adaptive HDR currently works on HDR10/PQ only, not SDR or HLG.** When active,
it replaces the static HDR target internally without moving that slider or
overwriting its saved value. Adaptive settings are remembered globally.

For HDR10, a useful starting point is Decoder **Auto**, picture mode **Auto**,
and adaptive HDR enabled at **100–600 nits**, **Smooth**, and **Mid / 50%**.
Adjust to taste; this is not a calibrated preset for every headset or movie.

### Fresh-install HDR defaults

Saved preferences and per-file settings can override these defaults.

| Setting | Default |
| --- | --- |
| Decoder / picture mode | Auto / Auto |
| Adaptive HDR | Off; range 100–600 nits, Smooth, Mid (50%) |
| Static HDR target | 100 nits |
| Brightness / HDR exposure | 0 / 0 EV |
| Contrast / saturation / vibrance | 1.00 / 1.00 / 0 |
| HDR/PQ Cinema tuning | Tone depth 0%, midtones +0.15 EV, color intensity +10% |
| HDR metadata / gamut mapping | Auto / Clip |

## Audio and subtitles

- Scrollable audio/subtitle selectors, including files with many tracks.
- Separate audio-sync and subtitle-sync adjustments, remembered per file.
- Hardware/software decoder selection and automatic fallback for supported
  failure cases, FFmpeg audio decoding, DTS handling, and stereo downmixing.
- Embedded text and bitmap subtitles, including **Blu-ray PGS/SUP** subtitles.
- Automatic external **SRT** discovery: matching video basename in the same
  folder, plus SRT files in a direct child folder named **Subs**, **Subtitle**,
  or **Subtitles** (case-insensitive). Subtitles are listed by filename.
  Discovery covers local/USB and SMB browsing, not FTP/SFTP/FTPS.
- Text subtitle color, brightness, size, and horizontal/vertical placement, with
  a preview. Image-based subtitles cannot use these text-style controls.
- Adjustable subtitle depth, closer than the screen by default in 3D modes;
  subtitles follow screen curvature.
- Optional, per-file **3D subtitle** toggle for subtitles with a baked-in SBS/OU
  pair. It extracts one eye's subtitle view and displays it as an ordinary
  subtitle to avoid baked stereo disparity. Off is the default.

## Controls

- Point and trigger-click to select buttons and open settings popovers.
- Click the movie image to show/hide the playback toolbar.
- Point at the cinema screen and use joystick **up/down** to resize it, including
  when playback controls are visible.
- Point at the screen and use joystick **left/right** to seek by 10 seconds.
- Trigger-hold and drag the screen to reposition it. In VR180/360 this changes
  horizontal orientation.
- Point at a file/track list and use joystick **up/down** to scroll.
- Trigger-hold slider handles to drag, or click a position on the slider.
- The toolbar includes play/pause, ±10 seconds, previous/next video, and Library.

Controller A / D-pad centre / media play-pause toggles playback. D-pad left/right
seeks by 10 seconds; X / D-pad up cycles stereo layout; Y / D-pad down cycles
subtitles. Menu shows controls; B or system Back leaves playback. Physical
button availability/mapping depends on the runtime and controller.

## Current limitations

- **Play For Dream MR is the tested platform.** Other Android OpenXR headsets
  may require platform-specific work; compatibility is not guaranteed.
- **No Blu-ray ISO/disc menus or MVC 3D decoding.** MakeMKV MVC tracks are not
  supported as stereoscopic MVC video; use a compatible SBS/OU conversion for 3D.
- **No custom Dolby Vision metadata or enhancement-layer processing.** An
  HDR10-compatible Dolby Vision base layer may play through the HDR10 pipeline.
  Dolby Vision-only files are not guaranteed to decode/display correctly.
  Adaptive HDR is Dream Player's own analysis, not Dolby Vision metadata playback.
- Immersive video supports equirectangular 180/360; other lens formats such as
  fisheye are not implemented.
- Subtitle rendering is simplified: full ASS typesetting, animation, and source
  styling are not reproduced. Bitmap support depends on the subtitle decoder;
  text customization does not recolor or resize bitmap glyphs.
- Local/USB visibility depends on Android permissions, filesystem support, and
  media indexing or folder access. Network playback needs adequate bandwidth;
  reconnect attempts cannot overcome a permanently unavailable server.
- DLNA and WebDAV browsing are not implemented. There is no virtual cinema-room
  environment; available environments are the dark background and passthrough.

## Build and install

Requirements:

- JDK 17
- Android SDK platform 36 and Build Tools 36.0.0
- Android NDK 28.2.13676358
- CMake 3.22.1
- Gradle 9.5 (wrapper included)

Create `local.properties` with your SDK path, then run:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s DreamPlayer DreamPlayerColor OpenXR-Loader MediaCodec CCodec ExoPlayerImpl
```

The app enters OpenXR directly and provides point-and-select menus in VR.
Signed release builds use a local `release-signing/keystore.properties` file;
keep signing keys, passwords, and `local.properties` out of version control.

## Project structure

- `PlayerActivity.kt`: browsing, playback, settings, subtitles, and VR menu UI.
- `native_player.cpp`: OpenXR lifecycle, rendering, stereo/immersive projection,
  screen interaction, and tone mapping.
- `SmbDiscovery.kt`, `SmbShareBrowser.kt`, `SmbRepository.kt`, `SmbDataSource.kt`:
  SMB discovery, browsing, and playback.
- `SftpRepository.kt`, `FtpRepository.kt`, `RemoteMediaDataSource.kt`: remote
  browsing and seekable playback with reconnection handling.
- `LocalVideoRepository.kt`, `UsbStorageRepository.kt`: local and USB browsing.
- `ExternalSubtitleRepository.kt`: external SRT discovery.
- `MediaStateStore.kt`: per-file playback state/preferences.
- `AudioSyncAudioProcessor.kt`, `StereoDownmixAudioProcessor.kt`: audio timing
  and stereo downmixing.

## Licensing

See [LICENSE](LICENSE) for the project's own code and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for included components.
The codec-enabled build includes GPL-licensed `nextlib-media3ext`/FFmpeg-related
components; the project's MIT license does not override third-party obligations.
Review those terms before redistributing binaries.
