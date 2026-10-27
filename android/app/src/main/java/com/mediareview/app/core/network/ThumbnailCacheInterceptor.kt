package com.mediareview.app.core.network

import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ImageResult
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 同一版本和档位跨封面/预取/查看器复用；原图的HTTP校验策略不受影响。 */
internal fun thumbnailCacheKey(namespace: String, raw: String): String? {
    val url = raw.toHttpUrlOrNull() ?: return null
    if (!Regex("/api/v1/media/[^/]+/(thumbnail|cover)").matches(url.encodedPath)) return null
    val canonical = url.newBuilder()
        .encodedPath(url.encodedPath.removeSuffix("/cover") + if (url.encodedPath.endsWith("/cover")) "/thumbnail" else "")
        .setQueryParameter("profile", url.queryParameter("profile") ?: ThumbnailProfile.GRID_LARGE.name)
        .build()
    return "thumbnail-v1:$namespace:$canonical"
}

/** 只携带不可逆命名空间，不在请求标签中放凭据。 */
internal class ThumbnailAuthScope(val namespace: String?)

/** 在Coil内存查找之前统一键，因此已缓存结果也不能绕过账号边界。 */
internal class ThumbnailCacheInterceptor(private val tokenProvider: TokenProvider) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data.toString()
        if (thumbnailCacheKey("", url) == null) return chain.proceed(request)
        val namespace = tokenProvider.imageCacheNamespaceFor(url)
        val builder = request.newBuilder().tag(ThumbnailAuthScope::class.java, ThumbnailAuthScope(namespace))
        if (namespace == null) {
            builder.memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED)
                .placeholderMemoryCacheKey(null as String?)
        } else {
            val key = thumbnailCacheKey(namespace, url)!!
            builder.memoryCacheKey(key).diskCacheKey(key)
        }
        val result = chain.proceed(builder.build())
        if (namespace != tokenProvider.imageCacheNamespaceFor(url)) {
            throw CancellationException("图片请求的配对状态已变化")
        }
        return result
    }
}
