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

/** 可编程的更新日志存储（替代 DataStore，便于 JVM 测试全部分支）。 */
private class FakeReleaseNotesStore(private var lastSeen: Int?) : ReleaseNotesDataSource {

    val saves = mutableListOf<Int>()

    override suspend fun lastSeenVersionCode(): Int? = lastSeen

    override suspend fun saveLastSeenVersionCode(versionCode: Int) {
        lastSeen = versionCode
        saves += versionCode
    }
}

/**
 * Stage 8C.1 §47/§55 + Stage 8C.2 §34：版本 / 更新日志目录合同。
 */
class ReleaseNotesContractTest {

    @Test
    fun `当前版本必须存在更新日志`() {
        assertNotNull(
            "当前 versionCode=${BuildConfig.VERSION_CODE} 在 ReleaseNotesCatalog 中缺少更新日志",
            ReleaseNotesCatalog.forVersionCode(BuildConfig.VERSION_CODE),
        )
    }

    @Test
    fun `versionCode 与 versionName 唯一`() {
        val codes = ReleaseNotesCatalog.notes.map { it.versionCode }
        val names = ReleaseNotesCatalog.notes.map { it.versionName }
        assertEquals("versionCode 必须唯一", codes.size, codes.toSet().size)
        assertEquals("versionName 必须唯一", names.size, names.toSet().size)
    }

    @Test
    fun `versionCode 严格递增`() {
        val codes = ReleaseNotesCatalog.notes.map { it.versionCode }
        assertEquals("目录必须按 versionCode 严格递增登记", codes.sorted(), codes)
        codes.zipWithNext().forEach { (previous, next) ->
            assertTrue("versionCode 必须递增: $previous -> $next", next > previous)
        }
    }

    @Test
    fun `每个版本的更新日志都是用户可读的`() {
        val banned = listOf("Stage", "Repository", "DTO", "Hilt", "MockWebServer")
        ReleaseNotesCatalog.notes.forEach { note ->
            val text = (listOf(note.title) + note.highlights).joinToString(" ")
            banned.forEach { word ->
                assertFalse("更新日志必须面向用户，不得出现开发内部词: $word", text.contains(word))
            }
            assertTrue("${note.versionName} 保持 3~6 条更新", note.highlights.size in 3..6)
        }
    }

    @Test
    fun `未登记的版本返回 null 不崩溃`() {
        assertNull(ReleaseNotesCatalog.forVersionCode(9999))
    }

    // ---------- Stage 8C.2 §28/§35 范围查询 ----------

    private fun codes(lastSeen: Int?, current: Int): List<Int> =
        ReleaseNotesCatalog.notesAfter(lastSeen, current).map { it.versionCode }

    @Test
    fun `首次安装只展示当前版本`() {
        assertEquals(listOf(10), codes(null, 10))
        assertEquals(listOf(8), codes(null, 8))
    }

    @Test
    fun `相邻版本升级只展示新版本`() {
        assertEquals(listOf(10), codes(9, 10))
        assertEquals(listOf(9), codes(8, 9))
    }

    @Test
    fun `跨多个未安装版本时聚合同一份列表且升序`() {
        // 用户 8 → 10（中间 9 未安装）：一次拿到 9 + 10
        assertEquals(listOf(9, 10), codes(8, 10))
        // 用户 8 → 11（中间 9、10 未安装）：一次拿到 9 + 10 + 11
        assertEquals(listOf(9, 10, 11), codes(8, 11))
    }

    @Test
    fun `同版本再次启动不展示`() {
        assertEquals(emptyList<Int>(), codes(10, 10))
    }

    @Test
    fun `lastSeen 大于当前版本时安全兜底`() {
        assertEquals(emptyList<Int>(), codes(11, 10))
        assertEquals(emptyList<Int>(), codes(9999, 10))
    }

    @Test
    fun `当前版本缺少更新日志时不展示且不崩溃`() {
        assertEquals(emptyList<Int>(), codes(9, 9999))
        assertEquals(emptyList<Int>(), codes(null, 9999))
    }
}

/**
 * Stage 8C.1 §38~§45 + Stage 8C.2 §23~§31/§44：新版本首次启动弹出更新日志，且只弹一次。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WhatsNewViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `升级 10 到 11 展示 alpha4`() = runTest(main.dispatcher) {
        val vm = WhatsNewViewModel(FakeReleaseNotesStore(10))
        advanceUntilIdle()

        assertTrue(vm.state.value.visible)
        assertEquals(listOf(BuildConfig.VERSION_CODE), vm.state.value.notes.map { it.versionCode })
        assertEquals("2.0.0-alpha4", vm.state.value.notes.single().versionName)
        assertFalse(vm.state.value.isMultiVersion)
    }

    @Test
    fun `升级 8 到 11 一个 Sheet 同时包含 alpha2 alpha3 alpha4`() = runTest(main.dispatcher) {
        val vm = WhatsNewViewModel(FakeReleaseNotesStore(8))
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.visible)
        assertEquals(listOf(9, 10, 11), state.notes.map { it.versionCode })
        assertEquals(
            listOf("2.0.0-alpha2", "2.0.0-alpha3", "2.0.0-alpha4"),
            state.notes.map { it.versionName },
        )
        assertTrue("跨版本必须聚合在一个 Sheet(不同版本分区显示)", state.isMultiVersion)
    }

    @Test
    fun `首次安装只展示当前版本`() = runTest(main.dispatcher) {
        val vm = WhatsNewViewModel(FakeReleaseNotesStore(null))
        advanceUntilIdle()

        assertTrue(vm.state.value.visible)
        assertEquals(listOf(BuildConfig.VERSION_CODE), vm.state.value.notes.map { it.versionCode })
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

        val restart = WhatsNewViewModel(FakeReleaseNotesStore(BuildConfig.VERSION_CODE))
        advanceUntilIdle()
        assertFalse(restart.state.value.visible)
    }

    @Test
    fun `设置页本次更新只展示当前版本`() = runTest(main.dispatcher) {
        // 模拟"跨了多个版本但用户主动打开设置页"：只显示当前版本
        val vm = WhatsNewViewModel(FakeReleaseNotesStore(8))
        advanceUntilIdle()
        vm.dismiss() // 先清掉自动弹窗状态
        advanceUntilIdle()

        vm.open()
        val state = vm.state.value
        assertTrue(state.visible)
        assertEquals(listOf(BuildConfig.VERSION_CODE), state.notes.map { it.versionCode })
        assertFalse(state.isMultiVersion)
    }

    @Test
    fun `已是最新版本时启动不弹窗但设置页仍可打开当前版本`() = runTest(main.dispatcher) {
        val vm = WhatsNewViewModel(FakeReleaseNotesStore(BuildConfig.VERSION_CODE))
        advanceUntilIdle()
        assertFalse(vm.state.value.visible)

        vm.open()
        assertTrue(vm.state.value.visible)
        assertEquals(
            BuildConfig.VERSION_CODE,
            vm.state.value.notes.single().versionCode,
        )
    }
}