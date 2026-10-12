package com.mediareview.app.feature.v2.player

import com.mediareview.app.feature.v2.data.MediaRepository
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.model.V2PlaybackEndpoint
import com.mediareview.app.feature.v2.model.V2PlaybackSource
import com.mediareview.app.feature.v2.player.native.state.PlaybackContext
import com.mediareview.app.feature.v2.player.native.state.PlaybackQueueItem
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerPrefetchTimingTest {
    @Test fun endpointReadyDoesNotPrefetchBeforePlaybackStabilizes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val requests = mutableListOf<String>()
            // 本测试只允许播放源解析；其他业务方法被调用即失败。
            val repository = Proxy.newProxyInstance(MediaRepository::class.java.classLoader,
                arrayOf(MediaRepository::class.java)) { _, method, args ->
                when (method.name) {
                    "getMode" -> V2DataMode.SERVER
                    "resolvePlayback" -> {
                        val id = args!![0] as String
                        requests += id
                        V2PlaybackSource(id, id, V2PlaybackEndpoint("https://fixture.invalid/$id.mp4"))
                    }
                    else -> error("Unexpected repository call: ${method.name}")
                }
            } as MediaRepository
            val vm = V2NativePlayerViewModel(repository)
            vm.open(PlaybackContext(listOf(PlaybackQueueItem("first", "first"), PlaybackQueueItem("next", "next"))))
            runCurrent()
            assertTrue(vm.state.value.ui is V2PlaybackUiState.Ready)
            assertEquals("必须由稳定播放事件触发下一条endpoint预解析", listOf("first"), requests)
            vm.onPlaybackStable("stale")
            runCurrent()
            assertEquals(listOf("first"), requests)
            vm.onPlaybackStable("first")
            vm.onPlaybackStable("first")
            runCurrent()
            assertEquals(listOf("first", "next"), requests)
            vm.moveTo(1)
            runCurrent()
            assertEquals("切换时复用唯一下一条预解析槽", listOf("first", "next"), requests)
        } finally { Dispatchers.resetMain() }
    }
}
