package com.mediareview.app.feature.v2.data

import android.content.Context
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2SpriteManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Demo 资源路径工具：把 assets 相对路径转为 URI，并读取雪碧图 manifest。 */
object DemoAssets {

    private const val ASSET_ROOT = "demo_media"

    const val VIDEOS_DIR = "demo_media/videos"
    const val IMAGES_DIR = "demo_media/images"
    const val SPRITES_DIR = "demo_media/sprites"

    private val json = Json { ignoreUnknownKeys = true }

    /** "demo_media/videos/01.mp4" -> "asset:///demo_media/videos/01.mp4" */
    fun assetUri(assetPath: String): String = "asset:///$assetPath"

    fun thumbUri(media: V2Media): String = assetUri(media.thumbPath)

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

    fun playbackUri(media: V2Media): String = assetUri(media.assetPath)

    fun spriteUri(media: V2Media): String? = media.spritePath?.let { assetUri(it) }

    /** 从 assets 读取并解析雪碧图 manifest json。 */
    fun readSpriteManifest(context: Context, manifestPath: String): V2SpriteManifest? {
        return try {
            val text = context.assets.open(manifestPath).bufferedReader().use { it.readText() }
            val obj = json.parseToJsonElement(text) as JsonObject
            V2SpriteManifest(
                source = obj["source"]?.jsonPrimitive?.content ?: "",
                columns = obj["columns"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
                rows = obj["rows"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
                cell_width = obj["cell_width"]?.jsonPrimitive?.content?.toIntOrNull() ?: 160,
                cell_height = obj["cell_height"]?.jsonPrimitive?.content?.toIntOrNull() ?: 90,
                frame_interval_s = obj["frame_interval_s"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 2.0,
                duration_s = obj["duration_s"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                frame_count = obj["frame_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
            )
        } catch (_: Exception) {
            null
        }
    }
}
