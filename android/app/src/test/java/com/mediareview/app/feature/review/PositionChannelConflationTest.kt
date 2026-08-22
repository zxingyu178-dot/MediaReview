package com.mediareview.app.feature.review

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 16 预部署收口:Review position 用 Channel.CONFLATED 串行上报。
 * 验证快速连续滑动时 CONFLATED 通道只保留最新位置,旧 position 不会晚到覆盖新位置。
 */
class PositionChannelConflationTest {

    @Test
    fun `快速连续发送时消费者只收到最新位置`() = runBlocking {
        val channel = Channel<Pair<String, Int>>(Channel.CONFLATED)
        // 快速滑动:连续发出 1..100 个位置(消费端尚未运行)
        repeat(100) { channel.trySend("session" to it) }
        // 单消费者启动后读到的应是最后一个(99),而非中间值
        val received = mutableListOf<Pair<String, Int>>()
        while (true) {
            val item = channel.tryReceive().getOrNull() ?: break
            received += item
        }
        assertTrue(received.isNotEmpty())
        assertEquals(99, received.last().second)
        // 中间值被 CONFLATED 合并,不会逐个到达
        assertTrue(received.size < 100)
    }

    @Test
    fun `串行消费保证按顺序写服务器`() = runBlocking {
        val channel = Channel<Pair<String, Int>>(Channel.CONFLATED)
        val written = mutableListOf<Int>()
        // 模拟消费者单协程串行写(每次消费前模拟网络延迟,期间又来了新位置)
        val consumer = launch {
            for ((_, idx) in channel) {
                written += idx
                delay(5)
            }
        }
        for (i in 0 until 20) {
            channel.trySend("s" to i)
            delay(1)
        }
        delay(100)
        consumer.cancel()
        // 写入序列单调不倒退(服务器按正确顺序收到最新位置)
        assertEquals(written.sorted(), written)
        assertEquals(19, written.last())
    }
}
