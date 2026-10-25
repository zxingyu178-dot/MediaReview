package com.mediareview.app.feature.v2.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewUiPolicyTest {
    @Test fun `progress uses authoritative reviewed and remaining counts`() {
        val progress = reviewProgress(
            absoluteIndex = 11,
            totalCount = 40,
            seenCount = 13,
            remainingCount = 24,
        )

        assertEquals(12, progress.position)
        assertEquals(40, progress.total)
        assertEquals(13, progress.reviewed)
        assertEquals(24, progress.remaining)
        assertEquals(0.325f, progress.fraction, 0.001f)
        assertEquals("第 12 / 共 40", progress.positionLabel)
        assertEquals("已批 13 · 剩余 24", progress.summaryLabel)
    }

    @Test fun `progress clamps legacy and out of range values`() {
        val progress = reviewProgress(absoluteIndex = 99, totalCount = 3, seenCount = 9, remainingCount = -1)
        assertEquals(3, progress.position)
        assertEquals(3, progress.reviewed)
        assertEquals(0, progress.remaining)
        assertEquals(1f, progress.fraction, 0.001f)
    }

    @Test fun `vertical gesture maps up to keep and down to pending delete`() {
        assertEquals(ReviewDecisionGesture.KEEP, reviewDecisionGesture(0f, -260f, 1080f, 1920f))
        assertEquals(ReviewDecisionGesture.DELETE, reviewDecisionGesture(0f, 260f, 1080f, 1920f))
    }

    @Test fun `horizontal and short drags never become decisions`() {
        assertNull(reviewDecisionGesture(500f, -260f, 1080f, 1920f))
        assertNull(reviewDecisionGesture(0f, 90f, 1080f, 1920f))
        assertNull(reviewDecisionGesture(0f, -90f, 1080f, 1920f))
    }

    @Test fun `navigation and screen jump stay bounded`() {
        assertFalse(canGoPrevious(0))
        assertTrue(canGoPrevious(1))
        assertFalse(canGoNext(4, 5))
        assertTrue(canGoNext(3, 5))
        assertEquals(14, fastForwardIndex(4, 30))
        assertEquals(29, fastForwardIndex(25, 30))
        assertEquals(0, fastForwardIndex(0, 0))
        assertEquals("已快进到第 15 / 共 30", fastForwardHint(4, 30))
        assertEquals("已到最后一项（第 30 / 共 30）", fastForwardHint(25, 30))
        assertNull(fastForwardHint(0, 0))
    }

    @Test fun `resume hint only appears after restored progress`() {
        assertNull(reviewResumeHint(0, 20))
        assertEquals("已恢复到第 8 项", reviewResumeHint(7, 20))
    }

    @Test fun `undo is enabled only after pending delete is confirmed`() {
        assertNull(reviewUndoTarget("m2", setOf("m1")))
        assertEquals("m2", reviewUndoTarget("m2", setOf("m1", "m2")))
        assertNull(reviewUndoTarget(null, setOf("m2")))
    }
}
