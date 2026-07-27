package dev.dreamplayer

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hierynomus.smbj.share.File
import java.io.EOFException

@UnstableApi
class SmbDataSource(private val credentials: SmbCredentials) : BaseDataSource(false) {
    private var repository: SmbRepository? = null
    private var file: File? = null
    private var openedUri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var readCount = 0
    private val readAhead = ByteArray(1024 * 1024)
    private var readAheadStart = 0L
    private var readAheadLength = 0

    override fun open(dataSpec: DataSpec): Long {
        try {
            transferInitializing(dataSpec)
            val path = dataSpec.uri.pathSegments.joinToString("\\")
            Log.i(TAG, "Opening //${credentials.host}/${credentials.share}/${path.replace('\\', '/')} at ${dataSpec.position}")
            repository = SmbRepository(credentials)
            file = repository!!.open(path)
            val size = file!!.fileInformation.standardInformation.endOfFile
            position = dataSpec.position
            readCount = 0
            readAheadStart = position
            readAheadLength = 0
            if (position > size) throw EOFException("Position $position exceeds SMB file size $size")
            bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) size - position else dataSpec.length
            openedUri = dataSpec.uri
            transferStarted(dataSpec)
            Log.i(TAG, "Opened ${dataSpec.uri.lastPathSegment}: $size bytes")
            return bytesRemaining
        } catch (error: Throwable) {
            Log.e(TAG, "SMB open failed for ${dataSpec.uri}", error)
            close()
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val requested = minOf(length.toLong(), bytesRemaining).toInt()
        val cachedOffset = (position - readAheadStart).toInt()
        if (cachedOffset < 0 || cachedOffset >= readAheadLength) {
            val fillLength = minOf(readAhead.size.toLong(), bytesRemaining).toInt()
            readAheadLength = try {
                file?.read(readAhead, position, 0, fillLength) ?: return C.RESULT_END_OF_INPUT
            } catch (error: Throwable) {
                Log.e(TAG, "SMB read failed at $position", error)
                throw error
            }
            if (readAheadLength <= 0) return C.RESULT_END_OF_INPUT
            readAheadStart = position
            if (readCount++ < 4) Log.i(TAG, "Buffered $readAheadLength bytes at $readAheadStart")
        }
        val sourceOffset = (position - readAheadStart).toInt()
        val count = minOf(requested, readAheadLength - sourceOffset)
        System.arraycopy(readAhead, sourceOffset, buffer, offset, count)
        position += count
        bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        val wasOpen = file != null
        runCatching { file?.close() }
        runCatching { repository?.close() }
        file = null
        repository = null
        openedUri = null
        if (wasOpen) transferEnded()
    }

    class Factory(private val credentials: SmbCredentials) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(credentials)
    }

    companion object { private const val TAG = "DreamPlayerSMB" }
}
