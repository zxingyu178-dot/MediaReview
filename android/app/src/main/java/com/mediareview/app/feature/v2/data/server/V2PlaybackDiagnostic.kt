package com.mediareview.app.feature.v2.data.server

import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.core.model.Envelope
import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.pairing.REQUIRED_SERVER_API_CONTRACT
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import retrofit2.HttpException

enum class V2DiagnosticCode(val label: String) {
    READABLE("视频流可读"), UNAUTHORIZED("认证失败（401）"), FORBIDDEN("权限不足（403）"),
    NOT_FOUND("媒体不存在（404）"), UPSTREAM_ERROR("服务错误（5xx）"), NETWORK("网络连接失败"),
    REDIRECT("播放地址发生跳转，已阻止"), NON_VIDEO("返回内容不是视频"), EMPTY("视频流为空"),
    HTTP_ERROR("HTTP 请求失败"), SERVER_ERROR("服务器返回错误"), INCOMPATIBLE("服务器版本过旧"),
    UNCONFIGURED("尚未配置服务器"), UNPAIRED("请先配对服务器"), NO_MEDIA("没有可用于检查的视频"),
    INVALID_PLAYBACK("服务器播放信息无效"), SERVER_UNREACHABLE("电脑端无法连接 Jellyfin"),
    MEDIA_UNAVAILABLE("媒体已不可用"),
}

data class V2StreamProbeResult(val code: V2DiagnosticCode, val httpStatus: Int? = null, val bytesRead: Int = 0)
enum class V2DiagnosticStage(val label: String) { HEALTH("服务器健康检查"), READINESS("电脑端播放检查"), PLAYBACK("播放信息检查"), PHONE("手机直连检查") }

/** 只存受控状态和 host:port，不留 URL、headers、原始异常或上游正文。 */
data class V2PlaybackDiagnosticReport(
    val stage: V2DiagnosticStage,
    val phone: V2StreamProbeResult,
    val serverReachable: Boolean? = null,
    val serverStreamStatus: String = "未检查",
    val credentialStatus: String = "未检查",
    val addressSource: String = "未确认",
    val authoritative: Boolean = false,
    val destination: String = "",
)

/** 独立客户端，绝不复用 MediaReview 的 Bearer/日志拦截器；最多读取 1024 字节。 */
class V2DirectStreamProbe internal constructor(dns: Dns = Dns.SYSTEM) {
    private val http = OkHttpClient.Builder().dns(dns)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS).build()

    private fun expectedReadLength(response: Response): Int? {
        val rawLength = response.header("Content-Length") ?: return null
        if (!rawLength.matches(Regex("[0-9]+"))) return null
        val length = rawLength.toLongOrNull() ?: return null
        if (response.code == 206) {
            val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
                .matchEntire(response.header("Content-Range").orEmpty()) ?: return null
            val start = range.groupValues[1].toLongOrNull() ?: return null
            val end = range.groupValues[2].toLongOrNull() ?: return null
            val total = range.groupValues[3].toLongOrNull() ?: return null
            if (start != 0L || end !in 0L..1023L || end >= total || length != end + 1L) return null
        }
        return minOf(length, 1024L).toInt()
    }
    suspend fun probe(url: String, headers: Map<String, String>): V2StreamProbeResult = suspendCancellableCoroutine { continuation ->
        val request = try {
            Request.Builder().url(url).apply {
                headers.forEach { (key, value) ->
                    // 设备认证与生产播放器一致；Bearer/Cookie 永不向媒体主机传递。
                    if (!key.equals("Authorization", true) && !key.equals("Cookie", true) &&
                        !key.equals("Proxy-Authorization", true) && !key.equals("Range", true)) header(key, value)
                }
                header("Range", "bytes=0-1023")
                header("Accept-Encoding", "identity")
            }.build()
        } catch (_: Exception) {
            continuation.resume(V2StreamProbeResult(V2DiagnosticCode.INVALID_PLAYBACK))
            return@suspendCancellableCoroutine
        }
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(V2StreamProbeResult(V2DiagnosticCode.NETWORK))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        val expectedLength = expectedReadLength(it)
                        val code = when (it.code) {
                            401 -> V2DiagnosticCode.UNAUTHORIZED
                            403 -> V2DiagnosticCode.FORBIDDEN
                            404 -> V2DiagnosticCode.NOT_FOUND
                            in 500..599 -> V2DiagnosticCode.UPSTREAM_ERROR
                            in 300..399 -> V2DiagnosticCode.REDIRECT
                            200, 206 -> null
                            else -> V2DiagnosticCode.HTTP_ERROR
                        }
                        if (code != null) V2StreamProbeResult(code, it.code)
                        else if (expectedLength == null) V2StreamProbeResult(V2DiagnosticCode.INVALID_PLAYBACK, it.code)
                        else if (it.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase().let { type ->
                            !type.startsWith("video/") && type != "application/octet-stream"
                        }) V2StreamProbeResult(V2DiagnosticCode.NON_VIDEO, it.code)
                        else {
                            val source = it.body?.source()
                            val buffer = ByteArray(1024)
                            var count = 0
                            while (count < expectedLength && continuation.isActive) {
                                val read = source?.read(buffer, count, expectedLength - count) ?: -1
                                if (read < 0) break
                                count += read
                            }
                            V2StreamProbeResult(when {
                                count != expectedLength -> V2DiagnosticCode.INVALID_PLAYBACK
                                count > 0 -> V2DiagnosticCode.READABLE
                                else -> V2DiagnosticCode.EMPTY
                            }, it.code, count)
                        }
                    }
                } catch (_: IOException) { V2StreamProbeResult(V2DiagnosticCode.NETWORK) }
                catch (_: Exception) { V2StreamProbeResult(V2DiagnosticCode.INVALID_PLAYBACK) }
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }
}

@Singleton
class V2PlaybackDiagnostic internal constructor(
    private val session: suspend () -> V2ServerSession,
    private val currentBase: suspend () -> String,
    private val apiFactory: ApiFactory,
    private val statusStore: V2ServerStatusStore,
    private val resolver: V2PlaybackResolver,
    private val probe: V2DirectStreamProbe = V2DirectStreamProbe(),
) {
    @Inject constructor(bootstrap: V2ServerSessionBootstrap, profiles: ServerProfileStore, apiFactory: ApiFactory,
        statusStore: V2ServerStatusStore, resolver: V2PlaybackResolver) : this(
        session = { bootstrap.restore() }, currentBase = { profiles.current().baseUrl },
        apiFactory = apiFactory, statusStore = statusStore, resolver = resolver,
    )

    suspend fun run(): V2PlaybackDiagnosticReport {
        var stage = V2DiagnosticStage.HEALTH
        var report = V2PlaybackDiagnosticReport(stage, V2StreamProbeResult(V2DiagnosticCode.SERVER_ERROR))
        val restored = try { session() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return report }
        suspend fun sameServer() {
            if (currentBase() != restored.baseUrl) throw CancellationException("服务器已切换")
        }
        try {
            if (!restored.configured) return report.copy(phone = V2StreamProbeResult(V2DiagnosticCode.UNCONFIGURED))
            val api = apiFactory.create(restored.baseUrl)
            val health = unwrap(apiFactory.create(restored.baseUrl, authenticated = false).health())
            sameServer()
            if (health.api_contract < REQUIRED_SERVER_API_CONTRACT) {
                statusStore.recordIncompatible(health.version, health.api_contract)
                return report.copy(phone = V2StreamProbeResult(V2DiagnosticCode.INCOMPATIBLE))
            }
            if (!restored.paired) return report.copy(phone = V2StreamProbeResult(V2DiagnosticCode.UNPAIRED))
            statusStore.recordCompatible(health.version, health.api_contract, health.capabilities)
            V2ServerAccessGuard(statusStore).requireBusinessAccess()
            stage = V2DiagnosticStage.READINESS
            val readiness = unwrap(api.playbackReadiness())
            sameServer()
            report = report.copy(stage = stage, serverReachable = readiness.jellyfin_server_reachable,
                serverStreamStatus = safeStreamStatus(readiness.direct_stream.status),
                credentialStatus = if (readiness.device_playback_key_status == "valid") "有效" else "未确认或不可用",
                addressSource = when (readiness.client_stream_source) {
                    "configured_client_url", "client_url" -> "配置的客户端地址"
                    "request_host_fallback" -> "请求主机回退"
                    else -> "未确认"
                }, authoritative = readiness.client_stream_authoritative,
                destination = destination(readiness.client_stream_base))
            val readinessFailure = when {
                readiness.client_address_status == "invalid" -> V2DiagnosticCode.INVALID_PLAYBACK
                !readiness.jellyfin_server_reachable -> V2DiagnosticCode.SERVER_UNREACHABLE
                readiness.direct_stream.status == "auth_failed" -> V2DiagnosticCode.UNAUTHORIZED
                readiness.direct_stream.status == "forbidden" -> V2DiagnosticCode.FORBIDDEN
                readiness.direct_stream.status == "not_found" -> V2DiagnosticCode.NOT_FOUND
                readiness.direct_stream.status == "redirect" -> V2DiagnosticCode.REDIRECT
                readiness.direct_stream.status == "unreachable" -> V2DiagnosticCode.NETWORK
                readiness.direct_stream.status == "server_error" -> V2DiagnosticCode.UPSTREAM_ERROR
                readiness.direct_stream.status == "http_error" -> V2DiagnosticCode.HTTP_ERROR
                readiness.direct_stream.status == "invalid_stream" -> V2DiagnosticCode.NON_VIDEO
                readiness.direct_stream.status == "media_unavailable" -> V2DiagnosticCode.MEDIA_UNAVAILABLE
                readiness.device_playback_key_status == "unavailable" -> V2DiagnosticCode.SERVER_ERROR
                else -> null
            }
            if (readinessFailure != null) return report.copy(phone = V2StreamProbeResult(readinessFailure))
            val id = readiness.media_id?.takeIf { it.isNotBlank() }
            if (!readiness.media_valid || id == null) return report.copy(phone = V2StreamProbeResult(V2DiagnosticCode.NO_MEDIA))
            stage = V2DiagnosticStage.PLAYBACK
            V2ServerAccessGuard(statusStore).requireBusinessAccess()
            val playback = unwrap(api.playback(id))
            sameServer()
            if (!playback.media_type.equals("video", true)) return report.copy(stage = stage, phone = V2StreamProbeResult(V2DiagnosticCode.NON_VIDEO))
            val endpoint = resolver.resolve(playback, restored.baseUrl).direct
            report = report.copy(stage = V2DiagnosticStage.PHONE, destination = destination(endpoint.url))
            stage = V2DiagnosticStage.PHONE
            val result = probe.probe(endpoint.url, endpoint.headers)
            sameServer()
            return report.copy(phone = result)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            sameServer()
            if (stage != V2DiagnosticStage.PHONE) statusStore.onRequestFailure(error)
            val code = when (error) {
                is HttpException -> when (error.code()) {
                    401 -> V2DiagnosticCode.UNAUTHORIZED
                    403 -> V2DiagnosticCode.FORBIDDEN
                    404 -> V2DiagnosticCode.NOT_FOUND
                    in 500..599 -> V2DiagnosticCode.UPSTREAM_ERROR
                    else -> V2DiagnosticCode.HTTP_ERROR
                }
                is IOException -> V2DiagnosticCode.NETWORK
                is ServerIncompatibleException -> V2DiagnosticCode.INCOMPATIBLE
                is IllegalArgumentException -> V2DiagnosticCode.INVALID_PLAYBACK
                else -> V2DiagnosticCode.SERVER_ERROR
            }
            return report.copy(stage = stage, phone = V2StreamProbeResult(code))
        }
    }
    private fun <T> unwrap(envelope: Envelope<T>): T {
        if (!envelope.success) throw IllegalStateException("服务器返回错误")
        return envelope.data ?: throw IllegalStateException("服务器返回空数据")
    }
    private fun destination(url: String): String = runCatching {
        val uri = URI(url)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) ""
        else "${uri.host}:${if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80}"
    }.getOrDefault("")

    private fun safeStreamStatus(status: String): String = when (status) {
        "readable" -> "视频流可读"
        "auth_failed", "unauthorized" -> "认证失败"
        "forbidden" -> "权限不足"
        "not_found" -> "媒体不存在"
        "redirect" -> "播放地址发生跳转，已阻止"
        "network_error", "unreachable" -> "网络连接失败"
        "no_video", "no_media" -> "没有视频"
        "invalid_stream", "non_video" -> "返回内容不是视频"
        "media_unavailable" -> "媒体已不可用"
        "server_error" -> "服务错误"
        "http_error" -> "HTTP 请求失败"
        "not_checked", "not_tested" -> "未检查"
        else -> "未确认或不可用"
    }
}
