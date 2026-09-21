package com.mediareview.app.feature.v2.player.native.state

import com.shuyu.gsyvideoplayer.compose.native_.GSYPlayState

/**
 * 播放阶段 → 中央浮层（纯逻辑映射）。
 * Loading（Preparing/Buffering）、Completed（重播/下一条）、Error（重试/返回）。
 */
object PlaybackUiMapper {

    enum class CenterOverlay { NONE, LOADING, COMPLETED, ERROR }

    fun overlayFor(state: GSYPlayState): CenterOverlay = when (state) {
        GSYPlayState.Preparing, GSYPlayState.Buffering -> CenterOverlay.LOADING
        GSYPlayState.Completed -> CenterOverlay.COMPLETED
        GSYPlayState.Error -> CenterOverlay.ERROR
        else -> CenterOverlay.NONE
    }

    /** 播放 / 暂停图标选择：以 snapshot 为准。 */
    fun isPlaying(state: GSYPlayState): Boolean =
        state == GSYPlayState.Playing || state == GSYPlayState.Buffering
}
