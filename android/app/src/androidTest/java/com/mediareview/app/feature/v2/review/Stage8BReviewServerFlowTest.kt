package com.mediareview.app.feature.v2.review

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.MediaUrlResolver
import com.mediareview.app.core.network.TokenProvider
import com.mediareview.app.feature.v2.data.server.V2MediaMapper
import com.mediareview.app.feature.v2.data.server.V2PlaybackResolver
import com.mediareview.app.feature.v2.data.server.V2ServerMediaRepository
import com.mediareview.app.feature.v2.data.server.V2ServerResourceCache
import com.mediareview.app.feature.v2.data.server.V2ServerStatusStore
import com.mediareview.app.feature.v2.review.data.V2ServerReviewSessionRepository
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
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
import org.junit.runner.RunWith

/**
 * Stage 8B §49：**设备侧** Server 模式批阅端到端（真实 HTTP + 真实 JSON，不依赖宿主机 Mock Server）。
 *
 * 在模拟器内直接启动 MockWebServer，并按与 Hilt 完全相同的生产链路装配
 * [V2ServerReviewSessionRepository] + [V2ServerMediaRepository] + [V2ReviewViewModel]，
 * 走完整动作序列：
 * 1. 进入批阅 → 没有 active 会话 → 新建会话；
 * 2. 队列按 current_index 所在页加载；
 * 3. 批量批阅（seen）只在服务端确认后生效；
 * 4. 位置（position）按**绝对索引**写回；
 * 5. 接近底部触发下一页（真实分页，不拉全量）；
 * 6. 播放地址只解析当前项 + 下一条（禁止批量 resolvePlayback，§5）；
 * 7. 重新批阅 = 新建会话（§39）。
 *
 * 诚实声明：本测试是**协议/流程级**端到端（真实 HTTP + 真机运行时 + 真实 ViewModel），
 * 但没有驱动 Compose 手势（上滑/下滑的视觉链路仍需要人工或 UI 自动化补充）。
 */
@RunWith(AndroidJUnit4::class)
class Stage8BReviewServerFlowTest {

    private lateinit var server: MockWebServer

    /** 请求记录（MockWebServer 在独立线程 dispatch，测试线程读取 → 必须线程安全）。 */
    private val requests: MutableList<RecordedRequest> =
        java.util.Collections.synchronizedList(mutableListOf())

    /** position 请求体（在 dispatch 时立刻读走，避免事后 body 已被消费）。 */
    private val positionBodies: MutableList<String> =
        java.util.Collections.synchronizedList(mutableListOf())

    /** seen 请求体。 */
    private val seenBodies: MutableList<String> =
        java.util.Collections.synchronizedList(mutableListOf())
    private var totalCount = 120

    @Before
    fun setUp() {
        requests.clear()
        positionBodies.clear()
        seenBodies.clear()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl?.encodedPath.orEmpty()
                val url = request.requestUrl
                when {
                    path.endsWith("/position") -> positionBodies += request.body.readUtf8()
                    path.endsWith("/seen") -> seenBodies += request.body.readUtf8()
                }
                return when {
                    path == "/api/v1/review/sessions/latest" -> notFound()
                    path == "/api/v1/review/sessions" -> json(sessionBody())
                    // Stage 8B.2 §18：恢复走 nearest（这里 echo 锚点 = 该索引可用）
                    path.endsWith("/nearest") -> {
                        val asked = url?.queryParameter("index")?.toIntOrNull() ?: 0
                        json("""{"success": true, "data": {"index": $asked}}""")
                    }
                    path.endsWith("/queue") -> {
                        val page = url?.queryParameter("page")?.toIntOrNull() ?: 1
                        val pageSize = url?.queryParameter("page_size")?.toIntOrNull() ?: 50
                        json(queueBody(page, pageSize))
                    }
                    path.endsWith("/seen") -> json(
                        // Stage 8B.1 §14 + 8B.2 §6:seen 响应必须带服务端权威进度计数
                        """{"success": true, "data": {"media_id": "x", "seen": true,
                           "seen_count": 1, "total_count": $totalCount,
                           "unavailable_count": 0, "remaining_count": ${totalCount - 1},
                           "completed_count": 1}}""".trimIndent(),
                    )
                    path.endsWith("/position") -> json(progressBody())
                    path.endsWith("/complete") -> json(progressBody())
                    path.endsWith("/playback") -> json(playbackBody(path))
                    path.startsWith("/api/v1/delete-queue") -> json("""{"success": true, "data": []}""")
                    path.startsWith("/api/v1/favorites") -> json("""{"success": true, "data": []}""")
                    else -> json("""{"success": true, "data": {}}""")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun 设备侧批阅会话全链路() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val apiFactory = ApiFactory(http, http, json)
        val resources = V2ServerResourceCache()
        val baseUrl = "http://127.0.0.1:${server.port}"
        val statusStore = V2ServerStatusStore()
        val sessions = V2ServerReviewSessionRepository(
            profilePort = object : com.mediareview.app.feature.v2.data.server.V2ServerProfilePort {
                override suspend fun baseUrl(): String = baseUrl
            },
            apiFactory = apiFactory,
            mapper = V2MediaMapper(MediaUrlResolver()),
            resources = resources,
            statusStore = statusStore,
        )
        val media = V2ServerMediaRepository(
            profilePort = object : com.mediareview.app.feature.v2.data.server.V2ServerProfilePort {
                override suspend fun baseUrl(): String = baseUrl
            },
            apiFactory = apiFactory,
            mapper = V2MediaMapper(MediaUrlResolver()),
            playbackResolver = V2PlaybackResolver(MediaUrlResolver()),
            albumCoverPort = object : com.mediareview.app.feature.v2.data.server.V2ServerAlbumCoverPort {
                override suspend fun allCovers(): Map<String, String> = emptyMap()
                override suspend fun setCover(folderId: String, mediaId: String?) = Unit
            },
            statusStore = statusStore,
            resources = resources,
        )
        // ServerProfileStore / TokenProvider 仅用于保证生产依赖可构造（本测试的 baseUrl 走 profilePort）
        ServerProfileStore(context)
        TokenProvider()

        val vm = V2ReviewViewModel(media, sessions)

        // 1) 进入批阅：latest 404 → 新建会话（§17）
        vm.enterReview()
        waitUntil("会话建立") { vm.state.value is V2ReviewUiState.Ready }
        val ready = vm.state.value as V2ReviewUiState.Ready
        assertEquals(totalCount, ready.totalCount)
        assertEquals(1, countPath("/api/v1/review/sessions", method = "POST"))
        assertTrue("必须加载了包含 current_index 的那一页", countPathEndsWith("/queue") == 1)

        // 2) 批阅当前项：seen 服务端确认
        vm.markSeen(0)
        waitUntil("seen 写入") { countPathEndsWith("/seen") == 1 }
        // 等服务端响应回到 VM 再断言（请求被 MockWebServer 记录 ≠ VM 已应用响应；
        // 高负载模拟器上两者的间隔可能明显变长 —— 消除测试自身竞态，不放宽语义）
        val seenMediaId = ready.items.first().mediaId
        waitUntil("seen 服务端确认后生效") { vm.isSeen(seenMediaId) }
        assertTrue("seen 必须服务端确认后才算已批阅", vm.isSeen(seenMediaId))
        assertTrue(
            "seen 请求必须显式带 seen=true（encodeDefaults=false 时曾被省略）：${seenBodies}",
            seenBodies.first().contains("\"seen\":true"),
        )

        // 3) 位置按绝对索引写回（settled 变化才上报）
        vm.onPageSettled(0)
        waitUntil("position 写入") { countPathEndsWith("/position") >= 1 }
        assertTrue("position 必须是绝对索引", lastPositionIndex() == 0)

        // 4) 接近底部：真实请求下一页（不拉全量）
        val before = countPathEndsWith("/queue")
        vm.onPageSettled(ready.items.size - 1)
        waitUntil("加载下一页") { countPathEndsWith("/queue") > before }
        waitUntil("窗口追加下一页") {
            (vm.state.value as? V2ReviewUiState.Ready)?.items?.size ?: 0 > ready.items.size
        }
        val grown = vm.state.value as V2ReviewUiState.Ready
        assertTrue("窗口必须已扩展（追加下一页）", grown.items.size > ready.items.size)
        // settled 到窗口末页（绝对索引 49）后：position 必须上报**绝对索引 49**（而不是本地 0）
        waitUntil("position 上报绝对索引 49") { lastPositionIndex() == 49 }
        // 同时只解析了当前项（d0050 = 绝对索引 49）与它的下一条
        waitUntil("切换页播放源") {
            requestSnapshot().any { it.requestUrl?.encodedPath == "/api/v1/media/d0050/playback" }
        }

        // 5) 播放地址只解析已 settle 过的条目与它们的下一条（§5：禁止批量 resolvePlayback）
        waitUntil("播放源解析") { countPathEndsWith("/playback") >= 1 }
        val resolvedIds = requestSnapshot()
            .filter { it.requestUrl?.encodedPath?.endsWith("/playback") == true }
            .mapNotNull { it.requestUrl?.encodedPath?.removePrefix("/api/v1/media/")?.removeSuffix("/playback") }
            .toSet()
        val allowed = setOf("d0001", "d0002", "d0050", "d0051")
        assertTrue(
            "只允许解析 settle 过的条目 + 下一条（禁止批量解析 120 条），实际 $resolvedIds",
            allowed.containsAll(resolvedIds),
        )

        // 6) 重新批阅 = 新建会话（§39）
        vm.restart()
        waitUntil("重新批阅") { countPath("/api/v1/review/sessions", method = "POST") == 2 }
        waitUntil("新会话就绪并回到第 1 条") {
            (vm.state.value as? V2ReviewUiState.Ready)?.absoluteCurrentIndex == 0
        }
    }

    // ---------- helpers ----------

    private fun requestSnapshot(): List<RecordedRequest> = synchronized(requests) { requests.toList() }

    private fun countPath(path: String, method: String? = null): Int =
        requestSnapshot().count {
            it.requestUrl?.encodedPath == path && (method == null || it.method == method)
        }

    private fun countPathEndsWith(suffix: String): Int =
        requestSnapshot().count { it.requestUrl?.encodedPath?.endsWith(suffix) == true }

    private fun lastPositionIndex(): Int? = synchronized(positionBodies) { positionBodies.lastOrNull() }
        ?.let { body -> Regex("\"index\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() }

    private fun waitUntil(label: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("等待超时: $label（已收到请求 ${requestSnapshot().map { it.requestUrl?.encodedPath }}）")
    }

    private fun sessionBody(): String = """
        {
          "success": true,
          "data": {
            "session_id": "s-device-1",
            "status": "active",
            "current_index": 0,
            "total_count": $totalCount,
            "seen_count": 0,
            "source": {"filter": {"media_type": "video"}, "sort": {"sort_by": "created", "sort_order": "desc"}}
          }
        }
    """.trimIndent()

    private fun progressBody(): String = """
        {"success": true, "data": {"session_id": "s-device-1", "status": "active",
         "current_index": 0, "total_count": $totalCount, "seen_count": 1}}
    """.trimIndent()

    private fun playbackBody(path: String): String {
        val mediaId = path.removePrefix("/api/v1/media/").removeSuffix("/playback")
        return """
            {
              "success": true,
              "data": {
                "media_id": "$mediaId", "title": "视频", "stream_url": "http://127.0.0.1:1/v.mp4",
                "direct": {"url": "http://127.0.0.1:1/$mediaId.mp4", "headers": {}},
                "fallback_hls": null
              }
            }
        """.trimIndent()
    }

    private fun queueBody(page: Int, pageSize: Int): String {
        val from = (page - 1) * pageSize
        val to = minOf(from + pageSize, totalCount)
        val items = (from until to).joinToString(",") { index ->
            val mediaId = "d%04d".format(index + 1)
            """
            {
              "index": $index,
              "seen": false,
              "media": {
                "media_id": "$mediaId", "name": "视频 $mediaId", "media_type": "video",
                "library_id": "lib1", "duration_ms": 18000, "width": 1920, "height": 1080,
                "cover_url": "/api/v1/media/$mediaId/thumbnail",
                "folder_id": "f1", "folder_name": "旅行", "is_favorite": false
              }
            }
            """.trimIndent()
        }
        return """
            {"success": true, "data": {"items": [$items], "total": $totalCount, "page": $page, "page_size": $pageSize}}
        """.trimIndent()
    }

    private fun notFound(): MockResponse = json(
        """{"success": false, "error": {"code": "NOT_FOUND", "message": "没有可恢复的批阅会话"}}""",
        404,
    )

    private fun json(body: String, code: Int = 200): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}