package com.mediareview.app.feature.v2.data.server

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class V2PlaybackDiagnosticTest {
    @Test fun rangeUsesDeviceHeaderAndReadsAtMost1024Bytes() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp4").setHeader("Content-Range", "bytes 0-1023/4096").setBody("x".repeat(1024)))
            val result = V2DirectStreamProbe().probe(server.url("/video").toString(), mapOf("X-Emby-Token" to "fixture-device-key", "Authorization" to "Bearer fixture-mediareview-key"))
            assertEquals(V2DiagnosticCode.READABLE, result.code)
            assertEquals(1024, result.bytesRead)
            val request = server.takeRequest()
            assertEquals("bytes=0-1023", request.getHeader("Range"))
            assertEquals("fixture-device-key", request.getHeader("X-Emby-Token"))
            assertNull(request.getHeader("Authorization"))
            assertFalse(result.toString().contains("fixture-device-key"))
        } finally { server.shutdown() }
    }
    @Test fun classifiesHttpAndRejectsRedirectWithoutFollowing() = runBlocking {
        val server = MockWebServer()
        try {
            val probe = V2DirectStreamProbe()
            for ((status, expected) in listOf(401 to V2DiagnosticCode.UNAUTHORIZED, 403 to V2DiagnosticCode.FORBIDDEN, 404 to V2DiagnosticCode.NOT_FOUND, 503 to V2DiagnosticCode.UPSTREAM_ERROR, 302 to V2DiagnosticCode.REDIRECT)) {
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Location", server.url("/secret")).setBody("private-token"))
                val result = probe.probe(server.url("/video").toString(), emptyMap())
                assertEquals(expected, result.code)
                assertFalse(result.toString().contains("private-token"))
            }
            assertEquals(5, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun nonVideoResponseIsNotReadableVideo() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("login"))
            assertEquals(V2DiagnosticCode.NON_VIDEO, V2DirectStreamProbe().probe(server.url("/video").toString(), emptyMap()).code)
        } finally { server.shutdown() }
    }
    @Test fun networkAndInvalidAddressFailuresNeverExposeCredentials() = runBlocking {
        val server = MockWebServer()
        server.start()
        val url = server.url("/video").toString()
        server.shutdown()
        val probe = V2DirectStreamProbe()
        val network = probe.probe(url, mapOf("X-Emby-Token" to "fixture-secret-key"))
        assertEquals(V2DiagnosticCode.NETWORK, network.code)
        assertFalse(network.toString().contains("fixture-secret-key"))
        val invalid = probe.probe("http://[fixture-secret-key", emptyMap())
        assertEquals(V2DiagnosticCode.INVALID_PLAYBACK, invalid.code)
        assertFalse(invalid.toString().contains("fixture-secret-key"))
    }
    @Test fun rejectsMissingOrInvalidRangeAndLengthMetadata() = runBlocking {
        val server = MockWebServer()
        try {
            val probe = V2DirectStreamProbe()
            for (response in listOf(
                MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp4").setBody("video"),
                MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp4").setHeader("Content-Range", "bytes 1-5/100").setBody("video"),
                MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp4").setHeader("Content-Range", "bytes 0-1024/4096").setBody("x".repeat(1025)),
                MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp4").setHeader("Content-Range", "bytes 0-5/5").setBody("x".repeat(6)),
                MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp4").setHeader("Content-Range", "bytes 0-4/100").setBody("four"),
                MockResponse().setResponseCode(200).setHeader("Content-Type", "video/mp4").setChunkedBody("video", 2),
            )) {
                server.enqueue(response)
                assertEquals(V2DiagnosticCode.INVALID_PLAYBACK, probe.probe(server.url("/video").toString(), emptyMap()).code)
            }
        } finally { server.shutdown() }
    }
}
