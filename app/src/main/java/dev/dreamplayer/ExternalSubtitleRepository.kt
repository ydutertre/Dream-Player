package dev.dreamplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.Locale

data class ExternalSubtitle(
    val uri: String,
    val fileName: String,
)

class ExternalSubtitleRepository(private val context: Context) {
    fun discover(spec: PlaybackSpec): List<ExternalSubtitle> = runCatching {
        when {
            spec.uri.startsWith("smb://", ignoreCase = true) -> discoverSmb(spec)
            Uri.parse(spec.uri).scheme.equals("file", ignoreCase = true) -> discoverFile(spec)
            else -> discoverMediaStore(spec)
        }
    }.onFailure { error ->
        Log.w(TAG, "External subtitle discovery failed for ${spec.uri}", error)
    }.getOrDefault(emptyList())

    private fun discoverSmb(spec: PlaybackSpec): List<ExternalSubtitle> {
        val credentials = spec.smb ?: return emptyList()
        val videoUri = Uri.parse(spec.uri)
        val videoPath = videoUri.pathSegments.joinToString("\\")
        val parentPath = videoPath.substringBeforeLast('\\', "")
        val videoName = videoPath.substringAfterLast('\\')
        val videoStem = videoName.substringBeforeLast('.', videoName)

        return SmbRepository(credentials).use { repository ->
            val parentEntries = repository.list(parentPath)
            buildList {
                parentEntries.asSequence()
                    .filterNot(SmbEntry::isDirectory)
                    .filter { isSrt(it.name) && stem(it.name).equals(videoStem, ignoreCase = true) }
                    .forEach { add(ExternalSubtitle(smbUri(credentials.host, it.path), it.name)) }

                parentEntries.asSequence()
                    .filter(SmbEntry::isDirectory)
                    .filter { isSubtitleDirectory(it.name) }
                    .forEach { directory ->
                        runCatching { repository.list(directory.path) }
                            .onFailure {
                                Log.w(TAG, "Could not scan SMB subtitle folder ${directory.path}", it)
                            }
                            .getOrDefault(emptyList())
                            .asSequence()
                            .filterNot(SmbEntry::isDirectory)
                            .filter { isSrt(it.name) }
                            .forEach {
                                add(ExternalSubtitle(smbUri(credentials.host, it.path), it.name))
                            }
                    }
            }
        }.distinctBy { it.uri.lowercase(Locale.ROOT) }
    }

    private fun discoverFile(spec: PlaybackSpec): List<ExternalSubtitle> {
        val video = Uri.parse(spec.uri).path?.let(::File) ?: return emptyList()
        val parent = video.parentFile ?: return emptyList()
        val videoStem = video.name.substringBeforeLast('.', video.name)
        return buildList {
            parent.listFiles().orEmpty()
                .asSequence()
                .filter(File::isFile)
                .filter { isSrt(it.name) && stem(it.name).equals(videoStem, ignoreCase = true) }
                .forEach { add(ExternalSubtitle(Uri.fromFile(it).toString(), it.name)) }
            parent.listFiles().orEmpty()
                .asSequence()
                .filter(File::isDirectory)
                .filter { isSubtitleDirectory(it.name) }
                .flatMap { it.listFiles().orEmpty().asSequence() }
                .filter(File::isFile)
                .filter { isSrt(it.name) }
                .forEach { add(ExternalSubtitle(Uri.fromFile(it).toString(), it.name)) }
        }.distinctBy { it.uri.lowercase(Locale.ROOT) }
    }

    private fun discoverMediaStore(spec: PlaybackSpec): List<ExternalSubtitle> {
        val videoUri = Uri.parse(spec.uri)
        val location = context.contentResolver.query(
            videoUri,
            arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            if (nameIndex < 0 || pathIndex < 0) return@use null
            LocalLocation(
                cursor.getString(nameIndex) ?: spec.title,
                normalizePath(cursor.getString(pathIndex).orEmpty()),
            )
        } ?: return emptyList()

        val volume = runCatching { MediaStore.getVolumeName(videoUri) }
            .getOrDefault(MediaStore.VOLUME_EXTERNAL)
        val filesUri = MediaStore.Files.getContentUri(volume)
        val videoStem = location.fileName.substringBeforeLast('.', location.fileName)
        val pathPrefix = if (location.relativePath.isBlank()) "%" else "${location.relativePath}/%"

        return buildList {
            context.contentResolver.query(
                filesUri,
                arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                ),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf(pathPrefix, "%.srt"),
                "${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE ASC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val fileName = cursor.getString(nameIndex) ?: continue
                    if (!isSrt(fileName)) continue
                    val relativePath = normalizePath(cursor.getString(pathIndex).orEmpty())
                    val besideVideo =
                        relativePath.equals(location.relativePath, ignoreCase = true) &&
                            stem(fileName).equals(videoStem, ignoreCase = true)
                    val inSubtitleFolder = directChildDirectory(
                        location.relativePath,
                        relativePath,
                    )?.let(::isSubtitleDirectory) == true
                    if (!besideVideo && !inSubtitleFolder) continue
                    val uri = ContentUris.withAppendedId(filesUri, cursor.getLong(idIndex))
                    add(ExternalSubtitle(uri.toString(), fileName))
                }
            }
        }.distinctBy { it.uri.lowercase(Locale.ROOT) }
    }

    private fun directChildDirectory(parent: String, candidate: String): String? {
        val normalizedParent = normalizePath(parent)
        val normalizedCandidate = normalizePath(candidate)
        val remainder = if (normalizedParent.isBlank()) {
            normalizedCandidate
        } else {
            if (!normalizedCandidate.startsWith("$normalizedParent/", ignoreCase = true)) return null
            normalizedCandidate.substring(normalizedParent.length + 1)
        }
        return remainder.takeIf { it.isNotBlank() && '/' !in it }
    }

    private fun smbUri(host: String, path: String): String = Uri.Builder()
        .scheme("smb")
        .authority(host)
        .apply { path.split('\\').filter(String::isNotEmpty).forEach(::appendPath) }
        .build()
        .toString()

    private fun normalizePath(path: String): String =
        path.replace('\\', '/').trim('/')

    private fun isSrt(name: String): Boolean =
        name.substringAfterLast('.', "").equals("srt", ignoreCase = true)

    private fun stem(name: String): String = name.substringBeforeLast('.', name)

    private fun isSubtitleDirectory(name: String): Boolean =
        name.lowercase(Locale.ROOT) in SUBTITLE_DIRECTORIES

    private data class LocalLocation(
        val fileName: String,
        val relativePath: String,
    )

    companion object {
        private const val TAG = "DreamPlayerSubtitle"
        private val SUBTITLE_DIRECTORIES = setOf("subs", "subtitle", "subtitles")
    }
}
