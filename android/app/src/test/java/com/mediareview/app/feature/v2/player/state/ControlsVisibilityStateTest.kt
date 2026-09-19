package com.mediareview.app.feature.v2.player.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ControlsVisibilityState：播放中 3s 自动隐藏、暂停保持、交互重置计时、锁定隐藏全部。
 * 使用虚拟时间（StandardTestDispatcher / advanceTimeBy）测试延迟逻辑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlsVisibilityStateTest {

    @Test
    fun `播放中约 3 秒无操作自动隐藏`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val state = ControlsVisibilityState(CoroutineScope(dispatcher))
        state.updatePlayback(true)
        assertTrue(state.visible)
        advanceTimeBy(2_999L)
        runCurrent()
        assertTrue(state.visible)
        advanceTimeBy(1L)
        runCurrent()
        assertFalse(state.visible)
    }

    @Test
    fun `暂停时控制层保持显示不自动隐藏`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val state = ControlsVisibilityState(CoroutineScope(dispatcher))
        state.updatePlayback(true)
        advanceTimeBy(3_100L)
        runCurrent()
        assertFalse(state.visible) // 播放中已隐藏
        state.updatePlayback(false) // 暂停
        assertTrue(state.visible) // 暂停恢复显示
        advanceTimeBy(10_000L)
        runCurrent()
        assertTrue(state.visible) // 暂停不隐藏
    }

    @Test
    fun `交互重置自动隐藏计时`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val state = ControlsVisibilityState(CoroutineScope(dispatcher))
        state.updatePlayback(true)
        advanceTimeBy(2_000L)
        state.onUserInteraction() // 重置计时
        advanceTimeBy(2_000L)
        runCurrent()
        assertTrue(state.visible) // 距上次交互仅 2s
        advanceTimeBy(1_000L)
        runCurrent()
        assertFalse(state.visible) // 距上次交互 3s
    }

    @Test
    fun `锁定隐藏全部普通控制`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val state = ControlsVisibilityState(CoroutineScope(dispatcher))
        state.updatePlayback(true)
        state.setLocked(true)
        assertTrue(state.locked)
        assertFalse(state.visible)
        advanceTimeBy(10_000L)
        runCurrent()
        assertFalse(state.visible) // 锁定态不自动显示
        state.setLocked(false)
        assertFalse(state.locked)
        assertTrue(state.visible) // 解锁恢复显示
    }

    @Test
    fun `锁定期间交互不改变显隐`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val state = ControlsVisibilityState(CoroutineScope(dispatcher))
        state.updatePlayback(true)
        state.setLocked(true)
        state.onUserInteraction()
        state.toggleVisibility()
        assertFalse(state.visible)
        assertEquals(true, state.locked)
    }

    @Test
    fun `显隐回调被通知`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var notified = 0
        val state = ControlsVisibilityState(CoroutineScope(dispatcher), onVisibilityChange = { notified++ })
        state.updatePlayback(true)
        advanceTimeBy(3_000L)
        runCurrent()
        assertTrue(notified >= 1) // 自动隐藏通知
    }
}
