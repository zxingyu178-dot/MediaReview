package com.mediareview.app.feature.v2.player.native.state

import org.junit.Assert.*
import org.junit.Test

class SeekEngineTest {
    @Test fun percentageMappingAndClamps() {
        assertEquals(listOf(0L, 10_000L, 20_000L, 40_000L),
            listOf(0f, .25f, .5f, 1f).map { PlayerSeekMapping.position(it, 40_000L) })
        assertEquals(0L, PlayerSeekMapping.position(-1f, 40_000L))
        assertEquals(40_000L, PlayerSeekMapping.position(2f, 40_000L))
        assertEquals(0L, PlayerSeekMapping.position(.5f, -1L))
        assertEquals(0L, PlayerSeekMapping.fromTouch(100f, 0f, 40_000L))
        assertEquals(28_000L, PlayerSeekMapping.fromTouch(70f, 100f, 40_000L))
    }

    @Test fun dragOnlyUpdatesPreviewAndCommitsExactlyOnce() {
        val seek = SeekDragEngine()
        assertTrue(seek.begin(8_000L, 40_000L, true))
        seek.preview(20_000L); seek.preview(28_000L)
        assertEquals(28_000L, seek.previewMs)
        assertEquals(SeekCommit(28_000L, true), seek.commit())
        assertNull(seek.commit())
    }

    @Test fun pausedScrubDoesNotRequestResumeAndCancelDoesNotSeek() {
        val seek = SeekDragEngine()
        assertFalse(seek.begin(8_000L, 40_000L, false))
        seek.preview(100_000L)
        assertEquals(SeekCommit(40_000L, false), seek.commit())
        seek.begin(8_000L, 40_000L, true)
        seek.preview(-500L)
        assertTrue(seek.cancel())
        assertNull(seek.commit())
    }

    @Test fun duplicateBeginDoesNotReplaceOriginalPlayingState() {
        val seek = SeekDragEngine()
        seek.begin(8_000L, 40_000L, true)
        assertFalse(seek.begin(20_000L, 40_000L, false))
        seek.preview(28_000L)
        assertEquals(SeekCommit(28_000L, true), seek.commit())
    }
}
