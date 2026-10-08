package com.mediareview.app.feature.v2.data.server

import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.MediaUrlResolver
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2MediaType
import com.mediareview.app.feature.v2.model.V2SpriteManifest
import com.mediareview.app.feature.v2.data.SpritePrepareResult
import com.mediareview.app.onlineServerStatus
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class V2ServerSpriteContractTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: V2ServerMediaRepository
    private lateinit var base: String
    private val media = V2Media("m1", "001", "视频", "f1", "文件夹", V2MediaType.VIDEO,
        12500, 1, 0, false, false, "", "", null, null, 1920, 1080)

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        base = "http://sprite.test:${server.port}"
        val http = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
        }).readTimeout(1, TimeUnit.SECONDS).build()
        repository = V2ServerMediaRepository(
            object : V2ServerProfilePort { override suspend fun baseUrl() = base },
            ApiFactory(http, http, Json { ignoreUnknownKeys = true }),
            V2MediaMapper(MediaUrlResolver()), V2PlaybackResolver(MediaUrlResolver()),
            object : V2ServerAlbumCoverPort {
                override suspend fun allCovers() = emptyMap<String, String>()
                override suspend fun setCover(folderId: String, mediaId: String?) = Unit
            }, onlineServerStatus(),
        )
    }
    @After fun teardown() { server.shutdown() }

    private suspend fun prepare(): SpritePrepareResult = repository.prepareSprite("m1")
    private fun response(data: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody("""{"success":true,"data":$data}""")
    private fun readyManifest(extra: String = "") = """{"media_id":"m1","status":"ready","columns":2,"rows":2,"count":3,"tile_width":160,"tile_height":90,"interval_ms":1250,"total_duration_ms":12500,"url":"/api/v1/cache/sprites/m1/image"$extra}"""
    private fun ensure(status: String = "ready", task: String = "") = response("""{"status":"$status","task_id":"$task"}""")
    private fun task(status: String) = response("""{"task_id":"t1","type":"sprite","status":"$status","media_id":"m1"}""")
    private fun failed(result: SpritePrepareResult) { assertTrue(result is SpritePrepareResult.Failed); assertNull(repository.spriteUri(media)) }

    @Test fun `ready maps units resolves URL and caches confirmed result`() = runTest {
        assertNull(repository.spriteUri(media)); assertNull(repository.spriteManifest(media))
        server.enqueue(ensure()); server.enqueue(response(readyManifest()))
        val result = prepare()
        assertEquals("Ready", result.javaClass.simpleName)
        assertEquals("$base/api/v1/cache/sprites/m1/image", repository.spriteUri(media))
        val manifest = repository.spriteManifest(media)!!
        assertEquals(1.25, manifest.frame_interval_s, 0.0); assertEquals(12.5, manifest.duration_s, 0.0)
        assertEquals(3, manifest.frame_count); assertEquals(160, manifest.cell_width)
        prepare(); assertEquals(2, server.requestCount)
        assertEquals("POST", server.takeRequest().method)
        assertEquals("/api/v1/cache/sprites/m1", server.takeRequest().path)
    }
    @Test fun `pending polls running then succeeded before fetching manifest`() = runTest {
        server.enqueue(ensure("pending", "t1")); server.enqueue(task("running"))
        server.enqueue(task("succeeded")); server.enqueue(response(readyManifest()))
        assertEquals("Ready", prepare().javaClass.simpleName); assertEquals(4, server.requestCount)
    }
    @Test fun `failed task keeps poster and no cache`() = runTest {
        server.enqueue(ensure("pending", "t1")); server.enqueue(task("failed")); failed(prepare())
        assertEquals(2, server.requestCount)
    }
    @Test fun `cancelled task keeps poster and never requests server cancel`() = runTest {
        server.enqueue(ensure("pending", "t1")); server.enqueue(task("cancelled")); failed(prepare())
        assertEquals(2, server.requestCount)
    }
    @Test fun `missing task fails immediately`() = runTest {
        server.enqueue(ensure("pending")); failed(prepare()); assertEquals(1, server.requestCount)
    }
    @Test fun `missing manifest fails`() = runTest {
        server.enqueue(ensure()); server.enqueue(response("null")); failed(prepare())
    }
    @Test fun `network HTTP failure returns failed`() = runTest {
        server.enqueue(response("null", 503)); failed(prepare())
    }
    @Test fun `manifest cannot contain credentials`() = runTest {
        server.enqueue(ensure()); server.enqueue(response(readyManifest().replace("/image", "/image?token=fixture")))
        failed(prepare())
    }
    @Test fun `malformed manifest dimensions counts units and identity rejected`() = runTest {
        val bad = listOf("\"columns\":2" to "\"columns\":0", "\"rows\":2" to "\"rows\":-1",
            "\"count\":3" to "\"count\":5", "\"count\":3" to "\"count\":0",
            "\"tile_width\":160" to "\"tile_width\":0", "\"tile_height\":90" to "\"tile_height\":-1",
            "\"interval_ms\":1250" to "\"interval_ms\":0", "\"total_duration_ms\":12500" to "\"total_duration_ms\":-1",
            "\"media_id\":\"m1\"" to "\"media_id\":\"other\"", "\"status\":\"ready\"" to "\"status\":\"pending\"")
        for ((from, to) in bad) {
            server.enqueue(ensure()); server.enqueue(response(readyManifest().replace(from, to))); failed(prepare())
        }
    }
    @Test fun `polling is finite for task that never completes`() = runTest {
        server.enqueue(ensure("pending", "t1")); repeat(50) { server.enqueue(task("running")) }
        failed(prepare()); assertTrue(server.requestCount in 2..31)
    }
    @Test fun `invalid task identity rejected`() = runTest {
        server.enqueue(ensure("pending", "t1")); server.enqueue(response("""{"task_id":"other","status":"succeeded","media_id":"m1"}"""))
        failed(prepare())
    }
    @Test fun `cache invalidation rejects same server late response`() = runTest {
        server.enqueue(ensure()); server.enqueue(response(readyManifest()).setBodyDelay(200, TimeUnit.MILLISECONDS))
        val work = async { prepare() }
        withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS); server.takeRequest(2, TimeUnit.SECONDS) }
        repository.invalidateAuxiliaryCache(); failed(work.await())
    }
    @Test fun `server switch rejects late response even without explicit invalidate`() = runTest {
        server.enqueue(ensure()); server.enqueue(response(readyManifest()).setBodyDelay(200, TimeUnit.MILLISECONDS))
        val work = async { prepare() }
        withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS); server.takeRequest(2, TimeUnit.SECONDS) }
        base = "http://other.test:${server.port}"; failed(work.await())
    }
    @Test fun `client cancellation propagates and never cancels remote task`() = runTest {
        server.enqueue(ensure("pending", "t1")); server.enqueue(task("running").setBodyDelay(200, TimeUnit.MILLISECONDS))
        val work = async { prepare() }
        withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS); server.takeRequest(2, TimeUnit.SECONDS) }
        work.cancelAndJoin(); assertTrue(work.isCancelled); assertNull(repository.spriteUri(media))
        assertEquals(2, server.requestCount)
    }
}
