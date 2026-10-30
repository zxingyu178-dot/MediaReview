package com.mediareview.app.feature.v2.nsfw

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class NsfwLoadingPolicyTest {
    @Test fun idlePollingBacksOffAndActivityResets() {
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
    @Test fun prefetchHasOneTaskAndCancelsObsoleteWork() = runTest {
        val started = mutableListOf<String>()
        val ended = mutableListOf<String>()
        val prefetch = NsfwImagePrefetcher(this) { url ->
            started += url
            try { awaitCancellation() } finally { ended += url }
        }
        prefetch.update("next-a"); runCurrent()
        prefetch.update("next-a"); runCurrent()
        assertEquals(listOf("next-a"), started)
        prefetch.update("next-b"); runCurrent()
        assertEquals(listOf("next-a"), ended)
        assertEquals(listOf("next-a", "next-b"), started)
        prefetch.stop(); runCurrent()
        assertEquals(started, ended)
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
