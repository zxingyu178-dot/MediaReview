package com.mediareview.app.feature.v2.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.ImageLoader
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.mediareview.app.core.network.CacheAuthInterceptor
import com.mediareview.app.core.network.TokenProvider
import com.mediareview.app.di.AppModule
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 仅合成小图和进程内HTTP；不连接生产Server，不读取真实媒体。 */
@RunWith(AndroidJUnit4::class)
class ThumbnailLoadingIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val provider = TokenProvider()
    private lateinit var base: ImageLoader
    private lateinit var loader: ImageLoader
    private lateinit var disk: DiskCache
    private lateinit var directory: File
    private val oldHeaders = CompletableDeferred<Unit>()
    private val nextHeaders = CompletableDeferred<Unit>()
    private val png by lazy {
        val bitmap = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    @Before fun setup() {
        server.start()
        provider.set("synthetic-first", server.url("/").toString())
        val http = AppModule.provideCacheOkHttpClient(CacheAuthInterceptor(provider)).newBuilder()
            .eventListener(object : EventListener() {
                override fun responseHeadersEnd(call: Call, response: Response) {
                    if (call.request().url.encodedPath.contains("/0/")) oldHeaders.complete(Unit)
                    if (call.request().url.encodedPath.contains("/50/")) nextHeaders.complete(Unit)
                }
            }).build()
        base = AppModule.provideImageLoader(context, http, provider)
        directory = File(context.cacheDir, "loading-test-${UUID.randomUUID()}")
        disk = DiskCache.Builder().directory(directory).build()
        loader = base.newBuilder().diskCache(disk).build()
    }

    @After fun close() {
        loader.shutdown()
        base.shutdown()
        disk.clear()
        server.shutdown()
    }

    private fun response() = MockResponse().setHeader("Content-Type", "image/png")
        .setHeader("Cache-Control", "private, max-age=3600").setBody(Buffer().write(png))
    private fun request(id: Int, consumer: String) = ImageRequest.Builder(context)
        .data(server.url("/api/v1/media/$id/thumbnail?profile=GRID_SMALL&v=synthetic").toString())
        .size(256, 144).memoryCacheKey("$consumer:$id").build()

    @Test fun coldWarmDiskAndAccountIsolationUseTheActualSharedLoader() = runBlocking<Unit> {
        server.enqueue(response().setBodyDelay(180, TimeUnit.MILLISECONDS))
        val start = SystemClock.elapsedRealtime()
        val cold = loader.execute(request(0, "prefetch")) as SuccessResult
        val coldMs = SystemClock.elapsedRealtime() - start
        assertEquals(DataSource.NETWORK, cold.dataSource)
        val warmStart = SystemClock.elapsedRealtime()
        val warm = withTimeout(2_000) { loader.execute(request(0, "album")) } as SuccessResult
        val warmMs = SystemClock.elapsedRealtime() - warmStart
        assertEquals(DataSource.MEMORY_CACHE, warm.dataSource)
        loader.memoryCache!!.clear()
        val fromDisk = withTimeout(2_000) { loader.execute(request(0, "wall-retry")) } as SuccessResult
        assertEquals(DataSource.DISK, fromDisk.dataSource)
        assertEquals(1, server.requestCount)
        assertEquals("Bearer synthetic-first", server.takeRequest().getHeader("Authorization"))
        provider.set("synthetic-second", server.url("/").toString())
        server.enqueue(response())
        assertEquals(DataSource.NETWORK, (loader.execute(request(0, "wall")) as SuccessResult).dataSource)
        assertEquals("Bearer synthetic-second", server.takeRequest().getHeader("Authorization"))
        provider.clear()
        server.enqueue(MockResponse().setResponseCode(401))
        assertFalse(loader.execute(request(0, "viewer")) is SuccessResult)
        assertEquals(3, server.requestCount)
        assertNull(server.takeRequest().getHeader("Authorization"))
        android.util.Log.i("MRLoadingEvidence", "cache cold_ms=$coldMs warm_ms=$warmMs warm=MEMORY_CACHE disk=DISK http=3 successful_png_bytes=${png.size * 2}")
    }

    @Test fun scrollingInterruptsSlowThumbnailBodiesBeforeLoadingTheNewWindow() = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = response().apply {
                if (!request.path!!.contains("/50/") && !request.path!!.contains("/51/")) {
                    setBodyDelay(3, TimeUnit.SECONDS)
                }
            }
        }
        val urls = (0..51).map { server.url("/api/v1/media/$it/thumbnail?profile=GRID_SMALL&v=scroll").toString() }
        val positions = Channel<Int>(Channel.CONFLATED)
        val job = launch(Dispatchers.Default) {
            prefetchCoverWindows(urls, positions.receiveAsFlow()) { url ->
                loader.execute(ImageRequest.Builder(context).data(url).size(256, 144).build())
            }
        }
        try {
            positions.send(0)
            withTimeout(5_000) { oldHeaders.await() }
            val start = SystemClock.elapsedRealtime()
            positions.send(50)
            withTimeout(1_000) { nextHeaders.await() }
            android.util.Log.i("MRLoadingEvidence", "scroll new_window_headers_ms=${SystemClock.elapsedRealtime() - start} old_body_delay_ms=3000")
        } finally {
            job.cancelAndJoin()
            positions.close()
        }
    }
}
