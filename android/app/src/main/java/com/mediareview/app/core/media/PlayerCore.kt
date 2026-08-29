package com.mediareview.app.core.media

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.PlaybackParameters
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 播放阶段(供 UI 显示缓冲/加载等状态)。 */
enum class PlaybackPhase { Idle, Loading, Ready, Playing, Buffering, Paused, Ended, Error }

/** 当前播放状态快照。 */
data class PlaybackStatus(
    val phase: PlaybackPhase = PlaybackPhase.Idle,
    val error: String? = null,
)

/** 某一媒体在对应播放器上的实时进度快照(上报前锁定,防止切换后串片)。 */
data class ProgressSnapshot(
    val positionMs: Long,
    val isPlaying: Boolean,
    val playWhenReady: Boolean,
)

/** 批阅队列中一个可播放项。 */
data class ReviewPlayable(
    val index: Int,
    val mediaId: String,
    val streamUrl: String? = null,
) {
    val isVideo: Boolean get() = !streamUrl.isNullOrBlank()
}

/**
 * 可复用 Player Core:普通播放器与批阅播放器共享同一套 Media3 播放层,不允许两套独立实现。
 *
 * 内部持有两个 ExoPlayer:
 * - [player]  当前(P0)播放
 * - [preload] 下一条(P1)预加载(只 prepare 不发声)
 *
 * 批阅模式调用方按此约定驱动:
 * - [settle] 页面停稳后**立即**切到当前项(只接受已解析 URL 的当前项,不阻塞等待下一条);
 * - [prepareNext] 由调用方在后台协程获取下一条 URL 后调用,预加载 P1 槽;
 * - 任何 stop 都会同步清空对应 ready,杜绝 P1 stale-ready;图片页绝不 prepare 空 URL。
 *
 * 进度上报防串片:每个槽位记录当前媒体 id,[snapshotFor] 只返回对应播放器的实时快照,
 * 媒体已切换后对旧 mediaId 返回 null,禁止拿新的 activePlayer 给旧 mediaId 上报。
 */
@Singleton
class PlayerCore @Inject constructor(
    @ApplicationContext context: Context,
) {
    val player: ExoPlayer by lazy { ExoPlayer.Builder(context).build() }
    val preload: ExoPlayer by lazy { ExoPlayer.Builder(context).build() }

    private val _status = MutableStateFlow(PlaybackStatus())
    val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    /** 当前真正发声/显示的是否为预加载那台(供 UI 绑定 PlayerView)。 */
    private val _activeIsPreload = MutableStateFlow(false)
    val activeIsPreload: StateFlow<Boolean> = _activeIsPreload.asStateFlow()

    private val slots = ReviewPlayerSlots()

    // 批阅状态
    private var currentIndex: Int = -1
    private var active: ExoPlayer = player
    private var throttled: Boolean = false
    private var listenersAttached: Boolean = false

    /** 槽位当前媒体 id:[0]=player(A), [1]=preload(B)。进度上报按 mediaId 定位播放器。 */
    private val slotMediaId = arrayOfNulls<String>(2)

    // 最近一次 prepareNext 的项(当前视频缓冲恢复后重新预加载 P1)
    private var nextItem: ReviewPlayable? = null

    /** 播放当前队列(普通播放器入口)。mediaId 记录到槽位,供进度上报快照定位。 */
    fun playStream(mediaId: String?, streamUrl: String) {
        ensureListeners()
        active = player
        _activeIsPreload.value = false
        slotMediaId[0] = mediaId
        _status.value = PlaybackStatus(phase = PlaybackPhase.Loading)
        player.stop()
        player.setMediaItem(MediaItem.fromUri(streamUrl))
        player.prepare()
        player.play()
    }

    fun pause() = player.pause()
    fun resume() = player.play()
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    /** 批阅根失活时暂停两个槽，防止当前槽恰为 preload 时继续在后台播放。 */
    fun deactivateReview() {
        player.pause()
        preload.pause()
        refreshStatus()
    }

    /** 倍速播放。 */
    fun setPlaybackSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 3f))
    }

    /** 音量(0~1)。 */
    fun setVolume(volume: Float) {
        player.volume = volume.coerceIn(0f, 1f)
    }

    /** 选择音轨/字幕轨(按 currentTracks 的组索引 + 轨索引)。 */
    fun selectTrack(groupIndex: Int, trackIndex: Int) {
        val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return
        val params = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
        player.trackSelectionParameters = params
    }

    /** 清除某类型(字幕 C.TRACK_TYPE_TEXT 等)的全部覆盖,恢复默认。 */
    fun clearTracksOfType(type: Int) {
        val params = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type).build()
        player.trackSelectionParameters = params
    }

    /**
     * 页面停稳后切到 [current](P0)。调用方必须先解析当前项 URL(它就是要播的视频),
     * 本方法不等待"下一条的下一条"——P1 由 [prepareNext] 在后台完成。
     * 优先复用已就绪的 P1 槽位,已就绪时立即出声。
     */
    fun settle(index: Int, current: ReviewPlayable) {
        currentIndex = index
        throttled = false
        ensureListeners()

        // 图片页:不向 ExoPlayer prepare 空 URL,停止当前视频(或保持静默)
        if (!current.isVideo) {
            active = player
            _activeIsPreload.value = false
            slots.markActive(slotB = false)
            slotMediaId[0] = null
            player.stop()
            slots.stopSlot(slotB = false)
            refreshStatus()
            return
        }

        val (targetIsB, alreadyReady) = slots.decideTarget(index)
        val target = slotPlayer(targetIsB)
        active = target
        slots.markActive(targetIsB)
        _activeIsPreload.value = targetIsB
        slotMediaId[if (targetIsB) 1 else 0] = current.mediaId
        if (!alreadyReady) {
            prepareSilent(target, current)
            slots.markReady(targetIsB, index)
        }
        target.play()
        refreshStatus()
    }

    /** 后台预加载 [item] 到非活动槽(P1);item 为空/非视频/无 URL 时清空该槽。 */
    fun prepareNext(index: Int, item: ReviewPlayable?) {
        nextItem = item
        val slotB = !slots.activeIsB
        val p = slotPlayer(slotB)
        if (item != null && item.isVideo && !item.streamUrl.isNullOrBlank()) {
            prepareSilent(p, item)
            slots.markReady(slotB, index)
            slotMediaId[if (slotB) 1 else 0] = item.mediaId
        } else {
            p.stop()
            slots.stopSlot(slotB)
            slotMediaId[if (slotB) 1 else 0] = null
        }
    }

    /**
     * 读取 [mediaId] 对应播放器的实时进度快照。
     * 媒体已切换(该 mediaId 已不在两个槽位上)返回 null——调用方必须跳过上报,
     * 防止拿新的 activePlayer 给旧 mediaId 上报造成串片。
     */
    fun snapshotFor(mediaId: String): ProgressSnapshot? {
        if (mediaId.isBlank()) return null
        val slotB = when {
            slotMediaId[1] == mediaId -> true
            slotMediaId[0] == mediaId -> false
            else -> return null
        }
        val p = slotPlayer(slotB)
        return ProgressSnapshot(
            positionMs = p.currentPosition,
            isPlaying = p.isPlaying,
            playWhenReady = p.playWhenReady,
        )
    }

    /** 页面开始滑动/离开当前项:保持停稳前不切换播放(不做动作,播放交给 settle)。 */
    fun onSwipeStarted() {
        // 滑动中不播放下一条的声音:当前播放器保持现状,等 settle 统一切换。
    }

    /** 当前真正播放的播放器实例(批阅页据此把 PlayerView 绑定到正确的实例)。 */
    fun activePlayer(): ExoPlayer = active

    /** 释放两个播放器资源(页面销毁时调用)。 */
    fun release() {
        player.stop()
        preload.stop()
        player.clearMediaItems()
        preload.clearMediaItems()
        slots.reset()
        active = player
        _activeIsPreload.value = false
        throttled = false
        currentIndex = -1
        slotMediaId[0] = null
        slotMediaId[1] = null
        nextItem = null
        _status.value = PlaybackStatus()
    }

    // ---- 内部 ----

    private fun slotPlayer(slotB: Boolean): ExoPlayer = if (slotB) preload else player

    private fun prepareSilent(p: ExoPlayer, item: ReviewPlayable) {
        if (!item.isVideo) {
            p.stop()
            return
        }
        p.stop()
        p.playWhenReady = false
        p.setMediaItem(MediaItem.fromUri(item.streamUrl.orEmpty()))
        p.prepare()
    }

    private fun ensureListeners() {
        if (listenersAttached) return
        listenersAttached = true
        player.addListener(listenerFor(player))
        preload.addListener(listenerFor(preload))
    }

    private fun listenerFor(p: ExoPlayer): Player.Listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (p !== active) return
            if (state == Player.STATE_BUFFERING) {
                // 当前缓冲:限制 P1 抢占带宽,并清空 P1 ready 防 stale-ready
                throttled = true
                val other = if (p === player) preload else player
                other.stop()
                slots.stopInactive()
            } else if (state == Player.STATE_READY && throttled) {
                throttled = false
                nextItem?.let { prepareNext(currentIndex + 1, it) }
            }
            refreshStatus()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (p !== active) return
            refreshStatus()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (p !== active) return
            refreshStatus()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (p !== active) return
            _status.value = PlaybackStatus(phase = PlaybackPhase.Error, error = error.message)
        }
    }

    private fun refreshStatus() {
        val p = active
        val phase = when (p.playbackState) {
            Player.STATE_IDLE -> PlaybackPhase.Idle
            Player.STATE_BUFFERING -> PlaybackPhase.Buffering
            Player.STATE_READY -> if (p.playWhenReady) PlaybackPhase.Playing else PlaybackPhase.Paused
            Player.STATE_ENDED -> PlaybackPhase.Ended
            else -> PlaybackPhase.Loading
        }
        _status.value = PlaybackStatus(phase = phase)
    }
}
