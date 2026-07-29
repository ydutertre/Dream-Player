package dev.dreamplayer

import android.util.Log
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress

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
    private var mediaStream: InputStream? = null

    init {
        var phase = "opening the control connection"
        try {
            client.connectTimeout = CONNECT_TIMEOUT_MS
            client.defaultTimeout = SOCKET_TIMEOUT_MS
            client.dataTimeout = java.time.Duration.ofMillis(
                DATA_TIMEOUT_MS.toLong()
            )
            client.controlEncoding = Charsets.UTF_8.name()
            client.setAutodetectUTF8(true)
            connectWithAddressFallback()
            if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                throw IOException("Server refused connection: ${reply()}")
            }
            phase = "authenticating"
            if (!client.login(profile.username, profile.password)) {
                throw IOException("Authentication failed: ${reply()}")
            }
            if (client is FTPSClient) {
                phase = "protecting the FTPS data channel"
                client.execPBSZ(0)
                client.execPROT("P")
            }
            phase = "enabling passive binary mode"
            client.enterLocalPassiveMode()
            client.setUseEPSVwithIPv4(true)
            if (!client.setFileType(FTP.BINARY_FILE_TYPE)) {
                throw IOException("Could not enable binary mode: ${reply()}")
            }
            Log.i(
                TAG,
                "${profile.protocol.label} session ready for " +
                    "${profile.host}:${profile.port}",
            )
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "${profile.protocol.label} failed while $phase for " +
                    "${profile.host}:${profile.port}; reply=${reply()}",
                error,
            )
            close()
            throw IOException(
                "${profile.protocol.label} failed while $phase: " +
                    error.message.orEmpty(),
                error,
            )
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

    fun openMedia(path: String, position: Long): Long {
        runCatching { mediaStream?.close() }
        mediaStream = null
        val metadata = client.mlistFile(path)
            ?: client.listFiles(path).firstOrNull { it.isFile }
            ?: throw IOException("Remote file was not found: $path")
        if (position > metadata.size) {
            throw IOException(
                "Position $position exceeds remote file size ${metadata.size}"
            )
        }
        client.restartOffset = position
        mediaStream = client.retrieveFileStream(path)
            ?: throw IOException("Server refused media download: ${reply()}")
        return metadata.size
    }

    fun readMedia(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = requireNotNull(mediaStream) { "No FTP media stream is open" }
        .read(buffer, offset, length)

    fun list(path: String): List<SftpEntry> {
        val files = try {
            client.listFiles(path)
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "${profile.protocol.label} directory listing failed for " +
                    profile.host,
                error,
            )
            throw IOException(
                "Directory listing failed: ${error.message.orEmpty()}",
                error,
            )
        }
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

    /**
     * Android's hostname connect path can stop after the first resolved
     * address. Some seedboxes advertise IPv6 even though their custom FTP
     * port is only bound on IPv4, so try every address explicitly.
     */
    private fun connectWithAddressFallback() {
        val addresses = InetAddress.getAllByName(profile.host)
            .sortedByDescending { it is Inet4Address }
        var lastError: Throwable? = null
        for (address in addresses) {
            try {
                Log.i(
                    TAG,
                    "Trying ${profile.protocol.label} ${address.hostAddress}",
                )
                client.connect(address, profile.port)
                return
            } catch (error: Throwable) {
                lastError = error
                if (client.isConnected) {
                    runCatching { client.disconnect() }
                }
            }
        }
        throw lastError ?: IOException(
            "No network addresses were found for ${profile.host}"
        )
    }

    override fun close() {
        val hadMediaStream = mediaStream != null
        runCatching { mediaStream?.close() }
        mediaStream = null
        if (!client.isConnected) return
        // A partially consumed RETR stream (for example after seeking) has a
        // pending transfer reply. Waiting for completePendingCommand() here
        // can block until the socket timeout. This session is intentionally
        // short-lived, so disconnect it directly and reopen at the new offset.
        if (hadMediaStream) {
            runCatching { client.disconnect() }
            return
        }
        runCatching { client.logout() }
        runCatching { client.disconnect() }
    }

    companion object {
        private const val TAG = "DreamPlayerRemote"
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val SOCKET_TIMEOUT_MS = 20_000
        private const val DATA_TIMEOUT_MS = 20_000
    }
}
