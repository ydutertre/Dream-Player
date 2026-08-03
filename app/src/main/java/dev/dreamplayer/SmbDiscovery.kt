package dev.dreamplayer

import android.content.Context
import android.net.ConnectivityManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class SmbServer(
    /** Stable connection target discovered on the LAN, normally an IPv4 address. */
    val host: String,
    /** Friendly DNS/NetBIOS name when one can be resolved, otherwise [host]. */
    val displayName: String = host,
)

private val IPV4_ADDRESS = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")

internal fun normalizeSmbHost(value: String): String = value.trim()
    .trimEnd('.')
    .lowercase(Locale.ROOT)
    .removeSuffix(".local")

internal fun smbHostsMatch(first: String, second: String): Boolean {
    val left = normalizeSmbHost(first)
    val right = normalizeSmbHost(second)
    if (left.isBlank() || right.isBlank()) return false
    if (left == right) return true
    return !IPV4_ADDRESS.matches(left) && !IPV4_ADDRESS.matches(right) &&
        left.substringBefore('.') == right.substringBefore('.')
}

internal fun smbServersMatch(first: SmbServer, second: SmbServer): Boolean {
    val firstNames = sequenceOf(first.host, first.displayName).filter { it.isNotBlank() }
    val secondNames = sequenceOf(second.host, second.displayName).filter { it.isNotBlank() }.toList()
    return firstNames.any { firstName ->
        secondNames.any { secondName -> smbHostsMatch(firstName, secondName) }
    }
}

/**
 * Combines the remembered profile with the current scan. A friendly hostname is the
 * stable identity; the freshly discovered address is the current connection target.
 */
internal fun mergeSmbServers(
    remembered: SmbServer?,
    discovered: List<SmbServer>,
): List<SmbServer> {
    val liveServers = ArrayList<SmbServer>(discovered.size)
    discovered.forEach { server ->
        if (liveServers.none { smbServersMatch(it, server) }) liveServers += server
    }
    if (remembered == null || liveServers.any { smbServersMatch(it, remembered) }) {
        return liveServers
    }
    return listOf(remembered) + liveServers
}

/** Finds SMB2/3 hosts by probing TCP 445 on the active LAN. */
class SmbDiscovery(private val context: Context) {
    fun discover(): List<SmbServer> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return emptyList()
        val link = connectivity.getLinkProperties(network) ?: return emptyList()
        val address = link.linkAddresses.firstOrNull { it.address is Inet4Address }?.address as? Inet4Address
            ?: return emptyList()
        val octets = address.address.map { it.toInt() and 0xff }
        val hosts = (1..254)
            .filterNot { it == octets[3] }
            .map { "${octets[0]}.${octets[1]}.${octets[2]}.$it" }

        val pool = Executors.newFixedThreadPool(48)
        return try {
            pool.invokeAll(hosts.map { host ->
                Callable {
                    if (hasSmb(host)) SmbServer(host, resolveFriendlyName(host) ?: host) else null
                }
            })
                .mapNotNull { runCatching { it.get() }.getOrNull() }
                .sortedWith(
                    compareBy<SmbServer> { it.displayName == it.host }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
                        .thenBy { it.host.substringAfterLast('.').toIntOrNull() ?: Int.MAX_VALUE }
                )
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    private fun hasSmb(host: String): Boolean = runCatching {
        Socket().use { socket -> socket.connect(InetSocketAddress(host, 445), 220) }
        true
    }.getOrDefault(false)

    private fun resolveFriendlyName(host: String): String? =
        resolveNetBiosName(host) ?: runCatching {
            InetAddress.getByName(host).canonicalHostName
                .trim()
                .trimEnd('.')
                .takeIf { it.isNotBlank() && !it.equals(host, ignoreCase = true) }
        }.getOrNull()

    /**
     * NetBIOS node-status is still the most reliable way to obtain a friendly name from
     * many Windows PCs and NAS devices discovered only by IPv4 address. Modern hosts that
     * disable NBNS simply time out and fall through to reverse DNS.
     */
    private fun resolveNetBiosName(host: String): String? = runCatching {
        val transactionId = Random.nextInt(0, 0x10000)
        val query = ByteBuffer.allocate(50).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(transactionId.toShort())
            putShort(0.toShort()) // Standard query.
            putShort(1.toShort()) // One question.
            putShort(0.toShort())
            putShort(0.toShort())
            putShort(0.toShort())
            put(32.toByte()) // Encoded NetBIOS name length.
            val wildcard = ByteArray(16) { if (it == 0) '*'.code.toByte() else 0 }
            wildcard.forEach { value ->
                val unsigned = value.toInt() and 0xff
                put(('A'.code + (unsigned ushr 4)).toByte())
                put(('A'.code + (unsigned and 0x0f)).toByte())
            }
            put(0.toByte()) // Root scope.
            putShort(0x0021.toShort()) // NBSTAT.
            putShort(0x0001.toShort()) // IN.
        }.array()
        val response = ByteArray(1024)
        DatagramSocket().use { socket ->
            socket.soTimeout = 280
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(host), 137))
            val packet = DatagramPacket(response, response.size)
            socket.receive(packet)
            parseNetBiosNodeStatus(response, packet.length, transactionId)
        }
    }.getOrNull()

    private fun parseNetBiosNodeStatus(data: ByteArray, length: Int, transactionId: Int): String? {
        if (length < 20) return null
        val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
        if ((buffer.short.toInt() and 0xffff) != transactionId) return null
        val flags = buffer.short.toInt() and 0xffff
        val questionCount = buffer.short.toInt() and 0xffff
        val answerCount = buffer.short.toInt() and 0xffff
        buffer.position(buffer.position() + 4) // Authority and additional counts.
        if (flags and 0x8000 == 0 || answerCount == 0) return null

        repeat(questionCount) {
            skipDnsName(buffer)
            if (buffer.remaining() < 4) return null
            buffer.position(buffer.position() + 4)
        }
        repeat(answerCount) {
            skipDnsName(buffer)
            if (buffer.remaining() < 10) return null
            val type = buffer.short.toInt() and 0xffff
            buffer.short // Class.
            buffer.int // TTL.
            val dataLength = buffer.short.toInt() and 0xffff
            if (dataLength > buffer.remaining()) return null
            val end = buffer.position() + dataLength
            if (type == 0x0021 && dataLength >= 1) {
                val nameCount = buffer.get().toInt() and 0xff
                repeat(nameCount) {
                    if (buffer.position() + 18 > end) return@repeat
                    val rawName = ByteArray(15)
                    buffer.get(rawName)
                    val suffix = buffer.get().toInt() and 0xff
                    val nameFlags = buffer.short.toInt() and 0xffff
                    val candidate = rawName.toString(Charsets.US_ASCII).trim()
                    val isGroup = nameFlags and 0x8000 != 0
                    if (
                        suffix == 0x00 && !isGroup && candidate.isNotBlank() &&
                        candidate != "*" && candidate != "__MSBROWSE__"
                    ) {
                        return candidate
                    }
                }
            }
            buffer.position(end)
        }
        return null
    }

    private fun skipDnsName(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            val size = buffer.get().toInt() and 0xff
            if (size == 0) return
            if (size and 0xc0 == 0xc0) {
                if (buffer.hasRemaining()) buffer.get()
                return
            }
            if (size > buffer.remaining()) {
                buffer.position(buffer.limit())
                return
            }
            buffer.position(buffer.position() + size)
        }
    }
}
