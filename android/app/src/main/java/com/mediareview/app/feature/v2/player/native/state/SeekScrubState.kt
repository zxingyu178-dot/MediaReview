package com.mediareview.app.feature.v2.player.native.state

/**
 * 底部进度条 Scrubbing 状态：拖动只本地预览，松手才提交真实 Seek。
 */
class SeekScrubState {

    var isScrubbing = false
        private set

    var previewMs = 0L
        private set

    /** 开始拖动：快照当前播放位置。 */
    fun begin(positionMs: Long) {
        isScrubbing = true
        previewMs = positionMs
    }

    fun update(valueMs: Long) {
        previewMs = valueMs
    }

    /** 松手：返回要提交的位置；非拖动中返回 null。 */
    fun commit(): Long? {
        if (!isScrubbing) return null
        isScrubbing = false
        return previewMs
    }

    /** 取消：放弃本次拖动。 */
    fun cancel() {
        isScrubbing = false
    }
}
