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
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 8A.1 §4/§6/§8 —— 首页启动优先级。
 *
 * 用可记录调用顺序的仓库验证：
 * - **P0** 首屏媒体（`mediaPage`）必须先于文件夹（`folders`）发生；
 * - 收藏（`favorites`）**不参与启动**，只有用户进入收藏页才加载；
 * - 文件夹补齐与首屏媒体在同一启动流程内发生（P1 后台并行）。
 *
 * 这是"顺序/惰性"语义测试，不依赖网络，因此可在模拟器上稳定运行。
 */
@RunWith(AndroidJUnit4::class)
class HomeStartupPriorityTest {

    @Test
    fun 启动时媒体最先开始且收藏不在启动路径内() {
        val repo = RecordingRepository()
        val vm = testHomeViewModel(repo, StubHistory())
        waitUntil("首屏媒体请求") { repo.events.contains("mediaPage") }
        waitUntil("文件夹补齐") { repo.events.contains("folders") }

        val mediaIndex = repo.events.indexOf("mediaPage")
        val foldersIndex = repo.events.indexOf("folders")
        assertTrue(
            "首屏媒体必须先于文件夹，实际顺序=${repo.events}",
            mediaIndex >= 0 && foldersIndex >= 0 && mediaIndex < foldersIndex,
        )
        assertEquals("收藏不得参与启动，实际顺序=${repo.events}", 0, repo.favoritesCalls)
        assertTrue("启动后应已有媒体数据", vm.currentList.value.isNotEmpty())
    }

    @Test
    fun 进入收藏页时才加载收藏且只加载一次() {
        val repo = RecordingRepository()
        val vm = testHomeViewModel(repo, StubHistory())
        waitUntil("首屏媒体请求") { repo.events.contains("mediaPage") }
        assertEquals(0, repo.favoritesCalls)

        vm.ensureFavoritesLoaded()
        waitUntil("收藏加载") { repo.favoritesCalls >= 1 }
        // 重复进入收藏页不得重复请求
        vm.ensureFavoritesLoaded()
        Thread.sleep(200)
        assertEquals("收藏只应加载一次", 1, repo.favoritesCalls)
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
private class StubHistory : SearchHistoryStore(ApplicationProvider.getApplicationContext()) {
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

/** 记录调用顺序的仓库：只服务启动优先级断言。 */
private class RecordingRepository : MediaRepository {
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    var favoritesCalls: Int = 0
        private set

    private val media = V2Media(
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

    override suspend fun mediaPage(query: V2MediaQuery): V2MediaPage {
        events += "mediaPage"
        return V2MediaPage(items = listOf(media), page = 1, pageSize = query.pageSize, total = 1)
    }

    override suspend fun folders(): List<V2Folder> {
        events += "folders"
        return listOf(V2Folder("f1", "文件夹", "", listOf("m1"), count = 1, imageCount = 1))
    }

    override suspend fun albums(): List<V2Album> {
        events += "albums"
        return emptyList()
    }

    override suspend fun favorites(): List<V2Media> {
        favoritesCalls++
        events += "favorites"
        return emptyList()
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

    override suspend fun resolvePlayback(mediaId: String): V2PlaybackSource =
        V2PlaybackSource(
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

    override suspend fun albumPage(
        albumId: String,
        page: Int,
        pageSize: Int,
        spec: V2SortSpec,
    ): com.mediareview.app.feature.v2.model.V2MediaPage =
        com.mediareview.app.feature.v2.model.V2MediaPage(listOf(media), page, pageSize, 1)

    override suspend fun setAlbumCover(albumId: String, mediaId: String) = Unit
}