package com.mediareview.app.feature.v2.loading

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.feature.v2.data.MediaRepository
import com.mediareview.app.feature.v2.data.SearchHistoryStore
import com.mediareview.app.feature.v2.data.V2DataMode
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
import com.mediareview.app.feature.v2.testHomeViewModel
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 阶段 8A.1.1 收口：P0/P1 状态解耦与收藏可重试（§2 / §3）。
 *
 * 用可注入故障的仓库验证：
 * - folders / albums 失败只写自己的错误态，**不污染** listError；
 *   `GET /media?page=1` 成功后媒体墙仍必须正常显示；
 * - 收藏首次失败后**可以重试**（不再永久锁死），成功才置 loaded；
 * - 已有成功数据时临时失败**不清空**旧数据。
 */
@RunWith(AndroidJUnit4::class)
class LoadingClosureViewModelTest {

    @Test
    fun folders失败不污染媒体列表错误态() {
        val repo = ClosureRepository().apply { failFolders = true }
        val vm = testHomeViewModel(repo, StubClosureHistory())
        waitUntil("首屏媒体") { vm.currentList.value.isNotEmpty() }
        waitUntil("文件夹错误") { vm.folderError.value != null }

        assertTrue("P0 媒体必须仍然显示", vm.currentList.value.isNotEmpty())
        assertNull("folders 失败绝不能写进 listError", vm.listError.value)
        assertNotNull(vm.folderError.value)
    }

    @Test
    fun albums失败不污染媒体与文件夹状态() {
        val repo = ClosureRepository().apply { failAlbums = true }
        val vm = testHomeViewModel(repo, StubClosureHistory())
        waitUntil("首屏媒体") { vm.currentList.value.isNotEmpty() }
        waitUntil("书架错误") { vm.albumError.value != null }

        assertTrue(vm.currentList.value.isNotEmpty())
        assertNull(vm.listError.value)
        assertNull("folders 成功时不应有文件夹错误", vm.folderError.value)
        assertTrue("文件夹仍应加载成功", vm.folders.value.isNotEmpty())
    }

    @Test
    fun 收藏首次失败后可重试并成功() {
        val repo = ClosureRepository().apply { failFavorites = true }
        val vm = testHomeViewModel(repo, StubClosureHistory())
        waitUntil("首屏媒体") { vm.currentList.value.isNotEmpty() }

        vm.ensureFavoritesLoaded()
        waitUntil("收藏报错") { vm.favoritesError.value != null }
        assertEquals(1, repo.favoritesCalls)

        // 失败后再次进入收藏页必须重新请求（不再被永久锁死）
        repo.failFavorites = false
        repo.favoriteItems = listOf(repo.media)
        vm.ensureFavoritesLoaded()
        waitUntil("收藏重试成功") { vm.favorites.value.isNotEmpty() }
        assertNull(vm.favoritesError.value)
        assertEquals("失败后应允许重试", 2, repo.favoritesCalls)
    }

    @Test
    fun 收藏临时失败不清空已有数据() {
        val repo = ClosureRepository()
        repo.favoriteItems = listOf(repo.media)
        val vm = testHomeViewModel(repo, StubClosureHistory())
        waitUntil("首屏媒体") { vm.currentList.value.isNotEmpty() }

        vm.ensureFavoritesLoaded()
        waitUntil("收藏成功") { vm.favorites.value.isNotEmpty() }

        // 之后临时失败：旧数据必须保留，只给出可重试的错误态
        repo.failFavorites = true
        vm.retryFavorites()
        waitUntil("收藏报错") { vm.favoritesError.value != null }
        assertTrue("临时失败不得清空已有收藏", vm.favorites.value.isNotEmpty())
    }

    private fun waitUntil(label: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("等待超时: $label")
    }
}

/** 内存版搜索历史（不触碰真实 DataStore）。 */
private class StubClosureHistory :
    SearchHistoryStore(ApplicationProvider.getApplicationContext()) {
    private val memory = mutableListOf<String>()
    override suspend fun current(): List<String> = memory.toList()
    override suspend fun add(term: String) {
        memory.remove(term)
        memory.add(0, term)
    }

    override suspend fun clear() {
        memory.clear()
    }
}

/** 可注入故障的仓库：用于验证 P0/P1 状态解耦与收藏重试。 */
private class ClosureRepository : MediaRepository {
    var failFolders = false
    var failAlbums = false
    var failFavorites = false
    var favoritesCalls = 0
        private set
    var favoriteItems: List<V2Media> = emptyList()

    val media = V2Media(
        id = "m1",
        code = "",
        name = "启动媒体",
        folderId = "f1",
        folderName = "文件夹",
        type = V2MediaType.IMAGE,
        durationMs = 0L,
        sizeBytes = 0L,
        dateMillis = 0L,
        isFavorite = false,
        isReviewed = false,
        assetPath = "",
        thumbPath = "",
        spritePath = null,
        spriteManifestPath = null,
        naturalWidth = 0,
        naturalHeight = 0,
    )

    override val mode: V2DataMode = V2DataMode.DEMO

    override suspend fun mediaPage(query: V2MediaQuery): V2MediaPage =
        V2MediaPage(items = listOf(media), page = 1, pageSize = query.pageSize, total = 1)

    override suspend fun folders(): List<V2Folder> {
        if (failFolders) throw IOException("folders 不可用")
        return listOf(V2Folder("f1", "文件夹", "", listOf("m1"), count = 1, imageCount = 1))
    }

    override suspend fun albums(): List<V2Album> {
        if (failAlbums) throw IOException("albums 不可用")
        return emptyList()
    }

    override suspend fun favorites(): List<V2Media> {
        favoritesCalls++
        if (failFavorites) throw IOException("favorites 不可用")
        return favoriteItems
    }

    override suspend fun media(): List<V2Media> = listOf(media)

    override suspend fun media(spec: V2SortSpec): List<V2Media> = listOf(media)

    override suspend fun mediaInFolder(folderId: String, spec: V2SortSpec): List<V2Media> =
        listOf(media)

    override suspend fun search(query: String, spec: V2SortSpec): List<V2Media> = listOf(media)

    override fun mediaById(id: String): V2Media? = media.takeIf { it.id == id }

    override suspend fun setFavorite(mediaId: String, favorite: Boolean): Boolean = true

    override suspend fun markReviewed(mediaId: String) = Unit

    override suspend fun pendingDeleteIds(): Set<String> = emptySet()

    override suspend fun setPendingDelete(mediaId: String, pending: Boolean) = Unit

    override suspend fun unmarkReviewed(mediaId: String) = Unit

    override suspend fun resolvePlayback(mediaId: String): V2PlaybackSource = V2PlaybackSource(
        mediaId = mediaId,
        title = media.name,
        direct = V2PlaybackEndpoint(url = "http://127.0.0.1:1/never"),
    )

    override fun playbackUri(mediaId: String): String = ""

    override fun thumbUri(media: V2Media): String = ""

    override fun coverUri(media: V2Media): String = ""

    override fun imageUri(media: V2Media): String = ""

    override fun spriteUri(media: V2Media): String? = null

    override fun spriteManifest(media: V2Media): V2SpriteManifest? = null

    override suspend fun imagesInAlbum(albumId: String, spec: V2SortSpec): List<V2Media> =
        listOf(media)

    override suspend fun setAlbumCover(albumId: String, mediaId: String) = Unit
}