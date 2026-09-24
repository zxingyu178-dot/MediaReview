package com.mediareview.app.feature.v2.player.native.state

/**
 * 播放队列项（Stage 8A）：**只保存标识与标题**。
 *
 * 真实 Server 下 URL / headers 必须按需异步解析（`GET /media/{id}/playback`），
 * 一次为整个队列解析 PlaybackInfo 是被明确禁止的（打开播放器就会给几百个视频发请求）。
 */
data class PlaybackQueueItem(
    val mediaId: String,
    val title: String,
)

/**
 * 播放上下文：为真实媒体浏览准备的上一条 / 下一条能力。
 *
 * @param queue 当前队列（按媒体顺序，只含 id 与标题）
 * @param currentIndex 当前条目索引
 * @param source 数据来源（"demo" / "server"）
 */
data class PlaybackContext(
    val queue: List<PlaybackQueueItem>,
    val currentIndex: Int = 0,
    val source: String = "demo",
) {
    init {
        require(currentIndex >= 0) { "currentIndex 不能为负" }
    }

    val current: PlaybackQueueItem?
        get() = queue.getOrNull(currentIndex)

    val hasPrevious: Boolean
        get() = currentIndex > 0

    val hasNext: Boolean
        get() = currentIndex < queue.lastIndex

    /** 上一条：已在首位时保持不变（边界安全）。 */
    fun previous(): PlaybackContext =
        if (hasPrevious) copy(currentIndex = currentIndex - 1) else this

    /** 下一条：已在末位时保持不变（边界安全）。 */
    fun next(): PlaybackContext =
        if (hasNext) copy(currentIndex = currentIndex + 1) else this

    /** 切换到指定索引（越界时夹取）。 */
    fun moveTo(index: Int): PlaybackContext =
        copy(currentIndex = index.coerceIn(0, queue.lastIndex.coerceAtLeast(0)))
}