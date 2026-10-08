package com.mediareview.app.feature.v2

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.feature.v2.home.MediaCard
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2MediaType
import com.mediareview.app.feature.v2.model.V2SpriteManifest
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.mediareview.app.ui.theme.MediaReviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.home.SpritePreviewContent
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue

/** 首次长按没有本地 sprite 也能请求；失败后普通点击保持可用。 */
@RunWith(AndroidJUnit4::class)
class Stage8FSpriteCardTest {
    @get:Rule val compose = createAndroidComposeRule<HiltTestActivity>()
    private fun media(type: V2MediaType) = V2Media("fixture", "001", "预览测试", "f", "测试",
        type, 10000, 1, 0, false, false, "", "", null, null, 160, 90)

    @Test fun firstLongPressRequestsSpriteAndOrdinaryClickRemainsAvailable() {
        var requested = 0
        var opened = 0
        compose.setContent {
            MediaReviewTheme {
                MediaCard(media(V2MediaType.VIDEO), "file:///android_asset/demo_media/images/01.jpg",
                    null, null, false, { requested++ }, onClick = { opened++ })
            }
        }
        compose.onNodeWithContentDescription("预览测试").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, requested); assertEquals(0, opened) }
        compose.onNodeWithContentDescription("预览测试").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }
    @Test fun imageLongPressDoesNotRequestSprite() {
        var requested = 0
        compose.setContent {
            MediaReviewTheme {
                MediaCard(media(V2MediaType.IMAGE), "", null, null, false,
                    { requested++ }, onClick = {})
            }
        }
        compose.onNodeWithContentDescription("预览测试").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(0, requested) }
    }

    @Test fun missingSpriteFileShowsErrorAndKeepsOrdinaryClick() {
        var opened = 0
        var failures = 0
        compose.setContent {
            val failed = remember { mutableStateOf(false) }
            MediaReviewTheme {
                MediaCard(media(V2MediaType.VIDEO), "", "file:///android_asset/stage8f_missing_sprite.webp",
                    V2SpriteManifest("fixture", 1, 1, 160, 90, 1.0, 10.0, 1), !failed.value,
                    {}, onClick = { opened++ }, previewError = if (failed.value) "预览生成失败" else null,
                    onSpritePreviewError = { failures++; failed.value = true })
            }
        }
        compose.waitUntil(timeoutMillis = 5000) { failures > 0 }
        compose.onNodeWithText("预览生成失败").assertIsDisplayed()
        compose.onNodeWithContentDescription("预览测试").performClick()
        compose.runOnIdle { assertEquals(1, failures); assertEquals(1, opened) }
    }

    @Test fun slowSpriteDownloadStartsPlaybackAndTimeoutOnlyAfterBitmapReady() {
        val server = delayedSpriteServer()
        try {
            val spriteUrl = server.url("/slow.webp").toString()
            var stopped = 0
            var failed = 0
            compose.setContent {
                SpritePreviewContent(spriteUrl, testManifest(), 10000,
                    { stopped++ }, Modifier.size(160.dp, 90.dp).testTag("sprite"),
                    onLoadError = { failed++ })
            }
            compose.waitUntil(5000) { server.requestCount == 1 }
            val requestedAt = SystemClock.uptimeMillis()
            compose.waitUntil(9000) { SystemClock.uptimeMillis() - requestedAt >= 7000 }
            compose.runOnIdle { assertEquals("Loading must not consume playback budget", 0, stopped) }
            compose.waitUntil(5000) { sheetColor() != null }
            val readyAt = SystemClock.uptimeMillis()
            val firstFrame = requireNotNull(sheetColor())
            compose.waitUntil(1500) { sheetColor()?.let { it != firstFrame } == true }
            compose.waitUntil(7000) { SystemClock.uptimeMillis() - readyAt >= 6000 }
            compose.runOnIdle { assertEquals("Bitmap must receive its playback budget", 0, stopped) }
            compose.waitUntil(1500) { stopped == 1 }
            compose.runOnIdle { assertEquals(0, failed); assertEquals(1, stopped) }
        } finally { server.shutdown() }
    }

    @Test fun userCanCancelDuringSlowSpriteDownload() {
        val server = delayedSpriteServer()
        try {
            val spriteUrl = server.url("/cancel.webp").toString()
            var stopped = 0
            var failed = 0
            val active = mutableStateOf(true)
            compose.setContent {
                Box {
                    if (active.value) SpritePreviewContent(spriteUrl,
                        testManifest(), 10000, { stopped++ },
                        Modifier.size(160.dp, 90.dp).testTag("sprite"), onLoadError = { failed++ })
                    Button(onClick = { active.value = false }) { Text("取消预览") }
                }
            }
            compose.waitUntil(5000) { server.requestCount == 1 }
            compose.onNodeWithText("取消预览").performClick()
            compose.onNodeWithTag("sprite").assertDoesNotExist()
            val cancelledAt = SystemClock.uptimeMillis()
            compose.waitUntil(10000) { SystemClock.uptimeMillis() - cancelledAt >= 8500 }
            compose.runOnIdle { assertEquals(0, stopped); assertEquals(0, failed); assertTrue(!active.value) }
        } finally { server.shutdown() }
    }

    private fun testManifest() = V2SpriteManifest("fixture", 2, 1, 160, 90, 5.0, 10.0, 2)

    private fun delayedSpriteServer(): MockWebServer {
        val bitmap = Bitmap.createBitmap(320, 90, Bitmap.Config.ARGB_8888)
        for (x in 0 until 320) for (y in 0 until 90)
            bitmap.setPixel(x, y, if (x < 160) AndroidColor.RED else AndroidColor.BLUE)
        val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        bitmap.recycle()
        return MockWebServer().apply {
            enqueue(MockResponse().setHeader("Content-Type", "image/png")
                .setBody(Buffer().write(bytes)).setBodyDelay(8, TimeUnit.SECONDS))
            start()
        }
    }

    // Verify actual decoded/cropped sheet pixels rather than an internal timer counter.
    private fun sheetColor(): Color? {
        val pixels = compose.onNodeWithTag("sprite").captureToImage().toPixelMap()
        val color = pixels[pixels.width / 2, pixels.height / 3]
        return color.takeIf { it == Color.Red || it == Color.Blue }
    }
}
