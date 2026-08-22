package com.mediareview.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.core.datastore.ServerProfile
import com.mediareview.app.core.datastore.ServerProfileStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val baseUrl: String = "",
    val deviceId: String = "",
    val paired: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val store: ServerProfileStore,
) : ViewModel() {

    private val _ui = MutableStateFlow(HomeUiState(loading = true))
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    init {
        reload()
    }

    /** 重新读取服务器配置(Reconnect 后返回首页可刷新)。 */
    fun reload() {
        viewModelScope.launch {
            _ui.value = HomeUiState(loading = true)
            try {
                val profile: ServerProfile = store.current()
                _ui.value = HomeUiState(
                    baseUrl = profile.baseUrl,
                    deviceId = store.deviceId(),
                    paired = profile.isPaired,
                    loading = false,
                )
            } catch (e: Exception) {
                _ui.value = HomeUiState(loading = false, error = e.message ?: "读取配置失败")
            }
        }
    }
}