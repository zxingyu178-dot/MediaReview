package com.mediareview.app.feature.v2.player

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.platform.app.InstrumentationRegistry
import com.mediareview.app.feature.v2.model.V2PlaybackEndpoint
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** 合成双音轨MP4及真实分段HLS；仅记录认证是否匹配。 */
internal class Stage8HTrackSourceFixture(
    private val blockOriginal: Boolean = false,
    private val blockReplacement: Boolean = false,
) : AutoCloseable {
    data class Hit(val path: String, val authenticated: Boolean)
    val hits = CopyOnWriteArrayList<Hit>()
    val originalRequested = CountDownLatch(1)
    val replacementRequested = CountDownLatch(1)
    private val originalGate = CountDownLatch(1)
    private val replacementGate = CountDownLatch(1)
    fun releaseOriginal() = originalGate.countDown()
    fun releaseReplacement() = replacementGate.countDown()
    private val greenPoster = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        output.toByteArray()
    }
    private val credential = UUID.randomUUID().toString()
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val mp4 = assets.open("dual_audio_60s.mp4").use { it.readBytes() }
    private val server = MockWebServer()
    val mp4Endpoint: V2PlaybackEndpoint
    val replacementEndpoint: V2PlaybackEndpoint
    val hlsEndpoint: V2PlaybackEndpoint
    val coverUrl: String

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                // Public synthetic cover is separate from authenticated media evidence.
                if (path == "/poster.png") return MockResponse()
                    .setHeader("Content-Type", "image/png").setBody(Buffer().write(greenPoster))
                val authenticated = request.getHeader("X-Emby-Token") == credential
                hits += Hit(path, authenticated)
                if (!authenticated) return MockResponse().setResponseCode(401)
                if (path == "/original.mp4") {
                    originalRequested.countDown()
                    if (blockOriginal && !originalGate.await(45, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                    return rangedMp4(request)
                }
                if (path == "/replacement.mp4") {
                    replacementRequested.countDown()
                    if (blockReplacement && !replacementGate.await(45, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                    return rangedMp4(request)
                }
                val file = path.removePrefix("/hls/")
                if (path.startsWith("/hls/") && (file == "index.m3u8" || Regex("segment\\d{3}\\.ts").matches(file))) {
                    val bytes = assets.open("hls_fixture/$file").use { it.readBytes() }
                    return MockResponse().setHeader("Content-Type", if (file.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t")
                        .setBody(Buffer().write(bytes))
                }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start(InetAddress.getByName("0.0.0.0"), 0)
        val host = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().first().hostAddress!!
        fun endpoint(path: String) = V2PlaybackEndpoint("http://$host:${server.port}$path", mapOf("X-Emby-Token" to credential))
        mp4Endpoint = endpoint("/original.mp4")
        replacementEndpoint = endpoint("/replacement.mp4")
        hlsEndpoint = endpoint("/hls/index.m3u8")
        coverUrl = "http://$host:${server.port}/poster.png"
    }

    private fun rangedMp4(request: RecordedRequest): MockResponse {
        val response = MockResponse().setHeader("Content-Type", "video/mp4").setHeader("Accept-Ranges", "bytes")
        val range = request.getHeader("Range") ?: return response.setBody(Buffer().write(mp4))
        val match = Regex("bytes=(\\d+)-(\\d*)").matchEntire(range)
            ?: return MockResponse().setResponseCode(416)
        val start = match.groupValues[1].toInt()
        val end = (match.groupValues[2].toIntOrNull() ?: mp4.lastIndex).coerceAtMost(mp4.lastIndex)
        if (start > end || start < 0) return MockResponse().setResponseCode(416)
        return response.setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${mp4.size}")
            .setBody(Buffer().write(mp4, start, end - start + 1))
    }
    override fun close() {
        releaseOriginal()
        releaseReplacement()
        server.shutdown()
    }
}
