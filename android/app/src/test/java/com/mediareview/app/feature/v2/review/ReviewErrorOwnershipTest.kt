package com.mediareview.app.feature.v2.review

import org.junit.Assert.*
import org.junit.Test

class ReviewErrorOwnershipTest {
    @Test fun currentErrorSurvivesKernelRelease() {
        assertTrue(acceptsReviewError(true, true, true, true, "current", null))
    }

    @Test fun retiredOrReplacedSourcesCannotClaimCurrentError() {
        assertFalse(acceptsReviewError(false, true, true, true, "current", null))
        assertFalse(acceptsReviewError(true, false, true, true, "current", null))
        assertFalse(acceptsReviewError(true, true, false, true, "current", null))
        assertFalse(acceptsReviewError(true, true, true, true, "current", "replacement"))
        assertFalse(acceptsReviewError(true, true, true, false, "current", null))
        assertFalse(acceptsReviewError(true, true, true, true, "", null))
        assertTrue(acceptsReviewError(true, true, true, true, "current", "current"))
    }
}
