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
 * GET  /api/v1/review/sessions/{id}/queue    队列分页（page / page_size ≤ 200，返回绝对 index + seen）
 * POST /api/v1/review/sessions/{id}/seen     标记已看（返回服务端权威 seen_count / total_count）
 * POST /api/v1/review/sessions/{id}/position 更新 current_index（断点恢复依据）
 * POST /api/v1/review/sessions/{id}/complete 手动完成（服务端 fail-closed：有未批阅则拒绝）
 * ```
 *
 * Stage 8B.1 正确性收口（评审 §3~§10 / §14）：
 * - **双向分页窗口**：显式记录 `firstLoadedPage` / `lastLoadedPage`，
 *   next = `lastLoadedPage + 1`、prev = `firstLoadedPage - 1`，先 next 再 prev 不会重复请求中间页；
 * - **合并去重**：所有分页合并按绝对索引去重 + 排序（绝不产生重复条目）；
 * - **空页推进**：整页媒体都失效时不 return null 死循环，而是推进页边界后继续找
 *   （`MAX_EMPTY_PAGE_SCAN` 限制单次调用扫描量，避免一次请求风暴）；
 * - **atEnd 按页边界**：`lastLoadedPage * pageSize >= totalCount`，队尾媒体失效也能结束；
 * - **失效 current_index 恢复**：优先"第一个 `>= current_index` 的可用项"，
 *   否则"`current_index` 之前最近的可用项"，并把恢复出的真实绝对索引**写回服务端**；
 * - **分页 single-flight**：Mutex 串行化 next / prev，绝不同时修改窗口；
 * - **seen 权威**：`markSeen` 直接返回服务端 `seen_count`（Android 不自行加一）。
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

    /**
     * 定位到 current_index 并加载它所在的那一页（§21：637/1000 → 直接取包含 637 的那一页）。
     *
     * §7 失效锚点恢复：
     * 1. 优先取**第一个 `absoluteIndex >= current_index` 的可用项**；
     * 2. 向后找不到时，取 `current_index` **之前最近的可用项**；
     * 3. 恢复出的真实绝对索引与 `current_index` 不同时**写回服务端 position**
     *    ——绝不能悄悄从本地 0 开始播放完全不同的视频。
     * 4. 整个队列都没有可用媒体时返回 null（上层进入空队列页）。
     */
    private suspend fun openWindow(info: ReviewSessionInfo): ReviewSessionOpen {
        sessionId = info.sessionId
        pageSize = ReviewQueuePaging.PAGE_SIZE
        totalCount = info.totalCount
        val anchor = info.currentIndex.coerceIn(0, (info.totalCount - 1).coerceAtLeast(0))
        val hit = locateAnchor(anchor) ?: return ReviewSessionOpen.Empty
        firstLoadedPage = hit.loaded.page
        lastLoadedPage = hit.loaded.page
        items = hit.loaded.items
        totalCount = hit.loaded.totalCount
        if (hit.absoluteIndex != info.currentIndex) {
            pushPosition(info.sessionId, hit.absoluteIndex)
        }
        return ReviewSessionOpen.Ready(
            session = info.copy(currentIndex = hit.absoluteIndex),
            window = snapshot(),
        )
    }

    /** 锚点恢复结果：命中页 + 恢复出的真实绝对索引。 */
    private class AnchorHit(val loaded: LoadedPage, val absoluteIndex: Int)

    private suspend fun locateAnchor(anchor: Int): AnchorHit? {
        val anchorPage = ReviewQueuePaging.resumePlan(anchor, pageSize).page
        val lastPage = ReviewQueuePaging.totalPages(totalCount, pageSize)
        var anchorPageLoaded: LoadedPage? = null
        // 1) 向后扫描（>= anchor）：第一个可用项即恢复目标
        for (page in anchorPage..lastPage) {
            val loaded = fetchPage(page)
            if (page == anchorPage) anchorPageLoaded = loaded
            ReviewQueuePaging.firstAtOrAfter(loaded.items, anchor)?.let { found ->
                return AnchorHit(loaded, found.absoluteIndex)
            }
        }
        // 2) 向前回退（< anchor）：最近的可用项（anchorPage 已拉取过，不重复请求）
        for (page in anchorPage downTo 1) {
            val loaded = if (page == anchorPage) anchorPageLoaded ?: fetchPage(page) else fetchPage(page)
            ReviewQueuePaging.nearestBefore(loaded.items, anchor)?.let { found ->
                return AnchorHit(loaded, found.absoluteIndex)
            }
        }
        return null
    }

    // ---------- 分页 ----------

    /**
     * 加载下一页：`lastLoadedPage + 1` 起向后扫描，直到找到非空页或到达队列末尾。
     *
     * 空页（整页媒体失效）**必须推进页边界**（§9），否则会永远重复请求同一页。
     */
    override suspend fun loadNextPage(): ReviewQueuePageResult? = pagingMutex.withLock {
        if (sessionId.isBlank() || atEnd()) return@withLock null
        val lastPage = ReviewQueuePaging.totalPages(totalCount, pageSize)
        var page = lastLoadedPage + 1
        var fetched = 0
        while (page <= lastPage && fetched < MAX_EMPTY_PAGE_SCAN) {
            val loaded = try {
                fetchPage(page)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return@withLock null
            }
            fetched += 1
            // §9：无论空页与否都记录"这一页已检查"
            lastLoadedPage = page
            if (loaded.items.isNotEmpty()) {
                items = ReviewQueuePaging.mergeItems(items, loaded.items)
                break
            }
            page += 1
        }
        if (fetched == 0) return@withLock null
        // 空页推进后也返回窗口快照：UI 才能知道 atEnd 已经成立（不再死循环请求）
        ReviewQueuePageResult(window = snapshot())
    }

    /**
     * 加载上一页：`firstLoadedPage - 1` 起向前扫描，直到找到非空页或到达第 1 页。
     * 空页同样必须推进（向前）页边界。
     */
    override suspend fun loadPrevPage(): ReviewQueuePageResult? = pagingMutex.withLock {
        if (sessionId.isBlank() || firstLoadedPage <= 1) return@withLock null
        var page = firstLoadedPage - 1
        var fetched = 0
        var prepended = 0
        while (page >= 1 && fetched < MAX_EMPTY_PAGE_SCAN) {
            val loaded = try {
                fetchPage(page)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return@withLock null
            }
            fetched += 1
            firstLoadedPage = page
            if (loaded.items.isNotEmpty()) {
                val before = items.size
                items = ReviewQueuePaging.mergeItems(loaded.items, items)
                prepended = items.size - before
                break
            }
            page -= 1
        }
        if (fetched == 0) return@withLock null
        ReviewQueuePageResult(window = snapshot(), prependedCount = prepended)
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
            // 服务端 fail-closed：还有未批阅时也会走这里（UI 保持会话并提示）
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
    )

    private companion object {
        const val HTTP_NOT_FOUND = 404

        /** 单次 next / prev 调用最多向后（前）扫描的空页数，避免一次请求风暴。 */
        const val MAX_EMPTY_PAGE_SCAN = 8
    }
}