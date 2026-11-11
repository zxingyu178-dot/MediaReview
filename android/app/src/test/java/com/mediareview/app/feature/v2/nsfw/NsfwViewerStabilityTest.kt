package com.mediareview.app.feature.v2.nsfw

import com.mediareview.app.feature.v2.nsfw.data.NsfwFeedItem
import org.junit.Assert.assertEquals
import org.junit.Test

class NsfwViewerStabilityTest {
    @Test fun backgroundHistoryAppendDoesNotChangeCurrentPositionText() {
        val initial = NsfwUiState(items = listOf(NsfwFeedItem(imageId = "a"), NsfwFeedItem(imageId = "b")), currentId = "b")
        assertEquals("第 2 张", initial.reviewPositionLabel)
        val appended = initial.copy(items = initial.items + NsfwFeedItem(imageId = "c"), syncing = true)
        assertEquals(initial.reviewPositionLabel, appended.reviewPositionLabel)
    }

    @Test fun unverifiedPositionDoesNotPresentLoadedCountAsTotal() {
        val state = NsfwUiState(items = listOf(NsfwFeedItem(imageId = "a")), currentId = "a", bidirectionalGallery = true)
        assertEquals("位置待确认", state.reviewPositionLabel)
    }
}
