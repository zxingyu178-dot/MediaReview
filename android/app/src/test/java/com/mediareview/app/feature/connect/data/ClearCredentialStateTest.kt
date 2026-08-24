package com.mediareview.app.feature.connect.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ClearCredentialStateTest {
    @Test
    fun `清除连接同时删除持久化和内存凭据`() = runBlocking {
        val events = mutableListOf<String>()

        clearCredentialState(
            clearPersistent = { events += "persistent" },
            clearMemory = { events += "memory" },
        )

        assertEquals(listOf("persistent", "memory"), events)
    }
}
