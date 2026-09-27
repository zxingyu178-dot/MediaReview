package com.mediareview.app.feature.v2.review.data

/**
 * 批阅会话数据层（Stage 8B §14：Review 数据层独立于 [com.mediareview.app.feature.v2.data.MediaRepository]）。
 *
 * 职责划分：
 * - `MediaRepository`（媒体 / 收藏 / 待删除 / 播放）保持原样；
 * - `V2ReviewSessionRepository` 专门负责 session / queue / seen / position / complete。
 *
 * "重新批阅"与队列分页的语义由实现决定：
 * - Server：全部走服务端 Review Session（新建会话时服务端会先完成旧 active 会话，§39）；
 * - Demo：本地未审队列，无分页（单页即全量），行为与 Stage 7.1 一致。
 */
interface V2ReviewSessionRepository {

    /**
     * 进入批阅：恢复最新 active 会话，并定位到其 `current_index` 所在分页（§21 深位置恢复：
     * 只拉包含该索引的一页，绝不加载 1..N）。
     *
     * §17 硬约束：
     * - **只有数据源明确返回"没有可恢复会话（NOT_FOUND）"才允许新建会话**；
     * - `latest` 网络失败 / 超时 / 其他错误一律返回 [ReviewSessionOpen.Failed]，
     *   绝不误建新会话（会把用户的批阅进度重置掉）。
     */
    suspend fun enterSession(forceNew: Boolean = false): ReviewSessionOpen

    /**
     * 接近底部：加载下一页并追加到窗口。
     *
     * @return null 表示已到末尾 / 当前不可加载 / 加载失败（调用方可重试，UI 不假成功）。
     */
    suspend fun loadNextPage(): ReviewQueuePageResult?

    /**
     * 接近顶部：加载上一页并前置到窗口。
     *
     * @return null 表示已在第一页 / 当前不可加载 / 加载失败；
     * 成功时 [ReviewQueuePageResult.prependedCount] > 0，UI 需按此补偿 pager 偏移。
     */
    suspend fun loadPrevPage(): ReviewQueuePageResult?

    /**
     * 标记已看：**只有数据源确认成功才返回 true**（§26：失败不得假成功，可重试）。
     */
    suspend fun markSeen(mediaId: String): Boolean

    /**
     * 上报绝对位置（§27 / §28）：只在 settled page 变化时调用；
     * latest-wins（取消旧请求 + 过期响应丢弃）由调用方保证。
     *
     * 失败不得抛出（位置上报属于后台闭环），但调用方也不得据此认为"已保存"。
     */
    suspend fun savePosition(absoluteIndex: Int)

    /** 会话完成（§40）：队列全部稳定批阅后调用；返回是否已确认。 */
    suspend fun completeSession(): Boolean

    /**
     * "重新批阅"（本次队列）：Server 语义 = 新建会话（§39），Demo = 本队列重审。
     */
    suspend fun restart(): ReviewSessionOpen

    /** 空队列完成页的"重新批阅"：Server = 新建会话，Demo = 全量视频重审。 */
    suspend fun restartAll(): ReviewSessionOpen
}