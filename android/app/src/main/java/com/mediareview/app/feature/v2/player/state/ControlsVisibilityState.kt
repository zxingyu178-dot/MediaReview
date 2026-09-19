package com.mediareview.app.feature.v2.player.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 控制层显隐状态。
 *
 * - 播放中约 3 秒无操作自动隐藏；
 * - 暂停时控制层保持显示；
 * - 任何手势 / 按钮操作重置自动隐藏计时；
 * - 锁定后隐藏全部普通控制。
 *
 * 纯逻辑 + 协程延迟，可用虚拟时间单测（StandardTestDispatcher / advanceTimeBy）。
 */
class ControlsVisibilityState(
    private val scope: CoroutineScope,
    private val onVisibilityChange: (() -> Unit)? = null,
    private val onLockChange: (() -> Unit)? = null,
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

    /** 播放 / 暂停状态更新：播放时安排自动隐藏，暂停时保持显示。 */
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
        onVisibilityChange?.invoke()
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
        onVisibilityChange?.invoke()
        onLockChange?.invoke()
    }

    private fun scheduleHide() {
        if (!playing) return
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(AUTO_HIDE_MS)
            if (!locked && playing && visible) {
                visible = false
                onVisibilityChange?.invoke()
            }
        }
    }

    private fun show() {
        if (!visible) {
            visible = true
            onVisibilityChange?.invoke()
        }
    }
}
