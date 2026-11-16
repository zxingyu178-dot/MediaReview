package com.mediareview.app.feature.v2.nsfw

import org.junit.Assert.assertEquals
import org.junit.Test

class NsfwImageMotionTest {
    @Test fun sameIdentityRefreshNeverAnimatesEvenWhenIndexChanges() {
        assertEquals(0, imageMotionDirection("a", "a", 2, 0))
    }

    @Test fun firstImageAndEmptyStateNeverAnimate() {
        assertEquals(0, imageMotionDirection(null, "a", -1, 0))
        assertEquals(0, imageMotionDirection("a", null, 0, -1))
    }

    @Test fun navigationUsesSequenceDirectionAndReviewReplacementMovesForward() {
        assertEquals(1, imageMotionDirection("a", "b", 0, 1))
        assertEquals(-1, imageMotionDirection("b", "a", 1, 0))
        assertEquals(1, imageMotionDirection("a", "b", 0, 0))
    }
}
