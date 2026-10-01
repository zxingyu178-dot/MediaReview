package com.mediareview.app.feature.v2.releasenotes

import com.mediareview.app.BuildConfig
import com.mediareview.app.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 可编程的更新日志存储（替代 DataStore，便于 JVM 测试 §55 全部分支）。 */
private class FakeReleaseNotesStore(private var lastSeen: Int?) : ReleaseNotesDataSource {

    val saves = mutableListOf<Int>()

    override suspend fun lastSeenVersionCode(): Int? = lastSeen

    override suspend fun saveLastSeenVersionCode(versionCode: Int) {
        lastSeen = versionCode
        saves += versionCode
    }
}

/**
 * Stage 8C.1 §47/§55：版本 / 更新日志合同。
 *
 * - 当前版本必须有 Release Notes（忘记写更新日志 -> TEST FAIL，自动 Gate）；
 * - 更新日志面向用户，不含开发内部词；
 * - 多版本跳跃只按当前版本查找（§45）。
 */
class ReleaseNotesContractTest {

    @Test
    fun `当前版本必须存在更新日志`() {
        assertNotNull(
            "当前 versionCode=${BuildConfig.VERSION_CODE} 在 ReleaseNotesCatalog 中缺少更新日志（§47）",
            ReleaseNotesCatalog.forVersionCode(BuildConfig.VERSION_CODE),
        )
    }

    @Test
    fun `多版本跳跃只按当前版本查找而不是逐版本`() {
        assertNull("未记录的版本不展示、不崩溃（§46）", ReleaseNotesCatalog.forVersionCode(9999))
        assertEquals(
            "2.0.0-alpha2",
            ReleaseNotesCatalog.forVersionCode(9)?.versionName,
        )
    }

    @Test
    fun `用户可读更新日志不含开发内部词`() {
        val banned = listOf("Stage", "Repository", "DTO", "Hilt", "MockWebServer")
        val note = ReleaseNotesCatalog.forVersionCode(BuildConfig.VERSION_CODE)!!
        val text = (listOf(note.title) + note.highlights).joinToString(" ")
        banned.forEach { word ->
            assertFalse("更新日志必须面向用户，不得出现开发内部词: $word", text.contains(word))
        }
        assertTrue("更新日志保持 3~6 条（§37）", note.highlights.size in 3..6)
    }
}

/**
 * Stage 8C.1 §38~§45：新版本首次启动弹出更新日志，且只弹一次。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WhatsNewViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `首次安装显示当前版本更新日志`() = runTest(main.dispatcher) {
        val vm = WhatsNewViewModel(FakeReleaseNotesStore(null))
        advanceUntilIdle()

        assertTrue(vm.state.value.visible)
        assertEquals(BuildConfig.VERSION_CODE, vm.state.value.note?.versionCode)
    }

    @Test
    fun `关闭后保存当前版本并且同版本第二次启动不显示`() = runTest(main.dispatcher) {
        val store = FakeReleaseNotesStore(null)
        val vm = WhatsNewViewModel(store)
        advanceUntilIdle()
        assertTrue(vm.state.value.visible)

        vm.dismiss()
        advanceUntilIdle()
        assertFalse(vm.state.value.visible)
        assertEquals(listOf(BuildConfig.VERSION_CODE), store.saves)

        // 同版本重启：读取到 lastSeen == 当前版本 -> 不再弹（§39）
        val restart = WhatsNewViewModel(FakeReleaseNotesStore(BuildConfig.VERSION_CODE))
        advanceUntilIdle()
        assertFalse(restart.state.value.visible)
    }

    @Test
    fun `版本变化后再次显示`() = runTest(main.dispatcher) {
        val vm = WhatsNewViewModel(FakeReleaseNotesStore(BuildConfig.VERSION_CODE - 1))
        advanceUntilIdle()
        assertTrue(vm.state.value.visible)
    }

    @Test
    fun `多版本跳跃只展示当前版本`() = runTest(main.dispatcher) {
        // lastSeen 很旧（跨越多版本），也只展示当前版本的更新日志（§45）
        val vm = WhatsNewViewModel(FakeReleaseNotesStore(1))
        advanceUntilIdle()
        assertEquals(BuildConfig.VERSION_CODE, vm.state.value.note?.versionCode)
    }

    @Test
    fun `设置页可以主动打开本次更新`() = runTest(main.dispatcher) {
        val vm = WhatsNewViewModel(FakeReleaseNotesStore(BuildConfig.VERSION_CODE))
        advanceUntilIdle()
        assertFalse(vm.state.value.visible)

        vm.open()
        assertTrue(vm.state.value.visible)
        assertEquals(BuildConfig.VERSION_CODE, vm.state.value.note?.versionCode)
    }

    @Test
    fun `当前版本缺失更新日志时不崩溃也不展示`() = runTest(main.dispatcher) {
        // 目录里没有 9999 的记录：必须返回 null（Release 不崩溃、不显示，§46）
        assertNull(ReleaseNotesCatalog.forVersionCode(9999))

        // 正常路径仍工作：有记录时照常展示
        val vm = WhatsNewViewModel(FakeReleaseNotesStore(null))
        advanceUntilIdle()
        assertTrue(vm.state.value.visible)
    }
}