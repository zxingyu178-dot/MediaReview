package com.mediareview.app.feature.v2.data

import com.mediareview.app.feature.v2.home.spriteFrameIndexAtTick
import com.mediareview.app.feature.v2.model.V2Album
import com.mediareview.app.feature.v2.model.V2Folder
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage5 相册逻辑测试：
 * - 书架 = 照片相册：仅图片计数、封面仅图片、无照片文件夹过滤
 * - 默认封面 = 最新照片；用户封面优先
 * - 用户封面不能指向其他文件夹图片
 */
class Stage5AlbumTest {

    private fun folder(id: String, name: String) = V2Folder(id, name, "", listOf())

    private fun media(id: String, folderId: String, type: V2MediaType, date: Long) = V2Media(
        id = id, code = id, name = id,
        folderId = folderId, folderName = folderId, type = type,
        durationMs = if (type == V2MediaType.VIDEO) 1000L else 0L, sizeBytes = 1L, dateMillis = date,
        isFavorite = false, isReviewed = false,
        assetPath = "x.mp4", thumbPath = "x.jpg", spritePath = null, spriteManifestPath = null,
        naturalWidth = 100, naturalHeight = 100,
    )

    @Test
    fun `书架只统计图片 视频不参与数量`() {
        val folders = listOf(folder("f1", "旅行"))
        val media = listOf(
            media("v1", "f1", V2MediaType.VIDEO, 5),
            media("p1", "f1", V2MediaType.IMAGE, 1),
            media("p2", "f1", V2MediaType.IMAGE, 2),
        )
        val albums = buildAlbums(folders, media, emptyMap())
        assertEquals(1, albums.size)
        assertEquals(2, albums[0].imageCount) // 只有 2 张照片（视频不计数）
    }

    @Test
    fun `无照片文件夹不出现在书架`() {
        val folders = listOf(folder("f1", "AI 视频"), folder("f2", "旅行"))
        val media = listOf(
            media("v1", "f1", V2MediaType.VIDEO, 1), // f1 只有视频
            media("p1", "f2", V2MediaType.IMAGE, 2),
        )
        val albums = buildAlbums(folders, media, emptyMap())
        assertEquals(1, albums.size)
        assertEquals("f2", albums[0].folderId)
    }

    @Test
    fun `默认封面=最新照片 且不能是视频`() {
        val folders = listOf(folder("f1", "旅行"))
        val media = listOf(
            media("p_old", "f1", V2MediaType.IMAGE, 10),
            media("p_new", "f1", V2MediaType.IMAGE, 99),
            media("v_new", "f1", V2MediaType.VIDEO, 100), // 视频最新，但不可作为封面
        )
        val albums = buildAlbums(folders, media, emptyMap())
        assertEquals("p_new", albums[0].coverImageId)
    }

    @Test
    fun `用户封面优先但仅限本相册照片`() {
        val folders = listOf(folder("f1", "旅行"), folder("f2", "备份"))
        val media = listOf(
            media("p1", "f1", V2MediaType.IMAGE, 1),
            media("p2", "f1", V2MediaType.IMAGE, 2),
            media("other", "f2", V2MediaType.IMAGE, 3),
            media("vid", "f1", V2MediaType.VIDEO, 4),
        )
        // 用户选中 p2（有效）→ 使用
        val ok = buildAlbums(folders, media, mapOf("f1" to "p2")).first { it.folderId == "f1" }
        assertEquals("p2", ok.coverImageId)
        // 用户指向其他文件夹图片 → 回退默认（p2 最新）
        val invalid = buildAlbums(folders, media, mapOf("f1" to "other")).first { it.folderId == "f1" }
        assertEquals("p2", invalid.coverImageId)
        // 用户指向视频 → 回退默认
        val invalidVideo = buildAlbums(folders, media, mapOf("f1" to "vid")).first { it.folderId == "f1" }
        assertEquals("p2", invalidVideo.coverImageId)
    }

    @Test
    fun `相册 id 与 folderId 一致 名称取文件夹名`() {
        val folders = listOf(folder("f_travel", "旅行"))
        val media = listOf(media("p1", "f_travel", V2MediaType.IMAGE, 1))
        val albums = buildAlbums(folders, media, emptyMap())
        assertEquals(V2Album("f_travel", "f_travel", "旅行", 1, "p1"), albums[0])
    }

    @Test
    fun `混合文件夹仅图片参与且无图时无相册`() {
        // 类似 Demo 的 AI 视频文件夹：10 视频 + 10 图片
        val folders = listOf(folder("f_ai", "AI 视频"))
        val media = buildList {
            repeat(10) { add(media("v$it", "f_ai", V2MediaType.VIDEO, it.toLong())) }
            repeat(10) { add(media("p$it", "f_ai", V2MediaType.IMAGE, (it + 20).toLong())) }
        }
        val albums = buildAlbums(folders, media, emptyMap())
        assertEquals(1, albums.size)
        assertEquals(10, albums[0].imageCount)
        assertEquals("p9", albums[0].coverImageId) // 最新照片
        assertTrue("封面不能是视频", !media.first { it.id == albums[0].coverImageId }.isVideo)
        assertTrue("封面只能是图片", media.first { it.id == albums[0].coverImageId }.type == V2MediaType.IMAGE)
    }

    // ---------- 雪碧图自动播放帧索引 ----------

    @Test
    fun `自动播放帧索引逐帧推进并回绕`() {
        assertEquals(0, spriteFrameIndexAtTick(0, 12))
        assertEquals(1, spriteFrameIndexAtTick(1, 12))
        assertEquals(6, spriteFrameIndexAtTick(6, 12))
        assertEquals(11, spriteFrameIndexAtTick(11, 12))
        assertEquals(0, spriteFrameIndexAtTick(12, 12)) // 回绕
        assertEquals(3, spriteFrameIndexAtTick(15, 12))
    }

    @Test
    fun `自动播放单帧曼陀罗安全`() {
        assertEquals(0, spriteFrameIndexAtTick(0, 1))
        assertEquals(0, spriteFrameIndexAtTick(999, 1))
    }
}