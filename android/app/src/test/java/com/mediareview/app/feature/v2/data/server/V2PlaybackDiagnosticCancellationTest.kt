package com.mediareview.app.feature.v2.data.server

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class V2PlaybackDiagnosticCancellationTest {
    @Test fun cancellationIsPropagatedInsteadOfNetworkResult() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("video").setBodyDelay(2, TimeUnit.SECONDS).setHeader("Content-Type", "video/mp4"))
            val job = async { V2DirectStreamProbe().probe(server.url("/video").toString(), emptyMap()) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        } finally { server.shutdown() }
    }
}
