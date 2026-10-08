package com.mediareview.app.feature.v2.viewer.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ImageViewerState 仅持有索引与 UI 显隐，真实待删除由 VM/controller 管理。 */
class ImageViewerStateTest {

    @Test
    fun `currentIndex 可更新`() {
        val state = ImageViewerState(initialIndex = 2)
        assertEquals(2, state.currentIndex)
        state.updateCurrentIndex(5)
        assertEquals(5, state.currentIndex)
    }

    @Test
    fun `uiVisible 单击切换`() {
        val state = ImageViewerState()
        assertTrue(state.uiVisible)
        state.toggleUi()
        assertFalse(state.uiVisible)
        state.toggleUi()
        assertTrue(state.uiVisible)
        state.setUiVisibility(false)
        assertFalse(state.uiVisible)
    }

}
