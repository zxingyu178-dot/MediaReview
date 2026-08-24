package com.mediareview.app.feature.connect.discovery

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val DEFAULT_PORT = 8766
private const val MULTICAST_PORT = 35001
private const val MULTICAST_GROUP = "239.255.42.99"

data class DiscoveredServer(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val name: String = "",
) {
    val baseUrl: String
        get() = "http://${if (':' in host) "[$host]" else host}:$port"
}

fun parseDiscoveryReply(payload: String, sourceHost: String): DiscoveredServer? {
    val address = runCatching { InetAddress.getByName(sourceHost) }.getOrNull() ?: return null
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) return null
    val lines = payload.lines()
    if (lines.size != 2 || !lines[0].startsWith("MEDIAREVIEW ")) return null
    val name = lines[0].removePrefix("MEDIAREVIEW ").trim()
    val port = lines[1].trim().toIntOrNull() ?: return null
    if (name.isBlank() || port !in 1..65535) return null
    return DiscoveredServer(address.hostAddress ?: return null, port, name)
}

suspend fun confirmHealthyCandidates(
    candidates: List<DiscoveredServer>,
    healthCheck: suspend (String) -> Boolean,
): List<DiscoveredServer> {
    val unique = candidates.distinctBy { it.host.lowercase() to it.port }
    return unique.filter { candidate -> runCatching { healthCheck(candidate.baseUrl) }.getOrDefault(false) }
}

class ServerDiscovery(
    private val healthCheck: suspend (String) -> Boolean = ::defaultHealthCheck,
) {
    suspend fun discover(timeoutMs: Long = 2000): List<DiscoveredServer> =
        withContext(Dispatchers.IO) {
            val candidates = mutableListOf<DiscoveredServer>()
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 200
                val message = "MEDIAREVIEW_DISCOVER".toByteArray(Charsets.UTF_8)
                socket.send(
                    DatagramPacket(
                        message,
                        message.size,
                        InetAddress.getByName(MULTICAST_GROUP),
                        MULTICAST_PORT,
                    ),
                )
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    ensureActive()
                    try {
                        val response = DatagramPacket(buffer, buffer.size)
                        socket.receive(response)
                        parseDiscoveryReply(
                            String(response.data, response.offset, response.length, Charsets.UTF_8),
                            response.address.hostAddress.orEmpty(),
                        )?.let(candidates::add)
                    } catch (_: SocketTimeoutException) {
                        // Short timeout makes cancellation observable until use closes the socket.
                    }
                }
            }
            confirmHealthyCandidates(candidates, healthCheck)
        }
}

private suspend fun defaultHealthCheck(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
    val connection = URI("$baseUrl/api/v1/system/health").toURL().openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 1200
        connection.readTimeout = 1200
        connection.instanceFollowRedirects = false
        connection.responseCode == HttpURLConnection.HTTP_OK
    } finally {
        connection.disconnect()
    }
}
