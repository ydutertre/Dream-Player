package dev.dreamplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbStorageRepositoryTest {
    @Test fun videoFilesAreRecognizedWithoutProviderMimeTypes() {
        listOf("Film.mkv", "Film.MP4", "Film.M2TS", "Film.MTS", "Film.WEBM", "Film.AVI")
            .forEach { assertTrue(it, UsbStorageRepository.isVideoFileName(it)) }
    }

    @Test fun nonVideoFilesAreNotListedAsVideos() {
        listOf("Film.srt", "Film.mkv.txt", "Film", "poster.jpg", "metadata.nfo")
            .forEach { assertFalse(it, UsbStorageRepository.isVideoFileName(it)) }
    }
}
