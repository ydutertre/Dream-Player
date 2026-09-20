package dev.dreamplayer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

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

    fun locations(): List<UsbLocation> = context.contentResolver.persistedUriPermissions
        .asSequence()
        .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
        .mapNotNull { permission -> location(permission.uri) }
        .distinctBy { it.treeUri.toString() }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        .toList()

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
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        if (DocumentsContract.getDocumentId(documentUri) == rootId) return null
        preferences.getString("parent:$documentUri", null)?.let(Uri::parse)
    }.getOrNull()

    fun name(documentUri: Uri): String = displayName(documentUri)

    fun matchingLocation(documentUri: Uri): UsbLocation? {
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
