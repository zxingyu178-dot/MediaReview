package com.mediareview.app.feature.duplicates

import com.mediareview.app.FakeMediaDataSource
import com.mediareview.app.MainDispatcherRule
import com.mediareview.app.core.model.DuplicateGroupDto
import com.mediareview.app.core.model.DuplicateMemberDto
import com.mediareview.app.core.model.DuplicateScanStatusDto
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.core.model.TaskStateDto
import com.mediareview.app.core.ui.ContentInvalidationStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Task D6:重复文件页 ViewModel —— 分组加载 / 后台扫描控制 / 双栏对比 / 保留选择。 */
@OptIn(ExperimentalCoroutinesApi::class)
class DuplicatesViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun group(id: String, members: List<DuplicateMemberDto> = emptyList()) = DuplicateGroupDto(
        group_id = id,
        type = "exact",
        count = members.size,
        media_ids = members.map { it.media_id },
        names = members.map { it.name },
        members = members,
    )

    @Test
    fun `loadIfNeeded 加载分组并同步扫描状态`() = runTest(main.dispatcher) {
        val repository = DuplicateRepository()
        repository.exact = listOf(group("g1", listOf(DuplicateMemberDto("m1", "a.mp4"))))
        repository.similar = listOf(group("g2"))
        repository.scanStatus = DuplicateScanStatusDto(task_id = "t1", status = "succeeded", progress = 100)

        val viewModel = DuplicatesViewModel(repository, ContentInvalidationStore())
        viewModel.loadIfNeeded()
        advanceUntilIdle()

        val ui = viewModel.ui.value
        assertEquals(1, ui.exact.size)
        assertEquals(1, ui.similar.size)
        assertEquals("t1", ui.scanTaskId)
        assertEquals("succeeded", ui.scanStatus)
        assertFalse(ui.loading)
    }

    @Test
    fun `triggerScan 发起后台任务并轮询至成功终态`() = runTest(main.dispatcher) {
        val repository = DuplicateRepository()
        repository.scanTask = TaskStateDto(task_id = "t1", status = "running", progress = 10)
        repository.scanStatus = DuplicateScanStatusDto(task_id = "t1", status = "succeeded", progress = 100)

        val viewModel = DuplicatesViewModel(repository, ContentInvalidationStore())
        viewModel.triggerScan()
        advanceUntilIdle()

        val ui = viewModel.ui.value
        assertEquals("t1", ui.scanTaskId)
        assertEquals("succeeded", ui.scanStatus)
        assertEquals(100, ui.scanProgress)
        assertFalse(ui.scanPolling)
    }

    @Test
    fun `triggerScan 无任务引用时给出可读提示`() = runTest(main.dispatcher) {
        val repository = DuplicateRepository()
        repository.scanTask = null

        val viewModel = DuplicatesViewModel(repository, ContentInvalidationStore())
        viewModel.triggerScan()
        advanceUntilIdle()

        assertEquals("无法发起重复扫描,请稍后重试", viewModel.ui.value.scanError)
    }

    @Test
    fun `暂停与继续切换扫描状态`() = runTest(main.dispatcher) {
        val repository = DuplicateRepository()
        repository.scanTask = TaskStateDto(task_id = "t1", status = "running", progress = 10)
        repository.scanStatus = DuplicateScanStatusDto(task_id = "t1", status = "running", progress = 10)

        val viewModel = DuplicatesViewModel(repository, ContentInvalidationStore())
        viewModel.triggerScan()
        runCurrent()
        assertTrue(viewModel.ui.value.scanPolling)

        // 服务端已被暂停(与真实 pause 合同一致:任务状态落库后轮询读到 paused)
        repository.scanStatus = DuplicateScanStatusDto(task_id = "t1", status = "paused", progress = 40)
        viewModel.pauseScan()
        runCurrent()
        assertEquals("paused", viewModel.ui.value.scanStatus)
        advanceUntilIdle()
        assertFalse(viewModel.ui.value.scanPolling)

        // 继续:回到 running 并重新轮询
        repository.scanStatus = DuplicateScanStatusDto(task_id = "t1", status = "running", progress = 40)
        viewModel.resumeScan()
        runCurrent()
        assertEquals("running", viewModel.ui.value.scanStatus)
        assertTrue(viewModel.ui.value.scanPolling)

        // 结束前推进到成功终态,释放轮询协程
        repository.scanStatus = DuplicateScanStatusDto(task_id = "t1", status = "succeeded", progress = 100)
        advanceUntilIdle()
        assertEquals("succeeded", viewModel.ui.value.scanStatus)
        assertFalse(viewModel.ui.value.scanPolling)
    }

    @Test
    fun `取消扫描置为终态并重载分组`() = runTest(main.dispatcher) {
        val repository = DuplicateRepository()
        repository.scanTask = TaskStateDto(task_id = "t1", status = "running")
        repository.scanStatus = DuplicateScanStatusDto(task_id = "t1", status = "running")
        repository.exact = listOf(group("g1"))

        val viewModel = DuplicatesViewModel(repository, ContentInvalidationStore())
        viewModel.triggerScan()
        runCurrent()

        viewModel.cancelScan()
        runCurrent()
        assertEquals("cancelled", viewModel.ui.value.scanStatus)
        assertEquals("取消后重载分组", 1, viewModel.ui.value.exact.size)

        // 结束前推进到取消终态,释放轮询协程
        repository.scanStatus = DuplicateScanStatusDto(task_id = "t1", status = "cancelled")
        advanceUntilIdle()
        assertFalse(viewModel.ui.value.scanPolling)
    }

    @Test
    fun `openCompare 加载成员摘要并可关闭`() = runTest(main.dispatcher) {
        val repository = DuplicateRepository()
        repository.exact = listOf(
            group("g1", listOf(DuplicateMemberDto("m1", "a.mp4"), DuplicateMemberDto("m2", "b.mp4"))),
        )
        repository.mediaSummaries = mapOf(
            "m1" to MediaSummary(media_id = "m1", media_type = "video"),
            "m2" to MediaSummary(media_id = "m2", media_type = "video"),
        )

        val viewModel = DuplicatesViewModel(repository, ContentInvalidationStore())
        viewModel.loadIfNeeded()
        advanceUntilIdle()

        viewModel.openCompare("g1")
        advanceUntilIdle()

        val ui = viewModel.ui.value
        assertEquals("g1", ui.compareGroup?.group_id)
        assertEquals(2, ui.compareSummaries.size)
        assertFalse(ui.compareLoading)

        viewModel.closeCompare()
        assertNull(viewModel.ui.value.compareGroup)
    }

    @Test
    fun `setKeep 成功后更新本地状态,失败不落`() = runTest(main.dispatcher) {
        val repository = DuplicateRepository()
        repository.exact = listOf(
            group("g1", listOf(DuplicateMemberDto("m1", "a.mp4"), DuplicateMemberDto("m2", "b.mp4"))),
        )

        val viewModel = DuplicatesViewModel(repository, ContentInvalidationStore())
        viewModel.loadIfNeeded()
        advanceUntilIdle()

        repository.keepSuccess = false
        viewModel.setKeep("g1", "m1", true)
        advanceUntilIdle()
        assertFalse("失败不落本地", viewModel.ui.value.exact[0].members[0].keep)

        repository.keepSuccess = true
        viewModel.setKeep("g1", "m1", true)
        advanceUntilIdle()
        assertTrue(viewModel.ui.value.exact[0].members[0].keep)
        assertFalse("其余成员不受影响", viewModel.ui.value.exact[0].members[1].keep)
    }

    @Test
    fun `openCompare 未知分组不崩溃`() = runTest(main.dispatcher) {
        val repository = DuplicateRepository()
        val viewModel = DuplicatesViewModel(repository, ContentInvalidationStore())
        viewModel.loadIfNeeded()
        advanceUntilIdle()

        viewModel.openCompare("ghost")
        advanceUntilIdle()
        assertNull(viewModel.ui.value.compareGroup)
    }
}

private class DuplicateRepository : FakeMediaDataSource() {
    var exact: List<DuplicateGroupDto> = emptyList()
    var similar: List<DuplicateGroupDto> = emptyList()
    var scanTask: TaskStateDto? = null
    var scanStatus: DuplicateScanStatusDto? = null
    var keepSuccess = true
    var mediaSummaries: Map<String, MediaSummary> = emptyMap()

    override suspend fun loadDuplicatesExact(): List<DuplicateGroupDto> = exact
    override suspend fun loadDuplicatesSimilar(): List<DuplicateGroupDto> = similar
    override suspend fun triggerDuplicateScan(): TaskStateDto? = scanTask
    override suspend fun duplicateScanStatus(): DuplicateScanStatusDto? = scanStatus
    override suspend fun setDuplicateKeep(groupId: String, mediaId: String, keep: Boolean): Boolean = keepSuccess
    override suspend fun loadMediaSummary(mediaId: String): MediaSummary? = mediaSummaries[mediaId]

    override suspend fun pauseTask(taskId: String): TaskStateDto? =
        TaskStateDto(task_id = taskId, status = "paused", progress = scanStatus?.progress ?: 0)
    override suspend fun resumeTask(taskId: String): TaskStateDto? =
        TaskStateDto(task_id = taskId, status = "running", progress = scanStatus?.progress ?: 0)
    override suspend fun cancelTask(taskId: String): TaskStateDto? =
        TaskStateDto(task_id = taskId, status = "cancelled", progress = scanStatus?.progress ?: 0)
}
