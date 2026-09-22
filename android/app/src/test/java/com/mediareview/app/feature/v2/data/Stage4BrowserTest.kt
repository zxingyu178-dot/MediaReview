package com.mediareview.app.feature.v2.data

import com.mediareview.app.feature.v2.home.spriteFrameIndexForProgress
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage4 浏览器测试：
 * - Cover 源映射（图片→图片资源、视频→Poster 资源）
 * - 搜索历史持久化（去重、保留 10 条）
 * - 雪碧图帧索引（0 / 0.5 / 1、边界 clamp）
 */
class Stage4BrowserTest {

    private fun mediaVideo(id: String, asset: String) = V2Media(
        id = id, code = "001", name = "视频",
        folderId = "f", folderName = "F", type = V2MediaType.VIDEO,
        durationMs = 20_000L, sizeBytes = 100, dateMillis = 0,
        isFavorite = false, isReviewed = false,
        assetPath = "demo_media/videos/$asset", thumbPath = "demo_media/sprites/$asset.webp",
        spritePath = "demo_media/sprites/$asset.webp", spriteManifestPath = "demo_media/sprites/$asset.json",
        naturalWidth = 1280, naturalHeight = 720,
    )

    private fun mediaImage(id: String, asset: String) = V2Media(
        id = id, code = "002", name = "图片",
        folderId = "f", folderName = "F", type = V2MediaType.IMAGE,
        durationMs = 0L, sizeBytes = 100, dateMillis = 0,
        isFavorite = false, isReviewed = false,
        assetPath = "demo_media/images/$asset", thumbPath = "demo_media/images/$asset",
        spritePath = null, spriteManifestPath = null,
        naturalWidth = 1280, naturalHeight = 720,
    )

    @Test
    fun `视频封面映射到 Poster 资源`() {
        val uri = DemoAssets.coverUri(mediaVideo("v1", "01_landscape.mp4"))
        assertTrue("应为 android.resource Poster，实际=$uri", uri.contains("demo_video_01_poster"))
        assertTrue(uri.startsWith("android.resource://"))
        // 多位数编号也能映射
        val uri6 = DemoAssets.coverUri(mediaVideo("v2", "06_wide.mp4"))
        assertTrue(uri6.contains("demo_video_06_poster"))
    }

    @Test
    fun `图片封面映射到 res raw 图片资源`() {
        // mediaImage 的 assetPath = "demo_media/images/img_landscape_01.jpg" → demo_img_landscape_01
        val uri = DemoAssets.coverUri(mediaImage("i1", "img_landscape_01.jpg"))
        assertTrue("应为 android.resource 图片，实际=$uri", uri.startsWith("android.resource://"))
        assertTrue(uri.contains("demo_img_landscape_01"))
    }

    @Test
    fun `封面映射不出现 asset 黑卡源`() {
        // 无论图片还是视频，统一 Cover 不允许返回 asset:///
        val v = DemoAssets.coverUri(mediaVideo("v3", "04_short.mp4"))
        val i = DemoAssets.coverUri(mediaImage("i2", "square_01.jpg"))
        assertTrue(v.startsWith("android.resource://"))
        assertTrue(i.startsWith("android.resource://"))
    }

    @Test
    fun `搜索历史去重置顶并保留 10 条`() {
        val current = (1..10).map { "词$it" }
        val next = SearchHistoryStore.nextItems("新词", current)
        assertEquals(10, next.size)
        assertEquals("新词", next.first())
        assertEquals("词9", next.last())
        // 重复词去重置顶
        val dup = SearchHistoryStore.nextItems("词5", current)
        assertEquals("词5", dup.first())
        assertEquals(10, dup.size)
        assertEquals(1, dup.count { it == "词5" })
    }

    @Test
    fun `空白搜索词不写入`() {
        val current = listOf("a")
        assertEquals(current, SearchHistoryStore.nextItems("   ", current))
        assertEquals(current, SearchHistoryStore.nextItems("", current))
    }

    @Test
    fun `雪碧图帧索引 0-0_5-1 映射正确且 clamp`() {
        // 12 帧：progress 0 → 0、0.5 → 6、接近 1 → 11
        assertEquals(0, spriteFrameIndexForProgress(0f, 12, 5, 3))
        assertEquals(6, spriteFrameIndexForProgress(0.5f, 12, 5, 3))
        assertEquals(11, spriteFrameIndexForProgress(1f, 12, 5, 3))
        assertEquals(11, spriteFrameIndexForProgress(99f, 12, 5, 3))
        assertEquals(0, spriteFrameIndexForProgress(-5f, 12, 5, 3))
    }

    @Test
    fun `雪碧图帧索引 单帧曼陀罗安全`() {
        assertEquals(0, spriteFrameIndexForProgress(0.5f, 1, 1, 1))
        assertEquals(0, spriteFrameIndexForProgress(1f, 1, 1, 1))
    }
}