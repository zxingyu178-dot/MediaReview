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
 * 已加载队列窗口。
 *
 * `items[i]` 的绝对索引 = [baseIndex] + i —— 但**必须以 [ReviewQueueItemUi.absoluteIndex]
 * 为准**：服务端可能因为媒体不可用而跳过队列项（缺项不压缩绝对索引）。
 */
data class ReviewQueueWindow(
    val items: List<ReviewQueueItemUi>,
    val baseIndex: Int,
    val totalCount: Int,
    val page: Int,
) {
    /** 已加载窗口是否已到队列末尾（以真实绝对索引为准，见 [ReviewQueuePaging.isAtEnd]）。 */
    val atEnd: Boolean
        get() = ReviewQueuePaging.isAtEnd(items.lastOrNull()?.absoluteIndex, totalCount)

    /** 是否还能向前（向上）加载：尚未到达第一页。 */
    val canLoadPrev: Boolean get() = baseIndex > 0

    /** 绝对索引 → 本地 pager 索引；不在已加载窗口内时返回 null。 */
    fun localIndexOf(absoluteIndex: Int): Int? =
        (absoluteIndex - baseIndex).takeIf { it in items.indices }
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
 * 移植自旧版 Review 已验证实现（§22），并修正一处边界：
 * 结束判断以**真实绝对索引**为准（缺项不压缩索引），而不是 `baseIndex + items.size`。
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

    /**
     * 结束判断：[lastLoadedAbsoluteIndex] 是已加载窗口最后一项的绝对索引
     * （null = 还没有任何数据 → 视为结束，不再请求）。
     */
    fun isAtEnd(lastLoadedAbsoluteIndex: Int?, totalCount: Int): Boolean {
        if (lastLoadedAbsoluteIndex == null) return true
        return lastLoadedAbsoluteIndex + 1 >= totalCount
    }

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