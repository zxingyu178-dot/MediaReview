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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

/**
 * Production 批阅会话仓库（Stage 8B §15：**复用现有 Server Review API**，不新造 Review Server）。
 *
 * 使用的服务端合同（server/app/api/v1/review.py）：
 * ```
 * POST /api/v1/review/sessions               新建会话（服务端先完成旧 active）
 * GET  /api/v1/review/sessions/latest        断点恢复（没有 active 时 404 NOT_FOUND）
 * GET  /api/v1/review/sessions/{id}          会话权威进度（total/seen/unavailable/remaining/completed）
 * GET  /api/v1/review/sessions/{id}/queue    队列分页（page / page_size ≤ 200，绝对 index + seen）
 * GET  /api/v1/review/sessions/{id}/nearest  最近可用项（SQL 直查：forward / backward / nearest）
 * POST /api/v1/review/sessions/{id}/seen     标记已看（返回权威进度计数）
 * POST /api/v1/review/sessions/{id}/position 更新 current_index（断点恢复依据）
 * POST /api/v1/review/sessions/{id}/complete 手动完成（服务端 fail-closed：remaining>0 拒绝）
 * ```
 *
 * Stage 8B.1：双向分页窗口、合并去重、缺项 localIndexOf、seen 权威、完成门槛、single-flight。
 * Stage 8B.2（评审 §15~§24 / §33 / §34）：**媒体可用性 / 稀疏队列**
 * - **恢复不再逐页扫描**：`current_index` → `nearest available`（1 次 SQL 命中）→ 只拉目标页
 *   （正常恢复 ≤ 1 nearest + 1 queue page）；
 * - **next / prev 空页跳页**：整页不可用时用 nearest 直接跳到下一个/上一个可用页，
 *   删除 `MAX_EMPTY_PAGE_SCAN` 的多页扫描路径；
 * - **current_index 越界**（>= total_count）→ 优先 nearest backward 找最后一个可用项；
 * - **整个 Session 都不可用** → [ReviewSessionOpen.NoAvailableMedia]（不再每次重扫整个队列）；
 * - **网络失败 ≠ 没有可用媒体**：nearest 失败向上抛（恢复 → Failed；分页 → null 可重试）。
 *
 * 关键约束：
 * - **禁批量 resolvePlayback**（§5）：本仓库只处理 metadata，播放地址由 ViewModel 按需解析；
 * - **不拉全量队列**（§20）：每页 50，只加载目标页与相邻页；
 * - **latest 失败不建会话**（§17）：只有明确 404 才新建；
 * - 队列项的封面 / 媒体映射复用 [V2MediaMapper] 与共享 [V2ServerResourceCache]。
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
    private var pageSize: Int = ReviewQueuePaging.PAGE_SIZE
    private var firstLoadedPage: Int = 0
    private var lastLoadedPage: Int = 0
    private var items: List<ReviewQueueItemUi> = emptyList()

    /** 分页 single-flight（§10）：next / prev 绝不同时修改窗口。 */
    private val pagingMutex = Mutex()

    // ---------- 进入会话 ----------

    override suspend fun enterSession(forceNew: Boolean): ReviewSessionOpen {
        return try {
            val info = if (forceNew) createNewSession() else restoreOrCreate()
            if (info.totalCount <= 0) return ReviewSessionOpen.Empty
            openWindow(info)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // §17/§34：任何失败都必须如实上报，绝不"失败即新建会话"、绝不当作"没有可用媒体"
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

    /**
     * 定位到 current_index 并加载它所在的那一页（Stage 8B.2 §18：**不再逐页扫描**）。
     *
     * ```
     * Server current_index → nearestAvailable(current_index) → 真实 absoluteIndex
     *   → 该索引所在页 → 只 GET 那一页（正常恢复 = 1 nearest + 1 queue page）
     * ```
     *
     * - §7：恢复出的真实绝对索引 != current_index 时**写回服务端 position**；
     * - §33：`current_index >= total_count`（旧 Session / 异常状态）优先 nearest backward；
     * - §20：nearest 明确返回 null（整个 Session 都没有可用媒体）→ [ReviewSessionOpen.NoAvailableMedia]；
     * - §34：nearest 网络失败向上抛 → [ReviewSessionOpen.Failed]（绝不当成"没有可用媒体"）。
     */
    private suspend fun openWindow(info: ReviewSessionInfo): ReviewSessionOpen {
        sessionId = info.sessionId
        pageSize = ReviewQueuePaging.PAGE_SIZE
        totalCount = info.totalCount
        val resolved = if (info.currentIndex >= info.totalCount) {
            // 越界状态：优先向后找最后一个可用项（严格小于 totalCount）
            nearestAvailable(index = info.totalCount, direction = NearestDirection.BACKWARD)
        } else {
            val anchor = info.currentIndex.coerceIn(0, (info.totalCount - 1).coerceAtLeast(0))
            nearestAvailable(index = anchor, direction = NearestDirection.NEAREST)
        } ?: return ReviewSessionOpen.NoAvailableMedia
        val page = pageOf(resolved)
        val loaded = fetchPage(page)
        firstLoadedPage = page
        lastLoadedPage = page
        items = loaded.items
        totalCount = loaded.totalCount
        if (resolved != info.currentIndex) {
            pushPosition(info.sessionId, resolved)
        }
        return ReviewSessionOpen.Ready(
            session = info.copy(currentIndex = resolved),
            window = snapshot(),
        )
    }

    // ---------- 分页 ----------

    /**
     * 加载下一页：`lastLoadedPage + 1` → 若该页整页不可用，用
     * `nearest(forward, index = 该页起点)` 直接跳到下一个可用页（§22）。
     *
     * 禁止逐页扫描（旧的 `MAX_EMPTY_PAGE_SCAN` 已删除，§24）。
     */
    override suspend fun loadNextPage(): ReviewQueuePageResult? = pagingMutex.withLock {
        if (sessionId.isBlank() || atEnd()) return@withLock null
        val lastPage = ReviewQueuePaging.totalPages(totalCount, pageSize)
        val page = lastLoadedPage + 1
        if (page > lastPage) {
            lastLoadedPage = lastPage
            return@withLock ReviewQueuePageResult(window = snapshot())
        }
        val loaded = try {
            fetchPage(page)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return@withLock null
        }
        if (loaded.items.isNotEmpty()) {
            lastLoadedPage = page
            items = ReviewQueuePaging.mergeItems(items, loaded.items)
            return@withLock ReviewQueuePageResult(window = snapshot())
        }
        // §9/§22：整页不可用 → 记录该页已检查，并直接跳页
        lastLoadedPage = page
        val jump = try {
            nearestAvailable(index = page * pageSize, direction = NearestDirection.FORWARD)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return@withLock null
        }
        if (jump == null) {
            // 该页之后没有任何可用媒体：边界推进到最后一页（atEnd 成立，不再请求）
            lastLoadedPage = lastPage
            return@withLock ReviewQueuePageResult(window = snapshot())
        }
        val jumpPage = pageOf(jump).coerceIn(page + 1, lastPage)
        val jumped = try {
            fetchPage(jumpPage)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return@withLock null
        }
        lastLoadedPage = jumpPage
        if (jumped.items.isNotEmpty()) {
            items = ReviewQueuePaging.mergeItems(items, jumped.items)
        }
        ReviewQueuePageResult(window = snapshot())
    }

    /**
     * 加载上一页：`firstLoadedPage - 1` → 空页时用
     * `nearest(backward, index = 该页起点)` 直接跳回上一个可用页（§23）。
     */
    override suspend fun loadPrevPage(): ReviewQueuePageResult? = pagingMutex.withLock {
        if (sessionId.isBlank() || firstLoadedPage <= 1) return@withLock null
        val page = firstLoadedPage - 1
        val loaded = try {
            fetchPage(page)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return@withLock null
        }
        if (loaded.items.isNotEmpty()) {
            firstLoadedPage = page
            val before = items.size
            items = ReviewQueuePaging.mergeItems(loaded.items, items)
            return@withLock ReviewQueuePageResult(
                window = snapshot(),
                prependedCount = items.size - before,
            )
        }
        // 空页 → 直接跳回上一个可用页
        firstLoadedPage = page
        val jump = try {
            nearestAvailable(index = (page - 1) * pageSize, direction = NearestDirection.BACKWARD)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return@withLock null
        }
        if (jump == null) {
            // 前面没有任何可用媒体：边界推进到第 1 页（canLoadPrev 关闭，不再请求）
            firstLoadedPage = 1
            return@withLock ReviewQueuePageResult(window = snapshot())
        }
        val jumpPage = pageOf(jump).coerceIn(1, page - 1)
        val jumped = try {
            fetchPage(jumpPage)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return@withLock null
        }
        firstLoadedPage = jumpPage
        val before = items.size
        if (jumped.items.isNotEmpty()) {
            items = ReviewQueuePaging.mergeItems(jumped.items, items)
        }
        ReviewQueuePageResult(window = snapshot(), prependedCount = items.size - before)
    }

    /**
     * 拉取一页并映射：绝对索引原样保留服务端给出的 `index`（缺项不压缩索引），
     * `seen` 原样保留（恢复后知道哪些已经批阅过）。
     */
    private suspend fun fetchPage(page: Int): LoadedPage {
        val (dto, baseUrl) = call { api, base ->
            unwrap(
                api.reviewQueue(sessionId, page = page, pageSize = pageSize),
            ) to base
        }
        val summaries = dto.items.mapNotNull { it.media }.filter { it.media_id.isNotBlank() }
        resources.putAll(summaries.map { mapper.mapMedia(summary = it, baseUrl = baseUrl) })
        return LoadedPage(
            page = page,
            items = dto.items.mapNotNull { it.toUi() },
            totalCount = dto.total,
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
            seen = seen,
        )
    }

    // ---------- 状态变更 ----------

    override suspend fun markSeen(mediaId: String): ReviewSeenResult? {
        if (sessionId.isBlank() || mediaId.isBlank()) return null
        return try {
            val dto = call { api, _ ->
                unwrap(api.markSeen(sessionId, ReviewSeenRequest(media_id = mediaId)))
            }
            ReviewSeenResult(
                mediaId = dto.media_id.ifBlank { mediaId },
                seen = dto.seen,
                seenCount = dto.seen_count,
                totalCount = dto.total_count,
                unavailableCount = dto.unavailable_count,
                remainingCount = dto.remaining_count,
                completedCount = dto.completed_count,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // §26：失败不得假成功（UI 保持未批阅并可重试）
            null
        }
    }

    override suspend fun savePosition(absoluteIndex: Int) {
        val target = sessionId
        if (target.isBlank()) return
        pushPosition(target, absoluteIndex)
    }

    override suspend fun refreshProgress(): ReviewSessionInfo? {
        val target = sessionId
        if (target.isBlank()) return null
        return try {
            call { api, _ -> unwrap(api.reviewSession(target)) }.toInfo()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // §13/§14：无法确认 → null（调用方绝不据此完成会话）
            null
        }
    }

    /**
     * 位置上报：属于断点恢复闭环，失败不打断批阅；下一次 settled 会重新上报。
     *
     * [targetSession] 在调用侧固定：会话已切换时的迟到写入只会打到旧会话
     * （旧会话已完成/被替换，服务端会拒绝），绝不会把旧绝对索引写进新会话。
     */
    private suspend fun pushPosition(targetSession: String, absoluteIndex: Int) {
        try {
            call { api, _ ->
                unwrap(
                    api.setReviewPosition(
                        targetSession,
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
            // 服务端 fail-closed：remaining > 0 时也会走这里（UI 保持会话并提示）
            false
        }
    }

    /** Server 语义：重新批阅 = 新建会话（§39，不是逐项重置旧会话）。 */
    override suspend fun restart(): ReviewSessionOpen = enterSession(forceNew = true)

    override suspend fun restartAll(): ReviewSessionOpen = enterSession(forceNew = true)

    // ---------- 访问层 ----------

    private class LoadedPage(
        val page: Int,
        val items: List<ReviewQueueItemUi>,
        val totalCount: Int,
    )

    /** 绝对索引 → 页号（1-based）。 */
    private fun pageOf(absoluteIndex: Int): Int =
        ReviewQueuePaging.resumePlan(absoluteIndex, pageSize).page

    /**
     * 最近可用项（§16/§17）：`null` = **服务端明确**没有可用媒体；
     * 网络 / 合同失败向上抛（由调用方按 §34 区分处理）。
     */
    private suspend fun nearestAvailable(index: Int, direction: NearestDirection): Int? {
        val dto = call { api, _ ->
            unwrap(api.nearestReviewIndex(sessionId, index.coerceAtLeast(0), direction.wire))
        }
        return dto.index
    }

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

    /** 窗口是否已到队列末尾（按页边界，见 [ReviewQueuePaging.isAtEnd]）。 */
    private fun atEnd(): Boolean = ReviewQueuePaging.isAtEnd(lastLoadedPage, pageSize, totalCount)

    private fun snapshot(): ReviewQueueWindow = ReviewQueueWindow(
        items = items,
        firstLoadedPage = firstLoadedPage,
        lastLoadedPage = lastLoadedPage,
        totalCount = totalCount,
        pageSize = pageSize,
    )

    private fun ReviewSessionDto.toInfo(): ReviewSessionInfo = ReviewSessionInfo(
        sessionId = session_id,
        totalCount = total_count,
        currentIndex = current_index,
        seenCount = seen_count,
        unavailableCount = unavailable_count,
        remainingCount = remaining_count,
        completedCount = completed_count,
    )

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}