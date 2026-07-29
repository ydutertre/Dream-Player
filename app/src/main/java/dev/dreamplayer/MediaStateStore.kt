package dev.dreamplayer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import java.security.MessageDigest
import java.util.Locale

/**
 * Persistent per-file state. Most columns are intentionally nullable: later QoL
 * phases can add values without replacing the database or changing media IDs.
 */
class MediaStateStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    data class ScreenTransform(
        val x: Float,
        val y: Float,
        val z: Float,
        val halfHeight: Float,
    )

    data class PlaybackState(
        val positionMs: Long?,
        val durationMs: Long?,
        val screenTransform: ScreenTransform?,
        val subtitleFingerprint: String?,
        val subtitleSyncMs: Int?,
        val subtitleDepthMeters: Float?,
        val subtitle3dEnabled: Boolean,
        val audioSyncMs: Int?,
        val audioTrackFingerprint: String?,
        val pictureSettings: PictureSettings?,
        val decoderMode: Int?,
        val decoderAutoSoftware: Boolean,
        val screenCurvature: Float?,
        val hdrMetadataMode: Int?,
        val hdrManualPeakNits: Float?,
    )

    data class PictureSettings(
        val mode: Int,
        val brightness: Float,
        val exposure: Float,
        val contrast: Float,
        val saturation: Float,
        val vibrance: Float,
    )

    data class ProjectionPreference(
        val mode: StereoMode,
        val projection: VideoProjection,
        val immersiveYawDegrees: Float,
        val immersivePitchDegrees: Float,
        val manuallySelected: Boolean,
    )

    data class RecentMedia(
        val uri: String,
        val title: String,
        val positionMs: Long?,
        val durationMs: Long?,
        val lastPlayedAt: Long,
        val smbShare: String?,
    )

    data class BrowserMetadata(
        val durationMs: Long?,
        val lastPlayedAt: Long?,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE media_state (
                media_id TEXT PRIMARY KEY NOT NULL,
                canonical_location TEXT NOT NULL,
                title TEXT,
                stereo_mode INTEGER,
                stereo_manual INTEGER NOT NULL DEFAULT 0,
                projection_mode INTEGER NOT NULL DEFAULT 0,
                immersive_yaw REAL NOT NULL DEFAULT 0,
                immersive_pitch REAL NOT NULL DEFAULT 0,
                position_ms INTEGER,
                duration_ms INTEGER,
                screen_x REAL,
                screen_y REAL,
                screen_z REAL,
                screen_half_height REAL,
                subtitle_fingerprint TEXT,
                subtitle_sync_ms INTEGER,
                subtitle_depth_meters REAL,
                subtitle_3d_enabled INTEGER NOT NULL DEFAULT 0,
                picture_mode INTEGER,
                brightness REAL,
                exposure REAL,
                contrast REAL,
                saturation REAL,
                vibrance REAL,
                shadows REAL,
                highlights REAL,
                audio_sync_ms INTEGER,
                audio_track_fingerprint TEXT,
                decoder_mode INTEGER,
                decoder_auto_software INTEGER NOT NULL DEFAULT 0,
                screen_curvature REAL,
                hdr_metadata_mode INTEGER,
                hdr_manual_peak_nits REAL,
                playback_uri TEXT,
                smb_share TEXT,
                last_played_at INTEGER,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX media_state_updated_at ON media_state(updated_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE media_state ADD COLUMN audio_sync_ms INTEGER")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE media_state ADD COLUMN audio_track_fingerprint TEXT")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE media_state ADD COLUMN saturation REAL")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE media_state ADD COLUMN decoder_mode INTEGER")
        }
        if (oldVersion < 6) {
            db.execSQL(
                "ALTER TABLE media_state ADD COLUMN decoder_auto_software INTEGER NOT NULL DEFAULT 0"
            )
        }
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE media_state ADD COLUMN subtitle_sync_ms INTEGER")
        }
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE media_state ADD COLUMN subtitle_depth_meters REAL")
        }
        if (oldVersion < 9) {
            db.execSQL("ALTER TABLE media_state ADD COLUMN screen_curvature REAL")
        }
        if (oldVersion < 10) {
            db.execSQL("ALTER TABLE media_state ADD COLUMN playback_uri TEXT")
            db.execSQL("ALTER TABLE media_state ADD COLUMN smb_share TEXT")
            db.execSQL("ALTER TABLE media_state ADD COLUMN last_played_at INTEGER")
            db.execSQL(
                "UPDATE media_state SET playback_uri = canonical_location " +
                    "WHERE playback_uri IS NULL"
            )
            db.execSQL(
                "UPDATE media_state SET last_played_at = updated_at " +
                    "WHERE last_played_at IS NULL AND position_ms IS NOT NULL"
            )
        }
        if (oldVersion < 11) {
            db.execSQL("ALTER TABLE media_state ADD COLUMN shadows REAL")
            db.execSQL("ALTER TABLE media_state ADD COLUMN highlights REAL")
        }
        if (oldVersion < 12) {
            db.execSQL("ALTER TABLE media_state ADD COLUMN vibrance REAL")
        }
        if (oldVersion < 13) {
            db.execSQL("ALTER TABLE media_state ADD COLUMN hdr_metadata_mode INTEGER")
            db.execSQL("ALTER TABLE media_state ADD COLUMN hdr_manual_peak_nits REAL")
        }
        if (oldVersion < 14) {
            db.execSQL(
                "ALTER TABLE media_state ADD COLUMN " +
                    "subtitle_3d_enabled INTEGER NOT NULL DEFAULT 0"
            )
        }
        if (oldVersion < 15) {
            db.execSQL(
                "ALTER TABLE media_state ADD COLUMN " +
                    "projection_mode INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE media_state ADD COLUMN " +
                    "immersive_yaw REAL NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE media_state ADD COLUMN " +
                    "immersive_pitch REAL NOT NULL DEFAULT 0"
            )
        }
    }

    fun listRecent(limit: Int = 6): List<RecentMedia> {
        if (limit <= 0) return emptyList()
        readableDatabase.query(
            "media_state",
            arrayOf(
                "COALESCE(playback_uri, canonical_location)",
                "title",
                "position_ms",
                "duration_ms",
                "last_played_at",
                "smb_share",
                "canonical_location",
            ),
            "last_played_at IS NOT NULL",
            null,
            null,
            null,
            "last_played_at DESC",
            limit.toString(),
        ).use { cursor ->
            val recent = ArrayList<RecentMedia>(cursor.count)
            while (cursor.moveToNext()) {
                val storedUri = cursor.getString(0)
                val storedParsed = Uri.parse(storedUri)
                val canonical = cursor.getString(6)
                val canonicalParsed = Uri.parse(canonical)
                val legacySmbShare = canonicalParsed.pathSegments.firstOrNull()
                val uri = if (
                    storedParsed.scheme.equals("smb", ignoreCase = true) &&
                    cursor.isNull(5) &&
                    legacySmbShare != null
                ) {
                    Uri.Builder()
                        .scheme("smb")
                        .authority(storedParsed.host)
                        .apply {
                            canonicalParsed.pathSegments.drop(1).forEach(::appendPath)
                        }
                        .build()
                        .toString()
                } else {
                    storedUri
                }
                val parsed = Uri.parse(uri)
                recent += RecentMedia(
                    uri = uri,
                    title = if (cursor.isNull(1)) {
                        Uri.parse(uri).lastPathSegment ?: "Video"
                    } else {
                        cursor.getString(1)
                    },
                    positionMs = if (cursor.isNull(2)) null else cursor.getLong(2),
                    durationMs = if (cursor.isNull(3)) null else cursor.getLong(3),
                    lastPlayedAt = cursor.getLong(4),
                    smbShare = if (!parsed.scheme.equals("smb", ignoreCase = true)) {
                        null
                    } else if (!cursor.isNull(5)) {
                        cursor.getString(5)
                    } else {
                        legacySmbShare
                    },
                )
            }
            return recent
        }
    }

    fun listBrowserMetadata(): Map<String, BrowserMetadata> {
        readableDatabase.query(
            "media_state",
            arrayOf("media_id", "duration_ms", "last_played_at"),
            "duration_ms IS NOT NULL OR last_played_at IS NOT NULL",
            null,
            null,
            null,
            null,
        ).use { cursor ->
            val result = HashMap<String, BrowserMetadata>(cursor.count)
            while (cursor.moveToNext()) {
                result[cursor.getString(0)] = BrowserMetadata(
                    durationMs = if (cursor.isNull(1)) null else cursor.getLong(1),
                    lastPlayedAt = if (cursor.isNull(2)) null else cursor.getLong(2),
                )
            }
            return result
        }
    }

    fun markPlayed(spec: PlaybackSpec) {
        upsert(
            spec,
            ContentValues().apply { put("last_played_at", System.currentTimeMillis()) },
        )
    }

    fun loadProjection(spec: PlaybackSpec): ProjectionPreference? {
        val identity = MediaIdentity.from(spec)
        readableDatabase.query(
            "media_state",
            arrayOf(
                "stereo_mode",
                "stereo_manual",
                "projection_mode",
                "immersive_yaw",
                "immersive_pitch",
            ),
            "media_id = ?",
            arrayOf(identity.id),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return null
            return ProjectionPreference(
                mode = StereoMode.fromWire(cursor.getInt(0)),
                projection = VideoProjection.fromWire(cursor.getInt(2)),
                immersiveYawDegrees =
                    if (cursor.isNull(3)) 0f else cursor.getFloat(3),
                immersivePitchDegrees =
                    if (cursor.isNull(4)) 0f else cursor.getFloat(4),
                manuallySelected = cursor.getInt(1) != 0,
            )
        }
    }

    fun loadPlaybackState(spec: PlaybackSpec): PlaybackState? {
        val identity = MediaIdentity.from(spec)
        readableDatabase.query(
            "media_state",
            arrayOf(
                "position_ms", "duration_ms",
                "screen_x", "screen_y", "screen_z", "screen_half_height",
                "subtitle_fingerprint", "subtitle_sync_ms", "subtitle_depth_meters",
                "subtitle_3d_enabled",
                "audio_sync_ms", "audio_track_fingerprint",
                "picture_mode", "brightness", "exposure", "contrast", "saturation",
                "vibrance",
                "decoder_mode",
                "decoder_auto_software",
                "screen_curvature",
                "hdr_metadata_mode",
                "hdr_manual_peak_nits",
            ),
            "media_id = ?",
            arrayOf(identity.id),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val transform = if ((2..5).all { !cursor.isNull(it) }) {
                ScreenTransform(
                    cursor.getFloat(2),
                    cursor.getFloat(3),
                    cursor.getFloat(4),
                    cursor.getFloat(5),
                )
            } else null
            return PlaybackState(
                positionMs = if (cursor.isNull(0)) null else cursor.getLong(0),
                durationMs = if (cursor.isNull(1)) null else cursor.getLong(1),
                screenTransform = transform,
                subtitleFingerprint = if (cursor.isNull(6)) null else cursor.getString(6),
                subtitleSyncMs = if (cursor.isNull(7)) null else cursor.getInt(7),
                subtitleDepthMeters = if (cursor.isNull(8)) null else cursor.getFloat(8),
                subtitle3dEnabled = !cursor.isNull(9) && cursor.getInt(9) != 0,
                audioSyncMs = if (cursor.isNull(10)) null else cursor.getInt(10),
                audioTrackFingerprint = if (cursor.isNull(11)) null else cursor.getString(11),
                pictureSettings = if ((12..15).all { !cursor.isNull(it) }) {
                    PictureSettings(
                        mode = cursor.getInt(12),
                        brightness = cursor.getFloat(13),
                        exposure = cursor.getFloat(14),
                        contrast = cursor.getFloat(15),
                        saturation = if (cursor.isNull(16)) 1f else cursor.getFloat(16),
                        vibrance = if (cursor.isNull(17)) 0f else cursor.getFloat(17),
                    )
                } else null,
                decoderMode = if (cursor.isNull(18)) null else cursor.getInt(18),
                decoderAutoSoftware = !cursor.isNull(19) && cursor.getInt(19) != 0,
                screenCurvature = if (cursor.isNull(20)) null else cursor.getFloat(20),
                hdrMetadataMode = if (cursor.isNull(21)) null else cursor.getInt(21),
                hdrManualPeakNits = if (cursor.isNull(22)) null else cursor.getFloat(22),
            )
        }
    }

    fun saveProjection(
        spec: PlaybackSpec,
        mode: StereoMode,
        projection: VideoProjection = VideoProjection.FLAT,
        immersiveYawDegrees: Float = 0f,
        immersivePitchDegrees: Float = 0f,
        manuallySelected: Boolean = true,
    ) {
        val identity = MediaIdentity.from(spec)
        val values = ContentValues().apply {
            put("media_id", identity.id)
            put("canonical_location", identity.canonicalLocation)
            put("title", spec.title)
            put("stereo_mode", mode.wireValue)
            put("projection_mode", projection.wireValue)
            put("immersive_yaw", immersiveYawDegrees)
            put("immersive_pitch", immersivePitchDegrees)
            put("stereo_manual", if (manuallySelected) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }
        upsert(spec, values)
    }

    fun savePlaybackState(
        spec: PlaybackSpec,
        positionMs: Long,
        durationMs: Long?,
        transform: ScreenTransform,
    ) {
        val values = ContentValues().apply {
            put("position_ms", positionMs.coerceAtLeast(0L))
            if (durationMs != null && durationMs > 0L) put("duration_ms", durationMs)
            put("screen_x", transform.x)
            put("screen_y", transform.y)
            put("screen_z", transform.z)
            put("screen_half_height", transform.halfHeight)
        }
        upsert(spec, values)
    }

    fun saveSubtitle(spec: PlaybackSpec, fingerprint: String) {
        upsert(spec, ContentValues().apply { put("subtitle_fingerprint", fingerprint) })
    }

    fun saveSubtitleSync(spec: PlaybackSpec, delayMs: Int) {
        upsert(spec, ContentValues().apply { put("subtitle_sync_ms", delayMs) })
    }

    fun saveSubtitleDepth(spec: PlaybackSpec, depthMeters: Float) {
        upsert(spec, ContentValues().apply { put("subtitle_depth_meters", depthMeters) })
    }

    fun saveSubtitle3dEnabled(spec: PlaybackSpec, enabled: Boolean) {
        upsert(
            spec,
            ContentValues().apply {
                put("subtitle_3d_enabled", if (enabled) 1 else 0)
            },
        )
    }

    fun saveScreenCurvature(spec: PlaybackSpec, curvature: Float) {
        upsert(spec, ContentValues().apply { put("screen_curvature", curvature) })
    }

    fun saveAudioSync(spec: PlaybackSpec, delayMs: Int) {
        upsert(spec, ContentValues().apply { put("audio_sync_ms", delayMs) })
    }

    fun saveAudioTrack(spec: PlaybackSpec, fingerprint: String) {
        upsert(spec, ContentValues().apply { put("audio_track_fingerprint", fingerprint) })
    }

    fun savePictureSettings(spec: PlaybackSpec, settings: PictureSettings) {
        upsert(spec, ContentValues().apply {
            put("picture_mode", settings.mode)
            put("brightness", settings.brightness)
            put("exposure", settings.exposure)
            put("contrast", settings.contrast)
            put("saturation", settings.saturation)
            put("vibrance", settings.vibrance)
        })
    }

    fun saveDecoderMode(spec: PlaybackSpec, mode: Int) {
        upsert(spec, ContentValues().apply { put("decoder_mode", mode) })
    }

    fun saveDecoderAutoSoftware(spec: PlaybackSpec, enabled: Boolean) {
        upsert(spec, ContentValues().apply {
            put("decoder_auto_software", if (enabled) 1 else 0)
        })
    }

    fun saveHdrMetadataSettings(spec: PlaybackSpec, mode: Int, manualPeakNits: Float) {
        upsert(spec, ContentValues().apply {
            put("hdr_metadata_mode", mode)
            put("hdr_manual_peak_nits", manualPeakNits)
        })
    }

    private fun upsert(spec: PlaybackSpec, values: ContentValues) {
        val identity = MediaIdentity.from(spec)
        values.put("media_id", identity.id)
        values.put("canonical_location", identity.canonicalLocation)
        values.put("playback_uri", spec.uri)
        spec.smb?.share?.let { values.put("smb_share", it) }
        values.put("title", spec.title)
        values.put("updated_at", System.currentTimeMillis())
        val db = writableDatabase
        val updated = db.update("media_state", values, "media_id = ?", arrayOf(identity.id))
        if (updated == 0) db.insertOrThrow("media_state", null, values)
    }

    companion object {
        private const val DATABASE_NAME = "media-state.db"
        private const val DATABASE_VERSION = 15
    }
}

data class MediaIdentity(
    val id: String,
    val canonicalLocation: String,
) {
    companion object {
        fun from(spec: PlaybackSpec): MediaIdentity {
            val parsed = Uri.parse(spec.uri)
            val canonical = if (parsed.scheme.equals("smb", ignoreCase = true)) {
                // Friendly SMB hostnames may replace a discovered IP in the UI. Keep the
                // stable connection target in the identity so that migration does not lose
                // resume positions and other per-file settings saved under the original IP.
                val host = (spec.smb?.connectionTarget ?: parsed.host)
                    .orEmpty()
                    .trim()
                    .lowercase(Locale.ROOT)
                val share = spec.smb?.share.orEmpty().trim().trim('/', '\\').lowercase(Locale.ROOT)
                val path = parsed.pathSegments
                    .joinToString("/")
                    .trim('/')
                    .lowercase(Locale.ROOT)
                "smb://$host/$share/$path".trimEnd('/')
            } else {
                parsed.buildUpon()
                    .fragment(null)
                    .build()
                    .normalizeScheme()
                    .toString()
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return MediaIdentity(digest, canonical)
        }
    }
}

object FilenameProjectionDetector {
    fun detect(filename: String): StereoMode? {
        val stem = filename.substringBeforeLast('.', filename)
        val tokens = stem.uppercase(Locale.ROOT)
            .split(Regex("[^A-Z0-9]+"))
            .filter(String::isNotBlank)
            .toSet()
        val compact = tokens.joinToString("")

        fun has(vararg values: String) = values.any { value ->
            value in tokens || (value.length >= 3 && compact.contains(value))
        }

        return when {
            has("FSBS", "FULLSBS", "FULLSIDEBYSIDE") -> StereoMode.SBS
            has("FOU", "FTB", "FULLOU", "FULLTB", "FULLOVERUNDER", "FULLTOPBOTTOM") ->
                StereoMode.OVER_UNDER
            has("HSBS", "HALFSBS", "HALFSIDEBYSIDE", "SBS", "SIDEBYSIDE") ->
                StereoMode.HALF_SBS
            has("HOU", "HTB", "HALFOU", "HALFTB", "HALFOVERUNDER", "HALFTOPBOTTOM", "OU", "TB", "OVERUNDER", "TOPBOTTOM") ->
                StereoMode.HALF_OVER_UNDER
            "2D" in tokens -> StereoMode.MONO
            else -> null
        }
    }
}
