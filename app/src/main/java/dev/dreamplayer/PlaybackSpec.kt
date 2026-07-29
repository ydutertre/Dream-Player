package dev.dreamplayer

import android.content.Intent

enum class StereoMode(val wireValue: Int) {
    MONO(0),
    SBS(1),
    OVER_UNDER(2),
    HALF_SBS(3),
    HALF_OVER_UNDER(4);

    companion object {
        fun fromWire(value: Int) = entries.firstOrNull { it.wireValue == value } ?: MONO
    }
}

data class PlaybackSpec(
    val uri: String,
    val title: String,
    val stereoMode: StereoMode,
    val subtitleUri: String? = null,
    val smb: SmbCredentials? = null,
    val remote: SftpProfile? = null,
    val projectionExplicit: Boolean = false,
) {
    fun writeTo(intent: Intent) {
        intent.putExtra(EXTRA_URI, uri)
        intent.putExtra(EXTRA_TITLE, title)
        intent.putExtra(EXTRA_MODE, stereoMode.wireValue)
        intent.putExtra(EXTRA_MODE_EXPLICIT, projectionExplicit)
        intent.putExtra(EXTRA_SUBTITLE, subtitleUri)
        smb?.let {
            intent.putExtra(EXTRA_SMB_HOST, it.host)
            intent.putExtra(EXTRA_SMB_SHARE, it.share)
            intent.putExtra(EXTRA_SMB_USER, it.username)
            intent.putExtra(EXTRA_SMB_PASSWORD, it.password)
            intent.putExtra(EXTRA_SMB_DOMAIN, it.domain)
            intent.putExtra(EXTRA_SMB_CONNECTION_HOST, it.connectionHost)
        }
    }

    companion object {
        private const val EXTRA_URI = "media_uri"
        private const val EXTRA_TITLE = "media_title"
        private const val EXTRA_MODE = "stereo_mode"
        private const val EXTRA_MODE_EXPLICIT = "stereo_mode_explicit"
        private const val EXTRA_SUBTITLE = "subtitle_uri"
        private const val EXTRA_SMB_HOST = "smb_host"
        private const val EXTRA_SMB_SHARE = "smb_share"
        private const val EXTRA_SMB_USER = "smb_user"
        private const val EXTRA_SMB_PASSWORD = "smb_password"
        private const val EXTRA_SMB_DOMAIN = "smb_domain"
        private const val EXTRA_SMB_CONNECTION_HOST = "smb_connection_host"

        fun from(intent: Intent): PlaybackSpec {
            val uri = requireNotNull(intent.getStringExtra(EXTRA_URI)) { "Missing media URI" }
            val host = intent.getStringExtra(EXTRA_SMB_HOST)
            val smb = if (host.isNullOrBlank()) null else SmbCredentials(
                host = host,
                share = intent.getStringExtra(EXTRA_SMB_SHARE).orEmpty(),
                username = intent.getStringExtra(EXTRA_SMB_USER).orEmpty(),
                password = intent.getStringExtra(EXTRA_SMB_PASSWORD).orEmpty(),
                domain = intent.getStringExtra(EXTRA_SMB_DOMAIN).orEmpty(),
                connectionHost = intent.getStringExtra(EXTRA_SMB_CONNECTION_HOST).orEmpty()
                    .ifBlank { host },
            )
            return PlaybackSpec(
                uri = uri,
                title = intent.getStringExtra(EXTRA_TITLE) ?: uri.substringAfterLast('/'),
                stereoMode = StereoMode.fromWire(intent.getIntExtra(EXTRA_MODE, 0)),
                subtitleUri = intent.getStringExtra(EXTRA_SUBTITLE),
                smb = smb,
                projectionExplicit = intent.getBooleanExtra(EXTRA_MODE_EXPLICIT, false),
            )
        }
    }
}

data class SmbCredentials(
    val host: String,
    val share: String,
    val username: String,
    val password: String,
    val domain: String,
    /** Resolvable address used for the socket; [host] remains the friendly user-facing identity. */
    val connectionHost: String = host,
)

val SmbCredentials.connectionTarget: String
    get() = connectionHost.ifBlank { host }
