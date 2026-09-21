package com.mediareview.app.feature.v2.viewer.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ImageViewerState：currentIndex / uiVisible / pendingDeleteIds（会话内）。 */
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

    @Test
    fun `pendingDelete 会话内切换`() {
        val state = ImageViewerState()
        assertFalse(state.isPendingDelete("img-1"))
        state.togglePendingDelete("img-1")
        assertTrue(state.isPendingDelete("img-1"))
        state.togglePendingDelete("img-1")
        assertFalse(state.isPendingDelete("img-1"))
    }

    @Test
    fun `pendingDelete 互不影响多个媒体`() {
        val state = ImageViewerState()
        state.togglePendingDelete("a")
        state.togglePendingDelete("b")
        assertEquals(setOf("a", "b"), state.pendingDeleteIds)
        state.togglePendingDelete("a")
        assertEquals(setOf("b"), state.pendingDeleteIds)
    }
}
