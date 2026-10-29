package com.mediareview.app.feature.v2.nsfw

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class GeneratedPhase { IDLE, LOADING, READY, UNAVAILABLE, ERROR }

data class GeneratedReviewState(
    val phase: GeneratedPhase = GeneratedPhase.IDLE,
    val queue: GeneratedImageQueue = GeneratedImageQueue(),
    val hasMore: Boolean = false,
    val message: String? = null,
)

/** 单页请求、前台调用；失败保留已有内容，绝不因空增量重置浏览位置。 */
class GeneratedReviewController(private val repository: GeneratedReviewRepository) {
    private val mutableState = MutableStateFlow(GeneratedReviewState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var cursor: String? = null
    private var watermark: String? = null

    fun select(id: String) {
        val current = state.value
        if (current.queue.items.any { it.id == id }) {
            mutableState.value = current.copy(queue = GeneratedImageQueue(current.queue.items, id))
        }
    }

    suspend fun loadMore() { if (state.value.hasMore) refresh() }

    suspend fun refresh() = mutex.withLock {
        mutableState.value = state.value.copy(phase = GeneratedPhase.LOADING, message = null)
        try {
            if (!repository.capabilities().incrementalFeed) {
                mutableState.value = state.value.copy(phase = GeneratedPhase.UNAVAILABLE,
                    message = "当前服务尚未提供持续图片审核能力")
                return@withLock
            }
            val page = repository.feed(cursor = cursor, after = if (cursor == null) watermark else null)
            // 防止错误游标导致无限重复拉取，保留数据供手动重试。
            require(page.nextCursor == null || page.nextCursor != cursor) { "分页游标未推进" }
            val nextQueue = state.value.queue.append(page.items)
            cursor = page.nextCursor
            watermark = page.watermark ?: watermark
            mutableState.value = state.value.copy(phase = GeneratedPhase.READY, queue = nextQueue,
                hasMore = cursor != null, message = null)
        } catch (cancelled: CancellationException) {
            mutableState.value = state.value.copy(phase = GeneratedPhase.IDLE)
            throw cancelled
        } catch (_: GeneratedCursorExpired) {
            cursor = null
            watermark = null
            mutableState.value = state.value.copy(phase = GeneratedPhase.ERROR, hasMore = false,
                message = "更新位置已过期，重试将重新核对列表，保留当前图片")
        } catch (_: Exception) {
            mutableState.value = state.value.copy(phase = GeneratedPhase.ERROR,
                message = "图片列表更新失败，已保留当前内容，请重试")
        }
    }
}
