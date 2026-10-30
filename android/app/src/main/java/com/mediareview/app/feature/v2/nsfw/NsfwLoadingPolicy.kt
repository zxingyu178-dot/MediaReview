package com.mediareview.app.feature.v2.nsfw

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 无新事件或网络失败时降频；活动/回焦恢复基础间隔。 */
internal class NsfwPollBackoff {
    private var idleRounds = 0
    fun interval(baseMs: Long): Long = (baseMs.coerceIn(1L, 60_000L) * (1L shl idleRounds)).coerceAtMost(60_000L)
    fun result(changed: Boolean) { idleRounds = if (changed) 0 else (idleRounds + 1).coerceAtMost(2) }
    fun reset() { idleRounds = 0 }
}

/** 只保留当前意图对应的一个可取消预取；失败不影响可见图片。 */
internal class NsfwImagePrefetcher(private val scope: CoroutineScope, private val load: suspend (String) -> Unit) {
    private var target: String? = null
    private var job: Job? = null
    fun update(url: String?) {
        if (url == target) return
        stop()
        target = url ?: return
        job = scope.launch {
            try { load(url) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 预取失败由后续显式加载重试，不自动抢占资源。 */ }
        }
    }
    fun stop() { job?.cancel(); job = null; target = null }
}

/** 不把原始凭据写入缓存键；URL含origin，因此不同服务器和凭据不会混读。 */
internal fun nsfwImageCacheKey(url: String, token: String): String? {
    if (token.isBlank()) return null
    return MessageDigest.getInstance("SHA-256").digest((token + "\n" + url).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
