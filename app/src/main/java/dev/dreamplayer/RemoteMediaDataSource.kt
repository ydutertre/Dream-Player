package dev.dreamplayer

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.IOException

/**
 * Seekable authenticated media source for SFTP, FTP and explicit FTPS.
 *
 * Media3 seeks open a new request at the requested byte offset. Transient
 * failures reconnect at the last delivered byte, avoiding duplicate data.
 */
@UnstableApi
class RemoteMediaDataSource(
    private val profile: SftpProfile,
) : BaseDataSource(true) {
    private var sftp: SftpRepository? = null
    private var ftp: FtpRepository? = null
    private var openedUri: Uri? = null
    private var remotePath = ""
    private var position = 0L
    private var bytesRemaining = 0L
    private var transferOpen = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        openedUri = dataSpec.uri
        remotePath = dataSpec.uri.path.orEmpty().ifBlank { "/" }
        position = dataSpec.position
        val size = try {
            openSession(position)
        } catch (error: Throwable) {
            closeSessions()
            throw RemoteMediaException.open(profile.protocol, error)
        }
        if (position > size) {
            closeSessions()
            throw EOFException("Requested position exceeds remote file size")
        }
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            size - position
        } else {
            minOf(dataSpec.length, size - position)
        }
        transferOpen = true
        transferStarted(dataSpec)
        Log.i(TAG, "Opened ${profile.protocol.label} media at byte $position")
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val requested = minOf(length.toLong(), bytesRemaining).toInt()
        var lastError: Throwable? = null
        repeat(MAX_READ_ATTEMPTS) { attempt ->
            try {
                val count = when (profile.protocol) {
                    RemoteProtocol.SFTP ->
                        requireNotNull(sftp).readMedia(
                            position,
                            buffer,
                            offset,
                            requested,
                        )
                    RemoteProtocol.FTP,
                    RemoteProtocol.FTPS ->
                        requireNotNull(ftp).readMedia(buffer, offset, requested)
                }
                if (count < 0) return C.RESULT_END_OF_INPUT
                position += count
                bytesRemaining -= count
                bytesTransferred(count)
                return count
            } catch (error: Throwable) {
                lastError = error
                Log.w(
                    TAG,
                    "${profile.protocol.label} read failed at $position " +
                        "(attempt ${attempt + 1}/$MAX_READ_ATTEMPTS)",
                    error,
                )
                closeSessions()
                if (attempt + 1 < MAX_READ_ATTEMPTS) {
                    try {
                        Thread.sleep(RECONNECT_DELAYS_MS[attempt])
                        openSession(position)
                    } catch (reconnectError: Throwable) {
                        lastError = reconnectError
                    }
                }
            }
        }
        throw RemoteMediaException.read(
            profile.protocol,
            position,
            lastError,
        )
    }

    private fun openSession(offset: Long): Long = when (profile.protocol) {
        RemoteProtocol.SFTP -> SftpRepository(profile).also {
            sftp = it
        }.openMedia(remotePath)
        RemoteProtocol.FTP,
        RemoteProtocol.FTPS -> FtpRepository(profile).also {
            ftp = it
        }.openMedia(remotePath, offset)
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        val wasOpen = transferOpen
        closeSessions()
        openedUri = null
        remotePath = ""
        transferOpen = false
        if (wasOpen) transferEnded()
    }

    private fun closeSessions() {
        runCatching { sftp?.close() }
        runCatching { ftp?.close() }
        sftp = null
        ftp = null
    }

    class Factory(
        private val profile: SftpProfile,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            RemoteMediaDataSource(profile)
    }

    companion object {
        private const val TAG = "DreamPlayerRemote"
        private const val MAX_READ_ATTEMPTS = 3
        private val RECONNECT_DELAYS_MS = longArrayOf(500L, 1_500L)
    }
}

class RemoteMediaException(
    message: String,
    cause: Throwable?,
) : IOException(message, cause) {
    companion object {
        fun open(protocol: RemoteProtocol, cause: Throwable) =
            RemoteMediaException(
                "${protocol.label} media could not be opened: " +
                    cause.message.orEmpty(),
                cause,
            )

        fun read(
            protocol: RemoteProtocol,
            position: Long,
            cause: Throwable?,
        ) = RemoteMediaException(
            "${protocol.label} connection was lost at byte $position",
            cause,
        )
    }
}
