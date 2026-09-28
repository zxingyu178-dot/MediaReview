package com.mediareview.app.feature.v2.review.data

/**
 * 批阅队列项（UI 唯一可见形态，Stage 8B §4）。
 *
 * **只保存媒体 Metadata**：绝不携带 playbackUrl / headers。
 * 播放地址只在真正需要播放时才解析（当前项 P0、下一条最多 P1）——
 * 因为播放端可能失效、token 会变化、服务器可能被切换（§30），
 * 队列持久化只允许保存 mediaId。
 */
data class ReviewQueueItemUi(
    /** 服务端队列里的**绝对索引**（§24：Server position 永远提交绝对位置）。 */
    val absoluteIndex: Int,
    val mediaId: String,
    val title: String,
    /** 编号；Server 无此概念时为空串（UI 对空值裁剪，绝不伪造编号）。 */
    val code: String,
    val folderName: String,
    val durationMs: Long,
    val naturalWidth: Int,
    val naturalHeight: Int,
    /** 封面绝对 URL（由数据层解析，UI 不感知来源）。 */
    val coverUrl: String,
    val favorite: Boolean,
    /** 服务端会话里这条是否已批阅（Stage 8B.1 §12：恢复后知道"以前是否看过"）。 */
    val seen: Boolean = false,
) {
    val durationSeconds: Int get() = (durationMs / 1000L).toInt()
}

/** 批阅会话摘要（Server=服务端 active session；Demo=本地未审队列快照）。 */
data class ReviewSessionInfo(
    val sessionId: String,
    val totalCount: Int,
    val currentIndex: Int,
    val seenCount: Int,
)

/**
 * 已看标记结果（Stage 8B.1 §14）：**服务端权威进度**。
 *
 * `seenCount` 直接来自服务端 `seen_count` —— Android 端绝不 `+1` 推算
 * （回看已经 seen 的媒体会重复计数，评审 §13）。
 */
data class ReviewSeenResult(
    val mediaId: String,
    val seen: Boolean,
    val seenCount: Int,
    val totalCount: Int,
)

/**
 * 已加载队列窗口（Stage 8B.1 §3：双向分页窗口）。
 *
 * 旧实现只有一个 `page` + `baseIndex`，无法表达"前后各加载过一页"的窗口
 * （13 → next 14 → prev 12 会误请求 13）；这里显式记录
 * [firstLoadedPage] / [lastLoadedPage]，页边界推进与 atEnd 判断都以它们为准。
 *
 * `items[i]` 的绝对索引**必须**以 [ReviewQueueItemUi.absoluteIndex] 为准：
 * 服务端会跳过不可用媒体（缺项不压缩绝对索引）。
 */
data class ReviewQueueWindow(
    val items: List<ReviewQueueItemUi>,
    val firstLoadedPage: Int,
    val lastLoadedPage: Int,
    val totalCount: Int,
    val pageSize: Int = ReviewQueuePaging.PAGE_SIZE,
) {
    /** 已加载窗口的第一项绝对索引（页边界换算，仅供诊断/日志）。 */
    val baseIndex: Int get() = (firstLoadedPage - 1) * pageSize

    /** 已加载窗口是否已到队列末尾（按**页边界**判断，见 [ReviewQueuePaging.isAtEnd]）。 */
    val atEnd: Boolean
        get() = ReviewQueuePaging.isAtEnd(lastLoadedPage, pageSize, totalCount)

    /** 是否还能向前（向上）加载：尚未到达第一页。 */
    val canLoadPrev: Boolean get() = firstLoadedPage > 1

    /**
     * 绝对索引 → 本地 pager 索引；不在已加载窗口内时返回 null。
     *
     * 必须用 `indexOfFirst` 搜索（评审 §6）：存在缺项时
     * `absoluteIndex - baseIndex` 会算错（600/602/603 缺 601 时 602 的本地索引是 1 而不是 2）。
     */
    fun localIndexOf(absoluteIndex: Int): Int? =
        items.indexOfFirst { it.absoluteIndex == absoluteIndex }.takeIf { it >= 0 }
}

/** 一页队列加载结果。 */
data class ReviewQueuePageResult(
    val window: ReviewQueueWindow,
    /** 前置分页新增条数（UI 需按此补偿 pager 偏移）；向后分页为 0。 */
    val prependedCount: Int = 0,
)

/** 进入批阅（恢复 / 新建）的结果。 */
sealed interface ReviewSessionOpen {
    /** 会话就绪，并已定位到 current_index 所在分页。 */
    data class Ready(
        val session: ReviewSessionInfo,
        val window: ReviewQueueWindow,
    ) : ReviewSessionOpen

    /** 没有任何可批阅的媒体（进入空队列完成页）。 */
    data object Empty : ReviewSessionOpen

    /**
     * 失败（网络 / 合同错误）。
     *
     * 阶段 8B §17：latest 请求失败**绝不能**被当成"没有 Session"而自动新建会话，
     * 必须由 UI 显示"无法恢复批阅 + 重新尝试"。
     */
    data class Failed(val message: String) : ReviewSessionOpen
}

/** 恢复定位结果（移植自旧版 `feature/review` 已验证的绝对索引映射）。 */
data class ReviewResumePlan(
    val page: Int,
    val baseIndex: Int,
    val localStart: Int,
)

/**
 * 批阅队列分页纯逻辑（可 JVM 单测）。
 *
 * Stage 8B.1 修正两处边界（评审 §6/§8/§9）：
 * - 结束判断以**已加载页边界**为准（`lastLoadedPage * pageSize >= totalCount`），
 *   而不是"最后一个可用媒体的绝对索引"——队尾媒体失效时后者永远不会认为到末尾；
 * - 窗口合并必须按绝对索引去重并排序——重复页请求绝不产生重复条目。
 */
object ReviewQueuePaging {

    /** 每页条数（§20：Android 不得一次取全部队列；服务端 page_size ≤ 200）。 */
    const val PAGE_SIZE = 50

    /** 包含 [absoluteIndex] 的分页计划。 */
    fun resumePlan(absoluteIndex: Int, pageSize: Int = PAGE_SIZE): ReviewResumePlan {
        val safeIndex = absoluteIndex.coerceAtLeast(0)
        val safeSize = pageSize.coerceAtLeast(1)
        val page = safeIndex / safeSize + 1
        val base = (page - 1) * safeSize
        return ReviewResumePlan(page = page, baseIndex = base, localStart = safeIndex - base)
    }

    /** 队列总页数（totalCount ≤ 0 时返回 1，避免 0 页窗口）。 */
    fun totalPages(totalCount: Int, pageSize: Int = PAGE_SIZE): Int =
        ((totalCount + pageSize - 1) / pageSize).coerceAtLeast(1)

    /**
     * 结束判断（**按页边界**）：已加载到的最后一页 * 每页条数 >= 总数。
     *
     * @param lastLoadedPage 已加载窗口的最后一页（≤ 0 = 还没有任何数据 → 视为结束）。
     */
    fun isAtEnd(lastLoadedPage: Int, pageSize: Int = PAGE_SIZE, totalCount: Int): Boolean {
        if (lastLoadedPage <= 0) return true
        return lastLoadedPage.toLong() * pageSize >= totalCount
    }

    /**
     * 合并分页结果（评审 §5）：按绝对索引去重 + 升序排序。
     *
     * 禁止出现 `48 49 50 51 50 51 52` 这种重复窗口。
     */
    fun mergeItems(
        existing: List<ReviewQueueItemUi>,
        incoming: List<ReviewQueueItemUi>,
    ): List<ReviewQueueItemUi> =
        (existing + incoming)
            .distinctBy { it.absoluteIndex }
            .sortedBy { it.absoluteIndex }

    /** 页内第一个 `absoluteIndex >= anchor` 的可用项（恢复定位：优先向后找）。 */
    fun firstAtOrAfter(items: List<ReviewQueueItemUi>, anchor: Int): ReviewQueueItemUi? =
        items.firstOrNull { it.absoluteIndex >= anchor }

    /** `absoluteIndex < anchor` 中最近的可用项（恢复定位：向后找不到时向前回退）。 */
    fun nearestBefore(items: List<ReviewQueueItemUi>, anchor: Int): ReviewQueueItemUi? =
        items.lastOrNull { it.absoluteIndex < anchor }

    /** 接近底部时是否需要加载下一页（纯函数，避免"到最后才 loading"）。 */
    fun shouldLoadNext(
        lastVisibleLocalIndex: Int,
        loadedCount: Int,
        atEnd: Boolean,
        loading: Boolean,
        prefetchDistance: Int = PREFETCH_DISTANCE,
    ): Boolean = !atEnd && !loading && loadedCount > 0 &&
        lastVisibleLocalIndex >= loadedCount - prefetchDistance

    /** 接近顶部时是否需要加载上一页。 */
    fun shouldLoadPrev(firstVisibleLocalIndex: Int, canLoadPrev: Boolean, loading: Boolean): Boolean =
        canLoadPrev && !loading && firstVisibleLocalIndex <= PREV_PREFETCH_DISTANCE

    /** 向后预取距离（条）。 */
    const val PREFETCH_DISTANCE = 5

    /** 向前预取距离（条）。 */
    const val PREV_PREFETCH_DISTANCE = 2
}