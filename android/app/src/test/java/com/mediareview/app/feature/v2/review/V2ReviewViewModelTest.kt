package com.mediareview.app.feature.v2.review

import com.mediareview.app.feature.v2.AppMode
import com.mediareview.app.feature.v2.data.MediaRepository
import com.mediareview.app.feature.v2.model.V2Album
import com.mediareview.app.feature.v2.model.V2Folder
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2MediaType
import com.mediareview.app.feature.v2.model.V2SortSpec
import com.mediareview.app.feature.v2.model.V2SpriteManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Review 队列 / 待删除 / 重新批阅的 JVM 逻辑测试。
 * 校验：
 * - 队列 = 未审视频（不含已审/图片）；
 * - pendingDelete 加入与撤销独立于 isReviewed；
 * - 重新批阅清空本次队列的已批阅标记。
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
        vm.loadIfNeeded()
        assertTrue(vm.ready.value)
        val queue = vm.queue.value
        assertEquals(2, queue.size)
        assertTrue(queue.all { it.isVideo && !it.isReviewed })
    }

    @Test
    fun `待删除与已批阅相互独立且可撤销`() = runTest {
        val repo = FakeRepo(videos = 2, reviewedVideos = 0, images = 0)
        val vm = V2ReviewViewModel(repo)
        vm.loadIfNeeded()
        val target = vm.queue.value.first().id
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
    fun `重新批阅清空本次队列已批阅标记`() = runTest {
        val repo = FakeRepo(videos = 2, reviewedVideos = 0, images = 0)
        val vm = V2ReviewViewModel(repo)
        vm.loadIfNeeded()
        vm.queue.value.forEach { vm.markReviewed(it.id) }
        assertTrue(vm.reviewedIds.value.size == 2)
        vm.restartReview()
        assertTrue(vm.reviewedIds.value.isEmpty())
        assertTrue(repo.unmarked.isNotEmpty())
        assertEquals(vm.queue.value.map { it.id }.toSet(), repo.unmarked)
    }

    /** 内存仓库：可控视频/已审/图片数量。 */
    private class FakeRepo(
        videos: Int,
        reviewedVideos: Int,
        images: Int,
    ) : MediaRepository {
        private val catalog = buildList {
            repeat(videos) { i ->
                val reviewed = i < reviewedVideos
                add(V2Media(
                    id = "v$i", code = "V$i", name = "视频 $i",
                    folderId = "f", folderName = "F",
                    type = V2MediaType.VIDEO, durationMs = 10_000L,
                    sizeBytes = 1000L, dateMillis = 100L + i,
                    isFavorite = false, isReviewed = reviewed,
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

        private val favorite = mutableSetOf<String>()
        private val reviewed = mutableSetOf<String>()
        private val pendingDelete = mutableSetOf<String>()

        val unmarked = mutableSetOf<String>()

        override val mode: AppMode = AppMode.DEMO
        override suspend fun folders(): List<V2Folder> = emptyList()
        override suspend fun media(): List<V2Media> = catalog.map { m ->
            m.copy(
                isFavorite = m.id in favorite,
                isReviewed = m.isReviewed || m.id in reviewed,
            )
        }
        override suspend fun media(spec: V2SortSpec): List<V2Media> = media()
        override suspend fun mediaInFolder(folderId: String, spec: V2SortSpec): List<V2Media> = media()
        override suspend fun search(query: String, spec: V2SortSpec): List<V2Media> = media()
        override fun mediaById(id: String): V2Media? = catalog.find { it.id == id }
        override suspend fun setFavorite(mediaId: String, isFav: Boolean) {
            if (isFav) favorite += mediaId else favorite -= mediaId
        }
        override suspend fun markReviewed(mediaId: String) { reviewed += mediaId }
        override suspend fun pendingDeleteIds(): Set<String> = pendingDelete.toSet()
        override suspend fun setPendingDelete(mediaId: String, pending: Boolean) {
            if (pending) pendingDelete += mediaId else pendingDelete -= mediaId
        }
        override suspend fun unmarkReviewed(mediaId: String) {
            reviewed -= mediaId
            unmarked += mediaId
        }
        override fun playbackUri(mediaId: String): String = ""
        override fun thumbUri(media: V2Media): String = ""
        override fun coverUri(media: V2Media): String = ""
        override fun imageUri(media: V2Media): String = ""
        override fun spriteUri(media: V2Media): String? = null
        override fun spriteManifest(media: V2Media): V2SpriteManifest? = null
        override suspend fun albums(): List<V2Album> = emptyList()
        override suspend fun imagesInAlbum(albumId: String, spec: V2SortSpec): List<V2Media> = emptyList()
        override suspend fun setAlbumCover(albumId: String, mediaId: String) {}
    }
}