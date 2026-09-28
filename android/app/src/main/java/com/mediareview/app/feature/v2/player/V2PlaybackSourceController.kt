package com.mediareview.app.feature.v2.player

import com.mediareview.app.feature.v2.model.V2PlaybackEndpoint
import com.mediareview.app.feature.v2.model.V2PlaybackSource
import com.mediareview.app.feature.v2.model.V2PlaybackStage

/**
 * 播放源控制器（Stage 8B §6~§9）：完整播放器与批阅模式**共用同一套播放语义**的纯逻辑，
 * 不含协程、不含 Android 依赖，可由 JVM 单测直接驱动。
 *
 * 1. **Source Identity（§7）**：每次切换媒体都会自增 [token] 并立即清空 [source]、
 *    回到 [V2PlaybackStage.DIRECT]。所有异步事件（resolve 成功 / resolve 失败 / 内核报错 /
 *    HLS 回退）在写入 UI 之前必须携带令牌校验：过期事件一律
 *    [V2PlaybackDecision.Ignore] —— 旧媒体的迟到错误绝不允许污染新媒体的界面。
 * 2. **Direct → 一次 HLS → Error（§31）**：只允许回退一次，禁止无限切换。
 * 3. **retry 必须真正回 Direct（§6）**：重试 = 对当前媒体重新 [moveTo]（作废旧令牌 +
 *    清空旧 source + 回到 Direct），不允许从上一次的 HLS 状态继续。
 * 4. **P1 预取只留一条（§9）**：单槽预取，后来者覆盖前者；取值要求 mediaId 精确匹配，
 *    因此快速 N → N+1 → N+2 时旧预取自然作废，不会让迟到结果切回旧媒体。
 */
class V2PlaybackSourceController {

    /** 当前媒体 id（source identity 的一部分）。 */
    var activeMediaId: String = ""
        private set

    /** 当前媒体对应的源身份令牌：只有等于最新令牌的事件才允许写入状态。 */
    var token: Int = 0
        private set

    /** 当前播放阶段（Direct / HLS 回退）。 */
    var stage: V2PlaybackStage = V2PlaybackStage.DIRECT
        private set

    /** 当前媒体已解析的播放源；切换媒体后立即为 null（不保留上一视频的 Source，§8）。 */
    var source: V2PlaybackSource? = null
        private set

    /** 预取的"下一条"mediaId（单槽）。 */
    private var prefetchedMediaId: String? = null

    /** 预取的"下一条"播放源（单槽）。 */
    private var prefetchedSource: V2PlaybackSource? = null

    /**
     * 切换到指定媒体（或对当前媒体重新开始）：立即清空 source、回到 Direct、作废旧令牌。
     *
     * @return 本次切换的令牌，必须在异步结果回调时原样带回。
     */
    fun moveTo(mediaId: String): Int {
        token += 1
        activeMediaId = mediaId
        source = null
        stage = V2PlaybackStage.DIRECT
        return token
    }

    /** resolve 成功：只有最新令牌才允许生效并产出播放决策。 */
    fun onResolved(eventToken: Int, resolved: V2PlaybackSource): V2PlaybackDecision {
        if (eventToken != token) return V2PlaybackDecision.Ignore
        source = resolved
        val endpoint = resolved.endpointFor(stage)
            ?: return V2PlaybackDecision.Fail("服务器未提供可用的播放地址")
        return V2PlaybackDecision.Play(resolved.mediaId, stage, endpoint)
    }

    /** resolve 抛错（网络 / 合同错误）：只有最新令牌才允许进入失败态。 */
    fun onResolveFailed(eventToken: Int, message: String?): V2PlaybackDecision =
        if (eventToken != token) {
            V2PlaybackDecision.Ignore
        } else {
            V2PlaybackDecision.Fail(message ?: "无法获取播放地址")
        }

    /**
     * 播放内核报错（GSY Error）：
     * - [failingMediaId] 必须等于 [activeMediaId]（否则是旧媒体的迟到错误 → Ignore）；
     * - Direct 失败 → 用同一个已解析源切 HLS（不重新请求）；
     * - HLS 再失败 → 终态失败（不再回退）。
     */
    fun onPlaybackFailed(eventToken: Int, failingMediaId: String): V2PlaybackDecision {
        if (eventToken != token || failingMediaId != activeMediaId) return V2PlaybackDecision.Ignore
        val current = source ?: return V2PlaybackDecision.Fail(null)
        val next = current.nextStageAfterFailure(stage)
        val endpoint = next?.let { current.endpointFor(it) }
        if (next == null || endpoint == null) return V2PlaybackDecision.Fail(null)
        stage = next
        return V2PlaybackDecision.Play(current.mediaId, next, endpoint)
    }

    /** 记录"下一条"的预取源（单槽；不限制 Map 增长，也不允许整队列解析）。 */
    fun prefetch(mediaId: String, resolved: V2PlaybackSource) {
        prefetchedMediaId = mediaId
        prefetchedSource = resolved
    }

    /** 取预取源：mediaId 不匹配（已被更新的预取覆盖）时视为未命中并返回 null。 */
    fun takePrefetched(mediaId: String): V2PlaybackSource? {
        if (prefetchedMediaId != mediaId) return null
        val hit = prefetchedSource
        prefetchedMediaId = null
        prefetchedSource = null
        return hit
    }

    /**
     * 查看预取槽（不消费）：用于避免对同一条媒体重复发起预取请求。
     *
     * @return mediaId 命中时返回已解析源，否则 null。
     */
    fun peekPrefetched(mediaId: String): V2PlaybackSource? =
        prefetchedSource?.takeIf { prefetchedMediaId == mediaId }

    /** 清空预取槽（退出播放器 / 重建上下文）。 */
    fun clearPrefetch() {
        prefetchedMediaId = null
        prefetchedSource = null
    }
}

/**
 * 播放决策：控制器对每一次事件给出的唯一结论，调用方据此写 UI 状态。
 *
 * - [Play]：可以播放该端点（URL + headers 成对切换）；
 * - [Fail]：进入失败态；[Fail.message] 为 null 表示"沿用调用方默认文案"；
 * - [Ignore]：事件已过期或被判定为串媒体，**不得写任何 UI 状态**。
 */
sealed interface V2PlaybackDecision {
    data class Play(
        val mediaId: String,
        val stage: V2PlaybackStage,
        val endpoint: V2PlaybackEndpoint,
    ) : V2PlaybackDecision

    data class Fail(val message: String?) : V2PlaybackDecision

    data object Ignore : V2PlaybackDecision
}