package com.mediareview.app.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.core.media.PlaybackPhase
import com.mediareview.app.core.media.PlayerCore
import com.mediareview.app.core.model.PlaybackEndpointDto
import com.mediareview.app.feature.home.data.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerUiState(
    val loading: Boolean = true,
    val title: String = "",
    val error: String? = null,
    /** 当前是否处于 HLS 转码回退(Direct Play 失败后的唯一一次回退)。 */
    val usingFallback: Boolean = false,
)

/**
 * 普通播放器:通过播放 API 获取 Direct Play 端点(含设备级凭据 headers)交给共享 PlayerCore;
 * 起播失败由 [PlaybackStateMachine] 驱动唯一一次 HLS 回退,再失败进入中文终态错误。
 * 凭据只经 PlayerCore 的 RequestMetadata 注入 HTTP 数据源,不进入 URL。
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repository: MediaRepository,
    val core: PlayerCore,
) : ViewModel() {

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    private val machine = PlaybackStateMachine()
    private var currentMediaId: String = ""
    private var resumePositionMs: Long = 0L
    private var hlsEndpoint: PlaybackEndpointDto? = null

    init {
        // 播放器错误 → 状态机事件(Direct 失败触发唯一一次回退;回退失败进入终态)
        viewModelScope.launch {
            core.errors.collect { event ->
                // 会话身份过滤:丢弃上一媒体迟到/缓冲的错误,避免污染新会话(I3)
                if (!isCurrentSessionError(event.mediaId, currentMediaId)) return@collect
                val reason = reasonForPlaybackError(event.error.errorCode)
                val stageEvent = errorEventFor(machine.stage.value, reason) ?: return@collect
                machine.dispatch(stageEvent)
                when (val stage = machine.stage.value) {
                    is PlaybackStage.FallbackHls -> startHls()
                    is PlaybackStage.Terminal -> _ui.update {
                        it.copy(loading = false, error = stage.message, usingFallback = false)
                    }

                    else -> Unit
                }
            }
        }
        // 起播成功判定:播放器 READY/Playing 时按当前阶段登记
        viewModelScope.launch {
            core.status.collect { status ->
                if (status.phase != PlaybackPhase.Ready && status.phase != PlaybackPhase.Playing) {
                    return@collect
                }
                when (machine.stage.value) {
                    is PlaybackStage.Idle -> machine.dispatch(PlaybackEvent.DirectStartSucceeded)
                    is PlaybackStage.FallbackHls -> machine.dispatch(PlaybackEvent.HlsStartSucceeded)
                    else -> Unit
                }
            }
        }
    }

    fun load(mediaId: String) {
        currentMediaId = mediaId
        machine.reset()
        viewModelScope.launch {
            _ui.value = PlayerUiState(loading = true)
            val info = repository.loadPlayback(mediaId)
            val direct = info?.direct?.takeIf { it.url.isNotBlank() }
                ?: info?.stream_url?.takeIf { it.isNotBlank() }?.let { PlaybackEndpointDto(url = it) }
            if (info == null || direct == null) {
                _ui.value = PlayerUiState(loading = false, error = "无法获取播放地址")
                return@launch
            }
            resumePositionMs = info.resume_position_ms
            hlsEndpoint = info.fallback_hls
            _ui.value = PlayerUiState(loading = false, title = info.title)
            core.playStream(mediaId, direct.url, direct.headers, resumePositionMs)
        }
    }

    /** Direct Play 失败后的唯一一次 HLS 回退;无回退端点时进入终态。 */
    private fun startHls() {
        val endpoint = hlsEndpoint
        if (endpoint == null || endpoint.url.isBlank()) {
            machine.dispatch(PlaybackEvent.HlsStartFailed(PlaybackFailureReason.DATASOURCE))
            val terminal = machine.stage.value as? PlaybackStage.Terminal
            _ui.update { it.copy(loading = false, error = terminal?.message, usingFallback = false) }
            return
        }
        _ui.update { it.copy(loading = true, error = null, usingFallback = true) }
        core.playStream(currentMediaId, endpoint.url, endpoint.headers, resumePositionMs)
    }

    fun togglePlayPause() {
        if (core.player.isPlaying) core.pause() else core.resume()
    }

    fun seekTo(positionMs: Long) = core.seekTo(positionMs)

    fun setPlaybackSpeed(speed: Float) = core.setPlaybackSpeed(speed)
    fun setVolume(volume: Float) = core.setVolume(volume)

    /** 回传播放进度到 Jellyfin(每次直接读取当前实时状态,不缓存旧 playing)。 */
    fun reportProgress() {
        val mediaId = currentMediaId
        if (mediaId.isBlank()) return
        val snap = core.snapshotFor(mediaId) ?: return
        viewModelScope.launch { repository.reportProgress(mediaId, snap.positionMs, !snap.isPlaying) }
    }

    /** 退出播放器/切走时补最后一条进度(独立作用域,不随页面销毁取消)。 */
    fun reportFinalProgress() {
        val mediaId = currentMediaId
        if (mediaId.isBlank()) return
        val snap = core.snapshotFor(mediaId) ?: return
        GlobalScope.launch(Dispatchers.IO) {
            repository.reportProgress(mediaId, snap.positionMs, !snap.isPlaying)
        }
    }

    override fun onCleared() {
        core.release()
        super.onCleared()
    }
}
