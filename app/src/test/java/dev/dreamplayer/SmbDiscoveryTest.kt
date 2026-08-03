package dev.dreamplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbDiscoveryTest {
    @Test
    fun changedAddressUsesTheFreshlyDiscoveredServer() {
        val remembered = SmbServer("192.0.2.20", "MEDIA-NAS")
        val discovered = listOf(SmbServer("192.0.2.41", "MEDIA-NAS"))

        assertEquals(discovered, mergeSmbServers(remembered, discovered))
    }

    @Test
    fun rememberedServerRemainsWhenItIsNotDiscovered() {
        val remembered = SmbServer("192.0.2.20", "MEDIA-NAS")
        val discovered = listOf(SmbServer("192.0.2.30", "MEDIA-PC"))

        assertEquals(listOf(remembered) + discovered, mergeSmbServers(remembered, discovered))
    }

    @Test
    fun addressOnlyServersAreNotMerged() {
        val servers = listOf(
            SmbServer("192.0.2.20"),
            SmbServer("192.0.2.41"),
        )

        assertEquals(servers, mergeSmbServers(null, servers))
    }

    @Test
    fun shortMdnsAndDnsNamesReferToTheSameServer() {
        assertTrue(smbHostsMatch("MEDIA-NAS.local.", "MEDIA-NAS"))
        assertTrue(smbHostsMatch("MEDIA-NAS.example", "MEDIA-NAS"))
    }
}
