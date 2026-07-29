package dev.dreamplayer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleDelayMilliseconds
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
class PlayerActivity : Activity(), Player.Listener {
    private var spec = PlaybackSpec("", "Library", StereoMode.MONO)
    private lateinit var player: ExoPlayer
    private lateinit var message: TextView
    private var nativeThread: Thread? = null
    @Volatile private var nativeStarted = false
    private var activityResumed = false
    private var resumePlaybackOnForeground = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentStereoMode = StereoMode.MONO
    private var currentProjection = VideoProjection.FLAT
    private var immersiveYawDegrees = 0f
    private var immersivePitchDegrees = 0f
    private var pictureMode = PictureMode.AUTO
    private var pictureBrightness = DEFAULT_PICTURE_BRIGHTNESS
    private var pictureExposure = DEFAULT_PICTURE_EXPOSURE
    private var pictureContrast = DEFAULT_PICTURE_CONTRAST
    private var pictureSaturation = DEFAULT_PICTURE_SATURATION
    private var pictureVibrance = DEFAULT_PICTURE_VIBRANCE
    private val cinemaTunings = mutableMapOf(
        VideoTransfer.SDR to defaultCinemaTuning(VideoTransfer.SDR),
        VideoTransfer.PQ to defaultCinemaTuning(VideoTransfer.PQ),
        VideoTransfer.HLG to defaultCinemaTuning(VideoTransfer.HLG),
    )
    private var sceneBrightness = DEFAULT_SCENE_BRIGHTNESS
    private var toneMapPeakNits = DEFAULT_TONE_MAP_PEAK_NITS
    private var adaptiveToneMapEnabled = false
    private var adaptiveToneMapMinNits = DEFAULT_ADAPTIVE_TONE_MAP_MIN_NITS
    private var adaptiveToneMapMaxNits = DEFAULT_ADAPTIVE_TONE_MAP_MAX_NITS
    private var adaptiveToneMapPriority = DEFAULT_ADAPTIVE_TONE_MAP_PRIORITY
    private var adaptiveToneMapMode = AdaptiveToneMapMode.SMOOTH
    private var renderResolutionScale = DEFAULT_RENDER_RESOLUTION_SCALE
    private var screenCurvature = DEFAULT_SCREEN_CURVATURE
    private var detectedVideoTransfer = VideoTransfer.SDR
    private var detectedVideoPrimaries = VideoPrimaries.BT709
    private var detectedHdrStaticMetadata = HdrStaticMetadata.NONE
    private var hdrMetadataMode = HdrMetadataMode.AUTO
    private var hdrManualPeakNits = DEFAULT_HDR_SOURCE_PEAK_NITS
    private var gamutMappingMode = GamutMappingMode.CLIP
    private var currentMediaHasSavedPictureSettings = false
    private var hdrDefaultExposureApplied = false
    private var audioSyncMs = 0
    private lateinit var audioSyncProcessor: AudioSyncAudioProcessor
    private var swapEyes = false
    private var lastVideoSize = VideoSize.UNKNOWN
    private var latestCues: List<Cue> = emptyList()
    private var subtitleChoices: List<SubtitleChoice> = listOf(
        SubtitleChoice("Auto", null, -1, SUBTITLE_AUTO),
        SubtitleChoice("Off", null, -1, SUBTITLE_OFF),
    )
    private var subtitleChoiceIndex = 0
    private var pendingSubtitleFingerprint: String? = null
    private var externalSubtitles: List<ExternalSubtitle> = emptyList()
    private var subtitleDiscoveryGeneration = 0
    private var subtitleSyncMs = 0
    private var subtitleDepthMeters = DEFAULT_2D_SUBTITLE_DEPTH_METERS
    private var subtitleDepthIsCustom = false
    private var subtitle3dEnabled = false
    private var audioChoices: List<AudioChoice> = listOf(
        AudioChoice("Auto", null, -1, AUDIO_AUTO),
    )
    private var audioChoiceIndex = 0
    private var pendingAudioFingerprint: String? = null
    private var osdGeneration = 0
    private var videoSurface: Surface? = null
    private var mediaLoaded = false
    private var playbackCompleted = false
    private var uiScreen = UiScreen.LIBRARY
    private var playbackPopover = PlaybackPopover.NONE
    private var uiVisible = true
    private var controlsAutoHideAtMs = 0L
    private var uiButtons: List<UiButton> = emptyList()
    private var hoveredButton: String? = null
    private var activeSlider: String? = null
    private var transientMessage: String? = null
    private val io = Executors.newSingleThreadExecutor()
    private var recentMedia: List<MediaStateStore.RecentMedia> = emptyList()
    private var browserMetadata: Map<String, MediaStateStore.BrowserMetadata> = emptyMap()
    private var currentMediaMarkedPlayed = false
    private var localVideos: List<LocalVideo> = emptyList()
    private var localFolder: String? = null
    private var localBrowserItems: List<LocalBrowserItem> = emptyList()
    private var browserSearchQuery = ""
    private var browserSearchDraft = ""
    private var browserSearchReturnScreen = UiScreen.LOCAL_FILES
    private var browserSortPopoverOpen = false
    private var browserSortField = BrowserSortField.NAME
    private var browserSortAscending = true
    private var browserScrollOffset = 0f
    private var browserJoystickInput = 0f
    private var browserScrollVelocity = 0f
    private var browserScrollFrameNanos = 0L
    @Volatile private var browserScrollMoving = false
    private val browserFramePending = AtomicBoolean(false)
    @Volatile private var latestBrowserFrameNanos = 0L
    private var browserScrollbarDragging = false
    private var browserScrollbarGrabOffset = 0f
    private var subtitleTrackScrollOffset = 0f
    private var audioTrackScrollOffset = 0f
    private var trackListJoystickInput = 0f
    private var trackListScrollVelocity = 0f
    private var trackListScrollFrameNanos = 0L
    @Volatile private var trackListScrollMoving = false
    private var trackListScrollbarDragging = false
    private var trackListScrollbarGrabOffset = 0f
    private var pointerUiX = 0f
    private var pointerUiY = 0f
    private var localFolderListScrollOffset = 0f
    private var smbServers: List<SmbServer> = emptyList()
    private var smbServerListScrollOffset = 0f
    private var smbCredentials = SmbCredentials("", "", "", "", "")
    private var smbDisplayHost = ""
    private var smbShares: List<String> = emptyList()
    private var smbEntries: List<SmbEntry> = emptyList()
    private var smbBrowserItems: List<SmbBrowserItem> = emptyList()
    private var smbPath = ""
    private var smbShareListScrollOffset = 0f
    private val smbNavigationStack = ArrayDeque<SmbNavigationState>()
    private var sftpProfiles: List<SftpProfile> = emptyList()
    private var sftpProfile = SftpProfile()
    private var sftpPortDraft = SftpProfile.DEFAULT_PORT.toString()
    private var sftpEntries: List<SftpEntry> = emptyList()
    private var sftpPath = "."
    private var sftpProfileListScrollOffset = 0f
    private val sftpNavigationStack = ArrayDeque<SftpNavigationState>()
    private var pendingSftpHostKey: SftpHostKeyException? = null
    private var menuBitmap: Bitmap? = null
    private var keyboardField = KeyboardField.HOST
    private var keyboardSymbols = false
    private var keyboardUppercase = false
    private lateinit var smbCredentialStore: SmbCredentialStore
    private lateinit var sftpProfileStore: SftpProfileStore
    private lateinit var mediaStateStore: MediaStateStore
    private var rememberSmbCredentials = false
    private var playbackBrowserReturn: BrowserReturnState? = null
    private var decoderMode = DecoderMode.AUTO
    private var effectiveDecoderMode = DecoderMode.HARDWARE
    private var healthLastElapsedMs = 0L
    private var healthLastPositionMs = 0L
    private var healthAnchorElapsedMs = 0L
    private var healthAnchorPositionMs = 0L
    private var abnormalClockSamples = 0
    private var decoderFallbackPending = false
    private var remoteRecoveryAttempts = 0
    private var remotePlaybackStableAtMs = 0L
    private var networkStatusMessage: String? = null
    private var mediaLoadGeneration = 0
    private val stateSaver = object : Runnable {
        override fun run() {
            if (mediaLoaded) {
                saveCurrentMediaState()
                mainHandler.postDelayed(this, STATE_SAVE_INTERVAL_MS)
            }
        }
    }
    private val uiTicker = object : Runnable {
        override fun run() {
            if (uiVisible && uiScreen == UiScreen.PLAYBACK && mediaLoaded) {
                val now = SystemClock.elapsedRealtime()
                val interactionActive =
                    playbackPopover != PlaybackPopover.NONE ||
                        activeSlider != null ||
                        browserScrollMoving ||
                        trackListScrollMoving ||
                        browserScrollbarDragging ||
                        trackListScrollbarDragging
                if (
                    controlsAutoHideAtMs > 0L &&
                    now >= controlsAutoHideAtMs &&
                    player.isPlaying &&
                    !interactionActive
                ) {
                    hideControls()
                    return
                }
                if (interactionActive && now >= controlsAutoHideAtMs) {
                    armControlsAutoHide()
                }
                renderVrMenu()
                mainHandler.postDelayed(this, 1_000L)
            }
        }
    }
    private val playbackHealthMonitor = object : Runnable {
        override fun run() {
            if (!mediaLoaded) return
            if (
                decoderMode == DecoderMode.AUTO &&
                effectiveDecoderMode == DecoderMode.HARDWARE &&
                player.isPlaying &&
                player.playbackParameters.speed == 1f
            ) {
                val elapsedMs = SystemClock.elapsedRealtime()
                val positionMs = player.currentPosition
                if (healthLastElapsedMs == 0L) {
                    healthAnchorElapsedMs = elapsedMs
                    healthAnchorPositionMs = positionMs
                } else {
                    val wallDelta = elapsedMs - healthLastElapsedMs
                    val mediaDelta = positionMs - healthLastPositionMs
                    if (wallDelta in 700L..1_400L && mediaDelta >= 0L) {
                        val ratio = mediaDelta.toDouble() / wallDelta
                        abnormalClockSamples =
                            if (ratio in AUTO_FALLBACK_RATIO_MIN..AUTO_FALLBACK_RATIO_MAX) {
                                abnormalClockSamples + 1
                            } else {
                                0
                            }
                        if (abnormalClockSamples >= AUTO_FALLBACK_SAMPLE_COUNT) {
                            val correctedPosition = (
                                healthAnchorPositionMs + elapsedMs - healthAnchorElapsedMs
                            ).coerceIn(0L, positionMs)
                            Log.w(
                                "DreamPlayerDecoder",
                                "Abnormal hardware clock detected; retrying with software video " +
                                    "at ${formatTime(correctedPosition)}",
                            )
                            scheduleSoftwareDecoderFallback(
                                correctedPosition,
                                "Hardware timing issue detected",
                            )
                            return
                        }
                    } else {
                        abnormalClockSamples = 0
                        healthAnchorElapsedMs = elapsedMs
                        healthAnchorPositionMs = positionMs
                    }
                }
                healthLastElapsedMs = elapsedMs
                healthLastPositionMs = positionMs
            } else {
                resetPlaybackHealthMonitor()
            }
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    private enum class UiScreen {
        LIBRARY, LOCAL_FOLDERS, LOCAL_FILES,
        SMB_SERVERS, SMB_LOGIN, SMB_SHARES, SMB_FILES, SMB_KEYBOARD,
        SFTP_PROFILES, SFTP_LOGIN, SFTP_TRUST, FTP_WARNING,
        SFTP_FILES, SFTP_KEYBOARD,
        SFTP_DELETE_CONFIRM,
        FILE_SEARCH_KEYBOARD,
        PLAYBACK, PROJECTION, PICTURE, DECODER, AUDIO_SYNC, AUDIO_TRACKS, SUBTITLES,
    }

    private enum class PlaybackPopover {
        NONE, SUBTITLES, AUDIO, PROJECTION, SETTINGS, CINEMA, COLOR_PIPELINE,
        ADAPTIVE_HDR, RENDER_QUALITY,
    }

    private enum class PictureMode(val wireValue: Int) {
        ORIGINAL(0),
        AUTO(1),
        FORCE_SDR(2),
        FORCE_PQ(3),
        FORCE_HLG(4);

        companion object {
            fun fromWire(value: Int): PictureMode =
                entries.firstOrNull { it.wireValue == value } ?: AUTO
        }
    }

    private enum class DecoderMode(val wireValue: Int, val label: String) {
        AUTO(0, "Auto"),
        HARDWARE(1, "Hardware"),
        SOFTWARE(2, "Software");

        companion object {
            fun fromWire(value: Int): DecoderMode =
                entries.firstOrNull { it.wireValue == value } ?: AUTO
        }
    }

    private enum class VideoTransfer(val wireValue: Int, val label: String) {
        SDR(0, "SDR"),
        PQ(1, "HDR10 / PQ"),
        HLG(2, "HDR / HLG"),
    }

    private data class CinemaTuning(
        var toneDepth: Float,
        var midtoneEv: Float,
        var colorIntensity: Float,
    )

    private enum class VideoPrimaries(val wireValue: Int) {
        BT709(0),
        BT2020(1),
    }

    private enum class HdrMetadataMode(val wireValue: Int, val label: String) {
        AUTO(0, "Auto"),
        IGNORE(1, "Ignore"),
        MANUAL(2, "Manual");

        companion object {
            fun fromWire(value: Int): HdrMetadataMode =
                entries.firstOrNull { it.wireValue == value } ?: AUTO
        }
    }

    private enum class GamutMappingMode(val wireValue: Int, val label: String) {
        CLIP(0, "Clip"),
        PERCEPTUAL(1, "Perceptual");

        companion object {
            fun fromWire(value: Int): GamutMappingMode =
                entries.firstOrNull { it.wireValue == value } ?: CLIP
        }
    }

    private enum class AdaptiveToneMapMode(val wireValue: Int, val label: String) {
        CINEMA(0, "Cinema"),
        SMOOTH(1, "Smooth");

        companion object {
            fun fromWire(value: Int): AdaptiveToneMapMode =
                entries.firstOrNull { it.wireValue == value } ?: SMOOTH
        }
    }

    private data class HdrStaticMetadata(
        val masteringPeakNits: Float?,
        val maxCllNits: Float?,
        val maxFallNits: Float?,
        val status: String,
    ) {
        companion object {
            val NONE = HdrStaticMetadata(null, null, null, "No usable static metadata")
        }
    }

    private enum class KeyboardField {
        HOST, USERNAME, PASSWORD, DOMAIN, SEARCH,
        SFTP_NAME, SFTP_HOST, SFTP_PORT, SFTP_USERNAME, SFTP_PASSWORD,
        SFTP_PATH,
    }

    private enum class BrowserSortField(val label: String) {
        NAME("Name"),
        DATE("Date"),
        DURATION("Duration"),
        RECENT("Recently played"),
    }

    private sealed interface BrowserReturnState {
        data class Local(
            val folder: String,
            val scrollOffset: Float,
            val searchQuery: String,
        ) : BrowserReturnState

        data class Smb(
            val credentials: SmbCredentials,
            val path: String,
            val entries: List<SmbEntry>,
            val scrollOffset: Float,
            val searchQuery: String,
        ) : BrowserReturnState

        data class Remote(
            val profile: SftpProfile,
            val path: String,
            val entries: List<SftpEntry>,
            val scrollOffset: Float,
        ) : BrowserReturnState
    }

    private data class SmbNavigationState(
        val path: String,
        val entries: List<SmbEntry>,
        val scrollOffset: Float,
        val searchQuery: String,
    )

    private data class LocalBrowserItem(
        val sourceIndex: Int,
        val video: LocalVideo,
        val metadata: MediaStateStore.BrowserMetadata?,
    )

    private data class SmbBrowserItem(
        val sourceIndex: Int,
        val entry: SmbEntry,
        val metadata: MediaStateStore.BrowserMetadata?,
    )

    private data class UiButton(
        val id: String,
        val label: String,
        val bounds: RectF,
        val selected: Boolean = false,
    )

    private data class SubtitleChoice(
        val label: String,
        val group: Tracks.Group?,
        val trackIndex: Int,
        val fingerprint: String,
    )

    private data class AudioChoice(
        val label: String,
        val group: Tracks.Group?,
        val trackIndex: Int,
        val fingerprint: String,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        smbCredentialStore = SmbCredentialStore(this)
        sftpProfileStore = SftpProfileStore(this)
        sftpProfiles = sftpProfileStore.load()
        mediaStateStore = MediaStateStore(this)
        sceneBrightness = getSharedPreferences(PICTURE_PREFS, Context.MODE_PRIVATE)
            .getFloat(PREF_SCENE_BRIGHTNESS, DEFAULT_SCENE_BRIGHTNESS)
            .sanitized(SCENE_BRIGHTNESS_MIN, SCENE_BRIGHTNESS_MAX, DEFAULT_SCENE_BRIGHTNESS)
        toneMapPeakNits = getSharedPreferences(PICTURE_PREFS, Context.MODE_PRIVATE)
            .getFloat(PREF_TONE_MAP_PEAK_NITS, DEFAULT_TONE_MAP_PEAK_NITS)
            .sanitized(TONE_MAP_PEAK_NITS_MIN, TONE_MAP_PEAK_NITS_MAX, DEFAULT_TONE_MAP_PEAK_NITS)
        val picturePreferences = getSharedPreferences(PICTURE_PREFS, Context.MODE_PRIVATE)
        adaptiveToneMapEnabled =
            picturePreferences.getBoolean(PREF_ADAPTIVE_TONE_MAP_ENABLED, false)
        adaptiveToneMapMinNits = picturePreferences
            .getFloat(
                PREF_ADAPTIVE_TONE_MAP_MIN_NITS,
                DEFAULT_ADAPTIVE_TONE_MAP_MIN_NITS,
            )
            .sanitized(
                ADAPTIVE_TONE_MAP_NITS_MIN,
                ADAPTIVE_TONE_MAP_NITS_MAX,
                DEFAULT_ADAPTIVE_TONE_MAP_MIN_NITS,
            )
        adaptiveToneMapMaxNits = picturePreferences
            .getFloat(
                PREF_ADAPTIVE_TONE_MAP_MAX_NITS,
                DEFAULT_ADAPTIVE_TONE_MAP_MAX_NITS,
            )
        adaptiveToneMapPriority = picturePreferences
            .getFloat(
                PREF_ADAPTIVE_TONE_MAP_PRIORITY,
                DEFAULT_ADAPTIVE_TONE_MAP_PRIORITY,
            )
            .sanitized(
                ADAPTIVE_TONE_MAP_PRIORITY_MIN,
                ADAPTIVE_TONE_MAP_PRIORITY_MAX,
                DEFAULT_ADAPTIVE_TONE_MAP_PRIORITY,
            )
        if (
            picturePreferences.getInt(PREF_ADAPTIVE_DEFAULT_RANGE_VERSION, 0) < 1
        ) {
            val migration = picturePreferences.edit()
                .putInt(PREF_ADAPTIVE_DEFAULT_RANGE_VERSION, 1)
            // Migrate only the original experimental default. Any range the
            // user actually changed remains untouched.
            if (
                kotlin.math.abs(adaptiveToneMapMinNits - 300f) < 0.5f &&
                kotlin.math.abs(adaptiveToneMapMaxNits - 500f) < 0.5f
            ) {
                adaptiveToneMapMinNits = DEFAULT_ADAPTIVE_TONE_MAP_MIN_NITS
                adaptiveToneMapMaxNits = DEFAULT_ADAPTIVE_TONE_MAP_MAX_NITS
                migration
                    .putFloat(
                        PREF_ADAPTIVE_TONE_MAP_MIN_NITS,
                        adaptiveToneMapMinNits,
                    )
                    .putFloat(
                        PREF_ADAPTIVE_TONE_MAP_MAX_NITS,
                        adaptiveToneMapMaxNits,
                    )
            }
            migration.apply()
        }
        if (adaptiveToneMapMinNits > adaptiveToneMapMaxNits) {
            adaptiveToneMapMinNits = DEFAULT_ADAPTIVE_TONE_MAP_MIN_NITS
            adaptiveToneMapMaxNits = DEFAULT_ADAPTIVE_TONE_MAP_MAX_NITS
        }
        adaptiveToneMapMode = AdaptiveToneMapMode.fromWire(
            picturePreferences.getInt(
                PREF_ADAPTIVE_TONE_MAP_MODE,
                AdaptiveToneMapMode.SMOOTH.wireValue,
            )
        )
        applyAdaptiveToneMapping(persist = false)
        renderResolutionScale = picturePreferences
            .getFloat(PREF_RENDER_RESOLUTION_SCALE, DEFAULT_RENDER_RESOLUTION_SCALE)
            .sanitized(
                RENDER_RESOLUTION_SCALE_MIN,
                RENDER_RESOLUTION_SCALE_MAX,
                DEFAULT_RENDER_RESOLUTION_SCALE,
            )
        gamutMappingMode = GamutMappingMode.fromWire(
            picturePreferences.getInt(
                PREF_GAMUT_MAPPING_MODE,
                GamutMappingMode.CLIP.wireValue,
            )
        )
        loadCinemaTunings(picturePreferences)
        applyCinemaSettings(persist = false)
        val browserPreferences = getSharedPreferences(BROWSER_PREFS, Context.MODE_PRIVATE)
        browserSortField = BrowserSortField.entries.getOrElse(
            browserPreferences.getInt(PREF_BROWSER_SORT_FIELD, BrowserSortField.NAME.ordinal)
        ) { BrowserSortField.NAME }
        browserSortAscending =
            browserPreferences.getBoolean(PREF_BROWSER_SORT_ASCENDING, true)
        browserMetadata = mediaStateStore.listBrowserMetadata()
        smbCredentialStore.load()?.let {
            smbCredentials = it
            smbDisplayHost = it.host
            rememberSmbCredentials = true
        }
        spec = playbackSpecFromIntent(intent)
            ?: PlaybackSpec("", "Library", StereoMode.MONO)
        currentStereoMode = spec.stereoMode
        hideSystemUi()
        message = TextView(this).apply {
            text = "Starting OpenXR…"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }
        setContentView(FrameLayout(this).apply { addView(message) })

        nativeThread = Thread({
            nativeStarted = true
            nativeRun(this, spec.stereoMode.wireValue, renderResolutionScale)
            nativeStarted = false
        }, "DreamPlayer-OpenXR").also { it.start() }

        if (spec.uri.isNotBlank()) loadMedia(spec)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newSpec = playbackSpecFromIntent(intent) ?: return
        if (newSpec.uri.isBlank() || isFinishing || isDestroyed) return
        resumePlaybackOnForeground = true
        loadMedia(newSpec)
    }

    private fun playbackSpecFromIntent(source: Intent): PlaybackSpec? =
        runCatching { PlaybackSpec.from(source) }.getOrNull()
            ?: source.data?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let { uri ->
                    PlaybackSpec(
                        uri,
                        Uri.parse(uri).lastPathSegment ?: "Video",
                        StereoMode.MONO,
                    )
                }

    @Suppress("unused") // Called from JNI after the GL external texture is ready.
    fun onNativeVideoSurface(surface: Surface) {
        runOnUiThread {
            videoSurface = surface
            if (mediaLoaded) player.setVideoSurface(surface)
            message.visibility = View.GONE
            if (mediaLoaded) showControls(8_000L) else showLibrary()
        }
    }

    @Suppress("unused") // Called from the OpenXR render thread.
    fun onNativePointer(u: Float, v: Float, pointerState: Int) {
        runOnUiThread {
            val pressed = pointerState == POINTER_PRESSED
            val held = pointerState == POINTER_HELD
            val released = pointerState == POINTER_RELEASED
            if (!uiVisible) {
                if (pressed && mediaLoaded) showControls(0L)
                return@runOnUiThread
            }
            val px = u * UI_WIDTH
            val py = v * UI_HEIGHT
            pointerUiX = px
            pointerUiY = py
            val rawTarget = uiButtons.lastOrNull { it.bounds.contains(px, py) }
            val target = if (
                browserSortPopoverOpen &&
                BROWSER_SORT_POPOVER_BOUNDS.contains(px, py) &&
                rawTarget?.id?.let(::isBrowserSortControl) != true
            ) {
                null
            } else {
                rawTarget
            }
            val hoverTarget = when {
                browserSortPopoverOpen &&
                    target?.id?.let(::isBrowserSortControl) != true -> null
                target?.id == BROWSER_SCROLLBAR_ID ||
                    target?.id == TRACK_LIST_SCROLLBAR_ID -> target
                isBrowserListScreen() && browserScrollMoving -> null
                trackListScrollMoving && isTrackListButton(target?.id) -> null
                else -> target
            }
            if (activeSlider == null && hoveredButton != hoverTarget?.id) {
                hoveredButton = hoverTarget?.id
                if (uiScreen == UiScreen.PLAYBACK) armControlsAutoHide()
                Log.i("DreamPlayerUI", "Hover: ${hoverTarget?.id ?: "none"}")
                renderVrMenu()
            }
            if (pressed) {
                if (uiScreen == UiScreen.PLAYBACK) armControlsAutoHide()
                if (
                    browserSortPopoverOpen &&
                    !BROWSER_SORT_POPOVER_BOUNDS.contains(px, py) &&
                    target?.id != "browser_sort"
                ) {
                    browserSortPopoverOpen = false
                    hoveredButton = null
                    renderVrMenu()
                    return@runOnUiThread
                }
                if (target != null) {
                    Log.i("DreamPlayerUI", "Click: ${target.id}")
                    if (target.id == BROWSER_SCROLLBAR_ID) {
                        stopBrowserScroll()
                        activeSlider = target.id
                        browserScrollbarDragging = true
                        browserScrollbarGrabOffset =
                            py - browserScrollbarThumbBounds().centerY()
                        handleBrowserScrollbarDrag(py)
                    } else if (target.id == TRACK_LIST_SCROLLBAR_ID) {
                        stopTrackListScroll()
                        activeSlider = target.id
                        trackListScrollbarDragging = true
                        trackListScrollbarGrabOffset =
                            py - trackListScrollbarThumbBounds().centerY()
                        handleTrackListScrollbarDrag(py)
                    } else if (isSlider(target.id)) {
                        activeSlider = target.id
                        handleUiAction(target.id, px, persist = false)
                    } else {
                        handleUiAction(target.id, px)
                    }
                } else if (mediaLoaded && uiScreen == UiScreen.PLAYBACK) {
                    if (playbackPopover != PlaybackPopover.NONE) {
                        val popoverBounds = playbackPopoverBounds(playbackPopover)
                        if (popoverBounds == null || !popoverBounds.contains(px, py)) {
                            stopTrackListScroll()
                            playbackPopover = PlaybackPopover.NONE
                            hoveredButton = null
                            armControlsAutoHide()
                            renderVrMenu()
                        }
                    } else if (!PLAYBACK_TOOLBAR_BOUNDS.contains(px, py)) {
                        hideControls()
                    }
                }
            } else if (held) {
                activeSlider?.let {
                    if (uiScreen == UiScreen.PLAYBACK) armControlsAutoHide()
                    if (it == BROWSER_SCROLLBAR_ID) handleBrowserScrollbarDrag(py)
                    else if (it == TRACK_LIST_SCROLLBAR_ID) handleTrackListScrollbarDrag(py)
                    else handleUiAction(it, px, persist = false)
                }
            } else if (released) {
                activeSlider?.let { slider ->
                    if (uiScreen == UiScreen.PLAYBACK) armControlsAutoHide()
                    if (slider == BROWSER_SCROLLBAR_ID) {
                        handleBrowserScrollbarDrag(py)
                        browserScrollbarDragging = false
                    } else if (slider == TRACK_LIST_SCROLLBAR_ID) {
                        handleTrackListScrollbarDrag(py)
                        trackListScrollbarDragging = false
                    } else {
                        handleUiAction(slider, px, persist = true)
                    }
                    activeSlider = null
                    hoveredButton = if (slider == BROWSER_SCROLLBAR_ID) slider else target?.id
                    renderVrMenu()
                }
            }
        }
    }

    @Suppress("unused") // Called from the OpenXR render thread.
    fun onNativeScroll(amount: Float) {
        runOnUiThread {
            if (isTrackListPointed()) {
                stopBrowserScroll()
                if (trackListScrollbarDragging) return@runOnUiThread
                val nextInput =
                    if (kotlin.math.abs(amount) >= BROWSER_JOYSTICK_DEAD_ZONE) amount else 0f
                if (nextInput != 0f && !trackListScrollMoving) {
                    trackListScrollMoving = true
                    hoveredButton = null
                    renderVrMenu()
                }
                if (nextInput != 0f) armControlsAutoHide()
                trackListJoystickInput = nextInput
                return@runOnUiThread
            }
            if (trackListScrollMoving) {
                trackListJoystickInput = 0f
            }
            if (!isBrowserListScreen()) {
                stopBrowserScroll()
                return@runOnUiThread
            }
            if (browserScrollbarDragging) return@runOnUiThread
            val nextInput = if (kotlin.math.abs(amount) >= BROWSER_JOYSTICK_DEAD_ZONE) amount else 0f
            if (nextInput != 0f && !browserScrollMoving) {
                browserScrollMoving = true
                hoveredButton = null
                renderVrMenu()
            }
            if (nextInput != 0f && uiScreen == UiScreen.PLAYBACK) armControlsAutoHide()
            browserJoystickInput = nextInput
        }
    }

    @Suppress("unused") // Called from the OpenXR render thread.
    fun onNativeSeekGesture(direction: Int, u: Float, v: Float) {
        runOnUiThread {
            if (
                direction == 0 ||
                !mediaLoaded ||
                !::player.isInitialized ||
                uiScreen != UiScreen.PLAYBACK
            ) {
                return@runOnUiThread
            }
            val px = u * UI_WIDTH
            val py = v * UI_HEIGHT
            if (uiVisible) {
                val overToolbar = PLAYBACK_TOOLBAR_BOUNDS.contains(px, py)
                val overPopover = playbackPopoverBounds(playbackPopover)?.contains(px, py) == true
                val overControl = uiButtons.any { it.bounds.contains(px, py) }
                if (overToolbar || overPopover || overControl) return@runOnUiThread
            }

            val oldPosition = player.currentPosition.coerceAtLeast(0L)
            val maximum = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
            val newPosition = if (direction < 0) {
                (oldPosition - JOYSTICK_SEEK_STEP_MS).coerceAtLeast(0L)
            } else {
                (oldPosition + JOYSTICK_SEEK_STEP_MS).coerceAtMost(maximum)
            }
            if (newPosition == oldPosition) return@runOnUiThread
            player.seekTo(newPosition)
            playbackCompleted = false
            showOsd(
                "${if (direction < 0) "−10 seconds" else "+10 seconds"}  •  " +
                    formatTime(newPosition),
                1_500L,
            )
        }
    }

    @Suppress("unused") // Called once per OpenXR display frame from the render thread.
    fun onNativeFrame(frameTimeNanos: Long) {
        latestBrowserFrameNanos = frameTimeNanos
        if (
            (!browserScrollMoving && !trackListScrollMoving) ||
            !browserFramePending.compareAndSet(false, true)
        ) return
        runOnUiThread {
            try {
                if (browserScrollMoving) updateBrowserScrollFrame(latestBrowserFrameNanos)
                if (trackListScrollMoving) updateTrackListScrollFrame(latestBrowserFrameNanos)
            } finally {
                browserFramePending.set(false)
            }
        }
    }

    private fun isBrowserListScreen(): Boolean = when (uiScreen) {
        UiScreen.LOCAL_FOLDERS,
        UiScreen.LOCAL_FILES,
        UiScreen.SMB_SERVERS,
        UiScreen.SMB_SHARES,
        UiScreen.SMB_FILES,
        UiScreen.SFTP_PROFILES,
        UiScreen.SFTP_FILES -> true
        else -> false
    }

    private fun refreshBrowserMetadata() {
        browserMetadata = mediaStateStore.listBrowserMetadata()
        rebuildBrowserItems()
    }

    private fun localPlaybackSpec(video: LocalVideo) =
        PlaybackSpec(video.uri.toString(), video.name, StereoMode.MONO)

    private fun smbPlaybackSpec(entry: SmbEntry): PlaybackSpec {
        val uri = Uri.Builder()
            .scheme("smb")
            .authority(smbCredentials.host)
            .apply {
                entry.path.split('\\').filter(String::isNotEmpty).forEach(::appendPath)
            }
            .build()
            .toString()
        return PlaybackSpec(uri, entry.name, StereoMode.MONO, smb = smbCredentials)
    }

    private fun remotePlaybackSpec(entry: SftpEntry): PlaybackSpec {
        val uri = Uri.Builder()
            .scheme(sftpProfile.protocol.name.lowercase(Locale.ROOT))
            .encodedAuthority("${sftpProfile.host}:${sftpProfile.port}")
            .path(entry.path)
            .build()
            .toString()
        return PlaybackSpec(
            uri,
            entry.name,
            StereoMode.MONO,
            remote = sftpProfile,
        )
    }

    private fun directedComparison(comparison: Int): Int =
        if (browserSortAscending) comparison else -comparison

    private fun compareNames(first: String, second: String): Int =
        directedComparison(String.CASE_INSENSITIVE_ORDER.compare(first, second))

    /** Unknown metadata remains at the bottom in both directions. */
    private fun compareKnownLongs(first: Long?, second: Long?): Int = when {
        first == null && second == null -> 0
        first == null -> 1
        second == null -> -1
        else -> directedComparison(first.compareTo(second))
    }

    private fun compareBrowserItems(
        firstName: String,
        secondName: String,
        firstDate: Long?,
        secondDate: Long?,
        firstDuration: Long?,
        secondDuration: Long?,
        firstRecent: Long?,
        secondRecent: Long?,
    ): Int {
        val primary = when (browserSortField) {
            BrowserSortField.NAME -> compareNames(firstName, secondName)
            BrowserSortField.DATE -> compareKnownLongs(firstDate, secondDate)
            BrowserSortField.DURATION -> compareKnownLongs(firstDuration, secondDuration)
            BrowserSortField.RECENT -> compareKnownLongs(firstRecent, secondRecent)
        }
        return if (primary != 0) primary else compareNames(firstName, secondName)
    }

    private fun rebuildBrowserItems() {
        localBrowserItems = buildVisibleLocalVideos()
        smbBrowserItems = buildVisibleSmbEntries()
    }

    private fun buildVisibleLocalVideos(): List<LocalBrowserItem> {
        val query = browserSearchQuery.trim()
        return localVideos.withIndex()
            .asSequence()
            .filter { it.value.folder == localFolder }
            .filter { query.isBlank() || it.value.name.contains(query, ignoreCase = true) }
            .map { indexed ->
                val metadata = browserMetadata[MediaIdentity.from(localPlaybackSpec(indexed.value)).id]
                LocalBrowserItem(indexed.index, indexed.value, metadata)
            }
            .sortedWith { first, second ->
                compareBrowserItems(
                    first.video.name,
                    second.video.name,
                    first.video.modifiedAtMs.takeIf { it > 0L },
                    second.video.modifiedAtMs.takeIf { it > 0L },
                    first.video.durationMs.takeIf { it > 0L } ?: first.metadata?.durationMs,
                    second.video.durationMs.takeIf { it > 0L } ?: second.metadata?.durationMs,
                    first.metadata?.lastPlayedAt,
                    second.metadata?.lastPlayedAt,
                )
            }
            .toList()
    }

    private fun buildVisibleSmbEntries(): List<SmbBrowserItem> {
        val query = browserSearchQuery.trim()
        return smbEntries.withIndex()
            .asSequence()
            .filter { query.isBlank() || it.value.name.contains(query, ignoreCase = true) }
            .map { indexed ->
                val metadata = if (indexed.value.isDirectory) {
                    null
                } else {
                    browserMetadata[MediaIdentity.from(smbPlaybackSpec(indexed.value)).id]
                }
                SmbBrowserItem(indexed.index, indexed.value, metadata)
            }
            .sortedWith { first, second ->
                when {
                    first.entry.isDirectory && !second.entry.isDirectory -> -1
                    !first.entry.isDirectory && second.entry.isDirectory -> 1
                    else -> compareBrowserItems(
                        first.entry.name,
                        second.entry.name,
                        first.entry.modifiedAtMs.takeIf { it > 0L },
                        second.entry.modifiedAtMs.takeIf { it > 0L },
                        first.metadata?.durationMs,
                        second.metadata?.durationMs,
                        first.metadata?.lastPlayedAt,
                        second.metadata?.lastPlayedAt,
                    )
                }
            }
            .toList()
    }

    private fun browserItemCount(): Int = when (uiScreen) {
        UiScreen.LOCAL_FOLDERS -> localVideos.map { it.folder }.distinct().size
        UiScreen.LOCAL_FILES -> localBrowserItems.size
        UiScreen.SMB_SERVERS -> smbServers.size
        UiScreen.SMB_SHARES -> smbShares.size
        UiScreen.SMB_FILES -> smbBrowserItems.size
        UiScreen.SFTP_PROFILES -> sftpProfiles.size
        UiScreen.SFTP_FILES -> sftpEntries.size
        else -> 0
    }

    private fun browserMaximumScroll(): Float =
        (browserItemCount() - BROWSER_VISIBLE_ROWS).coerceAtLeast(0).toFloat()

    private fun updateBrowserScrollFrame(frameTimeNanos: Long) {
        if (!isBrowserListScreen() || browserScrollbarDragging) {
            browserJoystickInput = 0f
            browserScrollVelocity = 0f
            browserScrollMoving = false
            browserScrollFrameNanos = 0L
            return
        }
        if (browserScrollFrameNanos == 0L) {
            browserScrollFrameNanos = frameTimeNanos
            return
        }
        val elapsedSeconds = (
            (frameTimeNanos - browserScrollFrameNanos) / 1_000_000_000f
        ).coerceIn(0.001f, 0.05f)
        browserScrollFrameNanos = frameTimeNanos

        if (browserJoystickInput != 0f) {
            val normalized = (
                (kotlin.math.abs(browserJoystickInput) - BROWSER_JOYSTICK_DEAD_ZONE) /
                    (1f - BROWSER_JOYSTICK_DEAD_ZONE)
            ).coerceIn(0f, 1f)
            val shapedInput = kotlin.math.sign(browserJoystickInput) * normalized * normalized
            val targetVelocity = -shapedInput * BROWSER_MAX_SCROLL_ROWS_PER_SECOND
            val response = 1f - kotlin.math.exp(-BROWSER_SCROLL_ACCELERATION * elapsedSeconds)
            browserScrollVelocity += (targetVelocity - browserScrollVelocity) * response
        } else {
            browserScrollVelocity *=
                kotlin.math.exp(-BROWSER_SCROLL_FRICTION * elapsedSeconds)
            if (kotlin.math.abs(browserScrollVelocity) < BROWSER_SCROLL_STOP_VELOCITY) {
                browserScrollVelocity = 0f
            }
        }

        val maximum = browserMaximumScroll()
        val previous = browserScrollOffset
        browserScrollOffset =
            (browserScrollOffset + browserScrollVelocity * elapsedSeconds).coerceIn(0f, maximum)
        if (
            browserScrollOffset == 0f && browserScrollVelocity < 0f ||
            browserScrollOffset == maximum && browserScrollVelocity > 0f
        ) {
            browserScrollVelocity = 0f
        }
        val stillMoving = browserJoystickInput != 0f || browserScrollVelocity != 0f
        browserScrollMoving = stillMoving
        if (browserScrollOffset != previous) {
            hoveredButton = null
            renderVrMenu()
        }
        if (!stillMoving) {
            browserScrollFrameNanos = 0L
        }
    }

    private fun stopBrowserScroll() {
        browserJoystickInput = 0f
        browserScrollVelocity = 0f
        browserScrollMoving = false
        browserScrollFrameNanos = 0L
        browserFramePending.set(false)
    }

    private fun browserScrollbarThumbBounds(): RectF {
        val maximum = browserMaximumScroll()
        val itemCount = browserItemCount()
        if (maximum <= 0f || itemCount <= 0) return RectF(BROWSER_SCROLLBAR_TRACK)
        val thumbHeight = (
            BROWSER_SCROLLBAR_TRACK.height() * BROWSER_VISIBLE_ROWS / itemCount.toFloat()
        ).coerceAtLeast(BROWSER_SCROLLBAR_MIN_THUMB_HEIGHT)
        val thumbTop = BROWSER_SCROLLBAR_TRACK.top +
            (BROWSER_SCROLLBAR_TRACK.height() - thumbHeight) *
            (browserScrollOffset.coerceIn(0f, maximum) / maximum)
        return RectF(
            BROWSER_SCROLLBAR_TRACK.left,
            thumbTop,
            BROWSER_SCROLLBAR_TRACK.right,
            thumbTop + thumbHeight,
        )
    }

    private fun handleBrowserScrollbarDrag(pointerY: Float) {
        val maximum = browserMaximumScroll()
        if (maximum <= 0f) return
        val thumb = browserScrollbarThumbBounds()
        val travel = BROWSER_SCROLLBAR_TRACK.height() - thumb.height()
        if (travel <= 0f) return
        val centerY = (
            pointerY - browserScrollbarGrabOffset
        ).coerceIn(
            BROWSER_SCROLLBAR_TRACK.top + thumb.height() / 2f,
            BROWSER_SCROLLBAR_TRACK.bottom - thumb.height() / 2f,
        )
        val fraction = (
            (centerY - thumb.height() / 2f - BROWSER_SCROLLBAR_TRACK.top) / travel
        ).coerceIn(0f, 1f)
        browserScrollOffset = maximum * fraction
        hoveredButton = BROWSER_SCROLLBAR_ID
        renderVrMenu()
    }

    private fun isTrackPopoverOpen(): Boolean =
        uiVisible &&
            uiScreen == UiScreen.PLAYBACK &&
            playbackPopover in setOf(PlaybackPopover.SUBTITLES, PlaybackPopover.AUDIO)

    private fun isTrackListPointed(): Boolean =
        isTrackPopoverOpen() && TRACK_LIST_POINTER_BOUNDS.contains(pointerUiX, pointerUiY)

    private fun isTrackListButton(id: String?): Boolean =
        id == TRACK_LIST_SCROLLBAR_ID ||
            id?.startsWith("subtitle_") == true && !id.startsWith("subtitle_sync_") ||
            id?.startsWith("audio_track_") == true

    private fun trackListItemCount(): Int = when (playbackPopover) {
        PlaybackPopover.SUBTITLES -> subtitleChoices.size
        PlaybackPopover.AUDIO -> audioChoices.size
        else -> 0
    }

    private fun trackListScrollOffset(): Float = when (playbackPopover) {
        PlaybackPopover.SUBTITLES -> subtitleTrackScrollOffset
        PlaybackPopover.AUDIO -> audioTrackScrollOffset
        else -> 0f
    }

    private fun setTrackListScrollOffset(value: Float) {
        val adjusted = value.coerceIn(0f, trackListMaximumScroll())
        when (playbackPopover) {
            PlaybackPopover.SUBTITLES -> subtitleTrackScrollOffset = adjusted
            PlaybackPopover.AUDIO -> audioTrackScrollOffset = adjusted
            else -> Unit
        }
    }

    private fun trackListMaximumScroll(): Float =
        (trackListItemCount() - TRACK_LIST_VISIBLE_ROWS).coerceAtLeast(0).toFloat()

    private fun ensureSelectedTrackVisible() {
        val selected = when (playbackPopover) {
            PlaybackPopover.SUBTITLES -> subtitleChoiceIndex
            PlaybackPopover.AUDIO -> audioChoiceIndex
            else -> return
        }
        val current = trackListScrollOffset()
        val next = when {
            selected < current -> selected.toFloat()
            selected >= current + TRACK_LIST_VISIBLE_ROWS ->
                (selected - TRACK_LIST_VISIBLE_ROWS + 1).toFloat()
            else -> current
        }
        setTrackListScrollOffset(next)
    }

    private fun updateTrackListScrollFrame(frameTimeNanos: Long) {
        if (!isTrackPopoverOpen() || trackListScrollbarDragging) {
            stopTrackListScroll()
            return
        }
        if (trackListScrollFrameNanos == 0L) {
            trackListScrollFrameNanos = frameTimeNanos
            return
        }
        val elapsedSeconds = (
            (frameTimeNanos - trackListScrollFrameNanos) / 1_000_000_000f
        ).coerceIn(0.001f, 0.05f)
        trackListScrollFrameNanos = frameTimeNanos

        if (trackListJoystickInput != 0f) {
            val normalized = (
                (kotlin.math.abs(trackListJoystickInput) - BROWSER_JOYSTICK_DEAD_ZONE) /
                    (1f - BROWSER_JOYSTICK_DEAD_ZONE)
            ).coerceIn(0f, 1f)
            val shapedInput =
                kotlin.math.sign(trackListJoystickInput) * normalized * normalized
            val targetVelocity = -shapedInput * TRACK_LIST_MAX_SCROLL_ROWS_PER_SECOND
            val response =
                1f - kotlin.math.exp(-BROWSER_SCROLL_ACCELERATION * elapsedSeconds)
            trackListScrollVelocity +=
                (targetVelocity - trackListScrollVelocity) * response
        } else {
            trackListScrollVelocity *=
                kotlin.math.exp(-BROWSER_SCROLL_FRICTION * elapsedSeconds)
            if (kotlin.math.abs(trackListScrollVelocity) < BROWSER_SCROLL_STOP_VELOCITY) {
                trackListScrollVelocity = 0f
            }
        }

        val maximum = trackListMaximumScroll()
        val previous = trackListScrollOffset()
        val next =
            (previous + trackListScrollVelocity * elapsedSeconds).coerceIn(0f, maximum)
        setTrackListScrollOffset(next)
        if (
            next == 0f && trackListScrollVelocity < 0f ||
            next == maximum && trackListScrollVelocity > 0f
        ) {
            trackListScrollVelocity = 0f
        }
        val stillMoving = trackListJoystickInput != 0f || trackListScrollVelocity != 0f
        trackListScrollMoving = stillMoving
        if (next != previous) {
            hoveredButton = null
            renderVrMenu()
        }
        if (!stillMoving) trackListScrollFrameNanos = 0L
    }

    private fun stopTrackListScroll() {
        trackListJoystickInput = 0f
        trackListScrollVelocity = 0f
        trackListScrollMoving = false
        trackListScrollFrameNanos = 0L
        browserFramePending.set(false)
    }

    private fun trackListScrollbarThumbBounds(): RectF {
        val maximum = trackListMaximumScroll()
        val itemCount = trackListItemCount()
        if (maximum <= 0f || itemCount <= 0) return RectF(TRACK_LIST_SCROLLBAR_TRACK)
        val thumbHeight = (
            TRACK_LIST_SCROLLBAR_TRACK.height() *
                TRACK_LIST_VISIBLE_ROWS / itemCount.toFloat()
        ).coerceAtLeast(TRACK_LIST_SCROLLBAR_MIN_THUMB_HEIGHT)
        val thumbTop = TRACK_LIST_SCROLLBAR_TRACK.top +
            (TRACK_LIST_SCROLLBAR_TRACK.height() - thumbHeight) *
            (trackListScrollOffset().coerceIn(0f, maximum) / maximum)
        return RectF(
            TRACK_LIST_SCROLLBAR_TRACK.left,
            thumbTop,
            TRACK_LIST_SCROLLBAR_TRACK.right,
            thumbTop + thumbHeight,
        )
    }

    private fun handleTrackListScrollbarDrag(pointerY: Float) {
        val maximum = trackListMaximumScroll()
        if (maximum <= 0f) return
        val thumb = trackListScrollbarThumbBounds()
        val travel = TRACK_LIST_SCROLLBAR_TRACK.height() - thumb.height()
        if (travel <= 0f) return
        val centerY = (
            pointerY - trackListScrollbarGrabOffset
        ).coerceIn(
            TRACK_LIST_SCROLLBAR_TRACK.top + thumb.height() / 2f,
            TRACK_LIST_SCROLLBAR_TRACK.bottom - thumb.height() / 2f,
        )
        val fraction = (
            (centerY - thumb.height() / 2f - TRACK_LIST_SCROLLBAR_TRACK.top) / travel
        ).coerceIn(0f, 1f)
        setTrackListScrollOffset(maximum * fraction)
        hoveredButton = TRACK_LIST_SCROLLBAR_ID
        renderVrMenu()
    }

    private fun loadMedia(
        newSpec: PlaybackSpec,
        browserReturn: BrowserReturnState? = null,
        resumeOverrideMs: Long? = null,
        autoSoftwareFallback: Boolean = false,
    ) {
        if (spec.uri != newSpec.uri) {
            remoteRecoveryAttempts = 0
            remotePlaybackStableAtMs = 0L
            networkStatusMessage = null
        }
        if (mediaLoaded && spec.uri == newSpec.uri) {
            loadMediaResolved(
                newSpec,
                browserReturn,
                resumeOverrideMs,
                autoSoftwareFallback,
                externalSubtitles,
            )
            return
        }

        val discoveryGeneration = ++subtitleDiscoveryGeneration
        if (mediaLoaded && ::player.isInitialized) player.pause()
        io.execute {
            val discovered = ExternalSubtitleRepository(this).discover(newSpec)
            runOnUiThread {
                if (
                    discoveryGeneration != subtitleDiscoveryGeneration ||
                    isFinishing ||
                    isDestroyed
                ) {
                    return@runOnUiThread
                }
                loadMediaResolved(
                    newSpec,
                    browserReturn,
                    resumeOverrideMs,
                    autoSoftwareFallback,
                    discovered,
                )
            }
        }
    }

    private fun loadMediaResolved(
        newSpec: PlaybackSpec,
        browserReturn: BrowserReturnState?,
        resumeOverrideMs: Long?,
        autoSoftwareFallback: Boolean,
        discoveredExternalSubtitles: List<ExternalSubtitle>,
    ) {
        mediaLoadGeneration++
        decoderFallbackPending = false
        if (mediaLoaded) {
            releaseCurrentPlayer(saveState = true)
        }
        externalSubtitles = discoveredExternalSubtitles
        if (externalSubtitles.isNotEmpty()) {
            Log.i(
                "DreamPlayerSubtitle",
                "Discovered external subtitles: ${externalSubtitles.joinToString { it.fileName }}",
            )
        }
        latestCues = emptyList()
        playbackBrowserReturn = browserReturn
        playbackCompleted = false
        currentMediaMarkedPlayed = false
        subtitleChoices = listOf(
            SubtitleChoice("Auto", null, -1, SUBTITLE_AUTO),
            SubtitleChoice("Off", null, -1, SUBTITLE_OFF),
        )
        subtitleChoiceIndex = 0
        subtitleTrackScrollOffset = 0f
        audioChoices = listOf(AudioChoice("Auto", null, -1, AUDIO_AUTO))
        audioChoiceIndex = 0
        audioTrackScrollOffset = 0f
        nativeSetSubtitle(renderSubtitle(emptyList()))
        val savedPlaybackState = mediaStateStore.loadPlaybackState(newSpec)
        decoderMode = DecoderMode.fromWire(
            savedPlaybackState?.decoderMode ?: DecoderMode.AUTO.wireValue
        )
        effectiveDecoderMode = when {
            decoderMode == DecoderMode.SOFTWARE -> DecoderMode.SOFTWARE
            decoderMode == DecoderMode.AUTO &&
                (autoSoftwareFallback || savedPlaybackState?.decoderAutoSoftware == true) ->
                DecoderMode.SOFTWARE
            else -> DecoderMode.HARDWARE
        }
        val savedPicture = savedPlaybackState?.pictureSettings?.takeIf {
            it.brightness.isFinite() &&
                it.exposure.isFinite() &&
                it.contrast.isFinite() &&
                it.saturation.isFinite() &&
                it.vibrance.isFinite()
        }
        currentMediaHasSavedPictureSettings = savedPicture != null
        hdrDefaultExposureApplied = false
        pictureMode = PictureMode.fromWire(savedPicture?.mode ?: PictureMode.AUTO.wireValue)
        pictureBrightness = savedPicture?.brightness
            ?.sanitized(
                PICTURE_BRIGHTNESS_MIN,
                PICTURE_BRIGHTNESS_MAX,
                DEFAULT_PICTURE_BRIGHTNESS,
            )
            ?: DEFAULT_PICTURE_BRIGHTNESS
        pictureExposure = savedPicture?.exposure
            ?.sanitized(
                PICTURE_EXPOSURE_MIN,
                PICTURE_EXPOSURE_MAX,
                DEFAULT_PICTURE_EXPOSURE,
            )
            ?: DEFAULT_PICTURE_EXPOSURE
        pictureContrast = savedPicture?.contrast
            ?.sanitized(
                PICTURE_CONTRAST_MIN,
                PICTURE_CONTRAST_MAX,
                DEFAULT_PICTURE_CONTRAST,
            )
            ?: DEFAULT_PICTURE_CONTRAST
        pictureSaturation = savedPicture?.saturation
            ?.sanitized(
                PICTURE_SATURATION_MIN,
                PICTURE_SATURATION_MAX,
                DEFAULT_PICTURE_SATURATION,
            )
            ?: DEFAULT_PICTURE_SATURATION
        pictureVibrance = savedPicture?.vibrance
            ?.sanitized(
                PICTURE_VIBRANCE_MIN,
                PICTURE_VIBRANCE_MAX,
                DEFAULT_PICTURE_VIBRANCE,
            )
            ?: DEFAULT_PICTURE_VIBRANCE
        hdrMetadataMode = HdrMetadataMode.fromWire(
            savedPlaybackState?.hdrMetadataMode ?: HdrMetadataMode.AUTO.wireValue
        )
        hdrManualPeakNits = savedPlaybackState?.hdrManualPeakNits
            ?.sanitized(
                HDR_SOURCE_PEAK_NITS_MIN,
                HDR_SOURCE_PEAK_NITS_MAX,
                DEFAULT_HDR_SOURCE_PEAK_NITS,
            )
            ?: DEFAULT_HDR_SOURCE_PEAK_NITS
        detectedHdrStaticMetadata = HdrStaticMetadata.NONE
        applyPictureSettings(persist = false)
        pendingSubtitleFingerprint = savedPlaybackState?.subtitleFingerprint
        subtitleSyncMs = savedPlaybackState?.subtitleSyncMs
            ?.coerceIn(-MAX_SUBTITLE_SYNC_MS, MAX_SUBTITLE_SYNC_MS)
            ?: 0
        pendingAudioFingerprint = savedPlaybackState?.audioTrackFingerprint
        val savedProjection = mediaStateStore.loadProjection(newSpec)
            ?.takeIf { it.manuallySelected }
        val detectedProjection = FilenameProjectionDetector.detect(newSpec.title)
        currentStereoMode = savedProjection?.mode
            ?: if (newSpec.projectionExplicit) newSpec.stereoMode
            else detectedProjection ?: StereoMode.MONO
        currentProjection =
            savedProjection?.projection ?: VideoProjection.FLAT
        immersiveYawDegrees =
            savedProjection?.immersiveYawDegrees?.coerceIn(-180f, 180f) ?: 0f
        immersivePitchDegrees =
            savedProjection?.immersivePitchDegrees?.coerceIn(-80f, 80f) ?: 0f
        spec = newSpec.copy(stereoMode = currentStereoMode)
        if (newSpec.projectionExplicit && savedProjection == null) {
            persistProjectionSettings()
        }
        Log.i(
            "DreamPlayerState",
            "Projection for ${newSpec.title}: $currentStereoMode " +
                "(source=${when {
                    savedProjection != null -> "saved"
                    newSpec.projectionExplicit -> "explicit"
                    detectedProjection != null -> "filename"
                    else -> "default"
                }}, mediaId=${MediaIdentity.from(spec).id.take(12)})",
        )
        nativeSetStereoMode(currentStereoMode.wireValue)
        nativeSetVideoProjection(currentProjection.wireValue)
        nativeSetImmersiveOrientation(
            immersiveYawDegrees,
            immersivePitchDegrees,
        )
        val savedSubtitleDepth = savedPlaybackState?.subtitleDepthMeters
            ?.takeIf(Float::isFinite)
        subtitleDepthIsCustom = savedSubtitleDepth != null
        subtitleDepthMeters = savedSubtitleDepth
            ?.sanitized(
                SUBTITLE_DEPTH_MIN_METERS,
                SUBTITLE_DEPTH_MAX_METERS,
                defaultSubtitleDepth(currentStereoMode),
            )
            ?: defaultSubtitleDepth(currentStereoMode)
        nativeSetSubtitleDepth(subtitleDepthMeters)
        subtitle3dEnabled = savedPlaybackState?.subtitle3dEnabled ?: false
        nativeSetSubtitle3dEnabled(subtitle3dEnabled)
        screenCurvature = savedPlaybackState?.screenCurvature
            ?.sanitized(
                SCREEN_CURVATURE_MIN,
                SCREEN_CURVATURE_MAX,
                DEFAULT_SCREEN_CURVATURE,
            )
            ?: DEFAULT_SCREEN_CURVATURE
        nativeSetScreenCurvature(screenCurvature)
        val transform = savedPlaybackState?.screenTransform
            ?.takeIf {
                it.x.isFinite() &&
                    it.y.isFinite() &&
                    it.z.isFinite() &&
                    it.halfHeight.isFinite()
            }
            ?: DEFAULT_SCREEN_TRANSFORM
        nativeSetScreenTransform(transform.x, transform.y, transform.z, transform.halfHeight)
        audioSyncMs = savedPlaybackState?.audioSyncMs
            ?.coerceIn(-AudioSyncAudioProcessor.MAX_DELAY_MS, AudioSyncAudioProcessor.MAX_DELAY_MS)
            ?: 0
        audioSyncProcessor = AudioSyncAudioProcessor(audioSyncMs)
        val isRemoteNetworkMedia = newSpec.remote != null
        val dataSourceFactory = when {
            newSpec.uri.startsWith("smb://") ->
                SmbDataSource.Factory(requireNotNull(newSpec.smb))
            isRemoteNetworkMedia ->
                RemoteMediaDataSource.Factory(requireNotNull(newSpec.remote))
            else -> DefaultDataSource.Factory(this)
        }
        val renderersFactory = object : NextRenderersFactory(this@PlayerActivity) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(StereoDownmixAudioProcessor(), audioSyncProcessor))
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }.apply {
            // PfD's platform DTS decoder exposes 8 KiB input buffers, which are smaller than
            // valid DTS-HD access units in some Blu-ray remuxes. Prefer FFmpeg for DTS audio only;
            // video and every other audio codec retain their existing renderer order.
            setPreferFfmpegForDts(true)
            setExtensionRendererMode(
                if (effectiveDecoderMode == DecoderMode.SOFTWARE) {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                } else {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                }
            )
        }
        val loadControl = if (isRemoteNetworkMedia) {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    REMOTE_MIN_BUFFER_MS,
                    REMOTE_MAX_BUFFER_MS,
                    REMOTE_PLAYBACK_BUFFER_MS,
                    REMOTE_REBUFFER_MS,
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        } else {
            DefaultLoadControl()
        }
        player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .also { it.addListener(this) }
        player.subtitleDelayMilliseconds = subtitleSyncMs.toLong()
        videoSurface?.let(player::setVideoSurface)
        val subtitleConfigurations = buildList {
            newSpec.subtitleUri?.let { subtitleUri ->
                add(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUri))
                        .setMimeType(subtitleMime(subtitleUri))
                        .setLabel(Uri.parse(subtitleUri).lastPathSegment ?: "External subtitle")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            }
            externalSubtitles.forEach { subtitle ->
                if (subtitle.uri != newSpec.subtitleUri) {
                    add(
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.uri))
                            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                            .setLabel(subtitle.fileName)
                            .build()
                    )
                }
            }
        }
        val item = MediaItem.Builder()
            .setUri(Uri.parse(newSpec.uri))
            .apply {
                if (subtitleConfigurations.isNotEmpty()) {
                    setSubtitleConfigurations(subtitleConfigurations)
                }
            }
            .build()
        player.setMediaItem(item)
        (resumeOverrideMs ?: resumePosition(savedPlaybackState))?.let { position ->
            player.seekTo(position)
            Log.i("DreamPlayerState", "Resuming ${newSpec.title} at ${formatTime(position)}")
        }
        player.prepare()
        player.playWhenReady = activityResumed
        resumePlaybackOnForeground = !activityResumed
        mediaLoaded = true
        if (isRemoteNetworkMedia) {
            val status =
                "Buffering ${newSpec.remote?.protocol?.label.orEmpty()} media…"
            networkStatusMessage = status
            transientMessage = status
        }
        if (resumeOverrideMs != null) {
            saveCurrentMediaState(positionOverrideMs = resumeOverrideMs)
        }
        mainHandler.removeCallbacks(stateSaver)
        mainHandler.postDelayed(stateSaver, STATE_SAVE_INTERVAL_MS)
        resetPlaybackHealthMonitor()
        mainHandler.removeCallbacks(playbackHealthMonitor)
        mainHandler.postDelayed(playbackHealthMonitor, 1_000L)
        nativeSetVideoAvailable(true)
        showControls(8_000L)
    }

    private fun releaseCurrentPlayer(saveState: Boolean) {
        if (!mediaLoaded) return
        if (saveState) saveCurrentMediaState()
        mainHandler.removeCallbacks(stateSaver)
        mainHandler.removeCallbacks(playbackHealthMonitor)
        mediaLoaded = false
        nativeSetVideoAvailable(false)

        // Do not explicitly detach the external OpenXR surface here. On PfD,
        // clearVideoSurface() blocks until Media3's detach timeout and leaves the
        // ANativeWindow connected, preventing every subsequent decoder from using it.
        // Direct release tears down the renderer and disconnects its codec cleanly.
        player.release()
    }

    private fun showLibrary() {
        nativeSetVideoAvailable(false)
        recentMedia = mediaStateStore.listRecent(RECENT_MEDIA_LIMIT)
        uiScreen = UiScreen.LIBRARY
        playbackPopover = PlaybackPopover.NONE
        uiVisible = true
        hoveredButton = null
        mainHandler.removeCallbacks(uiTicker)
        renderVrMenu()
    }

    private fun playRecent(index: Int) {
        val recent = recentMedia.getOrNull(index) ?: return
        val parsed = Uri.parse(recent.uri)
        val remoteProtocol = when (parsed.scheme?.lowercase(Locale.ROOT)) {
            "sftp" -> RemoteProtocol.SFTP
            "ftps" -> RemoteProtocol.FTPS
            "ftp" -> RemoteProtocol.FTP
            else -> null
        }
        if (remoteProtocol != null) {
            val defaultPort = remoteProtocol.defaultPort
            val port = parsed.port.takeIf { it > 0 } ?: defaultPort
            val remoteProfile = sftpProfiles.firstOrNull {
                it.protocol == remoteProtocol &&
                    it.port == port &&
                    it.host.equals(parsed.host, ignoreCase = true)
            }
            if (remoteProfile == null) {
                transientMessage =
                    "Cannot reopen remote file: its saved server profile is unavailable"
                renderVrMenu()
                return
            }
            transientMessage = null
            loadMedia(
                PlaybackSpec(
                    recent.uri,
                    recent.title,
                    StereoMode.MONO,
                    remote = remoteProfile,
                )
            )
            return
        }
        if (!parsed.scheme.equals("smb", ignoreCase = true)) {
            transientMessage = null
            loadMedia(PlaybackSpec(recent.uri, recent.title, StereoMode.MONO))
            return
        }

        val host = parsed.host.orEmpty()
        val share = recent.smbShare.orEmpty()
        if (host.isBlank() || share.isBlank()) {
            transientMessage = "Cannot open recent SMB file: its server or share is missing"
            renderVrMenu()
            return
        }

        val saved = smbCredentialStore.load()
            ?.takeIf { hostsMatch(it.host, host) }
        val credentials = (saved ?: SmbCredentials(host, share, "", "", ""))
            .copy(
                host = host,
                share = share,
                connectionHost = saved?.connectionTarget ?: host,
            )
        val path = parsed.pathSegments.joinToString("\\")
        transientMessage = "Opening ${recent.title}..."
        renderVrMenu()
        io.execute {
            runCatching {
                SmbRepository(credentials).use { repository ->
                    repository.open(path).use { /* Opening proves authentication and file access. */ }
                }
            }.onSuccess {
                runOnUiThread {
                    smbCredentials = credentials
                    smbDisplayHost = host
                    rememberSmbCredentials = saved != null
                    transientMessage = null
                    loadMedia(
                        PlaybackSpec(
                            recent.uri,
                            recent.title,
                            StereoMode.MONO,
                            smb = credentials,
                        )
                    )
                }
            }.onFailure { error ->
                runOnUiThread {
                    transientMessage =
                        "Could not open recent SMB file: ${friendlySmbError(error)}"
                    hoveredButton = null
                    renderVrMenu()
                }
            }
        }
    }

    private fun returnToSourceBrowser() {
        val destination = playbackBrowserReturn
        playbackBrowserReturn = null
        playbackPopover = PlaybackPopover.NONE
        uiVisible = true
        hoveredButton = null
        transientMessage = null
        mainHandler.removeCallbacks(uiTicker)
        refreshBrowserMetadata()
        when (destination) {
            is BrowserReturnState.Local -> {
                localFolder = destination.folder
                browserSearchQuery = destination.searchQuery
                rebuildBrowserItems()
                browserScrollOffset = destination.scrollOffset
                uiScreen = UiScreen.LOCAL_FILES
                renderVrMenu()
            }
            is BrowserReturnState.Smb -> {
                smbCredentials = destination.credentials
                smbPath = destination.path
                smbEntries = destination.entries
                browserSearchQuery = destination.searchQuery
                rebuildBrowserItems()
                browserScrollOffset = destination.scrollOffset
                uiScreen = UiScreen.SMB_FILES
                renderVrMenu()
            }
            is BrowserReturnState.Remote -> {
                sftpProfile = destination.profile
                sftpPortDraft = destination.profile.port.toString()
                sftpPath = destination.path
                sftpEntries = destination.entries
                browserScrollOffset = destination.scrollOffset
                uiScreen = UiScreen.SFTP_FILES
                renderVrMenu()
            }
            null -> showLibrary()
        }
    }

    private fun openLocalBrowser() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission), REQUEST_MEDIA_PERMISSION)
            return
        }
        loadLocalVideos()
    }

    private fun loadLocalVideos() {
        transientMessage = "Scanning local videos..."
        renderVrMenu()
        io.execute {
            runCatching { LocalVideoRepository(this).list() }
                .onSuccess { videos ->
                    runOnUiThread {
                        localVideos = videos
                        localFolder = null
                        browserSearchQuery = ""
                        rebuildBrowserItems()
                        browserScrollOffset = 0f
                        localFolderListScrollOffset = 0f
                        transientMessage = null
                        uiScreen = UiScreen.LOCAL_FOLDERS
                        hoveredButton = null
                        renderVrMenu()
                    }
                }
                .onFailure { error -> runOnUiThread {
                    transientMessage = "Local library error: ${error.message.orEmpty()}"
                    showLibrary()
                } }
        }
    }

    private fun discoverSmbServers() {
        uiScreen = UiScreen.SMB_SERVERS
        browserScrollOffset = 0f
        smbServerListScrollOffset = 0f
        transientMessage = "Scanning the local network for SMB servers..."
        hoveredButton = null
        renderVrMenu()
        io.execute {
            runCatching { SmbDiscovery(this).discover() }
                .onSuccess { servers -> runOnUiThread {
                    val remembered = smbCredentials.takeIf {
                        rememberSmbCredentials && it.host.isNotBlank()
                    }?.let {
                        SmbServer(it.connectionTarget, it.host)
                    }
                    smbServers = (listOfNotNull(remembered) + servers)
                        .distinctBy { it.host.lowercase(Locale.ROOT) }
                    transientMessage = if (smbServers.isEmpty()) "No SMB servers found; use Add server" else null
                    renderVrMenu()
                } }
                .onFailure { error -> runOnUiThread {
                    transientMessage = "SMB discovery error: ${error.message.orEmpty()}"
                    renderVrMenu()
                } }
        }
    }

    private fun showSmbLogin(
        host: String = smbCredentials.host,
        displayHost: String = smbDisplayHost.ifBlank { host },
        connectionHost: String = smbCredentials.connectionTarget.ifBlank { host },
        errorMessage: String? = null,
    ) {
        smbDisplayHost = displayHost.ifBlank { host }
        smbCredentials = if (host == smbCredentials.host) {
            smbCredentials.copy(
                share = "",
                connectionHost = connectionHost.ifBlank { host },
            )
        }
        else SmbCredentials(
            host = host,
            share = "",
            username = "",
            password = "",
            domain = "",
            connectionHost = connectionHost.ifBlank { host },
        )
        uiScreen = UiScreen.SMB_LOGIN
        hoveredButton = null
        transientMessage = errorMessage
        renderVrMenu()
    }

    private fun openSmbServer(server: SmbServer) {
        val preferredHost = server.displayName
            .takeIf { it.isNotBlank() && !it.equals(server.host, ignoreCase = true) }
            ?: server.host
        smbDisplayHost = preferredHost
        val saved = smbCredentialStore.load()
        val savedMatches = saved != null && sequenceOf(
            server.host,
            server.displayName,
        ).any { candidate -> hostsMatch(saved.host, candidate) }
        if (savedMatches) {
            rememberSmbCredentials = true
            smbCredentials = requireNotNull(saved).copy(
                host = preferredHost,
                share = "",
                connectionHost = server.host,
            )
            connectSmb(autoLogin = true)
        } else {
            showSmbLogin(
                host = preferredHost,
                displayHost = preferredHost,
                connectionHost = server.host,
            )
        }
    }

    private fun hostsMatch(first: String, second: String): Boolean {
        fun normalized(value: String) = value.trim()
            .trimEnd('.')
            .lowercase(Locale.ROOT)
            .removeSuffix(".local")
        val left = normalized(first)
        val right = normalized(second)
        if (left == right) return true
        val ipv4 = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
        return !ipv4.matches(left) && !ipv4.matches(right) &&
            left.substringBefore('.') == right.substringBefore('.')
    }

    private fun connectSmb(autoLogin: Boolean = false) {
        if (smbCredentials.host.isBlank()) {
            transientMessage = "Enter a server hostname or IP address"
            renderVrMenu()
            return
        }
        if (smbDisplayHost.isBlank()) smbDisplayHost = smbCredentials.host
        transientMessage = "Connecting to ${smbCredentials.host}..."
        renderVrMenu()
        val credentials = smbCredentials.copy(share = "")
        io.execute {
            runCatching { SmbShareBrowser().listShares(credentials) }
                .onSuccess { shares -> runOnUiThread {
                    smbCredentials = credentials
                    if (rememberSmbCredentials) smbCredentialStore.save(credentials)
                    else smbCredentialStore.clear()
                    smbShares = shares
                    browserScrollOffset = 0f
                    smbShareListScrollOffset = 0f
                    smbNavigationStack.clear()
                    transientMessage = if (shares.isEmpty()) "No accessible shares were returned" else null
                    uiScreen = UiScreen.SMB_SHARES
                    hoveredButton = null
                    renderVrMenu()
                } }
                .onFailure { error -> runOnUiThread {
                    val message = "SMB login failed: ${friendlySmbError(error)}"
                    if (autoLogin) {
                        showSmbLogin(
                            host = credentials.host,
                            displayHost = smbDisplayHost,
                            connectionHost = credentials.connectionTarget,
                            errorMessage = message,
                        )
                    } else {
                        transientMessage = message
                        renderVrMenu()
                    }
                } }
        }
    }

    private fun browseSmb(
        path: String,
        restoreScrollOffset: Float = 0f,
        restoreSearchQuery: String = "",
        rollbackNavigationOnFailure: Boolean = false,
    ) {
        val credentials = smbCredentials
        transientMessage = "Loading ${if (path.isBlank()) credentials.share else path}..."
        renderVrMenu()
        io.execute {
            runCatching {
                SmbRepository(credentials).use { repository ->
                    repository.list(path).filter { it.isDirectory || isVideoFile(it.name) }
                }
            }.onSuccess { entries -> runOnUiThread {
                smbPath = path
                smbEntries = entries
                browserSearchQuery = restoreSearchQuery
                refreshBrowserMetadata()
                browserScrollOffset = restoreScrollOffset
                transientMessage = null
                uiScreen = UiScreen.SMB_FILES
                hoveredButton = null
                renderVrMenu()
            } }.onFailure { error -> runOnUiThread {
                if (rollbackNavigationOnFailure && smbNavigationStack.isNotEmpty()) {
                    smbNavigationStack.removeLast()
                }
                transientMessage = "SMB folder error: ${friendlySmbError(error)}"
                renderVrMenu()
            } }
        }
    }

    private fun friendlySmbError(error: Throwable): String {
        val message = generateSequence(error) { it.cause }.mapNotNull { it.message }.firstOrNull().orEmpty()
        return when {
            message.contains("logon", true) || message.contains("access", true) -> "check username, password, and domain"
            message.contains("timed out", true) || message.contains("connect", true) -> "server did not respond"
            else -> message.ifBlank { error.javaClass.simpleName }
        }.take(100)
    }

    private fun showSftpProfiles() {
        sftpProfiles = sftpProfileStore.load()
        uiScreen = UiScreen.SFTP_PROFILES
        browserScrollOffset = sftpProfileListScrollOffset
        transientMessage = null
        hoveredButton = null
        renderVrMenu()
    }

    private fun showSftpLogin(
        profile: SftpProfile = SftpProfile(),
        errorMessage: String? = null,
    ) {
        sftpProfile = profile
        sftpPortDraft = profile.port.toString()
        pendingSftpHostKey = null
        uiScreen = UiScreen.SFTP_LOGIN
        transientMessage = errorMessage
        hoveredButton = null
        renderVrMenu()
    }

    private fun persistSftpProfile(profile: SftpProfile) {
        val normalized = profile.normalized()
        sftpProfiles = (
            sftpProfiles.filterNot { it.id == normalized.id } + normalized
        ).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
        sftpProfileStore.save(sftpProfiles)
    }

    private fun connectSftp() {
        val profile = sftpProfile.normalized()
        when {
            profile.host.isBlank() -> {
                transientMessage =
                    "Enter a ${profile.protocol.label} hostname or IP address"
                renderVrMenu()
                return
            }
            profile.username.isBlank() -> {
                transientMessage = "Enter a ${profile.protocol.label} username"
                renderVrMenu()
                return
            }
            profile.password.isBlank() -> {
                transientMessage = "Enter the ${profile.protocol.label} password"
                renderVrMenu()
                return
            }
        }
        // Save the selected protocol and credentials before connecting so a
        // failed attempt or an in-place app update does not make the user
        // re-enter the profile. Password persistence still follows the
        // profile's explicit "remember password" choice.
        persistSftpProfile(profile)
        if (
            profile.protocol == RemoteProtocol.FTP &&
            !profile.allowInsecureFtp
        ) {
            sftpProfile = profile
            transientMessage = null
            uiScreen = UiScreen.FTP_WARNING
            hoveredButton = null
            renderVrMenu()
            return
        }
        sftpProfile = profile
        transientMessage = "Connecting to ${profile.host}..."
        renderVrMenu()
        io.execute {
            runCatching {
                when (profile.protocol) {
                    RemoteProtocol.SFTP ->
                        SftpRepository(profile).use { repository ->
                            val root =
                                repository.canonicalize(profile.startPath)
                            root to repository.list(root)
                                .filter {
                                    it.isDirectory || isVideoFile(it.name)
                                }
                        }
                    RemoteProtocol.FTPS,
                    RemoteProtocol.FTP ->
                        FtpRepository(profile).use { repository ->
                            val root =
                                repository.canonicalize(profile.startPath)
                            root to repository.list(root)
                                .filter {
                                    it.isDirectory || isVideoFile(it.name)
                                }
                        }
                }
            }.onSuccess { (root, entries) -> runOnUiThread {
                sftpPath = root
                sftpEntries = entries
                sftpNavigationStack.clear()
                browserScrollOffset = 0f
                pendingSftpHostKey = null
                persistSftpProfile(profile)
                transientMessage = null
                uiScreen = UiScreen.SFTP_FILES
                hoveredButton = null
                renderVrMenu()
            } }.onFailure { error -> runOnUiThread {
                Log.e(
                    "DreamPlayerRemote",
                    "${profile.protocol.label} connection or initial listing failed",
                    error,
                )
                val hostKeyError = generateSequence(error) { it.cause }
                    .filterIsInstance<SftpHostKeyException>()
                    .firstOrNull()
                if (hostKeyError != null) {
                    pendingSftpHostKey = hostKeyError
                    transientMessage = null
                    uiScreen = UiScreen.SFTP_TRUST
                    hoveredButton = null
                    renderVrMenu()
                } else {
                    transientMessage =
                        "${profile.protocol.label} connection failed: " +
                            friendlySftpError(error)
                    uiScreen = UiScreen.SFTP_LOGIN
                    renderVrMenu()
                }
            } }
        }
    }

    private fun browseSftp(
        path: String,
        restoreScrollOffset: Float = 0f,
        rollbackNavigationOnFailure: Boolean = false,
    ) {
        val profile = sftpProfile
        transientMessage = "Loading $path..."
        renderVrMenu()
        io.execute {
            runCatching {
                when (profile.protocol) {
                    RemoteProtocol.SFTP ->
                        SftpRepository(profile).use { repository ->
                            repository.list(path)
                                .filter {
                                    it.isDirectory || isVideoFile(it.name)
                                }
                        }
                    RemoteProtocol.FTPS,
                    RemoteProtocol.FTP ->
                        FtpRepository(profile).use { repository ->
                            repository.list(path)
                                .filter {
                                    it.isDirectory || isVideoFile(it.name)
                                }
                        }
                }
            }.onSuccess { entries -> runOnUiThread {
                sftpPath = path
                sftpEntries = entries
                browserScrollOffset = restoreScrollOffset
                transientMessage = null
                uiScreen = UiScreen.SFTP_FILES
                hoveredButton = null
                renderVrMenu()
            } }.onFailure { error -> runOnUiThread {
                Log.e(
                    "DreamPlayerRemote",
                    "${profile.protocol.label} folder listing failed",
                    error,
                )
                if (
                    rollbackNavigationOnFailure &&
                    sftpNavigationStack.isNotEmpty()
                ) {
                    sftpNavigationStack.removeLast()
                }
                transientMessage =
                    "${profile.protocol.label} folder error: " +
                        friendlySftpError(error)
                renderVrMenu()
            } }
        }
    }

    private fun friendlySftpError(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.toList()
        val messages = chain.mapNotNull { it.message }
            .filter { it.isNotBlank() }
        val message = messages.firstOrNull().orEmpty()
        val combined = messages.joinToString(" | ")
        return when {
            combined.contains("identification", true) ||
                combined.contains("protocol version", true) ->
                "this port is not SFTP; select FTP or FTPS"
            combined.contains("certificate", true) ||
                combined.contains("certpath", true) ->
                "the FTPS certificate or hostname could not be verified"
            combined.contains("SSL", true) ||
                combined.contains("TLS", true) ->
                "the server does not appear to support explicit FTPS"
            combined.contains("auth", true) ||
                combined.contains("permission", true) ->
                "check username and password"
            combined.contains("timed out", true) -> "server timed out"
            combined.contains("refused", true) -> "connection was refused"
            combined.contains("no such file", true) ||
                combined.contains("not found", true) ->
                "start folder was not found"
            else -> message.ifBlank { error.javaClass.simpleName }
        }.replace('\n', ' ').take(120)
    }

    private fun isVideoFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf(
        "mkv", "mp4", "m4v", "mov", "avi", "webm", "ts", "m2ts", "mts", "mpg", "mpeg", "wmv",
    )

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MEDIA_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) loadLocalVideos()
            else {
                transientMessage = "Video permission is required for the in-VR library"
                showLibrary()
            }
        }
    }

    private fun chooseLocalVideo() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            },
            REQUEST_VIDEO,
        )
    }

    @Deprecated("Activity result API retained because this activity must stay a native VR Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VIDEO || resultCode != RESULT_OK) {
            if (!mediaLoaded) showLibrary()
            return
        }
        val uri = data?.data ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        loadMedia(PlaybackSpec(uri.toString(), uri.lastPathSegment ?: "Video", currentStereoMode))
    }

    @Suppress("unused") // Called from JNI if the runtime exits or initialization fails.
    fun onNativeExit(reason: String) {
        runOnUiThread {
            if (reason.isNotBlank()) {
                Log.e("DreamPlayerOpenXR", reason)
                Toast.makeText(
                    this,
                    "Dream Player VR stopped: $reason",
                    Toast.LENGTH_LONG,
                ).show()
            }
            if (mediaLoaded) releaseCurrentPlayer(saveState = true)
            finishAndRemoveTask()
        }
    }

    override fun onCues(cueGroup: CueGroup) {
        latestCues = cueGroup.cues
        cueGroup.cues.firstOrNull { it.bitmap != null }?.let { cue ->
            Log.i(
                "DreamPlayerSubtitle",
                "Bitmap cue ${cue.bitmap?.width}x${cue.bitmap?.height} " +
                    "position=${cue.position} line=${cue.line} " +
                    "size=${cue.size} bitmapHeight=${cue.bitmapHeight}",
            )
        }
        if (!uiVisible) applySubtitleCues(latestCues)
    }

    override fun onTracksChanged(tracks: Tracks) {
        val discovered = mutableListOf(
            SubtitleChoice("Auto", null, -1, SUBTITLE_AUTO),
            SubtitleChoice("Off", null, -1, SUBTITLE_OFF),
        )
        val fingerprintOccurrences = mutableMapOf<String, Int>()
        tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEach { group ->
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val name = listOfNotNull(
                    format.label?.takeIf { it.isNotBlank() },
                    format.language?.takeIf { it.isNotBlank() },
                ).distinct().joinToString(" - ").ifBlank { "Subtitle ${discovered.size - 1}" }
                val baseFingerprint = subtitleFingerprint(format)
                val occurrence = fingerprintOccurrences.getOrDefault(baseFingerprint, 0)
                fingerprintOccurrences[baseFingerprint] = occurrence + 1
                discovered += SubtitleChoice(name, group, trackIndex, "$baseFingerprint#$occurrence")
            }
        }
        val discoveredAudio = mutableListOf(AudioChoice("Auto", null, -1, AUDIO_AUTO))
        val audioFingerprintOccurrences = mutableMapOf<String, Int>()
        tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEachIndexed { groupIndex, group ->
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val supported = group.isTrackSupported(trackIndex)
                Log.i(
                    "DreamPlayerAudio",
                    "group=$groupIndex track=$trackIndex selected=${group.isTrackSelected(trackIndex)} " +
                        "supported=$supported mime=${format.sampleMimeType} " +
                        "codecs=${format.codecs} channels=${format.channelCount} rate=${format.sampleRate}",
                )
                if (supported) {
                    val baseFingerprint = audioFingerprint(format)
                    val occurrence = audioFingerprintOccurrences.getOrDefault(baseFingerprint, 0)
                    audioFingerprintOccurrences[baseFingerprint] = occurrence + 1
                    discoveredAudio += AudioChoice(
                        label = audioTrackLabel(format, discoveredAudio.size),
                        group = group,
                        trackIndex = trackIndex,
                        fingerprint = "$baseFingerprint#$occurrence",
                    )
                }
            }
        }
        var selectedVideoFormat: Format? = null
        tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.forEachIndexed { groupIndex, group ->
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val color = format.colorInfo
                if (group.isTrackSelected(trackIndex)) selectedVideoFormat = format
                Log.i(
                    "DreamPlayerVideo",
                    "group=$groupIndex track=$trackIndex selected=${group.isTrackSelected(trackIndex)} " +
                        "mime=${format.sampleMimeType} size=${format.width}x${format.height} " +
                        "space=${color?.colorSpace} range=${color?.colorRange} transfer=${color?.colorTransfer}",
                )
            }
        }
        selectedVideoFormat?.let(::applyVideoColorInfo)
        subtitleChoices = discovered
        val savedFingerprint = pendingSubtitleFingerprint
        if (savedFingerprint != null) {
            val restoredIndex = discovered.indexOfFirst { it.fingerprint == savedFingerprint }
            if (restoredIndex >= 0) {
                applySubtitle(restoredIndex, persist = false)
                pendingSubtitleFingerprint = null
                Log.i("DreamPlayerState", "Restored subtitle: ${discovered[restoredIndex].label}")
            }
        } else if (subtitleChoiceIndex !in discovered.indices) {
            subtitleChoiceIndex = 0
        }
        audioChoices = discoveredAudio
        val savedAudioFingerprint = pendingAudioFingerprint
        if (savedAudioFingerprint != null) {
            val restoredIndex = discoveredAudio.indexOfFirst { it.fingerprint == savedAudioFingerprint }
            if (restoredIndex >= 0) {
                pendingAudioFingerprint = null
                applyAudioTrack(restoredIndex, persist = false)
                Log.i("DreamPlayerState", "Restored audio: ${discoveredAudio[restoredIndex].label}")
            }
        } else {
            audioChoiceIndex = discoveredAudio.indexOfFirst { choice ->
                choice.group?.isTrackSelected(choice.trackIndex) == true
            }.takeIf { it >= 0 } ?: 0
        }
        if (
            uiVisible && (
                uiScreen == UiScreen.SUBTITLES ||
                    uiScreen == UiScreen.AUDIO_TRACKS ||
                    (
                        uiScreen == UiScreen.PLAYBACK &&
                            playbackPopover in setOf(
                                PlaybackPopover.SUBTITLES,
                                PlaybackPopover.AUDIO,
                            )
                        )
                )
        ) {
            if (isTrackPopoverOpen()) ensureSelectedTrackVisible()
            renderVrMenu()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        updateNetworkPlaybackStatus(playbackState)
        if (
            playbackState == Player.STATE_READY &&
            mediaLoaded &&
            !currentMediaMarkedPlayed &&
            spec.uri.isNotBlank()
        ) {
            mediaStateStore.markPlayed(spec)
            currentMediaMarkedPlayed = true
        }
        if (playbackState == Player.STATE_ENDED && mediaLoaded) {
            playbackCompleted = true
            saveCurrentMediaState(positionOverrideMs = 0L)
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) playbackCompleted = false
        if (isPlaying && spec.remote != null && remotePlaybackStableAtMs == 0L) {
            remotePlaybackStableAtMs = SystemClock.elapsedRealtime()
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        if (
            decoderMode == DecoderMode.AUTO &&
            effectiveDecoderMode == DecoderMode.HARDWARE &&
            isVideoDecoderFailure(error) &&
            scheduleSoftwareDecoderFallback(
                player.currentPosition.coerceAtLeast(0L),
                "Hardware decoder failed (${error.errorCodeName})",
            )
        ) {
            Log.w(
                "DreamPlayerDecoder",
                "Hardware video decoder error; scheduling software retry",
                error,
            )
            return
        }
        if (scheduleRemotePlaybackRecovery(error)) return
        if (spec.remote != null) {
            val friendly = friendlyRemotePlaybackError(error)
            message.text = "Remote playback stopped\n$friendly"
            message.visibility = View.VISIBLE
            showOsd("Remote playback stopped\n$friendly", 12_000L)
            return
        }
        message.text = "Playback error\n${error.errorCodeName}\n${error.message.orEmpty()}"
        message.visibility = View.VISIBLE
        showOsd("Playback error\n${error.errorCodeName}\n${error.message.orEmpty()}", 10_000L)
    }

    private fun updateNetworkPlaybackStatus(playbackState: Int) {
        if (spec.remote == null || !mediaLoaded) return
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                val status = if (player.currentPosition > 0L) {
                    "Network slowed down • buffering…"
                } else {
                    "Buffering ${spec.remote?.protocol?.label.orEmpty()} media…"
                }
                networkStatusMessage = status
                transientMessage = status
                showControls(0L)
            }
            Player.STATE_READY -> {
                networkStatusMessage?.let { status ->
                    if (transientMessage == status) transientMessage = null
                }
                networkStatusMessage = null
                if (
                    remotePlaybackStableAtMs > 0L &&
                    SystemClock.elapsedRealtime() - remotePlaybackStableAtMs >
                        REMOTE_STABLE_RESET_MS
                ) {
                    remoteRecoveryAttempts = 0
                    remotePlaybackStableAtMs = SystemClock.elapsedRealtime()
                }
                if (uiVisible) renderVrMenu()
            }
        }
    }

    private fun scheduleRemotePlaybackRecovery(
        error: PlaybackException,
    ): Boolean {
        spec.remote ?: return false
        if (
            remotePlaybackStableAtMs > 0L &&
            SystemClock.elapsedRealtime() - remotePlaybackStableAtMs >
                REMOTE_STABLE_RESET_MS
        ) {
            remoteRecoveryAttempts = 0
        }
        if (remoteRecoveryAttempts >= REMOTE_PLAYER_RETRY_COUNT) return false
        val networkFailure = generateSequence<Throwable>(error) { it.cause }
            .any {
                it is java.io.IOException ||
                    it is java.net.SocketException ||
                    it is java.net.SocketTimeoutException
            }
        if (!networkFailure) return false
        remoteRecoveryAttempts++
        val attempt = remoteRecoveryAttempts
        val position = player.currentPosition.coerceAtLeast(0L)
        val failedSpec = spec
        val returnState = playbackBrowserReturn
        val status =
            "Connection interrupted • reconnecting $attempt/$REMOTE_PLAYER_RETRY_COUNT…"
        networkStatusMessage = status
        transientMessage = status
        showControls(0L)
        Log.w(
            "DreamPlayerRemote",
            "Playback connection failed; retry $attempt at $position ms",
            error,
        )
        mainHandler.postDelayed({
            if (!mediaLoaded || spec != failedSpec) return@postDelayed
            loadMedia(
                failedSpec,
                returnState,
                resumeOverrideMs = position,
            )
        }, REMOTE_PLAYER_RETRY_DELAY_MS * attempt)
        return true
    }

    private fun friendlyRemotePlaybackError(
        error: PlaybackException,
    ): String {
        val details = generateSequence<Throwable>(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        return when {
            "authentication" in details || "login" in details ->
                "The saved username or password was rejected."
            "timed out" in details || "timeout" in details ->
                "The server stopped responding. Check the connection and try again."
            "unknownhost" in details || "unable to resolve" in details ->
                "The server name could not be resolved."
            "refused" in details ->
                "The server refused the connection on the configured port."
            "not found" in details || "no such file" in details ->
                "The remote file is no longer available."
            "certificate" in details || "handshake" in details ->
                "The FTPS certificate or secure connection could not be verified."
            else ->
                "Playback stopped after $REMOTE_PLAYER_RETRY_COUNT reconnection attempts."
        }
    }

    private fun isVideoDecoderFailure(error: PlaybackException): Boolean {
        val exoError = error as? ExoPlaybackException ?: return false
        if (exoError.type != ExoPlaybackException.TYPE_RENDERER) return false
        val rendererIsVideo =
            MimeTypes.isVideo(exoError.rendererFormat?.sampleMimeType) ||
                exoError.rendererName?.contains("video", ignoreCase = true) == true
        if (!rendererIsVideo) return false
        return error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
    }

    private fun scheduleSoftwareDecoderFallback(positionMs: Long, reason: String): Boolean {
        if (
            decoderFallbackPending ||
            !mediaLoaded ||
            decoderMode != DecoderMode.AUTO ||
            effectiveDecoderMode != DecoderMode.HARDWARE
        ) {
            return false
        }
        decoderFallbackPending = true
        val generation = mediaLoadGeneration
        val failedSpec = spec
        val returnState = playbackBrowserReturn
        mainHandler.post {
            if (
                !mediaLoaded ||
                generation != mediaLoadGeneration ||
                spec != failedSpec ||
                decoderMode != DecoderMode.AUTO ||
                effectiveDecoderMode != DecoderMode.HARDWARE
            ) {
                decoderFallbackPending = false
                return@post
            }
            mediaStateStore.saveDecoderAutoSoftware(failedSpec, true)
            loadMedia(
                failedSpec,
                returnState,
                resumeOverrideMs = positionMs,
                autoSoftwareFallback = true,
            )
            showOsd("$reason\nUsing software decoder", 5_000L)
        }
        return true
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        if (videoSize.width <= 0 || videoSize.height <= 0) return
        lastVideoSize = videoSize
        updateVideoAspect()
    }

    private fun updateVideoAspect() {
        val videoSize = lastVideoSize
        if (videoSize.width <= 0 || videoSize.height <= 0) return
        val pixelAspect = if (videoSize.pixelWidthHeightRatio > 0f) videoSize.pixelWidthHeightRatio else 1f
        val fullAspect = videoSize.width * pixelAspect / videoSize.height
        val eyeAspect = when (currentStereoMode) {
            StereoMode.MONO -> fullAspect
            StereoMode.SBS -> fullAspect / 2f
            StereoMode.OVER_UNDER -> fullAspect * 2f
            StereoMode.HALF_SBS,
            StereoMode.HALF_OVER_UNDER -> fullAspect
        }
        nativeSetVideoAspect(eyeAspect)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        return when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (!mediaLoaded) {
                    chooseLocalVideo()
                } else {
                    if (player.isPlaying) player.pause() else player.play()
                    showOsd(if (player.isPlaying) "Playing" else "Paused")
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!mediaLoaded) return true
                player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                showOsd("Seek -10 seconds")
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!mediaLoaded) return true
                player.seekTo(player.currentPosition + 10_000L)
                showOsd("Seek +10 seconds")
                true
            }
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_DPAD_UP -> {
                cycleStereoMode()
                true
            }
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                cycleSubtitle()
                true
            }
            KeyEvent.KEYCODE_MENU -> {
                showControls(8_000L)
                true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                finish()
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun cycleStereoMode() {
        currentStereoMode = when (currentStereoMode) {
            StereoMode.MONO -> StereoMode.SBS
            StereoMode.SBS -> StereoMode.HALF_SBS
            StereoMode.HALF_SBS -> StereoMode.OVER_UNDER
            StereoMode.OVER_UNDER -> StereoMode.HALF_OVER_UNDER
            StereoMode.HALF_OVER_UNDER -> StereoMode.MONO
        }
        nativeSetStereoMode(currentStereoMode.wireValue)
        applyDefaultSubtitleDepthForProjection()
        persistProjectionSettings()
        updateVideoAspect()
        showControls(0L)
    }

    private fun cycleSubtitle() {
        if (!mediaLoaded) {
            showOsd("No video loaded")
            return
        }
        applySubtitle((subtitleChoiceIndex + 1) % subtitleChoices.size)
        showControls(0L)
    }

    private fun applySubtitle(index: Int, persist: Boolean = true) {
        subtitleChoiceIndex = index.coerceIn(subtitleChoices.indices)
        val choice = subtitleChoices[subtitleChoiceIndex]
        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        when (subtitleChoiceIndex) {
            0 -> Unit
            1 -> builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            else -> choice.group?.let {
                builder.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, choice.trackIndex))
            }
        }
        player.trackSelectionParameters = builder.build()
        if (persist) {
            pendingSubtitleFingerprint = null
            mediaStateStore.saveSubtitle(spec, choice.fingerprint)
        }
    }

    private fun subtitleFingerprint(format: Format): String {
        fun normalized(value: String?) = value.orEmpty().trim().lowercase(Locale.ROOT)
        return listOf(
            normalized(format.language),
            normalized(format.label),
            normalized(format.sampleMimeType),
            format.roleFlags.toString(),
            format.selectionFlags.toString(),
        ).joinToString("|")
    }

    private fun audioFingerprint(format: Format): String {
        fun normalized(value: String?) = value.orEmpty().trim().lowercase(Locale.ROOT)
        return listOf(
            normalized(format.language),
            normalized(format.label),
            normalized(format.sampleMimeType),
            normalized(format.codecs),
            format.channelCount.toString(),
            format.sampleRate.toString(),
            format.roleFlags.toString(),
        ).joinToString("|")
    }

    private fun audioTrackLabel(format: Format, fallbackIndex: Int): String {
        val channelLabel = when (format.channelCount) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            C.LENGTH_UNSET -> null
            else -> "${format.channelCount} ch"
        }
        val codecLabel = format.codecs
            ?.substringBefore('.')
            ?.uppercase(Locale.ROOT)
            ?: format.sampleMimeType?.substringAfter('/')?.uppercase(Locale.ROOT)
        return listOfNotNull(
            format.label?.takeIf(String::isNotBlank),
            format.language?.takeIf(String::isNotBlank)?.uppercase(Locale.ROOT),
            channelLabel,
            codecLabel,
        ).distinct().joinToString(" • ").ifBlank { "Audio track $fallbackIndex" }
    }

    private fun applyAudioTrack(index: Int, persist: Boolean = true) {
        audioChoiceIndex = index.coerceIn(audioChoices.indices)
        val choice = audioChoices[audioChoiceIndex]
        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
        if (audioChoiceIndex > 0) {
            choice.group?.let {
                builder.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, choice.trackIndex))
            }
        }
        player.trackSelectionParameters = builder.build()
        if (persist) {
            pendingAudioFingerprint = null
            mediaStateStore.saveAudioTrack(spec, choice.fingerprint)
        }
    }

    private fun stereoLabel() = when (currentStereoMode) {
        StereoMode.MONO -> "2D"
        StereoMode.SBS -> "Full SBS"
        StereoMode.HALF_SBS -> "Half SBS"
        StereoMode.OVER_UNDER -> "Full OU"
        StereoMode.HALF_OVER_UNDER -> "Half OU"
    }

    private fun projectionLabel(): String =
        if (currentProjection == VideoProjection.FLAT) {
            stereoLabel()
        } else {
            "${currentProjection.label} • ${stereoLabel()}"
        }

    private fun persistProjectionSettings() {
        mediaStateStore.saveProjection(
            spec,
            currentStereoMode,
            currentProjection,
            immersiveYawDegrees,
            immersivePitchDegrees,
        )
    }

    private fun showControls(durationMs: Long) {
        if (!mediaLoaded) {
            showLibrary()
            return
        }
        uiScreen = UiScreen.PLAYBACK
        stopTrackListScroll()
        playbackPopover = PlaybackPopover.NONE
        uiVisible = true
        hoveredButton = null
        armControlsAutoHide(durationMs)
        renderVrMenu()
        mainHandler.removeCallbacks(uiTicker)
        mainHandler.postDelayed(uiTicker, 1_000L)
    }

    private fun hideControls() {
        if (!mediaLoaded) return
        stopTrackListScroll()
        playbackPopover = PlaybackPopover.NONE
        uiVisible = false
        controlsAutoHideAtMs = 0L
        hoveredButton = null
        mainHandler.removeCallbacks(uiTicker)
        nativeSetMenuInteractive(false)
        applySubtitleCues(latestCues)
    }

    private fun togglePlaybackPopover(popover: PlaybackPopover) {
        if (!mediaLoaded) return
        stopTrackListScroll()
        uiScreen = UiScreen.PLAYBACK
        playbackPopover = if (playbackPopover == popover) {
            PlaybackPopover.NONE
        } else {
            popover
        }
        if (isTrackPopoverOpen()) ensureSelectedTrackVisible()
        armControlsAutoHide()
        hoveredButton = null
        renderVrMenu()
    }

    private fun armControlsAutoHide(durationMs: Long = PLAYBACK_AUTO_HIDE_MS) {
        val delay = if (durationMs > 0L) durationMs else PLAYBACK_AUTO_HIDE_MS
        controlsAutoHideAtMs = SystemClock.elapsedRealtime() + delay
    }

    private fun playbackPopoverBounds(popover: PlaybackPopover): RectF? = when (popover) {
        PlaybackPopover.SUBTITLES,
        PlaybackPopover.AUDIO -> PLAYBACK_LEFT_POPOVER_BOUNDS
        PlaybackPopover.PROJECTION -> PLAYBACK_PROJECTION_POPOVER_BOUNDS
        PlaybackPopover.SETTINGS,
        PlaybackPopover.CINEMA,
        PlaybackPopover.COLOR_PIPELINE,
        PlaybackPopover.ADAPTIVE_HDR,
        PlaybackPopover.RENDER_QUALITY -> PLAYBACK_SETTINGS_POPOVER_BOUNDS
        PlaybackPopover.NONE -> null
    }

    private fun showOsd(text: String, durationMs: Long = 3_000L) {
        transientMessage = text.replace('\n', ' ')
        if (mediaLoaded) showControls(0L) else showLibrary()
        val generation = ++osdGeneration
        mainHandler.postDelayed({
            if (osdGeneration == generation) {
                transientMessage = null
                if (uiVisible) renderVrMenu()
            }
        }, durationMs)
    }

    private fun openBrowserSearch() {
        if (uiScreen != UiScreen.LOCAL_FILES && uiScreen != UiScreen.SMB_FILES) return
        browserSortPopoverOpen = false
        browserSearchReturnScreen = uiScreen
        browserSearchDraft = browserSearchQuery
        keyboardField = KeyboardField.SEARCH
        keyboardSymbols = false
        keyboardUppercase = false
        uiScreen = UiScreen.FILE_SEARCH_KEYBOARD
        hoveredButton = null
        renderVrMenu()
    }

    private fun openBrowserSort() {
        if (uiScreen != UiScreen.LOCAL_FILES && uiScreen != UiScreen.SMB_FILES) return
        browserSortPopoverOpen = !browserSortPopoverOpen
        hoveredButton = null
        renderVrMenu()
    }

    private fun isBrowserSortControl(id: String): Boolean =
        id == "browser_sort" ||
            id.startsWith("browser_sort_field_") ||
            id == "browser_sort_ascending" ||
            id == "browser_sort_descending"

    private fun persistBrowserSort() {
        getSharedPreferences(BROWSER_PREFS, Context.MODE_PRIVATE).edit()
            .putInt(PREF_BROWSER_SORT_FIELD, browserSortField.ordinal)
            .putBoolean(PREF_BROWSER_SORT_ASCENDING, browserSortAscending)
            .apply()
    }

    private fun applyBrowserSortChange() {
        persistBrowserSort()
        rebuildBrowserItems()
        browserScrollOffset = 0f
        hoveredButton = null
        renderVrMenu()
    }

    private fun handleUiAction(id: String, pointerX: Float, persist: Boolean = true) {
        when {
            id.startsWith("recent_") ->
                playRecent(id.removePrefix("recent_").toInt())
            id == "open_local" -> openLocalBrowser()
            id == "open_smb" -> discoverSmbServers()
            id == "open_sftp" -> showSftpProfiles()
            id == "sftp_profiles_back" -> {
                sftpProfileListScrollOffset = browserScrollOffset
                showLibrary()
            }
            id == "sftp_add_profile" -> showSftpLogin()
            id.startsWith("sftp_profile_") -> {
                sftpProfileListScrollOffset = browserScrollOffset
                val profile =
                    sftpProfiles[id.removePrefix("sftp_profile_").toInt()]
                if (profile.password.isBlank()) {
                    showSftpLogin(
                        profile,
                        "Enter the password to connect",
                    )
                } else {
                    sftpProfile = profile
                    connectSftp()
                }
            }
            id.startsWith("sftp_edit_") -> {
                sftpProfileListScrollOffset = browserScrollOffset
                showSftpLogin(
                    sftpProfiles[id.removePrefix("sftp_edit_").toInt()]
                )
            }
            id.startsWith("sftp_field_") -> {
                keyboardField = when (id.removePrefix("sftp_field_")) {
                    "name" -> KeyboardField.SFTP_NAME
                    "port" -> KeyboardField.SFTP_PORT
                    "user" -> KeyboardField.SFTP_USERNAME
                    "password" -> KeyboardField.SFTP_PASSWORD
                    "path" -> KeyboardField.SFTP_PATH
                    else -> KeyboardField.SFTP_HOST
                }
                keyboardSymbols = false
                keyboardUppercase = false
                uiScreen = UiScreen.SFTP_KEYBOARD
                hoveredButton = null
                renderVrMenu()
            }
            id.startsWith("remote_protocol_") -> {
                val newProtocol = when (
                    id.removePrefix("remote_protocol_")
                ) {
                    "ftps" -> RemoteProtocol.FTPS
                    "ftp" -> RemoteProtocol.FTP
                    else -> RemoteProtocol.SFTP
                }
                val oldProtocol = sftpProfile.protocol
                val port = if (
                    sftpProfile.port == oldProtocol.defaultPort
                ) {
                    newProtocol.defaultPort
                } else {
                    sftpProfile.port
                }
                sftpProfile = sftpProfile.copy(
                    protocol = newProtocol,
                    port = port,
                    hostKeyFingerprint = if (
                        newProtocol == RemoteProtocol.SFTP &&
                        oldProtocol == RemoteProtocol.SFTP
                    ) {
                        sftpProfile.hostKeyFingerprint
                    } else {
                        ""
                    },
                    allowInsecureFtp = false,
                )
                sftpPortDraft = port.toString()
                transientMessage = null
                hoveredButton = null
                renderVrMenu()
            }
            id == "sftp_remember_password" -> {
                sftpProfile = sftpProfile.copy(
                    rememberPassword = !sftpProfile.rememberPassword,
                )
                renderVrMenu()
            }
            id == "sftp_connect" -> connectSftp()
            id == "ftp_warning_cancel" -> {
                uiScreen = UiScreen.SFTP_LOGIN
                hoveredButton = null
                renderVrMenu()
            }
            id == "ftp_warning_continue" -> {
                sftpProfile = sftpProfile.copy(allowInsecureFtp = true)
                connectSftp()
            }
            id == "sftp_login_back" -> showSftpProfiles()
            id == "sftp_delete" -> {
                uiScreen = UiScreen.SFTP_DELETE_CONFIRM
                transientMessage = null
                hoveredButton = null
                renderVrMenu()
            }
            id == "sftp_delete_cancel" -> {
                uiScreen = UiScreen.SFTP_LOGIN
                hoveredButton = null
                renderVrMenu()
            }
            id == "sftp_delete_confirm" -> {
                sftpProfiles =
                    sftpProfiles.filterNot { it.id == sftpProfile.id }
                sftpProfileStore.save(sftpProfiles)
                showSftpProfiles()
            }
            id == "sftp_trust_cancel" -> {
                pendingSftpHostKey = null
                uiScreen = UiScreen.SFTP_LOGIN
                transientMessage = "Server identity was not trusted"
                hoveredButton = null
                renderVrMenu()
            }
            id == "sftp_trust_accept" -> {
                val pending = pendingSftpHostKey
                if (pending != null) {
                    sftpProfile = sftpProfile.copy(
                        hostKeyFingerprint = pending.actualFingerprint,
                    )
                    pendingSftpHostKey = null
                    connectSftp()
                }
            }
            id == "sftp_files_back" -> {
                val parent = if (sftpNavigationStack.isEmpty()) null
                else sftpNavigationStack.removeLast()
                if (parent != null) {
                    sftpPath = parent.path
                    sftpEntries = parent.entries
                    browserScrollOffset = parent.scrollOffset
                    transientMessage = null
                    hoveredButton = null
                    renderVrMenu()
                } else {
                    showSftpProfiles()
                }
            }
            id.startsWith("sftp_entry_") -> {
                val entry =
                    sftpEntries[id.removePrefix("sftp_entry_").toInt()]
                if (entry.isDirectory) {
                    sftpNavigationStack.addLast(
                        SftpNavigationState(
                            sftpPath,
                            sftpEntries.toList(),
                            browserScrollOffset,
                        )
                    )
                    browseSftp(
                        entry.path,
                        rollbackNavigationOnFailure = true,
                    )
                } else {
                    loadMedia(
                        remotePlaybackSpec(entry),
                        BrowserReturnState.Remote(
                            profile = sftpProfile,
                            path = sftpPath,
                            entries = sftpEntries.toList(),
                            scrollOffset = browserScrollOffset,
                        ),
                    )
                }
            }
            id == "smb_add_server" -> showSmbLogin("", "")
            id == "smb_discover_again" -> discoverSmbServers()
            id == "smb_servers_back" -> showLibrary()
            id.startsWith("smb_server_") -> {
                val server = smbServers[id.removePrefix("smb_server_").toInt()]
                smbServerListScrollOffset = browserScrollOffset
                openSmbServer(server)
            }
            id.startsWith("smb_field_") -> {
                keyboardField = when (id.removePrefix("smb_field_")) {
                    "user" -> KeyboardField.USERNAME
                    "password" -> KeyboardField.PASSWORD
                    "domain" -> KeyboardField.DOMAIN
                    else -> KeyboardField.HOST
                }
                keyboardSymbols = false
                keyboardUppercase = false
                uiScreen = UiScreen.SMB_KEYBOARD
                hoveredButton = null
                renderVrMenu()
            }
            id == "smb_connect" -> connectSmb()
            id == "smb_remember" -> {
                rememberSmbCredentials = !rememberSmbCredentials
                if (!rememberSmbCredentials) smbCredentialStore.clear()
                renderVrMenu()
            }
            id == "smb_login_back" -> {
                uiScreen = UiScreen.SMB_SERVERS
                browserScrollOffset = smbServerListScrollOffset
                transientMessage = null
                hoveredButton = null
                renderVrMenu()
            }
            id.startsWith("smb_share_") -> {
                val share = smbShares[id.removePrefix("smb_share_").toInt()]
                smbShareListScrollOffset = browserScrollOffset
                smbCredentials = smbCredentials.copy(share = share)
                smbNavigationStack.clear()
                browserSearchQuery = ""
                browseSmb("")
            }
            id == "smb_shares_back" -> {
                smbShareListScrollOffset = browserScrollOffset
                uiScreen = UiScreen.SMB_SERVERS
                browserScrollOffset = smbServerListScrollOffset
                transientMessage = null
                hoveredButton = null
                renderVrMenu()
            }
            id == "smb_files_back" -> {
                val parent = if (smbNavigationStack.isEmpty()) null
                else smbNavigationStack.removeLast()
                if (parent != null) {
                    smbPath = parent.path
                    smbEntries = parent.entries
                    browserSearchQuery = parent.searchQuery
                    rebuildBrowserItems()
                    browserScrollOffset = parent.scrollOffset
                    transientMessage = null
                    hoveredButton = null
                    renderVrMenu()
                } else {
                    uiScreen = UiScreen.SMB_SHARES
                    browserScrollOffset = smbShareListScrollOffset
                    hoveredButton = null
                    renderVrMenu()
                }
            }
            id.startsWith("smb_entry_") -> {
                val entry = smbEntries[id.removePrefix("smb_entry_").toInt()]
                if (entry.isDirectory) {
                    smbNavigationStack.addLast(
                        SmbNavigationState(
                            smbPath,
                            smbEntries.toList(),
                            browserScrollOffset,
                            browserSearchQuery,
                        )
                    )
                    browseSmb(entry.path, rollbackNavigationOnFailure = true)
                } else {
                    loadMedia(
                        smbPlaybackSpec(entry),
                        BrowserReturnState.Smb(
                            credentials = smbCredentials,
                            path = smbPath,
                            entries = smbEntries.toList(),
                            scrollOffset = browserScrollOffset,
                            searchQuery = browserSearchQuery,
                        ),
                    )
                }
            }
            id == "key_symbols" -> {
                keyboardSymbols = !keyboardSymbols
                hoveredButton = null
                renderVrMenu()
            }
            id == "key_case" -> {
                keyboardUppercase = !keyboardUppercase
                hoveredButton = null
                renderVrMenu()
            }
            id == "key_backspace" -> {
                setKeyboardValue(keyboardValue().dropLast(1))
                renderVrMenu()
            }
            id == "key_clear" -> {
                setKeyboardValue("")
                renderVrMenu()
            }
            id == "key_space" -> {
                setKeyboardValue(keyboardValue() + " ")
                renderVrMenu()
            }
            id == "key_done" -> {
                if (uiScreen == UiScreen.FILE_SEARCH_KEYBOARD) {
                    browserSearchQuery = browserSearchDraft.trim()
                    rebuildBrowserItems()
                    browserScrollOffset = 0f
                    uiScreen = browserSearchReturnScreen
                } else if (uiScreen == UiScreen.SFTP_KEYBOARD) {
                    uiScreen = UiScreen.SFTP_LOGIN
                } else {
                    uiScreen = UiScreen.SMB_LOGIN
                }
                hoveredButton = null
                renderVrMenu()
            }
            id == "key_cancel" -> {
                uiScreen = if (uiScreen == UiScreen.FILE_SEARCH_KEYBOARD) {
                    browserSearchReturnScreen
                } else if (uiScreen == UiScreen.SFTP_KEYBOARD) {
                    UiScreen.SFTP_LOGIN
                } else {
                    UiScreen.SMB_LOGIN
                }
                hoveredButton = null
                renderVrMenu()
            }
            id.startsWith("key_char_") -> {
                val character = id.removePrefix("key_char_").toInt().toChar()
                val maximumLength = when (keyboardField) {
                    KeyboardField.SFTP_PATH,
                    KeyboardField.SFTP_PASSWORD -> 256
                    else -> 64
                }
                if (keyboardValue().length < maximumLength) {
                    setKeyboardValue(keyboardValue() + character)
                }
                renderVrMenu()
            }
            id == "exit" -> finish()
            id == "browser_search" -> openBrowserSearch()
            id == "browser_sort" -> openBrowserSort()
            id.startsWith("browser_sort_field_") -> {
                browserSortField = when (id.removePrefix("browser_sort_field_")) {
                    "date" -> BrowserSortField.DATE
                    "duration" -> BrowserSortField.DURATION
                    "recent" -> BrowserSortField.RECENT
                    else -> BrowserSortField.NAME
                }
                applyBrowserSortChange()
            }
            id == "browser_sort_ascending" || id == "browser_sort_descending" -> {
                browserSortAscending = id == "browser_sort_ascending"
                applyBrowserSortChange()
            }
            id == "local_back" -> {
                if (uiScreen == UiScreen.LOCAL_FILES) {
                    uiScreen = UiScreen.LOCAL_FOLDERS
                    localFolder = null
                    browserSearchQuery = ""
                    rebuildBrowserItems()
                    browserScrollOffset = localFolderListScrollOffset
                    hoveredButton = null
                    renderVrMenu()
                } else showLibrary()
            }
            id.startsWith("folder_") -> {
                val folders = localVideos.map { it.folder }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
                localFolderListScrollOffset = browserScrollOffset
                localFolder = folders[id.removePrefix("folder_").toInt()]
                browserSearchQuery = ""
                rebuildBrowserItems()
                uiScreen = UiScreen.LOCAL_FILES
                browserScrollOffset = 0f
                hoveredButton = null
                renderVrMenu()
            }
            id.startsWith("video_") -> {
                val video = localVideos[id.removePrefix("video_").toInt()]
                loadMedia(
                    localPlaybackSpec(video),
                    BrowserReturnState.Local(
                        folder = requireNotNull(localFolder),
                        scrollOffset = browserScrollOffset,
                        searchQuery = browserSearchQuery,
                    ),
                )
            }
            id == "back_library" -> {
                if (mediaLoaded) {
                    releaseCurrentPlayer(saveState = true)
                }
                returnToSourceBrowser()
            }
            id == "seek_back" -> {
                player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                renderVrMenu()
            }
            id == "seek_forward" -> {
                player.seekTo((player.currentPosition + 10_000L).coerceAtMost(player.duration.coerceAtLeast(0L)))
                renderVrMenu()
            }
            id == "play_pause" -> {
                if (player.isPlaying) player.pause() else player.play()
                renderVrMenu()
            }
            id == "projection" -> {
                togglePlaybackPopover(PlaybackPopover.PROJECTION)
            }
            id == "picture" -> {
                uiScreen = UiScreen.PICTURE
                hoveredButton = null
                renderVrMenu()
            }
            id == "decoder" -> {
                uiScreen = UiScreen.DECODER
                hoveredButton = null
                renderVrMenu()
            }
            id == "audio_sync" -> {
                uiScreen = UiScreen.AUDIO_SYNC
                hoveredButton = null
                renderVrMenu()
            }
            id == "audio_tracks" -> {
                uiScreen = UiScreen.AUDIO_TRACKS
                hoveredButton = null
                renderVrMenu()
            }
            id == "audio" -> {
                togglePlaybackPopover(PlaybackPopover.AUDIO)
            }
            id == "subtitles" -> {
                togglePlaybackPopover(PlaybackPopover.SUBTITLES)
            }
            id == "settings" -> {
                togglePlaybackPopover(PlaybackPopover.SETTINGS)
            }
            id == "cinema_tune" -> {
                playbackPopover = PlaybackPopover.CINEMA
                hoveredButton = null
                renderVrMenu()
            }
            id == "cinema_back" -> {
                playbackPopover = PlaybackPopover.SETTINGS
                hoveredButton = null
                renderVrMenu()
            }
            id == "color_pipeline" -> {
                playbackPopover = PlaybackPopover.COLOR_PIPELINE
                hoveredButton = null
                renderVrMenu()
            }
            id == "color_pipeline_back" -> {
                playbackPopover = PlaybackPopover.SETTINGS
                hoveredButton = null
                renderVrMenu()
            }
            id == "adaptive_hdr" -> {
                playbackPopover = PlaybackPopover.ADAPTIVE_HDR
                hoveredButton = null
                renderVrMenu()
            }
            id == "adaptive_hdr_back" -> {
                playbackPopover = PlaybackPopover.SETTINGS
                hoveredButton = null
                renderVrMenu()
            }
            id == "adaptive_hdr_off" || id == "adaptive_hdr_on" -> {
                adaptiveToneMapEnabled = id == "adaptive_hdr_on"
                applyAdaptiveToneMapping()
                renderVrMenu()
            }
            id == "adaptive_hdr_cinema" || id == "adaptive_hdr_smooth" -> {
                adaptiveToneMapMode = if (id == "adaptive_hdr_cinema") {
                    AdaptiveToneMapMode.CINEMA
                } else {
                    AdaptiveToneMapMode.SMOOTH
                }
                applyAdaptiveToneMapping()
                renderVrMenu()
            }
            id == "render_quality" -> {
                playbackPopover = PlaybackPopover.RENDER_QUALITY
                hoveredButton = null
                renderVrMenu()
            }
            id == "render_quality_back" -> {
                playbackPopover = PlaybackPopover.SETTINGS
                hoveredButton = null
                renderVrMenu()
            }
            id == "settings_picture" -> {
                playbackPopover = PlaybackPopover.NONE
                uiScreen = UiScreen.PICTURE
                hoveredButton = null
                renderVrMenu()
            }
            id == "settings_decoder" -> {
                playbackPopover = PlaybackPopover.NONE
                uiScreen = UiScreen.DECODER
                hoveredButton = null
                renderVrMenu()
            }
            id == "popover_audio_sync" -> {
                playbackPopover = PlaybackPopover.NONE
                uiScreen = UiScreen.AUDIO_SYNC
                hoveredButton = null
                renderVrMenu()
            }
            id == "subtitle_depth_slider" -> {
                val fraction = (
                    (pointerX - SUBTITLE_DEPTH_SLIDER_LEFT) /
                        (SUBTITLE_DEPTH_SLIDER_RIGHT - SUBTITLE_DEPTH_SLIDER_LEFT)
                ).coerceIn(0f, 1f)
                if (!fraction.isFinite()) return
                setSubtitleDepth(
                    lerp(
                        SUBTITLE_DEPTH_MIN_METERS,
                        SUBTITLE_DEPTH_MAX_METERS,
                        fraction,
                    ),
                    persist,
                )
                renderVrMenu()
            }
            id == "subtitle_3d_enabled" -> {
                subtitle3dEnabled = !subtitle3dEnabled
                nativeSetSubtitle3dEnabled(subtitle3dEnabled)
                mediaStateStore.saveSubtitle3dEnabled(spec, subtitle3dEnabled)
                if (!uiVisible) applySubtitleCues(latestCues)
                renderVrMenu()
            }
            id.startsWith("subtitle_sync_") -> {
                val delta = when (id) {
                    "subtitle_sync_earlier_500" -> -500
                    "subtitle_sync_earlier_100" -> -100
                    "subtitle_sync_later_100" -> 100
                    "subtitle_sync_later_500" -> 500
                    else -> -subtitleSyncMs
                }
                setSubtitleSync(subtitleSyncMs + delta)
                renderVrMenu()
            }
            id == "screen_curvature_slider" -> {
                val fraction = (
                    (pointerX - SCREEN_CURVATURE_SLIDER_LEFT) /
                        (SCREEN_CURVATURE_SLIDER_RIGHT - SCREEN_CURVATURE_SLIDER_LEFT)
                ).coerceIn(0f, 1f)
                if (!fraction.isFinite()) return
                setScreenCurvature(
                    lerp(SCREEN_CURVATURE_MIN, SCREEN_CURVATURE_MAX, fraction),
                    persist,
                )
                renderVrMenu()
            }
            id == "seek_bar" -> {
                val duration = player.duration
                if (duration > 0) {
                    val fraction = ((pointerX - PLAYBACK_SEEK_LEFT) /
                        (PLAYBACK_SEEK_RIGHT - PLAYBACK_SEEK_LEFT)).coerceIn(0f, 1f)
                    player.seekTo((duration * fraction).toLong())
                    renderVrMenu()
                }
            }
            id.startsWith("mode_") -> {
                currentStereoMode = when (id) {
                    "mode_full_sbs" -> StereoMode.SBS
                    "mode_half_sbs" -> StereoMode.HALF_SBS
                    "mode_full_ou" -> StereoMode.OVER_UNDER
                    "mode_half_ou" -> StereoMode.HALF_OVER_UNDER
                    else -> StereoMode.MONO
                }
                nativeSetStereoMode(currentStereoMode.wireValue)
                applyDefaultSubtitleDepthForProjection()
                persistProjectionSettings()
                updateVideoAspect()
                renderVrMenu()
            }
            id.startsWith("projection_view_") -> {
                currentProjection = when (id) {
                    "projection_view_180" ->
                        VideoProjection.EQUIRECTANGULAR_180
                    "projection_view_360" ->
                        VideoProjection.EQUIRECTANGULAR_360
                    else -> VideoProjection.FLAT
                }
                nativeSetVideoProjection(currentProjection.wireValue)
                if (currentProjection != VideoProjection.FLAT) {
                    nativeRecenterImmersive()
                    immersiveYawDegrees = nativeGetImmersiveYaw()
                    immersivePitchDegrees = nativeGetImmersivePitch()
                }
                persistProjectionSettings()
                renderVrMenu()
            }
            id == "immersive_recenter" -> {
                nativeRecenterImmersive()
                immersiveYawDegrees = nativeGetImmersiveYaw()
                immersivePitchDegrees = nativeGetImmersivePitch()
                persistProjectionSettings()
                renderVrMenu()
            }
            id.startsWith("immersive_yaw_") -> {
                immersiveYawDegrees = (
                    immersiveYawDegrees +
                        if (id.endsWith("left")) -15f else 15f
                    ).coerceIn(-180f, 180f)
                nativeSetImmersiveOrientation(
                    immersiveYawDegrees,
                    immersivePitchDegrees,
                )
                persistProjectionSettings()
                renderVrMenu()
            }
            id.startsWith("immersive_pitch_") -> {
                immersivePitchDegrees = (
                    immersivePitchDegrees +
                        if (id.endsWith("down")) -10f else 10f
                    ).coerceIn(-80f, 80f)
                nativeSetImmersiveOrientation(
                    immersiveYawDegrees,
                    immersivePitchDegrees,
                )
                persistProjectionSettings()
                renderVrMenu()
            }
            id == "swap_eyes" -> {
                swapEyes = !swapEyes
                nativeSetSwapEyes(swapEyes)
                renderVrMenu()
            }
            id.startsWith("hdr_metadata_") -> {
                hdrMetadataMode = when (id) {
                    "hdr_metadata_ignore" -> HdrMetadataMode.IGNORE
                    "hdr_metadata_manual" -> HdrMetadataMode.MANUAL
                    else -> HdrMetadataMode.AUTO
                }
                persistHdrMetadataSettings()
                applyVideoColorPipeline()
                renderVrMenu()
            }
            id.startsWith("gamut_mapping_") -> {
                gamutMappingMode = if (id == "gamut_mapping_clip") {
                    GamutMappingMode.CLIP
                } else {
                    GamutMappingMode.PERCEPTUAL
                }
                getSharedPreferences(PICTURE_PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(PREF_GAMUT_MAPPING_MODE, gamutMappingMode.wireValue)
                    .apply()
                applyVideoColorPipeline()
                renderVrMenu()
            }
            id == "settings_slider_manual_peak" -> {
                val fraction = (
                    (pointerX - SETTINGS_SLIDER_LEFT) /
                        (SETTINGS_SLIDER_RIGHT - SETTINGS_SLIDER_LEFT)
                ).coerceIn(0f, 1f)
                // coerceIn() does not sanitize NaN (comparisons against NaN
                // are always false, so a NaN fraction passes through
                // unchanged). A non-finite pointerX must never turn into a
                // persisted, permanently-applied HDR setting.
                if (!fraction.isFinite()) return
                hdrManualPeakNits = logarithmicLerp(
                    HDR_SOURCE_PEAK_NITS_MIN,
                    HDR_SOURCE_PEAK_NITS_MAX,
                    fraction,
                )
                if (persist) persistHdrMetadataSettings()
                applyVideoColorPipeline()
                renderVrMenu()
            }
            id.startsWith("picture_mode_") -> {
                val selectedMode = when (id) {
                    "picture_mode_original" -> PictureMode.ORIGINAL
                    "picture_mode_sdr" -> PictureMode.FORCE_SDR
                    "picture_mode_pq" -> PictureMode.FORCE_PQ
                    "picture_mode_hlg" -> PictureMode.FORCE_HLG
                    else -> PictureMode.AUTO
                }
                if (
                    (selectedMode == PictureMode.FORCE_PQ ||
                        selectedMode == PictureMode.FORCE_HLG) &&
                    !currentMediaHasSavedPictureSettings &&
                    !hdrDefaultExposureApplied
                ) {
                    pictureExposure = DEFAULT_HDR_EXPOSURE
                    hdrDefaultExposureApplied = true
                }
                pictureMode = selectedMode
                applyPictureSettings()
                applyCinemaSettings(persist = false)
                renderVrMenu()
            }
            id.startsWith("picture_slider_") || id.startsWith("settings_slider_") -> {
                val inSettingsPopover = id.startsWith("settings_slider_")
                val sliderLeft =
                    if (inSettingsPopover) SETTINGS_SLIDER_LEFT else PICTURE_SLIDER_LEFT
                val sliderRight =
                    if (inSettingsPopover) SETTINGS_SLIDER_RIGHT else PICTURE_SLIDER_RIGHT
                val fraction =
                    ((pointerX - sliderLeft) / (sliderRight - sliderLeft)).coerceIn(0f, 1f)
                // See the manual_peak branch above: coerceIn() lets NaN
                // through unchanged, so guard explicitly before it can be
                // lerped into a picture/cinema value and persisted.
                if (!fraction.isFinite()) return
                val slider = id.substringAfter("slider_")
                val cinemaTuning = activeCinemaTuning()
                when (slider) {
                    "brightness" -> pictureBrightness =
                        lerp(PICTURE_BRIGHTNESS_MIN, PICTURE_BRIGHTNESS_MAX, fraction)
                    "exposure" -> pictureExposure =
                        lerp(PICTURE_EXPOSURE_MIN, PICTURE_EXPOSURE_MAX, fraction)
                    "contrast" -> pictureContrast =
                        lerp(PICTURE_CONTRAST_MIN, PICTURE_CONTRAST_MAX, fraction)
                    "saturation" -> pictureSaturation =
                        lerp(PICTURE_SATURATION_MIN, PICTURE_SATURATION_MAX, fraction)
                    "vibrance" -> pictureVibrance =
                        lerp(PICTURE_VIBRANCE_MIN, PICTURE_VIBRANCE_MAX, fraction)
                    "tone_depth" -> cinemaTuning.toneDepth =
                        lerp(CINEMA_TONE_DEPTH_MIN, CINEMA_TONE_DEPTH_MAX, fraction)
                    "midtones" -> cinemaTuning.midtoneEv =
                        lerp(CINEMA_MIDTONE_EV_MIN, CINEMA_MIDTONE_EV_MAX, fraction)
                    "color_intensity" -> cinemaTuning.colorIntensity =
                        lerp(
                            CINEMA_COLOR_INTENSITY_MIN,
                            CINEMA_COLOR_INTENSITY_MAX,
                            fraction,
                        )
                    "tone_peak" -> {
                        toneMapPeakNits = logarithmicLerp(
                            TONE_MAP_PEAK_NITS_MIN,
                            TONE_MAP_PEAK_NITS_MAX,
                            fraction,
                        )
                        if (persist) {
                            getSharedPreferences(PICTURE_PREFS, Context.MODE_PRIVATE).edit()
                                .putFloat(PREF_TONE_MAP_PEAK_NITS, toneMapPeakNits)
                                .apply()
                        }
                    }
                    "adaptive_min" -> {
                        adaptiveToneMapMinNits = lerp(
                            ADAPTIVE_TONE_MAP_NITS_MIN,
                            ADAPTIVE_TONE_MAP_NITS_MAX,
                            fraction,
                        )
                        if (adaptiveToneMapMinNits > adaptiveToneMapMaxNits) {
                            adaptiveToneMapMaxNits = adaptiveToneMapMinNits
                        }
                    }
                    "adaptive_max" -> {
                        adaptiveToneMapMaxNits = lerp(
                            ADAPTIVE_TONE_MAP_NITS_MIN,
                            ADAPTIVE_TONE_MAP_NITS_MAX,
                            fraction,
                        )
                        if (adaptiveToneMapMaxNits < adaptiveToneMapMinNits) {
                            adaptiveToneMapMinNits = adaptiveToneMapMaxNits
                        }
                    }
                    "tone_priority" -> {
                        adaptiveToneMapPriority = lerp(
                            ADAPTIVE_TONE_MAP_PRIORITY_MIN,
                            ADAPTIVE_TONE_MAP_PRIORITY_MAX,
                            fraction,
                        )
                    }
                    "scene" -> {
                        sceneBrightness = lerp(SCENE_BRIGHTNESS_MIN, SCENE_BRIGHTNESS_MAX, fraction)
                        if (persist) {
                            getSharedPreferences(PICTURE_PREFS, Context.MODE_PRIVATE).edit()
                                .putFloat(PREF_SCENE_BRIGHTNESS, sceneBrightness)
                                .apply()
                        }
                    }
                    "resolution_scale" -> {
                        val unsnapped = lerp(
                            RENDER_RESOLUTION_SCALE_MIN,
                            RENDER_RESOLUTION_SCALE_MAX,
                            fraction,
                        )
                        renderResolutionScale = (
                            kotlin.math.round(unsnapped / RENDER_RESOLUTION_SCALE_STEP) *
                                RENDER_RESOLUTION_SCALE_STEP
                            ).coerceIn(
                                RENDER_RESOLUTION_SCALE_MIN,
                                RENDER_RESOLUTION_SCALE_MAX,
                            )
                        if (persist) {
                            getSharedPreferences(PICTURE_PREFS, Context.MODE_PRIVATE).edit()
                                .putFloat(PREF_RENDER_RESOLUTION_SCALE, renderResolutionScale)
                                .apply()
                        }
                    }
                }
                val cinemaSlider =
                    slider == "tone_depth" ||
                        slider == "midtones" ||
                        slider == "color_intensity"
                val globalOnlySlider =
                    slider == "scene" ||
                        slider == "tone_peak" ||
                        slider == "adaptive_min" ||
                        slider == "adaptive_max" ||
                        slider == "tone_priority" ||
                        slider == "resolution_scale"
                val adaptiveSlider =
                    slider == "adaptive_min" ||
                        slider == "adaptive_max" ||
                        slider == "tone_priority"
                if (cinemaSlider) {
                    applyCinemaSettings(persist)
                } else if (adaptiveSlider) {
                    applyAdaptiveToneMapping(persist)
                } else if (slider == "resolution_scale") {
                    // The panel already explains that restart is required. Keep it open so
                    // the user can continue dragging or compare nearby scale values.
                } else {
                    applyPictureSettings(persist && !globalOnlySlider)
                }
                renderVrMenu()
            }
            id.startsWith("picture_reset_") -> {
                val slider = id.substringAfter("picture_reset_")
                val cinemaTuning = activeCinemaTuning()
                when (slider) {
                    "brightness" -> pictureBrightness = DEFAULT_PICTURE_BRIGHTNESS
                    "exposure" -> pictureExposure = defaultExposureForCurrentColorMode()
                    "contrast" -> pictureContrast = DEFAULT_PICTURE_CONTRAST
                    "saturation" -> pictureSaturation = DEFAULT_PICTURE_SATURATION
                    "vibrance" -> pictureVibrance = DEFAULT_PICTURE_VIBRANCE
                    "tone_depth" ->
                        cinemaTuning.toneDepth =
                            defaultCinemaTuning(activeCinemaTransfer()).toneDepth
                    "midtones" -> cinemaTuning.midtoneEv = DEFAULT_CINEMA_MIDTONE_EV
                    "color_intensity" ->
                        cinemaTuning.colorIntensity = DEFAULT_CINEMA_COLOR_INTENSITY
                    "tone_peak" -> {
                        toneMapPeakNits = DEFAULT_TONE_MAP_PEAK_NITS
                        getSharedPreferences(PICTURE_PREFS, Context.MODE_PRIVATE).edit()
                            .putFloat(PREF_TONE_MAP_PEAK_NITS, toneMapPeakNits)
                            .apply()
                    }
                    "adaptive_min" -> {
                        adaptiveToneMapMinNits = DEFAULT_ADAPTIVE_TONE_MAP_MIN_NITS
                        adaptiveToneMapMaxNits =
                            adaptiveToneMapMaxNits.coerceAtLeast(adaptiveToneMapMinNits)
                    }
                    "adaptive_max" -> {
                        adaptiveToneMapMaxNits = DEFAULT_ADAPTIVE_TONE_MAP_MAX_NITS
                        adaptiveToneMapMinNits =
                            adaptiveToneMapMinNits.coerceAtMost(adaptiveToneMapMaxNits)
                    }
                    "tone_priority" -> {
                        adaptiveToneMapPriority = DEFAULT_ADAPTIVE_TONE_MAP_PRIORITY
                    }
                    "manual_peak" -> {
                        hdrManualPeakNits = DEFAULT_HDR_SOURCE_PEAK_NITS
                        persistHdrMetadataSettings()
                    }
                    "scene" -> {
                        sceneBrightness = DEFAULT_SCENE_BRIGHTNESS
                        getSharedPreferences(PICTURE_PREFS, Context.MODE_PRIVATE).edit()
                            .putFloat(PREF_SCENE_BRIGHTNESS, sceneBrightness)
                            .apply()
                    }
                    "resolution_scale" -> {
                        renderResolutionScale = DEFAULT_RENDER_RESOLUTION_SCALE
                        getSharedPreferences(PICTURE_PREFS, Context.MODE_PRIVATE).edit()
                            .putFloat(PREF_RENDER_RESOLUTION_SCALE, renderResolutionScale)
                            .apply()
                    }
                }
                if (slider == "exposure") {
                    hdrDefaultExposureApplied =
                        pictureExposure == DEFAULT_HDR_EXPOSURE &&
                            currentColorModeIsHdr()
                }
                val cinemaSlider =
                    slider == "tone_depth" ||
                        slider == "midtones" ||
                        slider == "color_intensity"
                if (slider == "manual_peak") {
                    applyVideoColorPipeline()
                } else if (
                    slider == "adaptive_min" ||
                    slider == "adaptive_max" ||
                    slider == "tone_priority"
                ) {
                    applyAdaptiveToneMapping()
                } else if (cinemaSlider) {
                    applyCinemaSettings()
                } else if (slider == "resolution_scale") {
                    // Keep the render-quality panel open after resetting this slider.
                } else {
                    applyPictureSettings(
                        persist = slider != "scene" && slider != "tone_peak"
                    )
                }
                renderVrMenu()
            }
            id == "cinema_reset" -> {
                val transfer = activeCinemaTransfer()
                cinemaTunings[transfer] = defaultCinemaTuning(transfer)
                applyCinemaSettings()
                renderVrMenu()
            }
            id == "picture_reset" -> {
                pictureBrightness = DEFAULT_PICTURE_BRIGHTNESS
                pictureExposure = defaultExposureForCurrentColorMode()
                hdrDefaultExposureApplied =
                    pictureExposure == DEFAULT_HDR_EXPOSURE &&
                        currentColorModeIsHdr()
                pictureContrast = DEFAULT_PICTURE_CONTRAST
                pictureSaturation = DEFAULT_PICTURE_SATURATION
                pictureVibrance = DEFAULT_PICTURE_VIBRANCE
                applyPictureSettings()
                renderVrMenu()
            }
            id.startsWith("decoder_") -> {
                val selectedMode = when (id) {
                    "decoder_hardware" -> DecoderMode.HARDWARE
                    "decoder_software" -> DecoderMode.SOFTWARE
                    else -> DecoderMode.AUTO
                }
                val requestedEffectiveMode = when (selectedMode) {
                    DecoderMode.SOFTWARE -> DecoderMode.SOFTWARE
                    DecoderMode.AUTO,
                    DecoderMode.HARDWARE -> DecoderMode.HARDWARE
                }
                val position = player.currentPosition
                decoderMode = selectedMode
                mediaStateStore.saveDecoderMode(spec, selectedMode.wireValue)
                if (selectedMode == DecoderMode.AUTO) {
                    // Explicitly choosing Auto asks it to probe hardware again.
                    mediaStateStore.saveDecoderAutoSoftware(spec, false)
                }
                if (effectiveDecoderMode == requestedEffectiveMode) {
                    renderVrMenu()
                    showOsd("Decoder: ${selectedMode.label}", 3_000L)
                    return
                }
                loadMedia(
                    spec,
                    playbackBrowserReturn,
                    resumeOverrideMs = position,
                    autoSoftwareFallback = false,
                )
                showOsd("Decoder: ${selectedMode.label}", 3_000L)
            }
            id.startsWith("sync_") -> {
                val delta = when (id) {
                    "sync_earlier_100" -> -100
                    "sync_earlier_25" -> -25
                    "sync_later_25" -> 25
                    "sync_later_100" -> 100
                    else -> -audioSyncMs
                }
                setAudioSync(audioSyncMs + delta)
                renderVrMenu()
            }
            id.startsWith("subtitle_") -> {
                applySubtitle(id.removePrefix("subtitle_").toInt())
                ensureSelectedTrackVisible()
                renderVrMenu()
            }
            id.startsWith("audio_track_") -> {
                applyAudioTrack(id.removePrefix("audio_track_").toInt())
                ensureSelectedTrackVisible()
                renderVrMenu()
            }
            id == "modal_back" -> showControls(0L)
        }
    }

    private fun setAudioSync(valueMs: Int) {
        val adjusted = valueMs.coerceIn(
            -AudioSyncAudioProcessor.MAX_DELAY_MS,
            AudioSyncAudioProcessor.MAX_DELAY_MS,
        )
        if (adjusted == audioSyncMs) return
        audioSyncMs = adjusted
        audioSyncProcessor.setDelayMs(adjusted)
        mediaStateStore.saveAudioSync(spec, adjusted)
        // Re-seeking flushes the PCM processor so the new offset starts at a
        // clean media boundary instead of changing halfway through a buffer.
        val position = player.currentPosition
        player.seekTo(position)
        Log.i("DreamPlayerAudio", "Audio sync set to ${adjusted}ms")
    }

    private fun setSubtitleSync(valueMs: Int) {
        val adjusted = valueMs.coerceIn(-MAX_SUBTITLE_SYNC_MS, MAX_SUBTITLE_SYNC_MS)
        if (adjusted == subtitleSyncMs) return
        subtitleSyncMs = adjusted
        player.subtitleDelayMilliseconds = adjusted.toLong()
        mediaStateStore.saveSubtitleSync(spec, adjusted)
        // Re-evaluate the currently active cue immediately, including when advancing it.
        player.seekTo(player.currentPosition)
        Log.i("DreamPlayerSubtitle", "Subtitle sync set to ${adjusted}ms")
    }

    private fun defaultSubtitleDepth(mode: StereoMode): Float =
        if (mode == StereoMode.MONO) {
            DEFAULT_2D_SUBTITLE_DEPTH_METERS
        } else {
            DEFAULT_3D_SUBTITLE_DEPTH_METERS
        }

    private fun applyDefaultSubtitleDepthForProjection() {
        if (subtitleDepthIsCustom) return
        subtitleDepthMeters = defaultSubtitleDepth(currentStereoMode)
        nativeSetSubtitleDepth(subtitleDepthMeters)
    }

    private fun setSubtitleDepth(valueMeters: Float, persist: Boolean) {
        val adjusted =
            valueMeters.coerceIn(SUBTITLE_DEPTH_MIN_METERS, SUBTITLE_DEPTH_MAX_METERS)
        subtitleDepthMeters = adjusted
        nativeSetSubtitleDepth(adjusted)
        if (persist) {
            subtitleDepthIsCustom = true
            mediaStateStore.saveSubtitleDepth(spec, adjusted)
            Log.i("DreamPlayerSubtitle", "Subtitle depth set to ${adjusted}m")
        }
    }

    private fun setScreenCurvature(value: Float, persist: Boolean) {
        val adjusted = value.coerceIn(SCREEN_CURVATURE_MIN, SCREEN_CURVATURE_MAX)
        screenCurvature = adjusted
        nativeSetScreenCurvature(adjusted)
        if (persist) {
            mediaStateStore.saveScreenCurvature(spec, adjusted)
            Log.i("DreamPlayerScreen", "Screen curvature set to $adjusted")
        }
    }

    private fun keyboardValue(): String = when (keyboardField) {
        KeyboardField.HOST -> smbCredentials.host
        KeyboardField.USERNAME -> smbCredentials.username
        KeyboardField.PASSWORD -> smbCredentials.password
        KeyboardField.DOMAIN -> smbCredentials.domain
        KeyboardField.SEARCH -> browserSearchDraft
        KeyboardField.SFTP_NAME -> sftpProfile.name
        KeyboardField.SFTP_HOST -> sftpProfile.host
        KeyboardField.SFTP_PORT -> sftpPortDraft
        KeyboardField.SFTP_USERNAME -> sftpProfile.username
        KeyboardField.SFTP_PASSWORD -> sftpProfile.password
        KeyboardField.SFTP_PATH -> sftpProfile.startPath
    }

    private fun setKeyboardValue(value: String) {
        smbCredentials = when (keyboardField) {
            KeyboardField.HOST -> smbCredentials.copy(host = value, connectionHost = value)
            KeyboardField.USERNAME -> smbCredentials.copy(username = value)
            KeyboardField.PASSWORD -> smbCredentials.copy(password = value)
            KeyboardField.DOMAIN -> smbCredentials.copy(domain = value)
            KeyboardField.SEARCH -> {
                browserSearchDraft = value
                smbCredentials
            }
            else -> smbCredentials
        }
        sftpProfile = when (keyboardField) {
            KeyboardField.SFTP_NAME -> sftpProfile.copy(name = value)
            KeyboardField.SFTP_HOST -> sftpProfile.copy(
                host = value,
                hostKeyFingerprint = if (
                    value.trim().equals(
                        sftpProfile.host.trim(),
                        ignoreCase = true,
                    )
                ) {
                    sftpProfile.hostKeyFingerprint
                } else {
                    ""
                },
            )
            KeyboardField.SFTP_PORT -> {
                sftpPortDraft = value.filter(Char::isDigit).take(5)
                val adjustedPort = sftpPortDraft.toIntOrNull()
                    ?.coerceIn(1, 65_535)
                    ?: sftpProfile.port
                sftpProfile.copy(
                    port = adjustedPort,
                    hostKeyFingerprint = if (
                        adjustedPort == sftpProfile.port
                    ) {
                        sftpProfile.hostKeyFingerprint
                    } else {
                        ""
                    },
                )
            }
            KeyboardField.SFTP_USERNAME ->
                sftpProfile.copy(username = value)
            KeyboardField.SFTP_PASSWORD ->
                sftpProfile.copy(password = value)
            KeyboardField.SFTP_PATH -> sftpProfile.copy(startPath = value)
            else -> sftpProfile
        }
    }

    private fun keyboardTitle(): String = when (keyboardField) {
        KeyboardField.HOST -> "Server hostname or IP"
        KeyboardField.USERNAME -> "Username"
        KeyboardField.PASSWORD -> "Password"
        KeyboardField.DOMAIN -> "Domain (usually blank)"
        KeyboardField.SEARCH -> "Search file names"
        KeyboardField.SFTP_NAME -> "Profile name"
        KeyboardField.SFTP_HOST ->
            "${sftpProfile.protocol.label} hostname or IP"
        KeyboardField.SFTP_PORT -> "${sftpProfile.protocol.label} port"
        KeyboardField.SFTP_USERNAME ->
            "${sftpProfile.protocol.label} username"
        KeyboardField.SFTP_PASSWORD ->
            "${sftpProfile.protocol.label} password"
        KeyboardField.SFTP_PATH -> "Starting folder"
    }

    private fun renderSubtitle(lines: List<String>): Bitmap {
        val bitmap = Bitmap.createBitmap(2048, 320, Bitmap.Config.ARGB_8888)
        if (lines.isEmpty()) return bitmap
        val canvas = Canvas(bitmap)
        val text = lines.joinToString("\n")
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 68f
        }
        val split = text.split('\n').take(3)
        val firstY = 160f - (split.size - 1) * 44f
        split.forEachIndexed { index, line ->
            val y = firstY + index * 88f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 10f
            paint.color = Color.BLACK
            canvas.drawText(line.take(90), 1024f, y, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawText(line.take(90), 1024f, y, paint)
        }
        return bitmap
    }

    private fun applySubtitleCues(cues: List<Cue>) {
        val bitmapCues = cues.filter { it.bitmap != null }
        if (bitmapCues.isEmpty()) {
            nativeSetSubtitle(
                renderSubtitle(
                    cues.mapNotNull { it.text?.toString() }.filter { it.isNotBlank() }
                )
            )
            return
        }

        val layouts = bitmapCues.mapNotNull { cue ->
            val source = cue.bitmap ?: return@mapNotNull null
            val widthFraction = cue.size
                .takeUnless { it == Cue.DIMEN_UNSET || !it.isFinite() || it <= 0f }
                ?: (source.width.toFloat() / PGS_REFERENCE_WIDTH)
            val heightFraction = cue.bitmapHeight
                .takeUnless { it == Cue.DIMEN_UNSET || !it.isFinite() || it <= 0f }
                ?: (
                    widthFraction * source.height /
                        source.width.coerceAtLeast(1).toFloat() *
                        PGS_REFERENCE_WIDTH / PGS_REFERENCE_HEIGHT
                )

            val position = cue.position
                .takeUnless { it == Cue.DIMEN_UNSET || !it.isFinite() }
                ?: 0.5f
            val line = cue.line
                .takeUnless {
                    it == Cue.DIMEN_UNSET || !it.isFinite() ||
                        cue.lineType != Cue.LINE_TYPE_FRACTION
                }
                ?: 0.82f
            val left = anchorStart(
                position,
                widthFraction,
                cue.positionAnchor,
            )
            val top = anchorStart(
                line,
                heightFraction,
                cue.lineAnchor,
            )
            BitmapCueLayout(
                source,
                RectF(left, top, left + widthFraction, top + heightFraction),
            )
        }

        if (layouts.isEmpty()) {
            nativeSetSubtitle(renderSubtitle(emptyList()))
            return
        }

        // PGS usually contains one already-cropped bitmap. Upload it directly instead
        // of expanding every cue into a 1920x1080 ARGB plane (about 8 MB per event).
        // Native rendering applies these normalized bounds to the curved movie screen.
        if (layouts.size == 1) {
            val layout = layouts.first()
            nativeSetBitmapSubtitle(
                layout.bitmap,
                layout.bounds.left,
                layout.bounds.top,
                layout.bounds.right,
                layout.bounds.bottom,
            )
            return
        }

        // Preserve simultaneous PGS objects while allocating only their union.
        val union = RectF(layouts.first().bounds)
        layouts.drop(1).forEach { union.union(it.bounds) }
        val outputWidth = (union.width() * PGS_REFERENCE_WIDTH).toInt().coerceAtLeast(1)
        val outputHeight = (union.height() * PGS_REFERENCE_HEIGHT).toInt().coerceAtLeast(1)
        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        layouts.forEach { layout ->
            val bounds = layout.bounds
            canvas.drawBitmap(
                layout.bitmap,
                null,
                RectF(
                    (bounds.left - union.left) / union.width() * outputWidth,
                    (bounds.top - union.top) / union.height() * outputHeight,
                    (bounds.right - union.left) / union.width() * outputWidth,
                    (bounds.bottom - union.top) / union.height() * outputHeight,
                ),
                paint,
            )
        }
        nativeSetBitmapSubtitle(output, union.left, union.top, union.right, union.bottom)
        output.recycle()
    }

    private data class BitmapCueLayout(val bitmap: Bitmap, val bounds: RectF)

    private fun anchorStart(value: Float, extent: Float, anchor: Int): Float = when (anchor) {
        Cue.ANCHOR_TYPE_MIDDLE -> value - extent / 2f
        Cue.ANCHOR_TYPE_END -> value - extent
        else -> value
    }

    private fun renderVrMenu() {
        if (!uiVisible) return
        val bitmap = menuBitmap ?: Bitmap.createBitmap(
            UI_WIDTH.toInt(),
            UI_HEIGHT.toInt(),
            Bitmap.Config.ARGB_8888,
        ).also { menuBitmap = it }
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        val buttons = mutableListOf<UiButton>()
        val colorPanel = Color.argb(248, 3, 9, 18)
        val colorSurface = Color.rgb(7, 18, 32)
        val colorSurfaceRaised = Color.rgb(12, 29, 49)
        val colorSelected = Color.rgb(10, 55, 84)
        val colorHover = Color.rgb(13, 73, 108)
        val colorOutline = Color.rgb(27, 49, 69)
        val colorAccent = Color.rgb(42, 163, 215)
        val colorAccentBright = Color.rgb(91, 190, 235)
        val colorText = Color.rgb(232, 242, 249)
        val colorTextMuted = Color.rgb(153, 176, 194)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorText
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorPanel }

        fun text(
            value: String,
            x: Float,
            y: Float,
            size: Float,
            align: Paint.Align = Paint.Align.CENTER,
            color: Int = colorText,
        ) {
            textPaint.textSize = size
            textPaint.textAlign = align
            textPaint.color = color
            canvas.drawText(value.take(80), x, y, textPaint)
        }

        fun button(id: String, label: String, bounds: RectF, selected: Boolean = false, fontSize: Float = 46f) {
            val hovered = hoveredButton == id
            val fill = when {
                hovered -> colorHover
                selected -> colorSelected
                else -> colorSurface
            }
            canvas.drawRoundRect(bounds, 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
            canvas.drawRoundRect(bounds, 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (hovered || selected) colorAccent else colorOutline
                style = Paint.Style.STROKE
                strokeWidth = if (hovered) 4f else 2f
            })
            text(label, bounds.centerX(), bounds.centerY() + fontSize * 0.39f, fontSize)
            buttons += UiButton(id, label, bounds, selected)
        }

        fun sliderResetButton(id: String, label: String, centerX: Float, centerY: Float, radius: Float) {
            val bounds = RectF(
                centerX - radius,
                centerY - radius,
                centerX + radius,
                centerY + radius,
            )
            val hovered = hoveredButton == id
            canvas.drawCircle(
                centerX,
                centerY,
                radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (hovered) colorHover else colorSurface
                },
            )
            canvas.drawCircle(
                centerX,
                centerY,
                radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (hovered) colorAccent else colorOutline
                    style = Paint.Style.STROKE
                    strokeWidth = if (hovered) 3f else 2f
                },
            )
            val iconRadius = radius * 0.52f
            val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (hovered) colorAccentBright else colorTextMuted
                style = Paint.Style.STROKE
                strokeWidth = (radius * 0.14f).coerceAtLeast(2.5f)
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawArc(
                RectF(
                    centerX - iconRadius,
                    centerY - iconRadius,
                    centerX + iconRadius,
                    centerY + iconRadius,
                ),
                -55f,
                -255f,
                false,
                iconPaint,
            )
            canvas.drawPath(
                Path().apply {
                    moveTo(centerX - iconRadius * 0.95f, centerY - iconRadius * 0.70f)
                    lineTo(centerX - iconRadius * 1.30f, centerY - iconRadius * 0.12f)
                    lineTo(centerX - iconRadius * 0.58f, centerY - iconRadius * 0.05f)
                    close()
                },
                Paint(iconPaint).apply { style = Paint.Style.FILL },
            )
            buttons += UiButton(id, "Reset $label", bounds)
        }

        fun slider(
            id: String,
            label: String,
            valueLabel: String,
            value: Float,
            minimum: Float,
            maximum: Float,
            y: Float,
            logarithmic: Boolean = false,
        ) {
            val bounds = RectF(PICTURE_SLIDER_LEFT, y - 34f, PICTURE_SLIDER_RIGHT, y + 34f)
            val fraction = if (logarithmic) {
                (
                    kotlin.math.ln(value / minimum) /
                        kotlin.math.ln(maximum / minimum)
                ).coerceIn(0f, 1f)
            } else {
                ((value - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
            }
            text(label, 385f, y + 12f, 31f, Paint.Align.LEFT)
            text(valueLabel, 1690f, y + 12f, 29f, Paint.Align.RIGHT, colorTextMuted)
            val rail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorOutline }
            val active = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorAccent }
            canvas.drawRoundRect(PICTURE_SLIDER_LEFT, y - 7f, PICTURE_SLIDER_RIGHT, y + 7f, 7f, 7f, rail)
            val knobX = PICTURE_SLIDER_LEFT +
                (PICTURE_SLIDER_RIGHT - PICTURE_SLIDER_LEFT) * fraction
            canvas.drawRoundRect(PICTURE_SLIDER_LEFT, y - 7f, knobX, y + 7f, 7f, 7f, active)
            val knobRadius = if (hoveredButton == id || activeSlider == id) 24f else 19f
            canvas.drawCircle(
                knobX,
                y,
                knobRadius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorText },
            )
            canvas.drawCircle(
                knobX,
                y,
                knobRadius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (hoveredButton == id || activeSlider == id) colorAccentBright else colorOutline
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                },
            )
            buttons += UiButton(id, label, bounds)
            sliderResetButton(
                "picture_reset_${id.substringAfter("slider_")}",
                label,
                1745f,
                y,
                27f,
            )
        }

        fun compactSettingsSlider(
            id: String,
            label: String,
            valueLabel: String,
            value: Float,
            minimum: Float,
            maximum: Float,
            y: Float,
            logarithmic: Boolean = false,
            valueLabelX: Float = 1768f,
        ) {
            val bounds = RectF(
                SETTINGS_SLIDER_LEFT,
                y - 19f,
                SETTINGS_SLIDER_RIGHT,
                y + 19f,
            )
            val fraction = if (logarithmic) {
                (
                    kotlin.math.ln(value / minimum) /
                        kotlin.math.ln(maximum / minimum)
                ).coerceIn(0f, 1f)
            } else {
                ((value - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
            }
            text(label, 975f, y + 9f, 24f, Paint.Align.LEFT)
            text(valueLabel, valueLabelX, y + 9f, 22f, Paint.Align.RIGHT, colorTextMuted)
            canvas.drawRoundRect(
                SETTINGS_SLIDER_LEFT, y - 5f, SETTINGS_SLIDER_RIGHT, y + 5f,
                5f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorOutline },
            )
            val knobX = SETTINGS_SLIDER_LEFT +
                (SETTINGS_SLIDER_RIGHT - SETTINGS_SLIDER_LEFT) * fraction
            canvas.drawRoundRect(
                SETTINGS_SLIDER_LEFT, y - 5f, knobX, y + 5f,
                5f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorAccent },
            )
            val focused = hoveredButton == id || activeSlider == id
            canvas.drawCircle(
                knobX, y, if (focused) 18f else 14f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorText },
            )
            canvas.drawCircle(
                knobX, y, if (focused) 18f else 14f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (focused) colorAccentBright else colorOutline
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                },
            )
            buttons += UiButton(id, label, bounds)
            sliderResetButton(
                "picture_reset_${id.substringAfter("slider_")}",
                label,
                1832f,
                y,
                17f,
            )
        }

        fun subtitleDepthSlider(y: Float) {
            val id = "subtitle_depth_slider"
            val bounds = RectF(
                SUBTITLE_DEPTH_SLIDER_LEFT,
                y - 22f,
                SUBTITLE_DEPTH_SLIDER_RIGHT,
                y + 22f,
            )
            val fraction = (
                (subtitleDepthMeters - SUBTITLE_DEPTH_MIN_METERS) /
                    (SUBTITLE_DEPTH_MAX_METERS - SUBTITLE_DEPTH_MIN_METERS)
            ).coerceIn(0f, 1f)
            text("Depth", 190f, y + 8f, 23f, Paint.Align.LEFT)
            val depthLabel = if (subtitleDepthMeters < 0.005f) {
                "On screen"
            } else {
                "%.0f cm closer".format(Locale.US, subtitleDepthMeters * 100f)
            }
            text(depthLabel, 820f, y + 8f, 21f, Paint.Align.RIGHT, colorTextMuted)
            canvas.drawRoundRect(
                SUBTITLE_DEPTH_SLIDER_LEFT, y - 5f,
                SUBTITLE_DEPTH_SLIDER_RIGHT, y + 5f,
                5f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorOutline },
            )
            val knobX = SUBTITLE_DEPTH_SLIDER_LEFT +
                (SUBTITLE_DEPTH_SLIDER_RIGHT - SUBTITLE_DEPTH_SLIDER_LEFT) * fraction
            canvas.drawRoundRect(
                SUBTITLE_DEPTH_SLIDER_LEFT, y - 5f, knobX, y + 5f,
                5f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorAccent },
            )
            val focused = hoveredButton == id || activeSlider == id
            canvas.drawCircle(
                knobX, y, if (focused) 18f else 14f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorText },
            )
            canvas.drawCircle(
                knobX, y, if (focused) 18f else 14f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (focused) colorAccentBright else colorOutline
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                },
            )
            buttons += UiButton(id, "Subtitle depth", bounds)
        }

        fun projectionCurvatureSlider(y: Float) {
            val id = "screen_curvature_slider"
            val bounds = RectF(
                SCREEN_CURVATURE_SLIDER_LEFT,
                y - 23f,
                SCREEN_CURVATURE_SLIDER_RIGHT,
                y + 23f,
            )
            val fraction = (
                (screenCurvature - SCREEN_CURVATURE_MIN) /
                    (SCREEN_CURVATURE_MAX - SCREEN_CURVATURE_MIN)
            ).coerceIn(0f, 1f)
            text("Screen curvature", 1218f, y + 8f, 23f, Paint.Align.LEFT)
            text(
                if (screenCurvature < 0.01f) {
                    "Flat"
                } else {
                    "%.0f%%".format(Locale.US, screenCurvature * 100f)
                },
                1860f, y + 8f, 21f, Paint.Align.RIGHT, colorTextMuted,
            )
            canvas.drawRoundRect(
                SCREEN_CURVATURE_SLIDER_LEFT, y - 5f,
                SCREEN_CURVATURE_SLIDER_RIGHT, y + 5f,
                5f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorOutline },
            )
            val knobX = SCREEN_CURVATURE_SLIDER_LEFT +
                (SCREEN_CURVATURE_SLIDER_RIGHT - SCREEN_CURVATURE_SLIDER_LEFT) * fraction
            canvas.drawRoundRect(
                SCREEN_CURVATURE_SLIDER_LEFT, y - 5f, knobX, y + 5f,
                5f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorAccent },
            )
            val focused = hoveredButton == id || activeSlider == id
            canvas.drawCircle(
                knobX, y, if (focused) 18f else 14f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorText },
            )
            canvas.drawCircle(
                knobX, y, if (focused) 18f else 14f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (focused) colorAccentBright else colorOutline
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                },
            )
            buttons += UiButton(id, "Screen curvature", bounds)
        }

        fun iconButton(
            id: String,
            label: String,
            bounds: RectF,
            selected: Boolean = false,
            prominent: Boolean = false,
        ) {
            val hovered = hoveredButton == id
            val fill = when {
                hovered -> colorHover
                selected -> colorSelected
                prominent -> colorAccent
                else -> colorSurface
            }
            val cornerRadius = if (prominent) bounds.height() / 2f else 22f
            canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
            canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = when {
                    prominent && hovered -> colorAccentBright
                    hovered || selected -> colorAccent
                    prominent -> colorAccentBright
                    else -> colorOutline
                }
                style = Paint.Style.STROKE
                strokeWidth = if (hovered || prominent) 4f else 2f
            })
            val cx = bounds.centerX()
            val cy = bounds.centerY()
            val scale = minOf(bounds.width(), bounds.height()) * if (prominent) 0.25f else 0.27f
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (prominent) colorSurface else colorText
                style = Paint.Style.STROKE
                strokeWidth = 7f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val solid = Paint(stroke).apply { style = Paint.Style.FILL }
            when (id) {
                "back_library" -> {
                    val folder = Path().apply {
                        moveTo(cx - scale, cy - scale * 0.45f)
                        lineTo(cx - scale * 0.35f, cy - scale * 0.45f)
                        lineTo(cx - scale * 0.12f, cy - scale * 0.75f)
                        lineTo(cx + scale, cy - scale * 0.75f)
                        lineTo(cx + scale, cy + scale * 0.72f)
                        lineTo(cx - scale, cy + scale * 0.72f)
                        close()
                    }
                    canvas.drawPath(folder, stroke)
                }
                "seek_back", "seek_forward" -> {
                    // The arc geometry's visual direction is opposite its mathematical sweep.
                    val backwards = id == "seek_forward"
                    val arc = RectF(cx - scale, cy - scale, cx + scale, cy + scale)
                    canvas.drawArc(arc, if (backwards) -55f else 235f, if (backwards) -255f else 255f, false, stroke)
                    val arrowX = if (backwards) cx - scale * 0.92f else cx + scale * 0.92f
                    val arrow = Path().apply {
                        moveTo(arrowX, cy - scale * 0.7f)
                        lineTo(arrowX + (if (backwards) -1 else 1) * scale * 0.48f, cy - scale * 0.15f)
                        lineTo(arrowX + (if (backwards) 1 else -1) * scale * 0.15f, cy - scale * 0.05f)
                        close()
                    }
                    canvas.drawPath(arrow, solid)
                    text("10", cx, cy + 12f, 28f)
                }
                "play_pause" -> {
                    if (player.isPlaying) {
                        canvas.drawRoundRect(
                            cx - scale * 0.72f, cy - scale, cx - scale * 0.18f, cy + scale,
                            4f, 4f, solid,
                        )
                        canvas.drawRoundRect(
                            cx + scale * 0.18f, cy - scale, cx + scale * 0.72f, cy + scale,
                            4f, 4f, solid,
                        )
                    } else {
                        val play = Path().apply {
                            moveTo(cx - scale * 0.62f, cy - scale)
                            lineTo(cx + scale, cy)
                            lineTo(cx - scale * 0.62f, cy + scale)
                            close()
                        }
                        canvas.drawPath(play, solid)
                    }
                }
                "projection" -> {
                    canvas.drawRoundRect(
                        cx - scale * 1.15f, cy - scale * 0.72f,
                        cx + scale * 1.15f, cy + scale * 0.72f,
                        8f, 8f, stroke,
                    )
                    when (currentStereoMode) {
                        StereoMode.MONO -> {
                            val mountain = Path().apply {
                                moveTo(cx - scale * 0.72f, cy + scale * 0.36f)
                                lineTo(cx - scale * 0.2f, cy - scale * 0.2f)
                                lineTo(cx + scale * 0.12f, cy + scale * 0.1f)
                                lineTo(cx + scale * 0.46f, cy - scale * 0.3f)
                                lineTo(cx + scale * 0.82f, cy + scale * 0.36f)
                            }
                            canvas.drawPath(mountain, stroke)
                        }
                        StereoMode.SBS,
                        StereoMode.HALF_SBS -> {
                            canvas.drawLine(cx, cy - scale * 0.68f, cx, cy + scale * 0.68f, stroke)
                        }
                        StereoMode.OVER_UNDER,
                        StereoMode.HALF_OVER_UNDER -> {
                            canvas.drawLine(cx - scale * 1.1f, cy, cx + scale * 1.1f, cy, stroke)
                        }
                    }
                }
                "picture" -> {
                    canvas.drawCircle(cx, cy, scale * 0.45f, stroke)
                    repeat(8) { index ->
                        val angle = Math.PI * index / 4.0
                        val x1 = cx + Math.cos(angle).toFloat() * scale * 0.7f
                        val y1 = cy + Math.sin(angle).toFloat() * scale * 0.7f
                        val x2 = cx + Math.cos(angle).toFloat() * scale * 1.05f
                        val y2 = cy + Math.sin(angle).toFloat() * scale * 1.05f
                        canvas.drawLine(x1, y1, x2, y2, stroke)
                    }
                }
                "decoder" -> {
                    canvas.drawRoundRect(
                        cx - scale * 0.95f, cy - scale * 0.72f,
                        cx + scale * 0.95f, cy + scale * 0.72f,
                        8f, 8f, stroke,
                    )
                    repeat(3) { index ->
                        val offset = (index - 1) * scale * 0.55f
                        canvas.drawLine(
                            cx + offset, cy - scale * 0.72f,
                            cx + offset, cy - scale * 1.02f, stroke,
                        )
                        canvas.drawLine(
                            cx + offset, cy + scale * 0.72f,
                            cx + offset, cy + scale * 1.02f, stroke,
                        )
                    }
                    text(if (effectiveDecoderMode == DecoderMode.SOFTWARE) "SW" else "HW", cx, cy + 10f, 24f)
                }
                "audio_sync" -> {
                    val speaker = Path().apply {
                        moveTo(cx - scale * 1.08f, cy - scale * 0.32f)
                        lineTo(cx - scale * 0.55f, cy - scale * 0.32f)
                        lineTo(cx, cy - scale * 0.82f)
                        lineTo(cx, cy + scale * 0.82f)
                        lineTo(cx - scale * 0.55f, cy + scale * 0.32f)
                        lineTo(cx - scale * 1.08f, cy + scale * 0.32f)
                        close()
                    }
                    canvas.drawPath(speaker, stroke)
                    val clockX = cx + scale * 0.72f
                    val clockY = cy + scale * 0.22f
                    canvas.drawCircle(clockX, clockY, scale * 0.55f, stroke)
                    canvas.drawLine(clockX, clockY, clockX, clockY - scale * 0.32f, stroke)
                    canvas.drawLine(clockX, clockY, clockX + scale * 0.24f, clockY + scale * 0.12f, stroke)
                }
                "audio_tracks", "audio" -> {
                    val speaker = Path().apply {
                        moveTo(cx - scale * 1.05f, cy - scale * 0.34f)
                        lineTo(cx - scale * 0.52f, cy - scale * 0.34f)
                        lineTo(cx, cy - scale * 0.86f)
                        lineTo(cx, cy + scale * 0.86f)
                        lineTo(cx - scale * 0.52f, cy + scale * 0.34f)
                        lineTo(cx - scale * 1.05f, cy + scale * 0.34f)
                        close()
                    }
                    canvas.drawPath(speaker, stroke)
                    canvas.drawArc(
                        RectF(cx - scale * 0.22f, cy - scale * 0.58f, cx + scale * 0.75f, cy + scale * 0.58f),
                        -58f, 116f, false, stroke,
                    )
                    canvas.drawArc(
                        RectF(cx - scale * 0.18f, cy - scale, cx + scale * 1.25f, cy + scale),
                        -52f, 104f, false, stroke,
                    )
                }
                "subtitles" -> {
                    canvas.drawRoundRect(
                        cx - scale * 1.2f, cy - scale * 0.82f,
                        cx + scale * 1.2f, cy + scale * 0.82f,
                        9f, 9f, stroke,
                    )
                    text("CC", cx, cy + 13f, 31f)
                }
                "settings" -> {
                    canvas.drawCircle(cx, cy, scale * 0.43f, stroke)
                    canvas.drawCircle(cx, cy, scale * 0.13f, solid)
                    repeat(8) { index ->
                        val angle = Math.PI * index / 4.0
                        val innerX = cx + Math.cos(angle).toFloat() * scale * 0.68f
                        val innerY = cy + Math.sin(angle).toFloat() * scale * 0.68f
                        val outerX = cx + Math.cos(angle).toFloat() * scale * 1.03f
                        val outerY = cy + Math.sin(angle).toFloat() * scale * 1.03f
                        canvas.drawLine(innerX, innerY, outerX, outerY, stroke)
                    }
                }
            }
            buttons += UiButton(id, label, bounds, selected)
        }

        fun popoverPanel(bounds: RectF, title: String, anchorX: Float) {
            canvas.drawRoundRect(
                bounds,
                30f,
                30f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorPanel },
            )
            canvas.drawRoundRect(
                bounds,
                30f,
                30f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = colorOutline
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                },
            )
            val pointer = Path().apply {
                moveTo(anchorX - 24f, bounds.bottom - 1f)
                lineTo(anchorX + 24f, bounds.bottom - 1f)
                lineTo(anchorX, bounds.bottom + 28f)
                close()
            }
            canvas.drawPath(pointer, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorPanel })
            text(title, bounds.left + 44f, bounds.top + 62f, 39f, Paint.Align.LEFT)
        }

        fun projectionGridButton(
            id: String,
            label: String,
            bounds: RectF,
            mode: StereoMode? = null,
            selected: Boolean = false,
            swapControl: Boolean = false,
        ) {
            val hovered = hoveredButton == id
            val fill = when {
                hovered -> colorHover
                selected -> colorSelected
                else -> colorSurface
            }
            canvas.drawRoundRect(
                bounds, 22f, 22f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill },
            )
            canvas.drawRoundRect(
                bounds, 22f, 22f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (hovered || selected) colorAccent else colorOutline
                    style = Paint.Style.STROKE
                    strokeWidth = if (hovered || selected) 4f else 2f
                },
            )

            val cx = bounds.centerX()
            val iconY = bounds.top + 51f
            val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (selected) colorAccentBright else colorText
                style = Paint.Style.STROKE
                strokeWidth = 5f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val iconFill = Paint(iconStroke).apply { style = Paint.Style.FILL }

            if (swapControl) {
                val leftEye = RectF(cx - 72f, iconY - 21f, cx - 8f, iconY + 21f)
                val rightEye = RectF(cx + 8f, iconY - 21f, cx + 72f, iconY + 21f)
                canvas.drawOval(leftEye, iconStroke)
                canvas.drawOval(rightEye, iconStroke)
                canvas.drawCircle(leftEye.centerX(), iconY, 7f, iconFill)
                canvas.drawCircle(rightEye.centerX(), iconY, 7f, iconFill)
                val arrowY = iconY + 34f
                canvas.drawLine(cx - 48f, arrowY, cx + 48f, arrowY, iconStroke)
                val direction = if (swapEyes) -1f else 1f
                val arrowX = cx + direction * 48f
                val arrow = Path().apply {
                    moveTo(arrowX, arrowY)
                    lineTo(arrowX - direction * 15f, arrowY - 10f)
                    lineTo(arrowX - direction * 15f, arrowY + 10f)
                    close()
                }
                canvas.drawPath(arrow, iconFill)
            } else {
                val frame = RectF(cx - 70f, iconY - 31f, cx + 70f, iconY + 31f)
                canvas.drawRoundRect(frame, 8f, 8f, iconStroke)
                when (mode) {
                    StereoMode.MONO -> {
                        val mountain = Path().apply {
                            moveTo(cx - 52f, iconY + 17f)
                            lineTo(cx - 22f, iconY - 11f)
                            lineTo(cx + 1f, iconY + 7f)
                            lineTo(cx + 26f, iconY - 17f)
                            lineTo(cx + 53f, iconY + 17f)
                        }
                        canvas.drawPath(mountain, iconStroke)
                    }
                    StereoMode.SBS,
                    StereoMode.HALF_SBS ->
                        canvas.drawLine(cx, frame.top + 2f, cx, frame.bottom - 2f, iconStroke)
                    StereoMode.OVER_UNDER,
                    StereoMode.HALF_OVER_UNDER ->
                        canvas.drawLine(frame.left + 2f, iconY, frame.right - 2f, iconY, iconStroke)
                    null -> Unit
                }
                if (mode != StereoMode.MONO && mode != null) {
                    val badgeText = when (mode) {
                        StereoMode.SBS,
                        StereoMode.OVER_UNDER -> "F"
                        else -> "H"
                    }
                    canvas.drawCircle(
                        frame.right - 2f,
                        frame.top + 2f,
                        16f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = if (selected) colorAccent else colorSurfaceRaised
                        },
                    )
                    text(badgeText, frame.right - 2f, frame.top + 10f, 20f)
                }
            }
            text(label, cx, bounds.bottom - 14f, 22f)
            buttons += UiButton(id, label, bounds, selected)
        }

        fun popoverTrackList(
            itemCount: Int,
            selectedIndex: Int,
            itemLabel: (Int) -> String,
            itemId: (Int) -> String,
        ) {
            val maximum =
                (itemCount - TRACK_LIST_VISIBLE_ROWS).coerceAtLeast(0).toFloat()
            setTrackListScrollOffset(trackListScrollOffset().coerceIn(0f, maximum))
            val offset = trackListScrollOffset()
            val anchorIndex = kotlin.math.floor(offset).toInt()
            val firstIndex = (anchorIndex - TRACK_LIST_ROW_RENDER_BUFFER).coerceAtLeast(0)
            val lastIndex = minOf(
                itemCount - 1,
                anchorIndex + TRACK_LIST_VISIBLE_ROWS + TRACK_LIST_ROW_RENDER_BUFFER,
            )
            val saveCount = canvas.save()
            canvas.clipRect(TRACK_LIST_CLIP_BOUNDS)
            if (itemCount > 0) {
                for (index in firstIndex..lastIndex) {
                    val top = TRACK_LIST_ROW_TOP + (index - offset) * TRACK_LIST_ROW_STRIDE
                    val bounds = RectF(
                        TRACK_LIST_ROW_LEFT,
                        top,
                        TRACK_LIST_ROW_RIGHT,
                        top + TRACK_LIST_ROW_HEIGHT,
                    )
                    if (
                        bounds.bottom >= TRACK_LIST_CLIP_BOUNDS.top &&
                        bounds.top <= TRACK_LIST_CLIP_BOUNDS.bottom
                    ) {
                        button(
                            itemId(index),
                            itemLabel(index),
                            bounds,
                            selectedIndex == index,
                            fontSize = 23f,
                        )
                        val drawnButton = buttons.removeAt(buttons.lastIndex)
                        val hitBounds = RectF(drawnButton.bounds)
                        if (hitBounds.intersect(TRACK_LIST_HIT_BOUNDS)) {
                            buttons += drawnButton.copy(bounds = hitBounds)
                        }
                    }
                }
            }
            canvas.restoreToCount(saveCount)

            if (maximum > 0f) {
                canvas.drawRoundRect(
                    TRACK_LIST_SCROLLBAR_TRACK,
                    6f,
                    6f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorOutline },
                )
                val thumb = trackListScrollbarThumbBounds()
                val thumbColor = if (
                    hoveredButton == TRACK_LIST_SCROLLBAR_ID ||
                    trackListScrollbarDragging
                ) colorAccentBright else colorAccent
                canvas.drawRoundRect(
                    thumb,
                    6f,
                    6f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = thumbColor },
                )
                buttons += UiButton(
                    TRACK_LIST_SCROLLBAR_ID,
                    "Scroll tracks",
                    RectF(
                        thumb.left - TRACK_LIST_SCROLLBAR_HIT_PADDING,
                        thumb.top - TRACK_LIST_SCROLLBAR_HIT_PADDING,
                        thumb.right + TRACK_LIST_SCROLLBAR_HIT_PADDING,
                        thumb.bottom + TRACK_LIST_SCROLLBAR_HIT_PADDING,
                    ),
                )
            }
        }

        fun checkbox(id: String, label: String, bounds: RectF, checked: Boolean) {
            val hovered = hoveredButton == id
            if (hovered) {
                canvas.drawRoundRect(bounds, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = colorHover
                })
            }
            val box = RectF(bounds.left + 18f, bounds.centerY() - 24f, bounds.left + 66f, bounds.centerY() + 24f)
            canvas.drawRoundRect(box, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (checked) colorAccent else colorSurfaceRaised
                style = Paint.Style.FILL
            })
            canvas.drawRoundRect(box, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (hovered || checked) colorAccentBright else colorOutline
                style = Paint.Style.STROKE
                strokeWidth = 3f
            })
            if (checked) text("✓", box.centerX(), box.centerY() + 15f, 38f)
            text(label, bounds.left + 90f, bounds.centerY() + 14f, 35f, Paint.Align.LEFT)
            buttons += UiButton(id, label, bounds, checked)
        }

        fun browserEntryButton(id: String, name: String, detail: String, bounds: RectF, folder: Boolean) {
            val hovered = hoveredButton == id
            val fill = if (hovered) colorHover else colorSurfaceRaised
            canvas.drawRoundRect(bounds, 20f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
            canvas.drawRoundRect(bounds, 20f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (hovered) colorAccent else colorOutline
                style = Paint.Style.STROKE
                strokeWidth = if (hovered) 4f else 2f
            })
            if (hovered) {
                canvas.drawRoundRect(
                    bounds.left, bounds.top + 13f, bounds.left + 7f, bounds.bottom - 13f,
                    4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorAccentBright },
                )
            }
            val iconX = bounds.left + 38f
            val iconY = bounds.centerY()
            if (folder) {
                val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(207, 165, 73) }
                canvas.drawRoundRect(iconX, iconY - 18f, iconX + 58f, iconY + 24f, 7f, 7f, iconPaint)
                canvas.drawRoundRect(iconX + 5f, iconY - 29f, iconX + 33f, iconY - 9f, 6f, 6f, iconPaint)
            } else {
                val play = Path().apply {
                    moveTo(iconX + 8f, iconY - 27f)
                    lineTo(iconX + 60f, iconY)
                    lineTo(iconX + 8f, iconY + 27f)
                    close()
                }
                canvas.drawPath(play, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorAccentBright })
            }
            text(name.take(52), iconX + 88f, iconY + 13f, 36f, Paint.Align.LEFT)
            if (detail.isNotBlank()) {
                text(detail, bounds.right - 28f, iconY + 12f, 29f, Paint.Align.RIGHT, colorTextMuted)
            }
            val hitBounds = RectF(bounds)
            if (hitBounds.intersect(BROWSER_LIST_HIT_BOUNDS)) {
                buttons += UiButton(id, name, hitBounds)
            }
        }

        fun recentEntryButton(
            id: String,
            item: MediaStateStore.RecentMedia,
            bounds: RectF,
        ) {
            val hovered = hoveredButton == id
            canvas.drawRoundRect(
                bounds,
                20f,
                20f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (hovered) colorHover else colorSurfaceRaised
                },
            )
            canvas.drawRoundRect(
                bounds,
                20f,
                20f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (hovered) colorAccent else colorOutline
                    style = Paint.Style.STROKE
                    strokeWidth = if (hovered) 4f else 2f
                },
            )
            if (hovered) {
                canvas.drawRoundRect(
                    bounds.left,
                    bounds.top + 12f,
                    bounds.left + 7f,
                    bounds.bottom - 12f,
                    4f,
                    4f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorAccentBright },
                )
            }

            val iconX = bounds.left + 42f
            val iconY = bounds.centerY()
            canvas.drawCircle(
                iconX,
                iconY,
                27f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorAccent },
            )
            canvas.drawPath(
                Path().apply {
                    moveTo(iconX - 7f, iconY - 14f)
                    lineTo(iconX + 16f, iconY)
                    lineTo(iconX - 7f, iconY + 14f)
                    close()
                },
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorText },
            )

            val parsed = Uri.parse(item.uri)
            val source = if (parsed.scheme.equals("smb", ignoreCase = true)) {
                "SMB  •  ${parsed.host.orEmpty()}/${item.smbShare.orEmpty()}"
            } else {
                "Local"
            }
            val resume = item.positionMs
                ?.takeIf { it >= MINIMUM_RESUME_POSITION_MS }
                ?.let { position ->
                    val duration = item.durationMs
                    if (duration != null && duration > 0L) {
                        "Resume ${formatTime(position)} / ${formatTime(duration)}"
                    } else {
                        "Resume ${formatTime(position)}"
                    }
                }
            text(
                item.title.take(42),
                bounds.left + 88f,
                bounds.top + 39f,
                31f,
                Paint.Align.LEFT,
            )
            text(
                listOfNotNull(resume, source).joinToString("  •  ").take(58),
                bounds.left + 88f,
                bounds.bottom - 17f,
                23f,
                Paint.Align.LEFT,
                colorTextMuted,
            )
            buttons += UiButton(id, item.title, bounds)
        }

        fun scrollableList(
            itemCount: Int,
            rowStride: Float,
            rowHeight: Float,
            drawRow: (index: Int, bounds: RectF) -> Unit,
        ) {
            val maximum = (itemCount - BROWSER_VISIBLE_ROWS).coerceAtLeast(0).toFloat()
            browserScrollOffset = browserScrollOffset.coerceIn(0f, maximum)
            val anchorIndex = kotlin.math.floor(browserScrollOffset).toInt()
            // Keep rows on both sides of the viewport alive while they cross its edge.
            // Canvas clipping, rather than index culling, then reveals/hides them progressively.
            val firstIndex = (anchorIndex - BROWSER_ROW_RENDER_BUFFER).coerceAtLeast(0)
            val lastIndex = minOf(
                itemCount - 1,
                anchorIndex + BROWSER_VISIBLE_ROWS + BROWSER_ROW_RENDER_BUFFER,
            )
            val saveCount = canvas.save()
            canvas.clipRect(BROWSER_LIST_CLIP_BOUNDS)
            if (itemCount > 0) {
                for (index in firstIndex..lastIndex) {
                    val top = BROWSER_LIST_TOP + (index - browserScrollOffset) * rowStride
                    val bounds = RectF(BROWSER_LIST_LEFT, top, BROWSER_LIST_RIGHT, top + rowHeight)
                    if (bounds.bottom >= BROWSER_LIST_TOP && bounds.top <= BROWSER_LIST_BOTTOM) {
                        drawRow(index, bounds)
                    }
                }
            }
            canvas.restoreToCount(saveCount)

            if (maximum > 0f) {
                val track = BROWSER_SCROLLBAR_TRACK
                canvas.drawRoundRect(track, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = colorOutline
                })
                val thumb = browserScrollbarThumbBounds()
                val thumbColor = if (
                    hoveredButton == BROWSER_SCROLLBAR_ID || browserScrollbarDragging
                ) colorAccentBright else colorAccent
                canvas.drawRoundRect(
                    thumb, 8f, 8f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = thumbColor },
                )
                buttons += UiButton(
                    BROWSER_SCROLLBAR_ID,
                    "Scroll",
                    RectF(
                        thumb.left - BROWSER_SCROLLBAR_HIT_PADDING,
                        thumb.top - BROWSER_SCROLLBAR_HIT_PADDING,
                        thumb.right + BROWSER_SCROLLBAR_HIT_PADDING,
                        thumb.bottom + BROWSER_SCROLLBAR_HIT_PADDING,
                    ),
                )
            }
        }

        fun browserTools() {
            val searchLabel = if (browserSearchQuery.isBlank()) {
                "Search"
            } else {
                "Search: ${browserSearchQuery.take(11)}"
            }
            button(
                "browser_search",
                searchLabel,
                RectF(1355f, 75f, 1595f, 142f),
                browserSearchQuery.isNotBlank(),
                fontSize = 25f,
            )
            button(
                "browser_sort",
                "${browserSortField.label} ${if (browserSortAscending) "↑" else "↓"}",
                RectF(1610f, 75f, 1880f, 142f),
                browserSortPopoverOpen ||
                    browserSortField != BrowserSortField.NAME ||
                    !browserSortAscending,
                fontSize = 24f,
            )
        }

        fun browserSortPopover() {
            val bounds = BROWSER_SORT_POPOVER_BOUNDS
            canvas.drawRoundRect(
                bounds,
                28f,
                28f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorPanel },
            )
            canvas.drawRoundRect(
                bounds,
                28f,
                28f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = colorOutline
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                },
            )
            val pointer = Path().apply {
                moveTo(1718f, bounds.top + 1f)
                lineTo(1766f, bounds.top + 1f)
                lineTo(1742f, bounds.top - 22f)
                close()
            }
            canvas.drawPath(pointer, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorPanel })
            text("Sort by", bounds.left + 34f, bounds.top + 56f, 34f, Paint.Align.LEFT)
            button(
                "browser_sort_field_name", "Name",
                RectF(bounds.left + 28f, bounds.top + 82f, bounds.right - 28f, bounds.top + 152f),
                browserSortField == BrowserSortField.NAME,
                fontSize = 29f,
            )
            button(
                "browser_sort_field_date", "Date",
                RectF(bounds.left + 28f, bounds.top + 164f, bounds.right - 28f, bounds.top + 234f),
                browserSortField == BrowserSortField.DATE,
                fontSize = 29f,
            )
            button(
                "browser_sort_field_duration", "Duration",
                RectF(bounds.left + 28f, bounds.top + 246f, bounds.right - 28f, bounds.top + 316f),
                browserSortField == BrowserSortField.DURATION,
                fontSize = 29f,
            )
            button(
                "browser_sort_field_recent", "Recently played",
                RectF(bounds.left + 28f, bounds.top + 328f, bounds.right - 28f, bounds.top + 398f),
                browserSortField == BrowserSortField.RECENT,
                fontSize = 27f,
            )
            text(
                "Direction",
                bounds.left + 34f,
                bounds.top + 447f,
                25f,
                Paint.Align.LEFT,
                colorTextMuted,
            )
            button(
                "browser_sort_ascending", "↑",
                RectF(bounds.left + 180f, bounds.top + 412f, bounds.left + 290f, bounds.top + 472f),
                browserSortAscending,
                fontSize = 40f,
            )
            button(
                "browser_sort_descending", "↓",
                RectF(bounds.left + 304f, bounds.top + 412f, bounds.left + 414f, bounds.top + 472f),
                !browserSortAscending,
                fontSize = 40f,
            )
        }

        when (uiScreen) {
            UiScreen.LIBRARY -> {
                canvas.drawRoundRect(90f, 60f, 845f, 955f, 42f, 42f, panelPaint)
                canvas.drawRoundRect(885f, 60f, 1958f, 955f, 42f, 42f, panelPaint)
                text("Dream Player", 468f, 190f, 76f)
                text("Choose a source", 468f, 252f, 36f, color = colorTextMuted)
                button("open_local", "Local video library", RectF(155f, 295f, 780f, 405f), fontSize = 38f)
                button("open_smb", "Network shares (SMB)", RectF(155f, 435f, 780f, 545f), fontSize = 38f)
                button("open_sftp", "Internet server (FTP/SFTP)", RectF(155f, 575f, 780f, 685f), fontSize = 38f)
                text(
                    "Browse without leaving VR.",
                    468f,
                    750f,
                    29f,
                    color = colorTextMuted,
                )
                button("exit", "Exit", RectF(265f, 825f, 670f, 910f), fontSize = 36f)

                text("Recently played", 1422f, 145f, 50f)
                if (recentMedia.isEmpty()) {
                    text(
                        "Videos you play will appear here.",
                        1422f,
                        470f,
                        34f,
                        color = colorTextMuted,
                    )
                } else {
                    recentMedia.take(RECENT_MEDIA_LIMIT).forEachIndexed { index, item ->
                        val top = 185f + index * 118f
                        recentEntryButton(
                            "recent_$index",
                            item,
                            RectF(940f, top, 1903f, top + 96f),
                        )
                    }
                }
            }
            UiScreen.LOCAL_FOLDERS -> {
                canvas.drawRoundRect(170f, 55f, 1878f, 975f, 42f, 42f, panelPaint)
                text("Local videos", 1024f, 135f, 66f)
                val folders = localVideos.map { it.folder }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
                scrollableList(folders.size, 98f, 80f) { index, bounds ->
                    val folder = folders[index]
                    val count = localVideos.count { it.folder == folder }
                    browserEntryButton(
                        "folder_$index", folder, "$count videos",
                        RectF(290f, bounds.top, 1758f, bounds.bottom), true,
                    )
                }
                if (folders.isEmpty()) text("No local videos were found.", 1024f, 450f, 46f)
                button("local_back", "Back", RectF(250f, 885f, 570f, 950f))
                text("${folders.size} folders  •  Joystick ↑/↓ to scroll", 1740f, 930f, 30f, Paint.Align.RIGHT, colorTextMuted)
            }
            UiScreen.LOCAL_FILES -> {
                canvas.drawRoundRect(130f, 55f, 1918f, 975f, 42f, 42f, panelPaint)
                text((localFolder ?: "Local videos").takeLast(39), 180f, 128f, 43f, Paint.Align.LEFT)
                browserTools()
                scrollableList(localBrowserItems.size, 98f, 80f) { index, bounds ->
                    val item = localBrowserItems[index]
                    val video = item.video
                    val details = "${formatTime(video.durationMs)}  •  ${formatBytes(video.size)}"
                    browserEntryButton("video_${item.sourceIndex}", video.name, details,
                        RectF(230f, bounds.top, 1818f, bounds.bottom), false)
                }
                if (localBrowserItems.isEmpty()) {
                    text(
                        if (browserSearchQuery.isBlank()) {
                            "No videos were found in this folder."
                        } else {
                            "No file names match \"$browserSearchQuery\"."
                        },
                        1024f, 450f, 42f,
                    )
                }
                button("local_back", "Folders", RectF(250f, 885f, 570f, 950f))
                text("${localBrowserItems.size} videos  •  Joystick ↑/↓ to scroll", 1780f, 930f, 30f, Paint.Align.RIGHT, colorTextMuted)
            }
            UiScreen.SMB_SERVERS -> {
                canvas.drawRoundRect(130f, 55f, 1918f, 975f, 42f, 42f, panelPaint)
                text("SMB servers", 1024f, 135f, 64f)
                scrollableList(smbServers.size, 90f, 72f) { index, bounds ->
                    val server = smbServers[index]
                    browserEntryButton(
                        "smb_server_$index",
                        server.displayName,
                        if (server.displayName.equals(server.host, ignoreCase = true)) "" else server.host,
                        RectF(300f, bounds.top, 1748f, bounds.bottom), true,
                    )
                }
                button("smb_servers_back", "Back", RectF(180f, 885f, 460f, 955f), fontSize = 36f)
                button("smb_add_server", "Add server", RectF(505f, 885f, 835f, 955f), fontSize = 36f)
                button("smb_discover_again", "Scan again", RectF(880f, 885f, 1210f, 955f), fontSize = 36f)
                text("Joystick ↑/↓ to scroll", 1800f, 930f, 28f, Paint.Align.RIGHT, colorTextMuted)
            }
            UiScreen.SMB_LOGIN -> {
                canvas.drawRoundRect(210f, 55f, 1838f, 975f, 42f, 42f, panelPaint)
                text("Connect to SMB", 1024f, 145f, 66f)
                fun field(id: String, label: String, value: String, top: Float, secret: Boolean = false) {
                    text(label, 360f, top + 63f, 34f, Paint.Align.LEFT)
                    val shown = if (secret && value.isNotEmpty()) "•".repeat(value.length.coerceAtMost(24)) else value.ifBlank { "Select to enter" }
                    button("smb_field_$id", shown.take(38), RectF(700f, top, 1660f, top + 88f), fontSize = 38f)
                }
                field("host", "Server", smbCredentials.host, 205f)
                field("user", "Username", smbCredentials.username, 325f)
                field("password", "Password", smbCredentials.password, 445f, true)
                field("domain", "Domain", smbCredentials.domain, 565f)
                checkbox("smb_remember", "Remember credentials securely", RectF(570f, 680f, 1478f, 750f), rememberSmbCredentials)
                button("smb_login_back", "Back", RectF(430f, 790f, 850f, 905f))
                button("smb_connect", "Connect", RectF(910f, 790f, 1618f, 905f))
            }
            UiScreen.SMB_SHARES -> {
                canvas.drawRoundRect(150f, 55f, 1898f, 975f, 42f, 42f, panelPaint)
                text(smbDisplayHost.ifBlank { smbCredentials.host }, 1024f, 135f, 58f)
                scrollableList(smbShares.size, 92f, 75f) { index, bounds ->
                    browserEntryButton(
                        "smb_share_$index", smbShares[index], "",
                        RectF(270f, bounds.top, 1778f, bounds.bottom), true,
                    )
                }
                button("smb_shares_back", "Servers", RectF(250f, 885f, 570f, 950f), fontSize = 36f)
                text("${smbShares.size} shares  •  Joystick ↑/↓ to scroll", 1780f, 930f, 30f, Paint.Align.RIGHT, colorTextMuted)
            }
            UiScreen.SMB_FILES -> {
                canvas.drawRoundRect(120f, 55f, 1928f, 975f, 42f, 42f, panelPaint)
                val location = if (smbPath.isBlank()) smbCredentials.share else "${smbCredentials.share} / ${smbPath.replace('\\', '/')}"
                text(location.takeLast(40), 170f, 128f, 40f, Paint.Align.LEFT)
                browserTools()
                scrollableList(smbBrowserItems.size, 92f, 75f) { index, bounds ->
                    val item = smbBrowserItems[index]
                    val entry = item.entry
                    val detail = if (entry.isDirectory) {
                        ""
                    } else {
                        listOfNotNull(
                            item.metadata?.durationMs
                                ?.takeIf { it > 0L }
                                ?.let(::formatTime),
                            formatBytes(entry.size),
                        ).joinToString("  •  ")
                    }
                    browserEntryButton(
                        "smb_entry_${item.sourceIndex}", entry.name,
                        detail,
                        RectF(210f, bounds.top, 1838f, bounds.bottom), entry.isDirectory,
                    )
                }
                if (smbBrowserItems.isEmpty()) {
                    text(
                        if (browserSearchQuery.isBlank()) {
                            "No video files or folders found."
                        } else {
                            "No file names match \"$browserSearchQuery\"."
                        },
                        1024f, 450f, 42f,
                    )
                }
                button("smb_files_back", "Up", RectF(250f, 885f, 570f, 950f), fontSize = 36f)
                text("${smbBrowserItems.size} items  •  Joystick ↑/↓ to scroll", 1780f, 930f, 30f, Paint.Align.RIGHT, colorTextMuted)
            }
            UiScreen.SFTP_PROFILES -> {
                canvas.drawRoundRect(130f, 55f, 1918f, 975f, 42f, 42f, panelPaint)
                text("Internet servers", 1024f, 135f, 64f)
                scrollableList(sftpProfiles.size, 90f, 72f) { index, bounds ->
                    val profile = sftpProfiles[index]
                    browserEntryButton(
                        "sftp_profile_$index",
                        profile.displayName,
                        "${profile.protocol.label}  •  ${profile.username}@${profile.host}:${profile.port}",
                        RectF(250f, bounds.top, 1580f, bounds.bottom),
                        true,
                    )
                    button(
                        "sftp_edit_$index", "Edit",
                        RectF(1600f, bounds.top, 1810f, bounds.bottom),
                        fontSize = 28f,
                    )
                }
                if (sftpProfiles.isEmpty()) {
                    text("No Internet servers saved yet.", 1024f, 440f, 44f)
                    text(
                        "Add an SFTP, FTPS, or FTP server.",
                        1024f, 500f, 27f, color = colorTextMuted,
                    )
                }
                button(
                    "sftp_profiles_back", "Back",
                    RectF(250f, 885f, 570f, 950f), fontSize = 36f,
                )
                button(
                    "sftp_add_profile", "Add server",
                    RectF(620f, 885f, 1010f, 950f), fontSize = 36f,
                )
                text(
                    "${sftpProfiles.size} servers  •  Joystick ↑/↓ to scroll",
                    1780f, 930f, 30f, Paint.Align.RIGHT, colorTextMuted,
                )
            }
            UiScreen.SFTP_LOGIN -> {
                canvas.drawRoundRect(170f, 35f, 1878f, 990f, 42f, 42f, panelPaint)
                text("Internet server profile", 1024f, 92f, 50f)
                button(
                    "remote_protocol_sftp", "SFTP",
                    RectF(665f, 110f, 985f, 172f),
                    sftpProfile.protocol == RemoteProtocol.SFTP,
                    fontSize = 27f,
                )
                button(
                    "remote_protocol_ftps", "FTPS",
                    RectF(1000f, 110f, 1320f, 172f),
                    sftpProfile.protocol == RemoteProtocol.FTPS,
                    fontSize = 27f,
                )
                button(
                    "remote_protocol_ftp", "FTP",
                    RectF(1335f, 110f, 1655f, 172f),
                    sftpProfile.protocol == RemoteProtocol.FTP,
                    fontSize = 27f,
                )
                fun field(
                    id: String,
                    label: String,
                    value: String,
                    top: Float,
                    secret: Boolean = false,
                ) {
                    text(label, 310f, top + 51f, 30f, Paint.Align.LEFT)
                    val shown = if (secret && value.isNotEmpty()) {
                        "•".repeat(value.length.coerceAtMost(24))
                    } else {
                        value.ifBlank { "Select to enter" }
                    }
                    button(
                        "sftp_field_$id", shown.take(42),
                        RectF(665f, top, 1710f, top + 70f),
                        fontSize = 31f,
                    )
                }
                field("name", "Profile name", sftpProfile.name, 190f)
                field("host", "Host", sftpProfile.host, 275f)
                field("port", "Port", sftpPortDraft, 360f)
                field("user", "Username", sftpProfile.username, 445f)
                field("password", "Password", sftpProfile.password, 530f, true)
                field("path", "Start folder", sftpProfile.startPath, 615f)
                checkbox(
                    "sftp_remember_password",
                    "Remember password securely",
                    RectF(560f, 700f, 1488f, 762f),
                    sftpProfile.rememberPassword,
                )
                if (sftpProfiles.any { it.id == sftpProfile.id }) {
                    button(
                        "sftp_delete", "Delete",
                        RectF(260f, 805f, 540f, 895f), fontSize = 32f,
                    )
                }
                button(
                    "sftp_login_back", "Back",
                    RectF(600f, 805f, 940f, 895f), fontSize = 34f,
                )
                button(
                    "sftp_connect", "Save & browse",
                    RectF(980f, 805f, 1690f, 895f), fontSize = 34f,
                )
                text(
                    when (sftpProfile.protocol) {
                        RemoteProtocol.SFTP ->
                            "The SSH fingerprint is verified before authentication."
                        RemoteProtocol.FTPS ->
                            "Explicit TLS with certificate and hostname validation."
                        RemoteProtocol.FTP ->
                            "Plain FTP is unencrypted and requires explicit confirmation."
                    },
                    1024f, 945f, 22f, color = colorTextMuted,
                )
            }
            UiScreen.SFTP_TRUST -> {
                val pending = pendingSftpHostKey
                val changed = pending is SftpHostKeyException.Changed
                canvas.drawRoundRect(240f, 90f, 1808f, 930f, 42f, 42f, panelPaint)
                text(
                    if (changed) "Warning: server identity changed" else "Verify SFTP server",
                    1024f, 190f, 55f,
                    color = if (changed) Color.rgb(255, 150, 150) else colorText,
                )
                text(
                    if (changed) {
                        "Continue only if you intentionally changed the server key."
                    } else {
                        "Confirm this fingerprint matches the one shown by your server."
                    },
                    1024f, 270f, 27f, color = colorTextMuted,
                )
                text(
                    "${sftpProfile.host}:${sftpProfile.port}",
                    1024f, 350f, 34f,
                )
                text(
                    pending?.algorithm.orEmpty(),
                    1024f, 405f, 24f, color = colorTextMuted,
                )
                val fingerprint = pending?.actualFingerprint.orEmpty()
                text(fingerprint.take(36), 1024f, 495f, 32f)
                text(fingerprint.drop(36), 1024f, 545f, 32f)
                button(
                    "sftp_trust_cancel", "Cancel",
                    RectF(390f, 700f, 900f, 815f), fontSize = 36f,
                )
                button(
                    "sftp_trust_accept",
                    if (changed) "Trust new key" else "Trust & connect",
                    RectF(950f, 700f, 1658f, 815f), fontSize = 36f,
                )
            }
            UiScreen.FTP_WARNING -> {
                canvas.drawRoundRect(260f, 115f, 1788f, 900f, 42f, 42f, panelPaint)
                text(
                    "Plain FTP is not secure",
                    1024f, 235f, 60f,
                    color = Color.rgb(255, 180, 120),
                )
                text(
                    "Your username, password, file names, and video data",
                    1024f, 345f, 30f,
                )
                text(
                    "will travel over the Internet without encryption.",
                    1024f, 395f, 30f,
                )
                text(
                    "Use SFTP or FTPS whenever the server supports it.",
                    1024f, 490f, 27f, color = colorTextMuted,
                )
                button(
                    "ftp_warning_cancel", "Go back",
                    RectF(390f, 665f, 910f, 785f), fontSize = 37f,
                )
                button(
                    "ftp_warning_continue", "Use plain FTP",
                    RectF(960f, 665f, 1658f, 785f), fontSize = 37f,
                )
            }
            UiScreen.SFTP_DELETE_CONFIRM -> {
                canvas.drawRoundRect(310f, 160f, 1738f, 850f, 42f, 42f, panelPaint)
                text("Delete server profile?", 1024f, 285f, 62f)
                text(sftpProfile.displayName, 1024f, 385f, 40f)
                text(
                    "This removes the saved server details and credentials.",
                    1024f, 465f, 27f, color = colorTextMuted,
                )
                button(
                    "sftp_delete_cancel", "Cancel",
                    RectF(430f, 625f, 920f, 745f), fontSize = 38f,
                )
                button(
                    "sftp_delete_confirm", "Delete",
                    RectF(970f, 625f, 1618f, 745f), fontSize = 38f,
                )
            }
            UiScreen.SFTP_FILES -> {
                canvas.drawRoundRect(120f, 55f, 1928f, 975f, 42f, 42f, panelPaint)
                text(
                    "${sftpProfile.displayName}  /  ${sftpPath.takeLast(42)}",
                    170f, 128f, 38f, Paint.Align.LEFT,
                )
                scrollableList(sftpEntries.size, 92f, 75f) { index, bounds ->
                    val entry = sftpEntries[index]
                    browserEntryButton(
                        "sftp_entry_$index", entry.name,
                        if (entry.isDirectory) "" else formatBytes(entry.size),
                        RectF(210f, bounds.top, 1838f, bounds.bottom),
                        entry.isDirectory,
                    )
                }
                if (sftpEntries.isEmpty()) {
                    text(
                        "No video files or folders found.",
                        1024f, 450f, 42f,
                    )
                }
                button(
                    "sftp_files_back", "Up",
                    RectF(250f, 885f, 570f, 950f), fontSize = 36f,
                )
                text(
                    "${sftpEntries.size} items  •  Joystick ↑/↓ to scroll",
                    1780f, 930f, 30f, Paint.Align.RIGHT, colorTextMuted,
                )
            }
            UiScreen.SMB_KEYBOARD,
            UiScreen.SFTP_KEYBOARD,
            UiScreen.FILE_SEARCH_KEYBOARD -> {
                canvas.drawRoundRect(95f, 35f, 1953f, 995f, 42f, 42f, panelPaint)
                text(keyboardTitle(), 1024f, 105f, 50f)
                val raw = keyboardValue()
                val shown = if (
                    keyboardField == KeyboardField.PASSWORD ||
                    keyboardField == KeyboardField.SFTP_PASSWORD
                ) {
                    "•".repeat(raw.length)
                } else {
                    raw
                }
                canvas.drawRoundRect(
                    230f, 135f, 1818f, 225f, 20f, 20f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorSurfaceRaised },
                )
                text(shown.takeLast(52).ifBlank { " " }, 1024f, 195f, 42f)
                val letterRows = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm.-_@")
                val rows = if (keyboardSymbols) listOf(
                    "1234567890", "!@#$%&*+?", "()[]{}<>\\", "-_=/:;.,",
                ) else if (keyboardUppercase) letterRows.map { it.uppercase() } else letterRows
                rows.forEachIndexed { row, characters ->
                    val width = 1540f / characters.length
                    val left = 254f
                    characters.forEachIndexed { column, character ->
                        val x = left + column * width
                        button(
                            "key_char_${character.code}", character.toString(),
                            RectF(x, 275f + row * 112f, x + width - 10f, 365f + row * 112f),
                            fontSize = 42f,
                        )
                    }
                }
                button("key_case", if (keyboardUppercase) "lower" else "UPPER", RectF(120f, 755f, 390f, 850f), fontSize = 32f)
                button("key_symbols", if (keyboardSymbols) "ABC" else "Symbols", RectF(410f, 755f, 690f, 850f), fontSize = 32f)
                button("key_space", "Space", RectF(710f, 755f, 1040f, 850f), fontSize = 34f)
                button("key_backspace", "Backspace", RectF(1060f, 755f, 1410f, 850f), fontSize = 31f)
                button("key_clear", "Clear", RectF(1430f, 755f, 1710f, 850f), fontSize = 34f)
                button("key_cancel", "Cancel", RectF(480f, 885f, 900f, 960f), fontSize = 36f)
                button(
                    "key_done",
                    if (uiScreen == UiScreen.FILE_SEARCH_KEYBOARD) "Search" else "Done",
                    RectF(1148f, 885f, 1568f, 960f),
                    fontSize = 36f,
                )
            }
            UiScreen.PLAYBACK -> {
                canvas.drawRoundRect(PLAYBACK_TOOLBAR_BOUNDS, 34f, 34f, panelPaint)
                val duration = player.duration.takeIf { it > 0 } ?: 0L
                val fraction = if (duration > 0) player.currentPosition.toFloat() / duration else 0f
                val seekLeft = PLAYBACK_SEEK_LEFT
                val seekRight = PLAYBACK_SEEK_RIGHT
                val seekWidth = seekRight - seekLeft
                val seekBounds = RectF(seekLeft, 720f, seekRight, 790f)
                canvas.drawRoundRect(seekLeft, 749f, seekRight, 763f, 7f, 7f,
                    Paint().apply { color = colorOutline })
                canvas.drawRoundRect(seekLeft, 749f, seekLeft + seekWidth * fraction.coerceIn(0f, 1f), 763f, 7f, 7f,
                    Paint().apply { color = colorAccent })
                canvas.drawCircle(seekLeft + seekWidth * fraction.coerceIn(0f, 1f), 756f, 18f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorText })
                buttons += UiButton("seek_bar", "Seek", seekBounds)
                text(formatTime(player.currentPosition), 320f, 765f, 26f, Paint.Align.RIGHT, colorTextMuted)
                text(formatTime(duration), 1728f, 765f, 26f, Paint.Align.LEFT, colorTextMuted)
                val iconTop = 820f
                val iconBottom = 936f
                val standardButtonSize = iconBottom - iconTop
                fun standardBounds(left: Float) =
                    RectF(left, iconTop, left + standardButtonSize, iconBottom)

                // Left: navigation and language controls.
                iconButton("back_library", "Library", standardBounds(176f))
                iconButton(
                    "subtitles", "Subtitles", standardBounds(316f),
                    playbackPopover == PlaybackPopover.SUBTITLES ||
                        subtitleChoiceIndex > 1 ||
                        subtitleSyncMs != 0,
                )
                iconButton(
                    "audio", "Audio", standardBounds(456f),
                    playbackPopover == PlaybackPopover.AUDIO ||
                        audioChoiceIndex > 0 ||
                        audioSyncMs != 0,
                )

                // Center: the primary playback controls remain centered independently
                // of the number or width of controls in the side groups.
                iconButton("seek_back", "Back 10 seconds", RectF(816f, iconTop, 928f, iconBottom))
                iconButton(
                    "play_pause",
                    if (player.isPlaying) "Pause" else "Play",
                    RectF(952f, 806f, 1096f, 950f),
                    prominent = true,
                )
                iconButton("seek_forward", "Forward 10 seconds", RectF(1120f, iconTop, 1232f, iconBottom))

                // Right: presentation and player configuration.
                iconButton(
                    "projection", projectionLabel(), standardBounds(1616f),
                    playbackPopover == PlaybackPopover.PROJECTION ||
                        currentStereoMode != StereoMode.MONO ||
                        swapEyes ||
                        screenCurvature != DEFAULT_SCREEN_CURVATURE,
                )
                iconButton(
                    "settings", "Settings", standardBounds(1756f),
                        playbackPopover == PlaybackPopover.SETTINGS ||
                        playbackPopover == PlaybackPopover.CINEMA ||
                        playbackPopover == PlaybackPopover.COLOR_PIPELINE ||
                        playbackPopover == PlaybackPopover.ADAPTIVE_HDR ||
                        playbackPopover == PlaybackPopover.RENDER_QUALITY ||
                        pictureMode != PictureMode.AUTO ||
                        pictureBrightness != DEFAULT_PICTURE_BRIGHTNESS ||
                        pictureExposure != defaultExposureForCurrentColorMode() ||
                        pictureContrast != DEFAULT_PICTURE_CONTRAST ||
                        pictureSaturation != DEFAULT_PICTURE_SATURATION ||
                        pictureVibrance != DEFAULT_PICTURE_VIBRANCE ||
                        hdrMetadataMode != HdrMetadataMode.AUTO ||
                        gamutMappingMode != GamutMappingMode.CLIP ||
                        decoderMode != DecoderMode.AUTO,
                )

                val groupDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = colorOutline
                    strokeWidth = 2f
                }
                canvas.drawLine(622f, 838f, 622f, 918f, groupDividerPaint)
                canvas.drawLine(1426f, 838f, 1426f, 918f, groupDividerPaint)
                buttons.lastOrNull { it.id == hoveredButton }?.takeIf {
                    it.id != "seek_bar" &&
                        playbackPopover == PlaybackPopover.NONE &&
                        activeSlider == null
                }?.let { focused ->
                    val tooltipWidth = (focused.label.length * 17f + 52f).coerceIn(180f, 430f)
                    val tooltipCenter = focused.bounds.centerX().coerceIn(
                        PLAYBACK_TOOLBAR_BOUNDS.left + tooltipWidth / 2f + 20f,
                        PLAYBACK_TOOLBAR_BOUNDS.right - tooltipWidth / 2f - 20f,
                    )
                    val tooltip = RectF(
                        tooltipCenter - tooltipWidth / 2f, 778f,
                        tooltipCenter + tooltipWidth / 2f, 812f,
                    )
                    canvas.drawRoundRect(tooltip, 13f, 13f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = colorSurfaceRaised
                    })
                    text(focused.label, tooltipCenter, 804f, 23f, color = colorTextMuted)
                }

                when (playbackPopover) {
                    PlaybackPopover.SUBTITLES -> {
                        val bounds = PLAYBACK_LEFT_POPOVER_BOUNDS
                        popoverPanel(bounds, "Subtitles", 374f)
                        popoverTrackList(
                            subtitleChoices.size,
                            subtitleChoiceIndex,
                            { subtitleChoices[it].label },
                            { "subtitle_$it" },
                        )
                        subtitleDepthSlider(525f)
                        text(
                            when {
                                subtitleSyncMs < 0 -> "Timing  ${subtitleSyncMs} ms  •  earlier"
                                subtitleSyncMs > 0 -> "Timing  +${subtitleSyncMs} ms  •  later"
                                else -> "Timing  0 ms"
                            },
                            505f, 565f, 24f, color = colorTextMuted,
                        )
                        button(
                            "subtitle_sync_earlier_500", "−500",
                            RectF(190f, 585f, 300f, 640f), fontSize = 21f,
                        )
                        button(
                            "subtitle_sync_earlier_100", "−100",
                            RectF(310f, 585f, 420f, 640f), fontSize = 21f,
                        )
                        button(
                            "subtitle_sync_reset", "Reset",
                            RectF(430f, 585f, 580f, 640f),
                            subtitleSyncMs == 0, fontSize = 21f,
                        )
                        button(
                            "subtitle_sync_later_100", "+100",
                            RectF(590f, 585f, 700f, 640f), fontSize = 21f,
                        )
                        button(
                            "subtitle_sync_later_500", "+500",
                            RectF(710f, 585f, 820f, 640f), fontSize = 21f,
                        )
                        checkbox(
                            "subtitle_3d_enabled",
                            "3D subtitle",
                            RectF(190f, 646f, 820f, 708f),
                            subtitle3dEnabled,
                        )
                    }
                    PlaybackPopover.AUDIO -> {
                        val bounds = PLAYBACK_LEFT_POPOVER_BOUNDS
                        popoverPanel(bounds, "Audio", 514f)
                        popoverTrackList(
                            audioChoices.size,
                            audioChoiceIndex,
                            { audioChoices[it].label },
                            { "audio_track_$it" },
                        )
                        text(
                            when {
                                audioSyncMs < 0 -> "Timing  ${audioSyncMs} ms  •  earlier"
                                audioSyncMs > 0 -> "Timing  +${audioSyncMs} ms  •  later"
                                else -> "Timing  0 ms"
                            },
                            505f, 565f, 24f, color = colorTextMuted,
                        )
                        button(
                            "sync_earlier_100", "−100",
                            RectF(190f, 585f, 310f, 640f), fontSize = 21f,
                        )
                        button(
                            "sync_earlier_25", "−25",
                            RectF(320f, 585f, 440f, 640f), fontSize = 21f,
                        )
                        button(
                            "sync_reset", "Reset",
                            RectF(450f, 585f, 560f, 640f),
                            audioSyncMs == 0, fontSize = 21f,
                        )
                        button(
                            "sync_later_25", "+25",
                            RectF(570f, 585f, 690f, 640f), fontSize = 21f,
                        )
                        button(
                            "sync_later_100", "+100",
                            RectF(700f, 585f, 820f, 640f), fontSize = 21f,
                        )
                    }
                    PlaybackPopover.PROJECTION -> {
                        val bounds = PLAYBACK_PROJECTION_POPOVER_BOUNDS
                        popoverPanel(bounds, "Projection", 1674f)
                        text(
                            "View",
                            1212f,
                            180f,
                            23f,
                            Paint.Align.LEFT,
                            colorTextMuted,
                        )
                        button(
                            "projection_view_flat", "Flat",
                            RectF(1210f, 195f, 1418f, 260f),
                            currentProjection == VideoProjection.FLAT,
                            fontSize = 23f,
                        )
                        button(
                            "projection_view_180", "VR180",
                            RectF(1430f, 195f, 1638f, 260f),
                            currentProjection ==
                                VideoProjection.EQUIRECTANGULAR_180,
                            fontSize = 23f,
                        )
                        button(
                            "projection_view_360", "VR360",
                            RectF(1650f, 195f, 1870f, 260f),
                            currentProjection ==
                                VideoProjection.EQUIRECTANGULAR_360,
                            fontSize = 23f,
                        )
                        text(
                            "Stereo layout",
                            1212f,
                            300f,
                            23f,
                            Paint.Align.LEFT,
                            colorTextMuted,
                        )
                        button(
                            "mode_2d", "2D",
                            RectF(1210f, 315f, 1418f, 380f),
                            currentStereoMode == StereoMode.MONO,
                            fontSize = 22f,
                        )
                        button(
                            "mode_full_sbs", "Full SBS",
                            RectF(1430f, 315f, 1638f, 380f),
                            currentStereoMode == StereoMode.SBS,
                            fontSize = 22f,
                        )
                        button(
                            "mode_half_sbs", "Half SBS",
                            RectF(1650f, 315f, 1870f, 380f),
                            currentStereoMode == StereoMode.HALF_SBS,
                            fontSize = 22f,
                        )
                        button(
                            "mode_full_ou", "Full OU",
                            RectF(1210f, 392f, 1418f, 457f),
                            currentStereoMode == StereoMode.OVER_UNDER,
                            fontSize = 22f,
                        )
                        button(
                            "mode_half_ou", "Half OU",
                            RectF(1430f, 392f, 1638f, 457f),
                            currentStereoMode ==
                                StereoMode.HALF_OVER_UNDER,
                            fontSize = 22f,
                        )
                        button(
                            "swap_eyes",
                            if (swapEyes) "Eyes swapped" else "Swap eyes",
                            RectF(1650f, 392f, 1870f, 457f),
                            swapEyes,
                            fontSize = 22f,
                        )
                        if (currentProjection == VideoProjection.FLAT) {
                            projectionCurvatureSlider(535f)
                        } else {
                            text(
                                "Orientation",
                                1212f,
                                505f,
                                23f,
                                Paint.Align.LEFT,
                                colorTextMuted,
                            )
                            button(
                                "immersive_yaw_left", "Yaw −15°",
                                RectF(1210f, 522f, 1418f, 580f),
                                fontSize = 21f,
                            )
                            button(
                                "immersive_recenter", "Recenter",
                                RectF(1430f, 522f, 1638f, 580f),
                                fontSize = 21f,
                            )
                            button(
                                "immersive_yaw_right", "Yaw +15°",
                                RectF(1650f, 522f, 1870f, 580f),
                                fontSize = 21f,
                            )
                            button(
                                "immersive_pitch_down", "Pitch −10°",
                                RectF(1320f, 594f, 1530f, 652f),
                                fontSize = 21f,
                            )
                            button(
                                "immersive_pitch_up", "Pitch +10°",
                                RectF(1550f, 594f, 1760f, 652f),
                                fontSize = 21f,
                            )
                            text(
                                "Yaw %.0f°  •  Pitch %.0f°".format(
                                    Locale.US,
                                    immersiveYawDegrees,
                                    immersivePitchDegrees,
                                ),
                                1540f,
                                690f,
                                21f,
                                color = colorTextMuted,
                            )
                        }
                    }
                    PlaybackPopover.SETTINGS -> {
                        val bounds = PLAYBACK_SETTINGS_POPOVER_BOUNDS
                        popoverPanel(bounds, "Settings", 1814f)
                        val detectedStatus = detectedVideoTransfer.label +
                            if (detectedVideoPrimaries == VideoPrimaries.BT2020) {
                                " • BT.2020"
                            } else {
                                " • BT.709"
                            }
                        text(
                            "Detected: $detectedStatus",
                            1860f, 109f, 21f, Paint.Align.RIGHT, colorTextMuted,
                        )
                        text("Decoder", 975f, 174f, 24f, Paint.Align.LEFT)
                        button(
                            "decoder_auto", "Auto",
                            RectF(1200f, 140f, 1410f, 192f),
                            decoderMode == DecoderMode.AUTO, fontSize = 22f,
                        )
                        button(
                            "decoder_hardware", "Hardware",
                            RectF(1420f, 140f, 1630f, 192f),
                            decoderMode == DecoderMode.HARDWARE, fontSize = 22f,
                        )
                        button(
                            "decoder_software", "Software",
                            RectF(1640f, 140f, 1860f, 192f),
                            decoderMode == DecoderMode.SOFTWARE, fontSize = 22f,
                        )
                        text("Color mode", 975f, 239f, 24f, Paint.Align.LEFT)
                        val compactModes = listOf(
                            Triple("picture_mode_auto", "Auto", PictureMode.AUTO),
                            Triple("picture_mode_sdr", "SDR", PictureMode.FORCE_SDR),
                            Triple("picture_mode_pq", "HDR10", PictureMode.FORCE_PQ),
                            Triple("picture_mode_hlg", "HLG", PictureMode.FORCE_HLG),
                            Triple("picture_mode_original", "Original", PictureMode.ORIGINAL),
                        )
                        val compactModeBounds = listOf(
                            RectF(1120f, 205f, 1258f, 257f),
                            RectF(1266f, 205f, 1404f, 257f),
                            RectF(1412f, 205f, 1550f, 257f),
                            RectF(1558f, 205f, 1696f, 257f),
                            RectF(1704f, 205f, 1860f, 257f),
                        )
                        compactModes.forEachIndexed { index, (id, label, mode) ->
                            button(
                                id, label, compactModeBounds[index],
                                pictureMode == mode, fontSize = 20f,
                            )
                        }
                        compactSettingsSlider(
                            "settings_slider_brightness", "Brightness",
                            "%+.1f%%".format(Locale.US, pictureBrightness * 100f),
                            pictureBrightness,
                            PICTURE_BRIGHTNESS_MIN,
                            PICTURE_BRIGHTNESS_MAX,
                            286f,
                        )
                        compactSettingsSlider(
                            "settings_slider_exposure", "Exposure",
                            "%+.2f EV".format(Locale.US, pictureExposure),
                            pictureExposure,
                            PICTURE_EXPOSURE_MIN,
                            PICTURE_EXPOSURE_MAX,
                            326f,
                        )
                        compactSettingsSlider(
                            "settings_slider_contrast", "Contrast",
                            "%.2f".format(Locale.US, pictureContrast),
                            pictureContrast,
                            PICTURE_CONTRAST_MIN,
                            PICTURE_CONTRAST_MAX,
                            366f,
                        )
                        compactSettingsSlider(
                            "settings_slider_vibrance", "Vibrance",
                            "%+.0f".format(Locale.US, pictureVibrance * 100f),
                            pictureVibrance,
                            PICTURE_VIBRANCE_MIN,
                            PICTURE_VIBRANCE_MAX,
                            406f,
                        )
                        compactSettingsSlider(
                            "settings_slider_saturation", "Saturation",
                            "%.2f".format(Locale.US, pictureSaturation),
                            pictureSaturation,
                            PICTURE_SATURATION_MIN,
                            PICTURE_SATURATION_MAX,
                            446f,
                        )
                        compactSettingsSlider(
                            "settings_slider_tone_peak", "HDR target",
                            "%.0f nits".format(Locale.US, toneMapPeakNits),
                            toneMapPeakNits,
                            TONE_MAP_PEAK_NITS_MIN,
                            TONE_MAP_PEAK_NITS_MAX,
                            506f,
                            logarithmic = true,
                        )
                        compactSettingsSlider(
                            "settings_slider_scene", "Scene brightness",
                            "%.0f%%".format(Locale.US, sceneBrightness * 100f),
                            sceneBrightness,
                            SCENE_BRIGHTNESS_MIN,
                            SCENE_BRIGHTNESS_MAX,
                            566f,
                        )
                        button(
                            "cinema_tune", "Tune",
                            RectF(975f, 605f, 1137f, 660f),
                            fontSize = 17f,
                        )
                        button(
                            "color_pipeline", "HDR / gamut",
                            RectF(1147f, 605f, 1309f, 660f),
                            fontSize = 16f,
                        )
                        button(
                            "adaptive_hdr", "Adaptive HDR",
                            RectF(1319f, 605f, 1481f, 660f),
                            adaptiveToneMapEnabled,
                            fontSize = 16f,
                        )
                        button(
                            "render_quality", "Resolution",
                            RectF(1491f, 605f, 1653f, 660f),
                            fontSize = 16f,
                        )
                        button(
                            "picture_reset", "Reset sliders",
                            RectF(1663f, 605f, 1860f, 660f),
                            fontSize = 16f,
                        )
                    }
                    PlaybackPopover.CINEMA -> {
                        val cinemaTuning = activeCinemaTuning()
                        val bounds = PLAYBACK_SETTINGS_POPOVER_BOUNDS
                        popoverPanel(bounds, "Cinema tuning", 1814f)
                        text(
                            "Global ${activeCinemaTransfer().label} profile • black and white endpoints remain fixed",
                            975f, 122f, 21f, Paint.Align.LEFT, colorTextMuted,
                        )
                        compactSettingsSlider(
                            "settings_slider_tone_depth", "Tone depth",
                            "%.0f".format(
                                Locale.US,
                                cinemaTuning.toneDepth * CINEMA_TONE_DEPTH_DISPLAY_SCALE,
                            ),
                            cinemaTuning.toneDepth,
                            CINEMA_TONE_DEPTH_MIN,
                            CINEMA_TONE_DEPTH_MAX,
                            225f,
                        )
                        compactSettingsSlider(
                            "settings_slider_midtones", "Midtone brightness",
                            "%+.2f EV".format(Locale.US, cinemaTuning.midtoneEv),
                            cinemaTuning.midtoneEv,
                            CINEMA_MIDTONE_EV_MIN,
                            CINEMA_MIDTONE_EV_MAX,
                            325f,
                        )
                        compactSettingsSlider(
                            "settings_slider_color_intensity", "Color intensity",
                            "%+.0f%%".format(
                                Locale.US,
                                (cinemaTuning.colorIntensity - 1f) * 100f,
                            ),
                            cinemaTuning.colorIntensity,
                            CINEMA_COLOR_INTENSITY_MIN,
                            CINEMA_COLOR_INTENSITY_MAX,
                            425f,
                        )
                        text(
                            "Tone depth adds separation • Midtones changes ordinary scene brightness",
                            975f, 525f, 20f, Paint.Align.LEFT, colorTextMuted,
                        )
                        text(
                            "Color intensity adjusts perceptual chroma after SDR/HDR conversion",
                            975f, 555f, 20f, Paint.Align.LEFT, colorTextMuted,
                        )
                        button(
                            "cinema_reset", "Reset Cinema",
                            RectF(1198f, 595f, 1518f, 655f),
                            fontSize = 22f,
                        )
                        button(
                            "cinema_back", "Back",
                            RectF(1530f, 595f, 1860f, 655f),
                            fontSize = 22f,
                        )
                    }
                    PlaybackPopover.COLOR_PIPELINE -> {
                        val bounds = PLAYBACK_SETTINGS_POPOVER_BOUNDS
                        popoverPanel(bounds, "HDR metadata & gamut", 1814f)
                        text(
                            "HDR10 metadata",
                            975f, 132f, 24f, Paint.Align.LEFT,
                        )
                        text(
                            hdrMetadataSummary(),
                            1860f, 132f, 20f, Paint.Align.RIGHT, colorTextMuted,
                        )
                        button(
                            "hdr_metadata_auto", "Auto",
                            RectF(975f, 160f, 1255f, 220f),
                            hdrMetadataMode == HdrMetadataMode.AUTO,
                            fontSize = 21f,
                        )
                        button(
                            "hdr_metadata_ignore", "Ignore",
                            RectF(1265f, 160f, 1545f, 220f),
                            hdrMetadataMode == HdrMetadataMode.IGNORE,
                            fontSize = 21f,
                        )
                        button(
                            "hdr_metadata_manual", "Manual",
                            RectF(1555f, 160f, 1860f, 220f),
                            hdrMetadataMode == HdrMetadataMode.MANUAL,
                            fontSize = 21f,
                        )
                        text(
                            hdrMetadataModeDescription(),
                            975f, 260f, 20f, Paint.Align.LEFT, colorTextMuted,
                        )
                        if (hdrMetadataMode == HdrMetadataMode.MANUAL) {
                            compactSettingsSlider(
                                "settings_slider_manual_peak", "Content peak",
                                "%.0f nits".format(Locale.US, hdrManualPeakNits),
                                hdrManualPeakNits,
                                HDR_SOURCE_PEAK_NITS_MIN,
                                HDR_SOURCE_PEAK_NITS_MAX,
                                325f,
                                logarithmic = true,
                                valueLabelX = 1800f,
                            )
                        }
                        text("BT.2020 gamut mapping", 975f, 370f, 24f, Paint.Align.LEFT)
                        button(
                            "gamut_mapping_perceptual", "Perceptual",
                            RectF(1200f, 390f, 1520f, 450f),
                            gamutMappingMode == GamutMappingMode.PERCEPTUAL,
                            fontSize = 21f,
                        )
                        button(
                            "gamut_mapping_clip", "Clip",
                            RectF(1530f, 390f, 1860f, 450f),
                            gamutMappingMode == GamutMappingMode.CLIP,
                            fontSize = 21f,
                        )
                        text(
                            "Perceptual preserves luminance and color direction near the BT.709 boundary.",
                            975f, 495f, 20f, Paint.Align.LEFT, colorTextMuted,
                        )
                        button(
                            "color_pipeline_back", "Back",
                            RectF(1530f, 590f, 1860f, 650f),
                            fontSize = 22f,
                        )
                    }
                    PlaybackPopover.ADAPTIVE_HDR -> {
                        val bounds = PLAYBACK_SETTINGS_POPOVER_BOUNDS
                        popoverPanel(bounds, "Adaptive HDR tone mapping", 1814f)
                        text(
                            "Optional scene analysis for HDR10 / PQ only",
                            975f, 122f, 21f, Paint.Align.LEFT, colorTextMuted,
                        )
                        button(
                            "adaptive_hdr_off", "Off",
                            RectF(1200f, 150f, 1515f, 212f),
                            !adaptiveToneMapEnabled,
                            fontSize = 22f,
                        )
                        button(
                            "adaptive_hdr_on", "On",
                            RectF(1525f, 150f, 1860f, 212f),
                            adaptiveToneMapEnabled,
                            fontSize = 22f,
                        )
                        compactSettingsSlider(
                            "settings_slider_adaptive_min", "Range minimum",
                            "%.0f nits".format(Locale.US, adaptiveToneMapMinNits),
                            adaptiveToneMapMinNits,
                            ADAPTIVE_TONE_MAP_NITS_MIN,
                            ADAPTIVE_TONE_MAP_NITS_MAX,
                            240f,
                        )
                        compactSettingsSlider(
                            "settings_slider_adaptive_max", "Range maximum",
                            "%.0f nits".format(Locale.US, adaptiveToneMapMaxNits),
                            adaptiveToneMapMaxNits,
                            ADAPTIVE_TONE_MAP_NITS_MIN,
                            ADAPTIVE_TONE_MAP_NITS_MAX,
                            305f,
                        )
                        compactSettingsSlider(
                            "settings_slider_tone_priority", "Tone priority",
                            "%.0f%%".format(
                                Locale.US,
                                adaptiveToneMapPriority * 100f,
                            ),
                            adaptiveToneMapPriority,
                            ADAPTIVE_TONE_MAP_PRIORITY_MIN,
                            ADAPTIVE_TONE_MAP_PRIORITY_MAX,
                            375f,
                        )
                        text(
                            "Shadows",
                            SETTINGS_SLIDER_LEFT, 412f, 17f,
                            Paint.Align.LEFT, colorTextMuted,
                        )
                        text(
                            "Mid",
                            (SETTINGS_SLIDER_LEFT + SETTINGS_SLIDER_RIGHT) / 2f,
                            412f, 17f, Paint.Align.CENTER, colorTextMuted,
                        )
                        text(
                            "Highlights",
                            SETTINGS_SLIDER_RIGHT, 412f, 17f,
                            Paint.Align.RIGHT, colorTextMuted,
                        )
                        text(
                            "Adaptation",
                            975f, 475f, 24f, Paint.Align.LEFT,
                        )
                        button(
                            "adaptive_hdr_cinema", "Cinema",
                            RectF(1200f, 435f, 1515f, 500f),
                            adaptiveToneMapMode == AdaptiveToneMapMode.CINEMA,
                            fontSize = 21f,
                        )
                        button(
                            "adaptive_hdr_smooth", "Smooth",
                            RectF(1525f, 435f, 1860f, 500f),
                            adaptiveToneMapMode == AdaptiveToneMapMode.SMOOTH,
                            fontSize = 21f,
                        )
                        val effectiveLabel = when {
                            !adaptiveToneMapEnabled ->
                                "Effective target: static ${toneMapPeakNits.toInt()} nits"
                            activeCinemaTransfer() != VideoTransfer.PQ ->
                                "Effective target: waiting for HDR10 / PQ"
                            else ->
                                "Effective target: ${nativeGetAdaptiveToneMapTarget().toInt()} nits"
                        }
                        text(
                            effectiveLabel,
                            975f, 540f, 22f, Paint.Align.LEFT, colorTextMuted,
                        )
                        text(
                            if (adaptiveToneMapMode == AdaptiveToneMapMode.SMOOTH) {
                                "Smooth uses heavier 4–8 second within-shot averaging."
                            } else {
                                "Cinema responds in 2–5 seconds; hard cuts still switch immediately."
                            },
                            975f, 570f, 19f, Paint.Align.LEFT, colorTextMuted,
                        )
                        button(
                            "adaptive_hdr_back", "Back",
                            RectF(1530f, 605f, 1860f, 655f),
                            fontSize = 22f,
                        )
                    }
                    PlaybackPopover.RENDER_QUALITY -> {
                        val bounds = PLAYBACK_SETTINGS_POPOVER_BOUNDS
                        popoverPanel(bounds, "Render resolution", 1814f)
                        text(
                            "Supersamples both eyes before the OpenXR compositor displays them.",
                            975f, 132f, 21f, Paint.Align.LEFT, colorTextMuted,
                        )
                        compactSettingsSlider(
                            "settings_slider_resolution_scale", "Resolution scale",
                            "%.2fx".format(Locale.US, renderResolutionScale),
                            renderResolutionScale,
                            RENDER_RESOLUTION_SCALE_MIN,
                            RENDER_RESOLUTION_SCALE_MAX,
                            245f,
                        )
                        text(
                            "Higher values can sharpen 4K and 3D video, subtitles, and curved screens.",
                            975f, 345f, 20f, Paint.Align.LEFT, colorTextMuted,
                        )
                        text(
                            "GPU work grows quickly: 1.5x uses 2.25x as many rendered pixels.",
                            975f, 385f, 20f, Paint.Align.LEFT, colorTextMuted,
                        )
                        text(
                            "The OpenXR runtime may clamp the selected value. Applies after restarting the app.",
                            975f, 425f, 20f, Paint.Align.LEFT, colorTextMuted,
                        )
                        button(
                            "render_quality_back", "Back",
                            RectF(1530f, 590f, 1860f, 650f),
                            fontSize = 22f,
                        )
                    }
                    PlaybackPopover.NONE -> Unit
                }
            }
            UiScreen.PROJECTION -> {
                canvas.drawRoundRect(250f, 55f, 1798f, 995f, 42f, 42f, panelPaint)
                text("Projection", 1024f, 155f, 68f)
                button("mode_2d", "2D", RectF(390f, 205f, 840f, 330f), currentStereoMode == StereoMode.MONO)
                button("mode_full_sbs", "Full SBS", RectF(880f, 205f, 1658f, 330f), currentStereoMode == StereoMode.SBS)
                button("mode_half_sbs", "Half SBS", RectF(390f, 365f, 840f, 490f), currentStereoMode == StereoMode.HALF_SBS)
                button("mode_full_ou", "Full OU / TB", RectF(880f, 365f, 1658f, 490f), currentStereoMode == StereoMode.OVER_UNDER)
                button("mode_half_ou", "Half OU / TB", RectF(390f, 525f, 840f, 650f), currentStereoMode == StereoMode.HALF_OVER_UNDER)
                button("swap_eyes", if (swapEyes) "Eye order: swapped" else "Eye order: normal", RectF(880f, 525f, 1658f, 650f), swapEyes)
                button("modal_back", "Back", RectF(800f, 790f, 1248f, 880f))
            }
            UiScreen.PICTURE -> {
                canvas.drawRoundRect(250f, 35f, 1798f, 990f, 42f, 42f, panelPaint)
                text("Picture", 1024f, 120f, 62f)
                val detectedStatus = detectedVideoTransfer.label +
                    if (detectedVideoPrimaries == VideoPrimaries.BT2020) " • BT.2020" else " • BT.709"
                val colorStatus = when (pictureMode) {
                    PictureMode.ORIGINAL -> "Original decoder output • adjustments still apply"
                    PictureMode.AUTO -> "Auto detected: $detectedStatus"
                    PictureMode.FORCE_SDR -> "Manual override: SDR • BT.709"
                    PictureMode.FORCE_PQ -> "Manual override: HDR10 / PQ • BT.2020"
                    PictureMode.FORCE_HLG -> "Manual override: HDR / HLG • BT.2020"
                }
                text(colorStatus, 1024f, 177f, 30f, color = colorTextMuted)
                val modeLabels = listOf(
                    Triple("picture_mode_auto", "Auto", PictureMode.AUTO),
                    Triple("picture_mode_sdr", "SDR", PictureMode.FORCE_SDR),
                    Triple("picture_mode_pq", "HDR10", PictureMode.FORCE_PQ),
                    Triple("picture_mode_hlg", "HLG", PictureMode.FORCE_HLG),
                    Triple("picture_mode_original", "Original", PictureMode.ORIGINAL),
                )
                modeLabels.forEachIndexed { index, (id, label, mode) ->
                    val left = 325f + index * 282f
                    button(
                        id, label, RectF(left, 205f, left + 252f, 282f),
                        pictureMode == mode, fontSize = 27f,
                    )
                }
                slider(
                    "picture_slider_brightness", "Brightness",
                    "%+.1f%%".format(Locale.US, pictureBrightness * 100f),
                    pictureBrightness, PICTURE_BRIGHTNESS_MIN, PICTURE_BRIGHTNESS_MAX, 310f,
                )
                slider(
                    "picture_slider_exposure", "Exposure",
                    "%+.2f EV".format(Locale.US, pictureExposure),
                    pictureExposure, PICTURE_EXPOSURE_MIN, PICTURE_EXPOSURE_MAX, 380f,
                )
                slider(
                    "picture_slider_contrast", "Contrast",
                    "%.2f".format(Locale.US, pictureContrast),
                    pictureContrast, PICTURE_CONTRAST_MIN, PICTURE_CONTRAST_MAX, 450f,
                )
                slider(
                    "picture_slider_vibrance", "Vibrance",
                    "%+.0f".format(Locale.US, pictureVibrance * 100f),
                    pictureVibrance, PICTURE_VIBRANCE_MIN, PICTURE_VIBRANCE_MAX, 520f,
                )
                slider(
                    "picture_slider_saturation", "Saturation",
                    "%.2f".format(Locale.US, pictureSaturation),
                    pictureSaturation, PICTURE_SATURATION_MIN, PICTURE_SATURATION_MAX, 590f,
                )
                slider(
                    "picture_slider_tone_peak", "HDR display target",
                    "%.0f nits".format(Locale.US, toneMapPeakNits),
                    toneMapPeakNits,
                    TONE_MAP_PEAK_NITS_MIN,
                    TONE_MAP_PEAK_NITS_MAX,
                    660f,
                    logarithmic = true,
                )
                slider(
                    "picture_slider_scene", "Scene brightness",
                    "%.0f%%".format(Locale.US, sceneBrightness * 100f),
                    sceneBrightness, SCENE_BRIGHTNESS_MIN, SCENE_BRIGHTNESS_MAX, 730f,
                )
                text(
                    "Auto tone-maps HDR10 and HLG; decoder range expansion is intentionally avoided.",
                    1024f, 855f, 22f, color = colorTextMuted,
                )
                button("picture_reset", "Reset sliders", RectF(365f, 885f, 720f, 965f), fontSize = 32f)
                button("decoder", "Decoder", RectF(847f, 885f, 1202f, 965f), fontSize = 32f)
                button("modal_back", "Back", RectF(1329f, 885f, 1684f, 965f), fontSize = 32f)
            }
            UiScreen.DECODER -> {
                canvas.drawRoundRect(250f, 55f, 1798f, 995f, 42f, 42f, panelPaint)
                text("Video decoder", 1024f, 145f, 62f)
                text(
                    "Auto uses hardware for efficiency and switches to software if timing is abnormal.",
                    1024f, 205f, 30f, color = colorTextMuted,
                )
                button(
                    "decoder_auto", "Auto",
                    RectF(350f, 330f, 860f, 475f),
                    decoderMode == DecoderMode.AUTO,
                )
                button(
                    "decoder_hardware", "Hardware",
                    RectF(910f, 330f, 1698f, 475f),
                    decoderMode == DecoderMode.HARDWARE,
                )
                button(
                    "decoder_software", "Software",
                    RectF(350f, 535f, 1698f, 680f),
                    decoderMode == DecoderMode.SOFTWARE,
                )
                text(
                    "Active now: ${effectiveDecoderMode.label}",
                    1024f, 755f, 31f, color = colorTextMuted,
                )
                button("modal_back", "Back", RectF(800f, 835f, 1248f, 920f), fontSize = 34f)
            }
            UiScreen.AUDIO_SYNC -> {
                canvas.drawRoundRect(330f, 120f, 1718f, 920f, 42f, 42f, panelPaint)
                text("Audio sync", 1024f, 220f, 68f)
                val syncLabel = when {
                    audioSyncMs < 0 -> "${audioSyncMs} ms  •  audio earlier"
                    audioSyncMs > 0 -> "+${audioSyncMs} ms  •  audio later"
                    else -> "0 ms  •  original timing"
                }
                text(syncLabel, 1024f, 325f, 42f)
                text(
                    "Use negative values when sound is heard after the picture.",
                    1024f, 385f, 29f, color = colorTextMuted,
                )
                button("sync_earlier_100", "−100 ms", RectF(380f, 445f, 620f, 565f), fontSize = 34f)
                button("sync_earlier_25", "−25 ms", RectF(650f, 445f, 890f, 565f), fontSize = 34f)
                button("sync_reset", "Reset", RectF(920f, 445f, 1128f, 565f), audioSyncMs == 0, fontSize = 34f)
                button("sync_later_25", "+25 ms", RectF(1158f, 445f, 1398f, 565f), fontSize = 34f)
                button("sync_later_100", "+100 ms", RectF(1428f, 445f, 1668f, 565f), fontSize = 34f)
                button("modal_back", "Back", RectF(800f, 650f, 1248f, 755f), fontSize = 37f)
            }
            UiScreen.AUDIO_TRACKS -> {
                canvas.drawRoundRect(280f, 70f, 1768f, 960f, 42f, 42f, panelPaint)
                text("Audio track", 1024f, 175f, 72f)
                audioChoices.take(7).forEachIndexed { index, choice ->
                    val top = 220f + index * 92f
                    button(
                        "audio_track_$index",
                        choice.label,
                        RectF(420f, top, 1628f, top + 74f),
                        audioChoiceIndex == index,
                        fontSize = 34f,
                    )
                }
                button("audio_sync", "Audio timing", RectF(540f, 875f, 990f, 940f), fontSize = 29f)
                button("modal_back", "Back", RectF(1058f, 875f, 1508f, 940f), fontSize = 29f)
            }
            UiScreen.SUBTITLES -> {
                canvas.drawRoundRect(280f, 70f, 1768f, 960f, 42f, 42f, panelPaint)
                text("Subtitle track", 1024f, 175f, 72f)
                subtitleChoices.take(7).forEachIndexed { index, choice ->
                    val top = 220f + index * 92f
                    button(
                        "subtitle_$index",
                        choice.label,
                        RectF(420f, top, 1628f, top + 74f),
                        subtitleChoiceIndex == index,
                    )
                }
                button("modal_back", "Back", RectF(800f, 875f, 1248f, 940f))
            }
        }

        if (
            browserSortPopoverOpen &&
            (uiScreen == UiScreen.LOCAL_FILES || uiScreen == UiScreen.SMB_FILES)
        ) {
            browserSortPopover()
        }

        transientMessage?.let {
            canvas.drawRoundRect(280f, 35f, 1768f, 115f, 24f, 24f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(245, 91, 31, 39) })
            canvas.drawRoundRect(280f, 35f, 1768f, 115f, 24f, 24f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(214, 86, 91)
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                })
            text(it, 1024f, 91f, 36f)
        }
        uiButtons = buttons
        nativeSetMenuInteractive(true)
        nativeSetSubtitle(bitmap)
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
        else -> "$bytes B"
    }

    private fun resumePosition(state: MediaStateStore.PlaybackState?): Long? {
        val position = state?.positionMs ?: return null
        val duration = state.durationMs
        if (position < MINIMUM_RESUME_POSITION_MS) return null
        if (duration != null && duration > 0L) {
            val nearEndWindow = maxOf(MINIMUM_REMAINING_MS, (duration * END_FRACTION).toLong())
            if (duration - position <= nearEndWindow) return null
        }
        return position
    }

    private fun resetPlaybackHealthMonitor() {
        healthLastElapsedMs = 0L
        healthLastPositionMs = 0L
        healthAnchorElapsedMs = 0L
        healthAnchorPositionMs = 0L
        abnormalClockSamples = 0
    }

    private fun saveCurrentMediaState(positionOverrideMs: Long? = null) {
        if (!mediaLoaded || !::player.isInitialized || spec.uri.isBlank()) return
        val nativeTransform = nativeGetScreenTransform()
        if (nativeTransform.size != 4) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        val position = positionOverrideMs
            ?: if (playbackCompleted) 0L else player.currentPosition.coerceAtLeast(0L)
        mediaStateStore.savePlaybackState(
            spec = spec,
            positionMs = position,
            durationMs = duration,
            transform = MediaStateStore.ScreenTransform(
                x = nativeTransform[0],
                y = nativeTransform[1],
                z = nativeTransform[2],
                halfHeight = nativeTransform[3],
            ),
        )
    }

    private fun applyVideoColorInfo(format: Format) {
        val color = format.colorInfo
        detectedVideoTransfer = when (color?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> VideoTransfer.PQ
            C.COLOR_TRANSFER_HLG -> VideoTransfer.HLG
            else -> VideoTransfer.SDR
        }
        detectedVideoPrimaries =
            if (color?.colorSpace == C.COLOR_SPACE_BT2020) VideoPrimaries.BT2020
            else VideoPrimaries.BT709
        detectedHdrStaticMetadata = parseHdrStaticMetadata(color?.hdrStaticInfo)
        applyVideoColorPipeline()
        applyCinemaSettings(persist = false)
        if (
            (detectedVideoTransfer == VideoTransfer.PQ ||
                detectedVideoTransfer == VideoTransfer.HLG) &&
            pictureMode == PictureMode.AUTO &&
            !currentMediaHasSavedPictureSettings &&
            !hdrDefaultExposureApplied
        ) {
            pictureExposure = DEFAULT_HDR_EXPOSURE
            hdrDefaultExposureApplied = true
            applyPictureSettings(persist = false)
        }
        Log.i(
            "DreamPlayerColor",
            "Managed color input: ${detectedVideoTransfer.label}, $detectedVideoPrimaries, " +
                "range=${color?.colorRange}, lumaBits=${color?.lumaBitdepth}, " +
                "chromaBits=${color?.chromaBitdepth}, ${detectedHdrStaticMetadata.status}",
        )
        if (
            uiVisible &&
            (
                uiScreen == UiScreen.PICTURE ||
                    playbackPopover == PlaybackPopover.COLOR_PIPELINE
            )
        ) {
            renderVrMenu()
        }
    }

    private fun parseHdrStaticMetadata(data: ByteArray?): HdrStaticMetadata {
        if (data == null) return HdrStaticMetadata.NONE
        if (data.size < HDR_STATIC_INFO_SIZE_BYTES) {
            return HdrStaticMetadata(null, null, null, "Rejected: ${data.size}-byte metadata")
        }
        if (data[0].toInt() != HDR_STATIC_INFO_TYPE_1) {
            return HdrStaticMetadata(
                null,
                null,
                null,
                "Rejected: unsupported descriptor ${data[0].toInt() and 0xff}",
            )
        }
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        fun nitsAt(offset: Int): Float? {
            val value = buffer.getShort(offset).toInt() and 0xffff
            return value.toFloat().takeIf {
                it in HDR_METADATA_NITS_MIN..HDR_METADATA_NITS_MAX
            }
        }
        val masteringPeak = nitsAt(HDR_MASTERING_PEAK_OFFSET)
        val maxCll = nitsAt(HDR_MAX_CLL_OFFSET)
        val rawMaxFall = nitsAt(HDR_MAX_FALL_OFFSET)
        val sourcePeak = maxCll ?: masteringPeak
        val maxFall = rawMaxFall?.takeIf {
            sourcePeak == null || it <= sourcePeak
        }
        if (sourcePeak == null && maxFall == null) {
            return HdrStaticMetadata(
                null,
                null,
                null,
                "Rejected: empty or implausible values",
            )
        }
        val details = buildList {
            maxCll?.let { add("MaxCLL ${it.toInt()}") }
            maxFall?.let { add("MaxFALL ${it.toInt()}") }
            masteringPeak?.let { add("master ${it.toInt()}") }
        }.joinToString(" / ")
        return HdrStaticMetadata(
            masteringPeakNits = masteringPeak,
            maxCllNits = maxCll,
            maxFallNits = maxFall,
            status = details,
        )
    }

    private fun effectiveHdrSourcePeakNits(): Float = when (hdrMetadataMode) {
        HdrMetadataMode.AUTO ->
            detectedHdrStaticMetadata.maxCllNits
                ?: detectedHdrStaticMetadata.masteringPeakNits
                ?: DEFAULT_HDR_SOURCE_PEAK_NITS
        HdrMetadataMode.IGNORE -> DEFAULT_HDR_SOURCE_PEAK_NITS
        HdrMetadataMode.MANUAL -> hdrManualPeakNits
    }

    private fun effectiveHdrMaxFallNits(): Float =
        if (hdrMetadataMode == HdrMetadataMode.AUTO) {
            detectedHdrStaticMetadata.maxFallNits ?: 0f
        } else {
            0f
        }

    private fun applyVideoColorPipeline() {
        nativeSetVideoColorInfo(
            detectedVideoTransfer.wireValue,
            detectedVideoPrimaries.wireValue,
            effectiveHdrSourcePeakNits(),
            effectiveHdrMaxFallNits(),
            gamutMappingMode.wireValue,
        )
    }

    private fun persistHdrMetadataSettings() {
        if (mediaLoaded && spec.uri.isNotBlank()) {
            mediaStateStore.saveHdrMetadataSettings(
                spec,
                hdrMetadataMode.wireValue,
                hdrManualPeakNits,
            )
        }
    }

    private fun hdrMetadataSummary(): String =
        if (activeCinemaTransfer() != VideoTransfer.PQ) {
            "Not HDR10 / PQ"
        } else {
            detectedHdrStaticMetadata.status
        }

    private fun hdrMetadataModeDescription(): String {
        if (activeCinemaTransfer() != VideoTransfer.PQ) {
            return "These controls apply when the active color mode is HDR10 / PQ."
        }
        return when (hdrMetadataMode) {
            HdrMetadataMode.AUTO ->
                "Auto: ${effectiveHdrSourcePeakNits().toInt()}-nit source peak. " +
                    "Invalid or missing data falls back to " +
                    "${DEFAULT_HDR_SOURCE_PEAK_NITS.toInt()} nits."
            HdrMetadataMode.IGNORE ->
                "Ignore bypasses file metadata and uses the " +
                    "${DEFAULT_HDR_SOURCE_PEAK_NITS.toInt()}-nit fallback."
            HdrMetadataMode.MANUAL ->
                "Manual uses the per-file content peak below."
        }
    }

    private fun applyPictureSettings(persist: Boolean = true) {
        nativeSetPictureSettings(
            pictureMode.wireValue,
            pictureBrightness,
            pictureExposure,
            pictureContrast,
            pictureSaturation,
            pictureVibrance,
            toneMapPeakNits,
            sceneBrightness,
        )
        if (persist && mediaLoaded && spec.uri.isNotBlank()) {
            mediaStateStore.savePictureSettings(
                spec,
                MediaStateStore.PictureSettings(
                    pictureMode.wireValue,
                    pictureBrightness,
                    pictureExposure,
                    pictureContrast,
                    pictureSaturation,
                    pictureVibrance,
                ),
            )
            currentMediaHasSavedPictureSettings = true
        }
    }

    private fun applyAdaptiveToneMapping(persist: Boolean = true) {
        adaptiveToneMapMinNits = adaptiveToneMapMinNits.sanitized(
            ADAPTIVE_TONE_MAP_NITS_MIN,
            ADAPTIVE_TONE_MAP_NITS_MAX,
            DEFAULT_ADAPTIVE_TONE_MAP_MIN_NITS,
        )
        adaptiveToneMapMaxNits = adaptiveToneMapMaxNits.sanitized(
            ADAPTIVE_TONE_MAP_NITS_MIN,
            ADAPTIVE_TONE_MAP_NITS_MAX,
            DEFAULT_ADAPTIVE_TONE_MAP_MAX_NITS,
        )
        adaptiveToneMapPriority = adaptiveToneMapPriority.sanitized(
            ADAPTIVE_TONE_MAP_PRIORITY_MIN,
            ADAPTIVE_TONE_MAP_PRIORITY_MAX,
            DEFAULT_ADAPTIVE_TONE_MAP_PRIORITY,
        )
        if (adaptiveToneMapMinNits > adaptiveToneMapMaxNits) {
            adaptiveToneMapMinNits = adaptiveToneMapMaxNits
        }
        nativeSetAdaptiveToneMapping(
            adaptiveToneMapEnabled,
            adaptiveToneMapMinNits,
            adaptiveToneMapMaxNits,
            adaptiveToneMapPriority,
            adaptiveToneMapMode.wireValue,
        )
        if (persist) {
            getSharedPreferences(PICTURE_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_ADAPTIVE_TONE_MAP_ENABLED, adaptiveToneMapEnabled)
                .putFloat(PREF_ADAPTIVE_TONE_MAP_MIN_NITS, adaptiveToneMapMinNits)
                .putFloat(PREF_ADAPTIVE_TONE_MAP_MAX_NITS, adaptiveToneMapMaxNits)
                .putFloat(PREF_ADAPTIVE_TONE_MAP_PRIORITY, adaptiveToneMapPriority)
                .putInt(PREF_ADAPTIVE_TONE_MAP_MODE, adaptiveToneMapMode.wireValue)
                .apply()
        }
    }

    private fun activeCinemaTransfer(): VideoTransfer = when (pictureMode) {
        PictureMode.FORCE_SDR -> VideoTransfer.SDR
        PictureMode.FORCE_PQ -> VideoTransfer.PQ
        PictureMode.FORCE_HLG -> VideoTransfer.HLG
        PictureMode.AUTO,
        PictureMode.ORIGINAL -> detectedVideoTransfer
    }

    private fun activeCinemaTuning(): CinemaTuning =
        cinemaTunings.getValue(activeCinemaTransfer())

    private fun loadCinemaTunings(preferences: android.content.SharedPreferences) {
        val legacyToneDepth =
            preferences.getFloat(PREF_CINEMA_TONE_DEPTH, DEFAULT_CINEMA_TONE_DEPTH)
                .sanitized(CINEMA_TONE_DEPTH_MIN, CINEMA_TONE_DEPTH_MAX, DEFAULT_CINEMA_TONE_DEPTH)
        val legacyMidtoneEv =
            preferences.getFloat(PREF_CINEMA_MIDTONE_EV, DEFAULT_CINEMA_MIDTONE_EV)
                .sanitized(CINEMA_MIDTONE_EV_MIN, CINEMA_MIDTONE_EV_MAX, DEFAULT_CINEMA_MIDTONE_EV)
        val legacyColorIntensity =
            preferences.getFloat(
                PREF_CINEMA_COLOR_INTENSITY,
                DEFAULT_CINEMA_COLOR_INTENSITY,
            ).sanitized(
                CINEMA_COLOR_INTENSITY_MIN,
                CINEMA_COLOR_INTENSITY_MAX,
                DEFAULT_CINEMA_COLOR_INTENSITY,
            )
        VideoTransfer.entries.forEach { transfer ->
            val prefix = cinemaPreferencePrefix(transfer)
            val defaults = defaultCinemaTuning(transfer)
            val migratedToneDepth =
                if (transfer == VideoTransfer.PQ) defaults.toneDepth else legacyToneDepth
            cinemaTunings[transfer] = CinemaTuning(
                preferences.getFloat("$prefix-tone-depth", migratedToneDepth)
                    .sanitized(CINEMA_TONE_DEPTH_MIN, CINEMA_TONE_DEPTH_MAX, migratedToneDepth),
                preferences.getFloat("$prefix-midtone-ev", legacyMidtoneEv)
                    .sanitized(CINEMA_MIDTONE_EV_MIN, CINEMA_MIDTONE_EV_MAX, legacyMidtoneEv),
                preferences.getFloat("$prefix-color-intensity", legacyColorIntensity)
                    .sanitized(
                        CINEMA_COLOR_INTENSITY_MIN,
                        CINEMA_COLOR_INTENSITY_MAX,
                        legacyColorIntensity,
                    ),
            )
        }
    }

    private fun cinemaPreferencePrefix(transfer: VideoTransfer): String = when (transfer) {
        VideoTransfer.SDR -> "cinema-sdr"
        VideoTransfer.PQ -> "cinema-pq"
        VideoTransfer.HLG -> "cinema-hlg"
    }

    private fun applyCinemaSettings(persist: Boolean = true) {
        val transfer = activeCinemaTransfer()
        val tuning = cinemaTunings.getValue(transfer)
        nativeSetCinemaSettings(
            tuning.toneDepth,
            tuning.midtoneEv,
            tuning.colorIntensity,
        )
        if (persist) {
            val prefix = cinemaPreferencePrefix(transfer)
            getSharedPreferences(PICTURE_PREFS, Context.MODE_PRIVATE).edit()
                .putFloat("$prefix-tone-depth", tuning.toneDepth)
                .putFloat("$prefix-midtone-ev", tuning.midtoneEv)
                .putFloat("$prefix-color-intensity", tuning.colorIntensity)
                .apply()
        }
    }

    private fun currentColorModeIsHdr(): Boolean =
        pictureMode == PictureMode.FORCE_PQ ||
            pictureMode == PictureMode.FORCE_HLG ||
            (
                pictureMode == PictureMode.AUTO &&
                    (detectedVideoTransfer == VideoTransfer.PQ ||
                        detectedVideoTransfer == VideoTransfer.HLG)
            )

    private fun defaultExposureForCurrentColorMode(): Float =
        if (currentColorModeIsHdr()) DEFAULT_HDR_EXPOSURE
        else DEFAULT_PICTURE_EXPOSURE

    private fun lerp(minimum: Float, maximum: Float, fraction: Float): Float =
        minimum + (maximum - minimum) * fraction

    // Float.coerceIn() does NOT sanitize NaN: comparisons against NaN are
    // always false, so a NaN receiver passes through coerceIn() unchanged.
    // Use this instead whenever a value came from persisted storage or
    // pointer/controller input, so a corrupted or degenerate value can't
    // silently become a permanent, globally-applied setting.
    private fun Float.sanitized(minimum: Float, maximum: Float, fallback: Float): Float =
        if (isFinite()) coerceIn(minimum, maximum) else fallback

    private fun logarithmicLerp(minimum: Float, maximum: Float, fraction: Float): Float {
        val logMinimum = kotlin.math.ln(minimum)
        val logMaximum = kotlin.math.ln(maximum)
        return kotlin.math.exp(logMinimum + (logMaximum - logMinimum) * fraction)
    }

    private fun isSlider(id: String): Boolean =
        id == "seek_bar" ||
            id == "subtitle_depth_slider" ||
            id == "screen_curvature_slider" ||
            id.startsWith("picture_slider_") ||
            id.startsWith("settings_slider_")

    private fun subtitleMime(value: String): String = when (value.substringAfterLast('.', "").lowercase()) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "vtt" -> MimeTypes.TEXT_VTT
        "ssa", "ass" -> MimeTypes.TEXT_SSA
        "ttml", "xml" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        hideSystemUi()
        nativeSetResumed(true)
        if (
            resumePlaybackOnForeground &&
            mediaLoaded &&
            ::player.isInitialized
        ) {
            resumePlaybackOnForeground = false
            player.play()
        }
    }

    override fun onPause() {
        activityResumed = false
        pausePlaybackForBackground()
        saveCurrentMediaState()
        nativeSetResumed(false)
        super.onPause()
    }

    override fun onStop() {
        pausePlaybackForBackground()
        saveCurrentMediaState()
        super.onStop()
    }

    private fun pausePlaybackForBackground() {
        if (!mediaLoaded || !::player.isInitialized) return
        if (player.playWhenReady) resumePlaybackOnForeground = true
        player.pause()
    }

    override fun onDestroy() {
        stopBrowserScroll()
        mainHandler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        if (mediaLoaded) {
            releaseCurrentPlayer(saveState = true)
        }
        mediaStateStore.close()
        menuBitmap?.recycle()
        menuBitmap = null
        if (nativeStarted) nativeRequestStop()
        nativeThread?.join(2_000L)
        super.onDestroy()
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    companion object {
        private const val REQUEST_VIDEO = 4001
        private const val JOYSTICK_SEEK_STEP_MS = 10_000L
        private const val REMOTE_MIN_BUFFER_MS = 30_000
        private const val REMOTE_MAX_BUFFER_MS = 120_000
        private const val REMOTE_PLAYBACK_BUFFER_MS = 3_000
        private const val REMOTE_REBUFFER_MS = 8_000
        private const val REMOTE_PLAYER_RETRY_COUNT = 3
        private const val REMOTE_PLAYER_RETRY_DELAY_MS = 1_000L
        private const val REMOTE_STABLE_RESET_MS = 15_000L
        private const val REQUEST_MEDIA_PERMISSION = 4002
        private const val RECENT_MEDIA_LIMIT = 6
        private const val BROWSER_VISIBLE_ROWS = 7
        private const val BROWSER_ROW_RENDER_BUFFER = 2
        private const val BROWSER_JOYSTICK_DEAD_ZONE = 0.16f
        private const val BROWSER_MAX_SCROLL_ROWS_PER_SECOND = 7.5f
        private const val BROWSER_SCROLL_ACCELERATION = 11f
        private const val BROWSER_SCROLL_FRICTION = 7.5f
        private const val BROWSER_SCROLL_STOP_VELOCITY = 0.025f
        private const val BROWSER_LIST_TOP = 165f
        private const val BROWSER_LIST_BOTTOM = 855f
        private const val BROWSER_LIST_LEFT = 170f
        private const val BROWSER_LIST_RIGHT = 1850f
        private val BROWSER_SORT_POPOVER_BOUNDS = RectF(1378f, 164f, 1880f, 664f)
        private const val BROWSER_SCROLLBAR_ID = "browser_scrollbar"
        private const val BROWSER_SCROLLBAR_MIN_THUMB_HEIGHT = 65f
        private const val BROWSER_SCROLLBAR_HIT_PADDING = 26f
        private val BROWSER_SCROLLBAR_TRACK =
            RectF(1858f, BROWSER_LIST_TOP, 1876f, BROWSER_LIST_BOTTOM)
        private const val TRACK_LIST_VISIBLE_ROWS = 6
        private const val TRACK_LIST_ROW_RENDER_BUFFER = 1
        private const val TRACK_LIST_MAX_SCROLL_ROWS_PER_SECOND = 6f
        private const val TRACK_LIST_ROW_TOP = 185f
        private const val TRACK_LIST_ROW_STRIDE = 50f
        private const val TRACK_LIST_ROW_HEIGHT = 40f
        private const val TRACK_LIST_ROW_LEFT = 190f
        private const val TRACK_LIST_ROW_RIGHT = 805f
        private const val TRACK_LIST_SCROLLBAR_ID = "track_list_scrollbar"
        private const val TRACK_LIST_SCROLLBAR_MIN_THUMB_HEIGHT = 45f
        private const val TRACK_LIST_SCROLLBAR_HIT_PADDING = 18f
        private val TRACK_LIST_CLIP_BOUNDS =
            RectF(TRACK_LIST_ROW_LEFT, 180f, 838f, 485f)
        private val TRACK_LIST_HIT_BOUNDS =
            RectF(TRACK_LIST_ROW_LEFT, 180f, TRACK_LIST_ROW_RIGHT, 485f)
        private val TRACK_LIST_POINTER_BOUNDS =
            RectF(TRACK_LIST_ROW_LEFT, 175f, 842f, 490f)
        private val TRACK_LIST_SCROLLBAR_TRACK = RectF(820f, 185f, 832f, 475f)
        private const val UI_WIDTH = 2048f
        private const val UI_HEIGHT = 1024f
        private const val PGS_REFERENCE_WIDTH = 1920f
        private const val PGS_REFERENCE_HEIGHT = 1080f
        private const val PLAYBACK_SEEK_LEFT = 350f
        private const val PLAYBACK_SEEK_RIGHT = 1698f
        private const val PLAYBACK_AUTO_HIDE_MS = 8_000L
        private const val STATE_SAVE_INTERVAL_MS = 5_000L
        private const val AUTO_FALLBACK_RATIO_MIN = 1.55
        private const val AUTO_FALLBACK_RATIO_MAX = 2.45
        private const val AUTO_FALLBACK_SAMPLE_COUNT = 3
        private const val MINIMUM_RESUME_POSITION_MS = 10_000L
        private const val MINIMUM_REMAINING_MS = 120_000L
        private const val END_FRACTION = 0.03
        private const val SUBTITLE_AUTO = "subtitle:auto"
        private const val SUBTITLE_OFF = "subtitle:off"
        private const val MAX_SUBTITLE_SYNC_MS = 10_000
        private const val SUBTITLE_DEPTH_MIN_METERS = 0f
        private const val SUBTITLE_DEPTH_MAX_METERS = 0.50f
        private const val DEFAULT_2D_SUBTITLE_DEPTH_METERS = 0f
        private const val DEFAULT_3D_SUBTITLE_DEPTH_METERS = 0.12f
        private const val AUDIO_AUTO = "audio:auto"
        private const val PICTURE_PREFS = "picture-preferences"
        private const val BROWSER_PREFS = "browser-preferences"
        private const val PREF_BROWSER_SORT_FIELD = "sort-field"
        private const val PREF_BROWSER_SORT_ASCENDING = "sort-ascending"
        private const val PREF_SCENE_BRIGHTNESS = "scene-brightness"
        private const val PREF_TONE_MAP_PEAK_NITS = "tone-map-peak-nits"
        private const val PREF_ADAPTIVE_TONE_MAP_ENABLED = "adaptive-tone-map-enabled"
        private const val PREF_ADAPTIVE_TONE_MAP_MIN_NITS = "adaptive-tone-map-min-nits"
        private const val PREF_ADAPTIVE_TONE_MAP_MAX_NITS = "adaptive-tone-map-max-nits"
        private const val PREF_ADAPTIVE_TONE_MAP_PRIORITY = "adaptive-tone-map-priority"
        private const val PREF_ADAPTIVE_TONE_MAP_MODE = "adaptive-tone-map-mode"
        private const val PREF_ADAPTIVE_DEFAULT_RANGE_VERSION =
            "adaptive-default-range-version"
        private const val PREF_RENDER_RESOLUTION_SCALE = "render-resolution-scale"
        private const val PREF_GAMUT_MAPPING_MODE = "gamut-mapping-mode"
        // Legacy single-profile keys are retained only to migrate existing tuning.
        private const val PREF_CINEMA_TONE_DEPTH = "cinema-tone-depth"
        private const val PREF_CINEMA_MIDTONE_EV = "cinema-midtone-ev"
        private const val PREF_CINEMA_COLOR_INTENSITY = "cinema-color-intensity"
        private const val DEFAULT_PICTURE_BRIGHTNESS = 0f
        private const val DEFAULT_PICTURE_EXPOSURE = -0.50f
        private const val DEFAULT_HDR_EXPOSURE = 0f
        private const val DEFAULT_PICTURE_CONTRAST = 1f
        private const val DEFAULT_PICTURE_SATURATION = 1f
        private const val DEFAULT_PICTURE_VIBRANCE = 0f
        private const val DEFAULT_SCENE_BRIGHTNESS = 0.5f
        private const val DEFAULT_TONE_MAP_PEAK_NITS = 100f
        private const val DEFAULT_ADAPTIVE_TONE_MAP_MIN_NITS = 100f
        private const val DEFAULT_ADAPTIVE_TONE_MAP_MAX_NITS = 600f
        private const val DEFAULT_ADAPTIVE_TONE_MAP_PRIORITY = 0.5f
        private const val DEFAULT_RENDER_RESOLUTION_SCALE = 1f
        private const val DEFAULT_CINEMA_TONE_DEPTH = 0.22f
        private const val DEFAULT_PQ_CINEMA_TONE_DEPTH = 0f
        private const val DEFAULT_CINEMA_MIDTONE_EV = 0.15f
        private const val DEFAULT_CINEMA_COLOR_INTENSITY = 1.10f
        private const val DEFAULT_SCREEN_CURVATURE = 0f
        private const val SCREEN_CURVATURE_MIN = 0f
        private const val SCREEN_CURVATURE_MAX = 1f
        private const val PICTURE_BRIGHTNESS_MIN = -0.016f
        private const val PICTURE_BRIGHTNESS_MAX = 0.016f
        private const val PICTURE_EXPOSURE_MIN = -3f
        private const val PICTURE_EXPOSURE_MAX = 3f
        private const val PICTURE_CONTRAST_MIN = 0.6f
        private const val PICTURE_CONTRAST_MAX = 1.4f
        private const val PICTURE_SATURATION_MIN = 0.7f
        private const val PICTURE_SATURATION_MAX = 1.3f
        private const val PICTURE_VIBRANCE_MIN = -1f
        private const val PICTURE_VIBRANCE_MAX = 1f
        private const val SCENE_BRIGHTNESS_MIN = 0f
        private const val SCENE_BRIGHTNESS_MAX = 1f
        private const val TONE_MAP_PEAK_NITS_MIN = 50f
        private const val TONE_MAP_PEAK_NITS_MAX = 5_000f
        private const val ADAPTIVE_TONE_MAP_NITS_MIN = 100f
        private const val ADAPTIVE_TONE_MAP_NITS_MAX = 1_000f
        private const val ADAPTIVE_TONE_MAP_PRIORITY_MIN = 0f
        private const val ADAPTIVE_TONE_MAP_PRIORITY_MAX = 1f
        private const val RENDER_RESOLUTION_SCALE_MIN = 1f
        private const val RENDER_RESOLUTION_SCALE_MAX = 1.6f
        private const val RENDER_RESOLUTION_SCALE_STEP = 0.05f
        private const val DEFAULT_HDR_SOURCE_PEAK_NITS = 1_000f
        private const val HDR_SOURCE_PEAK_NITS_MIN = 100f
        private const val HDR_SOURCE_PEAK_NITS_MAX = 10_000f
        private const val HDR_METADATA_NITS_MIN = 50f
        private const val HDR_METADATA_NITS_MAX = 10_000f
        private const val HDR_STATIC_INFO_SIZE_BYTES = 25
        private const val HDR_STATIC_INFO_TYPE_1 = 0
        private const val HDR_MASTERING_PEAK_OFFSET = 17
        private const val HDR_MAX_CLL_OFFSET = 21
        private const val HDR_MAX_FALL_OFFSET = 23
        private const val CINEMA_TONE_DEPTH_MIN = 0f
        private const val CINEMA_TONE_DEPTH_MAX = 1f
        private const val CINEMA_TONE_DEPTH_DISPLAY_SCALE = 200f
        private const val CINEMA_MIDTONE_EV_MIN = -1f
        private const val CINEMA_MIDTONE_EV_MAX = 1f
        private const val CINEMA_COLOR_INTENSITY_MIN = 0.4f
        private const val CINEMA_COLOR_INTENSITY_MAX = 1.6f
        private fun defaultCinemaTuning(transfer: VideoTransfer) = CinemaTuning(
            if (transfer == VideoTransfer.PQ) {
                DEFAULT_PQ_CINEMA_TONE_DEPTH
            } else {
                DEFAULT_CINEMA_TONE_DEPTH
            },
            DEFAULT_CINEMA_MIDTONE_EV,
            DEFAULT_CINEMA_COLOR_INTENSITY,
        )
        private const val PICTURE_SLIDER_LEFT = 665f
        private const val PICTURE_SLIDER_RIGHT = 1510f
        private const val SETTINGS_SLIDER_LEFT = 1200f
        private const val SETTINGS_SLIDER_RIGHT = 1685f
        private const val SUBTITLE_DEPTH_SLIDER_LEFT = 340f
        private const val SUBTITLE_DEPTH_SLIDER_RIGHT = 690f
        private const val SCREEN_CURVATURE_SLIDER_LEFT = 1470f
        private const val SCREEN_CURVATURE_SLIDER_RIGHT = 1775f
        private const val POINTER_PRESSED = 1
        private const val POINTER_HELD = 2
        private const val POINTER_RELEASED = 3
        private val DEFAULT_SCREEN_TRANSFORM =
            MediaStateStore.ScreenTransform(0f, 0f, -2.4f, 1f)
        private val BROWSER_LIST_CLIP_BOUNDS =
            RectF(BROWSER_LIST_LEFT, BROWSER_LIST_TOP, 1880f, BROWSER_LIST_BOTTOM)
        private val BROWSER_LIST_HIT_BOUNDS =
            RectF(BROWSER_LIST_LEFT, BROWSER_LIST_TOP, BROWSER_LIST_RIGHT, BROWSER_LIST_BOTTOM)
        private val PLAYBACK_TOOLBAR_BOUNDS = RectF(120f, 700f, 1928f, 970f)
        private val PLAYBACK_LEFT_POPOVER_BOUNDS = RectF(150f, 105f, 860f, 720f)
        private val PLAYBACK_PROJECTION_POPOVER_BOUNDS =
            RectF(1185f, 105f, 1898f, 720f)
        private val PLAYBACK_SETTINGS_POPOVER_BOUNDS = RectF(930f, 45f, 1898f, 675f)
        init { System.loadLibrary("dreamplayer") }

        @JvmStatic private external fun nativeRun(
            activity: PlayerActivity,
            stereoMode: Int,
            renderResolutionScale: Float,
        )
        @JvmStatic private external fun nativeSetResumed(resumed: Boolean)
        @JvmStatic private external fun nativeRequestStop()
        @JvmStatic private external fun nativeSetVideoAspect(aspect: Float)
        @JvmStatic private external fun nativeSetScreenTransform(x: Float, y: Float, z: Float, halfHeight: Float)
        @JvmStatic private external fun nativeGetScreenTransform(): FloatArray
        @JvmStatic private external fun nativeSetStereoMode(stereoMode: Int)
        @JvmStatic private external fun nativeSetVideoProjection(projection: Int)
        @JvmStatic private external fun nativeSetImmersiveOrientation(
            yawDegrees: Float,
            pitchDegrees: Float,
        )
        @JvmStatic private external fun nativeRecenterImmersive()
        @JvmStatic private external fun nativeGetImmersiveYaw(): Float
        @JvmStatic private external fun nativeGetImmersivePitch(): Float
        @JvmStatic private external fun nativeSetPictureSettings(
            pictureMode: Int,
            brightness: Float,
            exposure: Float,
            contrast: Float,
            saturation: Float,
            vibrance: Float,
            toneMapPeakNits: Float,
            sceneBrightness: Float,
        )
        @JvmStatic private external fun nativeSetAdaptiveToneMapping(
            enabled: Boolean,
            minimumNits: Float,
            maximumNits: Float,
            priority: Float,
            mode: Int,
        )
        @JvmStatic private external fun nativeGetAdaptiveToneMapTarget(): Float
        @JvmStatic private external fun nativeSetVideoColorInfo(
            transfer: Int,
            primaries: Int,
            sourcePeakNits: Float,
            maxFallNits: Float,
            gamutMappingMode: Int,
        )
        @JvmStatic private external fun nativeSetCinemaSettings(
            toneDepth: Float,
            midtoneEv: Float,
            colorIntensity: Float,
        )
        @JvmStatic private external fun nativeSetSwapEyes(swapped: Boolean)
        @JvmStatic private external fun nativeSetVideoAvailable(available: Boolean)
        @JvmStatic private external fun nativeSetMenuInteractive(interactive: Boolean)
        @JvmStatic private external fun nativeSetSubtitle(bitmap: Bitmap)
        @JvmStatic private external fun nativeSetBitmapSubtitle(
            bitmap: Bitmap,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
        )
        @JvmStatic private external fun nativeSetSubtitleDepth(depthMeters: Float)
        @JvmStatic private external fun nativeSetSubtitle3dEnabled(enabled: Boolean)
        @JvmStatic private external fun nativeSetScreenCurvature(curvature: Float)
    }
}
