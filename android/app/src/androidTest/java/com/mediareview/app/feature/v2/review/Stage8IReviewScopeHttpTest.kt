package com.mediareview.app.feature.v2.review

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.core.datastore.ServerProfile
import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.feature.v2.data.*
import com.mediareview.app.feature.v2.player.RealHttpPlaybackFixture
import com.mediareview.app.feature.v2.testHomeViewModel
import com.mediareview.app.ui.theme.MediaReviewTheme
import coil.Coil
import coil.ImageLoader
import coil.imageLoader
import kotlinx.coroutines.runBlocking
import org.junit.*

/** Paired bootstrap + production repositories + authenticated HTTP + real Scope Sheet. */
class Stage8IReviewScopeHttpTest {
    @get:Rule val compose = createAndroidComposeRule<HiltTestActivity>()
    private lateinit var live: RealHttpPlaybackFixture
    private lateinit var vm: V2ReviewViewModel
    private lateinit var oldProfile: ServerProfile
    private lateinit var oldMode: V2DataMode
    private var oldLoader: ImageLoader? = null
    private var loader: ImageLoader? = null
    private var http: okhttp3.OkHttpClient? = null

    @Before fun preserve() = runBlocking {
        oldProfile = ServerProfileStore(compose.activity).current()
        oldMode = V2DataModeStore(compose.activity).current()
    }
    @After fun restore() {
        try {
            compose.runOnUiThread {
                com.shuyu.gsyvideoplayer.GSYVideoManager.releaseAllVideos()
                compose.activity.viewModelStore.clear()
            }
            oldLoader?.let(Coil::setImageLoader)
            loader?.shutdown()
            http?.let { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdown() }
            if (::live.isInitialized) live.close()
        } finally {
            runBlocking {
                try {
                    ServerProfileStore(compose.activity).let { store ->
                        store.clear()
                        if (oldProfile.baseUrl.isNotBlank()) store.saveBaseUrl(oldProfile.baseUrl)
                        if (oldProfile.token.isNotBlank()) store.savePairing(oldProfile.token)
                        val restored = store.current()
                        Assert.assertTrue("旧配对配置必须恢复，禁止输出配置或凭据",
                            oldProfile.baseUrl == restored.baseUrl && oldProfile.token == restored.token)
                    }
                } finally {
                    V2DataModeStore(compose.activity).set(oldMode)
                    Assert.assertTrue("旧数据源必须恢复", V2DataModeStore(compose.activity).current() == oldMode)
                }
            }
        }
    }

    private fun launch(active: Boolean = true, capability: Boolean = true) {
        live = RealHttpPlaybackFixture(imageMedia = true, authenticatedApi = true, folderFixture = true,
            reviewScopeFixture = true, reviewScopeCapability = capability).apply { reviewActive = active }
        runBlocking {
            live.configureTestProfile(ServerProfileStore(compose.activity))
            V2DataModeStore(compose.activity).set(V2DataMode.SERVER)
        }
        oldLoader = compose.activity.applicationContext.imageLoader
        http = live.authenticatedImageClient()
        loader = ImageLoader.Builder(compose.activity.applicationContext).okHttpClient(http!!).build()
        Coil.setImageLoader(loader!!)
        val home = testHomeViewModel(live.repository, SearchHistoryStore(compose.activity), live.status, authenticatedApi = true)
        compose.activity.viewModelStore.put("stage8i-scope-bootstrap", home)
        compose.waitUntil(10_000) { home.currentList.value.isNotEmpty() && home.serverSession.value.paired }
        Assert.assertTrue(home.isServerMode && home.serverSession.value.configured && home.serverSession.value.paired)
        Assert.assertTrue(live.apiRequests.any { it.path == "/api/v1/system/health" })
        Assert.assertTrue(live.apiRequests.any { it.path == "/api/v1/media" && it.pageSize == "1" && it.authenticated })
        vm = V2ReviewViewModel(live.repository, live.sessions)
        compose.activity.viewModelStore.put("stage8i-review-scope", vm)
        compose.setContent { MediaReviewTheme { V2ReviewScreen(vm, {}, {}) } }
    }

    @Test fun activeCancelConfirmMultiScopeAndResumeUseRealSessionRequests() {
        launch()
        compose.waitUntil(10_000) { vm.state.value is V2ReviewUiState.Ready }
        val old = vm.state.value as V2ReviewUiState.Ready
        Assert.assertEquals(0, live.reviewCreates.size)
        compose.onNodeWithTag("review_more").performClick()
        compose.onNodeWithTag("review_scope_reselect").performClick()
        compose.waitUntil(10_000) { !vm.scopeSheet.value.loading && vm.scopeSheet.value.folders.size == 4 }
        compose.waitUntil(10_000) {
            val pixels = compose.onNodeWithTag("folder_cover_ft_root", useUnmergedTree = true).captureToImage().toPixelMap()
            val center = pixels[pixels.width / 2, pixels.height / 2]
            center.green > .8f && center.red < .2f && center.blue < .2f
        }
        Assert.assertTrue(live.apiRequests.any { it.path.endsWith("/thumbnail") && it.authenticated })
        compose.onNodeWithTag("review_scope_folder_ft_root").performTouchInput { click() }
        compose.onNodeWithTag("review_scope_folder_ft_parent").performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("review_scope_descendants").performClick()
        compose.onNodeWithTag("review_scope_start").performClick()
        compose.onNodeWithText("当前批阅尚未完成").assertExists()
        Assert.assertEquals(0, live.reviewCreates.size)
        compose.onNodeWithTag("review_scope_cancel").performClick()
        val cancelled = vm.state.value as V2ReviewUiState.Ready
        Assert.assertEquals(old.sessionId, cancelled.sessionId)
        Assert.assertEquals(old.items.map { it.mediaId }, cancelled.items.map { it.mediaId })
        Assert.assertEquals(0, live.reviewCreates.size)
        compose.onNodeWithTag("review_more").performClick()
        compose.onNodeWithTag("review_scope_reselect").performClick()
        compose.waitUntil(10_000) { !vm.scopeSheet.value.loading }
        compose.onNodeWithTag("review_scope_folder_ft_root").performTouchInput { click() }
        compose.onNodeWithTag("review_scope_folder_ft_parent").performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("review_scope_descendants").performClick()
        compose.onNodeWithTag("review_scope_start").performClick()
        compose.onNodeWithTag("review_scope_confirm").performClick()
        compose.waitUntil(10_000) { (vm.state.value as? V2ReviewUiState.Ready)?.sessionId == "http-review-1" && !vm.scopeSheet.value.visible }
        Assert.assertEquals(1, live.reviewCreates.size)
        Assert.assertEquals(listOf("ft_root", "ft_parent"), live.reviewCreates.single().folderIds)
        Assert.assertFalse(live.reviewCreates.single().includeDescendants)
        compose.runOnIdle { vm.retryEnter() }
        compose.waitUntil(10_000) { (vm.state.value as? V2ReviewUiState.Ready)?.sessionId == "http-review-1" }
        Assert.assertEquals(1, live.reviewCreates.size)
        Assert.assertFalse(vm.scopeTitle.value.contains(":\\"))
        Assert.assertTrue(live.apiRequests.filter { it.path.contains("/review/") }.all { it.authenticated })
        android.util.Log.i("MRReviewScopeHTTP", "paired_bootstrap=true cover_pixels=true cancel_create=0 confirm_create=1 resume_create=1 multi_array=true descendants_bool=false")
    }

    @Test fun noActiveScopeSheetCreatesOnlyAfterChoice() {
        launch(active = false)
        compose.waitUntil(10_000) { vm.scopeSheet.value.visible && !vm.scopeSheet.value.loading }
        compose.onNodeWithTag("review_scope_sheet").assertExists()
        Assert.assertEquals(0, live.reviewCreates.size)
        compose.onNodeWithTag("review_scope_cancel").performClick()
        Assert.assertEquals(V2ReviewUiState.ScopeSelection, vm.state.value)
        Assert.assertEquals(0, live.reviewCreates.size)
        compose.onNodeWithText("选择批阅范围", substring = false).performClick()
        compose.waitUntil(10_000) { vm.scopeSheet.value.visible && !vm.scopeSheet.value.loading }
        compose.onNodeWithTag("review_scope_start").performClick()
        compose.waitUntil(10_000) { vm.state.value is V2ReviewUiState.Ready }
        Assert.assertEquals(1, live.reviewCreates.size)
        Assert.assertTrue(live.reviewCreates.single().folderIds.isEmpty())
    }

    @Test fun missingScopeCapabilityDisablesFoldersKeepsAllVideoReview() {
        launch(active = false, capability = false)
        compose.waitUntil(10_000) { vm.scopeSheet.value.visible }
        compose.onNodeWithTag("review_scope_choose_folders").assertIsNotEnabled()
        compose.onNodeWithTag("review_scope_upgrade").assertExists()
        Assert.assertEquals(0, live.reviewCreates.size)
        compose.onNodeWithTag("review_scope_start").performClick()
        compose.waitUntil(10_000) { vm.state.value is V2ReviewUiState.Ready }
        Assert.assertEquals(1, live.reviewCreates.size)
        Assert.assertTrue(live.reviewCreates.single().folderIds.isEmpty())
    }

    @Test fun acceptedCreationQueueFailureRetriesCanonicalSessionWithoutSecondPost() {
        launch()
        compose.waitUntil(10_000) { vm.state.value is V2ReviewUiState.Ready }
        compose.onNodeWithTag("review_more").performClick()
        compose.onNodeWithTag("review_scope_reselect").performClick()
        compose.waitUntil(10_000) { !vm.scopeSheet.value.loading }
        compose.onNodeWithTag("review_scope_start").performClick()
        live.reviewQueueFailure = true
        compose.onNodeWithTag("review_scope_confirm").performClick()
        compose.waitUntil(10_000) { vm.state.value is V2ReviewUiState.Error }
        Assert.assertEquals(1, live.reviewCreates.size)
        Assert.assertFalse(vm.scopeSheet.value.visible)
        Assert.assertFalse(vm.state.value is V2ReviewUiState.Ready)
        live.reviewQueueFailure = false
        compose.onNodeWithText("重新尝试").performClick()
        compose.waitUntil(10_000) { (vm.state.value as? V2ReviewUiState.Ready)?.sessionId == "http-review-1" }
        Assert.assertEquals(1, live.reviewCreates.size)
        Assert.assertTrue(live.apiRequests.filter { it.path.contains("/review/") }.all { it.authenticated })
        android.util.Log.i("MRReviewScopeHTTP", "accepted_create=1 queue_failure=true old_ready_retired=true canonical_resume=true retry_create=1")
    }
}
