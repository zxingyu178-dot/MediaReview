package com.mediareview.app.feature.v2.review.data

import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.MediaUrlResolver
import com.mediareview.app.feature.v2.data.server.V2MediaMapper
import com.mediareview.app.feature.v2.data.server.V2ServerProfilePort
import com.mediareview.app.feature.v2.data.server.V2ServerResourceCache
import com.mediareview.app.feature.v2.data.server.V2ServerStatusStore
import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Server 批阅会话仓库真实合同测试（Stage 8B §17 / §21 / §26 / §48）。
 *
 * 用 MockWebServer 走真实 HTTP + 真实 JSON DTO 解析，覆盖：
 * - 无 active → 404 NOT_FOUND → 新建会话；
 * - 有 active → 恢复，并且 **current_index = 637 时只请求包含 637 的那一页**；
 * - latest 网络失败 → 必须 Failed，**绝不新建会话**（会把用户进度重置）；
 * - seen 成功 / 失败（布尔字段真实解析，不再用 Map<String,String> 假成功）；
 * - position / complete 的真实响应解析；
 * - 队列项绝对索引原样保留 + 封面走共享资源缓存。
 */
class V2ServerReviewSessionRepositoryContractTest {

    private lateinit var server: MockWebServer
    private lateinit var router: ReviewDispatcher
    private lateinit var repository: V2ServerReviewSessionRepository
    private lateinit var resources: V2ServerResourceCache

    @Before
    fun setUp() {
        server = MockWebServer()
        router = ReviewDispatcher()
        server.dispatcher = router
        server.start()
        val http = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByName("127.0.0.1"))
            })
            .build()
        val baseUrl = "http://mediareview.test:${server.port}"
        resources = V2ServerResourceCache()
        repository = V2ServerReviewSessionRepository(
            profilePort = object : V2ServerProfilePort {
                override suspend fun baseUrl(): String = baseUrl
            },
            apiFactory = ApiFactory(http, http, Json { ignoreUnknownKeys = true; coerceInputValues = true }),
            mapper = V2MediaMapper(MediaUrlResolver()),
            resources = resources,
            statusStore = V2ServerStatusStore(),
        )
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ---------- §17 会话进入 ----------

    @Test
    fun `没有active会话时新建会话并加载第一页`() = runTest {
        router.latestStatus = 404
        router.sessionTotal = 1000

        val opened = repository.enterSession()

        val ready = opened as? ReviewSessionOpen.Ready ?: error("期望 Ready，实际 $opened")
        assertEquals(1000, ready.session.totalCount)
        assertEquals(0, ready.session.currentIndex)
        assertEquals(0, ready.window.baseIndex)
        assertEquals(50, ready.window.items.size)
        // 新会话必须由 POST /review/sessions 创建，且只请求了第 1 页
        assertTrue(router.paths().any { it == "/api/v1/review/sessions" })
        assertEquals(listOf(1), router.queuePages())
    }

    @Test
    fun `有active会话时恢复且只有current_index等于637时请求包含637的页`() = runTest {
        router.latestStatus = 200
        router.sessionTotal = 1000
        router.sessionCurrentIndex = 637

        val opened = repository.enterSession()

        val ready = opened as? ReviewSessionOpen.Ready ?: error("期望 Ready，实际 $opened")
        // 637 / 50 = 12（0-based）+ 1 → 第 13 页；baseIndex = 600，本地定位 37
        assertEquals(listOf(13), router.queuePages())
        assertEquals(600, ready.window.baseIndex)
        assertEquals(37, ready.window.localIndexOf(637))
        assertEquals(637, ready.window.items.first { it.absoluteIndex == 637 }.absoluteIndex)
        // 绝不加载 1..637
        assertFalse(router.queuePages().contains(1))
    }

    @Test
    fun `latest网络失败时必须失败且绝不新建会话`() = runTest {
        router.latestStatus = 500
        router.sessionTotal = 1000

        val opened = repository.enterSession()

        assertTrue("latest 失败必须如实上报（实际 $opened）", opened is ReviewSessionOpen.Failed)
        assertTrue(
            "网络失败绝不能自动新建会话（否则会重置用户进度）",
            router.paths().none { it == "/api/v1/review/sessions" },
        )
    }

    @Test
    fun `服务端返回总数为0时进入空队列`() = runTest {
        router.latestStatus = 404
        router.sessionTotal = 0

        assertEquals(ReviewSessionOpen.Empty, repository.enterSession())
    }

    // ---------- §20/§22 分页 ----------

    @Test
    fun `向后分页追加且绝对索引保持`() = runTest {
        router.latestStatus = 200
        router.sessionTotal = 120
        router.sessionCurrentIndex = 0
        repository.enterSession()

        val next = repository.loadNextPage()

        val window = next?.window ?: error("期望加载成功")
        assertEquals(100, window.items.size)
        assertEquals("追加分页不产生前置补偿", 0, next.prependedCount)
        assertEquals(99, window.items.last().absoluteIndex)
        assertEquals(listOf(1, 2), router.queuePages())
    }

    @Test
    fun `向前分页返回前置条数供补偿`() = runTest {
        router.latestStatus = 200
        router.sessionTotal = 120
        router.sessionCurrentIndex = 60
        repository.enterSession()

        val prev = repository.loadPrevPage()

        val window = prev?.window ?: error("期望加载成功")
        assertEquals(50, prev.prependedCount)
        assertEquals(0, window.baseIndex)
        // 前置后窗口 = 50（新页）+ 50（原页）
        assertEquals(100, window.items.size)
        assertEquals(0, window.items.first().absoluteIndex)
    }

    @Test
    fun `已到末尾时不再请求下一页`() = runTest {
        router.latestStatus = 200
        router.sessionTotal = 50
        router.sessionCurrentIndex = 0
        repository.enterSession()
        router.requests.clear()

        assertNull(repository.loadNextPage())
        assertTrue("已到末尾不得再发请求", router.paths().isEmpty())
    }

    // ---------- §26 seen ----------

    @Test
    fun `seen成功返回true且响应为布尔字段`() = runTest {
        router.latestStatus = 200
        router.sessionTotal = 10
        val opened = repository.enterSession()
        assertTrue("会话必须就绪（实际 $opened）", opened is ReviewSessionOpen.Ready)

        assertTrue("markSeen 必须由服务器确认成功", repository.markSeen("m1"))
        assertTrue(router.seenBodies().first().contains("\"seen\":true"))
    }

    @Test
    fun `seen失败返回false不假成功`() = runTest {
        router.latestStatus = 200
        router.sessionTotal = 10
        repository.enterSession()
        router.seenStatus = 500

        assertFalse(repository.markSeen("m1"))
    }

    // ---------- §27/§40 position / complete ----------

    @Test
    fun `position与complete可以解析真实进度响应`() = runTest {
        router.latestStatus = 200
        router.sessionTotal = 10
        repository.enterSession()

        // 不抛异常即说明 DTO 与真实响应（含整数 + 时间字段）一致
        repository.savePosition(3)
        assertTrue(repository.completeSession())
        assertEquals(listOf(3), router.positions())
    }

    // ---------- §24/§38 队列项与共享缓存 ----------

    @Test
    fun `队列项保留绝对索引且封面进入共享资源缓存`() = runTest {
        router.latestStatus = 200
        router.sessionTotal = 1000
        router.sessionCurrentIndex = 637

        val ready = repository.enterSession() as ReviewSessionOpen.Ready
        val first = ready.window.items.first()

        assertEquals(600, first.absoluteIndex)
        assertTrue("封面必须是绝对 URL", first.coverUrl.startsWith("http://mediareview.test:"))
        assertNotNull("批阅队列媒体必须进入共享缓存（完整播放器要能用 mediaById）", resources.media(first.mediaId))
        assertEquals("", first.code) // Server 无编号概念：留空而不是伪造
    }

    /** 按路径路由的 MockWebServer Dispatcher。 */
    private class ReviewDispatcher : Dispatcher() {
        val requests = mutableListOf<RecordedRequest>()
        var latestStatus = 404
        var sessionTotal = 0
        var sessionCurrentIndex = 0
        var seenStatus = 200

        fun paths(): List<String> = requests.map { it.requestUrl?.encodedPath.orEmpty() }

        fun queuePages(): List<Int> = requests
            .filter { it.requestUrl?.encodedPath?.endsWith("/queue") == true }
            .mapNotNull { it.requestUrl?.queryParameter("page")?.toIntOrNull() }

        fun seenBodies(): List<String> = requests
            .filter { it.requestUrl?.encodedPath?.endsWith("/seen") == true }
            .map { it.body.readUtf8() }

        fun positions(): List<Int> = requests
            .filter { it.requestUrl?.encodedPath?.endsWith("/position") == true }
            .mapNotNull { Regex("\"index\"\\s*:\\s*(\\d+)").find(it.body.readUtf8())?.groupValues?.get(1)?.toIntOrNull() }

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val path = request.requestUrl?.encodedPath.orEmpty()
            val url = request.requestUrl
            return when {
                path == "/api/v1/review/sessions/latest" && latestStatus == 404 -> json(
                    """{"success": false, "error": {"code": "NOT_FOUND", "message": "没有可恢复的批阅会话"}}""",
                    404,
                )
                path == "/api/v1/review/sessions/latest" && latestStatus != 200 -> json(
                    """{"success": false, "error": {"code": "INTERNAL_ERROR", "message": "latest 暂时不可用"}}""",
                    latestStatus,
                )
                path == "/api/v1/review/sessions/latest" -> json(sessionBody())
                path == "/api/v1/review/sessions" -> json(sessionBody())
                path.endsWith("/queue") -> {
                    val page = url?.queryParameter("page")?.toIntOrNull() ?: 1
                    val pageSize = url?.queryParameter("page_size")?.toIntOrNull() ?: 50
                    json(queueBody(page, pageSize))
                }
                path.endsWith("/seen") && seenStatus != 200 -> json(
                    """{"success": false, "error": {"code": "INTERNAL_ERROR", "message": "failed"}}""",
                    seenStatus,
                )
                path.endsWith("/seen") -> json(
                    """{"success": true, "data": {"media_id": "m1", "seen": true}}""",
                )
                path.endsWith("/position") -> json(progressBody(sessionCurrentIndex))
                path.endsWith("/complete") -> json(progressBody(sessionTotal))
                else -> json("""{"success": true, "data": {}}""")
            }
        }

        private fun sessionBody(): String = """
            {
              "success": true,
              "data": {
                "session_id": "s-0001",
                "status": "active",
                "current_index": $sessionCurrentIndex,
                "total_count": $sessionTotal,
                "seen_count": 0,
                "created_at": "2026-09-27T10:00:00Z",
                "updated_at": "2026-09-27T10:00:00Z",
                "completed_at": null,
                "source": {"filter": {"media_type": "video"}, "sort": {"sort_by": "created", "sort_order": "desc"}}
              }
            }
        """.trimIndent()

        private fun progressBody(index: Int): String = """
            {
              "success": true,
              "data": {
                "session_id": "s-0001",
                "status": "active",
                "current_index": $index,
                "total_count": $sessionTotal,
                "seen_count": 0,
                "created_at": "2026-09-27T10:00:00Z",
                "updated_at": "2026-09-27T10:00:00Z",
                "completed_at": null
              }
            }
        """.trimIndent()

        private fun queueBody(page: Int, pageSize: Int): String {
            val from = (page - 1) * pageSize
            val to = minOf(from + pageSize, sessionTotal)
            val items = (from until to).joinToString(",") { index ->
                val mediaId = "v%04d".format(index + 1)
                val name = "视频 %04d".format(index + 1)
                """
                {
                  "index": $index,
                  "media": {
                    "media_id": "$mediaId",
                    "name": "$name",
                    "media_type": "video",
                    "library_id": "lib1",
                    "duration_ms": 18000,
                    "width": 1920, "height": 1080,
                    "cover_url": "/api/v1/media/$mediaId/thumbnail",
                    "folder_id": "f1", "folder_name": "旅行",
                    "is_favorite": false
                  }
                }
                """.trimIndent()
            }
            return """
                {
                  "success": true,
                  "data": {
                    "items": [$items],
                    "total": $sessionTotal,
                    "page": $page,
                    "page_size": $pageSize
                  }
                }
            """.trimIndent()
        }

        private fun json(body: String, code: Int = 200): MockResponse = MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }
}