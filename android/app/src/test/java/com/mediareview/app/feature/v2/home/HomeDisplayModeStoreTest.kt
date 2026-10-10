package com.mediareview.app.feature.v2.home

import com.mediareview.app.feature.v2.data.HomeDisplayModeStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeDisplayModeStoreTest {
    @Test fun lateStartupReadDoesNotOverwriteLatestUserChoice() = runTest {
        val persistedRead = CompletableDeferred<String?>()
        var persisted: String? = null
        val store = HomeDisplayModeStore(read = { persistedRead.await() }, write = { persisted = it })
        launch { store.bootstrap() }
        runCurrent()
        store.set(HomeDisplayMode.ONE_COLUMN)
        assertEquals(HomeDisplayMode.ONE_COLUMN.name, persisted)
        assertEquals(HomeDisplayMode.ONE_COLUMN, store.mode.value)
        persistedRead.complete(HomeDisplayMode.TWO_COLUMN.name)
        runCurrent()
        assertEquals(HomeDisplayMode.ONE_COLUMN, store.mode.value)
    }
}
