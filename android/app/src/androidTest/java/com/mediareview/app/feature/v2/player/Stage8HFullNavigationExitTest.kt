package com.mediareview.app.feature.v2.player

import android.os.SystemClock
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.feature.v2.player.native.GsyNativePlayerScreen
import com.mediareview.app.feature.v2.player.native.state.PlaybackContext
import com.mediareview.app.feature.v2.player.native.state.PlaybackQueueItem
import com.mediareview.app.ui.theme.MediaReviewTheme
import com.shuyu.gsyvideoplayer.GSYVideoManager
import kotlinx.coroutines.Job
import org.junit.*
import org.junit.runner.RunWith
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/** 真实NavHost返回、entry-owned VM清除、退出进度POST与真实内核释放。 */
@RunWith(AndroidJUnit4::class)
class Stage8HFullNavigationExitTest {
    @get:Rule val compose = createAndroidComposeRule<HiltTestActivity>()
    private var fixture: RealHttpPlaybackFixture? = null
    private lateinit var playerVm: V2NativePlayerViewModel
    private lateinit var playerEntry: NavBackStackEntry
    private var wallEpoch = 0L
    private var composeEpoch = 0L
    private var backAt = 0L
    private data class Progress(val id: String, val position: Long, val paused: Boolean,
        val kernelPosition: Long, val elapsed: Long, val vmScopeActive: Boolean)
    private val reports = CopyOnWriteArrayList<Progress>()

    @After fun cleanup() {
        // 此处只收尾；测试必须在此之前观察导航自身导致的VM清除和内核停止。
        compose.runOnUiThread {
            GSYVideoManager.releaseAllVideos()
            compose.activity.viewModelStore.clear()
        }
        fixture?.close()
    }

    private fun <T> kernel(block: (GSYVideoManager) -> T): T {
        var result: T? = null
        compose.runOnUiThread { result = block(GSYVideoManager.instance()) }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun position() = kernel { it.currentPosition }
    private fun vmJob(): Job = playerVm.viewModelScope.coroutineContext[Job]!!
    private fun waitWallClock(timeoutMillis: Long, condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val remaining = composeEpoch + now - wallEpoch - compose.mainClock.currentTime
            if (remaining > 0L) compose.mainClock.advanceTimeBy(remaining, ignoreFrameDuration = true)
            if (condition()) return
            Assert.assertTrue("真实时间等待超时: ${timeoutMillis}ms backAt=$backAt reports=$reports posts=${fixture?.progresses}", now - start <= timeoutMillis)
            SystemClock.sleep(10)
        }
    }
    private fun showControls() {
        if (compose.onAllNodesWithTag("player_seek_bar").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("player_gesture_surface").performTouchInput { click(center) }
        }
        waitWallClock(5_000) { compose.onAllNodesWithTag("player_seek_bar").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun realNavigationExitPostsFinalPausedPositionAfterEntryScopeIsDestroyedAndStopsKernel() {
        compose.mainClock.autoAdvance = false
        wallEpoch = SystemClock.elapsedRealtime()
        composeEpoch = compose.mainClock.currentTime
        val live = RealHttpPlaybackFixture(longVideo = true).also { fixture = it }
        compose.setContent {
            MediaReviewTheme {
                val nav = rememberNavController()
                // 使用生产Navigation默认transition；不手动clear store或改变dispose次序。
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        Button(modifier = Modifier.testTag("navigation_open_player"), onClick = { nav.navigate("player") }) {
                            Text("打开测试视频")
                        }
                    }
                    composable("player") { entry ->
                        val factory = remember(entry) {
                            object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                    require(modelClass == V2NativePlayerViewModel::class.java)
                                    return V2NativePlayerViewModel(live.repository) as T
                                }
                            }
                        }
                        val vm: V2NativePlayerViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
                        playerVm = vm
                        playerEntry = entry
                        LaunchedEffect(vm) {
                            vm.open(PlaybackContext(listOf(PlaybackQueueItem("first", "真实导航退出测试"))))
                        }
                        val state by vm.state.collectAsState()
                        GsyNativePlayerScreen(
                            state = state,
                            onRequestIndex = vm::moveTo,
                            onPlaybackFailed = vm::onPlaybackFailed,
                            onRetry = vm::retry,
                            onReportProgress = { _, _ -> Assert.fail("进度必须带实际装载mediaId") },
                            onPlaybackStable = vm::onPlaybackStable,
                            onReportLoadedProgress = { id, ms, paused ->
                                reports += Progress(id, ms, paused, GSYVideoManager.instance().currentPosition,
                                    SystemClock.elapsedRealtime(), vm.viewModelScope.coroutineContext[Job]!!.isActive)
                                vm.reportLoadedProgress(id, ms, paused)
                            },
                            onBack = {
                                backAt = SystemClock.elapsedRealtime()
                                Assert.assertTrue("真实返回应pop player entry", nav.popBackStack())
                            },
                        )
                    }
                }
            }
        }
        waitWallClock(5_000) { compose.onAllNodesWithTag("navigation_open_player").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("navigation_open_player").performTouchInput { click(center) }
        waitWallClock(60_000) { kernel { it.isPlaying && it.duration >= 59_000 && it.currentPosition > 500 } }
        Assert.assertTrue(vmJob().isActive)
        showControls()
        compose.onNodeWithTag("player_seek_bar").performTouchInput { click(Offset(width * .4f, centerY)) }
        waitWallClock(10_000) { abs(position() - 24_000) < 2_500 && kernel { it.isPlaying } }
        val resumed = position()
        waitWallClock(5_000) { position() > resumed + 1_000 }
        val oldPlayer = kernel { it.player.mediaPlayer as IjkExo2MediaPlayer }
        showControls()
        val beforeExit = position()
        val reportStart = reports.size
        val postStart = live.progresses.size
        compose.onNodeWithContentDescription("返回").performTouchInput { click(center) }
        waitWallClock(10_000) {
            backAt > 0L && compose.onAllNodesWithTag("navigation_open_player").fetchSemanticsNodes().size == 1 &&
                vmJob().isCancelled && playerEntry.lifecycle.currentState == Lifecycle.State.DESTROYED
        }
        Assert.assertTrue("必须由真实导航清除entry-owned VM", vmJob().isCancelled)
        waitWallClock(5_000) { reports.drop(reportStart).any { it.paused && it.elapsed >= backAt } }
        val exited = reports.drop(reportStart).last { it.paused && it.elapsed >= backAt }
        Assert.assertEquals("first", exited.id)
        Assert.assertTrue("退出回调必须保留真实非零位置: before=$beforeExit event=$exited",
            exited.position > 20_000 && abs(exited.position - beforeExit) <= 2_500 &&
                abs(exited.position - exited.kernelPosition) <= 1_500)
        // 捕获的是生产Repository真实POST，不是人工调用repository，不保留VM到HTTP完成。
        waitWallClock(5_000) {
            live.progresses.drop(postStart).any {
                it.method == "POST" && it.mediaId == "first" && it.paused && abs(it.position - exited.position) <= 50L
            }
        }
        Assert.assertTrue("导航scope已销毁后仍须有实际退出POST: callback=$exited posts=${live.progresses.drop(postStart)}",
            live.progresses.drop(postStart).any { it.mediaId == "first" && it.paused && abs(it.position - exited.position) <= 50L })
        waitWallClock(5_000) { kernel { !it.isPlaying && !oldPlayer.isPlaying && oldPlayer.dataSource == null } }
        val silenceStart = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - silenceStart < 1_500) {
            Assert.assertFalse("退出不能继续声音", kernel { it.isPlaying })
            Assert.assertFalse("原真实内核必须停止", kernel { oldPlayer.isPlaying })
            Assert.assertNull("原内核释放后须清除source", kernel { oldPlayer.dataSource })
            SystemClock.sleep(100)
        }
        Assert.assertTrue(live.videos.isNotEmpty())
        Assert.assertTrue(live.videos.all { it.mediaId == "first" && it.authenticated })
    }
}
