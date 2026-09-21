package com.mediareview.app.feature.v2.player.native.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SeekScrubState：拖动本地预览、松手才提交、取消丢弃。 */
class SeekScrubStateTest {

    @Test
    fun `begin 快照当前位置进入 scrubbing`() {
        val state = SeekScrubState()
        state.begin(5000L)
        assertTrue(state.isScrubbing)
        assertEquals(5000L, state.previewMs)
    }

    @Test
    fun `update 只更新预览不提交`() {
        val state = SeekScrubState()
        state.begin(0L)
        state.update(30_000L)
        assertEquals(30_000L, state.previewMs)
        assertTrue(state.isScrubbing)
    }

    @Test
    fun `commit 返回预览值并结束 scrubbing`() {
        val state = SeekScrubState()
        state.begin(0L)
        state.update(42_000L)
        assertEquals(42_000L, state.commit())
        assertFalse(state.isScrubbing)
    }

    @Test
    fun `非 scrubbing 时 commit 返回 null`() {
        val state = SeekScrubState()
        assertNull(state.commit())
    }

    @Test
    fun `cancel 丢弃预览`() {
        val state = SeekScrubState()
        state.begin(0L)
        state.update(12_000L)
        state.cancel()
        assertFalse(state.isScrubbing)
        assertNull(state.commit())
    }
}
