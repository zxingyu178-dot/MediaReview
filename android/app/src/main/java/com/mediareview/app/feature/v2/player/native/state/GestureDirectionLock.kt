package com.mediareview.app.feature.v2.player.native.state

import kotlin.math.abs
import kotlin.math.hypot

enum class GestureDirection { Undecided, Horizontal, Vertical, Speed }

/** 每次 pointer down 创建/重置；传入从 down 起的累计位移，方向锁定后不可变化。 */
class GestureDirectionLock(private val touchSlop: Float) {
    var direction = GestureDirection.Undecided
        private set

    fun update(dx: Float, dy: Float): GestureDirection {
        if (direction != GestureDirection.Undecided) return direction
        if (hypot(dx, dy) <= touchSlop) return direction
        direction = when {
            abs(dx) > abs(dy) * 1.2f -> GestureDirection.Horizontal
            abs(dy) > abs(dx) * 1.2f -> GestureDirection.Vertical
            else -> GestureDirection.Undecided
        }
        return direction
    }

    fun claimSpeed(): Boolean {
        if (direction != GestureDirection.Undecided) return false
        direction = GestureDirection.Speed
        return true
    }

    fun reset() { direction = GestureDirection.Undecided }
}
