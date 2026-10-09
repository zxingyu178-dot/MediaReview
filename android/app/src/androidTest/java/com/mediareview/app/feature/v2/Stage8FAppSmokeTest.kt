package com.mediareview.app.feature.v2

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mediareview.app.MainActivity
import com.mediareview.app.BuildConfig
import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.data.V2DataModeStore
import com.mediareview.app.feature.v2.releasenotes.ReleaseNotesCatalog
import com.mediareview.app.feature.v2.releasenotes.ReleaseNotesStore
import com.shuyu.gsyvideoplayer.GSYVideoManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 实际 MainActivity + 生产 Hilt/全局 Coil。可用同证书测试 APK 验证未重签的 Release。 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Stage8FAppSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var fixture: Stage8FHttpFixture? = null
    private val evidence by lazy {
        File(context.getExternalFilesDir(null), "stage8f-smoke/run-${SystemClock.uptimeMillis()}").apply { mkdirs() }
    }

    @After fun close() {
        if (scenario != null) runCatching {
            screenshot("99-before-teardown")
            File(evidence, "99-semantics.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
            fixture?.let { record("finish pending=${it.pending} hits=${it.hits}") }
        }
        scenario?.close()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { GSYVideoManager.releaseAllVideos() }
        runBlocking { ServerProfileStore(context).clear(); V2DataModeStore(context).set(V2DataMode.DEMO) }
        fixture?.close()
    }

    private fun verifyActualTarget() {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        assertEquals(BuildConfig.VERSION_CODE.toLong(), pkg.longVersionCode)
        assertEquals(BuildConfig.VERSION_NAME, pkg.versionName)
        val requireRelease = InstrumentationRegistry.getArguments().getString("stage8fRequireRelease") == "true"
        if (requireRelease) assertFalse("实际安装目标必须是 Release", debuggable)
        record("target version=${pkg.versionName} code=${pkg.longVersionCode} debuggable=$debuggable requireRelease=$requireRelease")
    }

    @Test fun actualAppAlpha9MediaExperience() {
        verifyActualTarget()
        runBlocking {
            ServerProfileStore(context).clear()
            V2DataModeStore(context).set(V2DataMode.DEMO)
            // Clean Release 保留 null（首次安装）；全量 Debug suite 可能已有历史，模拟相邻升级。
            val notes = ReleaseNotesStore(context)
            if (notes.lastSeenVersionCode() != null) notes.saveLastSeenVersionCode(BuildConfig.VERSION_CODE - 1)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitTag("whats_new_sheet")
        compose.onAllNodesWithTag("whats_new_sheet").assertCountEquals(1)
        compose.onNodeWithText(BuildConfig.VERSION_NAME).assertExists()
        ReleaseNotesCatalog.forVersionCode(BuildConfig.VERSION_CODE)!!.highlights.forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("来自 2.0.0-alpha8").assertDoesNotExist()
        screenshot("01-alpha9-notes")
        click("知道了")
        compose.waitUntil(10_000) { runBlocking { ReleaseNotesStore(context).lastSeenVersionCode() } == BuildConfig.VERSION_CODE }
        scenario!!.recreate()
        waitText("· 切换数据源")
        compose.onNodeWithTag("whats_new_sheet").assertDoesNotExist()
        screenshot("02-home")

        // Demo 与批阅通过真实 GSY 内核 duration/position/dimensions/isPlaying 验证。
        val demoVideo = compose.onAllNodes(hasContentDescription("AI视频-测试", substring = true)).onFirst()
        demoVideo.performClick()
        kernel("demo-full-player")
        back()
        compose.onNodeWithContentDescription("批阅").performClick()
        kernel("demo-review")
        compose.onNodeWithContentDescription("首页").performClick()

        val http = Stage8FHttpFixture().also { fixture = it }
        connect(http)
        sprite(http)
        albumAndDelete(http)
        val protected = http.hits.filter { !it.path.endsWith("/health") && !it.path.startsWith("/api/v1/pairing/") }
        assertTrue("业务、图片与 sprite 请求必须真正携带认证", protected.isNotEmpty() && protected.all { it.authenticated })
        record("http protected=${protected.size} allAuthenticated=true albumPages=${http.hits.filter { it.path == "/api/v1/media" }.map { it.page to it.pageSize }}")
        screenshot("09-final-home")
    }

    /** 聚焦实际 App 的图片加载回归，避免视频内核故障掩盖 Viewer RED/GREEN。 */
    @Test fun actualAppViewerDecodedPixelsAndDelete() {
        verifyActualTarget()
        runBlocking {
            ServerProfileStore(context).clear()
            V2DataModeStore(context).set(V2DataMode.DEMO)
            ReleaseNotesStore(context).saveLastSeenVersionCode(BuildConfig.VERSION_CODE)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val http = Stage8FHttpFixture().also { fixture = it }
        connect(http)
        albumAndDelete(http)
    }

    private fun connect(http: Stage8FHttpFixture) {
        click("· 切换数据源")
        click("我的服务器")
        // 切换模式会异步写 DataStore：等待真正可编辑的表单控件，不能点击后立即查 label。
        inputField("服务器地址（如 192.168.1.10）").performTextReplacement(http.baseUrl)
        inputField("配对码（在服务器端查看）").performTextReplacement("123456")
        compose.onNodeWithText("连接服务器", useUnmergedTree = true).performClick()
        waitText("已配对")
        assertEquals(1, http.count("/api/v1/pairing/verify", "POST"))
        back()
        // 背景媒体存在不代表配对弹层已关闭；通过 Material3 的真实关闭控件完成测试前提。
        if (compose.onAllNodesWithText("数据源").fetchSemanticsNodes().isNotEmpty()) {
            File(evidence, "connect-sheet-after-back-semantics.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
            compose.onNodeWithContentDescription("Close sheet", useUnmergedTree = true).performClick()
            record("connect remainingDataSourceSheet closedViaMaterial3CloseSheet=true")
        }
        compose.waitUntilDoesNotExist(hasText("数据源"), 10_000)
        compose.onNodeWithText("数据源").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close sheet", useUnmergedTree = true).assertDoesNotExist()
        waitText(http.name("v1"))
        compose.onNodeWithContentDescription(http.name("v1"), useUnmergedTree = true).assertIsDisplayed()
        record("connect dataSourceSheetAbsent=true homeMediaVisible=true pairingVerifyCount=${http.count("/api/v1/pairing/verify", "POST")}")
        screenshot("connect-home-after-sheet-dismiss")
    }

    private fun inputField(label: String): SemanticsNodeInteraction {
        val matcher = hasSetTextAction() and (hasText(label) or hasAnyDescendant(hasText(label)))
        compose.waitUntil(15_000) {
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        return compose.onNode(matcher, useUnmergedTree = true)
    }

    private fun sprite(http: Stage8FHttpFixture) {
        val cover = compose.onNodeWithContentDescription(http.name("v1"), useUnmergedTree = true)
        compose.waitUntil(10_000) { color(cover) == Color.Green }
        cover.performTouchInput { longClick() }
        waitText("预览准备中…")
        screenshot("03-sprite-preparing")
        compose.waitUntil(10_000) { color(cover).let { it == Color.Red || it == Color.Blue } }
        val first = color(cover)
        screenshot("04-sprite-frame-a")
        compose.waitUntil(2000) { color(cover).let { it != first && (it == Color.Red || it == Color.Blue) } }
        screenshot("05-sprite-frame-b")
        assertEquals(1, http.count("/api/v1/cache/sprites/v1", "POST"))
        assertTrue(http.count("/api/v1/tasks/sprite-v1") >= 4)
        assertEquals(1, http.count("/api/v1/cache/sprites/v1"))
        assertEquals(1, http.count("/api/v1/cache/sprites/v1/file"))
        // 真实滚动停止预览；返回同一张封面应回到绿色静态图片。
        val grid = compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasContentDescription(http.name("v1"))))
        grid.performTouchInput { swipeUp() }
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToIndex(0)
        compose.waitUntil(5000) { color(cover) == Color.Green }
        cover.performTouchInput { longClick() }
        compose.waitUntil(5000) { color(cover).let { it == Color.Red || it == Color.Blue } }
        assertEquals("缓存重用不得再次 ensure", 1, http.count("/api/v1/cache/sprites/v1", "POST"))
        assertEquals("缓存重用不得再次 manifest", 1, http.count("/api/v1/cache/sprites/v1"))
        assertEquals("全局 Coil 应复用 sheet", 1, http.count("/api/v1/cache/sprites/v1/file"))
        http.failSprite = true
        compose.onNodeWithContentDescription(http.name("v2"), useUnmergedTree = true).performTouchInput { longClick() }
        waitText("预览生成失败")
        screenshot("06-sprite-error")
        compose.onNodeWithContentDescription(http.name("v2")).performClick()
        kernel("server-error-card-still-plays")
        assertTrue(http.hits.any { it.path == "/stream/v2.mp4" && it.authenticated })
        back()
        http.failSprite = false
    }

    private fun albumAndDelete(http: Stage8FHttpFixture) {
        http.failAlbumPage = 1
        click("书架")
        compose.onNodeWithText("测试相册").performClick()
        waitText("相册加载失败")
        compose.onNodeWithText("暂无照片").assertDoesNotExist()
        screenshot("07-album-first-error")
        http.failAlbumPage = 0
        click("重新加载")
        waitTag("album_grid")
        compose.onNodeWithText("120 张照片").assertExists()
        http.failAlbumPage = 2
        compose.onNodeWithTag("album_grid").performScrollToIndex(47)
        waitText("加载更多失败 · 重试")
        compose.onNodeWithContentDescription(http.name("p48")).performClick()
        waitTag("viewer_count")
        compose.onNodeWithTag("viewer_count").assertTextEquals("48 / 50")
        waitTag("viewer_album_retry")
        viewerPixel(http, 48)
        http.failAlbumPage = 0
        compose.onNodeWithTag("viewer_album_retry").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("48 / 100").fetchSemanticsNodes().isNotEmpty() }
        for (index in 49..51) {
            compose.onNodeWithTag("viewer_pager").performTouchInput { swipeLeft() }
            compose.onNodeWithTag("viewer_count").assertTextEquals("$index / 100")
            viewerPixel(http, index)
        }
        screenshot("08-album-51-pixels")
        // 外层连接成功 Snackbar 会覆盖底部按钮；等真实提示消失后再注入触摸。
        compose.waitUntilDoesNotExist(hasText("已连接服务器"), 15_000)
        record("before delete queueHits=${http.hits.filter { it.path.startsWith("/api/v1/delete-queue") }}")
        compose.onNodeWithTag("viewer_delete").assertIsEnabled()
        compose.onNodeWithTag("viewer_delete").performClick()
        waitText("撤销待删除")
        compose.waitUntil(5000) { "p51" in http.pending }
        back(); back()
        compose.onNodeWithContentDescription("整理").performClick()
        compose.waitUntil(10_000) { http.count("/api/v1/delete-queue/summary") > 0 }
        click("待删除")
        waitTag("delete_queue_screen")
        waitText(http.name("p51"))
        click("恢复")
        compose.waitUntil(5000) { http.pending.isEmpty() }
        waitText("暂无待删除内容")
        back()
        compose.onNodeWithContentDescription("首页").performClick()
        click("测试相册")
        waitTag("album_grid")
        compose.onNodeWithTag("album_grid").performScrollToIndex(47)
        compose.onNodeWithContentDescription(http.name("p48")).performClick()
        waitTag("viewer_count")
        repeat(3) { compose.onNodeWithTag("viewer_pager").performTouchInput { swipeLeft() } }
        compose.onNodeWithTag("viewer_count").assertTextEquals("51 / 100")
        viewerPixel(http, 51)
        compose.onNodeWithText("待删除").assertExists()
        compose.onNodeWithText("撤销待删除").assertDoesNotExist()
        assertTrue(http.hits.any { it.path == "/api/v1/delete-queue/p51" && it.method == "POST" })
        assertTrue(http.hits.any { it.path == "/api/v1/delete-queue/p51" && it.method == "DELETE" })
        assertTrue(http.hits.any { it.path == "/api/v1/media" && it.page == 2 && it.pageSize == 50 })
        back(); back()
    }

    private fun viewerPixel(http: Stage8FHttpFixture, index: Int) {
        waitText(http.name("p$index"))
        val expected = Color(Stage8FHttpFixture.photoColor(index))
        try {
            compose.waitUntil(10_000) { color(compose.onNodeWithTag("viewer_pager"), 2) == expected }
        } catch (failure: Throwable) {
            screenshot("viewer-$index-failure")
            record("viewer failure index=$index sampled=${color(compose.onNodeWithTag("viewer_pager"), 2)} expected=$expected requests=${http.hits.filter { it.path.contains("/p$index/") }}")
            File(evidence, "viewer-$index-semantics.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
            throw failure
        }
        compose.waitUntil(10_000) { http.hits.any { it.path == "/api/v1/media/p$index/original" && it.authenticated } }
        record("viewer index=$index name=${http.name("p$index")} decodedPixel=$expected originalHttp=true")
    }
    private fun color(node: SemanticsNodeInteraction, denominator: Int = 4): Color? = runCatching {
        val pixels = node.captureToImage().toPixelMap()
        pixels[pixels.width / 2, pixels.height / denominator]
    }.getOrNull()
    private fun kernel(label: String) {
        var baseline = 0L
        var observed = "no sample"
        try {
            compose.waitUntil(30_000) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val player = GSYVideoManager.instance()
                    observed = "duration=${player.duration} position=${player.currentPosition} dimensions=${player.currentVideoWidth}x${player.currentVideoHeight} isPlaying=${player.isPlaying}"
                    baseline = if (player.isPlaying && player.duration > 0 && player.currentVideoWidth > 0 && player.currentVideoHeight > 0) player.currentPosition else 0L
                }
                baseline > 100L
            }
        } catch (failure: Throwable) {
            record("kernel timeout $label $observed")
            screenshot("$label-failure")
            File(evidence, "$label-semantics.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
            throw failure
        }
        var advanced = false
        compose.waitUntil(10_000) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val player = GSYVideoManager.instance()
                advanced = player.isPlaying && player.currentPosition > baseline + 500
                if (advanced) record("kernel $label duration=${player.duration} position=${player.currentPosition} dimensions=${player.currentVideoWidth}x${player.currentVideoHeight} isPlaying=${player.isPlaying}")
            }
            advanced
        }
        screenshot(label)
    }
    private fun click(text: String) { waitText(text); compose.onNodeWithText(text).performClick() }
    private fun waitText(text: String) = compose.waitUntilAtLeastOneExists(hasText(text), 15_000)
    private fun waitTag(tag: String) = compose.waitUntilExactlyOneExists(hasTestTag(tag), 15_000)
    private fun back() { androidx.test.espresso.Espresso.pressBack(); compose.waitForIdle() }
    private fun screenshot(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun record(message: String) { Log.i("Stage8FSmoke", message); File(evidence, "metrics.txt").appendText("${SystemClock.uptimeMillis()} $message\n") }
}
