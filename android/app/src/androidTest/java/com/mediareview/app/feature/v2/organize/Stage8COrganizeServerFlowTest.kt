package com.mediareview.app.feature.v2.organize

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.MediaUrlResolver
import com.mediareview.app.feature.v2.data.server.V2MediaMapper
import com.mediareview.app.feature.v2.data.server.V2ServerProfilePort
import com.mediareview.app.feature.v2.data.server.V2ServerResourceCache
import com.mediareview.app.feature.v2.data.server.V2ServerStatusStore
import com.mediareview.app.feature.v2.organize.data.V2ServerOrganizeRepository
import com.mediareview.app.feature.v2.organize.delete.DeleteQueueViewModel
import com.mediareview.app.feature.v2.organize.duplicates.DuplicateCompareViewModel
import com.mediareview.app.feature.v2.organize.duplicates.DuplicatesViewModel
import com.mediareview.app.feature.v2.organize.libraries.LibraryManagerViewModel
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
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
import org.junit.runner.RunWith

/**
 * Stage 8C §55：**设备侧**整理中心流程级端到端（真实 HTTP + 真实 JSON + 真实 ViewModel）。
 *
 * 与 [Stage8COrganizeUiTest] 互补：本测试驱动 ViewModel 流程（扫描生命周期 / 对比 Keep /
 * 媒体库勾选保存 / 待删除恢复），UI 手势与导航由 UI 测试覆盖。
 *
 * 诚实声明：本测试是协议/流程级端到端，不驱动 Compose 手势。
 */
@RunWith(AndroidJUnit4::class)
class Stage8COrganizeServerFlowTest {

    private lateinit var server: MockWebServer
    private lateinit var router: OrganizeFlowDispatcher
    private lateinit var repository: V2ServerOrganizeRepository

    private val requests: MutableList<RecordedRequest> =
        Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        router = OrganizeFlowDispatcher()
        server = MockWebServer()
        server.dispatcher = router
        server.start()
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val baseUrl = "http://127.0.0.1:${server.port}"
        repository = V2ServerOrganizeRepository(
            profilePort = object : V2ServerProfilePort {
                override suspend fun baseUrl(): String = baseUrl
            },
            apiFactory = ApiFactory(http, http, json),
            mapper = V2MediaMapper(MediaUrlResolver()),
            resources = V2ServerResourceCache(),
            statusStore = V2ServerStatusStore(),
        )
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun 重复扫描生命周期与成功后自动重载() {
        val vm = DuplicatesViewModel(repository)
        vm.enterScreen()
        waitUntil("初始状态恢复") { !vm.ui.value.loading }
        assertEquals("succeeded", vm.ui.value.scan.status)
        assertEquals(1, vm.ui.value.exact.size)

        // 重新扫描：running → succeeded（真实轮询，1.5s 间隔，成功后自动重载分组）
        router.scanStatusSequence += listOf("running", "succeeded")
        vm.startScan()
        waitUntil("扫描完成并自动重载", timeoutMs = 20_000) {
            vm.ui.value.scan.status == "succeeded" &&
                router.paths().count { it == "/api/v1/duplicates/exact" } >= 2
        }

        // 暂停 / 继续 / 取消都走服务端任务接口（状态取自任务接口响应）
        vm.pauseScan()
        waitUntil("暂停完成") { vm.ui.value.scan.status == "paused" }
        assertTrue("暂停必须走服务端", router.paths().any { it.endsWith("/pause") })

        router.scanStatusSequence += listOf("running", "running")
        vm.resumeScan()
        waitUntil("继续完成") { vm.ui.value.scan.status == "pending" || vm.ui.value.scan.status == "running" }
        assertTrue("继续必须走服务端", router.paths().any { it.endsWith("/resume") })

        vm.cancelScan()
        waitUntil("取消完成") { vm.ui.value.scan.status == "cancelled" }
        assertTrue("取消必须走服务端", router.paths().any { it.endsWith("/cancel") })

        vm.leaveScreen()
    }

    @Test
    fun 打开重复对比一次请求且Keep服务端确认() {
        val vm = DuplicateCompareViewModel(repository)
        vm.load("exact:1000:60000:1")
        waitUntil("详情加载") { !vm.ui.value.loading }

        val detail = vm.ui.value.detail ?: error("详情为空")
        assertEquals(2, detail.members.size)
        // 一次请求拿全组成员（禁止 N+1：不得出现逐条媒体详情请求）
        assertEquals(1, router.paths().count { it == "/api/v1/duplicates/exact:1000:60000:1" })
        assertFalse(router.paths().any { it.startsWith("/api/v1/media/") })

        vm.setKeep("exact:1000:60000:1", "aaa", true)
        waitUntil("Keep 服务端确认") {
            vm.ui.value.detail?.members?.first { it.media.id == "aaa" }?.keep == true
        }
        assertTrue(router.paths().any { it.endsWith("/keep") })

        // 服务端失败：UI 不得改变
        router.keepStatus = 409
        vm.setKeep("exact:1000:60000:1", "bbb", true)
        waitUntil("失败处理完成") { !vm.ui.value.keepBusy }
        assertFalse(
            "Keep 失败不得改变 UI",
            vm.ui.value.detail?.members?.first { it.media.id == "bbb" }?.keep == true,
        )
    }

    @Test
    fun 媒体库勾选保存与失败保留草稿() {
        val vm = LibraryManagerViewModel(repository)
        vm.load()
        waitUntil("媒体库加载") { !vm.ui.value.loading }
        assertEquals(setOf("lib-movies", "lib-photos"), vm.ui.value.draftSelected)

        // 本地勾选不触发请求
        val before = router.paths().count { it == "/api/v1/libraries/selection" }
        vm.toggle("lib-temp")
        Thread.sleep(200)
        assertEquals(before, router.paths().count { it == "/api/v1/libraries/selection" })

        // 保存失败：保留草稿（等 PUT 真的发出且 VM 处理完响应，避免"请求未发出就断言"的竞态）
        router.selectionStatus = 422
        vm.apply()
        waitUntil("失败请求已发出并处理完成") {
            router.paths().count { it == "/api/v1/libraries/selection" } >= 1 &&
                !vm.ui.value.saving
        }
        assertTrue(vm.ui.value.draftSelected.contains("lib-temp"))
        assertTrue("保存失败必须保持页面与草稿", vm.ui.value.dirty)

        // 保存成功：以服务端返回为正式状态（dirty 为 true 时 apply 必然真正发出请求）
        router.selectionStatus = 200
        vm.apply()
        waitUntil("保存成功") { !vm.ui.value.dirty && !vm.ui.value.saving }
        assertTrue(router.paths().count { it == "/api/v1/libraries/selection" } >= 2)
    }

    @Test
    fun 待删除恢复只有服务端确认后才刷新() {
        val vm = DeleteQueueViewModel(repository)
        vm.load()
        waitUntil("队列加载") { !vm.ui.value.loading }
        assertEquals(2, vm.ui.value.entries.size)

        // 恢复失败：列表保持原样
        router.restoreStatus = 409
        vm.restore("aaa")
        waitUntil("恢复失败处理") { router.paths().count { it == "/api/v1/delete-queue/aaa" } >= 1 }
        Thread.sleep(200)
        assertEquals(2, vm.ui.value.entries.size)

        // 恢复成功：服务端确认后列表刷新
        router.restoreStatus = 200
        router.restoredIds += "aaa"
        vm.restore("aaa")
        waitUntil("恢复成功刷新") { vm.ui.value.entries.size == 1 }
    }

    private fun waitUntil(label: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError(
            "等待超时: $label（请求：${router.paths().takeLast(15)}）",
        )
    }
}

/** 设备侧 Mock Server：整理中心协议 + 扫描状态迁移（序列驱动，确定性）。 */
private class OrganizeFlowDispatcher : Dispatcher() {

    private val requests = Collections.synchronizedList(mutableListOf<String>())

    /** GET /duplicates/status 的状态序列（消费式）；耗尽后返回 succeeded。 */
    val scanStatusSequence = Collections.synchronizedList(mutableListOf<String>())

    @Volatile
    var keepStatus = 200

    @Volatile
    var restoreStatus = 200

    @Volatile
    var selectionStatus = 200

    val restoredIds = Collections.synchronizedSet(mutableSetOf<String>())

    fun paths(): List<String> = synchronized(requests) { requests.toList() }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath.orEmpty()
        val method = request.method.orEmpty()
        requests += path
        return when {
            path == "/api/v1/duplicates/status" -> json(taskBody(scanCurrentStatus()))
            path == "/api/v1/duplicates/scan" -> json(taskBody("pending"))
            path.startsWith("/api/v1/tasks/") -> {
                val status = when {
                    path.endsWith("/pause") -> "paused"
                    path.endsWith("/resume") -> "pending"
                    else -> "cancelled"
                }
                json(taskBody(status))
            }
            path == "/api/v1/duplicates/exact" -> json(
                """{"success":true,"data":[{"group_id":"exact:1000:60000:1","type":"exact","count":2,"size_bytes":1000,"detail":"byte-identical"}]}""",
            )
            path == "/api/v1/duplicates/similar" -> json("""{"success":true,"data":[]}""")
            path.startsWith("/api/v1/duplicates/") && path.endsWith("/keep") ->
                if (keepStatus == 200) {
                    json("""{"success":true,"data":{"group_id":"g","media_id":"aaa","keep":true}}""")
                } else {
                    error(keepStatus)
                }
            path.startsWith("/api/v1/duplicates/") -> json(
                """{"success":true,"data":{"group_id":"exact:1000:60000:1","type":"exact","detail":"byte-identical",
                 "count":2,"size_bytes":1000,"duration_ms":60000,
                 "members":[
                   {"media_id":"aaa","name":"A.mp4","keep":false,"size_bytes":1000,"duration_ms":60000,"width":1920,"height":1080,"media_type":"video","cover_url":"/api/v1/media/aaa/thumbnail?v=abc"},
                   {"media_id":"bbb","name":"B.mp4","keep":false,"size_bytes":1000,"duration_ms":60000,"width":1920,"height":1080,"media_type":"video","cover_url":"/api/v1/media/bbb/thumbnail?v=abc"}
                 ]}}""",
            )
            path == "/api/v1/delete-queue" && method == "GET" -> json(deleteQueueBody())
            path.startsWith("/api/v1/delete-queue/") && method == "DELETE" ->
                if (restoreStatus == 200) {
                    json("""{"success":true,"data":{"media_id":"aaa","queued":false}}""")
                } else {
                    error(restoreStatus)
                }
            path == "/api/v1/libraries" && method == "GET" -> json(
                """{"success":true,"data":[
                   {"jellyfin_id":"lib-movies","name":"电影","collection_type":"movies","selected":true,"sort_order":0},
                   {"jellyfin_id":"lib-photos","name":"照片","collection_type":"homevideos","selected":true,"sort_order":1},
                   {"jellyfin_id":"lib-temp","name":"临时素材","collection_type":null,"selected":false,"sort_order":2}]}""",
            )
            path == "/api/v1/libraries/selection" ->
                if (selectionStatus == 200) {
                    json(
                        """{"success":true,"data":[
                           {"jellyfin_id":"lib-movies","name":"电影","collection_type":"movies","selected":true,"sort_order":0},
                           {"jellyfin_id":"lib-photos","name":"照片","collection_type":"homevideos","selected":true,"sort_order":1},
                           {"jellyfin_id":"lib-temp","name":"临时素材","collection_type":null,"selected":true,"sort_order":2}]}""",
                    )
                } else {
                    error(selectionStatus)
                }
            else -> json("""{"success":true,"data":{}}""")
        }
    }

    /** 扫描轮询推进：按序列消费；耗尽后返回 succeeded（轮询随即结束）。 */
    private fun scanCurrentStatus(): String =
        if (scanStatusSequence.isEmpty()) "succeeded" else scanStatusSequence.removeAt(0)

    private fun taskBody(status: String): String =
        """{"success":true,"data":{"task_id":"t1","type":"duplicate_scan","status":"$status","progress":60,"error":null}}"""

    private fun deleteQueueBody(): String {
        val remaining = listOf("aaa", "bbb").filter { it !in restoredIds }
        val rows = remaining.joinToString(",") { id ->
            """{"media_id":"$id","status":"pending","size_bytes":1000,"added_at":"2026-09-30T10:00:00",
                "media":{"media_id":"$id","name":"视频 $id","media_type":"video","library_id":"lib-1",
                         "size_bytes":1000,"cover_url":"/api/v1/media/$id/thumbnail?v=abc"}}"""
        }
        return """{"success":true,"data":[$rows]}"""
    }

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun error(code: Int): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"success":false,"error":{"code":"ERR","message":"boom"}}""")
}