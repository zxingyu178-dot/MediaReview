package com.mediareview.app.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.core.media.PlayerCore
import com.mediareview.app.feature.home.data.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerUiState(
    val loading: Boolean = true,
    val title: String = "",
    val error: String? = null,
)

/**
 * 普通播放器:只接收 mediaId,通过播放 API 获取 Jellyfin 直连流地址后交给共享 PlayerCore。
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repository: MediaRepository,
    val core: PlayerCore,
) : ViewModel() {

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    private var currentMediaId: String = ""

    fun load(mediaId: String) {
        currentMediaId = mediaId
        viewModelScope.launch {
            _ui.value = PlayerUiState(loading = true)
            val info = repository.loadPlayback(mediaId)
            if (info == null || info.stream_url.isBlank()) {
                _ui.value = PlayerUiState(loading = false, error = "无法获取播放地址")
            } else {
                _ui.value = PlayerUiState(loading = false, title = info.title)
                core.playStream(mediaId, info.stream_url)
            }
        }
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
