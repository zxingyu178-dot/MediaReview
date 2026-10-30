package com.mediareview.app.feature.v2.player

import com.mediareview.app.feature.v2.model.V2PlaybackEndpoint
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import okhttp3.*
import okio.ByteString.Companion.encodeUtf8

fun interface VideoPrefetchPort {
    suspend fun prefetch(endpoint: V2PlaybackEndpoint)

    companion object { val None = VideoPrefetchPort { } }
}

/** Two bounded in-memory prefixes; no disk media or URL-only credential cache. */
class VideoPrefixCache(
    private val client: OkHttpClient,
    private val scope: () -> String?,
    private val prefixBytes: Int = 2 * 1024 * 1024,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : VideoPrefetchPort {
    class Entry internal constructor(
        val bytes: ByteArray, val etag: String, val total: Long,
        internal val scope: String, internal val storedAt: Long,
    )
    private val entries = LinkedHashMap<String, Entry>()
    private val lock = Mutex()
    private var lastAttempt: Pair<String, Long>? = null
    @Volatile private var attached = false

    init { require(prefixBytes in 1..2 * 1024 * 1024) }
    fun attachConsumer() { attached = true }
    @Synchronized fun clear() { entries.clear(); lastAttempt = null }
    internal fun scopeIs(value: String) = scope() == value
    @Synchronized fun find(endpoint: V2PlaybackEndpoint): Entry? {
        val namespace = scope() ?: return null.also { entries.clear() }
        entries.entries.removeAll { it.value.scope != namespace || nowMs() - it.value.storedAt >= 30_000 }
        return key(endpoint, namespace)?.let(entries::get)
    }
    @Synchronized internal fun evict(endpoint: V2PlaybackEndpoint) {
        scope()?.let { key(endpoint, it) }?.let(entries::remove)
    }

    override suspend fun prefetch(endpoint: V2PlaybackEndpoint) {
        if (!attached || !lock.tryLock()) return
        try {
            val namespace = scope() ?: return
            val key = key(endpoint, namespace) ?: return
            if (find(endpoint) != null) return
            synchronized(this) {
                if (lastAttempt?.let { it.first == key && nowMs() - it.second < 30_000 } == true) return
                lastAttempt = key to nowMs()
            }
            val request = Request.Builder().url(endpoint.url).apply {
                endpoint.headers.forEach { (name, value) -> header(name, value) }
                header("Range", "bytes=0-${prefixBytes - 1}")
                header("Accept-Encoding", "identity")
            }.build()
            val entry = fetch(request, namespace) ?: return
            currentCoroutineContext().ensureActive()
            synchronized(this) {
                if (scopeIs(namespace)) {
                    entries.entries.removeAll { it.value.scope != namespace }
                    while (entries.size >= 2) entries.remove(entries.keys.first())
                    entries[key] = entry
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: IOException) { /* Optional work never changes playback/error state. */
        } catch (_: IllegalArgumentException) { /* Invalid optional URL/headers are not playback work. */
        } finally { lock.unlock() }
    }

    private suspend fun fetch(request: Request, namespace: String): Entry? = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val range = parseVideoRange(response.header("Content-Range"))
                        val etag = response.header("ETag")
                        val mime = response.header("Content-Type").orEmpty().substringBefore(';').lowercase(Locale.ROOT)
                        val length = response.header("Content-Length")?.toLongOrNull()
                        if (response.code != 206 || range == null || range.first != 0L ||
                            range.last + 1 != length || length !in 1L..prefixBytes.toLong() ||
                            !strongVideoEtag(etag) || !reusableVideoHeaders(response.headers.toMultimap()) ||
                            !(mime.startsWith("video/") || mime == "application/octet-stream") ||
                            response.request.url != request.url || response.body == null) {
                            // Cancel before close: do not drain an ignored full-file response for reuse.
                            call.cancel()
                            if (cont.isActive) cont.resume(null)
                            return
                        }
                        val bytes = response.body!!.source().readByteArray(length!!)
                        if (cont.isActive) cont.resume(Entry(bytes, etag!!, range.total, namespace, nowMs()))
                    }
                } catch (error: IOException) { if (cont.isActive) cont.resumeWithException(error) }
            }
        })
    }

    private fun key(endpoint: V2PlaybackEndpoint, namespace: String): String? {
        val url = runCatching { Request.Builder().url(endpoint.url).build().url }.getOrNull() ?: return null
        if (url.scheme !in setOf("http", "https") || url.encodedPath.endsWith(".m3u8", true) ||
            url.encodedPath.endsWith(".mpd", true)) return null
        if (endpoint.headers.keys.any { it.equals("Range", true) || it.startsWith("If-", true) }) return null
        val identity = endpoint.headers.entries.map { it.key.lowercase(Locale.ROOT) to it.value }
            .filterNot { (name, _) -> name in setOf("user-agent", "accept-encoding", "icy-metadata") || name.startsWith("cmcd-") }
            .sortedBy { it.first }.joinToString("\u0000") { "${it.first}\u0000${it.second}" }
        return "$namespace\u0000${url}\u0000$identity".encodeUtf8().sha256().hex()
    }
}

internal data class VideoRange(val first: Long, val last: Long, val total: Long)
internal fun parseVideoRange(value: String?): VideoRange? {
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(value.orEmpty()) ?: return null
    val values = match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
    return VideoRange(values[0], values[1], values[2]).takeIf { it.first >= 0 && it.last >= it.first && it.total > it.last }
}
internal fun strongVideoEtag(value: String?) = value != null && value.length >= 2 && value.startsWith('"') && value.endsWith('"') &&
    value.substring(1, value.lastIndex).all { it == '!' || it in '#'..'~' || it.code in 128..255 }
internal fun reusableVideoHeaders(headers: Map<String, List<String>>): Boolean {
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.joinToString(",").orEmpty()
    return header("Cache-Control").split(',').none { it.trim().substringBefore('=').equals("no-store", true) } &&
        header("Content-Encoding").let { it.isEmpty() || it.equals("identity", true) }
}

data class VideoRangeResponse(val code: Int, val length: Long, val url: String, val headers: Map<String, List<String>>) {
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
}

/** The production Media3 adapter and protocol regression use this same reader. */
interface VideoRangeConnection {
    fun open(position: Long, length: Long, ifMatch: String? = null): VideoRangeResponse
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun close()
}

class VideoPrefixReader(
    private val cache: VideoPrefixCache,
    private val endpoint: V2PlaybackEndpoint,
    private val network: VideoRangeConnection,
) {
    private var entry: VideoPrefixCache.Entry? = null
    private var cursor = 0
    private var prefixEnd = 0
    private var remaining = -1L

    fun open(position: Long, length: Long): Long {
        entry = null
        cursor = 0
        prefixEnd = 0
        remaining = -1
        val candidate = cache.find(endpoint)?.takeIf { position < it.bytes.size }
        if (candidate != null) {
            val end = if (length < 0) candidate.total else position + minOf(candidate.total - position, length)
            val tailStart = candidate.bytes.size.toLong()
            val needsTail = end > tailStart
            val validationStart = if (needsTail) tailStart else 0L
            val validationLength = if (needsTail) end - tailStart else 1L
            val response = network.open(validationStart, validationLength, candidate.etag)
            if (response.code == 401 || response.code == 403) throw IOException("视频认证失败（${response.code}）")
            val range = parseVideoRange(response.header("Content-Range"))
            if (response.code == 206 && response.url == endpoint.url && response.header("ETag") == candidate.etag &&
                reusableVideoHeaders(response.headers) && range?.first == validationStart && range.total == candidate.total &&
                range.last - range.first + 1 == validationLength && response.length == validationLength &&
                response.header("Content-Length")?.toLongOrNull() == validationLength && cache.scopeIs(candidate.scope)) {
                if (!needsTail) network.close()
                entry = candidate
                cursor = position.toInt()
                prefixEnd = minOf(end, tailStart).toInt()
                remaining = end - position
                return remaining
            }
            network.close()
            cache.evict(endpoint)
        }
        val response = network.open(position, length)
        if (response.code !in 200..299) throw IOException("视频请求失败（${response.code}）")
        remaining = response.length
        return remaining
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        entry?.let { if (!cache.scopeIs(it.scope)) throw IOException("视频请求的配对状态已变化") }
        if (remaining == 0L) return -1
        val count = if (cursor < prefixEnd) {
            minOf(length, prefixEnd - cursor).also { size -> entry!!.bytes.copyInto(buffer, offset, cursor, cursor + size); cursor += size }
        } else network.read(buffer, offset, if (remaining < 0) length else minOf(length.toLong(), remaining).toInt())
        entry?.let { if (!cache.scopeIs(it.scope)) throw IOException("视频请求的配对状态已变化") }
        if (count < 0 && remaining > 0) throw IOException("视频数据提前结束")
        if (count > 0 && remaining >= 0) remaining -= count
        return count
    }

    fun close() { entry = null; network.close() }
}
