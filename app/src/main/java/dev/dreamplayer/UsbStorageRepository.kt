package dev.dreamplayer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File
import java.util.Locale

data class UsbLocation(
    val treeUri: Uri,
    val rootUri: Uri,
    val name: String,
)

data class UsbEntry(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAtMs: Long,
)

class UsbStorageRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences("usb-storage", Context.MODE_PRIVATE)

    fun locations(): List<UsbLocation> = (mountedLocations() + savedLocations())
        .distinctBy { it.treeUri.toString() }

    private fun savedLocations(): List<UsbLocation> = context.contentResolver.persistedUriPermissions
        .asSequence()
        .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
        .mapNotNull { permission -> location(permission.uri) }
        .distinctBy { it.treeUri.toString() }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        .toList()

    private fun mountedLocations(): List<UsbLocation> {
        val manager = context.getSystemService(StorageManager::class.java)
        return manager.storageVolumes.filter {
            !it.isPrimary && (it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY)
        }.mapNotNull { volume ->
            val id = if (Build.VERSION.SDK_INT >= 30) volume.mediaStoreVolumeName
                else volume.uuid?.lowercase(Locale.ROOT)
            id ?: return@mapNotNull null
            val uri = Uri.Builder().scheme("usb-volume").authority(id).build()
            UsbLocation(uri, uri, volume.getDescription(context))
        }
    }

    private fun volumeDirectory(volume: String): File? {
        if (Build.VERSION.SDK_INT < 30) return null
        return context.getSystemService(StorageManager::class.java).storageVolumes
            .firstOrNull { !it.isPrimary && it.mediaStoreVolumeName == volume }?.directory
    }

    // Use the same indexed media and playback URIs as the old Local Storage browser.
    // Also include directly readable files/folders, so indexing is not mandatory.
    private fun listMounted(treeUri: Uri, directoryUri: Uri, videosOnly: Boolean): List<UsbEntry> {
        check(mountedLocations().any { it.treeUri == treeUri }) { "USB drive disconnected. Reconnect it and refresh." }
        val volume = requireNotNull(treeUri.authority)
        val relative = directoryUri.path.orEmpty().trim('/')
        require(relative.split('/').none { it == ".." }) { "Invalid USB folder" }
        val prefix = if (relative.isEmpty()) "" else "$relative/"
        val entries = linkedMapOf<String, UsbEntry>()
        val indexed = LocalVideoRepository(context).list(volume, rootFolderLabel = "")
        indexed.forEach { video ->
            val folder = video.folder
            if (folder == relative) {
                entries[video.name] = UsbEntry(video.uri, video.name, false, video.size, video.modifiedAtMs)
            } else if (folder.startsWith(prefix) && folder.length > prefix.length) {
                val child = folder.removePrefix(prefix).substringBefore('/')
                val uri = treeUri.buildUpon().appendEncodedPath(Uri.encode(prefix + child, "/")).build()
                entries.putIfAbsent(child, UsbEntry(uri, child, true, 0L, 0L))
            }
        }
        val root = volumeDirectory(volume)
        val directory = root?.let { File(it, relative) }
        directory?.listFiles()?.forEach { file ->
            if (file.isDirectory || !videosOnly || isVideoFileName(file.name)) {
                val uri = if (file.isDirectory)
                    treeUri.buildUpon().appendEncodedPath(Uri.encode(prefix + file.name, "/")).build()
                else Uri.fromFile(file)
                entries.putIfAbsent(file.name, UsbEntry(uri, file.name, file.isDirectory, file.length(), file.lastModified()))
            }
        }
        return entries.values.toList()
    }

    fun location(treeUri: Uri): UsbLocation? = runCatching {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val name = preferences.getString("label:$treeUri", null)
            ?: rootId.ifBlank { "USB storage" }
        UsbLocation(treeUri, rootUri, name)
    }.getOrNull()

    fun remember(location: UsbLocation) {
        preferences.edit().putString("label:${location.treeUri}", location.name).apply()
    }

    // Document IDs are opaque: never infer parents by splitting their text.
    fun rememberParents(entries: List<UsbEntry>, parentUri: Uri) {
        val editor = preferences.edit()
        entries.forEach { editor.putString("parent:${it.uri}", parentUri.toString()) }
        editor.apply()
    }

    fun list(treeUri: Uri, directoryUri: Uri, videosOnly: Boolean = true): List<UsbEntry> {
        if (treeUri.scheme == "usb-volume") return listMounted(treeUri, directoryUri, videosOnly)
        context.contentResolver.query(directoryUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use {
            check(it.moveToFirst()) { "USB drive disconnected or folder no longer available" }
        } ?: error("USB drive unavailable. Reconnect it or add the location again.")
        val directoryId = DocumentsContract.getDocumentId(directoryUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            directoryId,
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return buildList {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                )
                val nameColumn = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                )
                val mimeColumn = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                )
                val sizeColumn = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_SIZE,
                )
                val modifiedColumn = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                )
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn) ?: continue
                    val mimeType = cursor.getString(mimeColumn).orEmpty()
                    val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                    if (videosOnly && !isDirectory && !isVideoFileName(name)) continue
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        cursor.getString(idColumn),
                    )
                    add(
                        UsbEntry(
                            uri = documentUri,
                            name = name,
                            isDirectory = isDirectory,
                            size = sizeColumn.takeIf { it >= 0 }?.let(cursor::getLong) ?: 0L,
                            modifiedAtMs = modifiedColumn.takeIf { it >= 0 }
                                ?.let(cursor::getLong) ?: 0L,
                        )
                    )
                }
            } ?: error("Could not read USB folder. Reconnect the drive or grant access again.")
        }
    }

    fun parentDirectory(treeUri: Uri, documentUri: Uri): Uri? = runCatching {
        if (treeUri.scheme == "usb-volume") {
            if (documentUri == treeUri) return null
            preferences.getString("parent:$documentUri", null)?.let { return Uri.parse(it) }
            if (documentUri.scheme == "usb-volume") {
                val path = documentUri.path.orEmpty().trim('/').substringBeforeLast('/', "")
                return treeUri.buildUpon().path(path.ifEmpty { null }).build()
            }
            if (documentUri.scheme == "file") {
                val root = volumeDirectory(requireNotNull(treeUri.authority)) ?: return null
                val parent = File(requireNotNull(documentUri.path)).parentFile ?: return null
                if (!parent.toPath().startsWith(root.toPath())) return null
                return treeUri.buildUpon().path(parent.relativeTo(root).path.replace('\\', '/')).build()
            }
            return context.contentResolver.query(documentUri,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use {
                if (!it.moveToFirst()) null else treeUri.buildUpon().path(it.getString(0)?.trim('/')).build()
            }
        }
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        if (DocumentsContract.getDocumentId(documentUri) == rootId) return null
        preferences.getString("parent:$documentUri", null)?.let(Uri::parse)
    }.getOrNull()

    fun name(documentUri: Uri): String = if (documentUri.scheme == "usb-volume")
        documentUri.lastPathSegment.orEmpty() else displayName(documentUri)

    fun matchingLocation(documentUri: Uri): UsbLocation? {
        if (!DocumentsContract.isTreeUri(documentUri)) {
            val mounted = mountedLocations()
            if (documentUri.scheme == "file") {
                val file = File(documentUri.path ?: return null).canonicalFile
                return mounted.firstOrNull { location ->
                    volumeDirectory(location.treeUri.authority.orEmpty())?.canonicalFile?.let {
                        file.toPath().startsWith(it.toPath())
                    } == true
                }
            }
            if (documentUri.authority != MediaStore.AUTHORITY) return null
            val volume = runCatching {
                context.contentResolver.query(documentUri, arrayOf(MediaStore.MediaColumns.VOLUME_NAME),
                    null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull()
            return mounted.firstOrNull { it.treeUri.authority == volume }
        }
        val treeId = runCatching {
            DocumentsContract.getTreeDocumentId(documentUri)
        }.getOrNull() ?: return null
        return locations().firstOrNull { location ->
            if (location.treeUri.authority != documentUri.authority) return@firstOrNull false
            val rootId = runCatching {
                DocumentsContract.getTreeDocumentId(location.treeUri)
            }.getOrNull() ?: return@firstOrNull false
            treeId == rootId
        }
    }

    private fun displayName(uri: Uri): String = context.contentResolver.query(
        uri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use ""
        cursor.getString(0).orEmpty()
    }.orEmpty()

    companion object {
        private val VIDEO_EXTENSIONS = setOf(
            "mkv", "mp4", "m4v", "mov", "avi", "webm", "ts", "m2ts", "mts",
            "mpg", "mpeg", "wmv",
        )

        fun isVideoFileName(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
    }
}
