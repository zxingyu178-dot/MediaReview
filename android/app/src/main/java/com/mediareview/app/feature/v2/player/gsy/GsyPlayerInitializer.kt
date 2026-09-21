package com.mediareview.app.feature.v2.player.gsy

import android.content.Context
import com.shuyu.gsyvideoplayer.player.PlayerFactory
import com.shuyu.gsyvideoplayer.utils.GSYVideoType
import tv.danmaku.ijk.media.exo2.Exo2PlayerManager

/**
 * 应用级 GSY 统一初始化（应用启动时调用一次，播放页不再重复切换内核）：
 * - 固定播放器内核为 Exo2PlayerManager（Media3/Exo 路线），为后续 MP4 / HLS /
 *   Jellyfin Direct Play + HLS fallback 做准备；
 * - 渲染固定为 TextureView（GSYVideoType.TEXTURE）：
 *   1. TextureView 内容走 HWUI 合成，支持平移/旋转/动画与 GSY 全屏容器迁移，
 *      横竖屏切换无 SurfaceView 的独立层级/黑边问题；
 *   2. TextureView 内容可被截屏（SurfaceView/GLSurfaceView 的独立 Surface 在部分设备
 *      与 adb screencap 下截不到）；
 *   3. 家庭媒体批阅场景不需要 SurfaceView 的极低层级/功耗优势。
 *   注：Android 模拟器默认 -gpu auto 的硬件 GL 模拟对 MediaCodec→TextureView 链路
 *   存在兼容问题（解码正常推进但纹理黑）；真机与 -gpu host/swiftshader 均正常，
 *   属模拟器环境问题，非 App 逻辑问题。
 */
object GsyPlayerInitializer {

    fun init(context: Context) {
        PlayerFactory.setPlayManager(Exo2PlayerManager::class.java)
        GSYVideoType.setRenderType(GSYVideoType.TEXTURE)
    }
}
