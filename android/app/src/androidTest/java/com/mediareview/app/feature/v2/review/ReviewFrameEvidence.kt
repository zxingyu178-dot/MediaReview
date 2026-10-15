package com.mediareview.app.feature.v2.review

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** UiAutomation screenshots include native SurfaceView pixels; Compose capture does not. */
internal data class ReviewFrameEvidence(val pixels: IntArray, val red: Int, val green: Int, val blue: Int,
    val capturedAtMs: Long, val captureLatencyMs: Long) {
    val hasVideoColors get() = red > 10 && green > 10 && blue > 10
    fun changedFrom(other: ReviewFrameEvidence): Int = pixels.indices.count { index ->
        val a = pixels[index]; val b = other.pixels[index]
        kotlin.math.abs(Color.red(a) - Color.red(b)) + kotlin.math.abs(Color.green(a) - Color.green(b)) +
            kotlin.math.abs(Color.blue(a) - Color.blue(b)) > 60
    }
    companion object {
        fun capture(name: String): ReviewFrameEvidence {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val started = SystemClock.elapsedRealtime()
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            val capturedAt = SystemClock.elapsedRealtime()
            try {
                val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "task7-evidence").apply { mkdirs() }
                File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val samples = ArrayList<Int>()
                var red = 0; var green = 0; var blue = 0
                // Sample the video center, excluding controls and Snackbar. A green poster can
                // satisfy neither multi-color evidence nor temporal differences.
                for (y in bitmap.height * 42 / 100 until bitmap.height * 58 / 100 step 8) {
                    for (x in bitmap.width * 15 / 100 until bitmap.width * 70 / 100 step 8) {
                        val c = bitmap.getPixel(x, y); samples += c
                        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                        if (r > g + 40 && r > b + 40) red++
                        if (g > r + 40 && g > b + 40) green++
                        if (b > r + 40 && b > g + 40) blue++
                    }
                }
                return ReviewFrameEvidence(samples.toIntArray(), red, green, blue, capturedAt, capturedAt - started)
            } finally { bitmap.recycle() }
        }
    }
}
