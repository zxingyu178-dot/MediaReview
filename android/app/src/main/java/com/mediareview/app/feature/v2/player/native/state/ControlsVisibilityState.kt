package com.mediareview.app.feature.v2.player.native.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 控制层显隐状态（MediaReview 播放器 UI 逻辑，非播放内核）。
 *
 * - 播放中约 3 秒无操作自动隐藏；
 * - 暂停时保持显示；
 * - Seek / 倍速 / 按钮 / 切比例等交互重新计时；
 * - 锁定后隐藏全部普通控制，仅保留锁图标。
 */
class ControlsVisibilityState(
    private val scope: CoroutineScope,
    private val onVisibilityChange: ((Boolean) -> Unit)? = null,
    private val onLockChange: ((Boolean) -> Unit)? = null,
) {

    companion object {
        const val AUTO_HIDE_MS = 3_000L
    }

    var visible = true
        private set

    var locked = false
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

    /** 手势 / 按钮操作：显示并重置自动隐藏计时（锁定态忽略）。 */
    fun onUserInteraction() {
        if (locked) return
        show()
        scheduleHide()
    }

    fun toggleVisibility() {
        if (locked) return
        visible = !visible
        if (visible) scheduleHide() else hideJob?.cancel()
        onVisibilityChange?.invoke(visible)
    }

    fun setLocked(locked: Boolean) {
        if (this.locked == locked) return
        this.locked = locked
        if (locked) {
            hideJob?.cancel()
            visible = false
        } else {
            visible = true
            scheduleHide()
        }
        onVisibilityChange?.invoke(visible)
        onLockChange?.invoke(this.locked)
    }

    private fun scheduleHide() {
        if (!playing) return
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(AUTO_HIDE_MS)
            if (!locked && playing && visible) {
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
