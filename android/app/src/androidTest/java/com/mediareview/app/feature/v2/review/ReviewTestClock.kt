package com.mediareview.app.feature.v2.review

import android.os.SystemClock
import androidx.compose.ui.test.MainTestClock
import org.junit.Assert

/** Give Compose only elapsed real time; native decoding always uses its own real clock. */
internal class ReviewTestClock(private val composeClock: MainTestClock) {
    private val wallEpoch = SystemClock.elapsedRealtime()
    private val composeEpoch = composeClock.currentTime
    init { composeClock.autoAdvance = false }
    fun sync() {
        val remaining = composeEpoch + SystemClock.elapsedRealtime() - wallEpoch - composeClock.currentTime
        if (remaining > 0) composeClock.advanceTimeBy(remaining, ignoreFrameDuration = true)
    }
    fun until(timeoutMs: Long, condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            sync()
            if (condition()) return
            Assert.assertTrue("真实等待超时 ${timeoutMs}ms", SystemClock.elapsedRealtime() - start < timeoutMs)
            SystemClock.sleep(10)
        }
    }
}
