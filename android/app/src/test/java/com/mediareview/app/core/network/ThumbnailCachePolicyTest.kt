package com.mediareview.app.core.network

import android.content.Context
import android.content.ContextWrapper
import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.size.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@OptIn(coil.annotation.ExperimentalCoilApi::class)
class ThumbnailCachePolicyTest {
    private val cover = "https://paired.example/api/v1/media/m/cover?v=1"
    @Test fun coverAndThumbnailConsumersShareTheSameProfileAndVersionKey() {
        assertEquals(thumbnailCacheKey("account", cover), thumbnailCacheKey("account",
            thumbnailProfileUrl(cover, ThumbnailProfile.GRID_LARGE)))
        assertNotEquals(thumbnailCacheKey("account", cover), thumbnailCacheKey("account",
            thumbnailProfileUrl(cover, ThumbnailProfile.GRID_SMALL)))
        assertNotEquals(thumbnailCacheKey("account", cover), thumbnailCacheKey("account", cover.replace("v=1", "v=2")))
        assertNotEquals(thumbnailCacheKey("account", cover), thumbnailCacheKey("other", cover))
        assertNotEquals(thumbnailCacheKey("account", cover), thumbnailCacheKey("account", cover.replace("paired.example", "other.example")))
        assertNull(thumbnailCacheKey("account", cover.replace("/cover", "/original")))
        assertNull(thumbnailCacheKey("account", "file:///demo.jpg"))
    }
    @Test fun cacheNamespaceIsStableForSameCredentialsAndInvalidAfterClearOrSwitch() {
        val provider = TokenProvider()
        assertNull(provider.imageCacheNamespaceFor(cover))
        provider.set("first-secret", "https://paired.example")
        val first = provider.imageCacheNamespaceFor(cover)!!
        assertFalse(first.contains("first-secret"))
        provider.set("first-secret", "https://paired.example/")
        assertEquals(first, provider.imageCacheNamespaceFor(cover))
        val restored = TokenProvider().also { it.set("first-secret", "https://paired.example") }
        assertEquals(first, restored.imageCacheNamespaceFor(cover))
        assertNull(provider.imageCacheNamespaceFor(cover.replace("paired.example", "foreign.example")))
        provider.set("second-secret", "https://paired.example")
        assertNotEquals(first, provider.imageCacheNamespaceFor(cover))
        provider.clear()
        assertNull(provider.imageCacheNamespaceFor(cover))
    }

    private val context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }
    private class Chain(override val request: ImageRequest, val next: suspend (ImageRequest) -> ImageResult) : Interceptor.Chain {
        override val size = Size(256, 144)
        override fun withRequest(request: ImageRequest): Interceptor.Chain = Chain(request, next)
        override fun withSize(size: Size): Interceptor.Chain = this
        override suspend fun proceed(request: ImageRequest): ImageResult = next(request)
    }

    @Test fun sharedInterceptorUnifiesConsumerKeysWithoutTouchingOriginals() = runBlocking<Unit> {
        val provider = TokenProvider().also { it.set("secret", "https://paired.example") }
        val interceptor = ThumbnailCacheInterceptor(provider)
        val namespace = provider.imageCacheNamespaceFor(cover)!!
        val small = thumbnailProfileUrl(cover, ThumbnailProfile.GRID_SMALL)
        val request = ImageRequest.Builder(context).data(small).size(256, 144)
            .memoryCacheKey("album-cell:$small").build()
        interceptor.intercept(Chain(request) { actual ->
            assertEquals(thumbnailCacheKey(namespace, small), actual.memoryCacheKey!!.key)
            assertEquals(actual.memoryCacheKey!!.key, actual.diskCacheKey)
            assertNull(actual.placeholderMemoryCacheKey)
            assertEquals(namespace, actual.tags.tag(ThumbnailAuthScope::class.java)!!.namespace)
            ErrorResult(null, actual, IllegalStateException("synthetic result"))
        })
        val original = ImageRequest.Builder(context).data(cover.replace("/cover", "/original"))
            .size(256, 144).memoryCacheKey("original-policy").build()
        interceptor.intercept(Chain(original) { actual ->
            assertSame(original, actual)
            ErrorResult(null, actual, IllegalStateException("synthetic result"))
        })
    }

    @Test fun unauthenticatedThumbnailsCannotReadCacheAndLateResultsCannotSurviveAccountSwitch() = runBlocking<Unit> {
        val provider = TokenProvider()
        val request = ImageRequest.Builder(context).data(cover).size(256, 144).build()
        val interceptor = ThumbnailCacheInterceptor(provider)
        interceptor.intercept(Chain(request) { actual ->
            assertEquals(CachePolicy.DISABLED, actual.memoryCachePolicy)
            assertEquals(CachePolicy.DISABLED, actual.diskCachePolicy)
            ErrorResult(null, actual, IllegalStateException("synthetic result"))
        })
        provider.set("first", "https://paired.example")
        try {
            interceptor.intercept(Chain(request) { actual ->
                provider.set("second", "https://paired.example")
                ErrorResult(null, actual, IllegalStateException("late cached result"))
            })
            fail("late account result must be discarded")
        } catch (_: CancellationException) { }
    }
}
