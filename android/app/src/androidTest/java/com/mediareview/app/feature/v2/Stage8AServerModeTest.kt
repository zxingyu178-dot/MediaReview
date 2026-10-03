package com.mediareview.app.feature.v2

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.ImageLoader
import coil.request.ImageRequest
import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.AuthInterceptor
import com.mediareview.app.core.network.CacheAuthInterceptor
import com.mediareview.app.core.network.MediaUrlResolver
import com.mediareview.app.core.network.TokenProvider
import com.mediareview.app.core.pairing.PairingRepository
import com.mediareview.app.feature.v2.data.AlbumCoverStore
import com.mediareview.app.feature.v2.data.DemoMediaRepository
import com.mediareview.app.feature.v2.data.SearchHistoryStore
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.data.V2DataModeStore
import com.mediareview.app.feature.v2.data.V2MediaRepositoryRouter
import com.mediareview.app.feature.v2.data.server.V2MediaMapper
import com.mediareview.app.feature.v2.data.server.V2PlaybackResolver
import com.mediareview.app.feature.v2.data.server.V2ServerHealthMonitor
import com.mediareview.app.feature.v2.data.server.V2ServerMediaRepository
import com.mediareview.app.feature.v2.data.server.V2ServerResourceCache
import com.mediareview.app.feature.v2.data.server.V2ServerSessionBootstrap
import com.mediareview.app.feature.v2.data.server.V2ServerStatusStore
import com.mediareview.app.feature.v2.home.HomeScreen
import com.mediareview.app.feature.v2.home.V2HomeViewModel
import com.mediareview.app.ui.theme.MediaReviewTheme
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 8A §38：Server 模式端到端（**Mock Server**，非真实 Jellyfin）。
 *
 * 前提：宿主机的 Mock MediaReview Server 已启动（见 04_NETWORK_QA.md）：
 * ```
 * server\.venv\Scripts\python.exe temp\stage8a_mock_server\mock_media_server.py \
 *     --port 8799 --root android\app\src\main\res\raw --audit <log>
 * ```
 * 模拟器通过 10.0.2.2 访问宿主机；配对码固定 123456，token 为 mock-token。
 *
 * 覆盖：真实 HTTP 分页列表 / 文件夹 / 封面（Coil 真加载）/ 原图 / playback direct + headers
 * （缺 header 会被服务端 403，直接证明 headers 真的发出）/ 收藏服务器确认 / 媒体墙 UI 渲染。
 *
 * 诚实声明：本测试不是真实 Jellyfin 环境，报告必须写明 "Mock Server"。
 */
@RunWith(AndroidJUnit4::class)
class Stage8AServerModeTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private lateinit var context: Context
    private lateinit var profileStore: ServerProfileStore
    private lateinit var tokenProvider: TokenProvider
    private lateinit var imageLoader: ImageLoader
    private lateinit var apiFactory: ApiFactory
    private lateinit var repository: V2ServerMediaRepository
    private lateinit var modeStore: V2DataModeStore
    private lateinit var serverStatus: V2ServerStatusStore
    private lateinit var albumCoverStore: AlbumCoverStore
    private lateinit var demoRepository: DemoMediaRepository

    private val baseUrl = "http://10.0.2.2:8799"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 手工装配与 Hilt 完全相同的生产依赖（不依赖 Hilt EntryPoint，测试自身可独立运行）
        profileStore = ServerProfileStore(context)
        tokenProvider = TokenProvider()
        albumCoverStore = AlbumCoverStore(context)
        serverStatus = V2ServerStatusStore()
        modeStore = V2DataModeStore(context)
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        // 与 AppModule 相同的认证链路：Bearer + 缓存图片请求头
        val authenticated = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenProvider))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val cacheClient = OkHttpClient.Builder()
            .addInterceptor(CacheAuthInterceptor(tokenProvider))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        apiFactory = ApiFactory(authenticated, OkHttpClient(), json)
        imageLoader = ImageLoader.Builder(context).okHttpClient { cacheClient }.build()
        demoRepository = DemoMediaRepository(context, albumCoverStore)

        // 写入与真实配对流程一致的持久化状态，再由生产 bootstrap 恢复 TokenProvider
        runBlocking {
            profileStore.saveBaseUrl(baseUrl)
            profileStore.savePairing("mock-token")
            V2ServerSessionBootstrap(profileStore, tokenProvider).restore()
            modeStore.set(V2DataMode.SERVER)
        }

        repository = V2ServerMediaRepository(
            profileStore = profileStore,
            apiFactory = apiFactory,
            mapper = V2MediaMapper(MediaUrlResolver()),
            playbackResolver = V2PlaybackResolver(MediaUrlResolver()),
            albumCoverStore = albumCoverStore,
            statusStore = serverStatus,
            // 阶段 8B：资源缓存由 Hilt 提供单例并与 Review 会话仓库共享；测试里单独构造即可
            resources = V2ServerResourceCache(),
        )
    }

    /** 最近一次构造的 ViewModel（等待失败时输出诊断信息）。 */
    private var lastVm: V2HomeViewModel? = null

    private fun buildViewModel(): V2HomeViewModel = V2HomeViewModel(
        repository = V2MediaRepositoryRouter(
            demo = demoRepository,
            server = repository,
            modeStore = modeStore,
            statusStore = serverStatus,
        ),
        searchHistory = SearchHistoryStore(context),
        modeStore = modeStore,
        bootstrap = V2ServerSessionBootstrap(profileStore, tokenProvider),
        healthMonitor = V2ServerHealthMonitor(profileStore, apiFactory, serverStatus),
        statusStore = serverStatus,
        pairingRepository = PairingRepository(profileStore, tokenProvider, apiFactory),
    ).also { lastVm = it }

    @Test
    fun serverModeLoadsPagedMediaFoldersAndRealCovers() {
        // 先在 Repository 层做确定性断言（不依赖 ViewModel 调度）
        val folders = runBlocking { repository.folders() }
        assertEquals(2, folders.size)
        val page1 = runBlocking { repository.mediaPage(com.mediareview.app.feature.v2.model.V2MediaQuery(page = 1, pageSize = 50)) }
        assertEquals(50, page1.items.size)
        assertEquals(60, page1.total)
        assertTrue(page1.hasMore)

        // 封面是服务器下发的绝对地址（经 MediaUrlResolver 解析，UI 不拼 IP）
        val video = page1.items.first { it.isVideo }
        val cover = repository.coverUri(video)
        assertTrue("封面应为服务器地址，实际 $cover", cover.startsWith("$baseUrl/api/v1/media/"))

        // Coil 真实加载封面（携带 Bearer token，服务端要求认证）
        val coverResult = runBlocking {
            imageLoader.execute(
                ImageRequest.Builder(context).data(cover).allowHardware(false).build(),
            )
        }
        assertTrue("封面必须真实加载成功：$coverResult", coverResult is coil.request.SuccessResult)
        assertNotNull(repository.mediaById(video.id))

        // ViewModel（真实 UI 路径）：分页第二页由滚动加载
        val vm = buildViewModel()
        awaitOrFail(60_000, "ViewModel 应加载服务器媒体列表") { vm.currentList.value.isNotEmpty() }
        assertEquals(50, vm.currentList.value.size)
        assertEquals(60, vm.totalCount.value)
        assertTrue(vm.hasMore.value)

        // 文件夹来自服务端聚合（含图片数），App 不做 N+1
        assertEquals(30, vm.folderCount("f_photos"))
        assertEquals(30, vm.folderCount("f_videos"))

        vm.loadNextPage()
        awaitOrFail(30_000, "第二页应加载完成") { vm.currentList.value.size == 60 }
        assertFalse(vm.hasMore.value)
    }

    @Test
    fun serverModeImageOriginalAndPlaybackHeadersAreReal() {
        val page = runBlocking { repository.mediaPage(com.mediareview.app.feature.v2.model.V2MediaQuery(page = 1, pageSize = 50)) }
        val image = page.items.first { !it.isVideo && it.folderId == "f_photos" }
        val original = repository.imageUri(image)
        assertTrue(original.endsWith("/original"))

        val originalResult = runBlocking {
            imageLoader.execute(
                ImageRequest.Builder(context).data(original).allowHardware(false).build(),
            )
        }
        assertTrue(
            "原图必须真实加载成功：$originalResult",
            originalResult is coil.request.SuccessResult,
        )

        // 播放源：Direct + 设备级 header + 唯一一次 HLS
        val videoId = page.items.first { it.isVideo }.id
        val source = runBlocking { repository.resolvePlayback(videoId) }
        assertTrue(source.direct.url.startsWith("$baseUrl/stream/"))
        assertEquals("mock-device-stream-key", source.direct.headers["X-Emby-Token"])
        assertNotNull(source.fallbackHls)

        // 真实 HTTP：带 headers 才能取到视频流（服务端缺 header 会 403）
        val client = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build()
        client.newCall(
            Request.Builder()
                .url(source.direct.url)
                .header("Range", "bytes=0-1023")
                .apply { source.direct.headers.forEach { (k, v) -> header(k, v) } }
                .build(),
        ).execute().use { response ->
            assertTrue("带设备 header 的视频流请求应成功，实际 ${response.code}", response.isSuccessful)
        }
        client.newCall(Request.Builder().url(source.direct.url).build()).execute().use { response ->
            assertEquals("缺少设备 header 必须被拒绝", 403, response.code)
        }
    }

    @Test
    fun serverModeFavoriteAndMediaWallRender() {
        val vm = buildViewModel()
        awaitOrFail(60_000, "ViewModel 应加载服务器媒体列表") { vm.currentList.value.isNotEmpty() }

        // 收藏：服务器成功才确认（走 ViewModel，与真实按钮路径一致）
        val target = vm.currentList.value.first { it.isVideo }.id
        vm.setFavorite(target, true)
        awaitOrFail(30_000, "收藏成功后本地状态应更新") { vm.favorites.value.any { it.id == target } }

        // 媒体墙渲染服务器数据（HomeScreen = 媒体墙 + 数据源状态条）
        val title = vm.currentList.value.first().name
        compose.setContent {
            MediaReviewTheme {
                HomeScreen(
                    vm = vm,
                    onOpenMedia = {},
                    onOpenFolder = {},
                    onOpenAlbum = {},
                    onOpenDataSource = {},
                )
            }
        }
        compose.waitUntil(60_000) {
            compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(title).assertExists()
        // Server 模式顶部状态条（在线）
        compose.onNodeWithText("· 切换数据源").assertExists()
    }

    @Test
    fun folderPickerFiltersServerSide() {
        val vm = buildViewModel()
        awaitOrFail(60_000, "ViewModel 应加载服务器媒体列表") { vm.currentList.value.isNotEmpty() }

        // 点文件夹胶囊 → 由服务端 folder_id 过滤（不是本地过滤）
        vm.selectFolder("f_photos")
        awaitOrFail(30_000, "服务端应返回 f_photos 下的图片") {
            vm.currentList.value.isNotEmpty() && vm.currentList.value.all { it.folderId == "f_photos" }
        }
        assertEquals(30, vm.totalCount.value)
        assertTrue(vm.currentList.value.all { !it.isVideo })

        // 搜索：也由服务端执行
        vm.setSearchMode(true)
        vm.updateSearchQuery("照片 0")
        awaitOrFail(30_000, "服务端应返回搜索命中的图片") { vm.currentList.value.isNotEmpty() }
        assertTrue(vm.currentList.value.all { it.name.contains("照片") })
    }

    /** 轮询等待（带诊断信息），避免只看到无信息的超时。 */
    private fun awaitOrFail(
        timeoutMs: Long,
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        val vm = lastVm
        val detail = if (vm == null) {
            "（无 ViewModel 引用）"
        } else {
            "当前列表=${vm.currentList.value.size} 总数=${vm.totalCount.value} " +
                "loading=${vm.listLoading.value} 初始化=${vm.initializing.value} " +
                "错误=${vm.listError.value} 状态=${vm.serverStatus.value} 模式=${vm.dataMode.value}"
        }
        throw AssertionError("等待超时：$what；$detail")
    }
}