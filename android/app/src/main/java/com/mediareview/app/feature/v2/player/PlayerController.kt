package com.mediareview.app.feature.v2.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * 播放器引擎（Media3 ExoPlayer 封装）。
 *
 * 职责：
 * - 媒体装载 / 播放 / 暂停 / Seek / 倍速 / 重试 / 重播；
 * - AudioAttributes + AudioFocus（Media3 setAudioAttributes(attrs, handleAudioFocus=true)）；
 * - 拔耳机 / 音频路由变化自动暂停（setHandleAudioBecomingNoisy(true)）。
 *
 * 引擎状态通过 [Listener.onStateChanged] 上报，不持有 UI；
 * 批阅模式后续可直接复用本 Controller。
 */
@OptIn(UnstableApi::class)
class PlayerController(
    private val appContext: Context,
) {

    /** 引擎状态变化回调（阶段 / 播放状态 / 时长变化时触发）。 */
    var listener: Listener? = null

    private var exoPlayer: ExoPlayer? = null

    /** 是否已进入过 READY（区分 PREPARING 与 BUFFERING）。 */
    var everReady = false
        private set

    /** 是否发生播放错误。 */
    var hasError = false
        private set

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) everReady = true
            listener?.onStateChanged()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            listener?.onStateChanged()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            hasError = true
            listener?.onStateChanged()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            listener?.onStateChanged()
        }
    }

    init {
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        exoPlayer = ExoPlayer.Builder(appContext)
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        exoPlayer?.addListener(playerListener)
    }

    /** 装载并自动开始播放。 */
    fun prepare(uri: String) {
        val player = exoPlayer ?: return
        everReady = false
        hasError = false
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
        listener?.onStateChanged()
    }

    fun play() {
        val player = exoPlayer ?: return
        if (!player.playWhenReady) {
            player.playWhenReady = true
            listener?.onStateChanged()
        }
    }

    fun pause() {
        val player = exoPlayer ?: return
        if (player.playWhenReady) {
            player.playWhenReady = false
            listener?.onStateChanged()
        }
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        player.playWhenReady = !player.playWhenReady
        listener?.onStateChanged()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs.coerceAtLeast(0L))
    }

    /** 相对当前位置跳转（clamp 到 0..duration）。 */
    fun seekBy(deltaMs: Long) {
        val player = exoPlayer ?: return
        val duration = player.duration.coerceAtLeast(0L)
        val target = (player.currentPosition + deltaMs).coerceIn(0L, duration)
        player.seekTo(target)
    }

    fun setSpeed(speed: Float) {
        exoPlayer?.setPlaybackSpeed(speed)
    }

    /** 错误后重试。 */
    fun retry() {
        val player = exoPlayer ?: return
        hasError = false
        player.prepare()
        player.playWhenReady = true
        listener?.onStateChanged()
    }

    /** Ended 后重播。 */
    fun restart() {
        val player = exoPlayer ?: return
        player.seekTo(0L)
        player.playWhenReady = true
        listener?.onStateChanged()
    }

    fun bindView(view: PlayerView) {
        view.useController = false
        view.player = exoPlayer
    }

    val isPlaying: Boolean get() = exoPlayer?.isPlaying ?: false
    val currentPosition: Long get() = exoPlayer?.currentPosition ?: 0L
    val duration: Long get() = exoPlayer?.duration ?: C.TIME_UNSET
    val bufferedPosition: Long get() = exoPlayer?.bufferedPosition ?: 0L
    val playbackState: Int get() = exoPlayer?.playbackState ?: Player.STATE_IDLE

    fun release() {
        exoPlayer?.removeListener(playerListener)
        exoPlayer?.release()
        exoPlayer = null
    }

    interface Listener {
        fun onStateChanged()
    }
}
