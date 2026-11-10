package com.mediareview.app.feature.v2.nsfw

import com.mediareview.app.feature.v2.nsfw.data.*
import org.junit.Assert.*
import org.junit.Test

class NsfwPromptComparisonTest {
    @Test fun exactVersionFreezesComparisonScopeAndKeepsDateAndSort() {
        val base = NsfwGalleryFilter(sort = NsfwGallerySort.OLDEST, status = "NEW", favorite = true)
        val scope = NsfwPromptComparison.open(NsfwPromptLink("p", "v", "EXACT"), base, 6)
        assertEquals(6, scope.pageSize)
        assertEquals("p", scope.query.filter.promptId)
        assertEquals("v", scope.query.filter.versionId)
        assertEquals(NsfwGallerySort.OLDEST, scope.query.filter.sort)
        assertNull(scope.query.filter.status)
        assertNull(scope.query.filter.favorite)
        assertEquals(10, scope.withPageSize(10).pageSize)
        assertEquals(scope.query, scope.withPageSize(10).query)
    }

    @Test fun unknownVersionAndUnsupportedPageSizeCannotStartComparison() {
        assertThrows(IllegalArgumentException::class.java) {
            NsfwPromptComparison.open(NsfwPromptLink("p", null, "UNRESOLVED"), NsfwGalleryFilter(), 6)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NsfwPromptComparison.open(NsfwPromptLink("p", "v", "EXACT"), NsfwGalleryFilter(), 9)
        }
    }

    @Test fun mixedVersionPageIsRejectedEvenWhenPromptMatches() {
        val scope = NsfwPromptComparison.open(NsfwPromptLink("p", "v", "EXACT"), NsfwGalleryFilter(), 10)
        val image = nsfwItem("a").copy(prompt = NsfwPromptLink("p", "v", "EXACT"))
        assertEquals(listOf(image), scope.validatePage(listOf(image)))
        assertThrows(IllegalArgumentException::class.java) {
            scope.validatePage(listOf(image.copy(prompt = NsfwPromptLink("p", "other", "EXACT"))))
        }
    }
}
