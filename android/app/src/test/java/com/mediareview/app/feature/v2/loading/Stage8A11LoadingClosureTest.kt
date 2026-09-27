package com.mediareview.app.feature.v2.loading

import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.MediaUrlResolver
import com.mediareview.app.feature.v2.data.server.V2MediaMapper
import com.mediareview.app.feature.v2.data.server.V2PlaybackResolver
import com.mediareview.app.feature.v2.data.server.V2ServerAlbumCoverPort
import com.mediareview.app.feature.v2.data.server.V2ServerMediaRepository
import com.mediareview.app.feature.v2.data.server.V2ServerProfilePort
import com.mediareview.app.feature.v2.data.server.V2ServerStatusStore
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 阶段 8A.1.1 加载管线收口测试（Android JVM 侧）。
 *
 * 覆盖 §4：
 * - **真实并发** single-flight：同时 async 调用 folders/folders/albums/folders → 只有 1 次 HTTP；
 * - 缓存绑定 server 身份：baseUrl 变化后即使仍在 TTL 内也必须重新请求；
 * - invalidateAuxiliaryCache() 后必须重新请求。
 */
class Stage8A11LoadingClosureTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: V2ServerMediaRepository
    private val baseUrlRef = AtomicReference<String>()
    private val requestPaths: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestPaths += request.path.orEmpty()
                val body = when {
                    request.path.orEmpty().startsWith("/api/v1/media/folders") -> envelope(
                        """
                        [
                          {"folder_id":"f1","name":"海边","count":3,"image_count":2,
                           "cover_media_id":"m2","cover_url":"/api/v1/media/m2/thumbnail?v=abc"}
                        ]
                        """.trimIndent(),
                    )
                    else -> envelope("[]")
                }
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(body)
            }
        }
        server.start()
        baseUrlRef.set("http://mediareview.test:${server.port}")
        val http = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByName("127.0.0.1"))
            })
            .build()
        repository = V2ServerMediaRepository(
            profilePort = object : V2ServerProfilePort {
                override suspend fun baseUrl(): String = baseUrlRef.get()
            },
            apiFactory = ApiFactory(
                http,
                http,
                Json {
                    ignoreUnknownKeys = true
                    coerceInputValues = true
                },
            ),
            mapper = V2MediaMapper(MediaUrlResolver()),
            playbackResolver = V2PlaybackResolver(MediaUrlResolver()),
            albumCoverPort = object : V2ServerAlbumCoverPort {
                override suspend fun allCovers(): Map<String, String> = emptyMap()
                override suspend fun setCover(folderId: String, mediaId: String?) = Unit
            },
            statusStore = V2ServerStatusStore(),
        )
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun envelope(dataJson: String): String =
        """{"success":true,"data":$dataJson,"error":null,"request_id":"t"}"""

    private fun foldersRequests(): Int =
        requestPaths.count { it.startsWith("/api/v1/media/folders") }

    // ---------- 真实并发 single-flight ----------

    @Test
    fun `真实并发 folders albums 混合调用只产生一次请求`() = runTest {
        // 用 Dispatchers.Default 让四个调用真正并行（不是顺序调用后命中缓存）
        withContext(Dispatchers.Default) {
            coroutineScope {
                listOf(
                    async { repository.folders() },
                    async { repository.folders() },
                    async { repository.albums() },
                    async { repository.folders() },
                ).awaitAll()
            }
        }
        assertEquals(
            "并发调用必须被 single-flight 合并，实际路径=$requestPaths",
            1,
            foldersRequests(),
        )
    }

    @Test
    fun `顺序调用同样只产生一次请求`() = runTest {
        repository.folders()
        repository.folders()
        repository.albums()
        repository.folders()
        assertEquals(1, foldersRequests())
    }

    // ---------- 缓存绑定 server 身份 ----------

    @Test
    fun `切换服务器地址后 TTL 内也必须重新请求`() = runTest {
        repository.folders()
        assertEquals(1, foldersRequests())

        // 同一台 MockWebServer，但客户端视角的 baseUrl 变了（等价于切到 Server B）
        baseUrlRef.set("http://another-mediareview.test:${server.port}")
        repository.folders()
        assertEquals(
            "换了服务器绝不能复用旧 folders 缓存，实际路径=$requestPaths",
            2,
            foldersRequests(),
        )
    }

    @Test
    fun `失效辅助缓存后必须重新请求`() = runTest {
        repository.folders()
        assertEquals(1, foldersRequests())

        repository.invalidateAuxiliaryCache()
        repository.folders()
        assertEquals(2, foldersRequests())
    }

    @Test
    fun `同一服务器 TTL 内不会重复请求`() = runTest {
        repository.folders()
        repository.folders()
        repository.folders()
        assertEquals(1, foldersRequests())
        assertTrue(foldersRequests() >= 1)
    }
}