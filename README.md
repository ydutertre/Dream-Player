# Dream Player

Dream Player is a clean-room media-player MVP for Play For Dream MR and other Android OpenXR headsets. It uses Android Media3 for hardware video/audio decoding and a native OpenGL ES OpenXR renderer for a stereoscopic world-space screen.

No code, assets, or proprietary behavior from SKYBOX, 4XVR, Dream Cinema, or another player are included.

## MVP features

- VR-native local video browser backed by Android MediaStore
- HTTP and HTTPS URLs
- In-VR SMB server discovery, login, share/folder browsing, and random-access streaming
- Folders-first, case-insensitive alphabetical sorting
- 2D, Full SBS, Half SBS, Full OU/TB, and Half OU/TB presentation
- Normal/swapped stereo eye order
- Embedded Media3 subtitles plus external SRT, WebVTT, SSA/ASS, and TTML selection
- Hardware decoding through Android `MediaCodec`, with FFmpeg audio fallback for TrueHD and other unsupported formats
- OpenXR stereo rendering on an OpenGL ES 3 swapchain
- Controller/media-key play/pause and 10-second seek

## Current MVP limitations

- The screen is a fixed flat world-space surface; resizing, recentering, curvature, passthrough, and environments are not implemented yet.
- Subtitle styling is simplified to centered white text with a black outline. Complex ASS positioning and bitmap subtitles are not preserved.
- SMB credentials are session-only by default. The optional **Remember credentials** checkbox encrypts them with an app-private Android Keystore key.
- DLNA, WebDAV, ISO/MVC, Dolby Vision processing, and custom HDR tone mapping are not implemented.
- The APK is designed around standard Android OpenXR. PFDM runtime-specific behavior must be verified on the headset.

## Build

Requirements:

- JDK 17
- Android SDK platform 36 and Build Tools 36.0.0
- Android NDK 28.2.13676358
- CMake 3.22.1
- Gradle 9.5

Create `local.properties` containing your SDK path, then run:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Install and inspect logs

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s DreamPlayer OpenXR-Loader MediaCodec CCodec ExoPlayerImpl
```

`PlayerActivity` enters OpenXR immediately and provides point-and-select library and playback menus in VR.

## Controls

- Controller A / D-pad center / media play-pause: toggle playback
- Controller X / D-pad up: cycle 2D, Full SBS, Half SBS, Full OU, and Half OU
- Controller Y / D-pad down: cycle Auto, Off, and every embedded/external subtitle track
- D-pad left/right: seek backward/forward 10 seconds
- Menu: show the in-VR playback controls
- With controls closed, point at the screen and hold/drag the trigger to reposition it; release to place it
- With controls closed, point at the screen and move the thumbstick up/down to resize it
- Controller B or system back: exit playback
- System back: exit playback

## Project structure

- `PlayerActivity.kt`: VR-native library, Media3 playback, lifecycle, subtitles, projection controls, and controller input
- `SmbRepository.kt`: SMB session and sorted directory listing
- `SmbDiscovery.kt`: active-LAN SMB server discovery
- `SmbShareBrowser.kt`: authenticated SMB2/3 share enumeration
- `SmbDataSource.kt`: seekable Media3 SMB data source
- `native_player.cpp`: Android OpenXR lifecycle and OpenGL ES renderer
