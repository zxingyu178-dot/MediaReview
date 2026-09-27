package com.mediareview.app.feature.v2.perf

import android.util.Log
import com.mediareview.app.BuildConfig
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Stage 8A.1 加载管线性能打点。
 *
 * 约定：
 * - 统一 tag `MR_PERF`，事件名固定，便于从 logcat 机械提取耗时做前后对比；
 * - **只用单调时钟**（[System.nanoTime]，monotonic），禁止用 wall clock 计算耗时；
 * - 只在 Debug 构建输出，Release 静默；
 * - 只记录耗时与计数，绝不记录 Token / 凭据 / 文件路径。
 *
 * 阶段 8A.1.1 收口（时间源修正）：
 * - [onProcessStart] 由 Application.onCreate **最早**调用，作为可解释的进程起点；
 *   不再把本 object 的类初始化时间当成"冷启动开始"；
 * - 测量按 **独立会话** 组织（Home / Paging / Viewer / Player），
 *   每条指标相对自己会话的起点，不再全部相对 home_enter；
 * - 每次打开 Viewer / Player / 分页都会**新建会话**，
 *   上一条媒体的 claim 状态不会污染下一条。
 */
object V2Perf {

    const val TAG = "MR_PERF"

    /** 需要统计"首批可见封面就绪"的条数（一屏约 8~10 张）。 */
    const val VISIBLE_COVER_TARGET = 8

    private val appStartNanos = AtomicLong(0L)

    private val homeSession = AtomicReference<TimingSession?>(null)
    private val pagingSession = AtomicReference<TimingSession?>(null)
    private val viewerSession = AtomicReference<TimingSession?>(null)
    private val playerSession = AtomicReference<TimingSession?>(null)

    private val coverReadyIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    // ---------- 进程起点 ----------

    /** 由 Application.onCreate 第一行调用。 */
    fun onProcessStart() {
        appStartNanos.compareAndSet(0L, System.nanoTime())
    }

    /** 进程起点（纳秒）；未初始化时返回 0。 */
    fun appStartNanos(): Long = appStartNanos.get()

    fun now(): Long = System.nanoTime()

    fun costMs(fromNanos: Long): Long = (System.nanoTime() - fromNanos) / 1_000_000

    internal fun log(event: String, baseNanos: Long?, details: (() -> String)? = null) {
        if (!BuildConfig.DEBUG) return
        val extra = buildString {
            if (baseNanos != null && baseNanos > 0L) {
                append("duration_ms=").append(costMs(baseNanos))
            }
            details?.invoke()?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append(' ')
                append(it)
            }
        }
        Log.i(TAG, "$event $extra".trim())
    }

    // ---------- 会话 ----------

    /** APP: app_start → home_enter。 */
    fun openHome(): TimingSession {
        val session = TimingSession(name = "home", baseNanos = appStartNanos())
        homeSession.set(session)
        coverReadyIds.clear()
        log("home_enter", appStartNanos().takeIf { it > 0L })
        return session
    }

    fun home(): TimingSession? = homeSession.get()

    /** PAGING: 每次请求新页时新建会话。 */
    fun openPaging(page: Int): TimingSession {
        val session = TimingSession(name = "paging", baseNanos = 0L)
        pagingSession.set(session)
        session.mark("page_request", { "page=$page" })
        return session
    }

    fun paging(): TimingSession? = pagingSession.get()

    /** VIEWER: viewer_open → preview_visible → full_image_ready（每次打开重建）。 */
    fun openViewer(): TimingSession {
        val session = TimingSession(name = "viewer", baseNanos = 0L)
        viewerSession.set(session)
        log("viewer_open", null)
        return session
    }

    fun viewer(): TimingSession? = viewerSession.get()

    /** PLAYER: player_open → playback_info_ready → first_frame（每次播放重建）。 */
    fun openPlayer(mediaId: String): TimingSession {
        val session = TimingSession(name = "player", baseNanos = 0L)
        playerSession.set(session)
        log("player_open", null, { "media=$mediaId" })
        return session
    }

    fun player(): TimingSession? = playerSession.get()

    // ---------- 封面（归属 home 会话） ----------

    /** 封面请求开始（仅首次记录 first_cover_request）。 */
    fun coverRequest(mediaId: String) {
        homeSession.get()?.markOnce(
            key = "first_cover_request",
            event = "first_cover_request",
            details = { "media=$mediaId" },
        )
    }

    /** 封面加载成功：统计首批可见封面就绪时间。 */
    fun coverSuccess(mediaId: String) {
        val session = homeSession.get() ?: return
        val added = coverReadyIds.add(mediaId)
        if (!added) return
        session.markOnce(
            key = "first_cover_success",
            event = "first_cover_success",
            details = { "media=$mediaId" },
        )
        if (coverReadyIds.size >= VISIBLE_COVER_TARGET) {
            session.markOnce(
                key = "visible_8_covers_ready",
                event = "visible_8_covers_ready",
                details = { "covers=${coverReadyIds.size}" },
            )
        }
    }
}

/**
 * 一次测量会话：所有 [mark] 都相对本会话起点 [baseNanos]。
 *
 * [baseNanos] 为 0 表示"以会话创建时刻为起点"（Paging / Viewer / Player）；
 * Home 会话传入进程起点，因此 `home_enter` / `first_media_render` 等相对 app_start。
 */
class TimingSession internal constructor(
    internal val name: String,
    internal val baseNanos: Long,
) {
    private val createdAt = System.nanoTime()
    private val reported: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private val base: Long get() = if (baseNanos > 0L) baseNanos else createdAt

    /** 每次都记录。 */
    fun mark(event: String, details: (() -> String)? = null) {
        V2Perf.log(event, base, details)
    }

    /** 同一 key 只记录一次（替代原先的全局 claim flag）。 */
    fun markOnce(key: String, event: String, details: (() -> String)? = null) {
        if (!reported.add(key)) return
        V2Perf.log(event, base, details)
    }

    fun elapsedMs(): Long = V2Perf.costMs(base)
}