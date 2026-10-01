package com.mediareview.app.feature.v2.organize

import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.organize.data.DeleteQueueSummary
import com.mediareview.app.feature.v2.organize.data.DuplicateSummary
import com.mediareview.app.feature.v2.organize.data.LibrarySelectionSummary
import com.mediareview.app.feature.v2.organize.data.ReviewProgressSummary

/**
 * 整理首页单张卡片的独立状态（Stage 8C §8/§9）。
 *
 * 四张卡各自请求、各自进入 Loading / Ready / Error —— 一张卡失败绝不让整页白屏，
 * 也绝不把接口失败伪装成 0 项（§45：宁可显示"加载失败 · 点击重试"）。
 */
sealed interface OrganizeCard<out T> {

    data object Loading : OrganizeCard<Nothing>

    data class Ready<T>(val data: T) : OrganizeCard<T>

    data class Error(val message: String) : OrganizeCard<Nothing>

    /** Demo 模式没有该能力（重复媒体 / 媒体库管理）：明确展示「仅服务器模式可用」。 */
    data class Unavailable(val message: String) : OrganizeCard<Nothing>
}

/** 整理首页状态：四张卡互不影响。 */
data class OrganizeUiState(
    val deleteCard: OrganizeCard<DeleteQueueSummary> = OrganizeCard.Loading,
    val duplicateCard: OrganizeCard<DuplicateSummary> = OrganizeCard.Loading,
    /** Ready(null) = 服务端明确没有 active 批阅会话（合法状态，不是失败）。 */
    val reviewCard: OrganizeCard<ReviewProgressSummary?> = OrganizeCard.Loading,
    val libraryCard: OrganizeCard<LibrarySelectionSummary> = OrganizeCard.Loading,
    /** 当前数据源模式（卡片文案与"仅服务器模式可用"提示用）。 */
    val dataMode: V2DataMode = V2DataMode.DEMO,
)

/** 大小格式化（B/KB/MB/GB），整理页各卡片与列表共用的唯一实现。 */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> "%.1f GB".format(gb)
        mb >= 1 -> "%.1f MB".format(mb)
        kb >= 1 -> "%.0f KB".format(kb)
        else -> "$bytes B"
    }
}