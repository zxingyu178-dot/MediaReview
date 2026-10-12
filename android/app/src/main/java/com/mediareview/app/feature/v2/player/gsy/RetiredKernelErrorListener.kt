package com.mediareview.app.feature.v2.player.gsy

import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer

/** 只在确定换源或退出时调用；旧 Exo 释放错误不能经全局 manager 投递到继任源。 */
internal fun detachRetiredKernelErrorListener(host: StandardGSYVideoPlayer?, loadedUrl: String) {
    if (host == null || loadedUrl.isEmpty()) return
    val manager = host.getGSYVideoManager()
    if (manager.listener() !== host) return
    val kernel = manager.player?.mediaPlayer as? IjkExo2MediaPlayer ?: return
    if (kernel.dataSource != loadedUrl) return
    // 新内核仍由 GSY initVideo 安装自己的 OnErrorListener，当前源错误照常反馈。
    kernel.setOnErrorListener(null)
}
