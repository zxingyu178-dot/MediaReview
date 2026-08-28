package com.mediareview.app.core.ui

import java.util.concurrent.atomic.AtomicBoolean

/** 让保留在同一 ViewModelStore 的根页面只触发一次自动初始加载。 */
class InitialLoadGate {
    private val claimed = AtomicBoolean(false)

    fun claim(): Boolean = claimed.compareAndSet(false, true)
}
