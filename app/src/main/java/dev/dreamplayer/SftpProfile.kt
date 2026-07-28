package dev.dreamplayer

import java.util.UUID

data class SftpProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val username: String = "",
    val password: String = "",
    val startPath: String = ".",
    val hostKeyFingerprint: String = "",
    val rememberPassword: Boolean = true,
) {
    val displayName: String
        get() = name.trim().ifBlank { host.trim() }

    fun normalized(): SftpProfile = copy(
        name = name.trim(),
        host = host.trim(),
        port = port.coerceIn(1, 65_535),
        username = username.trim(),
        startPath = startPath.trim().ifBlank { "." },
        hostKeyFingerprint = hostKeyFingerprint.trim(),
    )

    companion object {
        const val DEFAULT_PORT = 22
    }
}

data class SftpEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAtMs: Long,
)

data class SftpNavigationState(
    val path: String,
    val entries: List<SftpEntry>,
    val scrollOffset: Float,
)
