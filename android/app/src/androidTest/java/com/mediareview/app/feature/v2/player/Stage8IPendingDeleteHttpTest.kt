package com.mediareview.app.feature.v2.player

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.feature.v2.data.SearchHistoryStore
import com.mediareview.app.core.datastore.ServerProfile
import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.data.V2DataModeStore
import com.mediareview.app.feature.v2.home.V2HomeViewModel
import com.mediareview.app.feature.v2.player.native.GsyNativePlayerWithPendingDelete
import com.mediareview.app.feature.v2.player.native.state.PlaybackContext
import com.mediareview.app.feature.v2.player.native.state.PlaybackQueueItem
import com.mediareview.app.feature.v2.testHomeViewModel
import com.mediareview.app.feature.v2.viewer.V2ImageViewer
import com.mediareview.app.feature.v2.viewer.state.ViewerPendingDeleteState
import com.mediareview.app.ui.theme.MediaReviewTheme
import com.shuyu.gsyvideoplayer.GSYVideoManager
import org.junit.*
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import coil.Coil
import coil.ImageLoader
import coil.imageLoader
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient

/** 正式 Full wrapper / Viewer → 同一 VM controller → 生产 Repository → 真实隔离 HTTP。 */
@RunWith(AndroidJUnit4::class)
class Stage8IPendingDeleteHttpTest {
    @get:Rule val compose = createAndroidComposeRule<HiltTestActivity>()
    private var fixture: RealHttpPlaybackFixture? = null
    private lateinit var home: V2HomeViewModel
    private lateinit var player: V2NativePlayerViewModel
    private var previousMode: V2DataMode? = null
    private var previousProfile: ServerProfile? = null
    private var previousLoader: ImageLoader? = null
    private var ownedLoader: ImageLoader? = null
    private var ownedImageHttp: OkHttpClient? = null
    private val currentOriginalDecoded = AtomicBoolean(false)

    @Before fun preservePersistedServerState() {
        // 保存旧状态，不输出凭据；launch 内配置当前 fixture 后运行正式 Server bootstrap。
        runBlocking {
            previousMode = V2DataModeStore(compose.activity).current()
            previousProfile = ServerProfileStore(compose.activity).current()
        }
    }

    @After fun cleanup() {
        try {
            fixture?.releasePendingRead(); fixture?.releasePendingWrite()
            compose.runOnUiThread { GSYVideoManager.releaseAllVideos(); compose.activity.viewModelStore.clear() }
        } finally {
            try {
                previousLoader?.let(Coil::setImageLoader)
                ownedLoader?.shutdown()
                ownedImageHttp?.let { http ->
                    http.dispatcher.cancelAll()
                    http.connectionPool.evictAll()
                    http.dispatcher.executorService.shutdown()
                }
                fixture?.close()
            } finally {
                try {
                    previousProfile?.let { old -> runBlocking {
                        val store = ServerProfileStore(compose.activity)
                        store.clear()
                        if (old.baseUrl.isNotBlank()) store.saveBaseUrl(old.baseUrl)
                        if (old.token.isNotBlank()) store.savePairing(old.token)
                        val restored = ServerProfileStore(compose.activity).current()
                        Assert.assertTrue("旧 Server 配置必须恢复，禁止输出配置或凭据", restored.baseUrl == old.baseUrl && restored.token == old.token)
                        android.util.Log.i("MRPendingBootstrap", "restored_profile=true")
                    } }
                } finally {
                    previousMode?.let { mode -> runBlocking {
                        V2DataModeStore(compose.activity).set(mode)
                        Assert.assertTrue("旧数据源模式必须恢复", V2DataModeStore(compose.activity).current() == mode)
                        android.util.Log.i("MRPendingBootstrap", "restored_mode=true")
                    } }
                }
            }
        }
    }
    private fun launch(live: RealHttpPlaybackFixture, viewer: Boolean = false) {
        fixture = live
        runBlocking {
            live.configureTestProfile(ServerProfileStore(compose.activity))
            V2DataModeStore(compose.activity).set(V2DataMode.SERVER)
        }
        home = testHomeViewModel(live.repository, object : SearchHistoryStore(compose.activity) {
            override suspend fun current() = emptyList<String>()
            override suspend fun add(term: String) = Unit
            override suspend fun clear() = Unit
        }, authenticatedApi = true)
        player = V2NativePlayerViewModel(live.repository)
        compose.activity.viewModelStore.put("8i-home", home)
        compose.activity.viewModelStore.put("8i-player", player)
        compose.waitUntil(10_000) { home.currentList.value.isNotEmpty() }
        Assert.assertTrue("必须由真实 Server host 通过配置及配对 bootstrap", home.isServerMode && home.serverSession.value.configured && home.serverSession.value.paired)
        Assert.assertTrue("必须调用真实 health contract Gate", live.apiRequests.any { it.path == "/api/v1/system/health" })
        Assert.assertTrue("probe 必须用认证媒体请求验证恢复凭据", live.apiRequests.any { it.path == "/api/v1/media" && it.pageSize == "1" && it.authenticated })
        android.util.Log.i("MRPendingBootstrap", "server_host_configured=true paired=true health_gate=true authenticated_probe=true")
        if (viewer) {
            val appContext = compose.activity.applicationContext
            previousLoader = appContext.imageLoader
            val imageHttp = live.authenticatedImageClient().also { ownedImageHttp = it }
            val loader = ImageLoader.Builder(appContext).okHttpClient(imageHttp)
                .eventListener(object : coil.EventListener {
                    override fun onSuccess(request: coil.request.ImageRequest, result: coil.request.SuccessResult) {
                        if (request.data.toString().substringBefore('?').endsWith("/first/original") &&
                            result.drawable.intrinsicWidth > 0 && result.drawable.intrinsicHeight > 0) {
                            currentOriginalDecoded.set(true)
                        }
                    }
                }).build().also { ownedLoader = it }
            Coil.setImageLoader(loader)
        }
        compose.runOnUiThread {
            home.openMedia("first")
            player.open(PlaybackContext(listOf(PlaybackQueueItem("first", "测试视频"), PlaybackQueueItem("second", "下一条"))))
        }
        compose.setContent { MediaReviewTheme {
            if (viewer) V2ImageViewer(home, "first", {})
            else GsyNativePlayerWithPendingDelete(home, player, {})
        } }
        if (viewer) {
            compose.waitUntil(15_000) {
                if (!currentOriginalDecoded.get() || !live.apiRequests.any { it.path.endsWith("/first/original") && it.authenticated }) {
                    false
                } else {
                    val pixels = compose.onNodeWithTag("viewer_pager").captureToImage().toPixelMap()
                    pixels[pixels.width / 2, pixels.height / 2].let { it.green > .8f && it.red < .2f && it.blue < .2f }
                }
            }
            android.util.Log.i("MRPendingBootstrap", "viewer_original_authenticated=true drawable_success=true visible_green_pixel=true")
        }
    }
    private fun fullButton(): SemanticsNodeInteraction {
        if (compose.onAllNodesWithTag("player_pending_delete").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("player_gesture_surface").performTouchInput { click(center) }
        }
        return compose.onNodeWithTag("player_pending_delete")
    }
    @Test fun fullInitialLoadAndAddOnlyConfirmAfterHttpThenSnackbarUndoUsesDelete() {
        val live = RealHttpPlaybackFixture(blockPendingRead = true, blockPendingWrite = true, authenticatedApi = true)
        launch(live)
        Assert.assertTrue(live.pendingReadRequested.await(5, TimeUnit.SECONDS))
        fullButton().assertIsNotEnabled()
        live.releasePendingRead()
        compose.waitUntil(10_000) { home.viewerPendingDeleteState.value is ViewerPendingDeleteState.Ready }
        fullButton().performClick()
        Assert.assertTrue(live.pendingWriteRequested.await(5, TimeUnit.SECONDS))
        Assert.assertTrue(live.pending.isEmpty())
        fullButton().assertIsNotEnabled()
        live.releasePendingWrite()
        compose.waitUntil(10_000) { live.pending == setOf("first") && !home.viewerPendingDeleteBusy.value }
        compose.onNodeWithText("已加入待删除").assertExists()
        compose.onNodeWithText("撤销").performClick()
        compose.waitUntil(10_000) { live.pending.isEmpty() && live.pendingWrites.size == 2 }
        Assert.assertEquals(listOf(true, false), live.pendingWrites.map { it.pending })
        Assert.assertTrue(live.pendingWrites.all { it.mediaId == "first" && it.succeeded })
        fullButton().assertIsEnabled()
    }
    @Test fun fullLoadFailureRetriesAndWriteFailureNeverShowsConfirmedUndo() {
        val live = RealHttpPlaybackFixture(authenticatedApi = true).apply { pendingReadFailure = true }
        launch(live)
        compose.waitUntil(10_000) { home.viewerPendingDeleteState.value is ViewerPendingDeleteState.Error }
        compose.onNodeWithText("状态未知 · 重试").assertExists()
        live.pendingReadFailure = false
        fullButton().performClick()
        compose.waitUntil(10_000) { home.viewerPendingDeleteState.value is ViewerPendingDeleteState.Ready }
        live.pendingWriteFailure = true
        fullButton().performClick()
        compose.waitUntil(10_000) { live.pendingWrites.size == 1 && !home.viewerPendingDeleteBusy.value }
        Assert.assertTrue(live.pending.isEmpty())
        compose.onNodeWithText("加入待删除失败").assertExists()
        compose.onNodeWithText("撤销待删除").assertDoesNotExist()
    }
    @Test fun viewerSnackbarUndoCallsRealHttpRatherThanCurrentPageToggle() {
        val live = RealHttpPlaybackFixture(imageMedia = true, authenticatedApi = true)
        launch(live, viewer = true)
        compose.waitUntil(10_000) { home.viewerPendingDeleteState.value is ViewerPendingDeleteState.Ready }
        compose.onNodeWithTag("viewer_delete").performClick()
        compose.waitUntil(10_000) { live.pending == setOf("first") }
        compose.onNodeWithText("已加入待删除").assertExists()
        compose.onNodeWithTag("viewer_pager").performTouchInput { swipeLeft() }
        // 顶部名称和页码均取自真实 pager 当前页，先确认跨页再撤销旧媒体。
        compose.onNodeWithText("HTTP 视频 second").assertIsDisplayed()
        compose.onNodeWithTag("viewer_count").assertTextEquals("2 / 2").assertIsDisplayed()
        compose.onNodeWithText("已加入待删除").assertIsDisplayed()
        compose.onNodeWithText("撤销").performClick()
        compose.waitUntil(10_000) { live.pending.isEmpty() && live.pendingWrites.size == 2 }
        Assert.assertEquals(listOf(true, false), live.pendingWrites.map { it.pending })
        Assert.assertTrue(live.pendingWrites.all { it.mediaId == "first" })
    }
    @Test fun previousMediaLateHttpWriteCannotPublishNewMediaConfirmation() {
        val live = RealHttpPlaybackFixture(blockPendingWrite = true, authenticatedApi = true)
        launch(live)
        compose.waitUntil(10_000) { home.viewerPendingDeleteState.value is ViewerPendingDeleteState.Ready }
        fullButton().performClick()
        Assert.assertTrue(live.pendingWriteRequested.await(5, TimeUnit.SECONDS))
        compose.runOnUiThread { player.moveTo(1) }
        compose.waitUntil(10_000) { player.state.value.currentMediaId == "second" && home.viewerPendingDeleteState.value is ViewerPendingDeleteState.Ready && !home.viewerPendingDeleteBusy.value }
        live.releasePendingWrite()
        compose.waitUntil(10_000) { live.pendingWrites.size == 1 }
        compose.waitForIdle()
        Assert.assertFalse((home.viewerPendingDeleteState.value as ViewerPendingDeleteState.Ready).ids.contains("second"))
        compose.onNodeWithText("已加入待删除").assertDoesNotExist()
        compose.onNodeWithText("撤销待删除").assertDoesNotExist()
    }
}
