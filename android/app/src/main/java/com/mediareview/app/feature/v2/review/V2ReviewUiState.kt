package com.mediareview.app.feature.v2.review

import com.mediareview.app.feature.v2.review.data.ReviewQueueItemUi
import com.mediareview.app.feature.v2.review.data.ReviewQueueWindow

/**
 * 批阅页状态机（Stage 8B §23）。
 *
 * 旧实现的 `serverModeUnsupported` 占位状态在本阶段**彻底删除**：
 * Server 模式现在走真实 Review Session（创建 / 恢复 / 分页 / seen / position / complete）。
 */
sealed interface V2ReviewUiState {

    /** 尚未进入（首帧）。 */
    data object Idle : V2ReviewUiState

    /** 正在恢复 / 创建批阅会话（`latest` → 必要时创建）。 */
    data object LoadingSession : V2ReviewUiState

    /** 会话就绪，正在加载 current_index 所在的那一页队列。 */
    data object LoadingQueue : V2ReviewUiState

    /** 批阅中：队列窗口 + 绝对进度。 */
    data class Ready(
        val sessionId: String,
        val totalCount: Int,
        /** 当前 settled 页的**绝对索引**（Server position 永远提交绝对位置，§24）。 */
        val absoluteCurrentIndex: Int,
        val window: ReviewQueueWindow,
        /**
         * 已批阅计数：服务端 seen_count 起点 + 本次会话新确认条数（同一条只计一次）。
         * 仅用于展示/诊断；长期状态以服务端为准。
         */
        val seenCount: Int,
        val loadingNext: Boolean = false,
        val loadingPrev: Boolean = false,
    ) : V2ReviewUiState {
        val items: List<ReviewQueueItemUi> get() = window.items

        /** pager 用的本地索引（绝对索引在当前窗口内的位置）。 */
        val localCurrentIndex: Int
            get() = window.localIndexOf(absoluteCurrentIndex) ?: 0
    }

    /** 没有可批阅的媒体（空队列完成页）。 */
    data object Empty : V2ReviewUiState

    /** 队列已批阅完成（服务端 complete 已确认，§40）。 */
    data class Complete(val totalCount: Int) : V2ReviewUiState

    /** 进入 / 分页失败：必须显示"重新尝试"，绝不自动新建会话（§17）。 */
    data class Error(val message: String) : V2ReviewUiState
}

/**
 * 批阅页一次性提示（Snackbar）：
 * [undoMediaId] 非空时表示这条提示可以"撤销待删除"（§36）。
 */
data class ReviewMessage(
    val text: String,
    val undoMediaId: String? = null,
)