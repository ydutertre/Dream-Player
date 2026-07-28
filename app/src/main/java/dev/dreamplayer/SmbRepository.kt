package dev.dreamplayer

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.util.EnumSet

data class SmbEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAtMs: Long,
)

class SmbRepository(private val credentials: SmbCredentials) : Closeable {
    private val client = SMBClient()
    private val connection = client.connect(credentials.connectionTarget)
    private val session = connection.authenticate(
        AuthenticationContext(
            credentials.username,
            credentials.password.toCharArray(),
            credentials.domain,
        )
    )
    val share = session.connectShare(credentials.share) as DiskShare

    fun list(path: String): List<SmbEntry> = share.list(path).asSequence()
        .filterNot { it.fileName == "." || it.fileName == ".." }
        .map {
            val isDirectory = EnumWithValue.EnumUtils.isSet(
                it.fileAttributes,
                FileAttributes.FILE_ATTRIBUTE_DIRECTORY,
            )
            SmbEntry(
                name = it.fileName,
                path = join(path, it.fileName),
                isDirectory = isDirectory,
                size = it.endOfFile,
                modifiedAtMs = it.lastWriteTime.toEpochMillis(),
            )
        }
        .sortedWith(compareBy<SmbEntry>({ !it.isDirectory }, { it.name.lowercase() }))
        .toList()

    fun open(path: String) = share.openFile(
        path,
        EnumSet.of(AccessMask.GENERIC_READ),
        null,
        com.hierynomus.mssmb2.SMB2ShareAccess.ALL,
        com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN,
        null,
    )

    override fun close() {
        share.close()
        session.close()
        connection.close()
        client.close()
    }

    companion object {
        fun join(parent: String, child: String): String = when {
            parent.isBlank() -> child
            parent.endsWith('\\') -> parent + child
            else -> "$parent\\$child"
        }
    }
}
