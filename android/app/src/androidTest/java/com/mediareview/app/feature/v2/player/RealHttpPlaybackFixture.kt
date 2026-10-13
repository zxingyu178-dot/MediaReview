package com.mediareview.app.feature.v2.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mediareview.app.R
import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.AuthInterceptor
import com.mediareview.app.core.network.CacheAuthInterceptor
import com.mediareview.app.core.network.TokenProvider
import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.core.network.MediaUrlResolver
import com.mediareview.app.feature.v2.data.server.*
import com.mediareview.app.feature.v2.review.data.V2ServerReviewSessionRepository
import kotlinx.serialization.json.Json
import com.mediareview.app.core.model.ProgressRequest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 设备内真实 HTTP MP4；只保存认证是否匹配，不保存或输出请求头值。 */
internal class RealHttpPlaybackFixture(
    private val longVideo: Boolean = false,
    private val blockSecondPlayback: Boolean = false,
    private val blockFirstVideo: Boolean = false,
    private val resumeMs: Long = 0L,
    private val blockPendingRead: Boolean = false,
    private val blockPendingWrite: Boolean = false,
    private val imageMedia: Boolean = false,
    private val authenticatedApi: Boolean = false,
    private val folderFixture: Boolean = false,
) : AutoCloseable {
    @Volatile var pending: Set<String> = emptySet()
    @Volatile var pendingReadFailure = false
    @Volatile var pendingWriteFailure = false
    val pendingReadRequested = CountDownLatch(1)
    private val pendingReadRelease = CountDownLatch(1)
    fun releasePendingRead() { pendingReadRelease.countDown() }
    val pendingWriteRequested = CountDownLatch(1)
    private val pendingWriteRelease = CountDownLatch(1)
    fun releasePendingWrite() { pendingWriteRelease.countDown() }
    data class PendingWrite(val mediaId: String, val pending: Boolean, val succeeded: Boolean)
    val pendingWrites = CopyOnWriteArrayList<PendingWrite>()
    data class ApiRequest(val path: String, val pageSize: String?, val authenticated: Boolean,
        val folderId: String? = null, val includeDescendants: String? = null)
    val apiRequests = CopyOnWriteArrayList<ApiRequest>()
    val firstVideoRequested = CountDownLatch(1)
    private val firstVideoRelease = CountDownLatch(1)
    fun releaseFirstVideo() { firstVideoRelease.countDown() }
    val secondPlaybackRequested = CountDownLatch(1)
    private val secondPlaybackRelease = CountDownLatch(1)
    fun releaseSecondPlayback() { secondPlaybackRelease.countDown() }
    @Volatile private var secondPlaybackFails = false
    /** Release a real HTTP 503 before the client read timeout; the Server remains reachable. */
    fun failSecondPlayback() { secondPlaybackFails = true; secondPlaybackRelease.countDown() }
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
    /** 仅结构化业务数据，不保存任何认证头或原始请求体。 */
    data class ProgressPost(val mediaId: String, val position: Long, val paused: Boolean, val method: String)
    val progresses = CopyOnWriteArrayList<ProgressPost>()
    private val credential = UUID.randomUUID().toString()
    private val server = MockWebServer()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bytes = if (longVideo) androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        .open("gesture_60s.mp4").use { it.readBytes() } else context.resources.openRawResource(R.raw.demo_05_longer).use { it.readBytes() }
    private val imageBytes by lazy {
        val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.GREEN)
            java.io.ByteArrayOutputStream().use { output ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun envelope(body: String) = json("""{"success":true,"data":$body}""")
    private fun media(id: String): String {
        val images = if (imageMedia) ""","cover_url":"/api/v1/media/$id/cover?v=fixture1","original_url":"/api/v1/media/$id/original?v=fixture1" """ else ""
        return """{"media_id":"$id","name":"HTTP 视频 $id","media_type":"${if (imageMedia) "image" else "video"}","library_id":"test","duration_ms":${if (longVideo) 60000 else 18000},"width":640,"height":360,"folder_id":"${if (folderFixture) "ft_leaf" else "test"}","folder_name":"测试","is_favorite":false$images}"""
    }
    private fun session(index: Int = 0) = """{"session_id":"http-review","status":"active","current_index":$index,"total_count":2,"seen_count":0,"remaining_count":2,"unavailable_count":0,"completed_count":0,"source":{"filter":{"media_type":"${if (imageMedia) "image" else "video"}"},"sort":{"sort_by":"created","sort_order":"desc"}}}"""

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                if (path.startsWith("/api/v1/")) {
                    val valid = request.getHeader("Authorization") == "Bearer $credential"
                    apiRequests += ApiRequest(path, request.requestUrl?.queryParameter("page_size"), valid,
                        request.requestUrl?.queryParameter("folder_id"), request.requestUrl?.queryParameter("include_descendants"))
                    if (authenticatedApi && path != "/api/v1/system/health" && !valid) {
                        return MockResponse().setResponseCode(401)
                    }
                }
                return when {
                    path == "/api/v1/system/health" -> envelope("""{"status":"ok","version":"1.4.0","api_contract":2,"capabilities":${if (folderFixture) "[\"folder_tree_v1\"]" else "[]"}}""")
                    folderFixture && path == "/api/v1/media/folder-tree" -> envelope(listOf(
                        Triple("ft_root", null, "媒体库"), Triple("ft_parent", "ft_root", "旅行"),
                        Triple("ft_child", "ft_parent", "海边"), Triple("ft_leaf", "ft_child", "日落")
                    ).joinToString(prefix = "[", postfix = "]") { (id, parent, name) ->
                        """{"folder_id":"$id","parent_id":${parent?.let { "\"$it\"" } ?: "null"},"library_id":"test","name":"$name","child_count":${if (id == "ft_leaf") 0 else 1},"direct_media_count":${if (id == "ft_leaf") 2 else 0},"recursive_media_count":2,"direct_image_count":${if (id == "ft_leaf") 2 else 0},"cover_url":"/api/v1/media/first/thumbnail?v=fixture1"}"""
                    })
                    path == "/api/v1/media/folders" -> envelope("[]")
                    imageMedia && (path.endsWith("/thumbnail") || path.endsWith("/original")) ->
                        MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(imageBytes))
                    path.startsWith("/video/") -> video(request, path.substringAfterLast('/').removeSuffix(".mp4"))
                    path.endsWith("/playback") -> {
                        val id = path.removePrefix("/api/v1/media/").removeSuffix("/playback")
                        if (id == "second") {
                            secondPlaybackRequested.countDown()
                            if (blockSecondPlayback && !secondPlaybackRelease.await(20, TimeUnit.SECONDS)) {
                                return MockResponse().setResponseCode(503)
                            }
                            if (secondPlaybackFails) return MockResponse().setResponseCode(503)
                        }
                        // 用设备非回环地址下发播放 URL：生产 MediaUrlResolver 拒绝回环目的地
                        // （防"假地址 PASS"）；这里必须是真实可读地址而非 127.0.0.1。
                        val url = playbackBase.newBuilder().addPathSegments("video/$id.mp4").build()
                        envelope("""{"media_id":"$id","title":"HTTP 视频","stream_url":"$url","stream_url_authoritative":true,"stream_url_source":"configured_client_url","direct":{"url":"$url","headers":{"X-Emby-Token":"$credential"}},"fallback_hls":null,"resume_position_ms":${if (id == "first") resumeMs else 0L}}""")
                    }
                    path.endsWith("/nearest") -> envelope("""{"index":0}""")
                    path.endsWith("/queue") -> envelope("""{"items":[{"index":0,"seen":false,"media":${media("first")}}, {"index":1,"seen":false,"media":${media("second")}}],"total":2,"page":1,"page_size":50}""")
                    path.endsWith("/seen") -> envelope("""{"media_id":"first","seen":true,"seen_count":1,"total_count":2,"remaining_count":1,"unavailable_count":0,"completed_count":1}""")
                    path.contains("/review/sessions") -> envelope(session())
                    path == "/api/v1/delete-queue" -> {
                        pendingReadRequested.countDown()
                        if (blockPendingRead && !pendingReadRelease.await(20, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                        if (pendingReadFailure) return MockResponse().setResponseCode(503)
                        if (request.method != "GET") MockResponse().setResponseCode(405)
                        else envelope(pending.joinToString(prefix = "[", postfix = "]") { """{"media_id":"$it","status":"pending"}""" })
                    }
                    path.startsWith("/api/v1/delete-queue/") -> {
                        val id = path.substringAfterLast('/')
                        if (request.method !in listOf("POST", "DELETE")) return MockResponse().setResponseCode(405)
                        pendingWriteRequested.countDown()
                        if (blockPendingWrite && !pendingWriteRelease.await(20, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                        val target = request.method == "POST"
                        pendingWrites += PendingWrite(id, target, !pendingWriteFailure)
                        if (pendingWriteFailure) return MockResponse().setResponseCode(503)
                        pending = if (target) pending + id else pending - id
                        envelope("""{"media_id":"$id","queued":$target}""")
                    }
                    folderFixture && path == "/api/v1/media" && request.requestUrl?.queryParameter("include_descendants") == "false" &&
                        request.requestUrl?.queryParameter("folder_id") in listOf("ft_root", "ft_parent", "ft_child") ->
                        envelope("""{"items":[],"total":0,"page":1,"page_size":50}""")
                    path == "/api/v1/media" -> if (request.requestUrl?.queryParameter("page_size") == "1") {
                        envelope("""{"items":[${media("first")}],"total":2,"page":1,"page_size":1}""")
                    } else envelope("""{"items":[${media("first")},${media("second")}],"total":2,"page":1,"page_size":50}""")
                    path.endsWith("/progress") -> {
                        if (request.method != "POST") return MockResponse().setResponseCode(405)
                        val id = path.removePrefix("/api/v1/media/").removeSuffix("/progress")
                        val body = Json.decodeFromString(ProgressRequest.serializer(), request.body.readUtf8())
                        progresses += ProgressPost(id, body.position_ms,
                            body.is_paused, request.method.orEmpty())
                        envelope("""{"media_id":"$id","reported":true}""")
                    }
                    else -> {
                        android.util.Log.d("MRFixtureRoute",
                            "unknown method=" + request.method + " path=" + path + " status=404")
                        MockResponse().setResponseCode(404)
                    }
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
        if (blockFirstVideo && id == "first") {
            firstVideoRequested.countDown()
            if (!firstVideoRelease.await(20, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
        }
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

    /** 认证图片测试与配对/业务接口同origin；旧播放fixture仍保留原回环API基址。 */
    private val apiBase: String
        get() = if (authenticatedApi) playbackBase.toString() else "http://127.0.0.1:${server.port}/"

    /** 仅测试 Store 的临时 fixture 配置；随机凭据不通过公开字段/日志暴露。 */
    suspend fun configureTestProfile(store: ServerProfileStore) {
        store.saveBaseUrl(apiBase)
        store.savePairing(credential)
    }

    /** Viewer 测试独占的图片 HTTP client；调用方负责关闭，凭据仍仅保存在拦截器中。 */
    fun authenticatedImageClient(): OkHttpClient {
        val tokens = TokenProvider().apply { set(credential, apiBase) }
        return OkHttpClient.Builder().addInterceptor(CacheAuthInterceptor(tokens)).build()
    }

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

    val status = V2ServerStatusStore().apply {
        if (folderFixture) recordCompatible("1.4.0", 2, listOf("folder_tree_v1"))
        else update(V2ServerStatus.Online)
    }
    private val resources = V2ServerResourceCache()
    private val resolver = MediaUrlResolver()
    private val mapper = V2MediaMapper(resolver)
    private val http = if (authenticatedApi) {
        val tokens = TokenProvider().apply { set(credential, apiBase) }
        OkHttpClient.Builder().addInterceptor(AuthInterceptor(tokens)).build()
    } else OkHttpClient()
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
    override fun close() { releaseFirstVideo(); releaseSecondPlayback(); server.shutdown(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
}
