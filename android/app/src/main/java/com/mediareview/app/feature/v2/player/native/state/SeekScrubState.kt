package com.mediareview.app.feature.v2.player.native.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 进度条拖动（Scrub）状态（MediaReview UI 逻辑）。
 *
 * 单实例语义：
 * - 首次回调 begin(positionMs)：记录锚点并进入拖动态；
 * - 拖动过程 update(valueMs)：只更新本地预览位置，**不调用内核 seekTo**；
 * - 松手 commit()：位置发生变化才返回目标位置（由 UI 层执行一次 seekTo），否则返回 null；
 * - cancel()：放弃本次拖动（例如被手势层拦截 / 播放器释放）。
 *
 * 手感优化（Stage6）：[isScrubbing] 与 [previewMs] 使用 Compose 可观察状态，
 * 拖动过程中左侧时间文本、进度条 thumb、中央播放态都能在每一帧实时跟随，
 * 不再出现"thumb 跟手但时间数字不动 / 松手才刷新"的割裂感。
 * 暂停 / 恢复播放由 UI 层（GsyNativePlayerScreen）负责，本类只维护位置状态。
 */
class SeekScrubState {

    /** 本次拖动起始位置，用于判断松手时是否真的发生了位移。 */
    private var anchorMs: Long = 0L

    var isScrubbing: Boolean by mutableStateOf(false)
        private set

    /** 拖动中希望展示的位置（毫秒），仅用于本地 UI 预览，未提交给内核。 */
    var previewMs: Long by mutableStateOf(0L)
        private set

    /** 首次进度回调：记录当前播放位置作为锚点。 */
    fun begin(positionMs: Long) {
        if (!isScrubbing) {
            isScrubbing = true
            anchorMs = positionMs
            previewMs = positionMs
        }
    }

    /** 拖动过程：仅更新本地预览位置，不触发内核 seek。 */
    fun update(valueMs: Long) {
        if (isScrubbing) {
            previewMs = valueMs
        }
    }

    /** 松手提交：仅当位置相对锚点发生变化时返回目标位置，否则返回 null。 */
    fun commit(): Long? {
        if (!isScrubbing) return null
        val target = previewMs
        isScrubbing = false
        return if (target != anchorMs) target else null
    }

    /** 取消本次拖动，丢弃预览位置。 */
    fun cancel() {
        isScrubbing = false
        previewMs = 0L
    }
}
