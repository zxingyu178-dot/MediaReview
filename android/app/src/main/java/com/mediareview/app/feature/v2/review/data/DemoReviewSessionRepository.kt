package com.mediareview.app.feature.v2.review.data

import com.mediareview.app.feature.v2.data.MediaRepository
import com.mediareview.app.feature.v2.model.V2Media
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Demo 批阅会话仓库（Stage 7.1 行为保持不变，只把状态收敛到统一合同）：
 *
 * - 队列 = 进入时刻的**未批阅视频**（VIDEO ONLY），单页即全量（无分页；
 *   [loadNextPage] / [loadPrevPage] 恒为 null，窗口 `atEnd` 为 true）；
 * - `seen` = 本地 `markReviewed`（Demo 无服务端确认概念，本地必然成功）；
 * - `restart` = 本队列重审（清除当前队列的已批阅标记并重建同一队列）；
 * - `restartAll` = 全库重审（清除所有视频的已批阅标记并重建全量视频队列）；
 * - 位置只在内存会话里（App 重启不恢复），与线上 Server 模式语义区分。
 */
@Singleton
class DemoReviewSessionRepository @Inject constructor(
    private val repository: MediaRepository,
) : V2ReviewSessionRepository {

    private var currentQueue: List<ReviewQueueItemUi> = emptyList()

    override suspend fun enterSession(forceNew: Boolean): ReviewSessionOpen {
        val videos = allVideos().filter { !it.isReviewed }
        return openQueue(videos)
    }

    override suspend fun loadNextPage(): ReviewQueuePageResult? = null

    override suspend fun loadPrevPage(): ReviewQueuePageResult? = null

    override suspend fun markSeen(mediaId: String): Boolean {
        repository.markReviewed(mediaId)
        return true
    }

    override suspend fun savePosition(absoluteIndex: Int) {
        // Demo 无服务端断点恢复：位置只保留在内存会话里（UI 由 ViewModel 维护）。
    }

    override suspend fun completeSession(): Boolean = true

    /** Demo：本队列重审（不重建队列内容，符合 Stage 7.1 的"排队列不跳动"原则）。 */
    override suspend fun restart(): ReviewSessionOpen {
        val mediaIds = currentQueue.map { it.mediaId }
        mediaIds.forEach { repository.unmarkReviewed(it) }
        return openQueue(mediaIds.mapNotNull { repository.mediaById(it) })
    }

    override suspend fun restartAll(): ReviewSessionOpen = openQueue(allVideos())

    private suspend fun openQueue(videos: List<V2Media>): ReviewSessionOpen {
        if (videos.isEmpty()) {
            currentQueue = emptyList()
            return ReviewSessionOpen.Empty
        }
        currentQueue = videos.mapIndexed { index, media -> media.toUi(index) }
        return ReviewSessionOpen.Ready(
            session = ReviewSessionInfo(
                sessionId = "demo-session",
                totalCount = currentQueue.size,
                currentIndex = 0,
                seenCount = 0,
            ),
            window = ReviewQueueWindow(
                items = currentQueue,
                baseIndex = 0,
                totalCount = currentQueue.size,
                page = 1,
            ),
        )
    }

    private suspend fun allVideos(): List<V2Media> = repository.media().filter { it.isVideo }

    private fun V2Media.toUi(index: Int): ReviewQueueItemUi = ReviewQueueItemUi(
        absoluteIndex = index,
        mediaId = id,
        title = name,
        code = code,
        folderName = folderName,
        durationMs = durationMs,
        naturalWidth = naturalWidth,
        naturalHeight = naturalHeight,
        coverUrl = repository.coverUri(this),
        favorite = isFavorite,
    )
}