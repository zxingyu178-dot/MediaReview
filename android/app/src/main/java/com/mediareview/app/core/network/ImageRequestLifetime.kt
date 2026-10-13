package com.mediareview.app.core.network

import okhttp3.Call

/** 当前图片页的传输所有权；页切换必须中止已收到headers、仍在读body的连接。 */
internal class ImageRequestLifetime {
    private val lock = Any()
    private val calls = mutableSetOf<Call>()
    private var cancelled = false

    fun register(call: Call) {
        val cancelNow = synchronized(lock) {
            if (cancelled) true else { calls.add(call); false }
        }
        if (cancelNow) call.cancel()
    }

    fun cancel() {
        val owned = synchronized(lock) {
            cancelled = true
            calls.toList().also { calls.clear() }
        }
        owned.forEach(Call::cancel)
    }
}
