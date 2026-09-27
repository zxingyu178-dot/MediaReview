package com.mediareview.app.feature.v2.review.data

import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.core.model.Envelope
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.core.model.ReviewCreateRequest
import com.mediareview.app.core.model.ReviewPositionRequest
import com.mediareview.app.core.model.ReviewQueueItemDto
import com.mediareview.app.core.model.ReviewSeenRequest
import com.mediareview.app.core.model.ReviewSessionDto
import com.mediareview.app.core.model.ReviewSourceDto
import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.MediaReviewApi
import com.mediareview.app.feature.v2.data.server.V2MediaMapper
import com.mediareview.app.feature.v2.data.server.V2ServerProfilePort
import com.mediareview.app.feature.v2.data.server.V2ServerResourceCache
import com.mediareview.app.feature.v2.data.server.V2ServerStatusStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/**
 * Production 批阅会话仓库（Stage 8B §15：**复用现有 Server Review API**，不新造 Review Server）。
 *
 * 使用的服务端合同（server/app/api/v1/review.py）：
 * ```
 * POST /api/v1/review/sessions               新建会话（服务端先完成旧 active）
 * GET  /api/v1/review/sessions/latest        断点恢复（没有 active 时 404 NOT_FOUND）
 * GET  /api/v1/review/sessions/{id}/queue    队列分页（page / page_size ≤ 200，返回绝对 index）
 * POST /api/v1/review/sessions/{id}/seen     标记已看（服务端确认）
 * POST /api/v1/review/sessions/{id}/position 更新 current_index（断点恢复依据）
 * POST /api/v1/review/sessions/{id}/complete 手动完成
 * ```
 *
 * 关键约束：
 * - **禁批量 resolvePlayback**（§5）：本仓库只处理 metadata，播放地址由 ViewModel 按需解析；
 * - **不拉全量队列**（§20）：每页 50，只加载 current_index 所在页与相邻页；
 * - **latest 失败不建会话**（§17）：只有明确 404 才新建；
 * - 队列项的封面 / 媒体映射复用 [V2MediaMapper] 与共享 [V2ServerResourceCache]，
 *   与媒体墙同一份映射（完整播放器据此可以通过 `mediaById` 打开队列里的当前媒体）。
 */
@Singleton
class V2ServerReviewSessionRepository internal constructor(
    private val profilePort: V2ServerProfilePort,
    private val apiFactory: ApiFactory,
    private val mapper: V2MediaMapper,
    private val resources: V2ServerResourceCache,
    private val statusStore: V2ServerStatusStore,
) : V2ReviewSessionRepository {

    @Inject
    constructor(
        profileStore: ServerProfileStore,
        apiFactory: ApiFactory,
        mapper: V2MediaMapper,
        resources: V2ServerResourceCache,
        statusStore: V2ServerStatusStore,
    ) : this(
        profilePort = object : V2ServerProfilePort {
            override suspend fun baseUrl(): String = profileStore.current().baseUrl
        },
        apiFactory = apiFactory,
        mapper = mapper,
        resources = resources,
        statusStore = statusStore,
    )

    private var sessionId: String = ""
    private var totalCount: Int = 0
    private var page: Int = 1
    private var baseIndex: Int = 0
    private var items: List<ReviewQueueItemUi> = emptyList()

    // ---------- 进入会话 ----------

    override suspend fun enterSession(forceNew: Boolean): ReviewSessionOpen {
        return try {
            val info = if (forceNew) createNewSession() else restoreOrCreate()
            if (info.totalCount <= 0) return ReviewSessionOpen.Empty
            openWindow(info)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // §17：任何失败都必须如实上报，绝不"失败即新建会话"
            ReviewSessionOpen.Failed(error.message ?: "无法恢复批阅")
        }
    }

    /** 恢复最新 active 会话；**只有服务端明确 404（NOT_FOUND）才新建**。 */
    private suspend fun restoreOrCreate(): ReviewSessionInfo {
        val latest = try {
            call { api, _ -> unwrap(api.latestReviewSession()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (notFound: HttpException) {
            if (notFound.code() == HTTP_NOT_FOUND) return createNewSession() else throw notFound
        }
        return latest.toInfo()
    }

    /**
     * 新建会话（§18）：V2 产品批阅当前是 VIDEO ONLY，排序沿用产品默认（最新添加倒序）。
     * 服务端建会话时会先完成旧 active 会话（§39），因此不需要逐项重置旧队列。
     */
    private suspend fun createNewSession(): ReviewSessionInfo {
        val body = ReviewCreateRequest(
            source = ReviewSourceDto(
                filter = mapOf("media_type" to "video"),
                sort = mapOf("sort_by" to "created", "sort_order" to "desc"),
            ),
        )
        return call { api, _ -> unwrap(api.createReviewSession(body)) }.toInfo()
    }

    /** 定位到 current_index 所在分页并加载（§21：637/1000 → 直接取包含 637 的那一页）。 */
    private suspend fun openWindow(info: ReviewSessionInfo): ReviewSessionOpen {
        sessionId = info.sessionId
        val anchor = info.currentIndex.coerceIn(0, (info.totalCount - 1).coerceAtLeast(0))
        val plan = ReviewQueuePaging.resumePlan(anchor)
        val loaded = fetchPage(plan.page)
        page = plan.page
        baseIndex = plan.baseIndex
        totalCount = loaded.totalCount
        items = loaded.items
        return ReviewSessionOpen.Ready(
            session = info.copy(currentIndex = anchor),
            window = snapshot(),
        )
    }

    // ---------- 分页 ----------

    override suspend fun loadNextPage(): ReviewQueuePageResult? {
        if (sessionId.isBlank()) return null
        if (snapshot().atEnd) return null
        return try {
            val next = page + 1
            val loaded = fetchPage(next)
            if (loaded.items.isEmpty()) return null
            page = next
            items = items + loaded.items
            ReviewQueuePageResult(window = snapshot())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun loadPrevPage(): ReviewQueuePageResult? {
        if (sessionId.isBlank()) return null
        if (!snapshot().canLoadPrev) return null
        val prev = page - 1
        if (prev < 1) return null
        return try {
            val loaded = fetchPage(prev)
            if (loaded.items.isEmpty()) return null
            val added = loaded.items.size
            page = prev
            baseIndex = loaded.baseIndex
            items = loaded.items + items
            ReviewQueuePageResult(window = snapshot(), prependedCount = added)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 拉取一页并映射：绝对索引原样保留服务端给出的 `index`（缺项不压缩索引）。
     */
    private suspend fun fetchPage(page: Int): ReviewQueueWindow {
        val (dto, baseUrl) = call { api, base ->
            unwrap(
                api.reviewQueue(sessionId, page = page, pageSize = ReviewQueuePaging.PAGE_SIZE),
            ) to base
        }
        val summaries = dto.items.mapNotNull { it.media }.filter { it.media_id.isNotBlank() }
        resources.putAll(summaries.map { mapper.mapMedia(summary = it, baseUrl = baseUrl) })
        return ReviewQueueWindow(
            items = dto.items.mapNotNull { it.toUi() },
            baseIndex = (page - 1) * ReviewQueuePaging.PAGE_SIZE,
            totalCount = dto.total,
            page = page,
        )
    }

    private fun ReviewQueueItemDto.toUi(): ReviewQueueItemUi? {
        val summary: MediaSummary = media ?: return null
        if (summary.media_id.isBlank()) return null
        return ReviewQueueItemUi(
            absoluteIndex = index,
            mediaId = summary.media_id,
            title = summary.name,
            // Server 无"编号"概念：留空由 UI 裁剪，不伪造
            code = "",
            folderName = summary.folder_name.orEmpty(),
            durationMs = summary.duration_ms ?: 0L,
            naturalWidth = summary.width ?: 0,
            naturalHeight = summary.height ?: 0,
            coverUrl = resources.coverUrl(summary.media_id).orEmpty(),
            favorite = summary.is_favorite,
        )
    }

    // ---------- 状态变更 ----------

    override suspend fun markSeen(mediaId: String): Boolean {
        if (sessionId.isBlank() || mediaId.isBlank()) return false
        return try {
            call { api, _ -> unwrap(api.markSeen(sessionId, ReviewSeenRequest(media_id = mediaId))) }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // §26：失败不得假成功（UI 保持未批阅并可重试）
            false
        }
    }

    override suspend fun savePosition(absoluteIndex: Int) {
        if (sessionId.isBlank()) return
        try {
            call { api, _ ->
                unwrap(
                    api.setReviewPosition(
                        sessionId,
                        ReviewPositionRequest(index = absoluteIndex.coerceAtLeast(0)),
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // 位置属于断点恢复闭环：失败不打断批阅；下一次 settled 会重新上报
        }
    }

    override suspend fun completeSession(): Boolean {
        if (sessionId.isBlank()) return false
        return try {
            call { api, _ -> unwrap(api.completeReviewSession(sessionId)) }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
    }

    /** Server 语义：重新批阅 = 新建会话（§39，不是逐项重置旧会话）。 */
    override suspend fun restart(): ReviewSessionOpen = enterSession(forceNew = true)

    override suspend fun restartAll(): ReviewSessionOpen = enterSession(forceNew = true)

    // ---------- 访问层 ----------

    private suspend fun <T> call(block: suspend (MediaReviewApi, String) -> T): T {
        return try {
            val baseUrl = profilePort.baseUrl()
            require(baseUrl.isNotBlank()) { "尚未连接服务器" }
            val result = block(apiFactory.create(baseUrl), baseUrl)
            statusStore.onRequestSuccess()
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            statusStore.onRequestFailure(error)
            throw error
        }
    }

    private fun <T> unwrap(resp: Envelope<T>): T {
        if (!resp.success) {
            throw IllegalStateException(resp.error?.message ?: "服务器返回错误")
        }
        return resp.data ?: throw IllegalStateException("服务器返回空数据")
    }

    private fun snapshot(): ReviewQueueWindow = ReviewQueueWindow(
        items = items,
        baseIndex = baseIndex,
        totalCount = totalCount,
        page = page,
    )

    private fun ReviewSessionDto.toInfo(): ReviewSessionInfo = ReviewSessionInfo(
        sessionId = session_id,
        totalCount = total_count,
        currentIndex = current_index,
        seenCount = seen_count,
    )

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}