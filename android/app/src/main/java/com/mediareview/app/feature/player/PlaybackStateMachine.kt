package com.mediareview.app.feature.player

import androidx.media3.common.PlaybackException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 播放失败原因分类(映射 Media3 PlaybackException 错误码)。 */
enum class PlaybackFailureReason {
    /** 数据源/网络:读不到视频数据。 */
    DATASOURCE,

    /** 容器/封装:不支持的视频封装格式。 */
    CONTAINER,

    /** 解码器:不支持的音视频编码。 */
    DECODER,
}

/** 把 Media3 错误码归类为失败原因;未知码按数据源处理(最常见且可回退)。 */
fun reasonForPlaybackError(errorCode: Int): PlaybackFailureReason = when (errorCode) {
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    -> PlaybackFailureReason.CONTAINER

    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
    -> PlaybackFailureReason.DECODER

    else -> PlaybackFailureReason.DATASOURCE
}

/** 播放器所处阶段。attempt/fallbackAttempt 用于钉死"只回退一次"。 */
sealed interface PlaybackStage {
    data object Idle : PlaybackStage

    /** Direct Play 已成功起播。 */
    data class DirectPlaying(val attempt: Int) : PlaybackStage

    /** HLS 回退阶段(唯一一次);起播成功/失败都在此阶段上表达。 */
    data class FallbackHls(val attempt: Int) : PlaybackStage

    /** 终态:直连与回退均失败,带中文错误;只有 Cancelled/Retry 可离开。 */
    data class Terminal(val reason: PlaybackFailureReason, val fallbackAttempt: Int) : PlaybackStage {
        val message: String
            get() = "无法播放该视频(直连与转码回退均失败):${failureLabel(reason)}"
    }
}

/** 失败原因的中文说明。 */
fun failureLabel(reason: PlaybackFailureReason): String = when (reason) {
    PlaybackFailureReason.DATASOURCE -> "无法读取视频数据"
    PlaybackFailureReason.CONTAINER -> "不支持的视频封装格式"
    PlaybackFailureReason.DECODER -> "不支持的视频编码"
}

sealed interface PlaybackEvent {
    /** Direct Play 起播成功(播放器进入 READY/播放)。 */
    data object DirectStartSucceeded : PlaybackEvent

    /** Direct Play 起播失败(数据源/容器/解码器)。 */
    data class DirectStartFailed(val reason: PlaybackFailureReason) : PlaybackEvent

    /** HLS 回退起播成功。 */
    data object HlsStartSucceeded : PlaybackEvent

    /** HLS 回退起播失败 → 终态。 */
    data class HlsStartFailed(val reason: PlaybackFailureReason) : PlaybackEvent

    /** 用户取消/切换媒体/离开:完全重置,新会话重新获得回退配额。 */
    data object Cancelled : PlaybackEvent

    /** 终态后用户点重试:回到 Idle 开始新会话。 */
    data object RetryRequested : PlaybackEvent
}

/** 纯转移函数:同输入必同输出,便于 JVM 全覆盖测试。 */
object PlaybackTransitions {
    fun reduce(stage: PlaybackStage, event: PlaybackEvent): PlaybackStage = when (event) {
        is PlaybackEvent.DirectStartSucceeded -> when (stage) {
            // 只有 Idle 上的 Direct 成功才有效;迟到的旧 Direct 成功事件被忽略
            is PlaybackStage.Idle -> PlaybackStage.DirectPlaying(attempt = 1)
            else -> stage
        }

        is PlaybackEvent.DirectStartFailed -> when (stage) {
            is PlaybackStage.Idle, is PlaybackStage.DirectPlaying ->
                PlaybackStage.FallbackHls(attempt = 1)

            // 已经处于回退阶段又收到 Direct 失败(迟到/重复):直接终态,不再回退
            is PlaybackStage.FallbackHls ->
                PlaybackStage.Terminal(event.reason, fallbackAttempt = stage.attempt)

            is PlaybackStage.Terminal -> stage
        }

        is PlaybackEvent.HlsStartSucceeded -> when (stage) {
            is PlaybackStage.FallbackHls -> stage
            else -> stage
        }

        is PlaybackEvent.HlsStartFailed -> when (stage) {
            is PlaybackStage.FallbackHls ->
                PlaybackStage.Terminal(event.reason, fallbackAttempt = stage.attempt)

            else -> stage
        }

        PlaybackEvent.Cancelled -> PlaybackStage.Idle
        PlaybackEvent.RetryRequested -> PlaybackStage.Idle
    }
}

/** 普通播放器的播放阶段状态机(Direct → 唯一一次 HLS 回退 → 终态)。 */
class PlaybackStateMachine {
    private val _stage = MutableStateFlow<PlaybackStage>(PlaybackStage.Idle)
    val stage: StateFlow<PlaybackStage> = _stage.asStateFlow()

    fun dispatch(event: PlaybackEvent) {
        _stage.value = PlaybackTransitions.reduce(_stage.value, event)
    }

    fun reset() {
        _stage.value = PlaybackStage.Idle
    }
}
