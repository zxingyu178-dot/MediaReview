package com.mediareview.app.feature.v2.player

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer

/** 仅测试读取真实 Exo 内核 seek discontinuity，不给生产代码加计数器。UI 线程装卸。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class KernelSeekRecorder(kernel: IjkExo2MediaPlayer) : AutoCloseable {
    private val exo = IjkExo2MediaPlayer::class.java.getDeclaredField("mInternalPlayer")
        .apply { isAccessible = true }.get(kernel) as ExoPlayer
    @Volatile var count = 0
        private set
    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) count++
        }
    }
    init { exo.addListener(listener) }
    override fun close() { exo.removeListener(listener) }
}
