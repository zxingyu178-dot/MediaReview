package com.mediareview.app.feature.v2

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mediareview.app.HiltTestActivity
import coil.ImageLoader
import coil.decode.DataSource
import coil.request.SuccessResult
import com.mediareview.app.feature.v2.viewer.originalImageRequest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** 实际Coil2.7源盘缓存与解码，生产original无TTL；no-store必须仍生效。 */
@RunWith(AndroidJUnit4::class)
@OptIn(coil.annotation.ExperimentalCoilApi::class)
class Stage8IOriginalSourceCacheTest {
    @get:Rule val compose = createAndroidComposeRule<HiltTestActivity>()

    @Test fun painterUsesActualExactViewportForNonPowerOfTwoJpeg() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val server = MockWebServer()
        val bitmap = Bitmap.createBitmap(1100, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, this) }.toByteArray()
        bitmap.recycle()
        val decodedResult = java.util.concurrent.atomic.AtomicReference<SuccessResult?>()
        val loader = ImageLoader.Builder(context).eventListener(object : coil.EventListener {
            override fun onSuccess(request: coil.request.ImageRequest, result: SuccessResult) { decodedResult.set(result) }
        }).build()
        server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes)))
            val request = originalImageRequest(context, server.url("/api/v1/media/nonpower/original?v=one").toString(), "painter", 300, 218)
                .newBuilder().allowHardware(false).build()
            compose.setContent {
                val density = LocalDensity.current
                val painter = coil.compose.rememberAsyncImagePainter(request, imageLoader = loader, contentScale = ContentScale.Fit)
                Image(painter, "实际图片", contentScale = ContentScale.Fit,
                    modifier = Modifier.requiredSize(with(density) { 300.toDp() }, with(density) { 218.toDp() }).testTag("bounded_painter"))
            }
            compose.waitUntil(10_000) { decodedResult.get() != null }
            val decoded = decodedResult.get()!!
            assertTrue("真实painter宽${decoded.drawable.intrinsicWidth}超300", decoded.drawable.intrinsicWidth <= 300)
            assertTrue("真实painter高${decoded.drawable.intrinsicHeight}超218", decoded.drawable.intrinsicHeight <= 218)
            val pixels = compose.onNodeWithTag("bounded_painter").captureToImage().toPixelMap()
            assertTrue(pixels[pixels.width / 2, pixels.height / 2].red > .8f)
            android.util.Log.i("MR_IMAGE_TEST", "painter_source=1100x800 viewport=300x218 actual_decode=${decoded.drawable.intrinsicWidth}x${decoded.drawable.intrinsicHeight}")
        } finally { loader.shutdown(); server.shutdown() }
    }

    @Test fun exactViewportAndTinyRequestBoundLargeJpegDecode(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val server = MockWebServer()
        val bitmap = Bitmap.createBitmap(2560, 1920, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, this) }.toByteArray()
        bitmap.recycle()
        server.start()
        val loader = ImageLoader.Builder(context).build()
        try {
            val url = server.url("/api/v1/media/big/original?v=big1").toString()
            val tinyRequest = originalImageRequest(context, url, "large", 1, 1, prefetch = true)
            assertEquals(coil.size.Precision.EXACT, tinyRequest.precision)
            server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes)))
            val tiny = loader.execute(tinyRequest) as SuccessResult
            assertTrue(tiny.drawable.intrinsicWidth <= 2 && tiny.drawable.intrinsicHeight <= 2)
            val request = originalImageRequest(context, url, "large", 9000, 9000).newBuilder().allowHardware(false).build()
            assertEquals(coil.size.Precision.EXACT, request.precision)
            val decoded = loader.execute(request) as SuccessResult
            assertEquals(DataSource.DISK, decoded.dataSource)
            assertTrue(decoded.drawable.intrinsicWidth <= 2048 && decoded.drawable.intrinsicHeight <= 2048)
            assertEquals(1, server.requestCount)
            android.util.Log.i("MR_IMAGE_TEST", "large_source=2560x1920 current_decode=${decoded.drawable.intrinsicWidth}x${decoded.drawable.intrinsicHeight} source_prefetch=${tiny.drawable.intrinsicWidth}x${tiny.drawable.intrinsicHeight}")
        } finally { loader.shutdown(); server.shutdown() }
    }

    @Test fun expiredAndMustRevalidateSourcesCannotUseTheThirtySecondWindow(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val server = MockWebServer()
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, this) }.toByteArray()
        bitmap.recycle()
        fun response(control: String, ageSeconds: Long) = MockResponse().setHeader("Content-Type", "image/jpeg")
            .setHeader("Cache-Control", control).setHeader("Date", java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("GMT")
            }.format(java.util.Date(System.currentTimeMillis() - ageSeconds * 1000)))
            .setBody(Buffer().write(bytes))
        server.start()
        val loader = ImageLoader.Builder(context).build()
        try {
            for ((id, control, age) in listOf(Triple("expired", "max-age=0", 60L), Triple("revalidate", "max-age=0, must-revalidate", 5L))) {
                val url = server.url("/api/v1/media/$id/original?v=fixture1").toString()
                server.enqueue(response(control, age))
                assertEquals(DataSource.NETWORK, (loader.execute(originalImageRequest(context, url, "freshness", 1, 1, true)) as SuccessResult).dataSource)
                server.enqueue(response("max-age=0", 0))
                assertEquals(DataSource.NETWORK, (loader.execute(originalImageRequest(context, url, "freshness", 300, 225)) as SuccessResult).dataSource)
            }
            assertEquals(4, server.requestCount)
        } finally { loader.shutdown(); server.shutdown() }
    }

    @Test fun noTtlOriginalPrefetchReusesSourceButNoStoreAndNewGenerationDoNot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val server = MockWebServer()
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, this) }.toByteArray()
        bitmap.recycle()
        fun response(cacheControl: String? = null) = MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes)).apply {
            if (cacheControl != null) setHeader("Cache-Control", cacheControl)
        }
        server.start()
        val loader = ImageLoader.Builder(context).build()
        try {
            val url = server.url("/api/v1/media/p1/original?v=fixture1").toString()
            server.enqueue(response())
            val tiny = loader.execute(originalImageRequest(context, url, "source1", 1, 1, prefetch = true)) as SuccessResult
            assertEquals(DataSource.NETWORK, tiny.dataSource)
            assertTrue(tiny.drawable.intrinsicWidth <= 2)
            val viewport = loader.execute(originalImageRequest(context, url, "source1", 300, 225)) as SuccessResult
            assertEquals(DataSource.DISK, viewport.dataSource)
            assertTrue(viewport.drawable.intrinsicWidth in 250..600)
            assertEquals(1, server.requestCount)
            server.enqueue(response())
            val newSource = loader.execute(originalImageRequest(context, url, "source2", 300, 225)) as SuccessResult
            assertEquals(DataSource.NETWORK, newSource.dataSource)
            assertEquals(2, server.requestCount)
            val noStoreUrl = server.url("/api/v1/media/p2/original?v=fixture1").toString()
            server.enqueue(response("no-store")); server.enqueue(response("no-store")); server.enqueue(response("no-store"))
            assertTrue(loader.execute(originalImageRequest(context, noStoreUrl, "source1", 1, 1, true)) is SuccessResult)
            val fresh = loader.execute(originalImageRequest(context, noStoreUrl, "source1", 300, 225)) as SuccessResult
            assertEquals(DataSource.NETWORK, fresh.dataSource)
            assertEquals(4, server.requestCount)
            val revisit = loader.execute(originalImageRequest(context, noStoreUrl, "source1", 300, 225)) as SuccessResult
            assertEquals(DataSource.NETWORK, revisit.dataSource)
            assertEquals(5, server.requestCount)
            assertNull(loader.diskCache?.openSnapshot("source1|$noStoreUrl"))
            val noCacheUrl = server.url("/api/v1/media/p3/original?v=fixture1").toString()
            server.enqueue(response("no-cache")); server.enqueue(response("no-cache"))
            assertEquals(DataSource.NETWORK, (loader.execute(originalImageRequest(context, noCacheUrl, "source1", 300, 225)) as SuccessResult).dataSource)
            assertEquals(DataSource.NETWORK, (loader.execute(originalImageRequest(context, noCacheUrl, "source1", 300, 225)) as SuccessResult).dataSource)
            assertEquals(7, server.requestCount)
        } finally { loader.shutdown(); server.shutdown() }
    }
}
