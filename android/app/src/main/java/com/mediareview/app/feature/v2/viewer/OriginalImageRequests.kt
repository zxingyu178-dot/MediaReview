package com.mediareview.app.feature.v2.viewer

import android.content.Context
import com.mediareview.app.core.network.ImageRequestLifetime
import androidx.compose.runtime.staticCompositionLocalOf
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class OriginalImageSession(val namespace: String = "", val lifetime: ImageRequestLifetime? = null, val onReady: (String) -> Unit = {})
internal val LocalOriginalImageSession = staticCompositionLocalOf { OriginalImageSession() }

internal fun originalImageCacheKey(namespace: String, url: String) = "$namespace|$url"

/** 同源30秒邻接窗口，尊重no-store/no-cache/must-revalidate；不修改全局缓存规则。 */
internal fun originalImageRequest(context: Context, url: String?, namespace: String, width: Int, height: Int, prefetch: Boolean = false): ImageRequest {
    val builder = ImageRequest.Builder(context).data(url).crossfade(false)
        .size(width.coerceIn(1, 2048), height.coerceIn(1, 2048))
        .precision(Precision.EXACT)
    if (url != null && url.toHttpUrlOrNull() != null) {
        val key = originalImageCacheKey(namespace, url)
        builder.diskCacheKey(key).memoryCacheKey(key)
            .addHeader("Cache-Control", "max-stale=30")
            // memory命中会跳过HTTP缓存校验；当前Painter保留位图，跨页仅复用源盘缓存。
            .memoryCachePolicy(CachePolicy.DISABLED)
    }
    if (prefetch) builder.memoryCachePolicy(CachePolicy.DISABLED)
    return builder.build()
}

/** Next先于Previous，最多两个源；±2绝不进入original队列。 */
internal fun adjacentOriginalIndices(current: Int, count: Int): List<Int> =
    listOf(current + 1, current - 1).filter { it in 0 until count }
