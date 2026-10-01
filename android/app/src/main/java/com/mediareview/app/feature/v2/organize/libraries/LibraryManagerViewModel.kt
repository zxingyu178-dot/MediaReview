package com.mediareview.app.feature.v2.organize.libraries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.feature.v2.organize.data.LibrarySelectionItem
import com.mediareview.app.feature.v2.organize.data.OrganizeFeatureUnavailableInDemoException
import com.mediareview.app.feature.v2.organize.data.V2OrganizeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 媒体库管理状态（Stage 8C §39~§42）。
 *
 * `libraries` 是服务端正式状态；`draftSelected` 是本地编辑草稿 ——
 * 点 Checkbox 只改草稿，点「应用」才 `PUT /libraries/selection`；失败保留草稿。
 */
data class LibraryManagerUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /** Demo 模式没有媒体库能力：整页显示「仅服务器模式可用」。 */
    val demoUnavailable: Boolean = false,
    val libraries: List<LibrarySelectionItem> = emptyList(),
    val draftSelected: Set<String> = emptySet(),
    val saving: Boolean = false,
) {
    /** 草稿与服务端状态不一致（决定"应用"按钮是否可点）。 */
    val dirty: Boolean
        get() = libraries.any { it.selected != (it.jellyfinId in draftSelected) }
}

/**
 * 媒体库管理 ViewModel（复用 `GET /libraries` + `PUT /libraries/selection`，不新增第二套 API）。
 *
 * - 勾选只在本地编辑，**绝不**点一下发一次网络请求（§40）；
 * - 保存失败：保持页面与草稿 + 提示，不假成功（§40/§64）；
 * - 保存成功：以服务端返回为正式状态，并通知上层刷新首页缓存（§41）；
 * - 全取消在 UI 提前阻止，Server 仍做最终校验（§42）。
 */
@HiltViewModel
class LibraryManagerViewModel @Inject constructor(
    private val repository: V2OrganizeRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(LibraryManagerUiState())
    val ui: StateFlow<LibraryManagerUiState> = _ui.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 保存成功事件：上层据此 invalidateAuxiliaryCache + 刷新首页（§41）。 */
    private val _applied = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val applied: SharedFlow<Unit> = _applied.asSharedFlow()

    fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            try {
                val libraries = repository.loadLibraries()
                _ui.update {
                    it.copy(
                        loading = false,
                        error = null,
                        demoUnavailable = false,
                        libraries = libraries,
                        draftSelected = libraries.filter { row -> row.selected }
                            .map { row -> row.jellyfinId }
                            .toSet(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: OrganizeFeatureUnavailableInDemoException) {
                _ui.update { it.copy(loading = false, demoUnavailable = true, error = null) }
            } catch (error: Throwable) {
                _ui.update { it.copy(loading = false, error = error.message ?: "加载失败") }
            }
        }
    }

    /** 本地勾选（草稿）；不触发任何网络请求。 */
    fun toggle(jellyfinId: String) {
        _ui.update { state ->
            val draft = state.draftSelected
            state.copy(
                draftSelected = if (jellyfinId in draft) draft - jellyfinId else draft + jellyfinId,
            )
        }
    }

    /** 应用：全部取消在本地提前阻止；保存失败保持页面与草稿。 */
    fun apply() {
        val state = _ui.value
        if (state.saving) return
        if (state.draftSelected.isEmpty()) {
            _messages.tryEmit("至少选择一个媒体库")
            return
        }
        // 按列表顺序提交，保证请求体稳定可测
        val selectedIds = state.libraries
            .filter { it.jellyfinId in state.draftSelected }
            .map { it.jellyfinId }
        viewModelScope.launch {
            _ui.update { it.copy(saving = true) }
            try {
                val saved = repository.saveLibrarySelection(selectedIds)
                _ui.update {
                    it.copy(
                        saving = false,
                        libraries = saved,
                        draftSelected = saved.filter { row -> row.selected }
                            .map { row -> row.jellyfinId }
                            .toSet(),
                    )
                }
                _applied.tryEmit(Unit)
                _messages.tryEmit("媒体库选择已更新")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // 失败：保持页面与草稿，用户可重试
                _ui.update { it.copy(saving = false) }
                _messages.tryEmit("保存失败，请重试")
            }
        }
    }
}