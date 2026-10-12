package com.mediareview.app.feature.v2.player.native.state

import org.junit.Assert.*
import org.junit.Test

class SpeedGestureStateTest {
    @Test fun temporarySpeedRestoresBothFormalSpeeds() {
        for (formal in listOf(1f, 1.5f)) {
            val state = SpeedGestureState(formal)
            assertEquals(2f, state.longPress(playing = true)!!.speed, 0f)
            assertEquals(formal, state.formalSpeed, 0f)
            assertTrue(state.ownsPointerSequence)
            assertEquals(formal, state.release().speed, 0f)
            assertEquals(SpeedGestureMode.Normal, state.mode)
            assertFalse(state.ownsPointerSequence)
        }
    }

    @Test fun swipeLocksUntilSecondLongPressAndFeedbackOccursOnce() {
        val state = SpeedGestureState(1.5f)
        state.longPress(true)
        assertNull(state.dragUp(47f))
        assertTrue(state.dragUp(48f)!!.haptic)
        assertEquals(SpeedGestureMode.Locked2x, state.mode)
        assertNull(state.dragUp(100f))
        assertEquals(2f, state.release().speed, 0f)
        assertFalse(state.ownsPointerSequence)
        val unlock = state.longPress(true)!!
        assertEquals(1.5f, unlock.speed, 0f)
        assertTrue(unlock.haptic)
        assertTrue(state.ownsPointerSequence)
        assertEquals(1.5f, state.release().speed, 0f)
    }

    @Test fun speedSequenceExcludesOtherGestureDirectionsEvenAfterUnlock() {
        val state = SpeedGestureState()
        val direction = GestureDirectionLock(8f)
        state.longPress(true)
        direction.claimSpeed()
        state.dragUp(100f)
        assertEquals(GestureDirection.Speed, direction.update(200f, -100f))
        assertEquals(GestureDirection.Speed, direction.update(0f, 500f))
        state.release()
        state.longPress(true)
        assertTrue(state.ownsPointerSequence)
        assertEquals(GestureDirection.Speed, direction.update(-500f, 0f))
    }

    @Test fun pausedLongPressDoesNotStartTemporarySpeed() {
        val state = SpeedGestureState(1.25f)
        assertNull(state.longPress(false))
        assertEquals(1.25f, state.effectiveSpeed, 0f)
        assertFalse(state.ownsPointerSequence)
    }

    @Test fun formalSelectionAndSourceResetDiscardTemporaryLock() {
        val state = SpeedGestureState(1.5f)
        state.longPress(true); state.dragUp(60f); state.release()
        assertEquals(.75f, state.formalSpeedChanged(.75f).speed, 0f)
        assertEquals(SpeedGestureMode.Normal, state.mode)
        state.longPress(true)
        assertEquals(.75f, state.cancel().speed, 0f)
        assertFalse(state.ownsPointerSequence)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedFormalSpeedIsRejected() { SpeedGestureState().formalSpeedChanged(3f) }
}
