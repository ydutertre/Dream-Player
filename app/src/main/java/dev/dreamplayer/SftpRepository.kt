package dev.dreamplayer

import net.schmizz.sshj.AndroidConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.Closeable
import java.security.PublicKey

sealed class SftpHostKeyException(message: String) : Exception(message) {
    abstract val actualFingerprint: String
    abstract val algorithm: String

    data class Unknown(
        override val actualFingerprint: String,
        override val algorithm: String,
    ) : SftpHostKeyException("The SFTP server identity has not been trusted")

    data class Changed(
        val expectedFingerprint: String,
        override val actualFingerprint: String,
        override val algorithm: String,
    ) : SftpHostKeyException("The SFTP server identity has changed")
}

/**
 * One short-lived authenticated SFTP session. Browser calls create a fresh
 * instance so no SSH resources survive Activity navigation or failures.
 */
class SftpRepository(private val profile: SftpProfile) : Closeable {
    private var observedFingerprint = ""
    private var observedAlgorithm = ""
    private val ssh = SSHClient(AndroidConfig()).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
        timeout = SOCKET_TIMEOUT_MS
        addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(
                hostname: String,
                port: Int,
                key: PublicKey,
            ): Boolean {
                observedFingerprint = SecurityUtils.getFingerprint(key)
                observedAlgorithm = key.algorithm
                return profile.hostKeyFingerprint.isNotBlank() &&
                    constantTimeEquals(
                        observedFingerprint,
                        profile.hostKeyFingerprint,
                    )
            }

            override fun findExistingAlgorithms(
                hostname: String,
                port: Int,
            ): List<String> = emptyList()
        })
    }
    private lateinit var sftp: SFTPClient

    init {
        try {
            ssh.connect(profile.host, profile.port)
            ssh.authPassword(profile.username, profile.password)
            ssh.connection.keepAlive.keepAliveInterval = KEEP_ALIVE_SECONDS
            sftp = ssh.newSFTPClient()
        } catch (error: Throwable) {
            runCatching { ssh.close() }
            if (observedFingerprint.isNotBlank()) {
                if (profile.hostKeyFingerprint.isBlank()) {
                    throw SftpHostKeyException.Unknown(
                        observedFingerprint,
                        observedAlgorithm,
                    )
                }
                if (
                    !constantTimeEquals(
                        observedFingerprint,
                        profile.hostKeyFingerprint,
                    )
                ) {
                    throw SftpHostKeyException.Changed(
                        profile.hostKeyFingerprint,
                        observedFingerprint,
                        observedAlgorithm,
                    )
                }
            }
            throw error
        }
    }

    fun canonicalize(path: String): String = sftp.canonicalize(path)

    fun list(path: String): List<SftpEntry> = sftp.ls(path).asSequence()
        .filterNot { it.name == "." || it.name == ".." }
        .map { item ->
            val attributes = item.attributes
            SftpEntry(
                name = item.name,
                path = join(path, item.name),
                isDirectory = attributes.type ==
                    net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY,
                size = attributes.size,
                modifiedAtMs = attributes.mtime * 1_000L,
            )
        }
        .sortedWith(
            compareBy<SftpEntry>({ !it.isDirectory }, { it.name.lowercase() })
        )
        .toList()

    override fun close() {
        if (::sftp.isInitialized) runCatching { sftp.close() }
        runCatching { ssh.disconnect() }
        runCatching { ssh.close() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val SOCKET_TIMEOUT_MS = 20_000
        private const val KEEP_ALIVE_SECONDS = 20

        fun join(parent: String, child: String): String = when {
            parent == "/" -> "/$child"
            parent.endsWith('/') -> parent + child
            else -> "$parent/$child"
        }

        private fun constantTimeEquals(first: String, second: String): Boolean {
            val firstBytes = first.toByteArray(Charsets.UTF_8)
            val secondBytes = second.toByteArray(Charsets.UTF_8)
            return java.security.MessageDigest.isEqual(firstBytes, secondBytes)
        }
    }
}
