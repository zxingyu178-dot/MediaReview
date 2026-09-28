package com.mediareview.app.feature.v2.review

import com.mediareview.app.feature.v2.data.MediaRepository
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.model.V2Album
import com.mediareview.app.feature.v2.model.V2Folder
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2MediaPage
import com.mediareview.app.feature.v2.model.V2MediaQuery
import com.mediareview.app.feature.v2.model.V2MediaType
import com.mediareview.app.feature.v2.model.V2PlaybackEndpoint
import com.mediareview.app.feature.v2.model.V2PlaybackSource
import com.mediareview.app.feature.v2.model.V2SortSpec
import com.mediareview.app.feature.v2.model.V2SpriteManifest
import com.mediareview.app.feature.v2.review.data.ReviewQueueItemUi
import com.mediareview.app.feature.v2.review.data.ReviewQueuePageResult
import com.mediareview.app.feature.v2.review.data.ReviewQueueWindow
import com.mediareview.app.feature.v2.review.data.ReviewSeenResult
import com.mediareview.app.feature.v2.review.data.ReviewSessionInfo
import com.mediareview.app.feature.v2.review.data.ReviewSessionOpen
import com.mediareview.app.feature.v2.review.data.V2ReviewSessionRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 批阅会话 ViewModel 的 JVM 逻辑测试
 * （Stage 8B §17 / §21 / §24 / §26 / §28 / §33 / §37 / §39 / §40 / §47
 *   + Stage 8B.1 §13~§23）。
 *
 * 直接给 ViewModel 注入可控的 [V2ReviewSessionRepository]：
 * - 无 active → 新建；有 active → 恢复；latest 失败 → 不新建（§17）；
 * - 深位置恢复定位（§21）；
 * - seen 服务端确认制（§26）、**seenCount 完全采用服务端权威值（§13/§14）**、
 *   **in-flight 防重（§15）**；
 * - **position 序号化 latest-wins（§23）**；
 * - P0/P1 播放解析上限（§5/§33）、**P1 stale 身份校验（§22）**；
 * - 待删除 / 收藏的确认制（§34/§36）、**新分页收藏合并（§16）**；
 * - 完整播放器返回不重建会话（§37）、重新批阅 = 新建会话（§39）；
 * - **完成门槛 = atEnd + seen_count == total_count（§18）**、
 *   到末尾仍有未批阅不得完成（§20）、**complete single-flight（§21）**。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class V2ReviewViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---------- §17 会话进入 ----------

    @Test
    fun `没有active会话时进入会新建会话`() = runTest {
        val sessions = FakeSessions().apply {
            entryResult = readyOpen(sessionId = "new", total = 30, currentIndex = 0)
        }
        val vm = vm(sessions)

        vm.enterReview()

        assertEquals(listOf(false), sessions.enterForceNew)
        val state = vm.state.value as? V2ReviewUiState.Ready ?: error("期望 Ready，实际 ${vm.state.value}")
        assertEquals("new", state.sessionId)
        assertEquals(30, state.totalCount)
    }

    @Test
    fun `有active会话时恢复并定位到current_index所在页`() = runTest {
        val sessions = FakeSessions().apply {
            entryResult = readyOpen(
                sessionId = "active",
                total = 1000,
                currentIndex = 637,
                window = window(count = 50, firstPage = 13, total = 1000, startId = 601),
            )
        }
        val vm = vm(sessions)

        vm.enterReview()

        val state = vm.state.value as V2ReviewUiState.Ready
        assertEquals(637, state.absoluteCurrentIndex)
        assertEquals("pager 必须定位到 637 在窗口内的本地索引", 37, state.localCurrentIndex)
        assertEquals(600, state.window.baseIndex)
        assertEquals(13, state.window.firstLoadedPage)
    }

    @Test
    fun `latest失败时不新建会话且显示可重试错误`() = runTest {
        val sessions = FakeSessions().apply {
            entryResult = ReviewSessionOpen.Failed("无法恢复批阅")
        }
        val vm = vm(sessions)

        vm.enterReview()

        val state = vm.state.value
        assertTrue("必须是错误态（绝不自动新建会话）", state is V2ReviewUiState.Error)
        assertEquals(listOf(false), sessions.enterForceNew)
    }

    @Test
    fun `空队列进入显示空态`() = runTest {
        val sessions = FakeSessions().apply { entryResult = ReviewSessionOpen.Empty }
        val vm = vm(sessions)

        vm.enterReview()

        assertEquals(V2ReviewUiState.Empty, vm.state.value)
    }

    // ---------- §26 seen 服务端确认制 / §13 §14 seenCount 权威 ----------

    @Test
    fun `seen只有服务端确认成功才更新本地`() = runTest {
        val sessions = FakeSessions().apply {
            entryResult = readyOpen(total = 3)
        }
        val vm = vm(sessions)
        vm.enterReview()

        vm.markSeen(0)
        waitUntil("seen 确认") { vm.isSeen("v1") }

        assertEquals(listOf("v1"), sessions.seenCalls)
        val state = vm.state.value as V2ReviewUiState.Ready
        assertEquals("seenCount 必须来自服务端权威值", 1, state.seenCount)
    }

    @Test
    fun `seen失败不假成功`() = runTest {
        val sessions = FakeSessions().apply {
            entryResult = readyOpen(total = 3)
            failSeen = true
        }
        val vm = vm(sessions)
        vm.enterReview()

        vm.markSeen(0)
        waitUntil("seen 失败被调用") { sessions.seenCalls.isNotEmpty() }

        assertFalse("服务端未确认时必须保持未批阅", vm.isSeen("v1"))
        val state = vm.state.value as V2ReviewUiState.Ready
        assertEquals(0, state.seenCount)
    }

    @Test
    fun `seenCount完全采用服务端权威值不回看重复计数`() = runTest {
        // 恢复会话时服务端 seen_count = 40；用户回看已批阅过的媒体，服务端再次确认仍返回 40
        val sessions = FakeSessions().apply {
            entryResult = readyOpen(total = 50, sessionSeen = 40, window = window(count = 50, total = 50))
            seenResultOverride = ReviewSeenResult("", true, seenCount = 40, totalCount = 50)
        }
        val vm = vm(sessions)
        vm.enterReview()
        assertEquals(40, (vm.state.value as V2ReviewUiState.Ready).seenCount)

        vm.markSeen(0)
        waitUntil("服务端确认") { sessions.seenCalls.isNotEmpty() }

        val state = vm.state.value as V2ReviewUiState.Ready
        assertEquals("回看已 seen 的媒体不得把 40 变成 41（评审 §13）", 40, state.seenCount)
    }

    @Test
    fun `markSeen并发防重同一媒体只发一次`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val sessions = FakeSessions().apply {
            entryResult = readyOpen(total = 3)
            seenGate = gate
        }
        val vm = vm(sessions)
        vm.enterReview()

        vm.markSeen(0)
        vm.markSeen(0)
        waitUntil("第一次 seen 已发出") { sessions.seenCalls.size == 1 }
        gate.complete(Unit)
        waitUntil("seen 确认") { vm.isSeen("v1") }

        assertEquals("同一媒体并发只允许一次 POST（§15）", 1, sessions.seenCalls.size)
        // 已确认后再触发：不再发请求
        vm.markSeen(0)
        assertEquals(1, sessions.seenCalls.size)
    }

    // ---------- §23/§28 position latest-wins ----------

    @Test
    fun `position严格收敛到最新值迟到结果不覆盖`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val sessions = FakeSessions().apply {
            entryResult = readyOpen(total = 5, window = window(count = 5, total = 5))
            positionGate = gate
        }
        val vm = vm(sessions)
        vm.enterReview()

        vm.onPageSettled(0)
        vm.onPageSettled(1)
        gate.complete(Unit)
        waitUntil("位置写入完成") { sessions.positionCompleted.contains(1) }

        assertEquals("最后一次 settle 的位置必须最后到达服务端", 1, sessions.positionCompleted.last())
        assertEquals(1, (vm.state.value as V2ReviewUiState.Ready).absoluteCurrentIndex)
    }

    @Test
    fun `位置30到31到32时服务端最终保持32`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val sessions = FakeSessions().apply {
            entryResult = readyOpen(total = 100, window = window(count = 100, total = 100))
            positionGate = gate
        }
        val vm = vm(sessions)
        vm.enterReview()

        vm.onPageSettled(30)
        waitUntil("第一次位置请求发出") { sessions.positionStarted.isNotEmpty() }
        vm.onPageSettled(31)
        vm.onPageSettled(32)
        gate.complete(Unit)
        waitUntil("位置队列排空") { sessions.positionCompleted.contains(32) }

        assertEquals("Server 最终 position 必须是 32", 32, sessions.positionCompleted.last())
        assertEquals(32, (vm.state.value as V2ReviewUiState.Ready).absoluteCurrentIndex)
    }

    // ---------- §5/§33 播放解析上限 ----------

    @Test
    fun `进入后只解析当前项与下一条`() = runTest {
        val repo = FakeMediaRepository(mediaCount = 10)
        val sessions = FakeSessions().apply {
            entryResult = readyOpen(total = 10, window = window(count = 10, total = 10))
        }
        val vm = V2ReviewViewModel(repo, sessions)

        vm.enterReview()
        waitUntil("当前项解析完成") { (vm.playback.value as? com.mediareview.app.feature.v2.player.V2PlaybackUiState.Ready) != null }
        waitUntil("下一条预取完成") { repo.resolvedMediaIds.size >= 2 }

        assertTrue(
            "只允许 P0 当前 + P1 下一条（绝不批量 resolvePlayback），实际 ${repo.resolvedMediaIds}",
            repo.resolvedMediaIds.size <= 2,
        )
        assertEquals(listOf("v1", "v2"), repo.resolvedMediaIds)
    }

    @Test
    fun `切页后播放源切换为新页媒体`() = runTest {
        val repo = FakeMediaRepository(mediaCount = 10)
        val sessions = FakeSessions().apply { entryResult = readyOpen(total = 10, window = window(count = 10, total = 10)) }
        val vm = V2ReviewViewModel(repo, sessions)
        vm.enterReview()
        waitUntil("首条就绪") { playbackMediaId(vm) == "v1" }

        vm.onPageSettled(1)
        waitUntil("切页后新源就绪") { playbackMediaId(vm) == "v2" }

        assertEquals("v2", playbackMediaId(vm))
    }

    @Test
    fun `stale P1完成后不得覆盖新P1`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeMediaRepository(mediaCount = 10).apply {
            gateMediaId = "v2"
            mediaGate = gate
        }
        val sessions = FakeSessions().apply { entryResult = readyOpen(total = 10, window = window(count = 10, total = 10)) }
        val vm = V2ReviewViewModel(repo, sessions)

        vm.enterReview()
        waitUntil("首页 P1(v2) 请求已发出") { repo.resolvedMediaIds.contains("v2") }

        // 切到第 2 条：旧 P1 作废，新 P0 等同一个慢请求
        vm.onPageSettled(1)
        gate.complete(Unit)
        waitUntil("P0 v2 就绪") { playbackMediaId(vm) == "v2" }
        waitUntil("新 P1(v3) 预取完成") { repo.resolvedMediaIds.contains("v3") }

        // 切到第 3 条：必须命中 v3 预取（stale P1 不得把槽位覆盖回 v2，导致重复解析）
        vm.onPageSettled(2)
        waitUntil("P0 v3 就绪") { playbackMediaId(vm) == "v3" }

        assertEquals(
            "stale P1 不得覆盖新 P1：v3 必须只解析一次（命中预取槽）",
            1,
            repo.resolvedMediaIds.count { it == "v3" },
        )
    }

    // ---------- §34/§36 收藏与待删除确认制 / §16 跨页收藏 ----------

    @Test
    fun `待删除成功才更新且失败保持原状态`() = runTest {
        val repo = FakeMediaRepository(mediaCount = 3)
        val sessions = FakeSessions().apply { entryResult = readyOpen(total = 3) }
        val vm = V2ReviewViewModel(repo, sessions)
        vm.enterReview()

        vm.addPendingDelete("v1")
        waitUntil("待删除成功") { vm.isPendingDelete("v1") }

        vm.undoPendingDelete("v1")
        waitUntil("撤销成功") { !vm.isPendingDelete("v1") }

        // 服务端失败：UI 必须保持原状态
        repo.pendingDeleteResult = false
        vm.addPendingDelete("v1")
        waitUntil("待删除失败调用") { repo.pendingDeleteCalls.size >= 3 }
        assertFalse("失败不得假成功", vm.isPendingDelete("v1"))
    }

    @Test
    fun `收藏失败不改本地状态`() = runTest {
        val repo = FakeMediaRepository(mediaCount = 3)
        val sessions = FakeSessions().apply { entryResult = readyOpen(total = 3) }
        val vm = V2ReviewViewModel(repo, sessions)
        vm.enterReview()

        repo.favoriteResult = false
        vm.toggleFavorite("v1")
        waitUntil("收藏调用") { repo.favoriteCalls.isNotEmpty() }
        assertFalse(vm.isFavorite("v1"))

        repo.favoriteResult = true
        vm.toggleFavorite("v1")
        waitUntil("收藏成功") { vm.isFavorite("v1") }
    }

    @Test
    fun `新分页里已收藏的媒体必须并入本地收藏集合`() = runTest {
        val sessions = FakeSessions().apply {
            entryResult = readyOpen(total = 60, window = window(count = 50, firstPage = 1, total = 60))
        }
        val vm = vm(sessions)
        vm.enterReview()

        // 第 2 页带回来一条 favorite=true 的媒体（绝对索引 55）
        sessions.loadNextResult = ReviewQueuePageResult(
            window = window(count = 60, firstPage = 1, lastPage = 2, total = 60, favorites = setOf(55)),
        )
        vm.onPageSettled(49)
        waitUntil("下一页加载完成") { (vm.state.value as V2ReviewUiState.Ready).items.size == 60 }

        assertTrue(
            "第 2 页以后已收藏的视频不得显示成未收藏（§16）",
            vm.isFavorite("v56"),
        )
    }

    // ---------- §37 完整播放器返回 ----------

    @Test
    fun `完整播放器返回恢复原会话与原位置且不重建`() = runTest {
        val sessions = FakeSessions().apply { entryResult = readyOpen(total = 10, window = window(count = 10, total = 10)) }
        val vm = vm(sessions)
        vm.enterReview()
        vm.onPageSettled(3)
        waitUntil("位置写入") { vm.state.value.let { it is V2ReviewUiState.Ready && it.absoluteCurrentIndex == 3 } }

        vm.onLeaveForFullPlayer()
        vm.enterReview()

        val state = vm.state.value as V2ReviewUiState.Ready
        assertEquals("返回后必须仍在原位置", 3, state.absoluteCurrentIndex)
        assertEquals("不得重新创建会话（只进入一次）", listOf(false), sessions.enterForceNew)
    }

    // ---------- §39/§18/§20/§21 重新批阅与完成 ----------

    @Test
    fun `重新批阅创建新会话`() = runTest {
        val sessions = FakeSessions().apply {
            entryResult = readyOpen(sessionId = "s1", total = 3)
        }
        val vm = vm(sessions)
        vm.enterReview()

        sessions.entryResult = readyOpen(sessionId = "s2", total = 3)
        vm.restart()
        waitUntil("新建会话") { (vm.state.value as? V2ReviewUiState.Ready)?.sessionId == "s2" }

        assertEquals("重新批阅必须是 forceNew", listOf(false, true), sessions.enterForceNew)
    }

    @Test
    fun `到达末尾但服务端seen未达总数时不得完成`() = runTest {
        // 队列 2 条都已加载（atEnd），但服务端 seen_count 只有 1
        val sessions = FakeSessions().apply {
            entryResult = readyOpen(total = 2, window = window(count = 2, total = 2), sessionSeen = 1)
            seenResultOverride = ReviewSeenResult("", true, seenCount = 1, totalCount = 2)
        }
        val vm = vm(sessions)
        vm.enterReview()

        vm.markSeen(0)
        waitUntil("seen 确认") { sessions.seenCalls.isNotEmpty() }

        assertEquals("仍有未批阅时绝不能完成（评审 §17/§18）", 0, sessions.completeCalls)
        assertTrue("必须保持批阅状态而不是完成页", vm.state.value is V2ReviewUiState.Ready)
    }

    @Test
    fun `到达末尾且全部批阅后才调用complete`() = runTest {
        val sessions = FakeSessions().apply {
            entryResult = readyOpen(total = 2, window = window(count = 2, total = 2))
        }
        val vm = vm(sessions)
        vm.enterReview()

        // 只批阅第 1 条：不得完成
        vm.markSeen(0)
        waitUntil("第 1 条确认") { vm.isSeen("v1") }
        assertEquals(0, sessions.completeCalls)
        assertTrue(vm.state.value is V2ReviewUiState.Ready)

        // 末项确认（seen_count == total_count）后才调用服务端 complete
        vm.markSeen(1)
        waitUntil("会话完成调用") { sessions.completeCalls == 1 }
        waitUntil("进入完成页") { vm.state.value is V2ReviewUiState.Complete }
        assertEquals(V2ReviewUiState.Complete(2), vm.state.value)
    }

    @Test
    fun `complete double-trigger只发一次请求`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val sessions = FakeSessions().apply {
            entryResult = readyOpen(total = 3, window = window(count = 3, total = 3), sessionSeen = 1)
            completeGate = gate
            seenResultOverride = ReviewSeenResult("", true, seenCount = 3, totalCount = 3)
        }
        val vm = vm(sessions)
        vm.enterReview()

        // 第一次触发 complete（在飞）
        vm.markSeen(0)
        waitUntil("complete 已发出") { sessions.completeCalls == 1 }

        // 第二次 stable 回调再触发：必须被 single-flight 拦截
        vm.markSeen(1)
        waitUntil("第二次 seen 完成") { sessions.seenCalls.size == 2 }
        assertEquals("complete 必须 single-flight（§21）", 1, sessions.completeCalls)

        gate.complete(Unit)
        waitUntil("进入完成页") { vm.state.value is V2ReviewUiState.Complete }
    }

    @Test
    fun `complete失败不进入完成页`() = runTest {
        val sessions = FakeSessions().apply {
            entryResult = readyOpen(total = 1, window = window(count = 1, total = 1))
            completeResult = false
        }
        val vm = vm(sessions)
        vm.enterReview()

        vm.markSeen(0)
        waitUntil("complete 被调用") { sessions.completeCalls == 1 }

        assertTrue("服务端未确认时不得假装完成", vm.state.value is V2ReviewUiState.Ready)
    }

    // ---------- helpers ----------

    private fun vm(sessions: FakeSessions): V2ReviewViewModel =
        V2ReviewViewModel(FakeMediaRepository(mediaCount = 16), sessions)

    private fun playbackMediaId(vm: V2ReviewViewModel): String? =
        (vm.playback.value as? com.mediareview.app.feature.v2.player.V2PlaybackUiState.Ready)?.source?.mediaId

    private fun readyOpen(
        sessionId: String = "s1",
        total: Int = 3,
        currentIndex: Int = 0,
        sessionSeen: Int = 0,
        window: ReviewQueueWindow = window(count = total, firstPage = 1, total = total),
    ): ReviewSessionOpen = ReviewSessionOpen.Ready(
        session = ReviewSessionInfo(
            sessionId = sessionId,
            totalCount = total,
            currentIndex = currentIndex,
            seenCount = sessionSeen,
        ),
        window = window,
    )

    private fun window(
        count: Int,
        firstPage: Int = 1,
        lastPage: Int? = null,
        total: Int = count,
        startId: Int = 1,
        favorites: Set<Int> = emptySet(),
    ): ReviewQueueWindow {
        val items = (0 until count).map { offset ->
            val absolute = (firstPage - 1) * 50 + offset
            val id = "v${startId + offset}"
            ReviewQueueItemUi(
                absoluteIndex = absolute,
                mediaId = id,
                title = "视频 $id",
                code = "",
                folderName = "文件夹",
                durationMs = 12_000L,
                naturalWidth = 1920,
                naturalHeight = 1080,
                coverUrl = "http://host/$id.jpg",
                favorite = absolute in favorites,
            )
        }
        return ReviewQueueWindow(
            items = items,
            firstLoadedPage = firstPage,
            lastLoadedPage = lastPage ?: firstPage,
            totalCount = total,
        )
    }

    private fun waitUntil(label: String, timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("等待超时: $label")
    }
}

/**
 * 可控批阅会话仓库：精确记录每一次调用，便于断言"绝不做多余请求"。
 *
 * `markSeen` 默认模拟一个忠实的小服务端（按已确认集合返回权威计数），
 * 也可以通过 [seenResultOverride] 固定服务端返回值（测"权威值"语义）。
 */
private class FakeSessions : V2ReviewSessionRepository {
    var entryResult: ReviewSessionOpen = ReviewSessionOpen.Empty
    var loadNextResult: ReviewQueuePageResult? = null
    var loadPrevResult: ReviewQueuePageResult? = null
    var seenResultOverride: ReviewSeenResult? = null
    var failSeen = false
    var completeResult = true
    var positionGate: CompletableDeferred<Unit>? = null
    var seenGate: CompletableDeferred<Unit>? = null
    var completeGate: CompletableDeferred<Unit>? = null

    val enterForceNew = mutableListOf<Boolean>()
    val seenCalls = mutableListOf<String>()
    val positionStarted = mutableListOf<Int>()
    val positionCompleted = mutableListOf<Int>()
    var completeCalls = 0

    private var entryTotal = 0
    private val autoSeen = mutableSetOf<String>()

    override suspend fun enterSession(forceNew: Boolean): ReviewSessionOpen {
        enterForceNew += forceNew
        entryTotal = (entryResult as? ReviewSessionOpen.Ready)?.session?.totalCount ?: 0
        autoSeen.clear()
        return entryResult
    }

    override suspend fun loadNextPage(): ReviewQueuePageResult? = loadNextResult

    override suspend fun loadPrevPage(): ReviewQueuePageResult? = loadPrevResult

    override suspend fun markSeen(mediaId: String): ReviewSeenResult? {
        seenCalls += mediaId
        seenGate?.await()
        if (failSeen) return null
        autoSeen += mediaId
        return seenResultOverride?.copy(mediaId = mediaId)
            ?: ReviewSeenResult(mediaId = mediaId, seen = true, seenCount = autoSeen.size, totalCount = entryTotal)
    }

    override suspend fun savePosition(absoluteIndex: Int) {
        positionStarted += absoluteIndex
        positionGate?.await()
        positionCompleted += absoluteIndex
    }

    override suspend fun completeSession(): Boolean {
        completeCalls += 1
        completeGate?.await()
        return completeResult
    }

    override suspend fun restart(): ReviewSessionOpen {
        enterForceNew += true
        return entryResult
    }

    override suspend fun restartAll(): ReviewSessionOpen {
        enterForceNew += true
        return entryResult
    }
}

/** 只提供媒体 / 收藏 / 待删除 / 播放解析的最小仓库（记录播放解析次数，可对指定媒体加门）。 */
private class FakeMediaRepository(private val mediaCount: Int) : MediaRepository {

    var favoriteResult = true
    var pendingDeleteResult = true

    /** 该媒体的 resolvePlayback 会等待 [mediaGate]（模拟慢网络）。 */
    var gateMediaId: String? = null
    var mediaGate: CompletableDeferred<Unit>? = null

    val resolvedMediaIds = mutableListOf<String>()
    val favoriteCalls = mutableListOf<Pair<String, Boolean>>()
    val pendingDeleteCalls = mutableListOf<Pair<String, Boolean>>()
    private val pendingDelete = mutableSetOf<String>()
    private val favorites = mutableSetOf<String>()

    private val catalog: List<V2Media> = (1..mediaCount).map { index ->
        val id = "v$index"
        V2Media(
            id = id,
            code = "",
            name = "视频 $id",
            folderId = "f1",
            folderName = "文件夹",
            type = V2MediaType.VIDEO,
            durationMs = 12_000L,
            sizeBytes = 1024L,
            dateMillis = 0L,
            isFavorite = false,
            isReviewed = false,
            assetPath = "",
            thumbPath = "",
            spritePath = null,
            spriteManifestPath = null,
            naturalWidth = 1920,
            naturalHeight = 1080,
        )
    }

    override val mode: V2DataMode = V2DataMode.DEMO

    override suspend fun folders(): List<V2Folder> = emptyList()

    override suspend fun mediaPage(query: V2MediaQuery): V2MediaPage =
        V2MediaPage(catalog, 1, query.pageSize, catalog.size)

    override suspend fun media(): List<V2Media> = catalog

    override suspend fun media(spec: V2SortSpec): List<V2Media> = catalog

    override suspend fun mediaInFolder(folderId: String, spec: V2SortSpec): List<V2Media> = catalog

    override suspend fun search(query: String, spec: V2SortSpec): List<V2Media> = emptyList()

    override fun mediaById(id: String): V2Media? = catalog.find { it.id == id }

    override suspend fun setFavorite(mediaId: String, favorite: Boolean): Boolean {
        favoriteCalls += mediaId to favorite
        if (!favoriteResult) return false
        if (favorite) favorites += mediaId else favorites -= mediaId
        return true
    }

    override suspend fun favorites(): List<V2Media> = catalog.filter { it.id in favorites }

    override suspend fun markReviewed(mediaId: String) = Unit

    override suspend fun pendingDeleteIds(): Set<String> = pendingDelete.toSet()

    override suspend fun setPendingDelete(mediaId: String, pending: Boolean): Boolean {
        pendingDeleteCalls += mediaId to pending
        if (!pendingDeleteResult) return false
        if (pending) pendingDelete += mediaId else pendingDelete -= mediaId
        return true
    }

    override suspend fun unmarkReviewed(mediaId: String) = Unit

    override suspend fun resolvePlayback(mediaId: String): V2PlaybackSource {
        resolvedMediaIds += mediaId
        if (mediaId == gateMediaId) mediaGate?.await()
        return V2PlaybackSource(
            mediaId = mediaId,
            title = mediaId,
            direct = V2PlaybackEndpoint(url = "http://127.0.0.1:1/$mediaId"),
        )
    }

    override fun playbackUri(mediaId: String): String = ""

    override fun thumbUri(media: V2Media): String = ""

    override fun coverUri(media: V2Media): String = ""

    override fun imageUri(media: V2Media): String = ""

    override fun spriteUri(media: V2Media): String? = null

    override fun spriteManifest(media: V2Media): V2SpriteManifest? = null

    override suspend fun albums(): List<V2Album> = emptyList()

    override suspend fun albumPage(
        albumId: String,
        page: Int,
        pageSize: Int,
        spec: V2SortSpec,
    ): V2MediaPage = V2MediaPage(emptyList(), page, pageSize, 0)

    override suspend fun setAlbumCover(albumId: String, mediaId: String) = Unit
}