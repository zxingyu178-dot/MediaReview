package com.mediareview.app.feature.v2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 模拟 Server：仅存在于 instrumentation 生命周期。真实 HTTP、图片字节与 MP4，非家庭 LAN。 */
internal class Stage8FHttpFixture(private val legacy: Boolean = false, private val largeLibrary: Boolean = false) : AutoCloseable {
    data class Hit(val path: String, val method: String, val authenticated: Boolean, val page: Int?, val pageSize: Int?, val search: String? = null, val folder: String? = null)
    val hits = CopyOnWriteArrayList<Hit>()
    val pending = ConcurrentHashMap.newKeySet<String>()
    private val favorites = ConcurrentHashMap.newKeySet<String>()
    private val polls = ConcurrentHashMap<String, AtomicInteger>()
    @Volatile var failAlbumPage = 0
    @Volatile var failSprite = false
    @Volatile var failSearchPage = 0
    private val server = MockWebServer()
    private val videoBytes = ApplicationProvider.getApplicationContext<Context>().let { target ->
        val id = target.resources.getIdentifier("demo_05_longer", "raw", target.packageName)
        check(id != 0) { "实际目标 APK 缺少 demo_05_longer 媒体资源" }
        target.resources.openRawResource(id).use { it.readBytes() }
    }
    private val sheetBytes = png(320, 90) { x, _ -> if (x < 160) Color.RED else Color.BLUE }
    private val greenBytes = png(320, 180) { _, _ -> Color.GREEN }
    private val photoBytes = (0..3).associateWith { index -> png(320, 180) { _, _ -> photoColor(index) } }
    private val host = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>().first { it.isSiteLocalAddress }.hostAddress!!
    val baseUrl: String get() = "http://$host:${server.port}"
    private val photoCount = if (largeLibrary) 500 else if (legacy) 30 else 120
    private val videoCount = if (largeLibrary) 500 else if (legacy) 30 else 12
    private val ids get() = (1..videoCount).map { "v$it" } + (1..photoCount).map { "p$it" }

    init {
        if (largeLibrary) favorites.addAll(listOf("v301", "v302", "v303", "p301", "p302", "p303"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = requireNotNull(request.requestUrl)
                val path = url.encodedPath
                val public = path.endsWith("/health") || path.startsWith("/api/v1/pairing/")
                val stream = path.startsWith("/stream/")
                val authenticated = if (stream) request.getHeader("X-Emby-Token") == "mock-device-stream-key"
                    else request.getHeader("Authorization") == "Bearer mock-token"
                hits += Hit(path, request.method.orEmpty(), authenticated, url.queryParameter("page")?.toIntOrNull(), url.queryParameter("page_size")?.toIntOrNull(), url.queryParameter("search"), url.queryParameter("folder_id"))
                if (!public && !authenticated) return MockResponse().setResponseCode(if (stream) 403 else 401)
                return when {
                    path.endsWith("/health") -> envelope("""{"status":"ok","version":"1.2.2","api_contract":2,"capabilities":[]}""")
                    path.endsWith("/pairing/status") -> envelope("""{"pairing_required":true,"device_count":0}""")
                    path.endsWith("/pairing/verify") -> envelope("""{"paired":true,"token":"mock-token"}""")
                    path == "/api/v1/media/folders" -> envelope("""[{"folder_id":"f_photos","name":"测试相册","count":$photoCount,"image_count":$photoCount,"cover_media_id":"p1","cover_url":"/api/v1/media/p1/cover"},{"folder_id":"f_videos","name":"测试视频","count":$videoCount,"image_count":0}]""")
                    path == "/api/v1/media" -> {
                        val page = url.queryParameter("page")?.toIntOrNull() ?: 1
                        val size = url.queryParameter("page_size")?.toIntOrNull() ?: 50
                        val folder = url.queryParameter("folder_id")
                        if (!url.queryParameter("search").isNullOrBlank() && page == failSearchPage) return MockResponse().setResponseCode(503)
                        if (folder == "f_photos" && page == failAlbumPage) return MockResponse().setResponseCode(503)
                        val selected = ids.filter { id ->
                            (folder == null || folder == if (id.startsWith("p")) "f_photos" else "f_videos") &&
                                (url.queryParameter("media_type") == null || url.queryParameter("media_type") == if (id.startsWith("p")) "image" else "video") &&
                                (url.queryParameter("search").isNullOrBlank() || name(id).contains(url.queryParameter("search")!!))
                        }
                        envelope("""{"items":[${selected.drop((page - 1) * size).take(size).joinToString(",") { media(it) }}],"total":${selected.size},"page":$page,"page_size":$size}""")
                    }
                    path.endsWith("/cover") || path.endsWith("/original") -> {
                        val id = path.split('/')[4]
                        // 封面与原图颜色不同：Viewer 像素必须证明原图已解码，不能被缓存封面冒充。
                        binary(if (id.startsWith("p") && path.endsWith("/original")) photoBytes.getValue(id.drop(1).toInt() % 4) else greenBytes, "image/png")
                    }
                    path.startsWith("/api/v1/cache/sprites/") && path.endsWith("/file") -> binary(sheetBytes, "image/png")
                    path.startsWith("/api/v1/cache/sprites/") -> {
                        val id = path.substringAfterLast('/')
                        if (request.method == "POST") {
                            polls[id] = AtomicInteger()
                            envelope("""{"status":"pending","task_id":"sprite-$id"}""")
                        } else envelope("""{"media_id":"$id","status":"ready","columns":2,"rows":1,"count":2,"tile_width":160,"tile_height":90,"interval_ms":500,"total_duration_ms":1000,"video_width":320,"video_height":180,"url":"/api/v1/cache/sprites/$id/file"}""")
                    }
                    path.startsWith("/api/v1/tasks/sprite-") -> {
                        val id = path.substringAfter("sprite-")
                        val count = polls.getValue(id).incrementAndGet()
                        val status = if (failSprite) "failed" else if (count < 4) "running" else "succeeded"
                        envelope("""{"task_id":"sprite-$id","media_id":"$id","type":"sprite","status":"$status","progress":${if (status == "succeeded") 100 else 20}}""")
                    }
                    path.endsWith("/playback") -> {
                        val id = path.split('/')[4]
                        val endpoint = """{"url":"$baseUrl/stream/$id.mp4","headers":{"X-Emby-Token":"mock-device-stream-key"}}"""
                        envelope("""{"media_id":"$id","title":"${name(id)}","stream_url":"$baseUrl/stream/$id.mp4","stream_url_authoritative":true,"stream_url_source":"configured_client_url","direct":$endpoint,"fallback_hls":$endpoint}""")
                    }
                    stream -> video(request)
                    path == "/api/v1/favorites" -> envelope(favorites.sorted().joinToString(",", "[", "]") { """{"media_id":"$it","media":${media(it)}}""" })
                    path.startsWith("/api/v1/favorites/") -> {
                        val id = path.substringAfterLast('/'); val add = request.method == "POST"
                        if (add) favorites.add(id) else favorites.remove(id)
                        envelope("""{"media_id":"$id","favorited":$add}""")
                    }
                    path == "/api/v1/delete-queue/summary" -> envelope("""{"count":${pending.size},"total_bytes":${pending.size * 1024}}""")
                    path == "/api/v1/delete-queue" -> envelope(pending.joinToString(",", "[", "]") { """{"media_id":"$it","status":"pending","size_bytes":1024,"media":${media(it)}}""" })
                    path.startsWith("/api/v1/delete-queue/") -> {
                        val id = path.substringAfterLast('/'); val add = request.method == "POST"
                        if (add) pending.add(id) else pending.remove(id)
                        envelope("""{"media_id":"$id","queued":$add}""")
                    }
                    path.endsWith("/duplicates/summary") -> envelope("{}")
                    path.endsWith("/libraries") -> envelope("[]")
                    path.endsWith("/jellyfin/status") -> envelope("""{"server_name":"模拟媒体库","version":"fixture"}""")
                    path.endsWith("/progress") -> envelope("{}")
                    path.startsWith("/api/v1/media/") -> envelope(media(path.substringAfterLast('/')))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start(InetAddress.getByName("0.0.0.0"), 0)
    }

    fun count(path: String, method: String = "GET") = hits.count { it.path == path && it.method == method }
    fun isFavorite(mediaId: String): Boolean = mediaId in favorites
    fun name(id: String): String {
        val base = if (id.startsWith("p")) "照片 ${id.drop(1).padStart(3, '0')}" else "服务器视频 ${id.drop(1).padStart(if (largeLibrary) 3 else 2, '0')}"
        return if (largeLibrary && id.drop(1).toInt() <= 110) "abc $base" else base
    }
    private fun media(id: String): String {
        val photo = id.startsWith("p")
        return """{"media_id":"$id","name":"${name(id)}","media_type":"${if (photo) "image" else "video"}","library_id":"fixture","duration_ms":18000,"size_bytes":1024,"width":320,"height":180,"folder_id":"${if (photo) "f_photos" else "f_videos"}","folder_name":"${if (photo) "测试相册" else "测试视频"}","cover_url":"/api/v1/media/$id/cover","original_url":"/api/v1/media/$id/original","is_favorite":${id in favorites}}"""
    }
    private fun envelope(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"success":true,"data":$body}""")
    private fun binary(bytes: ByteArray, type: String) = MockResponse().setHeader("Content-Type", type).setBody(Buffer().write(bytes))
    // 与 RealHttpPlaybackFixture 相同的真实 MP4 Range 合同。
    private fun video(request: RecordedRequest): MockResponse {
        val range = request.getHeader("Range") ?: return binary(videoBytes, "video/mp4")
        val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(range) ?: return MockResponse().setResponseCode(416)
        val left = match.groupValues[1].toLongOrNull()
        val right = match.groupValues[2].toLongOrNull()
        val start = left ?: (videoBytes.size - (right ?: 0)).coerceAtLeast(0)
        val end = if (left == null) videoBytes.lastIndex.toLong() else (right ?: videoBytes.lastIndex.toLong()).coerceAtMost(videoBytes.lastIndex.toLong())
        if (start > end || start >= videoBytes.size) return MockResponse().setResponseCode(416)
        return binary(videoBytes.copyOfRange(start.toInt(), end.toInt() + 1), "video/mp4").setResponseCode(206)
            .setHeader("Accept-Ranges", "bytes").setHeader("Content-Range", "bytes $start-$end/${videoBytes.size}")
    }
    override fun close() = server.shutdown()
    companion object {
        fun photoColor(index: Int) = when (index % 4) { 0 -> Color.YELLOW; 1 -> Color.CYAN; 2 -> Color.MAGENTA; else -> Color.WHITE }
        private fun png(width: Int, height: Int, color: (Int, Int) -> Int): ByteArray {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for (x in 0 until width) for (y in 0 until height) bitmap.setPixel(x, y, color(x, y))
            return ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle(); output.toByteArray() }
        }
    }
}
