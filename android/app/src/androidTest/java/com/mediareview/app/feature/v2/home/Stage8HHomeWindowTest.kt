package com.mediareview.app.feature.v2.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.feature.v2.data.*
import com.mediareview.app.feature.v2.model.*
import com.mediareview.app.feature.v2.testHomeViewModel
import com.mediareview.app.ui.theme.MediaReviewTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/** 真实 HomeScreen + Coil HTTP字节与pointer，关闭Task2 M1而非简化header fixture。 */
class Stage8HHomeWindowTest {
    @get:Rule val compose = createAndroidComposeRule<HiltTestActivity>()
    private lateinit var server: MockWebServer
    private lateinit var coverBase: String
    private lateinit var repo: HomeFixtureRepository
    private lateinit var vm: V2HomeViewModel
    private val coverHits = AtomicInteger()
    private val coverPaths = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val spriteHits = AtomicInteger()
    private val folderCalls = AtomicInteger()
    private val mediaRequests = java.util.concurrent.CopyOnWriteArrayList<V2MediaQuery>()
    private var activeAlbum by mutableStateOf<String?>(null)
    private var largeText by mutableStateOf(false)
    private var albumDiagnostic = "尚未采样"
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runBlocking { V2DataModeStore(context).set(V2DataMode.DEMO) }
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.GREEN) }
        val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        bitmap.recycle()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path.orEmpty().contains("sprite")) { spriteHits.incrementAndGet(); return MockResponse().setResponseCode(500) }
                    coverPaths += request.requestUrl!!.encodedPath
                    coverHits.incrementAndGet()
                    return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes))
                }
            }
            start()
        }
        // MockWebServer.url可能做反向DNS，只在测试线程解析一次。
        coverBase = server.url("/").toString().trimEnd('/')
        val demo = DemoMediaRepository(context, AlbumCoverStore(context))
        repo = HomeFixtureRepository(demo)
        vm = testHomeViewModel(repo, SearchHistoryStore(context))
        compose.setContent { MediaReviewTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText) 1.35f else density.fontScale)) {
                Box(Modifier.fillMaxHeight().width(if (largeText) 320.dp else 420.dp)) {
                    val album = activeAlbum
                    if (album == null) HomeScreen(vm, {}, { _, _ -> }, { activeAlbum = it }, {})
                    else AlbumScreen(vm, album, { activeAlbum = null }, { _, _, _, _, _ -> })
                }
            }
        } }
        compose.waitUntil(10_000) { vm.currentList.value.size == 50 && vm.categoryHint.value != null && vm.albums.value.size == 100 }
    }
    @After fun teardown() { server.shutdown() }

    @Test fun realWholeHomeHeaderLegacyHintIsVisibleAndPointerClickable() {
        val header = compose.onNodeWithTag("home_header_expanded").fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertEquals(248f * density, header.height, density)
        assertEquals(100, vm.albums.value.size)
        assertEquals(1000, vm.totalCount.value)
        assertTrue(mediaRequests.isNotEmpty() && mediaRequests.all { it.pageSize == 50 })
        assertEquals(listOf(1), mediaRequests.map { it.page })
        compose.onNodeWithTag("home_category_hint").assertIsDisplayed()
        compose.onNodeWithText("电脑端版本过旧，层级分类需要 MediaReview Server 1.3.0+").assertIsDisplayed()
        val before = folderCalls.get()
        compose.onNodeWithTag("home_category_hint").performTouchInput { click() }
        compose.waitUntil(5_000) { folderCalls.get() > before }
        compose.onNodeWithTag("home_grid").performTouchInput { swipeUp() }
        compose.onNodeWithTag("home_header_compact").assertIsDisplayed()
        compose.onNodeWithTag("home_grid").performTouchInput { swipeDown() }
        compose.onNodeWithTag("home_category_hint").assertIsDisplayed()
        Log.i("Stage8HHome", "WHOLE_HOME headerPx=${header.height} legacyHintPointerClick=PASS")
    }
    @Test fun wholeHomeLegacyHintRemainsVisibleClickableAt320DpAndLargeFont() {
        compose.runOnIdle { largeText = true }
        compose.waitForIdle()
        val header = compose.onNodeWithTag("home_header_expanded").fetchSemanticsNode().boundsInRoot
        val hint = compose.onNodeWithTag("home_category_hint").fetchSemanticsNode().boundsInRoot
        Log.i("Stage8HHome", "LARGE_FONT_BEFORE_ASSERT header=$header hint=$hint")
        val beforeAssert = java.io.File(compose.activity.getExternalFilesDir(null), "stage8h-home-header-large-font-before-assert.png")
        beforeAssert.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithTag("home_category_hint").assertIsDisplayed()
        assertTrue("旧服务提示不能溢出固定顶部区域：hint=$hint header=$header", hint.bottom <= header.bottom + 1f)
        val before = folderCalls.get()
        compose.onNodeWithTag("home_category_hint").performTouchInput { click() }
        compose.waitUntil(5_000) { folderCalls.get() > before }
        val output = java.io.File(compose.activity.getExternalFilesDir(null), "stage8h-home-header-large-font.png")
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i("Stage8HHome", "WHOLE_HOME fontScale=1.35 widthDp=320 header=$header hint=$hint screenshot=${output.name}")
    }

    @Test fun metadataAlbumCoverActuallyDecodesWithoutMediaCache() {
        assertNull(repo.mediaById("outside-window-0"))
        compose.onNodeWithText("书架").performClick()
        try {
            compose.waitUntil(10_000) { coverHits.get() > 0 && albumGreen() }
        } finally {
            Log.i("Stage8HHome", "ALBUM_PIXEL_DIAGNOSTIC hits=${coverHits.get()} $albumDiagnostic")
            val output = java.io.File(compose.activity.getExternalFilesDir(null), "stage8h-album-pixel-diagnostic.png")
            output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithText("冷缓存相册0").assertIsDisplayed()
        assertTrue(albumGreen())
        Log.i("Stage8HHome", "DIRECT_ALBUM_COVER pixelGreen=PASS cacheMiss=PASS")
    }
    @Test fun metadataAndFirstPageWarmOnlyFirstAndNextScreenWithoutScrollIdle() {
        // 最后一对请求并发发出，15完成不能代表14已到达；等待整批而非某一末项。
        compose.waitUntil(10_000) {
            coverPaths.filter { it.startsWith("/cover/") }.distinct().size >= 16 &&
                coverPaths.filter { it.startsWith("/album-cover/") }.distinct().size >= 16
        }
        val mediaCovers = coverPaths.filter { it.startsWith("/cover/") }.distinct()
        val albums = coverPaths.filter { it.startsWith("/album-cover/") }.distinct()
        assertEquals(16, mediaCovers.size)
        assertEquals(16, albums.size)
        assertEquals(listOf(1), mediaRequests.map { it.page })
        assertEquals(50, vm.currentList.value.size)
        assertEquals(100, vm.albums.value.size)
        Log.i("Stage8HHome", "METADATA_PREFETCH media=16 albums=16 noScroll page=1 size=50 total=1000")
    }
    @Test fun realHomeLongPressProducesZeroSpriteHttp() {
        compose.waitUntil(10_000) { coverHits.get() > 0 }
        compose.onNodeWithTag("media_v0").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(0, spriteHits.get())
        compose.onNodeWithText("预览准备中…").assertDoesNotExist()
        compose.onNodeWithText("预览生成失败").assertDoesNotExist()
        Log.i("Stage8HHome", "HOME_LONGPRESS spriteHTTP=0")
    }
    @Test fun cachedAlbumCardsReturnImmediatelyDuringThreeSecondRefreshAndSurviveFailure() {
        compose.onNodeWithText("书架").performClick()
        compose.onNodeWithText("冷缓存相册0").performClick()
        compose.waitUntil(10_000) { vm.albumState.value?.items?.size == 50 }
        compose.onNodeWithContentDescription("照片1").assertIsDisplayed()
        compose.runOnIdle { activeAlbum = null }
        compose.waitForIdle()
        repo.slowAlbum = true; repo.failAlbum = true
        val enteredAt = android.os.SystemClock.uptimeMillis()
        compose.onNodeWithText("冷缓存相册0").performClick()
        compose.onNodeWithContentDescription("照片1").assertIsDisplayed()
        assertTrue("返回不能等待3秒网络", android.os.SystemClock.uptimeMillis() - enteredAt < 2_500)
        compose.onNodeWithTag("window_skeleton").assertDoesNotExist()
        compose.waitUntil(6_000) { vm.albumState.value?.moreError != null }
        compose.onNodeWithContentDescription("照片1").assertIsDisplayed()
        assertEquals(50, vm.albumState.value!!.items.size)
        Log.i("Stage8HHome", "CACHED_ALBUM delay3000 immediateCards=PASS failureRetains=PASS")
    }

    @Test fun libraryFinalDeleteAndExplicitRefreshInvalidateClosedAlbumWindows() {
        val invalidations = listOf<() -> Unit>({ vm.refreshAfterLibraryChange() }, { vm.refreshAfterFinalDelete(listOf("p1")) }, { vm.retryList() })
        for (invalidate in invalidations) {
            repo.slowAlbum = false
            var albumWindowId = 0L
            compose.runOnIdle { albumWindowId = vm.openAlbum("a0", V2SortSpec(typeFilter = V2TypeFilter.IMAGE)) }
            compose.waitUntil(5_000) { vm.albumState.value?.items?.size == 50 }
            compose.runOnIdle { vm.closeAlbum(albumWindowId) }
            repo.slowAlbum = true
            compose.runOnIdle {
                invalidate()
                albumWindowId = vm.openAlbum("a0", V2SortSpec(typeFilter = V2TypeFilter.IMAGE))
                assertTrue(vm.albumState.value!!.items.isEmpty())
                assertEquals(com.mediareview.app.feature.v2.viewer.state.AlbumLoadStatus.Loading, vm.albumState.value!!.status)
                vm.closeAlbum(albumWindowId)
            }
        }
        Log.i("Stage8HHome", "LIBRARY_FINALDELETE_REFRESH closedAlbumCacheInvalidation=PASS")
    }

    @Test fun closingHomeCancelsInFlightNextPage() {
        repo.blockNextPage = true
        compose.runOnIdle { vm.loadNextPage() }
        compose.waitUntil(5_000) { repo.nextPageStarted.isCompleted }
        compose.runOnIdle { vm.suspendHomeWindow() }
        compose.waitUntil(2_000) { repo.nextPageCancelled.isCompleted }
        assertEquals(50, vm.currentList.value.size)
    }

    @Test fun lateNextPageUsesConfirmedFavoriteAndPersistsItInWindow() {
        repo.blockNextPage = true
        compose.runOnIdle { vm.loadNextPage() }
        compose.waitUntil(5_000) { repo.nextPageStarted.isCompleted }
        compose.runOnIdle { vm.setFavorite("v50", true) }
        compose.waitUntil(5_000) { repo.favoriteConfirmed.isCompleted }
        // Let the confirmation coroutine publish its local override before releasing the old response.
        compose.waitForIdle()
        repo.nextPageGate.complete(Unit)
        compose.waitUntil(5_000) { vm.currentList.value.size == 100 }
        assertTrue(vm.currentList.value.single { it.id == "v50" }.isFavorite)
        compose.runOnIdle { vm.suspendHomeWindow(); vm.resumeHomeWindow() }
        assertTrue(vm.currentList.value.single { it.id == "v50" }.isFavorite)
    }
    private fun albumGreen(): Boolean = try {
        val pixels = compose.onNodeWithTag("album_cover_a0", useUnmergedTree = true).captureToImage().toPixelMap()
        val pixel = pixels[pixels.width / 2, pixels.height / 2]
        albumDiagnostic = "pixel=$pixel size=${pixels.width}x${pixels.height}"
        pixel.green > 0.8f && pixel.red < 0.1f && pixel.blue < 0.1f
    } catch (error: Throwable) {
        albumDiagnostic = "${error.javaClass.simpleName}: ${error.message}"
        false
    }

    private inner class HomeFixtureRepository(private val delegate: MediaRepository) : MediaRepository by delegate {
        private val sample = DemoMediaCatalog.buildMedia().first { it.isVideo }
        private val items = (0 until 1000).map { sample.copy(id = "v$it", name = "视频$it", isFavorite = false) }
        @Volatile var blockNextPage = false
        val nextPageStarted = CompletableDeferred<Unit>()
        val nextPageCancelled = CompletableDeferred<Unit>()
        val nextPageGate = CompletableDeferred<Unit>()
        val favoriteConfirmed = CompletableDeferred<Unit>()
        override suspend fun setFavorite(mediaId: String, favorite: Boolean): Boolean {
            favoriteConfirmed.complete(Unit)
            return true
        }
        override suspend fun mediaPage(query: V2MediaQuery): V2MediaPage {
            if (query.page == 2 && blockNextPage) {
                nextPageStarted.complete(Unit)
                try { nextPageGate.await() }
                catch (cancelled: CancellationException) { nextPageCancelled.complete(Unit); throw cancelled }
            }
            mediaRequests += query
            return V2MediaPage(items.drop((query.page - 1) * 50).take(50), query.page, 50, 1000)
        }
        override suspend fun media(): List<V2Media> = error("首页禁止全量请求")
        override suspend fun media(spec: V2SortSpec): List<V2Media> = error("首页禁止全量请求")
        @Volatile var slowAlbum = false
        @Volatile var failAlbum = false
        override suspend fun albumPage(albumId: String, page: Int, pageSize: Int, spec: V2SortSpec): V2MediaPage {
            if (slowAlbum) delay(3_000)
            if (failAlbum) error("刷新故障")
            return V2MediaPage((1..50).map { sample.copy(id = "p$it", name = "照片$it", folderId = albumId, type = V2MediaType.IMAGE) }, page, pageSize, 50)
        }
        override suspend fun folderTree(): List<V2FolderNode>? { folderCalls.incrementAndGet(); return null }
        override fun coverUri(media: V2Media) = "$coverBase/cover/${media.id}"
        override fun mediaById(id: String): V2Media? = null
        override suspend fun albums() = (0 until 100).map { V2Album("a$it", "a$it", "冷缓存相册$it", 100, "outside-window-$it", "$coverBase/album-cover/$it") }
    }
}
