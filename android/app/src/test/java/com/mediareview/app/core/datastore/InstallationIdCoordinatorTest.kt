package com.mediareview.app.core.datastore

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallationIdCoordinatorTest {
    @Test
    fun `两个并发首次调用返回同一已持久化 installation id`() = runBlocking {
        var persisted = ""
        var generated = 0
        val coordinator = InstallationIdCoordinator(
            read = { delay(10); persisted },
            write = { delay(10); persisted = it },
            generator = { generated += 1; "install-$generated" },
        )

        val first = async { coordinator.getOrCreate() }
        val second = async { coordinator.getOrCreate() }

        assertEquals(first.await(), second.await())
        assertEquals(first.await(), persisted)
        assertTrue(persisted.isNotBlank())
        assertEquals(1, generated)
    }
}
