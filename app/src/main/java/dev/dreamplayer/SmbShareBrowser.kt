package dev.dreamplayer

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.util.Properties

class SmbShareBrowser {
    fun listShares(credentials: SmbCredentials): List<String> {
        val context = createContext(credentials)
        return SmbFile("smb://${credentials.connectionTarget.trim('/')}/", context).use { root ->
            root.listFiles()
                .asSequence()
                .map { it.name.trimEnd('/') }
                .filter { it.isNotBlank() && !it.equals("IPC$", ignoreCase = true) }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .toList()
        }
    }

    companion object {
        fun createContext(credentials: SmbCredentials): CIFSContext {
            val properties = Properties().apply {
                setProperty("jcifs.smb.client.enableSMB2", "true")
                setProperty("jcifs.smb.client.disableSMB1", "true")
                setProperty("jcifs.smb.client.responseTimeout", "8000")
                setProperty("jcifs.smb.client.soTimeout", "12000")
            }
            val base = BaseContext(PropertyConfiguration(properties))
            val authenticator = NtlmPasswordAuthenticator(
                credentials.domain,
                credentials.username,
                credentials.password,
            )
            return base.withCredentials(authenticator)
        }
    }
}
