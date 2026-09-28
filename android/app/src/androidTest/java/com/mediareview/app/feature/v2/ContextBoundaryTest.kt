package com.mediareview.app.feature.v2

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.feature.v2.data.MediaRepository
import com.mediareview.app.feature.v2.data.SearchHistoryStore
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.home.V2HomeViewModel
import com.mediareview.app.feature.v2.model.V2Album
import com.mediareview.app.feature.v2.model.V2Folder
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2MediaPage
import com.mediareview.app.feature.v2.model.V2MediaQuery
import com.mediareview.app.feature.v2.model.V2MediaType
import com.mediareview.app.feature.v2.model.V2PlaybackEndpoint
import com.mediareview.app.feature.v2.model.V2PlaybackSource
import com.mediareview.app.feature.v2.model.V2SortSpec
import com.mediareview.app.feature.v2.model.V2SpriteManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 阶段 8B §10：媒体上下文边界。
 *
 * 旧实现用 `indexOfFirst(...).coerceAtLeast(0)`：找不到目标时**静默指向第 1 项**，
 * 用户点 A 却打开了 B。现在必须返回 false 并放弃建立上下文。
 */
@RunWith(AndroidJUnit4::class)
class ContextBoundaryTest {

    private fun vm(): V2HomeViewModel {
        val repo = ContextRepository()
        val vm = testHomeViewModel(repo, StubHistory())
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.currentList.value.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue("首屏媒体必须先加载出来", vm.currentList.value.isNotEmpty())
        return vm
    }

    @Test
    fun openMedia_命中时建立上下文且索引正确() {
        val vm = vm()
        assertTrue(vm.openMedia("v2"))
        // 上下文 = 当前列表的完整顺序（用户点击的是第 2 条）
        assertEquals(listOf("v1", "v2", "v3", "i1", "i2"), vm.contextQueue?.mediaIds)
        assertEquals(1, vm.contextQueue?.currentIndex)
    }

    @Test
    fun openMedia_目标不在当前列表时退化为单条上下文而不是指向首项() {
        val vm = vm()
        // "v9" 不在首页列表里（例如从收藏页点进来的媒体）：上下文只含这一条，绝不指向 v1
        assertTrue(vm.openMedia("v9"))
        assertEquals(listOf("v9"), vm.contextQueue?.mediaIds)
        assertEquals(0, vm.contextQueue?.currentIndex)
    }

    @Test
    fun openMedia_数据源也查不到时才返回false() {
        val vm = vm()
        assertFalse(vm.openMedia("不存在"))
        assertNull(vm.contextQueue)
    }

    @Test
    fun openMediaIn_空列表不建立上下文() {
        val vm = vm()
        assertFalse(vm.openMediaIn(emptyList(), "v1"))
        assertNull(vm.contextQueue)
    }

    @Test
    fun openMediaIn_单元素列表正常建立上下文() {
        val vm = vm()
        val single = listOf(ContextRepository().video("v9"))
        assertTrue(vm.openMediaIn(single, "v9"))
        assertEquals(0, vm.contextQueue?.currentIndex)
        assertEquals(1, vm.contextQueue?.mediaIds?.size)
    }

    @Test
    fun openMediaInVideoQueue_队列里没有该视频时不建立上下文() {
        val vm = vm()
        val images = listOf(ContextRepository().image("i1"), ContextRepository().image("i2"))
        assertFalse("全是图片的队列不得静默打开第 1 项", vm.openMediaInVideoQueue(images, "i1"))
        assertNull(vm.contextQueue)
    }

    @Test
    fun openMediaInVideoQueue_命中视频时建立上下文() {
        val vm = vm()
        val queue = listOf(ContextRepository().image("i1"), ContextRepository().video("v2"))
        assertTrue(vm.openMediaInVideoQueue(queue, "v2"))
        assertEquals(listOf("v2"), vm.contextQueue?.mediaIds)
        assertEquals(0, vm.contextQueue?.currentIndex)
    }

    @Test
    fun updateQueueIndex_单元素队列与越界输入都被收敛() {
        val vm = vm()
        // 单条上下文（收藏页点进来的场景）
        val single = listOf(ContextRepository().video("v9"))
        assertTrue(vm.openMediaIn(single, "v9"))
        assertEquals(0, vm.contextQueue?.currentIndex)

        vm.updateQueueIndex(9)
        assertEquals(0, vm.contextQueue?.currentIndex)
        vm.updateQueueIndex(-9)
        assertEquals(0, vm.contextQueue?.currentIndex)
    }

    @Test
    fun updateQueueIndex_多元素队列越界被收敛而不是非法值() {
        val vm = vm()
        vm.openMedia("v1") // 首页 5 条列表
        vm.updateQueueIndex(9)
        assertEquals(4, vm.contextQueue?.currentIndex)
        vm.updateQueueIndex(-1)
        assertEquals(0, vm.contextQueue?.currentIndex)
    }
}

/** 内存版搜索历史（不触碰真实 DataStore）。 */
private class StubHistory : SearchHistoryStore(ApplicationProvider.getApplicationContext()) {
    private val memory = mutableListOf<String>()
    override suspend fun current(): List<String> = memory.toList()
    override suspend fun add(term: String) {
        memory.remove(term)
        memory.add(0, term)
    }

    override suspend fun clear() = memory.clear()
}

/**
 * 只提供 3 条媒体（2 视频 + 1 图片）的最小仓库：用于验证上下文边界，
 * 不涉及网络、不涉及 DiffUtil 之外的任何行为。
 */
private class ContextRepository : MediaRepository {

    fun video(id: String) = media(id, "视频 $id", V2MediaType.VIDEO)

    fun image(id: String) = media(id, "照片 $id", V2MediaType.IMAGE)

    private fun media(id: String, name: String, type: V2MediaType) = V2Media(
        id = id,
        code = "",
        name = name,
        folderId = "f1",
        folderName = "文件夹",
        type = type,
        durationMs = if (type == V2MediaType.VIDEO) 12_000L else 0L,
        sizeBytes = 0L,
        dateMillis = 0L,
        isFavorite = false,
        isReviewed = false,
        assetPath = "",
        thumbPath = "",
        spritePath = null,
        spriteManifestPath = null,
        naturalWidth = 1920,
        naturalHeight = 1080,
    )

    /** 首页列表里可见的 5 条。 */
    private val pageCatalog = listOf(video("v1"), video("v2"), video("v3"), image("i1"), image("i2"))

    /** 数据源里还有一条不在首页列表（模拟"从收藏页点进来的媒体"）。 */
    private val offListMedia = video("v9")

    override val mode: V2DataMode = V2DataMode.DEMO

    override suspend fun mediaPage(query: V2MediaQuery): V2MediaPage =
        V2MediaPage(items = pageCatalog, page = 1, pageSize = query.pageSize, total = pageCatalog.size)

    override suspend fun folders(): List<V2Folder> = emptyList()

    override suspend fun albums(): List<V2Album> = emptyList()

    override suspend fun favorites(): List<V2Media> = emptyList()

    override suspend fun media(): List<V2Media> = pageCatalog

    override suspend fun media(spec: V2SortSpec): List<V2Media> = pageCatalog

    override suspend fun mediaInFolder(folderId: String, spec: V2SortSpec): List<V2Media> = pageCatalog

    override suspend fun search(query: String, spec: V2SortSpec): List<V2Media> = emptyList()

    override fun mediaById(id: String): V2Media? = (pageCatalog + offListMedia).find { it.id == id }

    override suspend fun setFavorite(mediaId: String, favorite: Boolean): Boolean = true

    override suspend fun markReviewed(mediaId: String) = Unit

    override suspend fun pendingDeleteIds(): Set<String> = emptySet()

    override suspend fun setPendingDelete(mediaId: String, pending: Boolean): Boolean = true

    override suspend fun unmarkReviewed(mediaId: String) = Unit

    override suspend fun resolvePlayback(mediaId: String): V2PlaybackSource = V2PlaybackSource(
        mediaId = mediaId,
        title = mediaId,
        direct = V2PlaybackEndpoint(url = "http://127.0.0.1:1/never"),
    )

    override fun playbackUri(mediaId: String): String = ""

    override fun thumbUri(media: V2Media): String = ""

    override fun coverUri(media: V2Media): String = ""

    override fun imageUri(media: V2Media): String = ""

    override fun spriteUri(media: V2Media): String? = null

    override fun spriteManifest(media: V2Media): V2SpriteManifest? = null

    override suspend fun albumPage(
        albumId: String,
        page: Int,
        pageSize: Int,
        spec: V2SortSpec,
    ): V2MediaPage = V2MediaPage(emptyList(), page, pageSize, 0)

    override suspend fun setAlbumCover(albumId: String, mediaId: String) = Unit
}