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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerProgressIdentityTest {
    @Test fun oldLoadedSourceProgressNeverWritesIntoNewLoadingSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val reports = mutableListOf<Triple<String, Long, Boolean>>()
            val repository = Proxy.newProxyInstance(MediaRepository::class.java.classLoader,
                arrayOf(MediaRepository::class.java)) { _, method, args ->
                when (method.name) {
                    "getMode" -> V2DataMode.SERVER
                    "resolvePlayback" -> (args!![0] as String).let {
                        V2PlaybackSource(it, it, V2PlaybackEndpoint("https://fixture.invalid/$it.mp4"))
                    }
                    "reportProgress" -> {
                        reports += Triple(args!![0] as String, args[1] as Long, args[2] as Boolean)
                        Unit
                    }
                    else -> error("Unexpected call: ${method.name}")
                }
            } as MediaRepository
            val vm = V2NativePlayerViewModel(repository)
            vm.open(PlaybackContext(listOf(PlaybackQueueItem("first", "first"), PlaybackQueueItem("next", "next"))))
            runCurrent()
            vm.moveTo(1)
            assertTrue(vm.state.value.ui is V2PlaybackUiState.Loading)
            vm.reportLoadedProgress("first", 24_000L, true)
            runCurrent()
            assertEquals(listOf(Triple("first", 24_000L, true)), reports)
            vm.reportLoadedProgress("unknown", 30_000L, true)
            runCurrent()
            assertEquals("未知source不得写进当前条目", 1, reports.size)
        } finally { Dispatchers.resetMain() }
    }
}
