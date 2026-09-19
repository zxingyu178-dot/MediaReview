package com.mediareview.app.feature.v2.player

import com.mediareview.app.feature.v2.player.state.VideoScaleState

/**
 * 播放器对外 UI 状态（引擎 / 状态汇总，供 UI 层只读订阅）。
 */
enum class PlaybackPhase {
    IDLE, PREPARING, BUFFERING, READY, PLAYING, PAUSED, ENDED, ERROR,
}

/**
 * ExoPlayer 状态机 → 正式播放阶段（纯函数，可单测）。
 *
 * @param playbackState ExoPlayer playbackState（STATE_IDLE / STATE_BUFFERING / STATE_READY / STATE_ENDED）
 * @param isPlaying     player.isPlaying
 * @param everReady     是否已进入过 READY（区分首次缓冲 PREPARING 与回退缓冲 BUFFERING）
 * @param hasError      是否发生过播放错误
 */
fun mapPlaybackPhase(
    playbackState: Int,
    isPlaying: Boolean,
    everReady: Boolean,
    hasError: Boolean,
): PlaybackPhase {
    if (hasError) return PlaybackPhase.ERROR
    return when (playbackState) {
        PlayerCompat.STATE_IDLE -> PlaybackPhase.IDLE
        PlayerCompat.STATE_BUFFERING -> if (everReady) PlaybackPhase.BUFFERING else PlaybackPhase.PREPARING
        PlayerCompat.STATE_READY -> if (isPlaying) PlaybackPhase.PLAYING else PlaybackPhase.PAUSED
        PlayerCompat.STATE_ENDED -> PlaybackPhase.ENDED
        else -> PlaybackPhase.IDLE
    }
}

/** ExoPlayer 状态常量（与 androidx.media3.common.Player 一致，避免纯逻辑依赖 Media3 类型）。 */
object PlayerCompat {
    const val STATE_IDLE = 1
    const val STATE_BUFFERING = 2
    const val STATE_READY = 3
    const val STATE_ENDED = 4
}

/** 播放器 UI 状态（一次快照，全部字段 UI 只读）。 */
data class PlayerUiState(
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val speed: Float = 1f,
    val isTempSpeedActive: Boolean = false,
    val isLocked: Boolean = false,
    val controlsVisible: Boolean = true,
    val scaleMode: VideoScaleState.ScaleMode = VideoScaleState.ScaleMode.FIT,
    val isLandscape: Boolean = false,
    /** Seek 提示：非空显示；正=前进，负=后退。 */
    val seekHintDeltaMs: Long? = null,
    val seekHintPositionMs: Long = 0L,
    val volumeHintPct: Int? = null,
    val brightnessHintPct: Int? = null,
    val errorMessage: String? = null,
)
