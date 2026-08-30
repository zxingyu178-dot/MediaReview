package com.mediareview.app.feature.viewer

import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.feature.mediawall.SpriteScrubState
import com.mediareview.app.feature.mediawall.spriteProgressLabel
import com.mediareview.app.feature.mediawall.tileIndexFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task B2 纯逻辑合同:图片查看器缩放/平移钳制、原图回退链、
 * 雪碧图 [0f,1f]→帧映射与生成进度文案。
 */
class ViewerAndSpriteLogicTest {

    @Test
    fun clampPanOffsetZeroesAtOneTimesScale() {
        val (x, y) = clampPanOffset(120f, -80f, scale = 1f, 1000f, 800f)
        assertEquals(0f, x)
        assertEquals(0f, y)
    }

    @Test
    fun clampPanOffsetAllowsPanWithinScaledBounds() {
        // 2x 缩放:边界 ±(w*(scale-1)/2) = ±500 / ±400
        val (x, y) = clampPanOffset(300f, -200f, scale = 2f, 1000f, 800f)
        assertEquals(300f, x)
        assertEquals(-200f, y)
    }

    @Test
    fun clampPanOffsetBlocksPanBeyondScaledBounds() {
        val (x, y) = clampPanOffset(900f, -999f, scale = 2f, 1000f, 800f)
        assertEquals(500f, x)
        assertEquals(-400f, y)
    }

    @Test
    fun viewerImageUrlPrefersOriginalThenCover() {
        val originalOnly = ImageViewerUiState(
            media = MediaSummary(media_id = "m", original_url = "/api/v1/media/m/original", cover_url = ""),
        )
        assertEquals("/api/v1/media/m/original", originalOnly.imageUrl)

        val coverOnly = ImageViewerUiState(
            media = MediaSummary(media_id = "m", original_url = null, cover_url = "/api/v1/media/m/thumbnail"),
        )
        assertEquals("/api/v1/media/m/thumbnail", coverOnly.imageUrl)

        val none = ImageViewerUiState(media = MediaSummary(media_id = "m"))
        assertNull(none.imageUrl)
    }

    @Test
    fun viewerErrorStateCarriesRetryableMessage() {
        val error = ImageViewerUiState(loading = false, error = "无法加载图片详情")
        assertFalse(error.loading)
        assertEquals("无法加载图片详情", error.error)
        assertTrue(error.imageUrl == null)
    }

    @Test
    fun tileIndexMapsFractionAcrossFrames() {
        assertEquals(0, tileIndexFor(0f, count = 10))
        assertEquals(4, tileIndexFor(0.49f, count = 10))
        assertEquals(9, tileIndexFor(1f, count = 10))
        // 边界钳制:越界 fraction 与非法 count 都不崩溃
        assertEquals(0, tileIndexFor(-0.5f, count = 10))
        assertEquals(9, tileIndexFor(1.5f, count = 10))
        assertEquals(0, tileIndexFor(0.5f, count = 0))
    }

    @Test
    fun spriteProgressLabelUsesServerProgressWhenPresent() {
        assertEquals("雪碧图生成中…", spriteProgressLabel(null))
        assertEquals("雪碧图生成中 0%", spriteProgressLabel(0))
        assertEquals("雪碧图生成中 40%", spriteProgressLabel(40))
        assertEquals("雪碧图生成中 100%", spriteProgressLabel(100))
    }

    @Test
    fun spriteProgressLabelReportsTerminalStates() {
        assertEquals("雪碧图生成失败,可重新长按重试", spriteProgressLabel(40, "failed"))
        assertEquals("已取消生成", spriteProgressLabel(0, "cancelled"))
        // 进行中状态不受 taskStatus=null 影响
        assertEquals("雪碧图生成中 20%", spriteProgressLabel(20, "running"))
    }

    @Test
    fun spriteWaitingStateExposesTaskForCancellation() {
        val pending = SpriteScrubState(
            pending = true,
            scrubbing = true,
            progress = 40,
            taskId = "task-1",
        )
        assertTrue(pending.showWaiting)
        assertFalse(pending.showScrub)
        assertEquals("task-1", pending.taskId)

        val ready = SpriteScrubState(
            scrubbing = true,
            ready = true,
        )
        assertFalse(ready.showWaiting)
        // 未挂载位图/清单时不得进入预览
        assertFalse(ready.showScrub)
    }
}
