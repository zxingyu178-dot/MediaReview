package com.mediareview.app.feature.v2.organize

import android.content.Context
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.graphics.toPixelMap
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.BuildConfig
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.OrganizeTestEntryPoint
import com.mediareview.app.feature.v2.releasenotes.ReleaseNotesStore
import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.feature.v2.V2MainScreen
import com.mediareview.app.feature.v2.data.AlbumCoverStore
import com.mediareview.app.feature.v2.data.DemoMediaRepository
import com.mediareview.app.feature.v2.data.SearchHistoryStore
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.testHomeViewModel
import com.mediareview.app.ui.theme.MediaReviewTheme
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Stage 8C §55/§64：整理中心 **UI 导航 + 真实 Server 数据**（设备内 MockWebServer）。
 *
 * - 整理页四张卡必须显示来自真实接口的数字（无任何 Mock 字样）；
 * - 整理 → 待删除 → 返回；整理 → 重复媒体 → 返回；整理 → 媒体库 → 返回；
 * - 详情页隐藏底部导航（§46）；
 * - 待删除：两步最终删除（prepare → 确认 Sheet → commit）+ 结果 Sheet 明确列出
 *   success / missing；恢复单项服务端确认制。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Stage8COrganizeUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<HiltTestActivity>()

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var router: OrganizeUiDispatcher
    private val evidence by lazy {
        File(context.getExternalFilesDir(null), "stage8f-organize-ui/run-${SystemClock.uptimeMillis()}").apply { mkdirs() }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Stage 8C.1：渲染 V2MainScreen 的测试先标记"当前版本已看过"，
        // 避免首启「本次更新」Sheet 叠加在整理页之上导致点击顺序相关（更新日志另有专门用例）。
        runBlocking {
            ReleaseNotesStore(context).saveLastSeenVersionCode(BuildConfig.VERSION_CODE)
        }
        router = OrganizeUiDispatcher()
        server = MockWebServer()
        server.dispatcher = router
        server.start()
        runBlocking {
            // 服务器地址与数据源模式都指向设备内 Mock Server（真实 HTTP + 真实 JSON）
            ServerProfileStore(context).saveBaseUrl("http://127.0.0.1:${server.port}")
            entryPoint().dataModeStore().set(V2DataMode.SERVER)
        }
        // 流程 UI fixture 显式装配模拟兼容 Server；不是实际 LAN 健康验收。
        // 整理子页使用 Hilt 仓库，手工 Home VM 的局部状态不会初始化 Hilt Guard。
        entryPoint().serverStatusStore().recordCompatible("1.2.2", 2)
    }

    @After
    fun tearDown() {
        // 先取消手工 Home VM 和 Hilt 子页 VM，再清测试持久化/单例状态，避免迟到请求。
        compose.runOnUiThread { compose.activity.viewModelStore.clear() }
        val requests = synchronized(router.paths) { router.paths.toList() }
        val diagnostic = "Hilt status=${entryPoint().serverStatusStore().status.value} " +
            "requestCount=${server.requestCount} summaryRequests=${requests.count { it == "/api/v1/delete-queue/summary" }} paths=$requests"
        Log.i("Stage8FOrganizeUi", diagnostic)
        File(evidence, "finish-status.txt").writeText(diagnostic)
        runBlocking {
            runCatching { ServerProfileStore(context).clear() }
            runCatching { entryPoint().dataModeStore().set(V2DataMode.DEMO) }
        }
        entryPoint().serverStatusStore().reset()
        runCatching { server.shutdown() }
    }

    private fun entryPoint(): OrganizeTestEntryPoint =
        EntryPointAccessors.fromApplication(context, OrganizeTestEntryPoint::class.java)

    private fun setContent() {
        val repository = DemoMediaRepository(context, AlbumCoverStore(context))
        val vm = testHomeViewModel(repository, SearchHistoryStore(context))
        compose.runOnUiThread { compose.activity.viewModelStore.put("stage8c-organize-home", vm) }
        compose.setContent {
            MediaReviewTheme {
                V2MainScreen(vm = vm)
            }
        }
    }

    private fun openOrganizeTab() {
        compose.onNode(hasContentDescription("整理")).performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("organize_page"), timeoutMillis = 15_000)
        // 卡片在 Loading 状态下不可点击：必须先等到真实数据就绪（确定性的点击时机）
        try {
            compose.waitUntilExactlyOneExists(hasText("2 项 · 预计释放 2.8 GB"), timeoutMillis = 15_000)
        } catch (failure: Throwable) {
            val status = entryPoint().serverStatusStore()
            val requests = List(server.requestCount) { server.takeRequest(100, TimeUnit.MILLISECONDS)?.path }
            val diagnostic = "Hilt status=${status.status.value} version=${status.serverVersion.value} " +
                "contract=${status.serverApiContract.value} requestCount=${server.requestCount} paths=$requests"
            Log.e("Stage8FOrganizeUi", diagnostic)
            File(evidence, "failure-status.txt").writeText(diagnostic)
            File(evidence, "failure-semantics.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
            throw failure
        }
    }

    /** 等到"重复媒体"卡 Ready（避免在 Loading 态点击无效）。 */
    private fun waitDuplicateCardReady() {
        compose.waitUntilExactlyOneExists(
            hasText("完全重复 3 组 · 疑似重复 7 组"),
            timeoutMillis = 15_000,
        )
    }

    /** 等到"媒体库管理"卡 Ready。 */
    private fun waitLibraryCardReady() {
        compose.waitUntilExactlyOneExists(hasText("已选 2 / 共 3 个媒体库"), timeoutMillis = 15_000)
    }

    // ---------- 整理首页：真实数据 ----------

    @Test
    fun organizeOverviewShowsRealServerNumbers() {
        setContent()
        openOrganizeTab()

        compose.onNodeWithText("数据源").assertIsDisplayed()
        compose.waitUntilExactlyOneExists(hasText("2 项 · 预计释放 2.8 GB"), timeoutMillis = 15_000)
        compose.onNodeWithText("完全重复 3 组 · 疑似重复 7 组").assertIsDisplayed()
        compose.onNodeWithText("暂无进行中的批阅").assertIsDisplayed()
        compose.onNodeWithText("已选 2 / 共 3 个媒体库").assertIsDisplayed()
        // §64：整理页不允许再出现任何 Mock 数字
        assertTrue(
            "整理页不允许出现 Mock 字样",
            compose.onAllNodes(hasText("Mock", substring = true)).fetchSemanticsNodes().isEmpty(),
        )
    }

    // ---------- 导航：整理 → 子页 → 返回 ----------

    @Test
    fun organizeToDeleteQueueAndBack() {
        setContent()
        openOrganizeTab()

        compose.onNodeWithText("待删除").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("delete_queue_screen"), timeoutMillis = 15_000)
        compose.waitUntilExactlyOneExists(hasText("视频 1"), timeoutMillis = 15_000)
        // §46：详情页隐藏底部导航
        assertTrue(
            "详情页不应显示底部导航",
            compose.onAllNodes(hasContentDescription("首页")).fetchSemanticsNodes().isEmpty(),
        )

        Espresso.pressBack()
        compose.waitUntilExactlyOneExists(hasTestTag("organize_page"), timeoutMillis = 15_000)
        compose.onNode(hasContentDescription("首页")).assertExists()
    }

    @Test
    fun organizeToDuplicatesAndBack() {
        setContent()
        openOrganizeTab()
        waitDuplicateCardReady()

        compose.onNodeWithText("重复媒体").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("duplicates_screen"), timeoutMillis = 15_000)
        compose.waitUntilExactlyOneExists(hasText("完全重复（1 组）"), timeoutMillis = 15_000)
        compose.onNodeWithText("疑似重复（1 组）").assertIsDisplayed()

        Espresso.pressBack()
        compose.waitUntilExactlyOneExists(hasTestTag("organize_page"), timeoutMillis = 15_000)
    }

    @Test
    fun organizeToLibrariesAndBack() {
        setContent()
        openOrganizeTab()
        waitLibraryCardReady()

        compose.onNodeWithText("媒体库管理").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("library_manager_screen"), timeoutMillis = 15_000)
        compose.waitUntilExactlyOneExists(hasText("电影"), timeoutMillis = 15_000)
        compose.onNodeWithText("临时素材").assertIsDisplayed()

        Espresso.pressBack()
        compose.waitUntilExactlyOneExists(hasTestTag("organize_page"), timeoutMillis = 15_000)
    }

    @Test
    fun diskReceiptShowsActualBytesAndRetainsFailedItemAfterRefresh() {
        router.receiptMode = 1
        setContent()
        openOrganizeTab()
        compose.onNodeWithText("待删除").performClick()
        compose.waitUntilExactlyOneExists(hasText("最终删除（2 项）"), 15_000)
        compose.onNodeWithText("最终删除（2 项）").performClick()
        compose.waitUntilExactlyOneExists(hasText("确认永久删除？"), 15_000)
        compose.onNodeWithText("永久删除").performClick()
        compose.waitUntilExactlyOneExists(hasText("已从磁盘删除：1 个文件"), 15_000)
        compose.onNodeWithText("实际释放：4 KB").assertIsDisplayed()
        compose.onNodeWithText("文件原本已不存在：0").assertIsDisplayed()
        compose.onNodeWithText("删除失败：1").assertIsDisplayed()
        compose.onNode(hasText("视频 2") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText("失败项仍保留在待删除队列").assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("删除完成").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("视频 1").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("视频 2").assertIsDisplayed()
        assertTrue(router.paths.count { it == "/api/v1/delete-queue" } >= 2)
    }

    @Test
    fun verifiedZeroBytesAndZeroFailuresAreExplicit() {
        router.receiptMode = 2
        setContent()
        openOrganizeTab()
        compose.onNodeWithText("待删除").performClick()
        compose.waitUntilExactlyOneExists(hasText("最终删除（2 项）"), 15_000)
        compose.onNodeWithText("最终删除（2 项）").performClick()
        compose.waitUntilExactlyOneExists(hasText("确认永久删除？"), 15_000)
        compose.onNodeWithText("永久删除").performClick()
        compose.waitUntilExactlyOneExists(hasText("已从磁盘删除：1 个文件"), 15_000)
        compose.onNodeWithText("实际释放：0 B").assertIsDisplayed()
        compose.onNodeWithText("文件原本已不存在：1").assertIsDisplayed()
        compose.onNodeWithText("删除失败：0").assertIsDisplayed()
        compose.onNodeWithText("实际磁盘删除数量和释放空间：未知").assertDoesNotExist()
    }

    @Test
    fun summaryRefreshKeepsExistingCardClickableAndFailureOffersRetry() {
        val gate = java.util.concurrent.CountDownLatch(1)
        var ownedHttp: okhttp3.OkHttpClient? = null
        try {
            val http = okhttp3.OkHttpClient.Builder().build()
            ownedHttp = http
            val vm = OrganizeViewModel(
                com.mediareview.app.feature.v2.organize.data.V2ServerOrganizeRepository(
                    profilePort = object : com.mediareview.app.feature.v2.data.server.V2ServerProfilePort {
                        override suspend fun baseUrl() = "http://127.0.0.1:${server.port}"
                    },
                    apiFactory = com.mediareview.app.core.network.ApiFactory(http, http, kotlinx.serialization.json.Json { ignoreUnknownKeys = true }),
                    mapper = com.mediareview.app.feature.v2.data.server.V2MediaMapper(com.mediareview.app.core.network.MediaUrlResolver()),
                    resources = com.mediareview.app.feature.v2.data.server.V2ServerResourceCache(),
                    statusStore = entryPoint().serverStatusStore(),
                ),
            )
            compose.activity.viewModelStore.put("stage8i-organize-feedback", vm)
            var opened = 0
            compose.setContent {
                MediaReviewTheme { OrganizePage(V2DataMode.SERVER, {}, { opened++ }, {}, {}, vm = vm) }
            }
            compose.waitUntilExactlyOneExists(hasText("2 项 · 预计释放 2.8 GB"), 15_000)
            router.summaryGate = gate
            router.summaryFailure = true
            compose.runOnIdle { vm.load() }
            compose.waitUntil(10_000) { router.summaryWaiting }
            compose.onNodeWithText("2 项 · 预计释放 2.8 GB · 正在刷新").assertIsDisplayed()
            val card = compose.onNodeWithText("待删除")
            val before = card.captureToImage().toPixelMap()
            card.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(150)
            val pressed = card.captureToImage().toPixelMap()
            assertTrue("抬起前不应执行操作", opened == 0)
            assertTrue("按下已有卡片应有即时反馈", (0 until before.height).any { y ->
                (0 until before.width).any { x -> before[x, y] != pressed[x, y] }
            })
            card.performTouchInput { up() }
            compose.runOnIdle { assertTrue(opened == 1) }
            gate.countDown()
            compose.waitUntilExactlyOneExists(hasText("刷新失败，显示上次结果 · 点击重试"), 15_000)
            compose.onNodeWithText("2 项 · 预计释放 2.8 GB").assertIsDisplayed()
            router.summaryGate = null
            router.summaryFailure = false
            val previousSuccesses = router.summarySuccesses
            compose.onNodeWithText("刷新失败，显示上次结果 · 点击重试").performClick()
            compose.waitUntil(15_000) {
                router.summarySuccesses > previousSuccesses &&
                    compose.onAllNodesWithText("2 项 · 预计释放 2.8 GB").fetchSemanticsNodes().size == 1 &&
                    compose.onAllNodesWithText("刷新失败，显示上次结果 · 点击重试").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText("待删除").performClick()
            compose.runOnIdle { assertTrue(opened == 2) }
        } finally {
            gate.countDown()
            router.summaryGate = null
            router.summaryFailure = false
            try {
                compose.runOnUiThread { compose.activity.viewModelStore.clear() }
            } finally {
                ownedHttp?.let { client ->
                    client.dispatcher.cancelAll()
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }
            }
        }
    }

    // ---------- 待删除：恢复 / 两步最终删除 ----------

    @Test
    fun deleteQueueRestoreOnlyAfterServerConfirm() {
        setContent()
        openOrganizeTab()
        compose.onNodeWithText("待删除").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("delete_queue_screen"), timeoutMillis = 15_000)
        compose.waitUntilExactlyOneExists(hasText("视频 1"), timeoutMillis = 15_000)

        // 点击"视频 1"一行的恢复按钮（该行内的"恢复"）
        compose.onAllNodes(hasText("恢复"))[0].performClick()
        // 先等服务端真的收到 DELETE（点击 → 请求完成之间存在竞态）
        compose.waitUntil(timeoutMillis = 15_000) { router.restoredIds.contains("aaa") }
        // Server 确认后列表刷新：被恢复的项消失，另一项仍在
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodes(hasText("视频 1")).fetchSemanticsNodes().isEmpty()
        }
        compose.onAllNodes(hasText("视频 2")).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun finalDeleteTwoStepConfirmAndResultSheet() {
        setContent()
        openOrganizeTab()
        compose.onNodeWithText("待删除").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("delete_queue_screen"), timeoutMillis = 15_000)

        // 第二步才出现确认：数字必须来自 prepare 响应
        compose.waitUntilExactlyOneExists(hasText("最终删除（2 项）"), timeoutMillis = 15_000)
        compose.onNodeWithText("最终删除（2 项）").performClick()
        compose.waitUntilExactlyOneExists(hasText("确认永久删除？"), timeoutMillis = 15_000)
        compose.onNodeWithText("将永久删除：\n2 个文件").assertIsDisplayed()
        compose.onNodeWithText("删除后无法恢复。").assertIsDisplayed()

        compose.onNodeWithText("永久删除").performClick()
        // 结果 Sheet 逐类显示 success / missing（绝不把失败藏起来）
        compose.waitUntilExactlyOneExists(hasText("删除完成"), timeoutMillis = 15_000)
        compose.onNodeWithText("服务端已处理：1 项").assertIsDisplayed()
        compose.onNodeWithText("实际磁盘删除数量和释放空间：未知").assertIsDisplayed()
        compose.onNodeWithText("删除失败：0").assertIsDisplayed()
        compose.onNodeWithText("文件原本已不存在：1").assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()

        // 完成后停留在待删除中心并刷新列表（不自动退出）
        compose.waitUntilExactlyOneExists(hasTestTag("delete_queue_screen"), timeoutMillis = 15_000)
        compose.waitUntilExactlyOneExists(hasText("没有待删除内容"), timeoutMillis = 15_000)
    }
}

/** 设备内 Mock Server：真实接口形状的整理中心响应（含状态迁移：restore / commit）。 */
private class OrganizeUiDispatcher : Dispatcher() {
    val paths = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Volatile
    private var committed = false

    @Volatile var receiptMode = 0
    @Volatile var summaryGate: java.util.concurrent.CountDownLatch? = null
    @Volatile var summaryWaiting = false
    @Volatile var summaryFailure = false
    @Volatile var summarySuccesses = 0

    val restoredIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private data class Item(val id: String, val name: String, val status: String, val size: Long)

    private val items = listOf(
        Item("aaa", "视频 1", "pending", 2_000_000_000),
        Item("bbb", "视频 2", "pending", 1_000_000_000),
    )

    private fun pending(): List<Item> =
        if (committed) {
            if (receiptMode == 1) listOf(items[1].copy(status = "failed")) else emptyList()
        } else items.filter { it.id !in restoredIds }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath.orEmpty()
        paths += path
        val method = request.method.orEmpty()
        val pendingNow = pending()
        val pendingBytes = pendingNow.sumOf { item -> item.size }
        val pendingIds = pendingNow.joinToString(",") { item -> "\"" + item.id + "\"" }
        return when {
            path == "/api/v1/delete-queue/summary" -> {
                summaryGate?.let { summaryWaiting = true; it.await(30, TimeUnit.SECONDS) }
                if (summaryFailure) MockResponse().setResponseCode(503)
                else {
                    summarySuccesses++
                    json("""{"success":true,"data":{"count":${pendingNow.size},"total_bytes":$pendingBytes}}""")
                }
            }
            path == "/api/v1/delete-queue" && method == "GET" -> json(deleteQueueBody())
            path.startsWith("/api/v1/delete-queue/") && method == "DELETE" -> {
                restoredIds += path.removePrefix("/api/v1/delete-queue/")
                json("""{"success":true,"data":{"media_id":"x","queued":false}}""")
            }
            path == "/api/v1/delete-queue/commit/prepare" -> json(
                """{"success":true,"data":{"nonce":"nonce-ui","expires_at":"2026-10-01T12:10:00","count":${pendingNow.size},"total_bytes":$pendingBytes,"media_ids":[$pendingIds]}}""",
            )
            path == "/api/v1/delete-queue/commit" -> {
                committed = true
                json(when (receiptMode) {
                    1 -> """{"success":true,"data":{"outcome":{"aaa":"success","bbb":"failed"},"deleted_count":1,"deleted_bytes":4321,"missing_count":0,"failed_count":1}}"""
                    2 -> """{"success":true,"data":{"outcome":{"aaa":"success","bbb":"missing"},"deleted_count":1,"deleted_bytes":0,"missing_count":1,"failed_count":0}}"""
                    else -> """{"success":true,"data":{"outcome":{"aaa":"success","bbb":"missing"}}}"""
                })
            }
            path == "/api/v1/duplicates/summary" -> json(
                """{"success":true,"data":{"exact_groups":3,"similar_groups":7,"scan_task_id":"t1","scan_status":"succeeded","scan_progress":100}}""",
            )
            // Stage 8C.1 §11：分组列表改为分页合同(items/total/page/page_size)
            path == "/api/v1/duplicates/exact" -> json(
                """{"success":true,"data":{"items":[{"group_id":"exact:1000:60000:1","type":"exact","count":2,"size_bytes":1000,"detail":"byte-identical"}],"total":1,"page":1,"page_size":50}}""",
            )
            path == "/api/v1/duplicates/similar" -> json(
                """{"success":true,"data":{"items":[{"group_id":"similar:2000","type":"similar","count":3,"size_bytes":2000,"detail":"疑似重复"}],"total":1,"page":1,"page_size":50}}""",
            )
            path == "/api/v1/duplicates/status" -> json("""{"success":true,"data":{"task_id":null}}""")
            path == "/api/v1/libraries" -> json(
                """{"success":true,"data":[
                   {"jellyfin_id":"lib-movies","name":"电影","collection_type":"movies","selected":true,"sort_order":0},
                   {"jellyfin_id":"lib-photos","name":"照片","collection_type":"homevideos","selected":true,"sort_order":1},
                   {"jellyfin_id":"lib-temp","name":"临时素材","collection_type":null,"selected":false,"sort_order":2}]}""",
            )
            path == "/api/v1/review/sessions/latest" -> MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"success":false,"error":{"code":"NOT_FOUND","message":"没有可恢复的批阅会话"}}""")
            else -> json("""{"success":true,"data":{}}""")
        }
    }

    private fun deleteQueueBody(): String {
        val rows = pending().joinToString(",") { item ->
            val isImage = false
            """
            {"media_id":"${item.id}","status":"${item.status}","size_bytes":${item.size},
             "added_at":"2026-09-30T10:00:00",
             "media":{"media_id":"${item.id}","name":"${item.name}","media_type":"${if (isImage) "image" else "video"}",
                      "library_id":"lib-1","size_bytes":${item.size},"duration_ms":30000,
                      "width":1920,"height":1080,
                      "cover_url":"/api/v1/media/${item.id}/thumbnail?v=abc",
                      "folder_id":"f1","folder_name":"旅行"}}
            """.trimIndent()
        }
        return """{"success":true,"data":[$rows]}"""
    }

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
