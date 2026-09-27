package com.mediareview.app.feature.v2.perf

import android.util.Log
import com.mediareview.app.BuildConfig
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/**
 * Stage 8A.1 加载管线性能打点。
 *
 * 约定（与任务文档 §3 一致）：
 * - 统一 tag `MR_PERF`，事件名固定，便于从 logcat 机械提取耗时做前后对比；
 * - **只用单调时钟**（[System.nanoTime]，monotonic），禁止用 wall clock 计算耗时；
 * - 只在 Debug 构建输出，Release 静默（不污染线上日志）；
 * - 只记录耗时与计数，绝不记录 Token / 凭据 / 文件路径。
 */
object V2Perf {

    const val TAG = "MR_PERF"

    /** 需要统计"首批可见封面就绪"的条数（一屏约 8~10 张）。 */
    const val VISIBLE_COVER_TARGET = 8

    private val processStartNanos = AtomicLong(System.nanoTime())
    private val homeEnterNanos = AtomicLong(0L)
    private val coverReadyIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val coversEightReported = AtomicLong(0L)
    private val firstCoverReported = AtomicLong(0L)
    private val firstMediaReported = AtomicLong(0L)
    private val firstMediaRenderReported = AtomicLong(0L)
    private val firstPageTwoReported = AtomicLong(0L)

    fun now(): Long = System.nanoTime()

    fun costMs(fromNanos: Long): Long = (System.nanoTime() - fromNanos) / 1_000_000

    /** 记录一条事件；[sinceNanos] 非空时附带 duration_ms。 */
    fun mark(event: String, sinceNanos: Long? = null, details: (() -> String)? = null) {
        if (!BuildConfig.DEBUG) return
        val extra = buildString {
            if (sinceNanos != null) append("duration_ms=").append(costMs(sinceNanos))
            details?.invoke()?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append(' ')
                append(it)
            }
        }
        Log.i(TAG, "$event $extra".trim())
    }

    /** 首页进入：重置全部首屏统计，并以进程启动为起点记录冷启动耗时。 */
    fun homeEnter() {
        homeEnterNanos.set(now())
        coverReadyIds.clear()
        coversEightReported.set(0L)
        firstCoverReported.set(0L)
        firstMediaReported.set(0L)
        firstMediaRenderReported.set(0L)
        firstPageTwoReported.set(0L)
        mark("home_enter", processStartNanos.get())
    }

    fun homeEnterNanos(): Long = homeEnterNanos.get()

    /** 返回 true 表示这是本次首页进入后的第一次媒体请求。 */
    fun claimFirstMediaRequest(): Boolean = firstMediaReported.compareAndSet(0L, 1L)

    fun claimFirstMediaRender(): Boolean = firstMediaRenderReported.compareAndSet(0L, 1L)

    fun claimFirstPageTwo(): Boolean = firstPageTwoReported.compareAndSet(0L, 1L)

    // ---- Viewer ----

    private val viewerPreviewReported = AtomicLong(0L)
    private val viewerFullReported = AtomicLong(0L)

    /** 打开 Viewer：重置本次会话的三个打点（preview / full 各只记首次）。 */
    fun viewerOpen() {
        viewerPreviewReported.set(0L)
        viewerFullReported.set(0L)
        mark("viewer_open", homeEnterNanos().takeIf { it > 0 })
    }

    fun claimViewerPreview(): Boolean = viewerPreviewReported.compareAndSet(0L, 1L)

    fun claimViewerFull(): Boolean = viewerFullReported.compareAndSet(0L, 1L)

    // ---- Player ----

    private val playbackInfoReported = AtomicLong(0L)
    private val firstFrameReported = AtomicLong(0L)

    /** 打开播放器：重置本次播放的打点。 */
    fun playerOpen(mediaId: String) {
        playbackInfoReported.set(0L)
        firstFrameReported.set(0L)
        mark("player_open", homeEnterNanos().takeIf { it > 0 }, { "media=$mediaId" })
    }

    fun claimPlaybackInfo(): Boolean = playbackInfoReported.compareAndSet(0L, 1L)

    fun claimFirstFrame(): Boolean = firstFrameReported.compareAndSet(0L, 1L)

    /** 封面请求开始（同一 media 只记一次首张）。 */
    fun coverRequest(mediaId: String) {
        if (firstCoverReported.compareAndSet(0L, 1L)) {
            mark("first_cover_request", homeEnterNanos().takeIf { it > 0L })
        }
    }

    /** 封面加载成功：统计首批可见封面就绪时间。 */
    fun coverSuccess(mediaId: String) {
        if (!BuildConfig.DEBUG) return
        val added = coverReadyIds.add(mediaId)
        if (!added) return
        if (coverReadyIds.size == 1) {
            mark("first_cover_success", homeEnterNanos().takeIf { it > 0L }, { "media=$mediaId" })
        }
        if (coverReadyIds.size >= VISIBLE_COVER_TARGET &&
            coversEightReported.compareAndSet(0L, 1L)
        ) {
            mark(
                "visible_8_covers_ready",
                homeEnterNanos().takeIf { it > 0L },
                { "covers=${coverReadyIds.size}" },
            )
        }
    }
}