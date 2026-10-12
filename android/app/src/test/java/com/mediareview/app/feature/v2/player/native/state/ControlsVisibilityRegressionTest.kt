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

@OptIn(ExperimentalCoroutinesApi::class)
class ControlsVisibilityRegressionTest {
    @Test fun heldGestureKeepsVisibleControlsThenStartsFreshHideTimer() = runTest {
        val state = ControlsVisibilityState(CoroutineScope(StandardTestDispatcher(testScheduler)))
        state.updatePlayback(true)
        advanceTimeBy(2_000)
        state.updateInteractionHeld(true)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(state.visible)
        state.updateInteractionHeld(false)
        advanceTimeBy(2_999)
        runCurrent()
        assertTrue(state.visible)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(state.visible)
    }

    @Test fun touchingHiddenSurfaceDoesNotShowControlsUntilSingleTapDecision() = runTest {
        val state = ControlsVisibilityState(CoroutineScope(StandardTestDispatcher(testScheduler)))
        state.updatePlayback(true)
        advanceTimeBy(3_000)
        runCurrent()
        assertFalse(state.visible)
        state.updateInteractionHeld(true)
        assertFalse(state.visible)
        state.updateInteractionHeld(false)
        state.toggleVisibility()
        advanceTimeBy(700)
        runCurrent()
        assertTrue(state.visible)
    }

    @Test fun controllerLockedThenPausedKeepsAllControlsHidden() = runTest {
        val state = ControlsVisibilityState(CoroutineScope(StandardTestDispatcher(testScheduler)))
        state.updatePlayback(true)
        state.onLockChanged(true)
        state.updatePlayback(false)
        assertFalse("锁控后暂停不能重新显示普通控件", state.visible)
        state.onUserInteraction()
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(state.visible)
    }
}
