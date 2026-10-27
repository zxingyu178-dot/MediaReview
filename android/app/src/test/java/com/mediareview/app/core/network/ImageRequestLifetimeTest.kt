package com.mediareview.app.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ImageRequestLifetimeTest {
    @Test fun queuedThumbnailCannotUseAnotherAccountOrNewPairingAfterScopeChanged() {
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/api/v1/media/p1/thumbnail").toString()
            val provider = TokenProvider().also { it.set("first", server.url("/").toString()) }
            val scope = ThumbnailAuthScope(provider.imageCacheNamespaceFor(url))
            val client = OkHttpClient.Builder().addInterceptor(CacheAuthInterceptor(provider)).build()
            val request = Request.Builder().url(url).tag(ThumbnailAuthScope::class.java, scope).build()
            provider.set("second", server.url("/").toString())
            try { client.newCall(request).execute().close(); fail("stale account must fail before HTTP") }
            catch (_: IOException) { }
            provider.clear()
            val anonymous = request.newBuilder().tag(ThumbnailAuthScope::class.java, ThumbnailAuthScope(null)).build()
            provider.set("third", server.url("/").toString())
            try { client.newCall(anonymous).execute().close(); fail("late pairing must not revive request") }
            catch (_: IOException) { }
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun pageCancellationInterruptsBodyAfterHeadersThroughExistingInterceptor() {
        val server = MockWebServer()
        val reader = Executors.newSingleThreadExecutor()
        server.enqueue(MockResponse().setBody("original").setBodyDelay(5, TimeUnit.SECONDS))
        server.start()
        try {
            val lifetime = ImageRequestLifetime()
            val client = OkHttpClient.Builder().addInterceptor(CacheAuthInterceptor(TokenProvider())).build()
            val call = client.newCall(Request.Builder().url(server.url("/api/v1/media/p1/original"))
                .tag(ImageRequestLifetime::class.java, lifetime).build())
            call.execute().use { response ->
                val body = reader.submit<Boolean> {
                    try { response.body!!.string(); false } catch (_: IOException) { true }
                }
                lifetime.cancel()
                assertTrue(call.isCanceled())
                assertTrue("已收到headers的慢body必须被取消", body.get(2, TimeUnit.SECONDS))
            }
        } finally { reader.shutdownNow(); server.shutdown() }
    }

    @Test fun lateRegistrationCannotReviveCancelledPage() {
        val lifetime = ImageRequestLifetime()
        val call = OkHttpClient().newCall(Request.Builder().url("http://localhost/original").build())
        lifetime.cancel()
        lifetime.register(call)
        assertTrue(call.isCanceled())
        lifetime.cancel()
    }
}
