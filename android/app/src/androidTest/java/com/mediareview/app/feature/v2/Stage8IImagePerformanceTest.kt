package com.mediareview.app.feature.v2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.imageLoader
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.feature.v2.data.*
import com.mediareview.app.feature.v2.home.*
import com.mediareview.app.feature.v2.model.*
import com.mediareview.app.feature.v2.viewer.V2ImageViewer
import com.mediareview.app.ui.theme.MediaReviewTheme
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** 此类只引用 Alpha11 已有入口；同一 Test APK 可对两版 Main APK 运行。 */
class Stage8IImagePerformanceTest {
    @get:Rule val compose = createAndroidComposeRule<HiltTestActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var fixture: ImageBenchmarkFixture
    private lateinit var previousLoader: ImageLoader
    private lateinit var loader: ImageLoader
    private lateinit var photos: List<V2Media>
    private lateinit var vm: V2HomeViewModel
    private var mode by mutableStateOf(HomeDisplayMode.TWO_COLUMN)
    private var shelf by mutableStateOf(false)
    private var viewer by mutableStateOf(false)
    private var realHome by mutableStateOf(false)
    private var realAlbum by mutableStateOf(false)
    private var coldStarted = 0L
    private val activeOriginals = AtomicInteger()
    private val maxOriginals = AtomicInteger()
    private val maxDecodedWidth = AtomicInteger()
    private val maxDecodedHeight = AtomicInteger()
    private var previousMode = V2DataMode.DEMO
    private var previousDisplay = HomeDisplayMode.TWO_COLUMN
    private val evidence by lazy { File(context.getExternalFilesDir(null), "stage8i-images/${SystemClock.uptimeMillis()}").apply { mkdirs() } }

    @Before fun setup() {
        runBlocking { V2DataModeStore(context).let { previousMode = it.current(); it.set(V2DataMode.DEMO) } }
        runBlocking { HomeDisplayModeStore(context).let { it.bootstrap(); previousDisplay = it.mode.value; it.set(HomeDisplayMode.TWO_COLUMN) } }
        fixture = ImageBenchmarkFixture()
        previousLoader = context.imageLoader
        val tokenProvider = com.mediareview.app.core.network.TokenProvider().apply {
            set("fixture-auth", fixture.url("p1", "thumbnail").substringBefore("/api/v1/"))
        }
        val http = okhttp3.OkHttpClient.Builder()
            .addInterceptor(com.mediareview.app.core.network.CacheAuthInterceptor(tokenProvider))
            .eventListenerFactory { object : okhttp3.EventListener() {
            private var original = false
            private val ended = AtomicBoolean()
            override fun callStart(call: okhttp3.Call) {
                original = call.request().url.encodedPath.endsWith("/original")
                if (original) {
                    val active = activeOriginals.incrementAndGet()
                    maxOriginals.accumulateAndGet(active, ::maxOf)
                    android.util.Log.i("MR_IMAGE_HTTP", "start ${call.request().url.encodedPath} active=$active at=${SystemClock.elapsedRealtime()}")
                }
            }
            private fun finish(call: okhttp3.Call, outcome: String) {
                if (original && ended.compareAndSet(false, true)) {
                    val active = activeOriginals.decrementAndGet()
                    android.util.Log.i("MR_IMAGE_HTTP", "$outcome ${call.request().url.encodedPath} active=$active at=${SystemClock.elapsedRealtime()}")
                }
            }
            override fun callEnd(call: okhttp3.Call) = finish(call, "end")
            override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) = finish(call, "failed")
        } }.build()
        loader = ImageLoader.Builder(context).okHttpClient(http).eventListener(object : coil.EventListener {
            override fun onSuccess(request: coil.request.ImageRequest, result: coil.request.SuccessResult) {
                if (request.data.toString().substringBefore('?').endsWith("/original") && result.drawable.intrinsicWidth > 8) {
                    maxDecodedWidth.updateAndGet { maxOf(it, result.drawable.intrinsicWidth) }
                    maxDecodedHeight.updateAndGet { maxOf(it, result.drawable.intrinsicHeight) }
                }
            }
        }).build()
        Coil.setImageLoader(loader)
        val demo = DemoMediaRepository(context, AlbumCoverStore(context))
        val photo = runBlocking { demo.media() }.first { !it.isVideo }
        photos = (1..100).map { photo.copy(id = "p$it", name = "照片 $it", naturalWidth = 2560, naturalHeight = 1920) }
        val repository = object : MediaRepository by demo {
            override suspend fun mediaPage(query: V2MediaQuery) = V2MediaPage(photos, query.page, query.pageSize, 100)
            override fun mediaById(id: String) = photos.firstOrNull { it.id == id }
            override fun coverUri(media: V2Media) = fixture.url(media.id, "thumbnail")
            override fun imageUri(media: V2Media) = fixture.url(media.id, "original")
            override suspend fun folders() = emptyList<V2Folder>()
            override suspend fun albums() = listOf(V2Album("fixture-album", "f", "全部照片", 100, "p1", fixture.url("p1", "thumbnail")))
            override suspend fun albumPage(albumId: String, page: Int, pageSize: Int, spec: V2SortSpec) =
                V2MediaPage(photos.drop((page - 1) * pageSize).take(pageSize), page, pageSize, 100)
            override suspend fun pendingDeleteIds() = emptySet<String>()
        }
        vm = testHomeViewModel(repository, SearchHistoryStore(context))
        coldStarted = SystemClock.elapsedRealtime()
        compose.setContent { MediaReviewTheme {
            when {
                viewer -> V2ImageViewer(vm, "p50", { viewer = false })
                realAlbum -> AlbumScreen(vm, "fixture-album", { realAlbum = false }, { _, _, _, _, _ -> })
                realHome -> HomeScreen(vm, {}, { _, _ -> }, { realAlbum = true }, {})
                shelf -> ShelfGrid(photos.map { V2Album(it.id, "f", it.name, 1, it.id, repository.coverUri(it)) }, { id -> photos.find { it.id == id }?.let(repository::coverUri) }, {})
                else -> LazyVerticalGrid(columns = GridCells.Fixed(mode.columns), modifier = Modifier.testTag("hundred_images")) {
                    items(photos, key = { it.id }) { media -> MediaCard(media, repository.coverUri(media), {}, displayMode = mode) }
                }
            }
        } }
        compose.waitUntil(10_000) { vm.currentList.value.isNotEmpty() }
    }
    @After fun close() {
        Coil.setImageLoader(previousLoader)
        loader.shutdown()
        fixture.close()
        runBlocking { V2DataModeStore(context).set(previousMode) }
        runBlocking { HomeDisplayModeStore(context).set(previousDisplay) }
    }

    @Test fun sameHundredImagesColdHotAndViewer() {
        compose.waitUntil(15_000) { fixture.hits.any { it.path.endsWith("/thumbnail") } }
        waitGreen("照片 1")
        val coldMs = SystemClock.elapsedRealtime() - coldStarted
        // 全部100张均实际滚入可见窗口，不能把100缩成首屏。
        for (index in 0 until 100) {
            compose.onNodeWithTag("hundred_images").performScrollToIndex(index)
            waitGreen("照片 ${index + 1}")
        }
        compose.onNodeWithTag("hundred_images").performScrollToIndex(99)
        waitGreen("照片 100")
        assertEquals(100, fixture.hits.filter { it.path.endsWith("/thumbnail") }.map { it.path }.distinct().size)
        assertTrue(fixture.hits.all { it.authenticated && it.version == "fixture1" })
        assertEquals(0, fixture.hits.count { it.path.endsWith("/original") })
        val coldBytes = fixture.hits.sumOf { it.bytes }
        val hotStart = SystemClock.elapsedRealtime()
        compose.onNodeWithTag("hundred_images").performScrollToIndex(0)
        waitGreen("照片 1")
        val hotMs = SystemClock.elapsedRealtime() - hotStart
        val hotBytes = fixture.hits.sumOf { it.bytes } - coldBytes
        val viewerStart = SystemClock.elapsedRealtime()
        compose.runOnIdle { viewer = true }
        compose.waitUntil(15_000) {
            val pixels = compose.onNodeWithTag("viewer_pager").captureToImage().toPixelMap()
            pixels[pixels.width / 2, pixels.height / 2].let { it.green > .65f && it.red < .3f }
        }
        val previewMs = SystemClock.elapsedRealtime() - viewerStart
        compose.waitUntil(15_000) { fixture.hits.any { it.path.contains("/p50/original") } }
        compose.waitUntil(15_000) {
            val pixels = compose.onNodeWithTag("viewer_pager").captureToImage().toPixelMap()
            pixels[pixels.width / 2, pixels.height / 2].let { it.red > .8f && it.blue < .2f }
        }
        val originalReadyMs = SystemClock.elapsedRealtime() - viewerStart
        // 邻接未发生的Alpha11忠实记NOT_PRESENT，性能对比不替老版伪造事件。
        SystemClock.sleep(1000)
        val neighbors = fixture.hits.filter { it.path.endsWith("/original") && !it.path.contains("/p50/") }
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        File(evidence, "performance.txt").writeText("version=${pkg.versionName}\ncold_first_pixels_ms=$coldMs\ncold_payload_bytes=$coldBytes\nhot_first_pixels_ms=$hotMs\nhot_payload_bytes=$hotBytes\npreview_visible_pixels_ms=$previewMs\ncurrent_original_pixels_ms=$originalReadyMs\nneighbor_prefetch=${if (neighbors.isEmpty()) "NOT_PRESENT" else neighbors.joinToString { it.path }}\noriginal_http=${fixture.hits.filter { it.path.endsWith("/original") }}\n")
        File(evidence, "http.txt").writeText(fixture.hits.joinToString("\n"))
    }

    /** 新合同单独运行；性能方法本身不强迫旧版profile通过。 */
    @Test fun threeLayoutsUseTheirProfilesAndNoOriginal() {
        waitGreen("照片 1")
        assertTrue(fixture.hits.filter { it.path.endsWith("/thumbnail") }.all { it.profile == "GRID_SMALL" })
        fixture.hits.clear()
        compose.runOnIdle { mode = HomeDisplayMode.ONE_COLUMN }
        compose.waitUntil(10_000) { fixture.hits.any { it.profile == "GRID_LARGE" } }
        assertTrue(fixture.hits.filter { it.path.endsWith("/thumbnail") }.all { it.profile == "GRID_LARGE" })
        fixture.hits.clear()
        compose.runOnIdle { shelf = true }
        compose.waitUntil(10_000) { fixture.hits.any { it.profile == "ALBUM" } }
        assertTrue(fixture.hits.filter { it.path.endsWith("/thumbnail") }.all { it.profile == "ALBUM" })
        assertEquals(0, fixture.hits.count { it.path.endsWith("/original") })
    }

    @Test fun viewerCurrentBeforeNeighborsOnlyImmediateSources() {
        compose.runOnIdle { viewer = true }
        compose.waitUntil(15_000) { fixture.hits.count { it.path.endsWith("/original") } >= 3 }
        val originals = fixture.hits.filter { it.path.endsWith("/original") }
        assertEquals("/api/v1/media/p50/original", originals.first().path)
        assertEquals(setOf("/api/v1/media/p50/original", "/api/v1/media/p51/original", "/api/v1/media/p49/original"), originals.map { it.path }.toSet())
        compose.onNodeWithTag("viewer_pager").performTouchInput { swipeLeft() }
        compose.waitUntil(15_000) { fixture.hits.any { it.path == "/api/v1/media/p52/original" } }
        // p51源预取成功后成为current不应再次下载（真正viewport解码仍有红像素）。
        assertEquals(1, fixture.hits.count { it.path == "/api/v1/media/p51/original" })
        val pixels = compose.onNodeWithTag("viewer_pager").captureToImage().toPixelMap()
        assertTrue(pixels[pixels.width / 2, pixels.height / 2].red > .8f)
        val bounds = compose.onNodeWithTag("viewer_pager").fetchSemanticsNode().boundsInRoot
        assertTrue("实际解码宽${maxDecodedWidth.get()}不能超viewport${bounds.width}", maxDecodedWidth.get() <= minOf(2048, bounds.width.toInt() + 1))
        assertTrue("实际解码高不能超viewport", maxDecodedHeight.get() <= minOf(2048, bounds.height.toInt() + 1))
        assertFalse(fixture.hits.any { it.path == "/api/v1/media/p53/original" })
    }

    @Test fun rapidPagingCancelsTheOldNeighborQueue() {
        fixture.slowOriginalId = "p51"
        compose.runOnIdle { viewer = true }
        compose.waitUntil(15_000) { fixture.hits.any { it.path == "/api/v1/media/p51/original" } }
        repeat(3) {
            compose.onNodeWithTag("viewer_pager").performTouchInput { swipeLeft() }
            compose.waitForIdle()
        }
        compose.waitUntil(15_000) { fixture.hits.any { it.path == "/api/v1/media/p54/original" } }
        // p51旧source被取消，其串行队列不能继续下载已经远离current的p49。
        assertFalse(fixture.hits.any { it.path == "/api/v1/media/p49/original" })
        assertFalse(fixture.hits.any { it.path == "/api/v1/media/p55/original" })
        assertTrue("原图HTTP并发必须有界，实际${maxOriginals.get()}", maxOriginals.get() <= 2)
        compose.runOnIdle { viewer = false }
        compose.waitUntil(5000) { activeOriginals.get() == 0 }
        File(evidence, "rapid-cancel-http.txt").writeText(fixture.hits.joinToString("\n"))
    }

    @Test fun actualHomeAndAlbumUseTheSameProfileAsWindowPrefetch() {
        compose.runOnIdle { realHome = true }
        waitGreen("照片 1")
        compose.waitUntil(10_000) { fixture.hits.any { it.profile == "ALBUM" } }
        assertTrue(fixture.hits.filter { it.path.contains("/p2/") }.all { it.profile == "GRID_SMALL" })
        compose.runOnIdle { vm.setTab(com.mediareview.app.feature.v2.home.V2HomeTab.SHELF) }
        compose.onNodeWithText("全部照片").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("album_grid").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("album_grid").performScrollToIndex(20)
        waitGreen("照片 21")
        assertTrue(fixture.hits.filter { it.path == "/api/v1/media/p21/thumbnail" }.all { it.profile == "GRID_SMALL" })
        assertTrue(fixture.hits.all { it.authenticated && it.version == "fixture1" })
        assertEquals(0, fixture.hits.count { it.path.endsWith("/original") })
        File(evidence, "production-home-album-http.txt").writeText(fixture.hits.joinToString("\n"))
    }

    @Test fun samePageNeighborSourceChangeCancelsPreviousTransport() {
        fixture.slowOriginalId = "p51"
        compose.runOnIdle { viewer = true }
        compose.waitUntil(15_000) { fixture.hits.any { it.path == "/api/v1/media/p51/original" } }
        fixture.nextOriginalVersion = "fixture2"
        // 当前仍p50；真实单击重组，使邻p51源URL改变，重启同页预取窗口。
        compose.onNodeWithTag("viewer_pager").performTouchInput { click() }
        compose.waitUntil(10_000) { fixture.hits.any { it.path == "/api/v1/media/p51/original" && it.version == "fixture2" } }
        compose.waitUntil(1500) { activeOriginals.get() <= 1 }
        assertEquals("邻源更新不能重载当前原图", 1, fixture.hits.count { it.path == "/api/v1/media/p50/original" })
        compose.runOnIdle { viewer = false }
        compose.waitUntil(1500) { activeOriginals.get() == 0 }
        File(evidence, "same-page-neighbor-cancel-http.txt").writeText(fixture.hits.joinToString("\n"))
    }

    @Test fun oneColumnCachedMemoryPreviewSurvivesDelayedSmallAndOriginal() = cachedLargePreview(false)
    @Test fun oneColumnCachedDiskPreviewSurvivesDelayedSmallAndOriginal() = cachedLargePreview(true)

    private fun cachedLargePreview(diskOnly: Boolean) {
        compose.runOnIdle { mode = HomeDisplayMode.ONE_COLUMN }
        compose.onNodeWithTag("hundred_images").performScrollToIndex(49)
        waitGreen("照片 50")
        compose.waitUntil(10_000) { fixture.hits.any { it.path == "/api/v1/media/p50/thumbnail" && it.profile == "GRID_LARGE" } }
        val largeHits = fixture.hits.count { it.path == "/api/v1/media/p50/thumbnail" && it.profile == "GRID_LARGE" }
        if (diskOnly) loader.memoryCache?.clear()
        fixture.slowThumbnailProfile = "GRID_SMALL"
        fixture.slowOriginalId = "p50"
        val start = SystemClock.elapsedRealtime()
        compose.runOnIdle { viewer = true }
        compose.waitUntil(1000) {
            runCatching {
                val pixels = compose.onNodeWithTag("viewer_pager").captureToImage().toPixelMap()
                pixels[pixels.width / 2, pixels.height / 2].let { it.green > .65f && it.red < .3f }
            }.getOrDefault(false)
        }
        assertEquals("只复用Large缓存，不另下载Large", largeHits, fixture.hits.count { it.path == "/api/v1/media/p50/thumbnail" && it.profile == "GRID_LARGE" })
        File(evidence, "one-column-${if (diskOnly) "disk" else "memory"}-preview.txt").writeText("green_preview_pixels_ms=${SystemClock.elapsedRealtime() - start}\nsmall_body_delay_ms=5000\noriginal_body_delay_ms=5000\nlarge_http_before=$largeHits\nlarge_http_after=$largeHits\n")
        compose.runOnIdle { viewer = false }
        compose.waitUntil(5000) { activeOriginals.get() == 0 }
    }

    private fun waitGreen(name: String) {
        compose.waitUntil(15_000) {
            runCatching {
                val pixels = compose.onNodeWithContentDescription(name, useUnmergedTree = true).captureToImage().toPixelMap()
                pixels[pixels.width / 2, pixels.height / 2].let { it.green > .65f && it.red < .3f }
            }.getOrDefault(false)
        }
    }
}

/** 旧默认q80与新profile q60/65使用同一JPEG生成器，统计真实响应字节。 */
internal class ImageBenchmarkFixture : AutoCloseable {
    data class Hit(val path: String, val profile: String?, val version: String?, val bytes: Int, val atMs: Long, val authenticated: Boolean)
    val hits = CopyOnWriteArrayList<Hit>()
    @Volatile var slowOriginalId: String? = null
    @Volatile var nextOriginalVersion: String = "fixture1"
    @Volatile var slowThumbnailProfile: String? = null
    private val server = MockWebServer()
    private val variants = listOf(null, "GRID_SMALL", "GRID_LARGE", "ALBUM", "original").associateWith { profile ->
        val (width, height, quality) = when (profile) {
            "GRID_SMALL" -> Triple(256, 144, 60)
            "GRID_LARGE" -> Triple(480, 270, 65)
            "ALBUM" -> Triple(320, 427, 65)
            "original" -> Triple(2560, 1920, 95)
            else -> Triple(480, 270, 80)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // 中心绿/红供真实像素验证，四周确定性纹理供payload比较。
        for (y in 0 until height) for (x in 0 until width) {
            val center = x in width / 3..width * 2 / 3 && y in height / 3..height * 2 / 3
            bitmap.setPixel(x, y, if (center) (if (profile == "original") Color.RED else Color.GREEN)
                else Color.rgb((x * 17 + y * 13) % 256, (x * 7 + y * 19) % 256, (x * 23 + y * 3) % 256))
        }
        ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output); bitmap.recycle(); output.toByteArray() }
    }
    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val bytes = variants[if (url.encodedPath.endsWith("/original")) "original" else url.queryParameter("profile")]!!
                val authenticated = request.getHeader("Authorization") == "Bearer fixture-auth"
                hits += Hit(url.encodedPath, url.queryParameter("profile"), url.queryParameter("v"), bytes.size, SystemClock.elapsedRealtime(), authenticated)
                if (!authenticated) return MockResponse().setResponseCode(401)
                // 原图不添加缓存TTL，与生产StreamingResponse一致。
                return MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes)).apply {
                    if (url.encodedPath.endsWith("/original")) setBodyDelay(
                        if (url.encodedPath.split('/')[4] == slowOriginalId) 5000 else 300,
                        java.util.concurrent.TimeUnit.MILLISECONDS)
                    else {
                        setHeader("Cache-Control", "private, max-age=86400")
                        if (url.queryParameter("profile") == slowThumbnailProfile && slowThumbnailProfile != null)
                            setBodyDelay(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    }
                }
            }
        }
        server.start()
    }
    fun url(id: String, resource: String): String {
        val version = if (id == "p51" && resource == "original") nextOriginalVersion else "fixture1"
        return server.url("/api/v1/media/$id/$resource?v=$version").toString()
    }
    override fun close() = server.shutdown()
}
