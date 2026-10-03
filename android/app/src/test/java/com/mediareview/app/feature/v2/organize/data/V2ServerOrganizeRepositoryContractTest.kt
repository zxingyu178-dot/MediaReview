package com.mediareview.app.feature.v2.organize.data

import com.mediareview.app.core.model.DeleteOutcomeStatus
import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.MediaUrlResolver
import com.mediareview.app.feature.v2.data.server.V2MediaMapper
import com.mediareview.app.feature.v2.data.server.V2ServerProfilePort
import com.mediareview.app.feature.v2.data.server.V2ServerResourceCache
import com.mediareview.app.feature.v2.data.server.V2ServerStatusStore
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage 8C §53：整理中心 Server 仓库真实合同测试（MockWebServer + 真实 JSON）。
 *
 * 覆盖：overview 摘要（含部分失败）、待删除列表/恢复（含封面、零 N+1）、
 * prepare/commit（逐项 success/missing/failed/unknown）、重复摘要/分组/详情（**一次请求**）/
 * keep 服务端确认、扫描生命周期（pause/resume/cancel）、媒体库列表与保存、批阅摘要 404 语义。
 *
 * 失败语义（§45）：所有失败必须**上抛异常**，绝不返回空列表/0 冒充成功。
 */
class V2ServerOrganizeRepositoryContractTest {

    private lateinit var server: MockWebServer
    private lateinit var router: OrganizeDispatcher
    private lateinit var repository: V2ServerOrganizeRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        router = OrganizeDispatcher()
        server.dispatcher = router
        server.start()
        val http = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByName("127.0.0.1"))
            })
            .build()
        val baseUrl = "http://mediareview.test:${server.port}"
        repository = V2ServerOrganizeRepository(
            profilePort = object : V2ServerProfilePort {
                override suspend fun baseUrl(): String = baseUrl
            },
            apiFactory = ApiFactory(http, http, Json { ignoreUnknownKeys = true; coerceInputValues = true }),
            mapper = V2MediaMapper(MediaUrlResolver()),
            resources = V2ServerResourceCache(),
            statusStore = onlineServerStatus(),
        )
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ---------- Overview 摘要 ----------

    @Test
    fun `待删除摘要来自summary接口而非列表`() = runTest {
        val summary = repository.deleteSummary()
        assertEquals(12, summary.count)
        assertEquals(1_876_543_210L, summary.totalBytes)
        assertEquals(listOf("/api/v1/delete-queue/summary"), router.paths())
        assertFalse(router.paths().contains("/api/v1/delete-queue"))
    }

    @Test
    fun `待删除摘要失败必须上抛而不是返回0`() = runTest {
        router.deleteSummaryStatus = 500
        val result = runCatching { repository.deleteSummary() }
        assertTrue("摘要失败必须显式失败（§45）", result.isFailure)
    }

    @Test
    fun `单卡失败不影响其他摘要请求`() = runTest {
        router.deleteSummaryStatus = 500
        router.latestStatus = 404
        assertTrue(runCatching { repository.deleteSummary() }.isFailure)
        // 其他摘要不受影响（各自独立请求）
        assertEquals(3, repository.duplicateSummary().exactGroups)
        assertNull(repository.reviewSummary())
        assertEquals(2, repository.librarySummary().selected)
    }

    @Test
    fun `批阅摘要404表示没有进行中的批阅`() = runTest {
        router.latestStatus = 404
        assertNull(repository.reviewSummary())
    }

    @Test
    fun `批阅摘要返回服务端权威进度`() = runTest {
        router.latestStatus = 200
        val summary = repository.reviewSummary()
        assertNotNull(summary)
        assertEquals(37, summary!!.seen)
        assertEquals(100, summary.total)
        assertEquals(63, summary.remaining)
    }

    @Test
    fun `批阅摘要网络失败必须上抛`() = runTest {
        router.latestStatus = 500
        assertTrue(runCatching { repository.reviewSummary() }.isFailure)
    }

    // ---------- 待删除队列 ----------

    @Test
    fun `待删除列表一次请求返回封面不做N加1`() = runTest {
        val entries = repository.loadDeleteQueue()

        assertEquals(2, entries.size)
        val first = entries.first()
        assertEquals("aaa", first.mediaId)
        assertEquals("pending", first.status)
        assertNull("pending 项没有失败原因", first.error)
        // Stage 8C.1 §23：failed 项必须携带服务端失败原因（guard 代码）
        assertEquals("file_size_changed", entries[1].error)
        assertEquals("视频 1", first.media?.name)
        assertTrue(
            "封面必须来自队列响应并解析为绝对地址: ${first.coverUri}",
            first.coverUri.orEmpty().startsWith("http://mediareview.test:") &&
                first.coverUri.orEmpty().contains("/api/v1/media/aaa/thumbnail?v=abc"),
        )
        // 只请求了队列本身,没有任何逐条媒体详情（客户端零 N+1）
        assertEquals(1, router.paths().count { it == "/api/v1/delete-queue" })
        assertFalse(router.paths().any { it.startsWith("/api/v1/media/") })
    }

    @Test
    fun `恢复失败上抛且成功不再请求`() = runTest {
        router.restoreStatus = 409
        assertTrue(runCatching { repository.restoreDeleteItem("aaa") }.isFailure)

        router.restoreStatus = 200
        repository.restoreDeleteItem("aaa")
        assertEquals(2, router.paths().count { it == "/api/v1/delete-queue/aaa" })
    }

    @Test
    fun `prepare返回快照数字且commit逐项解析`() = runTest {
        val prep = repository.prepareDeleteCommit()
        assertEquals("nonce-1", prep.nonce)
        assertEquals(2, prep.count)
        assertEquals(3000L, prep.totalBytes)
        assertEquals(listOf("aaa", "bbb"), prep.mediaIds)

        val outcome = repository.commitDelete(prep.nonce)
        assertEquals(DeleteOutcomeStatus.Success, outcome["aaa"])
        assertEquals(DeleteOutcomeStatus.Missing, outcome["bbb"])
        assertEquals(DeleteOutcomeStatus.Failed, outcome["ccc"])
        assertEquals(DeleteOutcomeStatus.Unknown, outcome["ddd"])
        // commit 请求体必须携带同一 nonce
        assertEquals("""{"nonce":"nonce-1"}""", router.lastBody("/api/v1/delete-queue/commit"))
    }

    // ---------- 重复媒体 ----------

    @Test
    fun `重复摘要解析计数与扫描状态`() = runTest {
        val summary = repository.duplicateSummary()
        assertEquals(3, summary.exactGroups)
        assertEquals(7, summary.similarGroups)
        assertEquals("task-9", summary.scanTaskId)
        assertEquals("succeeded", summary.scanStatus)
        assertEquals(100, summary.scanProgress)
        // Stage 8C.1 §19：失败重扫后仍能拿到"上次成功扫描时间"
        assertEquals("2026-10-02T08:00:00", summary.lastSuccessfulScanAt)
    }

    @Test
    fun `完全与疑似分组分页合同与查询参数`() = runTest {
        val exact = repository.loadDuplicateGroups(DuplicateGroupType.EXACT, page = 2, pageSize = 50)
        assertEquals("exact:1000:60000:2", exact.items.first().groupId)
        assertEquals("exact", exact.items.first().type)
        assertEquals(2, exact.items.first().count)
        assertEquals(120, exact.total)
        assertEquals(2, exact.page)
        assertTrue("还有下一页", exact.hasMore)
        val query = router.lastQuery("/api/v1/duplicates/exact").orEmpty()
        assertTrue("分页参数必须下发: page=2&page_size=50 -> $query", query.contains("page=2"))
        assertTrue("分页参数必须下发: page=2&page_size=50 -> $query", query.contains("page_size=50"))

        val last = repository.loadDuplicateGroups(DuplicateGroupType.EXACT, page = 3, pageSize = 50)
        assertFalse("最后一页 hasMore=false", last.hasMore)

        val similar = repository.loadDuplicateGroups(DuplicateGroupType.SIMILAR, page = 1)
        assertEquals("similar:2000:1", similar.items.first().groupId)
        assertEquals("similar", similar.items.first().type)
        assertEquals(7, similar.total)
    }

    @Test
    fun `分组详情一次请求返回成员摘要`() = runTest {
        val detail = repository.loadDuplicateDetail("exact:1000:60000:1")

        assertEquals(2, detail.members.size)
        val member = detail.members.first()
        assertEquals("aaa", member.media.id)
        assertEquals("A.mp4", member.media.name)
        assertEquals(1000L, member.media.sizeBytes)
        assertEquals(60_000L, member.media.durationMs)
        assertEquals(1920, member.media.naturalWidth)
        assertEquals(1080, member.media.naturalHeight)
        assertFalse(member.keep)
        assertTrue(member.coverUri.contains("/api/v1/media/aaa/thumbnail?v=abc"))
        // Stage 8C.1 §20：扫描后失效成员 -> available=false（UI 显示「文件已不可用」）
        assertTrue(detail.members[0].available)
        assertFalse(detail.members[1].available)

        // 详情必须一次请求拿全，且不得再逐条请求媒体详情（禁止 N+1）
        assertEquals(1, router.paths().count { it == "/api/v1/duplicates/exact:1000:60000:1" })
        assertFalse(router.paths().any { it.startsWith("/api/v1/media/") })
    }

    @Test
    fun `keep只有服务端成功才不抛错`() = runTest {
        repository.setDuplicateKeep("exact:1000:60000:1", "aaa", true)
        assertEquals(
            """{"media_id":"aaa","keep":true}""",
            router.lastBody("/api/v1/duplicates/exact:1000:60000:1/keep"),
        )

        router.keepStatus = 404
        assertTrue(
            runCatching { repository.setDuplicateKeep("exact:1000:60000:1", "aaa", false) }.isFailure,
        )
    }

    @Test
    fun `扫描生命周期走服务端任务接口`() = runTest {
        val started = repository.startDuplicateScan()
        assertEquals("task-9", started.taskId)
        assertEquals("pending", started.status)

        val running = repository.duplicateScanStatus()
        assertEquals("running", running.status)
        assertEquals(62, running.progress)
        assertTrue(running.isActive)

        val paused = repository.pauseDuplicateScan("task-9")
        assertEquals("paused", paused.status)
        val resumed = repository.resumeDuplicateScan("task-9")
        assertEquals("pending", resumed.status)
        val cancelled = repository.cancelDuplicateScan("task-9")
        assertEquals("cancelled", cancelled.status)

        assertEquals(1, router.paths().count { it == "/api/v1/tasks/task-9/pause" })
        assertEquals(1, router.paths().count { it == "/api/v1/tasks/task-9/resume" })
        assertEquals(1, router.paths().count { it == "/api/v1/tasks/task-9/cancel" })
    }

    @Test
    fun `没有扫描任务时taskId为空且不可轮询`() = runTest {
        router.statusHasTask = false
        val state = repository.duplicateScanStatus()
        assertNull(state.taskId)
        assertNull(state.status)
        assertFalse(state.isActive)
    }

    // ---------- 媒体库 ----------

    @Test
    fun `媒体库列表与保存选择`() = runTest {
        val libraries = repository.loadLibraries()
        assertEquals(listOf("电影", "照片", "临时素材"), libraries.map { it.name })
        assertEquals(listOf(true, true, false), libraries.map { it.selected })

        val saved = repository.saveLibrarySelection(listOf("lib-movies", "lib-photos"))
        assertEquals("""{"selected":["lib-movies","lib-photos"]}""", router.lastBody("/api/v1/libraries/selection"))
        assertEquals(2, saved.count { it.selected })
    }

    @Test
    fun `保存媒体库失败必须上抛且不假成功`() = runTest {
        router.selectionStatus = 422
        assertTrue(runCatching { repository.saveLibrarySelection(listOf("lib-movies")) }.isFailure)
    }

    @Test
    fun `媒体库摘要统计已选与总数`() = runTest {
        val summary = repository.librarySummary()
        assertEquals(2, summary.selected)
        assertEquals(3, summary.total)
    }
}

/** 整理中心协议的 MockWebServer 分发器（同时记录请求，供断言请求数与请求体）。 */
private class OrganizeDispatcher : Dispatcher() {

    private val recorded = java.util.Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val bodies = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())
    private val queries = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())

    var deleteSummaryStatus = 200
    var restoreStatus = 200
    var keepStatus = 200
    var selectionStatus = 200
    var latestStatus = 200
    var statusHasTask = true

    fun paths(): List<String> = synchronized(recorded) {
        recorded.mapNotNull { it.requestUrl?.encodedPath }
    }

    fun lastBody(path: String): String? = bodies[path]

    /** 最近一次请求的 query string（如 `page=2&page_size=50`）。 */
    fun lastQuery(path: String): String? = queries[path]

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath.orEmpty()
        if (request.body.size > 0) {
            bodies[path] = request.body.readUtf8()
        }
        queries[path] = request.requestUrl?.query.orEmpty()
        recorded += request
        val method = request.method.orEmpty()
        return when {
            path == "/api/v1/delete-queue/summary" ->
                if (deleteSummaryStatus == 200) {
                    json("""{"success":true,"data":{"count":12,"total_bytes":1876543210}}""")
                } else {
                    error(deleteSummaryStatus)
                }
            path == "/api/v1/delete-queue" && method == "GET" -> json(deleteQueueBody())
            path.startsWith("/api/v1/delete-queue/") && method == "DELETE" ->
                if (restoreStatus == 200) {
                    json("""{"success":true,"data":{"media_id":"aaa","queued":false}}""")
                } else {
                    error(restoreStatus)
                }
            path == "/api/v1/delete-queue/commit/prepare" -> json(
                """{"success":true,"data":{"nonce":"nonce-1","expires_at":"2026-10-01T12:10:00","count":2,"total_bytes":3000,"media_ids":["aaa","bbb"]}}""",
            )
            path == "/api/v1/delete-queue/commit" -> json(
                """{"success":true,"data":{"outcome":{"aaa":"success","bbb":"missing","ccc":"failed","ddd":"weird"}}}""",
            )
            path == "/api/v1/duplicates/summary" -> json(
                """{"success":true,"data":{"exact_groups":3,"similar_groups":7,"scan_task_id":"task-9","scan_status":"succeeded","scan_progress":100,"last_successful_scan_at":"2026-10-02T08:00:00"}}""",
            )
            path == "/api/v1/duplicates/exact" -> json(exactPageBody(request))
            path == "/api/v1/duplicates/similar" -> json(similarPageBody(request))
            path == "/api/v1/duplicates/scan" -> json(taskBody("pending", 0))
            path == "/api/v1/duplicates/status" ->
                if (statusHasTask) json(taskBody("running", 62)) else json("""{"success":true,"data":{"task_id":null}}""")
            path.startsWith("/api/v1/tasks/") -> {
                val status = when {
                    path.endsWith("/pause") -> "paused"
                    path.endsWith("/resume") -> "pending"
                    else -> "cancelled"
                }
                json(taskBody(status, 62))
            }
            path.startsWith("/api/v1/duplicates/") && path.endsWith("/keep") ->
                if (keepStatus == 200) {
                    json("""{"success":true,"data":{"group_id":"g","media_id":"aaa","keep":true}}""")
                } else {
                    error(keepStatus)
                }
            path.startsWith("/api/v1/duplicates/") -> json(duplicateDetailBody())
            path == "/api/v1/libraries" && method == "GET" -> json(librariesBody())
            path == "/api/v1/libraries/selection" ->
                if (selectionStatus == 200) json(selectionBody()) else error(selectionStatus)
            path == "/api/v1/review/sessions/latest" ->
                if (latestStatus == 200) json(reviewBody()) else error(latestStatus)
            else -> json("""{"success":true,"data":{}}""")
        }
    }

    private fun deleteQueueBody(): String = """
        {"success":true,"data":[
          {"media_id":"aaa","status":"pending","size_bytes":2000,"added_at":"2026-09-30T10:00:00",
           "media":{"media_id":"aaa","name":"视频 1","media_type":"video","library_id":"lib-1",
                    "size_bytes":2000,"duration_ms":30000,"width":1920,"height":1080,
                    "cover_url":"/api/v1/media/aaa/thumbnail?v=abc","folder_id":"f1","folder_name":"旅行"}},
          {"media_id":"bbb","status":"failed","size_bytes":1000,"added_at":"2026-09-30T11:00:00",
           "error":"file_size_changed",
           "media":{"media_id":"bbb","name":"视频 2","media_type":"video","library_id":"lib-1",
                    "size_bytes":1000,"cover_url":"/api/v1/media/bbb/thumbnail?v=def"}}
        ]}
    """.trimIndent()

    private fun queryInt(request: RecordedRequest, name: String, fallback: Int): Int =
        request.requestUrl?.queryParameter(name)?.toIntOrNull() ?: fallback

    /** Stage 8C.1 §11：exact 分页（items/total/page/page_size），group_id 带页码便于断言。 */
    private fun exactPageBody(request: RecordedRequest): String {
        val page = queryInt(request, "page", 1)
        val pageSize = queryInt(request, "page_size", 50)
        return """{"success":true,"data":{"items":[{"group_id":"exact:1000:60000:$page","type":"exact","count":2,"size_bytes":1000,"duration_ms":60000,"detail":"byte-identical"}],"total":120,"page":$page,"page_size":$pageSize}}"""
    }

    private fun similarPageBody(request: RecordedRequest): String {
        val page = queryInt(request, "page", 1)
        val pageSize = queryInt(request, "page_size", 50)
        return """{"success":true,"data":{"items":[{"group_id":"similar:2000:$page","type":"similar","count":3,"size_bytes":2000,"detail":"疑似"}],"total":7,"page":$page,"page_size":$pageSize}}"""
    }

    private fun duplicateDetailBody(): String = """
        {"success":true,"data":{"group_id":"exact:1000:60000:1","type":"exact","detail":"byte-identical",
         "count":2,"size_bytes":1000,"duration_ms":60000,
         "members":[
           {"media_id":"aaa","name":"A.mp4","keep":false,"available":true,"size_bytes":1000,"duration_ms":60000,
            "width":1920,"height":1080,"media_type":"video","cover_url":"/api/v1/media/aaa/thumbnail?v=abc"},
           {"media_id":"bbb","name":"B.mp4","keep":true,"available":false,"size_bytes":1000,"duration_ms":60000,
            "width":1920,"height":1080,"media_type":"video","cover_url":"/api/v1/media/bbb/thumbnail?v=def"}
         ]}}
    """.trimIndent()

    private fun taskBody(status: String, progress: Int): String =
        """{"success":true,"data":{"task_id":"task-9","type":"duplicate_scan","status":"$status","progress":$progress,"media_id":null,"error":null}}"""

    private fun librariesBody(): String = """
        {"success":true,"data":[
          {"jellyfin_id":"lib-movies","name":"电影","collection_type":"movies","selected":true,"sort_order":0},
          {"jellyfin_id":"lib-photos","name":"照片","collection_type":"homevideos","selected":true,"sort_order":1},
          {"jellyfin_id":"lib-temp","name":"临时素材","collection_type":null,"selected":false,"sort_order":2}
        ]}
    """.trimIndent()

    private fun selectionBody(): String = """
        {"success":true,"data":[
          {"jellyfin_id":"lib-movies","name":"电影","collection_type":"movies","selected":true,"sort_order":0},
          {"jellyfin_id":"lib-photos","name":"照片","collection_type":"homevideos","selected":true,"sort_order":1},
          {"jellyfin_id":"lib-temp","name":"临时素材","collection_type":null,"selected":false,"sort_order":2}
        ]}
    """.trimIndent()

    private fun reviewBody(): String = """
        {"success":true,"data":{"session_id":"s-1","status":"active","current_index":37,
         "total_count":100,"seen_count":37,"unavailable_count":0,"remaining_count":63,"completed_count":37}}
    """.trimIndent()

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun error(code: Int): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"success":false,"error":{"code":"ERR","message":"boom"}}""")
}