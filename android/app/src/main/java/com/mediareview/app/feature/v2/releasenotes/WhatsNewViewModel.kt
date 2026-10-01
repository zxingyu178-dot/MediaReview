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

/** 更新日志 Sheet 状态（§42：ModalBottomSheet，不是全屏页面）。 */
data class WhatsNewUiState(
    val visible: Boolean = false,
    val note: ReleaseNote? = null,
)

/**
 * 更新日志 ViewModel（Stage 8C.1 §38~§46）。
 *
 * 规则：
 * ```
 * App 启动 → 读取 lastSeenVersionCode
 *         → BuildConfig.VERSION_CODE != lastSeen → 显示 WhatsNewSheet
 * ```
 * - 首次安装（lastSeen = null）也允许展示一次（§41：欢迎 + 本次更新，逻辑最简单一致）；
 * - 用户关闭后写入 `last_seen_version_code`，同版本再次启动不再弹（§39）；
 * - 多版本跳跃只展示**当前版本**的更新日志（§45）；
 * - 当前版本在 [ReleaseNotesCatalog] 缺失时：不崩溃、不展示（§46）；
 *   Debug/Test 由 `CurrentVersionHasReleaseNotesTest` 强制失败兜底（§47）；
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
        val note = ReleaseNotesCatalog.forVersionCode(BuildConfig.VERSION_CODE) ?: return
        if (store.lastSeenVersionCode() != BuildConfig.VERSION_CODE) {
            _state.update { it.copy(visible = true, note = note) }
        }
    }

    /** 设置页「本次更新」主动打开（§52）。 */
    fun open() {
        val note = ReleaseNotesCatalog.forVersionCode(BuildConfig.VERSION_CODE) ?: return
        _state.update { it.copy(visible = true, note = note) }
    }

    /** 用户关闭：立即隐藏并记录已展示版本（只写一次，重复关闭幂等）。 */
    fun dismiss() {
        val versionCode = BuildConfig.VERSION_CODE
        _state.update { it.copy(visible = false) }
        viewModelScope.launch { store.saveLastSeenVersionCode(versionCode) }
    }
}