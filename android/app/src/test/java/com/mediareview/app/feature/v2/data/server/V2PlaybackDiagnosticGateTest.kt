package com.mediareview.app.feature.v2.data.server

import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.MediaUrlResolver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class V2PlaybackDiagnosticGateTest {
    @Test fun incompatibleHealthPreventsReadinessAndPlayback() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("""{"success":true,"data":{"version":"old","api_contract":1}}"""))
            val base = server.url("/").toString()
            val http = OkHttpClient()
            val status = V2ServerStatusStore()
            val diagnostic = V2PlaybackDiagnostic(
                session = { V2ServerSession(configured = true, baseUrl = base, paired = true) },
                currentBase = { base },
                apiFactory = ApiFactory(http, http, Json { ignoreUnknownKeys = true }),
                statusStore = status,
                resolver = V2PlaybackResolver(MediaUrlResolver()),
            )
            assertEquals(V2DiagnosticCode.INCOMPATIBLE, diagnostic.run().phone.code)
            assertEquals(V2ServerStatus.Incompatible, status.status.value)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun noVideoIsDistinctFromPhoneNetworkFailure() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("""{"success":true,"data":{"version":"test","api_contract":2}}"""))
            server.enqueue(MockResponse().setBody("""{"success":true,"data":{"jellyfin_server_reachable":true,"media_valid":false,"media_id":null}}"""))
            val base = server.url("/").toString()
            val http = OkHttpClient()
            val diagnostic = V2PlaybackDiagnostic(
                session = { V2ServerSession(configured = true, baseUrl = base, paired = true) },
                currentBase = { base },
                apiFactory = ApiFactory(http, http, Json { ignoreUnknownKeys = true }),
                statusStore = V2ServerStatusStore(),
                resolver = V2PlaybackResolver(MediaUrlResolver()),
            )
            val result = diagnostic.run()
            assertEquals(V2DiagnosticCode.NO_MEDIA, result.phone.code)
            assertTrue(result.serverReachable == true)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun readinessFailuresNeverClaimNoMediaOrPhoneNetworkFailure() = runBlocking {
        for ((fields, expected) in listOf(
            "\"jellyfin_server_reachable\":false" to V2DiagnosticCode.SERVER_UNREACHABLE,
            "\"jellyfin_server_reachable\":true,\"direct_stream\":{\"status\":\"auth_failed\"}" to V2DiagnosticCode.UNAUTHORIZED,
            "\"jellyfin_server_reachable\":true,\"direct_stream\":{\"status\":\"media_unavailable\"}" to V2DiagnosticCode.MEDIA_UNAVAILABLE,
            "\"jellyfin_server_reachable\":true,\"direct_stream\":{\"status\":\"not_found\"}" to V2DiagnosticCode.NOT_FOUND,
            "\"jellyfin_server_reachable\":true,\"direct_stream\":{\"status\":\"unreachable\"}" to V2DiagnosticCode.NETWORK,
            "\"jellyfin_server_reachable\":true,\"direct_stream\":{\"status\":\"server_error\"}" to V2DiagnosticCode.UPSTREAM_ERROR,
            "\"jellyfin_server_reachable\":true,\"client_address_status\":\"invalid\"" to V2DiagnosticCode.INVALID_PLAYBACK,
            // 服务器流检查的分类必须与手机直连探针一致：302 重定向、403 权限不足各自独立，
            // 绝不归为无媒体（NO_MEDIA）或 401（UNAUTHORIZED）。
            "\"jellyfin_server_reachable\":true,\"direct_stream\":{\"status\":\"redirect\",\"http_status\":302}" to V2DiagnosticCode.REDIRECT,
            "\"jellyfin_server_reachable\":true,\"direct_stream\":{\"status\":\"forbidden\",\"http_status\":403}" to V2DiagnosticCode.FORBIDDEN,
        )) {
            val server = MockWebServer()
            try {
                server.enqueue(MockResponse().setBody("""{"success":true,"data":{"version":"test","api_contract":2}}"""))
                server.enqueue(MockResponse().setBody("""{"success":true,"data":{$fields,"client_stream_base":"http://jellyfin.test:8096","media_valid":false,"media_id":null}}"""))
                val base = server.url("/").toString()
                val http = OkHttpClient()
                val diagnostic = V2PlaybackDiagnostic(
                    session = { V2ServerSession(true, base, true) }, currentBase = { base },
                    apiFactory = ApiFactory(http, http, Json { ignoreUnknownKeys = true }),
                    statusStore = V2ServerStatusStore(), resolver = V2PlaybackResolver(MediaUrlResolver()),
                )
                val result = diagnostic.run()
                assertEquals(expected, result.phone.code)
                assertEquals(V2DiagnosticStage.READINESS, result.stage)
                assertEquals("jellyfin.test:8096", result.destination)
                assertEquals(2, server.requestCount)
            } finally { server.shutdown() }
        }
    }
}
