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
 * 完整播放器 ViewModel（Stage 8A §14 / §15，Stage 8B §6~§9 正确性修正）：
 *
 * - 播放源异步解析（[MediaRepository.resolvePlayback]）全部在本 ViewModel 内完成，
 *   Compose 只消费 [V2PlaybackUiState]；
 * - **只解析当前条目**：上一条 / 下一条只有真正切过去时才解析（只允许预取"下一条"一条，
 *   禁止整队列解析）；
 * - 播放语义（Direct → 一次 HLS → Error、stale source guard、retry 回 Direct）全部委托给
 *   [V2PlaybackSourceController]，与批阅模式共享同一套实现；
 * - 内核报错必须带上"报错时播放器里实际装载的 mediaId"（[onPlaybackFailed]）：
 *   旧媒体的迟到错误会被判定为过期事件并忽略，绝不写进新媒体的 UI。
 */
@HiltViewModel
class V2NativePlayerViewModel @Inject constructor(
    private val repository: MediaRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(V2PlayerState())
    val state: StateFlow<V2PlayerState> = _state.asStateFlow()

    private val sources = V2PlaybackSourceController()

    private var resolveJob: Job? = null
    private var prefetchJob: Job? = null

    /**
     * 打开播放器（队列 + 起始索引）。
     * 幂等：同一个队列 / 同一起始索引重复调用不会重复请求网络。
     */
    fun open(context: PlaybackContext) {
        val existing = _state.value
        if (existing.context == context && existing.ui !is V2PlaybackUiState.Failed) return
        V2Perf.openPlayer(context.queue.getOrNull(context.currentIndex)?.mediaId.orEmpty())
        sources.moveTo("")
        sources.clearPrefetch()
        _state.value = V2PlayerState(context = context, ui = V2PlaybackUiState.Loading)
        load(context.currentIndex)
    }

    /** 切换到指定索引：先把索引落到 UI（上/下一条可用性立即更新），再异步解析该条播放源。 */
    fun moveTo(index: Int) {
        val context = _state.value.context
        if (context.queue.isEmpty()) return
        val target = index.coerceIn(0, context.queue.lastIndex)
        if (target == context.currentIndex && _state.value.ui is V2PlaybackUiState.Ready) return
        _state.value = _state.value.copy(
            context = context.moveTo(target),
            ui = V2PlaybackUiState.Loading,
        )
        load(target)
    }

    /**
     * 播放内核报错（GSY Error）。
     *
     * @param failingMediaId 报错时播放器内核里**实际装载**的媒体 id（由播放层提供）。
     * 只有它等于当前媒体时才处理；否则视为旧媒体的迟到错误直接忽略（§7）。
     */
    fun onPlaybackFailed(failingMediaId: String) {
        if (failingMediaId.isBlank()) return
        apply(sources.onPlaybackFailed(sources.token, failingMediaId))
    }

    /**
     * 用户在 Error 层重试（§6）：重新解析当前条目，并且**必须从 Direct 重新开始**
     * （[V2PlaybackSourceController.moveTo] 在解析前就清空旧 source、回到 Direct、
     * 作废旧令牌），不从上一次的 HLS 状态继续。
     */
    fun retry() {
        _state.value = _state.value.copy(ui = V2PlaybackUiState.Loading)
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
        prefetchJob?.cancel()
        // Source Identity: 解析开始前就切换身份并清空上一视频的 Source（§7 / §8）
        val eventToken = sources.moveTo(item.mediaId)
        resolveJob = viewModelScope.launch {
            val resolved = sources.takePrefetched(item.mediaId)
                ?: try {
                    repository.resolvePlayback(item.mediaId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    apply(sources.onResolveFailed(eventToken, error.message))
                    return@launch
                }
            apply(sources.onResolved(eventToken, resolved))
            if (allowPrefetch) prefetchNext()
        }
    }

    /** 写出一次播放决策（过期事件不写任何状态）。 */
    private fun apply(decision: V2PlaybackDecision) {
        when (decision) {
            V2PlaybackDecision.Ignore -> return
            is V2PlaybackDecision.Play -> {
                val source = sources.source ?: return
                _state.value = _state.value.copy(
                    ui = V2PlaybackUiState.Ready(source, decision.stage, decision.endpoint),
                )
                // Stage 8A.1.1 性能指标: 播放地址就绪(相对 player 会话；first_frame 由播放内核进度回调记录)
                V2Perf.player()?.markOnce("playback_info_ready", "playback_info_ready")
            }
            is V2PlaybackDecision.Fail -> {
                _state.value = _state.value.copy(
                    ui = V2PlaybackUiState.Failed(decision.message ?: defaultFailureMessage()),
                )
            }
        }
    }

    /** 预取"下一条"的一个播放源（单槽；切换媒体时取消上一条预取任务）。 */
    private fun prefetchNext() {
        val context = _state.value.context
        val next = context.queue.getOrNull(context.currentIndex + 1) ?: return
        prefetchJob = viewModelScope.launch {
            val resolved = runCatching { repository.resolvePlayback(next.mediaId) }.getOrNull()
                ?: return@launch
            sources.prefetch(next.mediaId, resolved)
        }
    }

    private fun defaultFailureMessage(): String =
        if (repository.mode == V2DataMode.SERVER) "播放失败，服务器未提供可用地址" else "播放失败"
}