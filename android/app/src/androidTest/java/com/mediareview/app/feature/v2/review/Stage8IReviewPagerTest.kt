package com.mediareview.app.feature.v2.review

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.feature.v2.player.RealHttpPlaybackFixture
import com.mediareview.app.feature.v2.player.V2PlaybackUiState
import com.mediareview.app.feature.v2.player.KernelSeekRecorder
import com.mediareview.app.ui.theme.MediaReviewTheme
import com.shuyu.gsyvideoplayer.GSYVideoManager
import org.junit.*
import org.junit.runner.RunWith
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer
import coil.imageLoader
import coil.Coil
import coil.ImageLoader
import okhttp3.OkHttpClient
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import coil.size.Precision
import coil.decode.DataSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.util.concurrent.ConcurrentHashMap

/** Same synthetic six-item HTTP fixture can run against the frozen Alpha11 Main as a comparison. */
@RunWith(AndroidJUnit4::class)
class Stage8IReviewPagerTest {
    @get:Rule val compose = createAndroidComposeRule<HiltTestActivity>()
    private lateinit var live: RealHttpPlaybackFixture
    private lateinit var vm: V2ReviewViewModel
    private lateinit var clock: ReviewTestClock
    private var previousLoader: ImageLoader? = null
    private var ownedLoader: ImageLoader? = null
    private var ownedImageHttp: OkHttpClient? = null
    private val settledTimes = ConcurrentHashMap<Int, Long>()
    private var observation: Job? = null
    private var frameCaptureLatencyMs = 0L
    @After fun cleanup() {
        observation?.cancel()
        try {
            compose.runOnUiThread { GSYVideoManager.releaseAllVideos(); compose.activity.viewModelStore.clear() }
        } finally {
            previousLoader?.let(Coil::setImageLoader)
            ownedLoader?.shutdown()
            ownedImageHttp?.let { http ->
                http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
            }
            if (::live.isInitialized) live.close()
        }
    }
    private fun <T> kernel(block: (GSYVideoManager) -> T): T {
        var result: T? = null
        compose.runOnUiThread { result = block(GSYVideoManager.instance()) }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun page() = (vm.state.value as? V2ReviewUiState.Ready)?.absoluteCurrentIndex
    private fun body() = compose.onNodeWithTag("review_gesture_surface")
    private fun launch(paging: Boolean = false) {
        clock = ReviewTestClock(compose.mainClock)
        live = RealHttpPlaybackFixture(shortVideo = true, authenticatedApi = true, reviewItemCount = if (paging) 55 else 6,
            reviewStartIndex = if (paging) 48 else 0, blockNextReviewPage = paging)
        val appContext = compose.activity.applicationContext
        previousLoader = appContext.imageLoader
        val imageHttp = live.authenticatedImageClient().also { ownedImageHttp = it }
        val loader = ImageLoader.Builder(appContext).okHttpClient(imageHttp)
            .eventListener(object : coil.EventListener {
                override fun onError(request: ImageRequest, result: coil.request.ErrorResult) {
                    android.util.Log.i("MRTask7Evidence", "poster_request errorType=${result.throwable.javaClass.simpleName} neighborSecond=${request.data.toString().contains("/second/thumbnail")}")
                }
                override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                    if (request.data.toString().contains("/second/thumbnail")) android.util.Log.i("MRTask7Evidence", "poster_request neighborSecond=true source=${result.dataSource} decodedWidth=${result.drawable.intrinsicWidth} decodedHeight=${result.drawable.intrinsicHeight}")
                }
            }).build().also { ownedLoader = it }
        Coil.setImageLoader(loader)
        compose.runOnUiThread {
            vm = V2ReviewViewModel(live.repository, live.sessions)
            compose.activity.viewModelStore.put("stage8i-pager", vm)
            observation = CoroutineScope(Dispatchers.Main.immediate).launch {
                var previous: Int? = null
                vm.state.collect { state ->
                    val index = (state as? V2ReviewUiState.Ready)?.absoluteCurrentIndex
                    if (index != null && index != previous) settledTimes[index] = SystemClock.elapsedRealtime()
                    previous = index
                }
            }
        }
        compose.setContent { MediaReviewTheme { V2ReviewScreen(vm, {}, {}) } }
        moving(if (paging) 48 else 0)
    }
    private fun firstFrame(index: Int, evidencePrefix: String = "pager"): Pair<IjkExo2MediaPlayer, Long> {
        var player: IjkExo2MediaPlayer? = null
        var frame: ReviewFrameEvidence? = null
        var lastPage: Int? = null
        var lastReady = false
        var lastKernelPresent = false
        var lastSourceMatches = false
        var lastPlaying = false
        var captures = 0
        var lastRed = 0
        var lastGreen = 0
        var lastBlue = 0
        try {
            clock.until(60_000) {
                lastPage = page()
                val ready = vm.playback.value as? V2PlaybackUiState.Ready
                lastReady = ready != null
                lastKernelPresent = false
                lastSourceMatches = false
                lastPlaying = false
                val matches = lastPage == index && ready != null && kernel {
                    val p = it.player?.mediaPlayer as? IjkExo2MediaPlayer
                    lastKernelPresent = p != null
                    lastSourceMatches = p != null && p.dataSource == ready.endpoint.url
                    lastPlaying = it.isPlaying
                    if (lastSourceMatches && lastPlaying) player = p
                    player === p && lastSourceMatches && lastPlaying
                }
                if (!matches) false else {
                    val captured = ReviewFrameEvidence.capture("$evidencePrefix-$index-first-frame")
                    captures++
                    lastRed = captured.red; lastGreen = captured.green; lastBlue = captured.blue
                    if (captured.hasVideoColors) frame = captured
                    captured.hasVideoColors
                }
            }
        } catch (error: AssertionError) {
            if (evidencePrefix == "rapid-pager") {
                // Preserve the original timeout and RGB/source contract; record which condition failed.
                val timeoutFrame = runCatching { ReviewFrameEvidence.capture("rapid-pager-$index-timeout") }.getOrNull()
                android.util.Log.e("MRTask7Evidence", "rapid_first_frame_timeout index=$index page=$lastPage ready=$lastReady kernel=$lastKernelPresent sourceMatches=$lastSourceMatches playing=$lastPlaying rgbCaptures=$captures lastRgb=$lastRed,$lastGreen,$lastBlue timeoutRgb=${timeoutFrame?.red},${timeoutFrame?.green},${timeoutFrame?.blue}", error)
            }
            throw error
        }
        frameCaptureLatencyMs = frame!!.captureLatencyMs
        return player!! to frame!!.capturedAtMs
    }
    private fun moving(index: Int, evidencePrefix: String = "pager"): IjkExo2MediaPlayer {
        val player = firstFrame(index, evidencePrefix).first
        val baseline = kernel { it.currentPosition }
        clock.until(5000) { kernel { it.player.mediaPlayer === player && it.isPlaying && it.currentPosition > baseline + 300 } }
        return player
    }
    @Test fun fivePhysicalVerticalSwipesEachSettleExactlyOneWithOnlyCurrentKernelAudio() {
        launch()
        var previous = kernel { it.player.mediaPlayer as IjkExo2MediaPlayer }
        for (index in 1..5) {
            // Actual drag/fling; no semantics scrollToIndex/VM page calls/manual Pager scroll.
            body().performTouchInput { swipe(Offset(width * .35f, height * .8f), Offset(width * .35f, height * .2f), durationMillis = 140) }
            clock.until(30_000) { page() == index }
            clock.until(5000) { settledTimes[index] != null }
            val settledAt = settledTimes[index]!!
            val (current, frameAt) = firstFrame(index)
            val before = kernel { it.currentPosition }
            clock.until(5000) { kernel { it.player.mediaPlayer === current && it.isPlaying && it.currentPosition > before + 300 } }
            Assert.assertEquals("每次手势至多且恰好切一条", index, page())
            Assert.assertNotSame(previous, current)
            Assert.assertFalse("旧内核不得串音", kernel { previous.isPlaying })
            Assert.assertTrue("旧source必须释放", kernel { previous.dataSource.isNullOrEmpty() })
            val ready = vm.playback.value as V2PlaybackUiState.Ready
            Assert.assertEquals(ready.endpoint.url, kernel { current.dataSource })
            Assert.assertTrue("未提前启动远端player", live.videos.all { request ->
                request.mediaId in (0..index).map { when(it) { 0 -> "first"; 1 -> "second"; else -> "item$it" } }
            })
            Assert.assertTrue(live.videos.all { it.authenticated })
            android.util.Log.i("MRTask7Evidence", "swipe=$index settled=$index settled_state_event_to_first_rgb_frame_ms=${frameAt - settledAt} sampling=first_observed_rgb_wall_synced_no_latency_upper_bound screenshot_latency_ms=$frameCaptureLatencyMs sourceMatches=true oldKernelPlaying=false")
            previous = current
        }
    }
    @Test fun rapidFivePhysicalSwipesWithoutWaitingForPreparationKeepOnlyFinalAudioAndSource() {
        launch()
        val observed = mutableSetOf(kernel { it.player.mediaPlayer as IjkExo2MediaPlayer })
        for (index in 1..5) {
            body().performTouchInput { swipe(Offset(width * .35f, height * .8f), Offset(width * .35f, height * .2f), durationMillis = 120) }
            // Only settlement; do not wait for a frame, Prepared or advancing playback.
            clock.until(30_000) { page() == index }
            kernel { manager ->
                (manager.player?.mediaPlayer as? IjkExo2MediaPlayer)?.let { observed += it }
                if (manager.isPlaying) {
                    val ready = vm.playback.value as? V2PlaybackUiState.Ready
                    Assert.assertNotNull("Loading时不得有旧音频", ready)
                    Assert.assertEquals((vm.state.value as V2ReviewUiState.Ready).items[index].mediaId, ready!!.source.mediaId)
                    Assert.assertEquals(ready.endpoint.url, (manager.player.mediaPlayer as IjkExo2MediaPlayer).dataSource)
                }
            }
            Assert.assertEquals(index, page())
        }
        val final = moving(5, "rapid-pager")
        kernel { observed.filter { it !== final }.forEach { old ->
            Assert.assertFalse("快速连滑旧kernel不得串音", old.isPlaying)
            Assert.assertTrue("快速连滑旧source必须释放", old.dataSource.isNullOrEmpty())
        } }
        Assert.assertTrue(live.videos.all { it.authenticated })
        android.util.Log.i("MRTask7Evidence", "rapid_swipes=5 each_settled_one=true waited_prepared_between=false final_source_frame=true retired_kernels=${observed.count { it !== final }}")
    }
    @Test fun actualAppendResponseWhilePointerHeldPastHalfDoesNotInterruptNativeDrag() {
        launch(paging = true)
        clock.until(5000) { live.nextReviewPageRequested.count == 0L }
        body().performTouchInput {
            down(Offset(width * .35f, height * .85f))
            moveTo(Offset(width * .35f, height * .25f), delayMillis = 200)
        }
        // Real HTTP page response arrives while the original pointer is still held down.
        Assert.assertEquals(48, page())
        live.releaseNextReviewPage()
        clock.until(10_000) { (vm.state.value as? V2ReviewUiState.Ready)?.items?.size == 55 }
        body().performTouchInput { up() }
        clock.until(30_000) { page() == 49 }
        moving(49)
        Assert.assertEquals(49, page())
        Assert.assertTrue(live.apiRequests.any { it.path.endsWith("/queue") })
        android.util.Log.i("MRTask7Evidence", "append_while_pointer_down=true from=48 settled=49 actual_source_frame=true")
    }
    @Test fun stableAdjacentPosterPrefetchSeedsDisplayedSizeMemoryCacheWithoutDistantOrOriginalRequests() {
        launch()
        val url = (vm.state.value as V2ReviewUiState.Ready).items[1].coverUrl
        Assert.assertTrue("生产Resolver必须产生可用相邻poster URL", url.isNotBlank())
        val loader = compose.activity.imageLoader
        try {
            clock.until(10_000) { loader.memoryCache?.keys?.any { it.key == url } == true }
        } finally {
            android.util.Log.i("MRTask7Evidence", "poster_cache neighborUrlNonBlank=${url.isNotBlank()} neighborKeyPresent=${loader.memoryCache?.keys?.any { it.key == url }} memoryKeyCount=${loader.memoryCache?.keys?.size} thumbnailRequests=${live.apiRequests.count { it.path == "/api/v1/media/second/thumbnail" }} thumbnailAuth=${live.apiRequests.filter { it.path == "/api/v1/media/second/thumbnail" }.all { it.authenticated }}")
        }
        Assert.assertTrue(live.apiRequests.any { it.path == "/api/v1/media/second/thumbnail" && it.authenticated })
        Assert.assertFalse(live.apiRequests.any { it.path.endsWith("/original") || it.path == "/api/v1/media/item2/thumbnail" || it.path == "/api/v1/media/item2/playback" })
        val count = live.apiRequests.count { it.path == "/api/v1/media/second/thumbnail" }
        val result = runBlocking {
            loader.execute(ImageRequest.Builder(compose.activity).data(url).size(720, 1280)
                .scale(Scale.FIT).precision(Precision.INEXACT).build())
        }
        Assert.assertTrue("相邻预取必须命中展示尺寸decoded内存缓存", result is SuccessResult && result.dataSource == DataSource.MEMORY_CACHE)
        body().performTouchInput { swipe(Offset(width * .35f, height * .8f), Offset(width * .35f, height * .2f), durationMillis = 140) }
        moving(1)
        Assert.assertEquals("实际翻页展示不能重复HTTP下载poster", count, live.apiRequests.count { it.path == "/api/v1/media/second/thumbnail" })
        android.util.Log.i("MRTask7Evidence", "neighbor_poster_decoded_memory_hit=true displayed_same_size=true repeated_http=0 original=0 distant_before_swipe=0")
    }
    @Test fun horizontalSeekAndLongPressUpLockRemainExclusiveFromNativePager() {
        launch()
        val player = kernel { it.player.mediaPlayer as IjkExo2MediaPlayer }
        body().performTouchInput {
            down(Offset(width * .2f, height * .45f)); moveTo(Offset(width * .25f, height * .45f))
            moveTo(Offset(width * .3f, height * .2f)); up()
        }
        Assert.assertEquals(0, page())
        Assert.assertSame(player, kernel { it.player.mediaPlayer })
        body().performTouchInput {
            down(center); advanceEventTime(450); moveTo(center)
            advanceEventTime(50); moveTo(center.copy(y = centerY - 100 * compose.activity.resources.displayMetrics.density)); up()
        }
        clock.until(5000) { kernel { kotlin.math.abs(player.speed - 2f) < .01f } }
        Assert.assertEquals("长按上推锁2x不得翻页", 0, page())
        Assert.assertSame(player, kernel { it.player.mediaPlayer })
        Assert.assertFalse(live.videos.any { it.mediaId != "first" })
    }
    @Test fun physicalSeekBarTapAndPausedDragRemainIsolatedFromBodyTap() {
        launch()
        val player = kernel { it.player.mediaPlayer as IjkExo2MediaPlayer }
        val recorder = kernel { KernelSeekRecorder(player) }
        try {
            compose.onNodeWithTag("review_seek_bar").performTouchInput { click(Offset(width * .25f, centerY)) }
            val clickedAt = SystemClock.elapsedRealtime()
            clock.until(5000) { SystemClock.elapsedRealtime() - clickedAt > 800 }
            Assert.assertEquals(1, recorder.count)
            Assert.assertTrue(recorder.lastTargetMs?.let { it in 1400L..1600L } == true)
            Assert.assertTrue("子进度条点击不能穿透成body暂停", kernel { it.isPlaying && it.player.mediaPlayer === player })
            Assert.assertEquals(0, page())
            body().performTouchInput { click(Offset(width * .35f, height * .4f)) }
            clock.until(5000) { kernel { !it.isPlaying } }
            compose.onNodeWithTag("review_seek_bar").performTouchInput {
                down(Offset(width * .3f, centerY)); moveTo(Offset(width * .65f, centerY), delayMillis = 180); up()
            }
            val draggedAt = SystemClock.elapsedRealtime()
            clock.until(5000) { SystemClock.elapsedRealtime() - draggedAt > 800 }
            Assert.assertEquals("暂停拖动只再提交一次", 2, recorder.count)
            Assert.assertTrue(recorder.lastTargetMs?.let { it in 3800L..4000L } == true)
            Assert.assertFalse("暂停进度条拖动不能穿透成body恢复", kernel { it.isPlaying })
            Assert.assertSame(player, kernel { it.player.mediaPlayer })
            Assert.assertEquals(0, page())
            body().performTouchInput { click(Offset(width * .35f, height * .4f)) }
            clock.until(5000) { kernel { it.isPlaying } }
            Assert.assertTrue(live.videos.all { it.mediaId == "first" && it.authenticated })
            android.util.Log.i("MRTask7Evidence", "seekbar_tap_body_isolated=true paused_drag_stays_paused=true body_tap_pause_resume=true sameKernel=true seekCount=${recorder.count}")
        } finally { kernel { recorder.close() } }
    }
}
