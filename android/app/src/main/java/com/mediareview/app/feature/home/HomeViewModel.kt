package com.mediareview.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.feature.connect.data.PairingRepository
import com.mediareview.app.feature.connect.data.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val baseUrl: String = "",
    val deviceId: String = "",
    val paired: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val connection: ConnectionState = ConnectionState(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val pairingRepository: PairingRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(HomeUiState(loading = true))
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            pairingRepository.connection.collect { connection ->
                _ui.update { it.copy(connection = connection) }
            }
        }
        reload()
    }

    /** 重新读取服务器配置(Reconnect 后返回首页可刷新)。 */
    fun reload() {
        viewModelScope.launch {
            _ui.value = HomeUiState(loading = true)
            try {
                val restored = pairingRepository.load()
                _ui.value = HomeUiState(
                    baseUrl = restored.baseUrl,
                    deviceId = restored.deviceId,
                    paired = restored.paired,
                    loading = false,
                    connection = restored.connection,
                )
            } catch (e: Exception) {
                _ui.value = HomeUiState(loading = false, error = e.message ?: "读取配置失败")
            }
        }
    }
}
