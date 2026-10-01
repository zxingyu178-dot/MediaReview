package com.mediareview.app.feature.v2.organize.duplicates

/**
 * 重复扫描的 UI 辅助常量（Stage 8C §28/§29）。
 *
 * 状态机（服务端权威，客户端只读展示）：
 * `pending → running → succeeded / failed / cancelled`，可 `running ↔ paused`。
 * 只在 [SCAN_POLLING_STATUSES] 内轮询，切后台/离开页面停止轮询，重进先 GET /duplicates/status。
 */
internal object DuplicateScanUi {

    /** 建议 1.5 ~ 2 秒（任务书 §29）。 */
    const val POLL_INTERVAL_MS = 1_500L

    /** 需要轮询的状态（终态一律停止轮询）。 */
    val POLLING_STATUSES = setOf("pending", "running")

    /** 扫描状态 → 用户可见中文。 */
    fun statusLabel(status: String?): String = when (status) {
        "pending" -> "等待开始"
        "running" -> "扫描中"
        "paused" -> "已暂停"
        "succeeded" -> "扫描完成"
        "failed" -> "扫描失败"
        "cancelled" -> "已取消"
        else -> "尚未扫描"
    }
}