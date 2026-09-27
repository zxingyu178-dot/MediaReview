package com.mediareview.app.feature.v2.review

import com.mediareview.app.feature.v2.data.MediaRepository
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
import com.mediareview.app.feature.v2.model.V2TypeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Review Session / 队列生命周期的 JVM 逻辑测试（Stage 7）。
 * 校验：
 * - 队列 = 未审视频（不含已审/图片）；
 * - 再次进入 Review（enterReview）能看到最新未审队列（不再依赖 loadIfNeeded 偶然状态）；
 * - 空队列"重新批阅"可真正重建全量队列（restartAllVideos）；
 * - 完成条件 = 队列全部已批阅；缺任一未审条目不完成（取代旧"末页已批阅"弱判定）；
 * - 完整播放器返回（onLeaveForFullPlayer → enterReview）恢复原 Session（页/已批阅）；
 * - pendingDelete 与 reviewed 独立、可撤销；
 * - restart 后所有 Session 条目回到未审。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class V2ReviewViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `队列只含未审视频`() = runTest {
        val repo = FakeRepo(videos = 3, reviewedVideos = 1, images = 2)
        val vm = V2ReviewViewModel(repo)
        vm.enterReview()
        assertTrue(vm.ready.value)
        val queue = vm.queue.value
        assertEquals(2, queue.size)
        assertTrue(queue.all { repo.isVideo(it.mediaId) && !repo.isReviewedById(it.mediaId) })
    }

    @Test
    fun `再次进入 Review 能看到最新未审队列`() = runTest {
        val repo = FakeRepo(videos = 3, reviewedVideos = 0, images = 0)
        val vm = V2ReviewViewModel(repo)
        vm.enterReview()
        assertEquals(listOf("v0", "v1", "v2"), vm.queue.value.map { it.mediaId })
        // 外部（如首页）标记 v1 已批阅
        repo.markReviewed("v1")
        vm.enterReview()
        assertEquals(listOf("v0", "v2"), vm.queue.value.map { it.mediaId })
    }

    @Test
    fun `空队列重新批阅可真正重建全量队列`() = runTest {
        val repo = FakeRepo(videos = 3, reviewedVideos = 3, images = 0)
        val vm = V2ReviewViewModel(repo)
        vm.enterReview()
        assertTrue(vm.queue.value.isEmpty())
        assertTrue(vm.ready.value)
        // 空队列页"重新批阅"：清除全部视频已批阅并重建全量队列
        vm.restartAllVideos()
        assertEquals(listOf("v0", "v1", "v2"), vm.queue.value.map { it.mediaId })
        assertTrue(vm.queue.value.all { !vm.isReviewed(it.mediaId) })
        assertTrue(vm.reviewedIds.value.isEmpty())
        assertEquals(0, vm.currentIndex.value)
    }

    @Test
    fun `最后一条已批阅但前面还有未审_NOT_complete`() = runTest {
        val repo = FakeRepo(videos = 3, reviewedVideos = 0, images = 0)
        val vm = V2ReviewViewModel(repo)
        vm.enterReview()
        assertFalse(vm.isComplete.value)
        vm.markReviewed("v2") // 只有最后一条已审
        assertFalse(vm.isComplete.value)
        // 补齐其余两条后才完成
        vm.markReviewed("v0")
        vm.markReviewed("v1")
        assertTrue(vm.isComplete.value)
    }

    @Test
    fun `全部已批阅才 complete`() = runTest {
        val repo = FakeRepo(videos = 3, reviewedVideos = 0, images = 0)
        val vm = V2ReviewViewModel(repo)
        vm.enterReview()
        vm.queue.value.forEach { vm.markReviewed(it.mediaId) }
        assertTrue(vm.isComplete.value)
        assertTrue(repo.reviewed == vm.queue.value.map { it.mediaId }.toSet())
    }

    @Test
    fun `待删除与已批阅相互独立且可撤销`() = runTest {
        val repo = FakeRepo(videos = 2, reviewedVideos = 0, images = 0)
        val vm = V2ReviewViewModel(repo)
        vm.enterReview()
        val target = vm.queue.value.first().mediaId
        vm.addPendingDelete(target)
        assertTrue(target in vm.pendingDeleteIds.value)
        // 待删除不改变批阅状态
        vm.markReviewed(target)
        assertTrue(vm.isReviewed(target))
        assertTrue(vm.isPendingDelete(target))
        // 撤销后从集合移除
        vm.undoPendingDelete(target)
        assertTrue(target !in vm.pendingDeleteIds.value)
        assertEquals(emptySet<String>(), repo.pendingDeleteIds())
    }

    @Test
    fun `restartCurrentSession 清空本次队列已批阅标记`() = runTest {
        val repo = FakeRepo(videos = 2, reviewedVideos = 0, images = 0)
        val vm = V2ReviewViewModel(repo)
        vm.enterReview()
        vm.queue.value.forEach { vm.markReviewed(it.mediaId) }
        assertTrue(vm.reviewedIds.value.size == 2)
        vm.restartCurrentSession()
        assertTrue(vm.reviewedIds.value.isEmpty())
        assertEquals(vm.queue.value.map { it.mediaId }.toSet(), repo.unmarked)
        assertFalse(vm.isComplete.value)
    }

    @Test
    fun `restartAllVideos 后所有视频回到未审`() = runTest {
        val repo = FakeRepo(videos = 3, reviewedVideos = 1, images = 1)
        val vm = V2ReviewViewModel(repo)
        vm.enterReview()
        assertEquals(2, vm.queue.value.size)
        vm.markReviewed(vm.queue.value.first().mediaId)
        vm.restartAllVideos()
        // 全量视频均已清除批阅标记
        assertEquals(repo.allMediaIds, repo.unmarked)
        assertEquals(3, vm.queue.value.size)
        assertTrue(vm.queue.value.all { !vm.isReviewed(it.mediaId) })
        assertEquals(0, vm.currentIndex.value)
    }

    @Test
    fun `完整播放器返回恢复原 Session 不重建队列`() = runTest {
        val repo = FakeRepo(videos = 3, reviewedVideos = 0, images = 0)
        val vm = V2ReviewViewModel(repo)
        vm.enterReview()
        vm.onPageSettled(1)
        vm.markReviewed("v0") // 已看第 1 条
        vm.onLeaveForFullPlayer()
        // 模拟完整播放器返回：恢复会话，不重建队列、不跳回第 1 条
        vm.enterReview()
        assertEquals(3, vm.queue.value.size)
        assertEquals(1, vm.currentIndex.value)
        assertTrue("v0" in vm.reviewedIds.value)
        assertFalse(vm.isComplete.value)
    }

    @Test
    fun `离开Tab再进入重建最新队列且回到第1条`() = runTest {
        val repo = FakeRepo(videos = 3, reviewedVideos = 0, images = 0)
        val vm = V2ReviewViewModel(repo)
        vm.enterReview()
        vm.onPageSettled(1)
        vm.markReviewed("v0")
        // 正常离开（非完整播放器）后再次进入：刷新队列
        vm.enterReview()
        assertEquals(2, vm.queue.value.size) // v0 已审，不在新队列
        assertEquals(0, vm.currentIndex.value)
    }

    @Test
    fun `Headers 与对应媒体一一对应不串源`() = runTest {
        val repo = FakeRepo(videos = 2, reviewedVideos = 0, images = 0)
        val vm = V2ReviewViewModel(repo)
        vm.enterReview()
        val a = vm.queue.value[0]
        val b = vm.queue.value[1]
        // 每条媒体的 header 都携带各自的 mediaId（Demo debug header 语义）
        assertEquals(a.mediaId, a.headers["X-Debug-Media"])
        assertEquals(b.mediaId, b.headers["X-Debug-Media"])
        // A 的 Header 不会出现在 B 上
        assertNotEquals(a.headers, b.headers)
        assertEquals(1, a.headers.keys.size)
        assertEquals(1, b.headers.keys.size)
    }

    @Test
    fun `单视频队列浏览完成后重新批阅回到可审`() = runTest {
        val repo = FakeRepo(videos = 1, reviewedVideos = 0, images = 0)
        val vm = V2ReviewViewModel(repo)
        vm.enterReview()
        assertEquals(1, vm.queue.value.size)
        // 浏览完成：标记该条 → complete
        vm.markReviewed(vm.queue.value.first().mediaId)
        assertTrue(vm.isComplete.value)
        // 完成页"重新批阅"（restartCurrentSession）：
        vm.restartCurrentSession()
        assertTrue(vm.reviewedIds.value.isEmpty())
        assertFalse(vm.isComplete.value)
        assertEquals(0, vm.currentIndex.value)
        assertTrue(vm.queue.value.single().mediaId in repo.unmarked)
    }

    /** 内存仓库：可控视频/已审/图片数量；可查询/修改外部状态。 */
    private class FakeRepo(
        videos: Int,
        reviewedVideos: Int,
        images: Int,
    ) : MediaRepository {
        private val catalog = buildList {
            repeat(videos) { i ->
                add(V2Media(
                    id = "v$i", code = "V$i", name = "视频 $i",
                    folderId = "f", folderName = "F",
                    type = V2MediaType.VIDEO, durationMs = 10_000L,
                    sizeBytes = 1000L, dateMillis = 100L + i,
                    isFavorite = false, isReviewed = false,
                    assetPath = "demo_media/videos/01_landscape.mp4",
                    thumbPath = "", spritePath = null, spriteManifestPath = null,
                    naturalWidth = 1280, naturalHeight = 720,
                ))
            }
            repeat(images) { i ->
                add(V2Media(
                    id = "img$i", code = "I$i", name = "图片 $i",
                    folderId = "f", folderName = "F",
                    type = V2MediaType.IMAGE, durationMs = 0L,
                    sizeBytes = 500L, dateMillis = 200L + i,
                    isFavorite = false, isReviewed = false,
                    assetPath = "demo_media/images/img_landscape_01.jpg",
                    thumbPath = "", spritePath = null, spriteManifestPath = null,
                    naturalWidth = 1280, naturalHeight = 720,
                ))
            }
        }

        val allMediaIds: Set<String> = catalog.filter { it.isVideo }.map { it.id }.toSet()

        private val favorite = mutableSetOf<String>()
        val reviewed = mutableSetOf<String>()
        private val pendingDelete = mutableSetOf<String>()

        val unmarked = mutableSetOf<String>()

        init {
            // 初始已批阅（完全由 reviewed 集合驱动，unmarkReviewed 才能真正清除）
            repeat(reviewedVideos) { i -> reviewed += "v$i" }
        }

        override val mode: V2DataMode = V2DataMode.DEMO
        override suspend fun folders(): List<V2Folder> = emptyList()
        override suspend fun mediaPage(query: V2MediaQuery): V2MediaPage {
            val items = media().let { list ->
                when (query.spec.typeFilter) {
                    V2TypeFilter.ALL -> list
                    V2TypeFilter.VIDEO -> list.filter { it.isVideo }
                    V2TypeFilter.IMAGE -> list.filter { !it.isVideo }
                }
            }
            return V2MediaPage(items = items, page = 1, pageSize = query.pageSize, total = items.size)
        }
        override suspend fun media(): List<V2Media> = catalog.map { m ->
            m.copy(
                isFavorite = m.id in favorite,
                isReviewed = m.id in reviewed,
            )
        }
        override suspend fun media(spec: V2SortSpec): List<V2Media> = media()
        override suspend fun mediaInFolder(folderId: String, spec: V2SortSpec): List<V2Media> = media()
        override suspend fun search(query: String, spec: V2SortSpec): List<V2Media> = media()
        override fun mediaById(id: String): V2Media? = catalog.find { it.id == id }
        override suspend fun setFavorite(mediaId: String, isFav: Boolean): Boolean {
            if (isFav) favorite += mediaId else favorite -= mediaId
            return true
        }
        override suspend fun favorites(): List<V2Media> = media().filter { it.isFavorite }
        override suspend fun markReviewed(mediaId: String) { reviewed += mediaId }
        override suspend fun pendingDeleteIds(): Set<String> = pendingDelete.toSet()
        override suspend fun setPendingDelete(mediaId: String, pending: Boolean): Boolean {
            if (pending) pendingDelete += mediaId else pendingDelete -= mediaId
            return true
        }
        override suspend fun unmarkReviewed(mediaId: String) {
            reviewed -= mediaId
            unmarked += mediaId
        }
        override suspend fun resolvePlayback(mediaId: String): V2PlaybackSource = V2PlaybackSource(
            mediaId = mediaId,
            title = mediaId,
            direct = V2PlaybackEndpoint(url = "uri://$mediaId"),
        )
        override fun playbackUri(mediaId: String): String = ""
        override fun playbackHeaders(mediaId: String): Map<String, String> =
            mapOf("X-Debug-Media" to mediaId)
        override fun thumbUri(media: V2Media): String = ""
        override fun coverUri(media: V2Media): String = ""
        override fun imageUri(media: V2Media): String = ""
        override fun spriteUri(media: V2Media): String? = null
        override fun spriteManifest(media: V2Media): V2SpriteManifest? = null
        override suspend fun albums(): List<V2Album> = emptyList()
        override suspend fun albumPage(
            albumId: String,
            page: Int,
            pageSize: Int,
            spec: V2SortSpec,
        ): com.mediareview.app.feature.v2.model.V2MediaPage =
            com.mediareview.app.feature.v2.model.V2MediaPage(emptyList(), page, pageSize, 0)
        override suspend fun setAlbumCover(albumId: String, mediaId: String) {}

        fun isVideo(id: String): Boolean = catalog.find { it.id == id }?.isVideo == true
        fun isReviewedById(id: String): Boolean = id in reviewed
    }
}