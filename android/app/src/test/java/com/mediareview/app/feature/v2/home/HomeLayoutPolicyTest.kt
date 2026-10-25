package com.mediareview.app.feature.v2.home

import org.junit.Assert.*
import org.junit.Test

class HomeLayoutPolicyTest {
    @Test fun overflowActionsKeepAllExistingHomeControlsReachable() {
        assertEquals(
            listOf("排序与筛选", "切换为单列", "数据源", "浏览文件夹"),
            homeOverflowActions(HomeDisplayMode.TWO_COLUMN).map { it.label },
        )
        assertEquals("切换为双列", homeOverflowActions(HomeDisplayMode.ONE_COLUMN)[1].label)
    }

    @Test fun folderBackMovesToParentBeforeLeavingBrowser() {
        val parents = mapOf("root" to null, "movies" to "root", "action" to "movies")
        assertEquals(FolderBackTarget.Parent("movies"), folderBackTarget("action", parents, null))
        assertEquals(FolderBackTarget.Parent(null), folderBackTarget("root", parents, "root"))
        assertEquals(FolderBackTarget.Exit, folderBackTarget(null, parents, "root"))
    }

    @Test fun layoutAnchorFollowsMediaIdentityAfterReorder() {
        val anchor = HomeGridAnchor("m42", 73)
        assertEquals(1, anchor.indexIn(listOf("m1", "m42", "m2")))
        assertEquals(73, anchor.offset)
        assertEquals(0, anchor.indexIn(listOf("m1")))
    }
    @Test fun accumulatedDirectionThresholdCollapsesAndExpandsAwayFromTop() {
        val policy = HomeHeaderScrollPolicy()
        repeat(3) { assertFalse(policy.scroll(-7f)) }
        assertTrue(policy.scroll(-4f))
        assertTrue(policy.collapsed)
        assertFalse(policy.scroll(2f))
        assertFalse(policy.scroll(-1f))
        assertFalse(policy.scroll(23f))
        assertTrue(policy.scroll(2f))
        assertFalse(policy.collapsed)
    }
    @Test fun defaultAndRoundTripDisplayMode() {
        assertEquals(HomeDisplayMode.TWO_COLUMN, HomeDisplayMode.fromStored(null))
        assertEquals(HomeDisplayMode.TWO_COLUMN, HomeDisplayMode.fromStored("invalid"))
        HomeDisplayMode.entries.forEach { assertEquals(it, HomeDisplayMode.fromStored(it.name)) }
    }

    @Test fun mediaTitlePresentationKeepsVideoMetadataAndSoftensImageNames() {
        assertEquals(MediaTitlePresentation.IMAGE_OVERLAY, mediaTitlePresentation(isVideo = false))
        assertEquals(MediaTitlePresentation.VIDEO_LABEL, mediaTitlePresentation(isVideo = true))
    }
}
