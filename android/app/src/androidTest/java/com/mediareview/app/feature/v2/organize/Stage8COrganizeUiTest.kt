package com.mediareview.app.feature.v2.organize

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.OrganizeTestEntryPoint
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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        router = OrganizeUiDispatcher()
        server = MockWebServer()
        server.dispatcher = router
        server.start()
        runBlocking {
            // 服务器地址与数据源模式都指向设备内 Mock Server（真实 HTTP + 真实 JSON）
            ServerProfileStore(context).saveBaseUrl("http://127.0.0.1:${server.port}")
            entryPoint().dataModeStore().set(V2DataMode.SERVER)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            runCatching { entryPoint().dataModeStore().set(V2DataMode.DEMO) }
        }
        runCatching { server.shutdown() }
    }

    private fun entryPoint(): OrganizeTestEntryPoint =
        EntryPointAccessors.fromApplication(context, OrganizeTestEntryPoint::class.java)

    private fun setContent() {
        val repository = DemoMediaRepository(context, AlbumCoverStore(context))
        val vm = testHomeViewModel(repository, SearchHistoryStore(context))
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
        compose.waitUntilExactlyOneExists(hasText("2 项 · 预计释放 2.8 GB"), timeoutMillis = 15_000)
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
        compose.onNodeWithText("成功删除：1").assertIsDisplayed()
        compose.onNodeWithText("文件已不存在：1").assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()

        // 完成后停留在待删除中心并刷新列表（不自动退出）
        compose.waitUntilExactlyOneExists(hasTestTag("delete_queue_screen"), timeoutMillis = 15_000)
        compose.waitUntilExactlyOneExists(hasText("没有待删除内容"), timeoutMillis = 15_000)
    }
}

/** 设备内 Mock Server：真实接口形状的整理中心响应（含状态迁移：restore / commit）。 */
private class OrganizeUiDispatcher : Dispatcher() {

    @Volatile
    private var committed = false

    val restoredIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private data class Item(val id: String, val name: String, val status: String, val size: Long)

    private val items = listOf(
        Item("aaa", "视频 1", "pending", 2_000_000_000),
        Item("bbb", "视频 2", "pending", 1_000_000_000),
    )

    private fun pending(): List<Item> =
        if (committed) emptyList() else items.filter { it.id !in restoredIds }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath.orEmpty()
        val method = request.method.orEmpty()
        val pendingNow = pending()
        val pendingBytes = pendingNow.sumOf { item -> item.size }
        val pendingIds = pendingNow.joinToString(",") { item -> "\"" + item.id + "\"" }
        return when {
            path == "/api/v1/delete-queue/summary" -> json(
                """{"success":true,"data":{"count":${pendingNow.size},"total_bytes":$pendingBytes}}""",
            )
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
                json("""{"success":true,"data":{"outcome":{"aaa":"success","bbb":"missing"}}}""")
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