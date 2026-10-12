package com.mediareview.app.feature.v2.player.native.state

import org.junit.Assert.*
import org.junit.Test

class GestureDirectionLockTest {
    @Test fun waitsForSlopAndUnambiguousDirection() {
        val direction = GestureDirectionLock(8f)
        assertEquals(GestureDirection.Undecided, direction.update(4f, 0f))
        assertEquals(GestureDirection.Undecided, direction.update(10f, 10f))
        assertEquals(GestureDirection.Horizontal, direction.update(13f, 10f))
    }
    @Test fun lockedHorizontalNeverTurnsIntoVertical() {
        val direction = GestureDirectionLock(8f)
        direction.update(20f, 0f)
        assertEquals(GestureDirection.Horizontal, direction.update(0f, 200f))
    }
    @Test fun lockedVerticalNeverTurnsIntoSeek() {
        val direction = GestureDirectionLock(8f)
        direction.update(0f, -20f)
        assertEquals(GestureDirection.Vertical, direction.update(200f, 0f))
    }
    @Test fun speedCannotStealAnAlreadyStartedDrag() {
        val direction = GestureDirectionLock(8f)
        direction.update(20f, 0f)
        assertFalse(direction.claimSpeed())
        direction.reset()
        assertTrue(direction.claimSpeed())
        assertEquals(GestureDirection.Speed, direction.update(0f, -100f))
    }
    @Test fun doubleTapAccumulatesSameSideAndResetsWhenSideOrTimeChanges() {
        val state = DoubleTapSeekState()
        assertEquals(SeekTap(30_000L, 10_000L), state.tap(.8f, 1000L, 20_000L, 60_000L))
        assertEquals(SeekTap(40_000L, 20_000L), state.tap(.8f, 1500L, 30_000L, 60_000L))
        assertEquals(SeekTap(50_000L, 30_000L), state.tap(.8f, 2000L, 40_000L, 60_000L))
        assertEquals(SeekTap(40_000L, -10_000L), state.tap(.2f, 2200L, 50_000L, 60_000L))
        assertEquals(SeekTap(30_000L, -10_000L), state.tap(.2f, 4000L, 40_000L, 60_000L))
    }
    @Test fun doubleTapClampsAtBothEnds() {
        val state = DoubleTapSeekState()
        assertEquals(0L, state.tap(.1f, 0L, 2_000L, 40_000L).targetMs)
        assertEquals(40_000L, state.tap(.9f, 200L, 39_000L, 40_000L).targetMs)
    }
}
