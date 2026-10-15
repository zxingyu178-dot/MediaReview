package com.mediareview.app.feature.v2.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer

/** Test-only actual Exo observation/fault injection, like KernelSeekRecorder; UI thread only. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class KernelPlaybackProbe(kernel: IjkExo2MediaPlayer) {
    private val exo = IjkExo2MediaPlayer::class.java.getDeclaredField("mInternalPlayer")
        .apply { isAccessible = true }.get(kernel) as ExoPlayer
    var repeatMode: Int
        get() = exo.repeatMode
        set(value) { exo.repeatMode = value }
    val isPlaying get() = exo.isPlaying
    val state get() = exo.playbackState
    val playWhenReady get() = exo.playWhenReady
    val positionMs get() = exo.currentPosition
    val durationMs get() = exo.duration
}
