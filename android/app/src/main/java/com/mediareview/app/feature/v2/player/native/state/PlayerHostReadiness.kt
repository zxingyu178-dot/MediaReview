package com.mediareview.app.feature.v2.player.native.state

import com.shuyu.gsyvideoplayer.compose.native_.GSYPlayerController
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 等待 GSY host（GSYPlayerSurface 的 AndroidView）attach 且完成首次布局。
 *
 * 可取消（所在协程取消即立即停止轮询）、带明确超时，超时返回 false。
 * Stage 7.1 的 `host.postDelayed { ... }` 写法不可取消，协程取消后仍可能执行（stale play），
 * 一律改用本挂起函数。
 */
suspend fun awaitPlayerHostReady(
    controller: GSYPlayerController,
    timeoutMs: Long = 2_000L,
): Boolean = withTimeoutOrNull(timeoutMs) {
    var ready = false
    while (!ready) {
        ready = controller.withHost { host ->
            host.isAttachedToWindow && host.width > 0 && host.height > 0
        } ?: false
        if (!ready) delay(40L)
    }
    true
} ?: false