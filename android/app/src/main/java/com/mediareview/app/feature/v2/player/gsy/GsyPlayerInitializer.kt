package com.mediareview.app.feature.v2.player.gsy

import android.content.Context
import com.shuyu.gsyvideoplayer.player.PlayerFactory
import tv.danmaku.ijk.media.exo2.Exo2PlayerManager

/**
 * 应用级 GSY 统一初始化：固定播放器内核为 Exo2PlayerManager（Media3/Exo 路线），
 * 为后续 MP4 / HLS / Jellyfin Direct Play + HLS fallback 做准备。
 * 应用启动时调用一次，播放页不再重复切换内核。
 */
object GsyPlayerInitializer {

    fun init(context: Context) {
        PlayerFactory.setPlayManager(Exo2PlayerManager::class.java)
    }
}
