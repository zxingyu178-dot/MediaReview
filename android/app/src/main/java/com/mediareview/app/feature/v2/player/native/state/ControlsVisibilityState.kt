package com.mediareview.app.feature.v2.player.native.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 控制层显隐状态（MediaReview 播放器 UI 逻辑，非播放内核）。
 *
 * Stage 2.2.1 状态收敛：锁定状态的唯一真实来源是 GSY controller.snapshot.isLocked，
 * 本类不再持有 locked，也不提供 setLocked；仅通过 [onLockChanged] 接收外部
 * （controller 快照）同步过来的锁定态，以驱动显隐：
 *
 * - 播放中约 3 秒无操作自动隐藏；
 * - 暂停时保持显示；
 * - Seek / 倍速 / 按钮 / 切比例等交互重新计时；
 * - 锁定（来自 controller）后立即隐藏普通控制；解锁后重新显示并计时。
 */
class ControlsVisibilityState(
    private val scope: CoroutineScope,
    private val onVisibilityChange: ((Boolean) -> Unit)? = null,
) {

    companion object {
        const val AUTO_HIDE_MS = 3_000L
    }

    var visible = true
        private set

    private var hideJob: Job? = null
    private var playing = false

    fun updatePlayback(playing: Boolean) {
        this.playing = playing
        if (playing) {
            scheduleHide()
        } else {
            hideJob?.cancel()
            show()
        }
    }

    /** 手势 / 按钮操作：显示并重置自动隐藏计时。 */
    fun onUserInteraction() {
        show()
        scheduleHide()
    }

    fun toggleVisibility() {
        visible = !visible
        if (visible) scheduleHide() else hideJob?.cancel()
        onVisibilityChange?.invoke(visible)
    }

    /**
     * 锁定态由 controller.snapshot.isLocked 同步驱动（本类不持有锁定真值）。
     * 锁定：立即隐藏并停止计时；解锁：重新显示并按播放状态计时。
     */
    fun onLockChanged(locked: Boolean) {
        if (locked) {
            hideJob?.cancel()
            if (visible) {
                visible = false
                onVisibilityChange?.invoke(false)
            }
        } else {
            visible = true
            onVisibilityChange?.invoke(true)
            scheduleHide()
        }
    }

    private fun scheduleHide() {
        if (!playing) return
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(AUTO_HIDE_MS)
            if (playing && visible) {
                visible = false
                onVisibilityChange?.invoke(false)
            }
        }
    }

    private fun show() {
        if (!visible) {
            visible = true
            onVisibilityChange?.invoke(true)
        }
    }
}
