package com.mediareview.app.feature.v2.player

import android.os.SystemClock
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.feature.v2.player.native.GsyNativePlayerScreen
import com.mediareview.app.feature.v2.player.native.state.PlaybackContext
import com.mediareview.app.feature.v2.player.native.state.PlaybackQueueItem
import com.mediareview.app.ui.theme.MediaReviewTheme
import com.shuyu.gsyvideoplayer.GSYVideoManager
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView
import org.junit.*
import org.junit.runner.RunWith
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer
import kotlin.math.abs

/** 真实ActivityScenario STOP/RESUME与HTTP视频的后台停声验收。 */
@RunWith(AndroidJUnit4::class)
class Stage8HFullBackgroundTest {
    @get:Rule val compose = createAndroidComposeRule<HiltTestActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var fixture: RealHttpPlaybackFixture? = null
    private lateinit var vm: V2NativePlayerViewModel
    private var wallEpoch = 0L
    private var composeEpoch = 0L
    private var foreground = true

    private fun <T> ui(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun <T> kernel(block: (GSYVideoManager) -> T) = ui { block(GSYVideoManager.instance()) }
    private fun position() = kernel { it.currentPosition }
    private fun host() = kernel { it.listener() as? GSYVideoView }
    private fun player() = kernel { it.player.mediaPlayer as IjkExo2MediaPlayer }
    private fun resetForegroundEpoch() {
        wallEpoch = SystemClock.elapsedRealtime()
        composeEpoch = compose.mainClock.currentTime
    }
    /** 仅前台按真实流逝补帧；禁止把后台累计时间补成快速虚拟时钟。 */
    private fun waitForeground(timeoutMillis: Long, condition: () -> Boolean) {
        check(foreground) { "后台禁止Compose时钟/idle/semantics" }
        val started = SystemClock.elapsedRealtime()
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val remaining = composeEpoch + now - wallEpoch - compose.mainClock.currentTime
            if (remaining > 0L) compose.mainClock.advanceTimeBy(remaining, ignoreFrameDuration = true)
            if (condition()) return
            Assert.assertTrue("前台真实等待超时: ${timeoutMillis}ms", now - started <= timeoutMillis)
            SystemClock.sleep(10)
        }
    }
    /** 后台只读真实main-thread内核；不调用compose.waitForIdle/waitUntil/advanceTime。 */
    private fun waitKernel(timeoutMillis: Long, condition: () -> Boolean) {
        val started = SystemClock.elapsedRealtime()
        while (!condition()) {
            Assert.assertTrue("真实内核等待超时: ${timeoutMillis}ms", SystemClock.elapsedRealtime() - started <= timeoutMillis)
            SystemClock.sleep(10)
        }
    }
    private fun stopActivity() {
        foreground = false
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED) // 实际onPause + onStop，不是伪标志。
        compose.activityRule.scenario.onActivity {
            Assert.assertEquals(Lifecycle.State.CREATED, it.lifecycle.currentState)
        }
    }
    private fun resumeActivity() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.activityRule.scenario.onActivity {
            Assert.assertEquals(Lifecycle.State.RESUMED, it.lifecycle.currentState)
        }
        foreground = true
        resetForegroundEpoch()
    }
    private fun showControls() {
        if (compose.onAllNodesWithTag("player_seek_bar").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("player_gesture_surface").performTouchInput { click(center) }
        }
        waitForeground(5_000) { compose.onAllNodesWithTag("player_seek_bar").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun launch(blockFirst: Boolean = false) {
        compose.mainClock.autoAdvance = false
        resetForegroundEpoch()
        val live = RealHttpPlaybackFixture(longVideo = true, blockFirstVideo = blockFirst).also { fixture = it }
        vm = V2NativePlayerViewModel(live.repository)
        ui {
            compose.activity.viewModelStore.put("stage8h-full-background", vm)
            vm.open(PlaybackContext(listOf(PlaybackQueueItem("first", "后台生命周期测试"))))
        }
        compose.setContent {
            val state by vm.state.collectAsState()
            MediaReviewTheme {
                GsyNativePlayerScreen(state, vm::moveTo, vm::onPlaybackFailed, vm::retry,
                    vm::reportProgress, {}, onPlaybackStable = vm::onPlaybackStable,
                    onReportLoadedProgress = vm::reportLoadedProgress)
            }
        }
        if (blockFirst) {
            waitForeground(15_000) { live.firstVideoRequested.count == 0L }
            Assert.assertFalse("真实视频响应阻塞时不得在播放", kernel { it.isPlaying })
        } else {
            waitForeground(60_000) { kernel { it.isPlaying && it.duration >= 59_000 && it.currentPosition > 500 } }
            val before = position()
            waitForeground(5_000) { position() > before + 400 }
        }
        Assert.assertTrue(live.videos.any { it.mediaId == "first" && it.authenticated })
        Assert.assertTrue(live.videos.all { it.mediaId == "first" && it.authenticated })
    }
    private fun seekThirty() {
        showControls()
        compose.onNodeWithTag("player_seek_bar").performTouchInput {
            down(Offset(width * .15f, centerY))
            moveTo(Offset(width * .5f, centerY))
            up()
        }
        waitForeground(10_000) { kernel { it.isPlaying && abs(it.currentPosition - 30_000) < 2_500 } }
    }
    private fun assertIdentity(expectedHost: GSYVideoView, expectedPlayer: IjkExo2MediaPlayer, url: String?) {
        Assert.assertSame(expectedHost, host())
        Assert.assertSame(expectedPlayer, player())
        Assert.assertEquals(url, kernel { expectedPlayer.dataSource })
        Assert.assertEquals("first", vm.state.value.currentMediaId)
    }
    private fun assertSilentFor(durationMillis: Long, expectedHost: GSYVideoView,
        expectedPlayer: IjkExo2MediaPlayer, url: String?, fixedPosition: Long? = null) {
        check(!foreground)
        val started = SystemClock.elapsedRealtime()
        do {
            Assert.assertFalse("STOP期间manager不得发声", kernel { it.isPlaying })
            Assert.assertFalse("STOP期间旧Exo不得发声", kernel { expectedPlayer.isPlaying })
            assertIdentity(expectedHost, expectedPlayer, url)
            fixedPosition?.let {
                Assert.assertTrue("STOP暂停位置不能推进: before=$it actual=${position()}", abs(position() - it) <= 150)
            }
            SystemClock.sleep(20)
        } while (SystemClock.elapsedRealtime() - started < durationMillis)
    }

    @Test fun explicitlyPausedAtThirtyStaysPausedAcrossRealStopAndResume() {
        launch()
        showControls()
        compose.onNodeWithContentDescription("更多").performTouchInput { click(center) }
        waitForeground(5_000) { compose.onAllNodesWithText("暂停播放").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("暂停播放").performTouchInput { click(center) }
        waitForeground(5_000) { !kernel { it.isPlaying } && host()?.currentState == GSYVideoView.CURRENT_STATE_PAUSE }
        // Establish 30 seconds after the real product pause, so menu interaction cannot advance the precondition.
        showControls()
        compose.onNodeWithTag("player_seek_bar").performTouchInput {
            down(Offset(width * .15f, centerY))
            moveTo(Offset(width * .5f, centerY))
            up()
        }
        waitForeground(10_000) {
            Assert.assertFalse("暂停后物理seek不得恢复播放", kernel { it.isPlaying })
            host()?.currentState == GSYVideoView.CURRENT_STATE_PAUSE && abs(position() - 30_000) < 2_500
        }
        val pausedHost = host() ?: error("真实host缺失")
        val pausedPlayer = player()
        val url = kernel { pausedPlayer.dataSource }
        val pausedPosition = position()
        Assert.assertTrue(abs(pausedPosition - 30_000) < 2_500)
        stopActivity()
        assertSilentFor(1_500, pausedHost, pausedPlayer, url, pausedPosition)
        resumeActivity()
        // 这条原始行为断言不得改成“允许自动恢复”。旧autoPauseResume预期会在此或下面RED。
        Assert.assertFalse("用户显式暂停后回前台不得被库onResume恢复", kernel { it.isPlaying })
        val started = SystemClock.elapsedRealtime()
        waitForeground(5_000) {
            Assert.assertFalse("回前台仍须保持用户Paused", kernel { it.isPlaying })
            Assert.assertFalse(kernel { pausedPlayer.isPlaying })
            assertIdentity(pausedHost, pausedPlayer, url)
            Assert.assertTrue("回前台用户暂停位置不能改变", abs(position() - pausedPosition) <= 150)
            host()?.currentState == GSYVideoView.CURRENT_STATE_PAUSE &&
                compose.onAllNodesWithTag("player_seek_bar").fetchSemanticsNodes().size == 1 &&
                SystemClock.elapsedRealtime() - started >= 3_500
        }
        compose.onNodeWithContentDescription("播放").assertExists()
    }

    @Test fun playingAtThirtyStopsInBackgroundAndResumesSameKernelNearSavedPosition() {
        launch()
        seekThirty()
        val playingHost = host() ?: error("真实host缺失")
        val playingPlayer = player()
        val url = kernel { playingPlayer.dataSource }
        val before = position()
        stopActivity()
        waitKernel(5_000) { !kernel { it.isPlaying } }
        val stoppedPosition = position()
        Assert.assertTrue("后台停止须在原30秒附近", abs(stoppedPosition - before) <= 500)
        assertSilentFor(1_500, playingHost, playingPlayer, url, stoppedPosition)
        resumeActivity()
        waitForeground(5_000) { kernel { it.isPlaying } }
        val resumed = position()
        Assert.assertTrue("回前台须从原position恢复，不能归零/reload", resumed >= stoppedPosition - 150 && resumed <= stoppedPosition + 1_000)
        assertIdentity(playingHost, playingPlayer, url)
        showControls()
        compose.onNodeWithTag("player_seek_bar").assertExists()
        waitForeground(5_000) { position() > resumed + 400 }
        assertIdentity(playingHost, playingPlayer, url)
        Assert.assertTrue(fixture!!.videos.all { it.mediaId == "first" && it.authenticated })
    }

    @Test fun preparingResponseReleasedWhileStoppedRemainsSilentThenResumesWithoutReload() {
        launch(blockFirst = true)
        val preparingHost = host() ?: error("Preparing真实host缺失")
        val preparingPlayer = player()
        val url = kernel { preparingPlayer.dataSource }
        Assert.assertNotNull(url)
        stopActivity()
        Assert.assertFalse(kernel { it.isPlaying })
        fixture!!.releaseFirstVideo() // 在真正STOP后释放真实HTTP响应，不伪造Prepared事件。
        waitKernel(15_000) {
            Assert.assertFalse("STOP后真实Prepared不得自动开始", kernel { it.isPlaying })
            assertIdentity(preparingHost, preparingPlayer, url)
            kernel { it.duration >= 59_000 }
        }
        assertSilentFor(2_000, preparingHost, preparingPlayer, url)
        resumeActivity()
        waitForeground(60_000) { kernel { it.isPlaying && it.duration >= 59_000 && it.currentPosition > 500 } }
        assertIdentity(preparingHost, preparingPlayer, url)
        // 前台补真实帧到Ready controls，不能直接在没有UI帧的宿主上查控件。
        showControls()
        compose.onNodeWithTag("player_seek_bar").assertExists()
        val resumed = position()
        waitForeground(5_000) { position() > resumed + 400 }
        assertIdentity(preparingHost, preparingPlayer, url)
        Assert.assertTrue(fixture!!.videos.any { it.url == url && it.mediaId == "first" && it.authenticated })
        // 同URL的Range请求是合法数据读取；无reload的事实由同host+同Exo对象+同dataSource证明。
        Assert.assertTrue(fixture!!.videos.all { it.mediaId == "first" && it.authenticated })
    }

    @After fun cleanup() {
        // 先回前台仅为失败收尾，任何release均发生在测试断言之后。
        if (!foreground) resumeActivity()
        ui { GSYVideoManager.releaseAllVideos(); compose.activity.viewModelStore.clear() }
        fixture?.close()
    }
}
