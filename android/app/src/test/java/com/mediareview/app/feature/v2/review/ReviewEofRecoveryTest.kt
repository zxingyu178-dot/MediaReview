package com.mediareview.app.feature.v2.review

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewEofRecoveryTest {
    @Test fun `缓冲超过三秒后仍等待当前内核并恢复`() = runTest {
        var playing = false
        var position = 6000L
        val recovery = async {
            awaitOwnedReviewPlaybackAdvance(1500, { true }, { playing }, { position }, { 6000 })
        }
        runCurrent()
        advanceTimeBy(4000)
        runCurrent()
        assertFalse("慢缓冲不能提前放弃仍归属当前页的恢复", recovery.isCompleted)
        playing = true
        position = 1500
        advanceTimeBy(100); runCurrent()
        position = 1530
        advanceTimeBy(100); runCurrent()
        position = 1560
        advanceTimeBy(100); runCurrent()
        assertTrue(recovery.await())
    }

    @Test fun `等待中离开当前播放归属不会恢复旧内核`() = runTest {
        var owned = true
        val recovery = async {
            awaitOwnedReviewPlaybackAdvance(1500, { owned }, { false }, { 6000 }, { 6000 })
        }
        runCurrent()
        advanceTimeBy(400)
        owned = false
        advanceTimeBy(100); runCurrent()
        assertFalse(recovery.await())
    }

    @Test fun `seek跳到目标本身不能冒充持续播放`() = runTest {
        var position = 6000L
        val recovery = async {
            awaitOwnedReviewPlaybackAdvance(1500, { true }, { true }, { position }, { 6000 })
        }
        runCurrent()
        position = 1500
        advanceTimeBy(100); runCurrent()
        assertFalse(recovery.isCompleted)
        position = 1530
        advanceTimeBy(100); runCurrent()
        assertFalse(recovery.isCompleted)
        position = 1560
        advanceTimeBy(100); runCurrent()
        assertTrue(recovery.await())
    }
}
