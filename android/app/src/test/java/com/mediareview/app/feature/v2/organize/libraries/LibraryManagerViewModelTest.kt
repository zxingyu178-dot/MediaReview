package com.mediareview.app.feature.v2.organize.libraries

import com.mediareview.app.MainDispatcherRule
import com.mediareview.app.feature.v2.organize.FakeOrganizeRepository
import com.mediareview.app.feature.v2.organize.data.LibrarySelectionItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Stage 8C §54：媒体库管理 ViewModel —— 本地编辑草稿 / 应用才提交 / 失败保留草稿 /
 * 全取消本地阻止 / 成功后通知上层刷新。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryManagerViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val serverState = listOf(
        LibrarySelectionItem("lib-movies", "电影", true),
        LibrarySelectionItem("lib-photos", "照片", true),
        LibrarySelectionItem("lib-temp", "临时素材", false),
    )

    @Test
    fun `加载后草稿等于服务端状态`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            librariesResult = Result.success(serverState)
        }
        val vm = LibraryManagerViewModel(repo)
        vm.load()
        advanceUntilIdle()

        val ui = vm.ui.value
        assertFalse(ui.loading)
        assertEquals(3, ui.libraries.size)
        assertEquals(setOf("lib-movies", "lib-photos"), ui.draftSelected)
        assertFalse("草稿与服务端一致时不可点应用", ui.dirty)
    }

    @Test
    fun `勾选只改本地草稿不发请求`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            librariesResult = Result.success(serverState)
        }
        val vm = LibraryManagerViewModel(repo)
        vm.load()
        advanceUntilIdle()

        vm.toggle("lib-temp")
        assertEquals(setOf("lib-movies", "lib-photos", "lib-temp"), vm.ui.value.draftSelected)
        assertTrue(vm.ui.value.dirty)
        assertEquals("点击 Checkbox 绝不能立即发网络请求", 0, repo.saveSelectionCalls)
    }

    @Test
    fun `应用成功后以服务端返回为正式状态并通知上层`() = runTest(main.dispatcher) {
        val saved = listOf(
            LibrarySelectionItem("lib-movies", "电影", false),
            LibrarySelectionItem("lib-photos", "照片", true),
            LibrarySelectionItem("lib-temp", "临时素材", true),
        )
        val repo = FakeOrganizeRepository().apply {
            librariesResult = Result.success(serverState)
            saveSelectionResult = Result.success(saved)
        }
        val vm = LibraryManagerViewModel(repo)
        val applied = mutableListOf<Unit>()
        val collector = launch { vm.applied.collect { applied += it } }
        vm.load()
        advanceUntilIdle()

        vm.toggle("lib-movies") // 取消电影
        vm.toggle("lib-temp") // 勾选临时素材
        vm.apply()
        advanceUntilIdle()

        assertEquals(1, repo.saveSelectionCalls)
        // 只提交一次，且按列表顺序（服务端权威状态来自响应）
        assertEquals(listOf(listOf("lib-photos", "lib-temp")), repo.savedSelections)
        assertEquals(setOf("lib-photos", "lib-temp"), vm.ui.value.draftSelected)
        assertFalse(vm.ui.value.dirty)
        assertEquals(1, applied.size)
        collector.cancel()
    }

    @Test
    fun `保存失败保留草稿且不通知上层`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            librariesResult = Result.success(serverState)
            saveSelectionResult = Result.failure(IllegalStateException("boom"))
        }
        val vm = LibraryManagerViewModel(repo)
        val applied = mutableListOf<Unit>()
        val collector = launch { vm.applied.collect { applied += it } }
        vm.load()
        advanceUntilIdle()

        vm.toggle("lib-movies")
        vm.apply()
        advanceUntilIdle()

        val ui = vm.ui.value
        assertEquals("失败必须保留草稿", setOf("lib-photos"), ui.draftSelected)
        assertTrue(ui.dirty)
        assertFalse(ui.saving)
        assertTrue(applied.isEmpty())
        collector.cancel()
    }

    @Test
    fun `全取消在本地阻止提交`() = runTest(main.dispatcher) {
        val repo = FakeOrganizeRepository().apply {
            librariesResult = Result.success(serverState)
        }
        val vm = LibraryManagerViewModel(repo)
        vm.load()
        advanceUntilIdle()

        vm.toggle("lib-movies")
        vm.toggle("lib-photos")
        vm.apply()
        advanceUntilIdle()

        assertEquals("至少选择一个媒体库：本地直接阻止，不发请求", 0, repo.saveSelectionCalls)
        assertTrue(vm.ui.value.draftSelected.isEmpty())
    }
}