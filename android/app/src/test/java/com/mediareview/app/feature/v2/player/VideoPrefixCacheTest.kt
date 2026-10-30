package com.mediareview.app.feature.v2.player

import com.mediareview.app.feature.v2.model.V2PlaybackEndpoint
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class VideoPrefixCacheTest {
    private fun response(body: String, start: Int, total: Int, etag: String = "\"v1\"") =
        MockResponse().setResponseCode(206).setBody(body).setHeader("ETag", etag)
            .setHeader("Content-Type", "video/mp4")
            .setHeader("Content-Range", "bytes $start-${start + body.length - 1}/$total")

    @Test fun `prefetch requires consumer and preserves bounded shared prefix`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            var scope: String? = "pair-a"
            val cache = VideoPrefixCache(OkHttpClient(), { scope }, prefixBytes = 3)
            val endpoint = V2PlaybackEndpoint(server.url("/v.mp4").toString(), mapOf("Authorization" to "Bearer sample"))
            cache.prefetch(endpoint)
            assertEquals(0, server.requestCount)
            cache.attachConsumer()
            server.enqueue(response("ABC", 0, 6))
            cache.prefetch(endpoint)
            assertEquals("bytes=0-2", server.takeRequest().getHeader("Range"))
            val entry = cache.find(endpoint)!!
            assertArrayEquals("ABC".toByteArray(), entry.bytes)
            assertNull(cache.find(endpoint.copy(headers = mapOf("Authorization" to "different"))))
            scope = "pair-b"
            assertNull(cache.find(endpoint))
        } finally { server.shutdown() }
    }

    @Test fun `real reader consumes cached prefix and validated tail only`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val client = OkHttpClient()
            val cache = VideoPrefixCache(client, { "pair" }, prefixBytes = 3)
            cache.attachConsumer()
            val endpoint = V2PlaybackEndpoint(server.url("/v.mp4").toString())
            server.enqueue(response("ABC", 0, 6))
            cache.prefetch(endpoint)
            server.takeRequest()
            server.enqueue(response("DEF", 3, 6))
            val reader = VideoPrefixReader(cache, endpoint, OkHttpRangeConnection(client, endpoint))
            assertEquals(6L, reader.open(0, -1))
            val bytes = ByteArray(6)
            var offset = 0
            while (offset < bytes.size) offset += reader.read(bytes, offset, bytes.size - offset)
            assertEquals(-1, reader.read(ByteArray(1), 0, 1))
            assertEquals("ABCDEF", String(bytes))
            reader.close()
            val tail = server.takeRequest()
            assertEquals("bytes=3-5", tail.getHeader("Range"))
            assertEquals("\"v1\"", tail.getHeader("If-Match"))
        } finally { server.shutdown() }
    }

    @Test fun `changed entity falls back before delivering old bytes`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val client = OkHttpClient()
            val cache = VideoPrefixCache(client, { "pair" }, prefixBytes = 3)
            cache.attachConsumer()
            val endpoint = V2PlaybackEndpoint(server.url("/v.mp4").toString())
            server.enqueue(response("OLD", 0, 6))
            cache.prefetch(endpoint)
            server.enqueue(MockResponse().setResponseCode(412))
            server.enqueue(MockResponse().setBody("NEWNEW"))
            val reader = VideoPrefixReader(cache, endpoint, OkHttpRangeConnection(client, endpoint))
            assertEquals(6L, reader.open(0, -1))
            val bytes = ByteArray(6)
            assertEquals(6, reader.read(bytes, 0, 6))
            assertEquals("NEWNEW", String(bytes))
            reader.close()
            assertNull(cache.find(endpoint))
        } finally { server.shutdown() }
    }

    @Test fun `unauthorized validation and changed pairing never serve cached bytes`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val client = OkHttpClient()
            var scope: String? = "pair"
            val cache = VideoPrefixCache(client, { scope }, prefixBytes = 3)
            cache.attachConsumer()
            val endpoint = V2PlaybackEndpoint(server.url("/v.mp4").toString())
            server.enqueue(response("ABC", 0, 6))
            cache.prefetch(endpoint)
            for (code in listOf(401, 403)) {
                server.enqueue(MockResponse().setResponseCode(code))
                val denied = VideoPrefixReader(cache, endpoint, OkHttpRangeConnection(client, endpoint))
                try { denied.open(0, -1); fail("must reject $code") } catch (_: IOException) { }
                denied.close()
            }
            server.enqueue(response("DEF", 3, 6))
            val reader = VideoPrefixReader(cache, endpoint, OkHttpRangeConnection(client, endpoint))
            reader.open(0, -1)
            scope = "new-pair"
            try { reader.read(ByteArray(3), 0, 3); fail("must reject changed pairing") } catch (_: IOException) { }
            reader.close()
        } finally { server.shutdown() }
    }

    @Test fun `no store weak validator and full body are not cached`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val cache = VideoPrefixCache(OkHttpClient(), { "pair" }, prefixBytes = 3)
            cache.attachConsumer()
            val endpoint = V2PlaybackEndpoint(server.url("/v.mp4").toString())
            for (reply in listOf(response("ABC", 0, 6).setHeader("Cache-Control", "private, no-store"),
                response("ABC", 0, 6, "W/\"weak\""), MockResponse().setBody("WHOLEFILE"))) {
                server.enqueue(reply)
                cache.prefetch(endpoint)
                assertNull(cache.find(endpoint))
                cache.clear()
            }
        } finally { server.shutdown() }
    }

    @Test fun `cache evicts by capacity and expiry and seek outside stays network only`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            var now = 0L
            val client = OkHttpClient()
            val cache = VideoPrefixCache(client, { "pair" }, prefixBytes = 3, nowMs = { now })
            cache.attachConsumer()
            val endpoints = (1..3).map { V2PlaybackEndpoint(server.url("/$it.mp4").toString()) }
            for (endpoint in endpoints) { server.enqueue(response("ABC", 0, 6)); cache.prefetch(endpoint) }
            assertNull(cache.find(endpoints[0]))
            val endpoint = endpoints[2]
            server.enqueue(response("EF", 4, 6))
            val reader = VideoPrefixReader(cache, endpoint, OkHttpRangeConnection(client, endpoint))
            assertEquals(2L, reader.open(4, 2))
            assertEquals(2, reader.read(ByteArray(2), 0, 2))
            reader.close()
            repeat(3) { server.takeRequest() }
            assertNull(server.takeRequest().getHeader("If-Match"))
            now = 30_001
            assertNull(cache.find(endpoint))
        } finally { server.shutdown() }
    }

    @Test fun `cancellation cancels in flight network call`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val call = java.util.concurrent.atomic.AtomicReference<okhttp3.Call>()
        val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS)
            .eventListener(object : okhttp3.EventListener() {
                override fun callStart(started: okhttp3.Call) { call.set(started) }
            }).build()
        val cache = VideoPrefixCache(client, { "pair" })
        cache.attachConsumer()
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val job = launch(Dispatchers.Default) {
            cache.prefetch(V2PlaybackEndpoint(server.url("/v.mp4").toString()))
        }
        try {
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(call.get().isCanceled())
        } finally { job.cancelAndJoin(); client.dispatcher.cancelAll(); server.shutdown() }
    }

    @Test fun `small range validates before cached read and reopened reader does not reuse cursor`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val client = OkHttpClient()
            val cache = VideoPrefixCache(client, { "pair" }, prefixBytes = 3)
            cache.attachConsumer()
            val endpoint = V2PlaybackEndpoint(server.url("/v.mp4").toString())
            server.enqueue(response("ABC", 0, 6))
            cache.prefetch(endpoint)
            server.enqueue(response("A", 0, 6))
            val reader = VideoPrefixReader(cache, endpoint, OkHttpRangeConnection(client, endpoint))
            assertEquals(1L, reader.open(1, 1))
            val bytes = ByteArray(1)
            assertEquals(1, reader.read(bytes, 0, 1))
            assertEquals("B", String(bytes))
            reader.close()
            cache.clear()
            server.enqueue(MockResponse().setBody("NEW"))
            reader.open(0, -1)
            assertEquals(1, reader.read(bytes, 0, 1))
            assertEquals("N", String(bytes))
            reader.close()
        } finally { server.shutdown() }
    }

    @Test fun `changed validator no store and malformed tail fall back without mixing bytes`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val client = OkHttpClient()
            val cache = VideoPrefixCache(client, { "pair" }, prefixBytes = 3)
            cache.attachConsumer()
            val endpoint = V2PlaybackEndpoint(server.url("/v.mp4").toString())
            for (invalid in listOf(response("DEF", 3, 6, "\"v2\""),
                response("DEF", 3, 6).setHeader("Cache-Control", "no-store"),
                response("DEF", 3, 6).setHeader("Content-Range", "bytes 2-4/6"))) {
                cache.clear()
                server.enqueue(response("OLD", 0, 6))
                cache.prefetch(endpoint)
                server.enqueue(invalid)
                server.enqueue(MockResponse().setBody("NEWNEW"))
                val reader = VideoPrefixReader(cache, endpoint, OkHttpRangeConnection(client, endpoint))
                assertEquals(6L, reader.open(0, -1))
                val bytes = ByteArray(6)
                assertEquals(6, reader.read(bytes, 0, 6))
                assertEquals("NEWNEW", String(bytes))
                reader.close()
            }
        } finally { server.shutdown() }
    }
}

/** Real HTTP transport for the shared reader; production supplies Media3's original transport. */
private class OkHttpRangeConnection(private val client: OkHttpClient, private val endpoint: V2PlaybackEndpoint) : VideoRangeConnection {
    private var response: okhttp3.Response? = null
    override fun open(position: Long, length: Long, ifMatch: String?): VideoRangeResponse {
        val request = okhttp3.Request.Builder().url(endpoint.url).apply {
            endpoint.headers.forEach { (name, value) -> header(name, value) }
            if (position > 0 || length > 0) header("Range", "bytes=$position-${if (length > 0) (position + length - 1).toString() else ""}")
            ifMatch?.let { header("If-Match", it) }
        }.build()
        val opened = client.newCall(request).execute()
        response = opened
        return VideoRangeResponse(opened.code, opened.body?.contentLength() ?: -1, opened.request.url.toString(), opened.headers.toMultimap())
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = response!!.body!!.byteStream().read(buffer, offset, length)
    override fun close() { response?.close(); response = null }
}
