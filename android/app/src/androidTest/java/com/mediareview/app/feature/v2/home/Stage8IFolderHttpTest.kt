package com.mediareview.app.feature.v2.home

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.toPixelMap
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

/** 正式配对 bootstrap + 生产 ServerRepo + HTTP；Compose widget host，不代表 NavHost/真机/LAN 验收。 */
class Stage8IFolderHttpTest {
    @get:Rule val compose = createAndroidComposeRule<HiltTestActivity>()
    private lateinit var live: RealHttpPlaybackFixture
    private lateinit var vm: V2HomeViewModel
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
            compose.runOnUiThread { compose.activity.viewModelStore.clear() }
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
    private fun launch(folder: Boolean) {
        live = RealHttpPlaybackFixture(imageMedia = true, authenticatedApi = true, folderFixture = folder)
        runBlocking {
            live.configureTestProfile(ServerProfileStore(compose.activity))
            V2DataModeStore(compose.activity).set(V2DataMode.SERVER)
        }
        oldLoader = compose.activity.applicationContext.imageLoader
        http = live.authenticatedImageClient()
        loader = ImageLoader.Builder(compose.activity.applicationContext).okHttpClient(http!!).build()
        Coil.setImageLoader(loader!!)
        vm = testHomeViewModel(live.repository, SearchHistoryStore(compose.activity), live.status, authenticatedApi = true)
        compose.activity.viewModelStore.put("stage8i-folders", vm)
        compose.waitUntil(10_000) { vm.currentList.value.isNotEmpty() && (!folder || vm.folderTree.value.size == 4) }
        Assert.assertTrue(vm.isServerMode && vm.serverSession.value.configured && vm.serverSession.value.paired)
        Assert.assertTrue(live.apiRequests.any { it.path == "/api/v1/system/health" })
        Assert.assertTrue(live.apiRequests.any { it.path == "/api/v1/media" && it.pageSize == "1" && it.authenticated })
        compose.setContent {
            var browsing by remember { mutableStateOf(false) }
            var initial by remember { mutableStateOf<String?>(null) }
            MediaReviewTheme {
                if (browsing) FolderBrowserScreen(vm, initial, { browsing = false }, { _, _, _ -> })
                else HomeScreen(vm, {}, { _, _ -> }, {}, {}, { initial = it; browsing = true })
            }
        }
    }
    @Test fun actualHttpHierarchyScopeCoverAndBack() {
        launch(true)
        compose.onNodeWithText("旅行", substring = false).performTouchInput { click() }
        compose.waitUntil(10_000) { vm.folderBrowserState.value.folderId == "ft_parent" && !vm.folderBrowserState.value.loading }
        compose.onNodeWithTag("folder_ft_child").assertIsDisplayed()
        Assert.assertEquals(0, vm.folderTree.value.first { it.id == "ft_parent" }.directMediaCount)
        compose.waitUntil(10_000) { live.apiRequests.any { it.path.endsWith("/thumbnail") && it.authenticated } }
        compose.waitUntil(10_000) {
            val pixels = compose.onNodeWithTag("folder_cover_ft_child", useUnmergedTree = true).captureToImage().toPixelMap()
            val center = pixels[pixels.width / 2, pixels.height / 2]
            center.green > .8f && center.red < .2f && center.blue < .2f
        }
        compose.waitUntil(10_000) { !vm.folderBrowserState.value.includeDescendants && !vm.folderBrowserState.value.loading }
        Assert.assertTrue(vm.folderBrowserState.value.items.isEmpty())
        compose.onNodeWithTag("folder_ft_child").assertIsDisplayed().performTouchInput { click() }
        compose.waitUntil(10_000) { vm.folderBrowserState.value.folderId == "ft_child" && !vm.folderBrowserState.value.loading }
        compose.onNodeWithTag("folder_ft_leaf").performTouchInput { click() }
        compose.waitUntil(10_000) { vm.folderBrowserState.value.folderId == "ft_leaf" && vm.folderBrowserState.value.items.size == 2 }
        compose.onNodeWithTag("media_first").assertExists()
        compose.onNodeWithText("旅行", substring = false).performTouchInput { click() }
        compose.waitUntil(10_000) { vm.folderBrowserState.value.folderId == "ft_parent" && !vm.folderBrowserState.value.loading }
        compose.onNodeWithTag("folder_more").performTouchInput { click() }
        compose.onNodeWithTag("folder_scope_toggle").performTouchInput { click() }
        compose.waitUntil(10_000) { vm.folderBrowserState.value.includeDescendants && vm.folderBrowserState.value.items.size == 2 && !vm.folderBrowserState.value.refreshing }
        compose.onNodeWithTag("folder_back").performTouchInput { click() }
        compose.onNodeWithTag("folder_ft_parent").assertExists()
        val requests = live.apiRequests.filter { it.folderId != null }
        Assert.assertTrue(requests.all { it.folderId!!.startsWith("ft_") && it.authenticated && it.pageSize == "50" })
        Assert.assertTrue(requests.any { it.folderId == "ft_parent" && it.includeDescendants == "true" })
        Assert.assertTrue(requests.any { it.folderId == "ft_parent" && it.includeDescendants == "false" })
        Assert.assertTrue(requests.any { it.folderId == "ft_leaf" })
        android.util.Log.i("MRFolderHTTP", "paired_bootstrap=true hierarchy=ft_parent>ft_child>ft_leaf scope=true,false cover_pixels=true breadcrumb_back=true widget_host=true")
    }
    @Test fun missingFolderCapabilityShowsRuntimeAndKeepsBasicMediaUsable() {
        launch(false)
        compose.onNodeWithTag("runtime_diagnosis").assertExists()
        compose.onNodeWithTag("folder_upgrade_hint").assertExists()
        compose.onNodeWithText("全部分类").assertIsNotEnabled()
        Assert.assertEquals("1.4.0", vm.serverVersion.value)
        Assert.assertEquals(2, vm.serverApiContract.value)
        Assert.assertTrue(vm.serverCapabilities.value.isEmpty())
        Assert.assertTrue(vm.currentList.value.isNotEmpty())
        runBlocking {
            Assert.assertNotNull(live.repository.resolvePlayback("first"))
            Assert.assertTrue(live.repository.imageUri(vm.currentList.value.first()).isNotBlank())
            Assert.assertTrue(live.sessions.enterSession(forceNew = true) is com.mediareview.app.feature.v2.review.data.ReviewSessionOpen.Ready)
        }
        Assert.assertFalse(live.apiRequests.any { it.path == "/api/v1/media/folder-tree" })
        compose.runOnIdle { vm.openFolderBrowser("ft_parent") }
        Assert.assertNull(vm.folderBrowserState.value.folderId)
    }
}
