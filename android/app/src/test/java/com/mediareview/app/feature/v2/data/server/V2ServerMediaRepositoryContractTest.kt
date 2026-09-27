package com.mediareview.app.feature.v2.data.server

import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.MediaUrlResolver
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.model.V2MediaQuery
import com.mediareview.app.feature.v2.model.V2SortField
import com.mediareview.app.feature.v2.model.V2SortOrder
import com.mediareview.app.feature.v2.model.V2SortSpec
import com.mediareview.app.feature.v2.model.V2TypeFilter
import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Production Repository 合同测试（Stage 8A §36 / §37）：
 * 真实 HTTP（MockWebServer）验证 Server 数据接入合同，不伪造 PASS。
 *
 * 覆盖：列表映射 / 文件夹映射 / search & sort 参数 / 分页 / 封面与原图 URL 解析 /
 * 收藏成功与失败 / 401 / 网络超时（不可达）/ playback direct + HLS 数据 / headers 真实 HTTP 不串源 /
 * resume position / Server 模式同步播放地址 fail-fast。
 */
class V2ServerMediaRepositoryContractTest {

    private lateinit var server: MockWebServer
    private lateinit var router: RoutingDispatcher
    private lateinit var repository: V2ServerMediaRepository
    private lateinit var statusStore: V2ServerStatusStore
    private lateinit var http: OkHttpClient
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        router = RoutingDispatcher()
        server.dispatcher = router
        server.start()
        // MockWebServer 只监听回环地址：用统一 DNS 把测试域名都指到 127.0.0.1，
        // 这样 MediaUrlResolver 的"本机/回环不安全目的地"策略不会被绕过或误伤。
        http = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByName("127.0.0.1"))
            })
            .build()
        baseUrl = "http://mediareview.test:${server.port}"
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        val apiFactory = ApiFactory(http, http, json)
        statusStore = V2ServerStatusStore()
        repository = V2ServerMediaRepository(
            profilePort = object : V2ServerProfilePort {
                override suspend fun baseUrl(): String = baseUrl
            },
            apiFactory = apiFactory,
            mapper = V2MediaMapper(MediaUrlResolver()),
            playbackResolver = V2PlaybackResolver(MediaUrlResolver()),
            albumCoverPort = object : V2ServerAlbumCoverPort {
                override suspend fun allCovers(): Map<String, String> = emptyMap()
                override suspend fun setCover(folderId: String, mediaId: String?) = Unit
            },
            statusStore = statusStore,
        )
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ---------- 列表 / 分页 / 参数 ----------

    @Test
    fun `媒体列表映射分页与资源URL解析`() = runTest {
        router.mediaBody = mediaPageJson(
            total = 2,
            page = 1,
            pageSize = 50,
            items = """
                {
                  "media_id": "m1", "name": "海滩视频", "media_type": "video", "library_id": "lib1",
                  "duration_ms": 18000, "size_bytes": 1024, "width": 1920, "height": 1080,
                  "created_at": "2026-01-02T03:04:05Z",
                  "cover_url": "/api/v1/media/m1/thumbnail",
                  "folder_id": "f_abc", "folder_name": "海边"
                },
                {
                  "media_id": "m2", "name": "山景照片", "media_type": "image", "library_id": "lib1",
                  "size_bytes": 2048, "width": 4000, "height": 3000,
                  "created_at": "2026-01-03T00:00:00Z",
                  "cover_url": "/api/v1/media/m2/thumbnail",
                  "original_url": "/api/v1/media/m2/original",
                  "folder_id": "f_abc", "folder_name": "海边"
                }
            """.trimIndent(),
        )

        val page = repository.mediaPage(V2MediaQuery(page = 1, pageSize = 50))

        assertEquals(2, page.total)
        assertEquals(1, page.page)
        assertEquals(50, page.pageSize)
        assertFalse(page.hasMore)
        val video = page.items.first { it.id == "m1" }
        assertEquals("海滩视频", video.name)
        assertTrue(video.isVideo)
        assertEquals(18_000L, video.durationMs)
        assertEquals("f_abc", video.folderId)
        assertEquals("海边", video.folderName)
        assertEquals(1920, video.naturalWidth)
        assertTrue("created_at 应解析为毫秒时间", video.dateMillis > 0L)
        // Server 资源只存在于 Repository 资源缓存：Demo 语义字段保持空
        assertEquals("", video.assetPath)
        assertEquals("", video.thumbPath)
        assertNull(video.spritePath)

        val image = page.items.first { it.id == "m2" }
        assertFalse(image.isVideo)
        assertEquals("$baseUrl/api/v1/media/m1/thumbnail", repository.coverUri(video))
        assertEquals("$baseUrl/api/v1/media/m2/original", repository.imageUri(image))
        assertEquals("$baseUrl/api/v1/media/m2/thumbnail", repository.coverUri(image))

        val request = router.requests.first { it.path!!.startsWith("/api/v1/media?") }
        assertEquals("1", request.query("page"))
        assertEquals("50", request.query("page_size"))
        assertEquals("created", request.query("sort_by"))
        assertEquals("desc", request.query("sort_order"))
        assertNull(request.query("search"))
    }

    @Test
    fun `搜索排序与类型筛选参数由服务器执行`() = runTest {
        router.mediaBody = mediaPageJson(total = 0, page = 2, pageSize = 50, items = "")

        repository.mediaPage(
            V2MediaQuery(
                page = 2,
                pageSize = 50,
                folderId = "f_abc",
                search = "海边",
                spec = V2SortSpec(
                    field = V2SortField.NAME,
                    order = V2SortOrder.ASC,
                    typeFilter = V2TypeFilter.VIDEO,
                ),
            ),
        )

        val request = router.requests.first { it.path!!.startsWith("/api/v1/media?") }
        assertEquals("海边", request.query("search"))
        assertEquals("name", request.query("sort_by"))
        assertEquals("asc", request.query("sort_order"))
        assertEquals("video", request.query("media_type"))
        assertEquals("f_abc", request.query("folder_id"))
        assertEquals("2", request.query("page"))
    }

    @Test
    fun `分页 hasMore 依据总数而不是页是否满`() = runTest {
        router.mediaBody = mediaPageJson(total = 120, page = 1, pageSize = 50, items = "")

        val page = repository.mediaPage(V2MediaQuery(page = 1, pageSize = 50))

        assertEquals(120, page.total)
        assertTrue(page.hasMore)
    }

    @Test
    fun `兼容路径 media 使用有界窗口而不是全量`() = runTest {
        router.mediaBody = mediaPageJson(total = 5000, page = 1, pageSize = 200, items = "")

        repository.media()

        val request = router.requests.first { it.path!!.startsWith("/api/v1/media?") }
        assertEquals("200", request.query("page_size"))
    }

    // ---------- 文件夹 / 相册 ----------

    @Test
    fun `文件夹映射包含封面与图片数量`() = runTest {
        router.foldersBody = """
            {
              "success": true,
              "data": [
                {
                  "folder_id": "f_abc", "name": "海边", "count": 12,
                  "cover_media_id": "m2", "cover_url": "/api/v1/media/m2/thumbnail", "image_count": 7
                },
                {
                  "folder_id": "f_def", "name": "视频", "count": 3,
                  "cover_media_id": null, "cover_url": null, "image_count": 0
                }
              ]
            }
        """.trimIndent()

        val folders = repository.folders()

        assertEquals(2, folders.size)
        val sea = folders.first { it.id == "f_abc" }
        assertEquals("海边", sea.name)
        assertEquals(12, sea.count)
        assertEquals(7, sea.imageCount)
        assertEquals(listOf("m2"), sea.coverMediaIds)
        val videosOnly = folders.first { it.id == "f_def" }
        assertEquals(0, videosOnly.imageCount)
        assertTrue(videosOnly.coverMediaIds.isEmpty())

        // 书架：只有含图片的文件夹成为相册，封面来自服务器聚合（App 不额外请求媒体）
        val albums = repository.albums()
        assertEquals(1, albums.size)
        assertEquals("f_abc", albums.first().id)
        assertEquals(7, albums.first().imageCount)
        assertEquals("m2", albums.first().coverImageId)
    }

    // ---------- 收藏 ----------

    @Test
    fun `收藏成功由服务器确认`() = runTest {
        router.favoritesBody = """{"success": true, "data": []}"""

        assertTrue(repository.setFavorite("m1", true))
        val add = router.requests.first { it.path == "/api/v1/favorites/m1" }
        assertEquals("POST", add.method)

        assertTrue(repository.setFavorite("m1", false))
        val remove = router.requests.last { it.path == "/api/v1/favorites/m1" }
        assertEquals("DELETE", remove.method)
    }

    @Test
    fun `收藏与进度接口必须能解析服务端布尔结果`() = runTest {
        // 真实服务端返回 {"favorited": true, "created": true} / {"reported": true}：
        // 客户端 DTO 必须是布尔字段（早期 Map<String,String> 会解析失败 → 收藏静默失效）。
        router.favoritesBody = """{"success": true, "data": []}"""

        assertTrue(repository.setFavorite("m7", true))
        repository.reportProgress("m7", positionMs = 1234L, isPaused = false)

        val progress = router.requests.first { it.path == "/api/v1/media/m7/progress" }
        assertEquals("POST", progress.method)
        assertTrue(progress.body.readUtf8().contains("\"position_ms\":1234"))
    }

    @Test
    fun `收藏失败不得假成功`() = runTest {
        router.failFavoritesMutation = true

        assertFalse(repository.setFavorite("m1", true))
        // 失败后不回写内存状态：再次读取仍为 null（未确认过）
        assertNull(repository.mediaById("m1"))
    }

    @Test
    fun `收藏列表映射为媒体并标记已收藏`() = runTest {
        router.favoritesBody = """
            {
              "success": true,
              "data": [
                {
                  "media_id": "m9",
                  "media": {
                    "media_id": "m9", "name": "收藏视频", "media_type": "video",
                    "cover_url": "/api/v1/media/m9/thumbnail", "folder_id": "f_abc", "folder_name": "海边",
                    "is_favorite": true
                  }
                }
              ]
            }
        """.trimIndent()

        val favorites = repository.favorites()

        assertEquals(1, favorites.size)
        assertTrue(favorites.first().isFavorite)
        assertEquals("$baseUrl/api/v1/media/m9/thumbnail", repository.coverUri(favorites.first()))
    }

    // ---------- 错误映射：401 / 网络不可达 ----------

    @Test
    fun `401 标记认证失效`() = runTest {
        router.forceStatus = 401

        assertThrows(Throwable::class.java) {
            kotlinx.coroutines.runBlocking { repository.mediaPage(V2MediaQuery()) }
        }
        assertEquals(V2ServerStatus.AuthRejected, statusStore.status.value)
    }

    @Test
    fun `网络不可达标记离线`() = runTest {
        server.shutdown()

        assertThrows(Throwable::class.java) {
            kotlinx.coroutines.runBlocking { repository.mediaPage(V2MediaQuery()) }
        }
        assertEquals(V2ServerStatus.Offline, statusStore.status.value)
    }

    @Test
    fun `未配置服务器时直接失败不发请求`() = runTest {
        val unconfigured = V2ServerMediaRepository(
            profilePort = object : V2ServerProfilePort {
                override suspend fun baseUrl(): String = ""
            },
            apiFactory = ApiFactory(http, http, Json { ignoreUnknownKeys = true }),
            mapper = V2MediaMapper(MediaUrlResolver()),
            playbackResolver = V2PlaybackResolver(MediaUrlResolver()),
            albumCoverPort = object : V2ServerAlbumCoverPort {
                override suspend fun allCovers(): Map<String, String> = emptyMap()
                override suspend fun setCover(folderId: String, mediaId: String?) = Unit
            },
            statusStore = statusStore,
        )

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { unconfigured.mediaPage(V2MediaQuery()) }
        }
        assertTrue(router.requests.isEmpty())
    }

    // ---------- 播放：direct / HLS / resume / headers 真实 HTTP ----------

    @Test
    fun `播放源解析包含Direct与HLS和续播位置`() = runTest {
        router.playbackBody = playbackJson(
            mediaId = "m1",
            directUrl = "$baseUrl/api/v1/play/m1/direct",
            directHeaders = mapOf("X-Test-Media" to "A"),
            hlsUrl = "$baseUrl/api/v1/play/m1/hls.m3u8",
            hlsHeaders = mapOf("X-Test-Media" to "A"),
            resumeMs = 42_000,
        )

        val source = repository.resolvePlayback("m1")

        assertEquals("m1", source.mediaId)
        assertEquals("$baseUrl/api/v1/play/m1/direct", source.direct.url)
        assertEquals(mapOf("X-Test-Media" to "A"), source.direct.headers)
        assertEquals("$baseUrl/api/v1/play/m1/hls.m3u8", source.fallbackHls?.url)
        assertEquals(42_000L, source.resumePositionMs)
        assertEquals(18_000L, source.durationMs)
        // 合同：Direct 之后最多一次 HLS，再失败必须进入 Error（禁止无限切换）
        assertEquals(
            com.mediareview.app.feature.v2.model.V2PlaybackStage.HLS_FALLBACK,
            source.nextStageAfterFailure(com.mediareview.app.feature.v2.model.V2PlaybackStage.DIRECT),
        )
        assertNull(
            source.nextStageAfterFailure(com.mediareview.app.feature.v2.model.V2PlaybackStage.HLS_FALLBACK),
        )
    }

    @Test
    fun `播放地址必须由服务器下发direct否则fail-closed`() = runTest {
        router.playbackBody = """
            {
              "success": true,
              "data": {
                "media_id": "m1", "title": "海滩视频", "stream_url": "/api/v1/play/m1/direct",
                "fallback_hls": null, "resume_position_ms": 0
              }
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repository.resolvePlayback("m1") }
        }
    }

    @Test
    fun `切换媒体时headers真实HTTP不串源`() = runTest {
        // A：direct.headers 携带 X-Test-Media: A
        router.playbackBodies["a"] = playbackJson(
            mediaId = "a",
            directUrl = "$baseUrl/api/v1/play/a/direct",
            directHeaders = mapOf("X-Test-Media" to "A"),
        )
        router.playbackBodies["b"] = playbackJson(
            mediaId = "b",
            directUrl = "$baseUrl/api/v1/play/b/direct",
            directHeaders = mapOf("X-Test-Media" to "B"),
        )

        val sourceA = repository.resolvePlayback("a")
        http.newCall(
            Request.Builder()
                .url(sourceA.direct.url)
                .apply { sourceA.direct.headers.forEach { (k, v) -> header(k, v) } }
                .build(),
        ).execute().close()
        val requestA = router.requests.last { it.path == "/api/v1/play/a/direct" }
        assertEquals("A", requestA.getHeader("X-Test-Media"))

        // 切到 B：同一个 OkHttpClient 真实发出请求，headers 必须完全替换
        val sourceB = repository.resolvePlayback("b")
        http.newCall(
            Request.Builder()
                .url(sourceB.direct.url)
                .apply { sourceB.direct.headers.forEach { (k, v) -> header(k, v) } }
                .build(),
        ).execute().close()
        val requestB = router.requests.last { it.path == "/api/v1/play/b/direct" }
        assertEquals("B", requestB.getHeader("X-Test-Media"))
        assertFalse("B 请求不得携带 A 的 header 值", "A" == requestB.getHeader("X-Test-Media"))
    }

    @Test
    fun `Server模式禁止同步播放地址`() {
        assertThrows(IllegalStateException::class.java) { repository.playbackUri("m1") }
        assertThrows(IllegalStateException::class.java) { repository.playbackHeaders("m1") }
    }

    @Test
    fun `仓储模式标记为SERVER`() {
        assertEquals(V2DataMode.SERVER, repository.mode)
    }

    // ---------- helpers ----------

    private fun RecordedRequest.query(name: String): String? = requestUrl?.queryParameter(name)

    private fun mediaPageJson(total: Int, page: Int, pageSize: Int, items: String): String = """
        {
          "success": true,
          "data": {
            "items": [$items],
            "total": $total,
            "page": $page,
            "page_size": $pageSize,
            "sync": {"state": "idle", "stale": false, "processed": $total, "total": $total, "message": ""}
          }
        }
    """.trimIndent()

    private fun playbackJson(
        mediaId: String,
        directUrl: String,
        directHeaders: Map<String, String>,
        hlsUrl: String? = null,
        hlsHeaders: Map<String, String> = emptyMap(),
        resumeMs: Long = 0,
    ): String {
        fun headers(map: Map<String, String>) =
            map.entries.joinToString(",") { "\"${it.key}\": \"${it.value}\"" }
        val hls = if (hlsUrl == null) {
            "null"
        } else {
            """{"url": "$hlsUrl", "headers": {${headers(hlsHeaders)}}}"""
        }
        return """
            {
              "success": true,
              "data": {
                "media_id": "$mediaId",
                "title": "视频 $mediaId",
                "stream_url": "$directUrl",
                "direct": {"url": "$directUrl", "headers": {${headers(directHeaders)}}},
                "fallback_hls": $hls,
                "resume_position_ms": $resumeMs,
                "duration_ms": 18000,
                "width": 1920,
                "height": 1080
              }
            }
        """.trimIndent()
    }

    /** 按路径路由的 MockWebServer Dispatcher：避免依赖请求顺序，便于逐个场景断言。 */
    private class RoutingDispatcher : Dispatcher() {
        val requests = mutableListOf<RecordedRequest>()

        var mediaBody: String = """{"success": true, "data": {"items": [], "total": 0, "page": 1, "page_size": 50, "sync": {"state": "idle", "stale": false, "processed": 0, "total": 0, "message": ""}}}"""
        var foldersBody: String = """{"success": true, "data": []}"""
        var favoritesBody: String = """{"success": true, "data": []}"""
        var playbackBody: String = """{"success": true, "data": {"media_id": "m", "title": "t", "direct": {"url": "", "headers": {}}}}"""
        val playbackBodies = mutableMapOf<String, String>()
        var forceStatus: Int? = null
        var failFavoritesMutation: Boolean = false

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val path = request.path.orEmpty()
            forceStatus?.let { code ->
                return json("""{"success": false, "error": {"code": "e", "message": "forced"}}""", code)
            }
            return when {
                path.startsWith("/api/v1/favorites/") && request.method != "GET" -> {
                    if (failFavoritesMutation) {
                        json("""{"success": false, "error": {"code": "e", "message": "fail"}}""", 500)
                    } else {
                        // 与真实服务端一致：布尔字段（不是字符串 Map）
                        val mediaId = path.removePrefix("/api/v1/favorites/")
                        val favorite = request.method == "POST"
                        json(
                            """{"success": true, "data": {"media_id": "$mediaId", "favorited": $favorite, "created": $favorite}}""",
                        )
                    }
                }
                path.endsWith("/progress") -> json("""{"success": true, "data": {"media_id": "m", "reported": true}}""")
                path.startsWith("/api/v1/favorites") -> json(favoritesBody)
                path.startsWith("/api/v1/media/folders") -> json(foldersBody)
                path.startsWith("/api/v1/media/") && path.endsWith("/playback") -> {
                    val mediaId = path.removePrefix("/api/v1/media/").removeSuffix("/playback")
                    json(playbackBodies[mediaId] ?: playbackBody)
                }
                path.startsWith("/api/v1/play/") -> json("""{"ok": true}""")
                path.startsWith("/api/v1/media") -> json(mediaBody)
                else -> json("""{"success": true, "data": {}}""")
            }
        }

        private fun json(body: String, code: Int = 200): MockResponse = MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }
}