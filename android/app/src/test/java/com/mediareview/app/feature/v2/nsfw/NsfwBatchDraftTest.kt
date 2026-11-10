package com.mediareview.app.feature.v2.nsfw

import org.junit.Assert.*
import org.junit.Test

/** 只验证用户选择和真实可见集合，不访问媒体或提交审核。 */
class NsfwBatchDraftTest {
    @Test fun prefetchedButNeverDisplayedImageCannotBeRejected() {
        val draft = NsfwBatchDraft.open(listOf(nsfwItem("offscreen"))).loaded("offscreen", true)
        assertTrue(runCatching { draft.freeze("request", allowRejectAll = true) }.isFailure)
    }
    @Test fun onlyLoadedNewImagesOnThisNineImagePageCanBeSubmitted() {
        val all = listOf(nsfwItem("already-reviewed", status = "KEEP")) + (1..12).map { nsfwItem("image-$it") }
        var draft = NsfwBatchDraft.open(all)
        assertEquals(9, draft.items.size)
        draft = draft.loaded("image-1", true).loaded("image-2", true).loaded("image-3", false)
        draft = draft.displayed("image-1").displayed("image-2")
        draft = draft.toggle("image-1").toggle("image-3").toggle("image-12")
        val frozen = draft.freeze("request-1")
        assertEquals(listOf("image-1", "image-2"), frozen.items.map { it.imageId })
        assertEquals(listOf("KEEP", "REJECT"), frozen.items.map { it.action })
        assertEquals(all.first { it.imageId == "image-1" }.revision, frozen.items.first().expectedRevision)
        assertFalse(frozen.items.any { it.imageId == "image-3" || it.imageId == "image-12" })
    }

    @Test fun allRejectedNeedsExplicitExtraConfirmationAndEmptyLoadedCannotSubmit() {
        var draft = NsfwBatchDraft.open(listOf(nsfwItem("a"), nsfwItem("b")))
        assertTrue(runCatching { draft.freeze("request") }.isFailure)
        draft = draft.loaded("a", true).displayed("a")
        assertTrue(runCatching { draft.freeze("request") }.isFailure)
        val frozen = draft.freeze("request", allowRejectAll = true)
        assertEquals(listOf("REJECT"), frozen.items.map { it.action })
    }

    @Test fun frozenRequestIsUnaffectedByLaterSelectionsOrImageLoading() {
        var draft = NsfwBatchDraft.open(listOf(nsfwItem("a"), nsfwItem("b")))
        draft = draft.loaded("a", true).displayed("a").toggle("a")
        val frozen = draft.freeze("original-request")
        draft = draft.loaded("b", true).displayed("b").toggle("b").toggle("a")
        assertEquals(listOf("a"), frozen.items.map { it.imageId })
        assertEquals("KEEP", frozen.items.single().action)
        assertEquals("original-request", frozen.requestId)
        assertEquals(setOf("b"), draft.selectedIds)
    }

    @Test fun refreshRebindsOnlyOriginalNewImagesAndKeepsValidSelection() {
        val draft = NsfwBatchDraft.open(listOf(nsfwItem("a"), nsfwItem("b")))
            .loaded("a", true).displayed("a").toggle("a")
        var refreshed = draft.refreshed(listOf(nsfwItem("a", revision = "9"), nsfwItem("b", status = "KEEP"), nsfwItem("new-arrival")))
        assertEquals(listOf("a"), refreshed.items.map { it.imageId })
        assertEquals(setOf("a"), refreshed.selectedIds)
        assertTrue(refreshed.eligibleIds.isEmpty())
        refreshed = refreshed.loaded("a", true).displayed("a")
        assertEquals("9", refreshed.freeze("new-request").items.single().expectedRevision)
    }
}
