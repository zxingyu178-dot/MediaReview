package com.mediareview.app.feature.v2.nsfw

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import coil.EventListener
import coil.ImageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.mediareview.app.feature.v2.nsfw.data.NsfwFeedItem
import com.mediareview.app.feature.v2.nsfw.di.NsfwImageLoader
import com.mediareview.app.feature.v2.nsfw.network.NsfwTokenProvider
import com.mediareview.app.ui.theme.MediaReviewTheme
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** An isolated 30-image run with a repeatable bandwidth limit and synthetic PNG payloads. */
class NsfwReviewTransferTest {
    @get:Rule val compose = createComposeRule()

    @Test fun thirtyConsecutiveReviewsRecordActualPreviewTransfer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer()
        val requests = ConcurrentHashMap<String, Int>()
        val dataSources = ConcurrentHashMap<String, Int>()
        val imageErrors = ConcurrentHashMap<String, Int>()
        val payloadBytes = AtomicLong()
        val random = Random(42)
        val previewBody = ByteArrayOutputStream().use { bytes ->
            val bitmap = Bitmap.createBitmap(96, 128, Bitmap.Config.ARGB_8888)
            try {
                bitmap.setPixels(IntArray(96 * 128) { random.nextInt() or 0xff000000.toInt() },
                    0, 96, 0, 0, 96, 128)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
                bytes.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                requests.merge(path, 1, Int::plus)
                if (!path.endsWith("/preview")) return MockResponse().setResponseCode(404)
                return MockResponse().setHeader("Content-Type", "image/png")
                    .setHeader("Cache-Control", "private, max-age=120")
                    .setBody(Buffer().write(previewBody))
                    .throttleBody(4096, 40, TimeUnit.MILLISECONDS)
            }
        }
        val http = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body ?: return@addNetworkInterceptor response
            val counted = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long =
                    super.read(sink, byteCount).also { if (it > 0) payloadBytes.addAndGet(it) }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = counted
            }).build()
        }.build()
        val loader = ImageLoader.Builder(context).okHttpClient { http }
            .eventListener(object : EventListener {
                override fun onError(request: ImageRequest, result: ErrorResult) {
                    if (request.data.toString().endsWith("/preview")) {
                        val error = result.throwable
                        imageErrors.merge(error.javaClass.simpleName, 1, Int::plus)
                    }
                }

                override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                    if (request.data.toString().endsWith("/preview")) {
                        dataSources.merge(result.dataSource.name, 1, Int::plus)
                    }
                }
            }).build()
        server.start()
        try {
            val base = server.url("/").toString().removeSuffix("/")
            val tokenProvider = NsfwTokenProvider().apply { set("generated-fixture-only", base) }
            val items = (1..30).map {
                NsfwFeedItem(imageId = "transfer-$it", revision = "1", status = "NEW",
                    updatedAt = "2026-11-20T00:00:00Z", width = 96, height = 128,
                    previewUrl = "/mobile/images/transfer-$it/preview",
                    originalUrl = "/mobile/images/transfer-$it/original")
            }
            val imageLoader = NsfwImageLoader(loader, context, tokenProvider)
            val state = mutableStateOf(NsfwUiState(phase = NsfwPhase.READY, baseUrl = base,
                items = items, currentId = items.first().imageId, bidirectionalGallery = true))
            var reviewed = 0
            val readyTimes = mutableListOf<Long>()
            compose.setContent {
                MediaReviewTheme {
                    NsfwImageReviewPane(state.value, imageLoader, {}, {}, {}, {}, {}, { action, _ ->
                        if (action == "KEEP") {
                            reviewed++
                            items.getOrNull(reviewed)?.let { state.value = state.value.copy(currentId = it.imageId) }
                        }
                    }, {}, {}, {}, {}, {})
                }
            }
            val keep = compose.onNodeWithText("保留")
            repeat(items.size) {
                val started = SystemClock.elapsedRealtime()
                try {
                    compose.waitUntil(20_000) { runCatching { keep.assertIsEnabled() }.isSuccess }
                } catch (failure: Throwable) {
                    Log.e("MRTransferAudit", "reviewed=$reviewed requests=$requests successes=$dataSources " +
                        "imageErrors=$imageErrors payloadBytes=${payloadBytes.get()}")
                    runCatching { Log.e("MRTransferAudit", compose.onRoot().printToString()) }
                    throw failure
                }
                readyTimes += SystemClock.elapsedRealtime() - started
                keep.performClick()
            }
            compose.runOnIdle { assertEquals(items.size, reviewed) }
            assertTrue("No original payload should be requested at fit size", requests.keys.none { it.endsWith("/original") })
            assertEquals(items.size, requests.keys.count { it.endsWith("/preview") })
            assertTrue(payloadBytes.get() >= items.size.toLong() * previewBody.size)
            val previewRequests = requests.filterKeys { it.endsWith("/preview") }.values
            val sorted = readyTimes.sorted()
            Log.i("MRTransferAudit", "reviewed=$reviewed previewRequests=${previewRequests.sum()} " +
                "duplicateRequests=${previewRequests.sum() - items.size} payloadBytes=${payloadBytes.get()} " +
                "decodeResults=$dataSources p50ReadyMs=${sorted[14]} p95ReadyMs=${sorted[28]}")
        } finally {
            loader.shutdown()
            server.shutdown()
        }
    }
}
