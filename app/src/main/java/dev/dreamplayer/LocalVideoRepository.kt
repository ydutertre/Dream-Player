package dev.dreamplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class LocalVideo(
    val uri: Uri,
    val name: String,
    val folder: String,
    val size: Long,
    val durationMs: Long,
    val modifiedAtMs: Long,
)

class LocalVideoRepository(private val context: Context) {
    fun list(): List<LocalVideo> {
        // Keep removable media out of Local Storage. USB devices are browsed
        // directly through the document provider so files do not depend on
        // MediaStore indexing or MIME-type detection.
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_MODIFIED,
        )
        return buildList {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DISPLAY_NAME} COLLATE NOCASE ASC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val folderColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    add(
                        LocalVideo(
                            // Preserve the URI identity already used for resume/settings.
                            uri = ContentUris.withAppendedId(
                                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id),
                            name = cursor.getString(nameColumn) ?: "Video",
                            folder = cursor.getString(folderColumn)?.trimEnd('/')?.ifBlank { "Videos" } ?: "Videos",
                            size = cursor.getLong(sizeColumn),
                            durationMs = cursor.getLong(durationColumn),
                            modifiedAtMs = cursor.getLong(modifiedColumn) * 1_000L,
                        )
                    )
                }
            }
        }
    }
}
