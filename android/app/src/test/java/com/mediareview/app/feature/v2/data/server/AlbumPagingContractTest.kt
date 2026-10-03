package com.mediareview.app.feature.v2.data.server

import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.MediaUrlResolver
import com.mediareview.app.feature.v2.model.V2SortField
import com.mediareview.app.feature.v2.model.V2SortOrder
import com.mediareview.app.feature.v2.model.V2SortSpec
import com.mediareview.app.onlineServerStatus
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 相册分页真实合同测试（Stage 8B §12 / §13 / §47）。
 *
 * 旧实现固定 `page=1 / pageSize=200`：201 张照片的相册会永久丢 1 张，
 * 500 张会丢 300 张。本测试用 MockWebServer 真实按页取回 201 / 500 张，
 * 验证：请求参数合同（folder_id + media_type=image + page/page_size）、
 * 逐页无重复、顺序稳定、能一直翻到最后一页（以 total 为准，不靠"页是否满"）。
 */
class AlbumPagingContractTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: V2ServerMediaRepository

    /** 服务端相册总张数（测试逐个用例改写）。 */
    private var albumTotal = 0

    /** 服务端收到的请求（用于断言 query 合同）。 */
    private val requests = mutableListOf<RecordedRequest>()

    private val albumSpec = V2SortSpec(
        field = V2SortField.RECENT,
        order = V2SortOrder.DESC,
        typeFilter = com.mediareview.app.feature.v2.model.V2TypeFilter.IMAGE,
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val url = request.requestUrl ?: error("无请求 URL")
                val page = url.queryParameter("page")?.toIntOrNull() ?: 1
                val pageSize = url.queryParameter("page_size")?.toIntOrNull() ?: 50
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(pageBody(page, pageSize))
            }
        }
        server.start()
        val http = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByName("127.0.0.1"))
            })
            .build()
        val baseUrl = "http://mediareview.test:${server.port}"
        repository = V2ServerMediaRepository(
            profilePort = object : V2ServerProfilePort {
                override suspend fun baseUrl(): String = baseUrl
            },
            apiFactory = ApiFactory(http, http, Json { ignoreUnknownKeys = true; coerceInputValues = true }),
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

    /** 生成"第 n 张"照片（id 形如 p0001，便于断言顺序与去重）。 */
    private fun pageBody(page: Int, pageSize: Int): String {
        val from = (page - 1) * pageSize
        val to = minOf(from + pageSize, albumTotal)
        val items = (from until to).joinToString(",") { index ->
            val id = "p%04d".format(index + 1)
            val name = "照片 %04d".format(index + 1)
            """
            {
              "media_id": "$id",
              "name": "$name",
              "media_type": "image",
              "library_id": "lib1",
              "cover_url": "/api/v1/media/$id/thumbnail",
              "folder_id": "f_album", "folder_name": "旅行"
            }
            """.trimIndent()
        }
        return """
            {
              "success": true,
              "data": {
                "items": [$items],
                "total": $albumTotal,
                "page": $page,
                "page_size": $pageSize,
                "sync": {"state": "idle"}
              }
            }
        """.trimIndent()
    }

    /** 按分页合同把所有页取完（最多 40 页，避免死循环）。 */
    private suspend fun loadAll(pageSize: Int = 50): List<String> {
        val ids = mutableListOf<String>()
        var page = 1
        var total = -1
        while (page <= 40) {
            val result = repository.albumPage("f_album", page, pageSize, albumSpec)
            total = result.total
            if (result.items.isEmpty()) break
            ids += result.items.map { it.id }
            if (page * pageSize >= total) break
            page += 1
        }
        return ids
    }

    @Test
    fun `相册分页请求合同 folder_id + media_type image + page page_size`() = runTest {
        albumTotal = 5
        requests.clear()

        repository.albumPage("f_album", page = 3, pageSize = 50, spec = albumSpec)

        val url = requests.last().requestUrl ?: error("无请求 URL")
        assertEquals("/api/v1/media", url.encodedPath)
        assertEquals("f_album", url.queryParameter("folder_id"))
        assertEquals("image", url.queryParameter("media_type"))
        assertEquals("3", url.queryParameter("page"))
        assertEquals("50", url.queryParameter("page_size"))
    }

    @Test
    fun `201 张相册可以翻到第 201 张且逐页无重复`() = runTest {
        albumTotal = 201

        val ids = loadAll()

        assertEquals(201, ids.size)
        assertEquals(201, ids.toSet().size) // 无重复
        assertEquals("p0001", ids.first()) // 顺序稳定（服务端顺序原样保留）
        assertEquals("p0201", ids.last()) // 旧的 200 上限会在这里丢最后一张
    }

    @Test
    fun `500 张相册分页完整且顺序稳定`() = runTest {
        albumTotal = 500

        val ids = loadAll()

        assertEquals(500, ids.size)
        assertEquals(500, ids.toSet().size)
        assertEquals((1..500).map { "p%04d".format(it) }, ids)
    }

    @Test
    fun `最后一页不满时仍以 total 判定结束而不是看页大小`() = runTest {
        albumTotal = 120

        val ids = loadAll()

        assertEquals(120, ids.size)
        // 120 = 50 + 50 + 20：第 3 页只有 20 条，但 total 判定让它被完整取回
        assertTrue(ids.last() == "p0120")
    }
}