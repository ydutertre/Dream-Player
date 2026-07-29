package dev.dreamplayer

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.Closeable
import java.io.IOException

/** Password-authenticated FTP or explicit FTPS directory session. */
class FtpRepository(private val profile: SftpProfile) : Closeable {
    private val client: FTPClient = when (profile.protocol) {
        RemoteProtocol.FTPS -> FTPSClient(false).apply {
            // Commons Net otherwise validates certificate dates but does not
            // verify that the certificate belongs to the requested hostname.
            setTrustManager(null)
            isEndpointCheckingEnabled = true
            enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
        }
        RemoteProtocol.FTP -> FTPClient()
        RemoteProtocol.SFTP ->
            throw IllegalArgumentException("SFTP requires SftpRepository")
    }

    init {
        try {
            client.connectTimeout = CONNECT_TIMEOUT_MS
            client.defaultTimeout = SOCKET_TIMEOUT_MS
            client.dataTimeout = java.time.Duration.ofMillis(
                DATA_TIMEOUT_MS.toLong()
            )
            client.controlEncoding = Charsets.UTF_8.name()
            client.setAutodetectUTF8(true)
            client.connect(profile.host, profile.port)
            if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                throw IOException("Server refused connection: ${reply()}")
            }
            if (!client.login(profile.username, profile.password)) {
                throw IOException("Authentication failed: ${reply()}")
            }
            if (client is FTPSClient) {
                client.execPBSZ(0)
                client.execPROT("P")
            }
            client.enterLocalPassiveMode()
            client.setUseEPSVwithIPv4(true)
            if (!client.setFileType(FTP.BINARY_FILE_TYPE)) {
                throw IOException("Could not enable binary mode: ${reply()}")
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun canonicalize(path: String): String {
        val requested = path.trim().ifBlank { "." }
        if (!client.changeWorkingDirectory(requested)) {
            throw IOException("Start folder was not found: ${reply()}")
        }
        return client.printWorkingDirectory()
            ?.takeIf { it.isNotBlank() }
            ?: requested
    }

    fun list(path: String): List<SftpEntry> {
        val files = client.listFiles(path)
        if (!FTPReply.isPositiveCompletion(client.replyCode)) {
            throw IOException("Directory listing failed: ${reply()}")
        }
        return files.asSequence()
            .filterNot { it.name == "." || it.name == ".." }
            .filter { it.isDirectory || it.isFile }
            .map { item ->
                SftpEntry(
                    name = item.name,
                    path = SftpRepository.join(path, item.name),
                    isDirectory = item.isDirectory,
                    size = item.size.coerceAtLeast(0L),
                    modifiedAtMs = runCatching {
                        item.timestampInstant.toEpochMilli()
                    }.getOrDefault(0L),
                )
            }
            .sortedWith(
                compareBy<SftpEntry>(
                    { !it.isDirectory },
                    { it.name.lowercase() },
                )
            )
            .toList()
    }

    private fun reply(): String =
        client.replyString?.replace('\r', ' ')?.replace('\n', ' ')?.trim()
            .orEmpty()

    override fun close() {
        if (!client.isConnected) return
        runCatching { client.logout() }
        runCatching { client.disconnect() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val SOCKET_TIMEOUT_MS = 20_000
        private const val DATA_TIMEOUT_MS = 20_000
    }
}
