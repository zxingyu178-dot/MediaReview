package com.mediareview.app.feature.v2.review.data

import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.MediaUrlResolver
import com.mediareview.app.feature.v2.data.server.V2MediaMapper
import com.mediareview.app.feature.v2.data.server.V2ServerProfilePort
import com.mediareview.app.feature.v2.data.server.V2ServerResourceCache
import com.mediareview.app.feature.v2.data.server.V2ServerStatusStore
import java.net.InetAddress
import kotlinx.coroutines.async
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
 * Server 批阅会话仓库真实合同测试（Stage 8B §17 / §21 / §26 / §48 + Stage 8B.1 §3~§15）。
 *
 * 用 MockWebServer 走真实 HTTP + 真实 JSON DTO 解析，覆盖：
 * - 无 active → 404 NOT_FOUND → 新建会话；
 * - 有 active → 恢复，并且 **current_index = 637 时只请求包含 637 的那一页**；
 * - latest 网络失败 → 必须 Failed，**绝不新建会话**（会把用户进度重置）；
 * - **双向分页**：先 next 再 prev 不重复请求中间页；先 prev 再 next 结果一致；
 * - **缺项**：绝对索引原样保留，localIndexOf 按真实位置；
 * - **失效 current_index 恢复**：向后找不到时向前回退，并把真实索引写回服务端；
 * - **空页推进**：整页不可用时推进页边界，绝不 return null 死循环；
 * - **atEnd 按页边界**：尾部媒体失效也能结束；
 * - **合并去重 + 分页 single-flight**；
 * - seen 成功 / 失败（含服务端权威 seen_count），队列项 seen 字段解析；
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
        assertEquals(13, ready.window.firstLoadedPage)
        assertEquals(13, ready.window.lastLoadedPage)
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

    // ---------- §7 失效 current_index 恢复 ----------

    @Test
    fun `current_index指向不可用媒体时向后定位到下一个可用项并写回位置`() = runTest {
        router.latestStatus = 200
        router.sessionTotal = 1000
        router.sessionCurrentIndex = 637
        // 第 13 页只有 600..636 可用（637 起全部失效），第 14 页起无可用媒体
        router.pageItems = { page ->
            when (page) {
                13 -> (600..636).toList()
                else -> emptyList()
            }
        }

        val opened = repository.enterSession()

        val ready = opened as? ReviewSessionOpen.Ready ?: error("期望 Ready，实际 $opened")
        assertEquals("恢复出的真实索引必须写回 session", 636, ready.session.currentIndex)
        assertEquals("pager 必须定位到恢复项", 36, ready.window.localIndexOf(636))
        assertTrue("恢复出的真实 absoluteIndex 必须写回服务端 position", router.positions().contains(636))
    }

    @Test
    fun `current_index页面整页不可用时向后跨页定位`() = runTest {
        router.latestStatus = 200
        router.sessionTotal = 1000
        router.sessionCurrentIndex = 637
        // 第 13 页整页失效；第 14 页有可用媒体 700..749
        router.pageItems = { page ->
            when (page) {
                14 -> (700..749).toList()
                else -> emptyList()
            }
        }

        val opened = repository.enterSession()

        val ready = opened as? ReviewSessionOpen.Ready ?: error("期望 Ready，实际 $opened")
        assertEquals(700, ready.session.currentIndex)
        assertEquals(14, ready.window.firstLoadedPage)
        assertEquals(0, ready.window.localIndexOf(700))
        assertEquals("锚点页只请求一次（不重复拉取）", listOf(13, 14), router.queuePages())
        assertTrue(router.positions().contains(700))
    }

    @Test
    fun `current_index之前才有可用媒体时向前回退定位`() = runTest {
        router.latestStatus = 200
        router.sessionTotal = 1000
        router.sessionCurrentIndex = 637
        // 637 之后全部失效；第 12 页有 550..599
        router.pageItems = { page ->
            when (page) {
                13 -> (600..636).toList()
                12 -> (550..599).toList()
                else -> emptyList()
            }
        }

        val opened = repository.enterSession()

        val ready = opened as? ReviewSessionOpen.Ready ?: error("期望 Ready，实际 $opened")
        assertEquals("必须回退到 637 之前最近的可用项", 636, ready.session.currentIndex)
        assertTrue(ready.window.localIndexOf(636) != null)
    }

    // ---------- §4/§5/§10 双向分页 ----------

    @Test
    fun `先next再prev不会重复请求中间页且窗口无重复`() = runTest {
        router.latestStatus = 200
        router.sessionTotal = 1000
        router.sessionCurrentIndex = 637
        repository.enterSession()
        router.requests.clear()

        val next = repository.loadNextPage()?.window ?: error("期望加载成功")
        val prev = repository.loadPrevPage()?.window ?: error("期望加载成功")

        assertEquals("next = lastLoadedPage+1；prev = firstLoadedPage-1", listOf(14, 12), router.queuePages())
        assertEquals(12, prev.firstLoadedPage)
        assertEquals(14, prev.lastLoadedPage)
        val indexes = prev.items.map { it.absoluteIndex }
        assertEquals("窗口必须去重且有序", indexes.distinct(), indexes)
        assertEquals(indexes.sorted(), indexes)
        // 12/13/14 页各 50 条 → 150 条，无重复
        assertEquals(150, indexes.size)
        assertEquals(600, next.baseIndex)
    }

    @Test
    fun `先prev再next结果与反向顺序一致`() = runTest {
        router.latestStatus = 200
        router.sessionTotal = 1000
        router.sessionCurrentIndex = 637

        val first = V2ServerReviewSessionRepository(
            profilePort = baseUrlPort(),
            apiFactory = testApiFactory(),
            mapper = V2MediaMapper(MediaUrlResolver()),
            resources = V2ServerResourceCache(),
            statusStore = V2ServerStatusStore(),
        )
        first.enterSession()
        first.loadPrevPage()
        val windowA = first.loadNextPage()?.window ?: error("期望加载成功")

        router.requests.clear()
        repository.enterSession()
        repository.loadNextPage()
        val windowB = repository.loadPrevPage()?.window ?: error("期望加载成功")

        assertEquals(
            "两种顺序的最终窗口必须一致（12+13+14）",
            windowA.items.map { it.absoluteIndex },
            windowB.items.map { it.absoluteIndex },
        )
        assertEquals(12, windowB.firstLoadedPage)
        assertEquals(14, windowB.lastLoadedPage)
    }

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

    @Test
    fun `队列项带seen状态且恢复后保持`() = runTest {
        router.latestStatus = 200
        router.sessionTotal = 120
        router.sessionCurrentIndex = 0
        router.seenIndexes += setOf(2, 5)

        val ready = repository.enterSession() as ReviewSessionOpen.Ready

        assertFalse(ready.window.items[0].seen)
        assertTrue("服务端 seen=true 的队列项恢复后必须保持", ready.window.items[2].seen)
        assertTrue(ready.window.items[5].seen)
    }

    // ---------- §9 空页推进 ----------

    @Test
    fun `空页必须推进页边界并继续找到可用页`() = runTest {
        router.latestStatus = 200
        router.sessionTotal = 200
        router.sessionCurrentIndex = 0
        // 第 2 页整页不可用；第 3 页有可用媒体
        router.pageItems = { page ->
            when (page) {
                1 -> (0..49).toList()
                3 -> (100..149).toList()
                else -> emptyList()
            }
        }
        repository.enterSession()
        router.requests.clear()

        val result = repository.loadNextPage()

        val window = result?.window ?: error("空页之后必须继续找到可用页（不得 return null）")
        assertEquals("第 2 页（空）与第 3 页都必须被请求", listOf(2, 3), router.queuePages())
        assertEquals(3, window.lastLoadedPage)
        assertEquals(149, window.items.last().absoluteIndex)
        assertFalse("200 条还剩第 4 页，未到末尾", window.atEnd)
    }

    @Test
    fun `尾部整页失效时推进到末尾并返回窗口而非死循环`() = runTest {
        router.latestStatus = 200
        router.sessionTotal = 100
        router.sessionCurrentIndex = 0
        // 只有第 1 页有媒体，第 2 页整页不可用
        router.pageItems = { page -> if (page == 1) (0..49).toList() else emptyList() }
        repository.enterSession()
        router.requests.clear()

        val result = repository.loadNextPage()

        val window = result?.window ?: error("空页推进后必须返回窗口快照（让 atEnd 生效）")
        assertEquals(listOf(2), router.queuePages())
        assertTrue("页边界推进到末尾后必须认为结束（尾部媒体失效也能结束）", window.atEnd)

        router.requests.clear()
        assertNull("再次加载必须是 null 且不再发请求（禁止无限循环）", repository.loadNextPage())
        assertTrue(router.paths().isEmpty())
    }

    // ---------- §10 分页 single-flight ----------

    @Test
    fun `并发next与prev不会破坏窗口（互斥串行）`() = runTest {
        router.latestStatus = 200
        router.sessionTotal = 300
        router.sessionCurrentIndex = 100 // 第 3 页
        repository.enterSession()
        router.requests.clear()

        val nextJob = async { repository.loadNextPage() }
        val prevJob = async { repository.loadPrevPage() }
        val nextResult = nextJob.await()
        val prevResult = prevJob.await()

        assertNotNull("next 必须成功", nextResult)
        assertNotNull("prev 必须成功", prevResult)
        val pages = router.queuePages().sorted()
        assertEquals("并发分页必须各取一个不同页（互斥 + 页边界推进）", listOf(2, 4), pages)

        val finalWindow = repository.loadPrevPage()?.window ?: error("窗口必须仍然可用")
        val indexes = finalWindow.items.map { it.absoluteIndex }
        assertEquals("窗口绝不允许重复条目", indexes.distinct(), indexes)
        assertEquals(indexes.sorted(), indexes)
        assertEquals("最终窗口覆盖第 1..4 页", 1, finalWindow.firstLoadedPage)
        assertEquals(4, finalWindow.lastLoadedPage)
        assertEquals("每个页最多请求一次", router.queuePages().distinct(), router.queuePages())
    }

    // ---------- §14 seen ----------

    @Test
    fun `seen成功返回服务端权威进度`() = runTest {
        router.latestStatus = 200
        router.sessionTotal = 10
        val opened = repository.enterSession()
        assertTrue("会话必须就绪（实际 $opened）", opened is ReviewSessionOpen.Ready)
        router.seenCount = 4
        router.seenTotal = 10

        val result = repository.markSeen("m1")

        assertNotNull("markSeen 必须由服务器确认成功", result)
        assertEquals("m1", result?.mediaId)
        assertTrue(result?.seen == true)
        assertEquals("seenCount 必须直接来自服务端（不得本地 +1）", 4, result?.seenCount)
        assertEquals(10, result?.totalCount)
        assertTrue(router.seenBodies().first().contains("\"seen\":true"))
    }

    @Test
    fun `seen失败返回null不假成功`() = runTest {
        router.latestStatus = 200
        router.sessionTotal = 10
        repository.enterSession()
        router.seenStatus = 500

        assertNull(repository.markSeen("m1"))
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

    @Test
    fun `会话切换后的位置写入打到当前会话而不是旧会话`() = runTest {
        router.latestStatus = 200
        router.sessionTotal = 10
        repository.enterSession()
        router.requests.clear()

        repository.restart()
        repository.savePosition(7)

        val positionPaths = router.requests
            .filter { it.requestUrl?.encodedPath?.endsWith("/position") == true }
            .map { it.requestUrl?.encodedPath.orEmpty() }
        assertEquals(1, positionPaths.size)
        assertTrue(
            "位置必须打到新建会话 s-created-1（绝不污染旧会话 s-0001）",
            positionPaths.single().contains("s-created-1"),
        )
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

    private fun testApiFactory(): ApiFactory {
        val http = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByName("127.0.0.1"))
            })
            .build()
        return ApiFactory(http, http, Json { ignoreUnknownKeys = true; coerceInputValues = true })
    }

    private fun baseUrlPort(): V2ServerProfilePort = object : V2ServerProfilePort {
        override suspend fun baseUrl(): String = "http://mediareview.test:${server.port}"
    }

    /** 按路径路由的 MockWebServer Dispatcher。 */
    private class ReviewDispatcher : Dispatcher() {
        val requests = mutableListOf<RecordedRequest>()
        var latestStatus = 404
        var sessionTotal = 0
        var sessionCurrentIndex = 0
        var seenStatus = 200

        /** markSeen 响应里的服务端权威计数。 */
        var seenCount = 0
        var seenTotal = 0

        /** 队列里 seen=true 的绝对索引（恢复场景）。 */
        val seenIndexes = mutableSetOf<Int>()

        /** 显式指定每页的绝对索引；null = 连续满页（默认）。 */
        var pageItems: ((Int) -> List<Int>)? = null

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
                path == "/api/v1/review/sessions/latest" -> json(sessionBody("s-0001"))
                path == "/api/v1/review/sessions" -> {
                    createCount += 1
                    json(sessionBody("s-created-$createCount"))
                }
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
                    """{"success": true, "data": {"media_id": "m1", "seen": true,
                       "seen_count": $seenCount, "total_count": $seenTotal}}""".trimIndent(),
                )
                path.endsWith("/position") -> json(progressBody(sessionCurrentIndex))
                path.endsWith("/complete") -> json(progressBody(sessionTotal))
                else -> json("""{"success": true, "data": {}}""")
            }
        }

        private var createCount = 0

        private fun sessionBody(sessionId: String): String = """
            {
              "success": true,
              "data": {
                "session_id": "$sessionId",
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
            val indexes = pageItems?.invoke(page)
                ?: (from until minOf(from + pageSize, sessionTotal)).toList()
            val items = indexes.joinToString(",") { index ->
                val mediaId = "v%04d".format(index + 1)
                val name = "视频 %04d".format(index + 1)
                val seen = index in seenIndexes
                """
                {
                  "index": $index,
                  "seen": $seen,
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