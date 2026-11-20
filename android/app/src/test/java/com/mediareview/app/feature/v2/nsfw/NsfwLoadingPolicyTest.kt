package com.mediareview.app.feature.v2.nsfw

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class NsfwLoadingPolicyTest {
    @Test fun failedPollingBacksOffAndSuccessResets() {
        val policy = NsfwPollBackoff()
        assertEquals(15000L, policy.interval(15000))
        policy.result(false)
        assertEquals(30000L, policy.interval(15000))
        repeat(10) { policy.result(false) }
        assertEquals(60000L, policy.interval(15000))
        policy.result(true)
        assertEquals(15000L, policy.interval(15000))
    }
    @Test fun foregroundResetRestoresBaseInterval() {
        val policy = NsfwPollBackoff()
        repeat(3) { policy.result(false) }
        policy.reset()
        assertEquals(15000L, policy.interval(15000))
    }
    @Test fun movingWindowKeepsOverlappingWorkAndCancelsOnlyObsoleteRequests() = runTest {
        val started = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val prefetch = NsfwImagePrefetcher(this) { url ->
            started += url
            try { awaitCancellation() } finally { cancelled += url }
        }
        prefetch.update(listOf("a", "b", "c", "outside")); runCurrent()
        assertEquals(listOf("a", "b", "c"), started)
        prefetch.update(listOf("b", "c", "d"), visibleUrl = "a"); runCurrent()
        assertTrue(cancelled.isEmpty())
        assertEquals(listOf("a", "b", "c", "d"), started)
        prefetch.update(listOf("c", "d", "e"), visibleUrl = "b"); runCurrent()
        assertEquals(listOf("a"), cancelled)
        assertEquals(listOf("a", "b", "c", "d", "e"), started)
        prefetch.stop(); runCurrent()
        assertEquals(started, cancelled)
    }
    @Test fun cacheKeyIsScopedByCredentialAndOriginWithoutExposingToken() {
        val first = nsfwImageCacheKey("http://bridge.test:8877/mobile/images/a/preview", "fake-token-A")
        assertNotNull(first)
        assertFalse(first!!.contains("fake-token-A"))
        assertEquals(first, nsfwImageCacheKey("http://bridge.test:8877/mobile/images/a/preview", "fake-token-A"))
        assertNotEquals(first, nsfwImageCacheKey("http://bridge.test:8877/mobile/images/a/preview", "fake-token-B"))
        assertNotEquals(first, nsfwImageCacheKey("http://other.test:8877/mobile/images/a/preview", "fake-token-A"))
        assertNull(nsfwImageCacheKey("http://bridge.test:8877/mobile/images/a/preview", ""))
    }
}
