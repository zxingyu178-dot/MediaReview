package com.mediareview.app.feature.v2.review.data

import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.data.V2DataModeStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 批阅会话数据源路由（与 [com.mediareview.app.feature.v2.data.V2MediaRepositoryRouter] 同构）：
 * UI / ViewModel 只依赖 [V2ReviewSessionRepository]，具体实现按运行时模式委托：
 *
 * - DEMO   → [DemoReviewSessionRepository]（本地未审队列，无分页）
 * - SERVER → [V2ServerReviewSessionRepository]（真实 Review Session + 分页 + seen/position）
 *
 * 路由器本身不持状态；会话窗口状态在各自实现内部（切换数据源时旧窗口自然被丢弃，
 * ViewModel 会重新 [enterSession]）。
 */
@Singleton
class V2ReviewSessionRepositoryRouter @Inject constructor(
    private val demo: DemoReviewSessionRepository,
    private val server: V2ServerReviewSessionRepository,
    private val modeStore: V2DataModeStore,
) : V2ReviewSessionRepository {

    private fun active(): V2ReviewSessionRepository =
        if (modeStore.mode.value == V2DataMode.SERVER) server else demo

    override suspend fun enterSession(forceNew: Boolean): ReviewSessionOpen =
        active().enterSession(forceNew)

    override suspend fun loadNextPage(): ReviewQueuePageResult? = active().loadNextPage()

    override suspend fun loadPrevPage(): ReviewQueuePageResult? = active().loadPrevPage()

    override suspend fun markSeen(mediaId: String): Boolean = active().markSeen(mediaId)

    override suspend fun savePosition(absoluteIndex: Int) = active().savePosition(absoluteIndex)

    override suspend fun completeSession(): Boolean = active().completeSession()

    override suspend fun restart(): ReviewSessionOpen = active().restart()

    override suspend fun restartAll(): ReviewSessionOpen = active().restartAll()
}