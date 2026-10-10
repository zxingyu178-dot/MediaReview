package com.mediareview.app.feature.v2.data

import com.mediareview.app.feature.v2.model.V2Media

/** Demo 资源路径工具：把 assets 相对路径转为 URI，。 */
object DemoAssets {

    private const val ASSET_ROOT = "demo_media"

    const val VIDEOS_DIR = "demo_media/videos"
    const val IMAGES_DIR = "demo_media/images"


    /** "demo_media/videos/01.mp4" -> "asset:///demo_media/videos/01.mp4" */
    fun assetUri(assetPath: String): String = "asset:///$assetPath"

    fun thumbUri(media: V2Media): String = coverUri(media)

    /**
     * 统一封面 URI（UI 封面一律走这里，不感知数据来源）：
     * - 图片：res/raw 内原图（android.resource://），与 Viewer 同一资源；
     * - 视频：res/raw 内 Poster 帧（android.resource://）。
     * 未来 Production 换成 Server / Jellyfin Thumbnail URL 时仅修改本映射。
     */
    fun coverUri(media: V2Media): String {
        val base = media.assetPath.substringAfterLast('/')
        return if (media.isVideo) {
            val num = base.takeWhile { it.isDigit() }.ifEmpty { "01" }
            "android.resource://${com.mediareview.app.BuildConfig.APPLICATION_ID}/raw/demo_video_${num}_poster"
        } else {
            imageUri(media)
        }
    }

    /**
     * 图片原图 URI：Demo 图片已移至 res/raw（asset:/// 在本机 Coil 上加载失败），
     * 用 android.resource:// 播放，与本地视频方案一致。
     */
    fun imageUri(media: V2Media): String {
        val base = media.assetPath.substringAfterLast('/')
            .removeSuffix(".jpg").removeSuffix(".jpeg").removeSuffix(".png").removeSuffix(".webp")
        return "android.resource://${com.mediareview.app.BuildConfig.APPLICATION_ID}/raw/demo_$base"
    }

    /**
     * 视频可播放 URI：Demo 视频位于 res/raw（android.resource://<pkg>/raw/demo_<name>），
     * 与统一封面同源；图片仍走 [imageUri]。UI 不感知这里的资源形式，
     * 未来 Production 换成 Server / Jellyfin Direct URL 时仅修改本映射。
     */
    fun playbackUri(media: V2Media): String {
        return if (media.isVideo) {
            val base = media.assetPath.substringAfterLast('/').removeSuffix(".mp4")
            "android.resource://${com.mediareview.app.BuildConfig.APPLICATION_ID}/raw/demo_$base"
        } else {
            imageUri(media)
        }
    }

}
