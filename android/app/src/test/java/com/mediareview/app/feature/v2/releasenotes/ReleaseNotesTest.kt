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
    @Test fun `Alpha12 当前版本与七条说明精确匹配`() {
        assertEquals(19, BuildConfig.VERSION_CODE)
        assertEquals("2.0.0-alpha12", BuildConfig.VERSION_NAME)
        val note = requireNotNull(ReleaseNotesCatalog.forVersionCode(19))
        assertEquals("2.0.0-alpha12", note.versionName)
        assertEquals("MediaReview 2.0 Alpha 12", note.title)
        assertEquals(listOf(
            "提升图片缩略图和图库浏览速度",
            "图片查看器会预加载相邻原图",
            "播放器新增待删除操作并优化拖动手感",
            "优化批阅模式循环播放和上下滑动体验",
            "批阅现在可以按文件夹选择范围",
            "修复待删除视频大小显示并增强删除结果确认",
            "修复部分层级分类无法正常使用的问题",
        ), note.highlights)
        assertEquals(listOf(19), codes(null, 19))
        assertEquals(listOf(19), codes(18, 19))
        assertEquals(listOf(18, 19), codes(17, 19))
    }

    @Test fun `Alpha8 历史目录合同保持独立`() {
        val note = requireNotNull(ReleaseNotesCatalog.forVersionCode(15))
        assertEquals("2.0.0-alpha8", note.versionName)
        assertEquals("MediaReview 2.0 Alpha 8", note.title)
        assertEquals(listOf(
            "提供新版手机安装包与电脑端升级包",
            "增加手机到 Jellyfin 的播放链路检测",
            "完善电脑端与手机端播放地址配置说明",
            "加强安装包版本、签名与升级文件完整性校验",
        ), note.highlights)
        assertEquals(listOf(15), codes(null, 15))
    }

    @Test fun `Alpha9 历史目录合同保持独立`() {
        val note = requireNotNull(ReleaseNotesCatalog.forVersionCode(16))
        assertEquals("2.0.0-alpha9", note.versionName)
        assertEquals("MediaReview 2.0 Alpha 9", note.title)
        assertEquals(listOf(
            "视频封面长按预览正式支持服务器媒体",
            "图片查看器的待删除操作现已同步到整理中心",
            "优化大相册连续翻页体验",
            "修复网络异常时相册被误显示为空的问题",
        ), note.highlights)
        assertEquals(listOf(16), codes(null, 16))
    }


    @Test fun `Alpha10 历史目录与四条说明精确匹配`() {
        val note = requireNotNull(ReleaseNotesCatalog.forVersionCode(17))
        assertEquals("2.0.0-alpha10", note.versionName)
        assertEquals("MediaReview 2.0 Alpha 10", note.title)
        assertEquals(listOf(
            "搜索结果支持连续加载更多内容",
            "搜索和首页状态现在互不干扰",
            "优化从收藏进入播放器和图片查看器后的连续浏览",
            "清理无效页面路径并提升浏览稳定性",
        ), note.highlights)
        assertEquals(listOf(17), codes(null, 17))
    }

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
            assertTrue("${note.versionName} 更新条数符合版本合同", note.highlights.size in (if (note.versionCode in setOf(18, 19)) 7..7 else 3..6))
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
        // 用户 8 → 12（中间 9、10、11 未安装）：一次拿到 9 + 10 + 11 + 12
        assertEquals(listOf(9, 10, 11, 12), codes(8, 12))
        // 用户 8 → 13：一次拿到 9 + 10 + 11 + 12 + 13
        assertEquals(listOf(9, 10, 11, 12, 13), codes(8, 13))
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
    fun `升级 18 到 19 只展示 alpha12`() = runTest(main.dispatcher) {
        val vm = WhatsNewViewModel(FakeReleaseNotesStore(18))
        advanceUntilIdle()

        assertTrue(vm.state.value.visible)
        assertEquals(listOf(BuildConfig.VERSION_CODE), vm.state.value.notes.map { it.versionCode })
        assertEquals("2.0.0-alpha12", vm.state.value.notes.single().versionName)
        assertFalse(vm.state.value.isMultiVersion)
    }

    @Test
    fun `升级 8 到 19 一个 Sheet 同时包含 alpha2 到 alpha12`() = runTest(main.dispatcher) {
        val vm = WhatsNewViewModel(FakeReleaseNotesStore(8))
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.visible)
        assertEquals(listOf(9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19), state.notes.map { it.versionCode })
        assertEquals(
            listOf("2.0.0-alpha2", "2.0.0-alpha3", "2.0.0-alpha4", "2.0.0-alpha5", "2.0.0-alpha6", "2.0.0-alpha7", "2.0.0-alpha8", "2.0.0-alpha9", "2.0.0-alpha10", "2.0.0-alpha11", "2.0.0-alpha12"),
            state.notes.map { it.versionName },
        )
        assertTrue("跨版本必须聚合在一个 Sheet(不同版本分区显示)", state.isMultiVersion)
    }

    @Test
    fun `升级 16 到 19 聚合 alpha10 到 alpha12`() = runTest(main.dispatcher) {
        val vm = WhatsNewViewModel(FakeReleaseNotesStore(16))
        advanceUntilIdle()
        assertTrue(vm.state.value.visible)
        assertEquals(listOf(17, 18, 19), vm.state.value.notes.map { it.versionCode })
        assertEquals(listOf("2.0.0-alpha10", "2.0.0-alpha11", "2.0.0-alpha12"), vm.state.value.notes.map { it.versionName })
        assertTrue(vm.state.value.isMultiVersion)
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
        assertEquals(listOf(19), store.saves)

        val restart = WhatsNewViewModel(store)
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
