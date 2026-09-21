package com.mediareview.app.feature.v2.player.native.state

import com.mediareview.app.feature.v2.player.gsy.GsyPlaybackRequest

/**
 * 播放上下文：为真实媒体浏览准备的上一条 / 下一条能力。
 *
 * @param mediaList 当前队列（按媒体顺序）
 * @param currentIndex 当前条目索引
 * @param source 数据来源（本阶段 "demo"；以后可为 "server" / "jellyfin"）
 */
data class PlaybackContext(
    val mediaList: List<GsyPlaybackRequest>,
    val currentIndex: Int = 0,
    val source: String = "demo",
) {
    init {
        require(currentIndex >= 0) { "currentIndex 不能为负" }
    }

    val current: GsyPlaybackRequest?
        get() = mediaList.getOrNull(currentIndex)

    val hasPrevious: Boolean
        get() = currentIndex > 0

    val hasNext: Boolean
        get() = currentIndex < mediaList.lastIndex

    /** 上一条：已在首位时保持不变（边界安全）。 */
    fun previous(): PlaybackContext =
        if (hasPrevious) copy(currentIndex = currentIndex - 1) else this

    /** 下一条：已在末位时保持不变（边界安全）。 */
    fun next(): PlaybackContext =
        if (hasNext) copy(currentIndex = currentIndex + 1) else this

    /** 切换到指定索引（越界时夹取）。 */
    fun moveTo(index: Int): PlaybackContext =
        copy(currentIndex = index.coerceIn(0, mediaList.lastIndex.coerceAtLeast(0)))
}
