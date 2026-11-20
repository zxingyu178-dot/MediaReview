package com.mediareview.app.feature.v2.nsfw

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 仅失败时退避；健康空响应仍保持前台追新周期，回焦恢复基础间隔。 */
internal class NsfwPollBackoff {
    private var idleRounds = 0
    fun interval(baseMs: Long): Long = (baseMs.coerceIn(1L, 60_000L) * (1L shl idleRounds)).coerceAtMost(60_000L)
    fun result(success: Boolean) { idleRounds = if (success) 0 else (idleRounds + 1).coerceAtMost(2) }
    fun reset() { idleRounds = 0 }
}

/** 保留连续浏览窗口中仍需要的任务，不重复启动已经预热的请求。 */
internal class NsfwImagePrefetcher(private val scope: CoroutineScope, private val load: suspend (String) -> Unit) {
    private val jobs = mutableMapOf<String, Job>()

    fun update(urls: List<String>, visibleUrl: String? = null) {
        val targets = urls.distinct().take(3).toMutableSet()
        if (visibleUrl != null && visibleUrl in jobs) targets += visibleUrl
        (jobs.keys - targets).forEach { jobs.remove(it)?.cancel() }
        targets.forEach { url ->
            if (url !in jobs) jobs[url] = scope.launch {
                try { load(url) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* 进入当前页时正常加载，失败可重试。 */ }
            }
        }
    }

    fun stop() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }
}

/** 不把原始凭据写入缓存键；URL含origin，因此不同服务器和凭据不会混读。 */
internal fun nsfwImageCacheKey(url: String, token: String): String? {
    if (token.isBlank()) return null
    return MessageDigest.getInstance("SHA-256").digest((token + "\n" + url).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
