package com.mediareview.app.feature.v2.data.server

import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.MediaUrlResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class V2PlaybackDiagnosticPipelineTest {
    @Test fun productionApiAndResolverSendSameDirectHeadersWithoutBearer() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            val dns = object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1")) }
            val http = OkHttpClient.Builder().dns(dns).addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer fixture-mediareview-key").build())
            }.build()
            val public = OkHttpClient.Builder().dns(dns).build()
            val base = "http://mediareview.test:${server.port}"
            server.enqueue(MockResponse().setBody("""{"success":true,"data":{"version":"test","api_contract":2}}"""))
            server.enqueue(MockResponse().setBody("""{"success":true,"data":{"jellyfin_server_reachable":true,"media_valid":true,"media_id":"video-1","device_playback_key_status":"valid","client_stream_source":"configured_client_url","client_stream_authoritative":true,"direct_stream":{"status":"readable"}}}"""))
            server.enqueue(MockResponse().setBody("""{"success":true,"data":{"media_id":"video-1","media_type":"video","stream_url_authoritative":true,"direct":{"url":"http://jellyfin.test:${server.port}/video","headers":{"X-Emby-Token":"fixture-device-key"}}}}"""))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp4").setHeader("Content-Range", "bytes 0-1023/2048").setBody("x".repeat(1024)))
            val diagnostic = V2PlaybackDiagnostic(
                session = { V2ServerSession(true, base, true) }, currentBase = { base },
                apiFactory = ApiFactory(http, public, Json { ignoreUnknownKeys = true }),
                statusStore = V2ServerStatusStore(), resolver = V2PlaybackResolver(MediaUrlResolver()),
                probe = V2DirectStreamProbe(dns),
            )
            val result = diagnostic.run()
            assertEquals(V2DiagnosticStage.PHONE, result.stage)
            assertEquals(V2DiagnosticCode.READABLE, result.phone.code)
            assertEquals("jellyfin.test:${server.port}", result.destination)
            assertFalse(result.toString().contains("fixture-device-key"))
            assertFalse(result.toString().contains("fixture-mediareview-key"))
            val health = server.takeRequest()
            assertEquals("/api/v1/system/health", health.path)
            assertNull(health.getHeader("Authorization"))
            assertEquals("/api/v1/system/playback-readiness", server.takeRequest().path)
            assertEquals("/api/v1/media/video-1/playback", server.takeRequest().path)
            val range = server.takeRequest()
            assertEquals("/video", range.path)
            assertEquals("fixture-device-key", range.getHeader("X-Emby-Token"))
            assertNull(range.getHeader("Authorization"))
            assertEquals("bytes=0-1023", range.getHeader("Range"))
        } finally { server.shutdown() }
    }
    @Test fun serverSwitchCancelsOldReportBeforeUpdatingGate() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("""{"success":true,"data":{"version":"test","api_contract":2}}"""))
            val base = server.url("/").toString()
            val http = OkHttpClient()
            val status = V2ServerStatusStore()
            val diagnostic = V2PlaybackDiagnostic(
                session = { V2ServerSession(true, base, true) }, currentBase = { "http://new-server.test" },
                apiFactory = ApiFactory(http, http, Json { ignoreUnknownKeys = true }),
                statusStore = status, resolver = V2PlaybackResolver(MediaUrlResolver()),
            )
            try { diagnostic.run(); fail("必须取消旧服务器诊断") }
            catch (_: CancellationException) { }
            assertEquals(V2ServerStatus.Unconfigured, status.status.value)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun readinessHttpFailureIsComputerStageNotPhoneFailure() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("""{"success":true,"data":{"version":"test","api_contract":2}}"""))
            server.enqueue(MockResponse().setResponseCode(503).setBody("fixture-secret"))
            val base = server.url("/").toString()
            val http = OkHttpClient()
            val diagnostic = V2PlaybackDiagnostic(
                session = { V2ServerSession(true, base, true) }, currentBase = { base },
                apiFactory = ApiFactory(http, http, Json { ignoreUnknownKeys = true }),
                statusStore = V2ServerStatusStore(), resolver = V2PlaybackResolver(MediaUrlResolver()),
            )
            val result = diagnostic.run()
            assertEquals(V2DiagnosticStage.READINESS, result.stage)
            assertEquals(V2DiagnosticCode.UPSTREAM_ERROR, result.phone.code)
            assertFalse(result.toString().contains("fixture-secret"))
        } finally { server.shutdown() }
    }
}
