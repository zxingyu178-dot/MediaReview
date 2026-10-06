package com.mediareview.app.feature.v2.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mediareview.app.R
import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.MediaUrlResolver
import com.mediareview.app.feature.v2.data.server.*
import com.mediareview.app.feature.v2.review.data.V2ServerReviewSessionRepository
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** 设备内真实 HTTP MP4；只保存认证是否匹配，不保存或输出请求头值。 */
internal class RealHttpPlaybackFixture : AutoCloseable {
    /**
     * 播放内核真实发出的视频请求。[url] 是内核实际请求的完整地址——
     * GSY 的 Compose host player 不提供已装载 URL 的公开读取接口（GSYPlayerSnapshot 无 URL 字段），
     * 因此以"请求真的到达该 URL"作为内核 source 的事实证据。
     */
    data class VideoRequest(
        val mediaId: String,
        val url: String,
        val authenticated: Boolean,
        val ranged: Boolean,
    )
    val videos = CopyOnWriteArrayList<VideoRequest>()
    private val credential = UUID.randomUUID().toString()
    private val server = MockWebServer()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bytes = context.resources.openRawResource(R.raw.demo_05_longer).use { it.readBytes() }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun envelope(body: String) = json("""{"success":true,"data":$body}""")
    private fun media(id: String) = """{"media_id":"$id","name":"HTTP 视频 $id","media_type":"video","library_id":"test","duration_ms":18000,"width":640,"height":360,"folder_id":"test","folder_name":"测试","is_favorite":false}"""
    private fun session(index: Int = 0) = """{"session_id":"http-review","status":"active","current_index":$index,"total_count":2,"seen_count":0,"remaining_count":2,"unavailable_count":0,"completed_count":0,"source":{"filter":{"media_type":"video"},"sort":{"sort_by":"created","sort_order":"desc"}}}"""

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                return when {
                    path.startsWith("/video/") -> video(request, path.substringAfterLast('/').removeSuffix(".mp4"))
                    path.endsWith("/playback") -> {
                        val id = path.removePrefix("/api/v1/media/").removeSuffix("/playback")
                        // 用设备非回环地址下发播放 URL：生产 MediaUrlResolver 拒绝回环目的地
                        // （防"假地址 PASS"）；这里必须是真实可读地址而非 127.0.0.1。
                        val url = playbackBase.newBuilder().addPathSegments("video/$id.mp4").build()
                        envelope("""{"media_id":"$id","title":"HTTP 视频","stream_url":"$url","stream_url_authoritative":true,"stream_url_source":"configured_client_url","direct":{"url":"$url","headers":{"X-Emby-Token":"$credential"}},"fallback_hls":null}""")
                    }
                    path.endsWith("/nearest") -> envelope("""{"index":0}""")
                    path.endsWith("/queue") -> envelope("""{"items":[{"index":0,"seen":false,"media":${media("first")}}, {"index":1,"seen":false,"media":${media("second")}}],"total":2,"page":1,"page_size":50}""")
                    path.endsWith("/seen") -> envelope("""{"media_id":"first","seen":true,"seen_count":1,"total_count":2,"remaining_count":1,"unavailable_count":0,"completed_count":1}""")
                    path.contains("/review/sessions") -> envelope(session())
                    path == "/api/v1/media" -> envelope("""{"items":[${media("first")},${media("second")}],"total":2,"page":1,"page_size":50}""")
                    path.endsWith("/progress") -> envelope("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        // 必须绑定所有接口：播放地址用设备 LAN 地址下发（非回环），只监听回环会连不上。
        server.start(InetAddress.getByName("0.0.0.0"), 0)
    }

    private fun video(request: RecordedRequest, id: String): MockResponse {
        val valid = request.getHeader("X-Emby-Token") == credential
        val range = request.getHeader("Range")
        videos += VideoRequest(id, request.requestUrl.toString(), valid, range != null)
        if (!valid) return MockResponse().setResponseCode(401)
        val response = MockResponse().setHeader("Content-Type", "video/mp4").setHeader("Accept-Ranges", "bytes")
        if (range == null) return response.setBody(Buffer().write(bytes))
        val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(range)
            ?: return response.setResponseCode(416).setHeader("Content-Range", "bytes */${bytes.size}")
        val left = match.groupValues[1].toLongOrNull()
        val right = match.groupValues[2].toLongOrNull()
        val start = left ?: (bytes.size - (right ?: 0)).coerceAtLeast(0)
        val end = if (left == null) bytes.lastIndex.toLong() else (right ?: bytes.lastIndex.toLong()).coerceAtMost(bytes.lastIndex.toLong())
        if (start > end || start >= bytes.size) return response.setResponseCode(416).setHeader("Content-Range", "bytes */${bytes.size}")
        return response.setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
            .setBody(Buffer().write(bytes, start.toInt(), (end - start + 1).toInt()))
    }

    /** MediaReview API 基地址（业务接口，不受媒体 URL 安全策略约束）。 */
    private val apiBase: String = "http://127.0.0.1:${server.port}/"

    /** 播放地址必须用设备真实 LAN 地址（生产 Resolver 拒绝回环/本机目的地）。 */
    private val playbackBase: HttpUrl = run {
        val host = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?: error("设备没有可用的局域网 IPv4 地址，无法提供真实播放地址")
        HttpUrl.Builder().scheme("http").host(host.hostAddress!!).port(server.port).build()
    }

    private val status = V2ServerStatusStore().apply { update(V2ServerStatus.Online) }
    private val resources = V2ServerResourceCache()
    private val resolver = MediaUrlResolver()
    private val mapper = V2MediaMapper(resolver)
    private val http = OkHttpClient()
    private val api = ApiFactory(http, http, Json { ignoreUnknownKeys = true; coerceInputValues = true })
    private val profile = object : V2ServerProfilePort {
        override suspend fun baseUrl() = apiBase
    }
    val repository = V2ServerMediaRepository(profile, api, mapper, V2PlaybackResolver(resolver),
        object : V2ServerAlbumCoverPort {
            override suspend fun allCovers(): Map<String, String> = emptyMap()
            override suspend fun setCover(folderId: String, mediaId: String?) = Unit
        }, status, resources)
    val sessions = V2ServerReviewSessionRepository(profile, api, mapper, resources, status)
    override fun close() { server.shutdown(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
}


