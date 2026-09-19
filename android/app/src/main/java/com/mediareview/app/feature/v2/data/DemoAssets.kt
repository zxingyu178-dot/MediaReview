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
