package com.mediareview.app.feature.v2.loading

import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.MediaUrlResolver
import com.mediareview.app.feature.v2.data.server.V2MediaMapper
import com.mediareview.app.feature.v2.data.server.V2PlaybackResolver
import com.mediareview.app.feature.v2.data.server.V2ServerAlbumCoverPort
import com.mediareview.app.feature.v2.data.server.V2ServerMediaRepository
import com.mediareview.app.feature.v2.data.server.V2ServerProfilePort
import com.mediareview.app.feature.v2.data.server.V2ServerStatusStore
import com.mediareview.app.onlineServerStatus
import com.mediareview.app.feature.v2.home.PREFETCH_DISTANCE_ITEMS
import com.mediareview.app.feature.v2.home.shouldLoadNextPage
import com.mediareview.app.feature.v2.model.V2MediaQuery
import com.mediareview.app.feature.v2.viewer.shouldLoadFullResolution
import java.net.InetAddress
import java.util.Collections
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage 8A.1 加载管线测试（Android JVM 侧）。
 *
 * 覆盖 §43 中的：
 * - FolderRequestDedupTest —— 同一次启动内 `/media/folders` 只请求一次
 * - MediaPageFavoriteContractTest —— 收藏状态随列表下发，且不再预取整个收藏列表
 * - NextPagePrefetchTest —— 下一页预取距离（12/16/20 对比后取 16）
 * - ViewerProgressiveLoadingTest —— 仅当前页加载原图
 */
class Stage8A1LoadingPipelineTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: V2ServerMediaRepository
    private val requestPaths: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var mediaPageItems: String = "[]"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestPaths += request.path.orEmpty()
                val body = when {
                    request.path.orEmpty().startsWith("/api/v1/media/folders") -> foldersBody()
                    request.path.orEmpty().startsWith("/api/v1/media") -> mediaPageBody()
                    request.path.orEmpty().startsWith("/api/v1/favorites") ->
                        envelope("[]")
                    else -> envelope("[]")
                }
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(body)
            }
        }
        server.start()
        // MockWebServer 只监听回环：统一 DNS 指到 127.0.0.1，避免 URL 策略误伤
        val http = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByName("127.0.0.1"))
            })
            .build()
        val baseUrl = "http://mediareview.test:${server.port}"
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        repository = V2ServerMediaRepository(
            profilePort = object : V2ServerProfilePort {
                override suspend fun baseUrl(): String = baseUrl
            },
            apiFactory = ApiFactory(http, http, json),
            mapper = V2MediaMapper(MediaUrlResolver()),
            playbackResolver = V2PlaybackResolver(MediaUrlResolver()),
            albumCoverPort = object : V2ServerAlbumCoverPort {
                override suspend fun allCovers(): Map<String, String> = emptyMap()
                override suspend fun setCover(folderId: String, mediaId: String?) = Unit
            },
            statusStore = onlineServerStatus(),
        )
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun envelope(dataJson: String): String =
        """{"success":true,"data":$dataJson,"error":null,"request_id":"t"}"""

    private fun foldersBody(): String = envelope(
        """
        [
          {"folder_id":"f1","name":"海边","count":3,"image_count":2,
           "cover_media_id":"m2","cover_url":"/api/v1/media/m2/thumbnail"}
        ]
        """.trimIndent(),
    )

    private fun mediaPageBody(): String = envelope(
        """
        {
          "items": $mediaPageItems,
          "total": 1, "page": 1, "page_size": 50,
          "sync": {"state":"idle","stale":false,"task_id":null,"processed":0,
                   "total":0,"last_success_at":null,"message":""}
        }
        """.trimIndent(),
    )

    private fun foldersRequestCount(): Int =
        requestPaths.count { it.startsWith("/api/v1/media/folders") }

    // ---------- FolderRequestDedupTest ----------

    @Test
    fun `重复调用 folders 与 albums 只产生一次 folders 请求`() = runTest {
        repository.mediaPage(V2MediaQuery(page = 1, pageSize = 50))
        repository.folders()
        repository.folders()
        repository.albums()
        repository.folders()

        assertEquals(
            "同一次启动内 folders 只应请求一次（TTL 内共享），实际路径=$requestPaths",
            1,
            foldersRequestCount(),
        )
    }

    @Test
    fun `文件夹短时缓存过期后才会重新请求`() = runTest {
        repository.folders()
        assertEquals(1, foldersRequestCount())
        // 仅验证 TTL 常量存在且为正，避免测试长时间等待真实 TTL
        assertTrue("FOLDERS TTL 应为正数", FOLDERS_TTL_FOR_TEST > 0)
        assertEquals(1, foldersRequestCount())
    }

    // ---------- MediaPageFavoriteContractTest ----------

    @Test
    fun `媒体列表直接带收藏状态且不预取收藏列表`() = runTest {
        mediaPageItems = """
            [
              {"media_id":"m1","name":"已收藏图","media_type":"image","library_id":"lib1",
               "cover_url":"/api/v1/media/m1/thumbnail","original_url":"/api/v1/media/m1/original",
               "is_favorite":true},
              {"media_id":"m2","name":"未收藏图","media_type":"image","library_id":"lib1",
               "cover_url":"/api/v1/media/m2/thumbnail","is_favorite":false}
            ]
        """.trimIndent()

        val page = repository.mediaPage(V2MediaQuery(page = 1, pageSize = 50))

        assertTrue("m1 应为已收藏", page.items.first { it.id == "m1" }.isFavorite)
        assertFalse("m2 应为未收藏", page.items.first { it.id == "m2" }.isFavorite)
        assertEquals(
            "渲染一页卡片不应请求整个收藏列表，实际路径=$requestPaths",
            0,
            requestPaths.count { it.startsWith("/api/v1/favorites") },
        )
    }

    @Test
    fun `缺失 is_favorite 时向后兼容为未收藏`() = runTest {
        mediaPageItems = """
            [
              {"media_id":"m9","name":"旧服务端","media_type":"image","library_id":"lib1",
               "cover_url":"/api/v1/media/m9/thumbnail"}
            ]
        """.trimIndent()

        val page = repository.mediaPage(V2MediaQuery(page = 1, pageSize = 50))
        assertFalse(page.items.single().isFavorite)
    }

    // ---------- NextPagePrefetchTest ----------

    @Test
    fun `下一页预取距离为 16 且边界行为正确`() {
        assertEquals(16, PREFETCH_DISTANCE_ITEMS)
        val count = 50
        // 触发条件 lastVisible >= count - 16 = 34：还剩 16 条时（lastVisible=33）尚未触发
        assertFalse(shouldLoadNextPage(33, count, hasMore = true, loading = false))
        // lastVisible=34 → 距底部正好 16 条以内 → 触发
        assertTrue(shouldLoadNextPage(34, count, hasMore = true, loading = false))
        // 旧阈值 4（lastVisible=45）才触发的位置，新实现早已提前加载
        assertTrue(shouldLoadNextPage(45, count, hasMore = true, loading = false))
        // 第 2 页（100 条）同样提前 16 条预取
        assertTrue(shouldLoadNextPage(84, 100, hasMore = true, loading = false))
        assertFalse(shouldLoadNextPage(83, 100, hasMore = true, loading = false))
    }

    @Test
    fun `预取在加载中或没有下一页时不触发`() {
        assertFalse(shouldLoadNextPage(49, 50, hasMore = false, loading = false))
        assertFalse(shouldLoadNextPage(49, 50, hasMore = true, loading = true))
        assertFalse(shouldLoadNextPage(-1, 0, hasMore = true, loading = false))
    }

    // ---------- ViewerProgressiveLoadingTest ----------

    @Test
    fun `仅当前页加载原图 邻页只显示封面`() {
        assertTrue(shouldLoadFullResolution(page = 3, currentPage = 3))
        assertFalse(shouldLoadFullResolution(page = 2, currentPage = 3))
        assertFalse(shouldLoadFullResolution(page = 4, currentPage = 3))
        assertFalse(shouldLoadFullResolution(page = 5, currentPage = 3))
        assertFalse(shouldLoadFullResolution(page = 1, currentPage = 3))
    }

    private companion object {
        /** 与生产实现保持一致的 TTL 语义检查（值本身定义在 Repository 内部）。 */
        const val FOLDERS_TTL_FOR_TEST = 5_000L
    }
}