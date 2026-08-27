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
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val DEFAULT_PORT = 8766
private const val MULTICAST_PORT = 35001
private const val MULTICAST_GROUP = "239.255.42.99"
private const val MAX_HEALTH_BYTES = 8192

interface HealthResponse : AutoCloseable {
    val status: Int
    val contentType: String?
    fun readBody(limit: Int): ByteArray
}

fun validateMediaReviewHealth(status: Int, contentType: String?, body: ByteArray): Boolean {
    if (status != HttpURLConnection.HTTP_OK ||
        contentType?.substringBefore(';')?.trim()?.lowercase() != "application/json" ||
        body.size > MAX_HEALTH_BYTES
    ) return false
    return runCatching {
        val root = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
        if (root["success"]?.jsonPrimitive?.booleanOrNull != true) return false
        val data = root["data"]?.jsonObject ?: return false
        data["status"]?.jsonPrimitive?.content == "ok" &&
            data["version"]?.jsonPrimitive?.content?.isNotBlank() == true &&
            data["components"]?.jsonObject != null
    }.getOrDefault(false)
}

suspend fun probeMediaReviewHealth(open: suspend () -> HealthResponse): Boolean {
    val response = try { open() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return false }
    return try {
        validateMediaReviewHealth(response.status, response.contentType, response.readBody(MAX_HEALTH_BYTES))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    } finally {
        response.close()
    }
}

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
    probeMediaReviewHealth {
        val connection = URI("$baseUrl/api/v1/system/health").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 1200
        connection.readTimeout = 1200
        connection.instanceFollowRedirects = false
        object : HealthResponse {
            override val status: Int get() = connection.responseCode
            override val contentType: String? get() = connection.contentType
            override fun readBody(limit: Int): ByteArray {
                if (connection.contentLengthLong > limit) return ByteArray(limit + 1)
                return connection.inputStream.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    while (output.size() <= limit) {
                        val read = stream.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            }
            override fun close() = connection.disconnect()
        }
    }
}
