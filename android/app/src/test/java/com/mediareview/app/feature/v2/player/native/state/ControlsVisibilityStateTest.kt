package com.mediareview.app.feature.v2.player.native.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ControlsVisibilityState：播放 3s 自动隐藏、暂停保持、交互重置、锁定隐藏。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlsVisibilityStateTest {

    @Test
    fun `播放中约 3 秒无操作自动隐藏`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val state = ControlsVisibilityState(CoroutineScope(dispatcher))
        state.updatePlayback(true)
        advanceTimeBy(2_999L)
        runCurrent()
        assertTrue(state.visible)
        advanceTimeBy(1L)
        runCurrent()
        assertFalse(state.visible)
    }

    @Test
    fun `暂停时控制层保持显示`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val state = ControlsVisibilityState(CoroutineScope(dispatcher))
        state.updatePlayback(true)
        advanceTimeBy(3_100L)
        runCurrent()
        assertFalse(state.visible)
        state.updatePlayback(false)
        assertTrue(state.visible)
        advanceTimeBy(10_000L)
        runCurrent()
        assertTrue(state.visible)
    }

    @Test
    fun `交互重置自动隐藏计时`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val state = ControlsVisibilityState(CoroutineScope(dispatcher))
        state.updatePlayback(true)
        advanceTimeBy(2_000L)
        state.onUserInteraction()
        advanceTimeBy(2_000L)
        runCurrent()
        assertTrue(state.visible)
        advanceTimeBy(1_000L)
        runCurrent()
        assertFalse(state.visible)
    }

    @Test
    fun `锁定隐藏全部普通控制 解锁恢复`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val state = ControlsVisibilityState(CoroutineScope(dispatcher))
        state.updatePlayback(true)
        state.setLocked(true)
        assertTrue(state.locked)
        assertFalse(state.visible)
        advanceTimeBy(10_000L)
        runCurrent()
        assertFalse(state.visible)
        state.setLocked(false)
        assertFalse(state.locked)
        assertTrue(state.visible)
    }
}
