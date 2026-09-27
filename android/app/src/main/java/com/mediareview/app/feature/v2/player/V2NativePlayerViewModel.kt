package com.mediareview.app.feature.v2.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.feature.v2.data.MediaRepository
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.model.V2PlaybackEndpoint
import com.mediareview.app.feature.v2.model.V2PlaybackSource
import com.mediareview.app.feature.v2.model.V2PlaybackStage
import com.mediareview.app.feature.v2.perf.V2Perf
import com.mediareview.app.feature.v2.player.native.state.PlaybackContext
import com.mediareview.app.feature.v2.player.native.state.PlaybackQueueItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 播放源解析状态：Loading / Ready / Error（Player 组合函数不得直接发网络请求）。 */
sealed interface V2PlaybackUiState {
    data object Loading : V2PlaybackUiState

    /** [endpoint] 是当前 [stage] 实际要播放的 URL + headers（headers 与 URL 成对切换）。 */
    data class Ready(
        val source: V2PlaybackSource,
        val stage: V2PlaybackStage,
        val endpoint: V2PlaybackEndpoint,
    ) : V2PlaybackUiState

    data class Failed(val message: String) : V2PlaybackUiState
}

/** 播放器状态：队列 + 当前索引 + 播放源解析状态。 */
data class V2PlayerState(
    val context: PlaybackContext = PlaybackContext(emptyList()),
    val ui: V2PlaybackUiState = V2PlaybackUiState.Loading,
) {
    val current: PlaybackQueueItem? get() = context.current
    val hasPrevious: Boolean get() = context.hasPrevious
    val hasNext: Boolean get() = context.hasNext
    val currentMediaId: String? get() = context.current?.mediaId
}

/**
 * 完整播放器 ViewModel（Stage 8A §14 / §15）：
 *
 * - 播放源异步解析（[MediaRepository.resolvePlayback]）全部在本 ViewModel 内完成，
 *   Compose 只消费 [V2PlaybackUiState]；
 * - **只解析当前条目**：上一条 / 下一条只有真正切过去时才解析（允许预取下一条，禁止整队列解析）；
 * - Direct Play → 失败时最多一次 HLS 回退（[onPlaybackFailed]）→ 再失败进入 Error，禁止无限切换。
 */
@HiltViewModel
class V2NativePlayerViewModel @Inject constructor(
    private val repository: MediaRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(V2PlayerState())
    val state: StateFlow<V2PlayerState> = _state.asStateFlow()

    private var resolveJob: Job? = null

    /** 预取下一条（mediaId → 已解析播放源），切过去时直接命中，不重复请求。 */
    private val prefetched = mutableMapOf<String, V2PlaybackSource>()

    private var currentSource: V2PlaybackSource? = null
    private var stage: V2PlaybackStage = V2PlaybackStage.DIRECT

    /**
     * 打开播放器（队列 + 起始索引）。
     * 幂等：同一个队列 / 同一起始索引重复调用不会重复请求网络。
     */
    fun open(context: PlaybackContext) {
        val existing = _state.value
        if (existing.context == context && existing.ui !is V2PlaybackUiState.Failed) return
        V2Perf.openPlayer(context.queue.getOrNull(context.currentIndex)?.mediaId.orEmpty())
        prefetched.clear()
        currentSource = null
        stage = V2PlaybackStage.DIRECT
        _state.value = V2PlayerState(context = context, ui = V2PlaybackUiState.Loading)
        load(context.currentIndex)
    }

    /** 切换到指定索引：先把索引落到 UI（上/下一条可用性立即更新），再异步解析该条播放源。 */
    fun moveTo(index: Int) {
        val context = _state.value.context
        if (context.queue.isEmpty()) return
        val target = index.coerceIn(0, context.queue.lastIndex)
        if (target == context.currentIndex && _state.value.ui is V2PlaybackUiState.Ready) return
        stage = V2PlaybackStage.DIRECT
        _state.value = _state.value.copy(
            context = context.moveTo(target),
            ui = V2PlaybackUiState.Loading,
        )
        load(target)
    }

    /**
     * 播放内核报错（GSY Error）：Direct 失败 → 用同一个已解析源切到 HLS（不重新请求）；
     * HLS 再失败 → Error（不再回退，禁止无限切换）。
     */
    fun onPlaybackFailed() {
        val source = currentSource
        val nextStage = source?.nextStageAfterFailure(stage)
        val endpoint = nextStage?.let { source.endpointFor(it) }
        if (source != null && nextStage != null && endpoint != null) {
            stage = nextStage
            _state.value = _state.value.copy(
                ui = V2PlaybackUiState.Ready(source, nextStage, endpoint),
            )
            return
        }
        _state.value = _state.value.copy(
            ui = V2PlaybackUiState.Failed(
                repository.mode.let { mode ->
                    if (mode == V2DataMode.SERVER) "播放失败，服务器未提供可用地址" else "播放失败"
                },
            ),
        )
    }

    /** 用户在 Error 层重试：重新解析当前条目（从 Direct 重新开始）。 */
    fun retry() {
        load(_state.value.context.currentIndex, allowPrefetch = false)
    }

    /** 播放进度上报（Demo 无该能力时由 Repository 侧忽略；Server 为 POST /media/{id}/progress）。 */
    fun reportProgress(positionMs: Long, isPaused: Boolean) {
        val mediaId = _state.value.currentMediaId ?: return
        viewModelScope.launch {
            repository.reportProgress(mediaId, positionMs, isPaused)
        }
    }

    // ---------- 内部 ----------

    private fun load(index: Int, allowPrefetch: Boolean = true) {
        val item = _state.value.context.queue.getOrNull(index) ?: return
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            val source = prefetched.remove(item.mediaId)
                ?: try {
                    repository.resolvePlayback(item.mediaId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    _state.value = _state.value.copy(
                        ui = V2PlaybackUiState.Failed(error.message ?: "无法获取播放地址"),
                    )
                    return@launch
                }
            val endpoint = source.endpointFor(stage)
            if (endpoint == null) {
                _state.value = _state.value.copy(
                    ui = V2PlaybackUiState.Failed("服务器未提供可用的播放地址"),
                )
                return@launch
            }
            currentSource = source
            _state.value = _state.value.copy(
                ui = V2PlaybackUiState.Ready(source, stage, endpoint),
            )
            // Stage 8A.1.1 性能指标: 播放地址就绪(相对 player 会话；first_frame 由播放内核进度回调记录)
            V2Perf.player()?.markOnce("playback_info_ready", "playback_info_ready")
            if (allowPrefetch) prefetchNext()
        }
    }

    /** 预取下一条（仅一条，不解析整个队列）。 */
    private fun prefetchNext() {
        val context = _state.value.context
        val next = context.queue.getOrNull(context.currentIndex + 1) ?: return
        if (prefetched.containsKey(next.mediaId)) return
        if (prefetched.size > MAX_PREFETCH) prefetched.clear()
        viewModelScope.launch {
            val resolved = runCatching { repository.resolvePlayback(next.mediaId) }.getOrNull() ?: return@launch
            prefetched[next.mediaId] = resolved
        }
    }

    private companion object {
        /** 预取窗口上限：只允许少量前瞻，避免变相"整队列解析"。 */
        const val MAX_PREFETCH = 2
    }
}