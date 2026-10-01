package com.mediareview.app.feature.v2.releasenotes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 更新日志 Sheet 状态（Stage 8C.1 §42；Stage 8C.2 §29）。
 *
 * `notes` 可能是**多个版本**（跨版本升级时一次展示所有未读版本），
 * `currentVersionName` 用于标题（始终是当前版本）。
 */
data class WhatsNewUiState(
    val visible: Boolean = false,
    val notes: List<ReleaseNote> = emptyList(),
    val currentVersionName: String = "",
) {
    /** 是否包含多个未读版本（Sheet 用分区标题区分来源）。 */
    val isMultiVersion: Boolean get() = notes.size > 1
}

/**
 * 更新日志 ViewModel（Stage 8C.1 §38~§46；Stage 8C.2 §23~§31）。
 *
 * 规则：
 * ```
 * App 启动 → 读取 lastSeenVersionCode
 *         → notesAfter(lastSeen, VERSION_CODE) 非空 → 显示一个 WhatsNewSheet
 * ```
 * - **跨版本升级**（小版本不发 APK 时很容易发生，如 9 → 11）：Sheet 一次性展示
 *   `(lastSeen, current]` 所有版本的更新日志，但**只弹一个** Sheet（§24~§26）；
 * - 首次安装（lastSeen = null）只展示当前版本（§27）；
 * - 用户关闭后只写 `last_seen_version_code = VERSION_CODE`（§30：到当前版本为止都已看过）；
 * - 设置页「本次更新」**只展示当前版本**（§31），与自动弹窗的多版本聚合区分开；
 * - 当前版本缺失更新日志时：不崩溃、不展示（§46），Debug/Test 由合同测试兜底（§47）；
 * - **不阻塞 App 初始化**（§43）：Sheet 只是 Overlay，Home 照常加载。
 */
@HiltViewModel
class WhatsNewViewModel @Inject constructor(
    private val store: ReleaseNotesDataSource,
) : ViewModel() {

    private val _state = MutableStateFlow(WhatsNewUiState())
    val state: StateFlow<WhatsNewUiState> = _state.asStateFlow()

    init {
        // 初始化只发起一次异步读取，不阻塞首帧（§43/§44）
        viewModelScope.launch { considerShow() }
    }

    private suspend fun considerShow() {
        val pending = ReleaseNotesCatalog.notesAfter(
            lastSeenVersionCode = store.lastSeenVersionCode(),
            currentVersionCode = BuildConfig.VERSION_CODE,
        )
        if (pending.isNotEmpty()) {
            _state.update {
                it.copy(
                    visible = true,
                    notes = pending,
                    currentVersionName = BuildConfig.VERSION_NAME,
                )
            }
        }
    }

    /** 设置页「本次更新」主动打开：**只展示当前版本**（§31）。 */
    fun open() {
        val note = ReleaseNotesCatalog.forVersionCode(BuildConfig.VERSION_CODE) ?: return
        _state.update {
            it.copy(visible = true, notes = listOf(note), currentVersionName = BuildConfig.VERSION_NAME)
        }
    }

    /** 用户关闭：立即隐藏并记录"到当前版本为止都已看完"（§30，幂等）。 */
    fun dismiss() {
        val versionCode = BuildConfig.VERSION_CODE
        _state.update { it.copy(visible = false) }
        viewModelScope.launch { store.saveLastSeenVersionCode(versionCode) }
    }
}