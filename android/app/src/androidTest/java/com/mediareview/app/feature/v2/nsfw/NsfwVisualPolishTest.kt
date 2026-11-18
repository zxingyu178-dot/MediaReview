package com.mediareview.app.feature.v2.nsfw

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import com.mediareview.app.feature.v2.nsfw.data.NsfwFeedItem
import com.mediareview.app.feature.v2.nsfw.data.NsfwImageDetails
import com.mediareview.app.feature.v2.nsfw.di.NsfwImageLoader
import com.mediareview.app.feature.v2.nsfw.network.NsfwTokenProvider
import com.mediareview.app.ui.theme.MediaReviewTheme
import java.io.ByteArrayOutputStream
import java.io.File
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Only geometric synthetic media; never connects to the user's services or pairing store. */
class NsfwVisualPolishTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun capture(name: String) {
        val target = File(context.filesDir, "visual-polish/$name.png")
        target.parentFile!!.mkdirs()
        // ModalBottomSheet uses another window; capture all windows instead of its underlay.
        val bitmap = if (name == "gallery-filters") InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            else compose.onRoot().captureToImage().asAndroidBitmap()
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun syncKeepsToolbarAndCurrentImageInteractive() = withImages { base, loader, items ->
        val state = mutableStateOf(NsfwUiState(phase = NsfwPhase.READY, baseUrl = base, items = items, currentId = items[0].imageId, galleryEnabled = true, galleryTotal = items.size, galleryPositionValid = true))
        compose.setContent { MediaReviewTheme { Surface {
            NsfwGallery(state.value, loader, rememberLazyGridState(), {}, {}, {}, {}, { _, _ -> })
        } } }
        compose.onNodeWithContentDescription("筛选").assertIsEnabled()
        val bounds = compose.onNodeWithContentDescription("筛选").fetchSemanticsNode().boundsInRoot
        compose.waitUntil(15_000) { loader.loader.memoryCache?.keys?.isNotEmpty() == true }
        val filterBefore = compose.onNodeWithContentDescription("筛选").captureToImage().asAndroidBitmap()
        val moreBefore = compose.onNodeWithContentDescription("更多操作").captureToImage().asAndroidBitmap()
        capture("gallery-idle")
        compose.runOnIdle { state.value = state.value.copy(syncing = true) }
        compose.onNodeWithContentDescription("筛选").assertIsEnabled()
        compose.onNodeWithContentDescription("刷新图库").assertExists()
        assertEquals(bounds, compose.onNodeWithContentDescription("筛选").fetchSemanticsNode().boundsInRoot)
        assertTrue("Background sync must keep the filter icon pixels stable", filterBefore.sameAs(compose.onNodeWithContentDescription("筛选").captureToImage().asAndroidBitmap()))
        assertTrue("Background sync must keep the more icon pixels stable", moreBefore.sameAs(compose.onNodeWithContentDescription("更多操作").captureToImage().asAndroidBitmap()))
        capture("gallery-sync")
        compose.runOnIdle { state.value = state.value.copy(syncing = false) }
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithText("筛选生图").assertIsDisplayed()
        capture("gallery-filters")
    }

    @Test fun nextImageHasOneInteractiveViewerAndSameImageRefreshKeepsIt() = withImages { base, loader, items ->
        val state = mutableStateOf(NsfwUiState(phase = NsfwPhase.READY, baseUrl = base, items = items, currentId = items[0].imageId, detail = NsfwImageDetails(imageId = items[0].imageId)))
        compose.setContent { MediaReviewTheme { Surface {
            NsfwImageReviewPane(state.value, loader, {}, {}, {},
                { state.value = state.value.copy(currentId = items[1].imageId, detail = NsfwImageDetails(imageId = items[1].imageId)) }, {}, { _, _ -> }, {}, {}, {}, {}, {})
        } } }
        compose.onAllNodesWithContentDescription("图片").assertCountEquals(1)
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithText("保留").assertIsEnabled(); true }.getOrDefault(false) }
        capture("viewer-first")
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("图片").performTouchInput { swipeLeft(durationMillis = 350L) }
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithContentDescription("图片").assertCountEquals(1)
            compose.onNodeWithText("保留").assertIsNotEnabled()
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithText("保留").assertIsEnabled(); true }.getOrDefault(false) }
        compose.runOnIdle { state.value = state.value.copy(syncing = true) }
        compose.onAllNodesWithContentDescription("图片").assertCountEquals(1)
        capture("viewer-next")
    }

    @Test fun undoStaysInViewerAndActionBarFitsLargeText() = withImages { base, loader, items ->
        val first = items[0]
        val second = items[1]
        val state = mutableStateOf(NsfwUiState(phase = NsfwPhase.READY, baseUrl = base, items = items,
            currentId = second.imageId, detail = NsfwImageDetails(imageId = second.imageId),
            undoDecision = NsfwUndoDecision(first.imageId, "RESET", "", "2")))
        var returnToGallery = false
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                MediaReviewTheme { Surface {
                    Box(Modifier.width(320.dp)) {
                        NsfwImageReviewPane(state.value, loader, { returnToGallery = true }, {}, {}, {}, {},
                            { _, _ -> }, {}, { state.value = state.value.copy(busy = true) }, {}, {}, {})
                    }
                } }
            }
        }
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithText("保留").assertIsEnabled(); true }.getOrDefault(false) }
        val undo = compose.onNodeWithContentDescription("撤销上次审核")
        val keep = compose.onNodeWithText("保留")
        val reject = compose.onNodeWithText("淘汰")
        val keepBounds = keep.fetchSemanticsNode().boundsInRoot
        val rejectBounds = reject.fetchSemanticsNode().boundsInRoot
        val undoBounds = undo.fetchSemanticsNode().boundsInRoot
        assertTrue(keepBounds.right <= rejectBounds.left && rejectBounds.right <= undoBounds.left)
        capture("viewer-large-type-undo")
        undo.performClick()
        undo.assertIsNotEnabled()
        compose.onNodeWithTag("nsfw_image_stage").assertIsDisplayed()
        compose.onNodeWithContentDescription("图片").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(!returnToGallery)
            state.value = state.value.copy(currentId = first.imageId, busy = false,
                detail = NsfwImageDetails(imageId = first.imageId), undoDecision = null)
        }
        compose.waitUntil(15_000) { runCatching { keep.assertIsEnabled(); true }.getOrDefault(false) }
        undo.assertIsNotEnabled()
        compose.onNodeWithTag("nsfw_image_stage").assertIsDisplayed()
    }
    private fun withImages(block: (String, NsfwImageLoader, List<NsfwFeedItem>) -> Unit) {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(if (request.path.orEmpty().contains("sample1")) Color.rgb(32, 63, 91) else Color.rgb(90, 52, 42))
                val paint = Paint().apply { color = Color.rgb(153, 188, 190); isAntiAlias = true }
                canvas.drawCircle(160f, 180f, 90f, paint)
                paint.color = Color.rgb(223, 193, 136)
                canvas.drawRect(48f, 315f, 272f, 400f, paint)
                val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                bitmap.recycle()
                return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes))
            }
        }
        server.start()
        val base = server.url("/").toString().removeSuffix("/")
        val coil = ImageLoader.Builder(context).build()
        val tokens = NsfwTokenProvider().apply { set("synthetic-test-only", base) }
        val items = (1..12).map { id -> NsfwFeedItem(imageId = "sample$id", revision = "1", width = 320, height = 480,
            thumbnailUrl = "/mobile/images/sample$id/thumbnail", previewUrl = "/mobile/images/sample$id/preview", originalUrl = "/mobile/images/sample$id/original") }
        try { block(base, NsfwImageLoader(coil, context, tokens), items) }
        finally { coil.shutdown(); server.shutdown() }
    }
}
