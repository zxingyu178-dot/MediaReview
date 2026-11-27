package com.mediareview.app.feature.v2.nsfw.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 审图页只读生图进度文案：排队 / 生成中 / 阶段间隙 / 暂停 / 旧桥兼容。 */
class NsfwGenerationProgressTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `空闲时显示暂无任务`() {
        assertEquals("电脑端暂无生成任务", NsfwGenerationProgress().label)
    }

    @Test
    fun `生成中显示当前张与剩余段`() {
        val progress = NsfwGenerationProgress(
            currentImage = 3,
            imagesInSegment = 6,
            remainingSegments = 20,
            activeStatus = "running",
        )
        assertEquals("电脑端生成中：第 3 / 6 张 · 剩余 20 段", progress.label)
    }

    @Test
    fun `生成中同时显示排队任务数`() {
        val progress = NsfwGenerationProgress(
            currentImage = 3,
            imagesInSegment = 6,
            remainingSegments = 20,
            activeStatus = "running",
            queuedJobs = 2,
        )
        assertEquals("电脑端生成中：第 3 / 6 张 · 剩余 20 段 · 排队 2", progress.label)
    }

    @Test
    fun `暂停时也显示排队任务数`() {
        val progress = NsfwGenerationProgress(
            remainingSegments = 3,
            activeStatus = "paused",
            queuedJobs = 2,
            pausedJobs = 1,
        )
        assertEquals("电脑端生成已暂停 · 剩余 3 段 · 排队 2", progress.label)
    }

    @Test
    fun `排队状态显示已提交任务数`() {
        val progress = NsfwGenerationProgress(
            remainingSegments = 2,
            activeStatus = "queued",
            queuedJobs = 2,
        )
        assertEquals("电脑端已提交：排队 2 个任务", progress.label)
    }

    @Test
    fun `暂停状态优先提示`() {
        val progress = NsfwGenerationProgress(
            remainingSegments = 2,
            activeStatus = "paused",
            pausedJobs = 1,
        )
        assertEquals("电脑端生成已暂停 · 剩余 2 段", progress.label)
    }

    @Test
    fun `阶段间隙仍是生成中`() {
        val progress = NsfwGenerationProgress(
            remainingSegments = 3,
            activeStatus = "running",
        )
        assertEquals("电脑端生成中：准备下一张 · 剩余 3 段", progress.label)
    }

    @Test
    fun `旧桥响应缺少状态字段时保持兼容`() {
        val progress = json.decodeFromString<NsfwGenerationProgress>(
            """{"current_image":1,"images_in_segment":2,"remaining_segments":1}"""
        )
        assertNull(progress.activeStatus)
        assertEquals(0, progress.queuedJobs)
        assertEquals("电脑端生成中：第 1 / 2 张 · 剩余 1 段", progress.label)
    }

    @Test
    fun `分段进度比例在未知或非法计数时为 null`() {
        assertEquals(0.5f, NsfwGenerationProgress(1, 2, 1).fraction)
        assertEquals(1f, NsfwGenerationProgress(3, 2, 1).fraction)
        assertNull(NsfwGenerationProgress(null, 2, 1).fraction)
        assertNull(NsfwGenerationProgress(1, null, 1).fraction)
        assertNull(NsfwGenerationProgress(1, 0, 1).fraction)
    }

    @Test
    fun `当前张完成度显示百分比`() {
        val progress = NsfwGenerationProgress(1, 2, 1, "running", 0, 0, 0.62f)
        assertEquals(0.62f, progress.itemFraction)
        assertEquals("电脑端生成中：第 1 / 2 张 · 剩余 1 段 · 62%", progress.label)
    }

    @Test
    fun `当前张完成度缺少或非法时安全回退`() {
        assertNull(NsfwGenerationProgress(1, 2, 1).itemFraction)
        assertEquals(1f, NsfwGenerationProgress(1, 2, 1, "running", 0, 0, 1.4f).itemFraction)
        assertEquals(0f, NsfwGenerationProgress(1, 2, 1, "running", 0, 0, -0.3f).itemFraction)
    }

    @Test
    fun `解析当前张完成度字段`() {
        val progress = json.decodeFromString<NsfwGenerationProgress>(
            """{"current_image":1,"images_in_segment":2,"remaining_segments":1,
               "active_status":"running","current_progress":0.42}"""
        )
        assertEquals(0.42f, progress.currentProgress)
        assertEquals("电脑端生成中：第 1 / 2 张 · 剩余 1 段 · 42%", progress.label)
    }

    @Test
    fun `生图库顶部仅在能力可用且有任务时显示进度`() {
        assertFalse(NsfwGenerationProgress().visibleInGallery(true))
        assertFalse(NsfwGenerationProgress(remainingSegments = 2).visibleInGallery(false))
        assertTrue(NsfwGenerationProgress(remainingSegments = 2).visibleInGallery(true))
        assertTrue(NsfwGenerationProgress(activeStatus = "queued", queuedJobs = 1,
            remainingSegments = 1).visibleInGallery(true))
    }

    @Test
    fun `新桥响应解析队列状态字段`() {
        val progress = json.decodeFromString<NsfwGenerationProgress>(
            """{"current_image":null,"images_in_segment":null,"remaining_segments":2,
               "active_status":"queued","queued_jobs":2,"paused_jobs":0}"""
        )
        assertEquals("queued", progress.activeStatus)
        assertEquals(2, progress.queuedJobs)
        assertEquals("电脑端已提交：排队 2 个任务", progress.label)
    }
}
