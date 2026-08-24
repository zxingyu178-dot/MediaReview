package com.mediareview.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.core.datastore.ServerProfile
import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.feature.connect.data.PairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val loading: Boolean = true,
    val baseUrl: String = "",
    val deviceId: String = "",
    val paired: Boolean = false,
    /** 连接检查结果:null=未检查;否则为错误消息或"连接正常"。 */
    val checkMessage: String? = null,
    val checking: Boolean = false,
)

/** 设置页:查看服务器配置、检查连接、重新配对/清除配置。 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: ServerProfileStore,
    private val pairingRepository: PairingRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val profile: ServerProfile = store.current()
            _ui.value = SettingsUiState(
                loading = false,
                baseUrl = profile.baseUrl,
                deviceId = store.deviceId(),
                paired = profile.isPaired,
            )
        }
    }

    /** 检查当前服务器是否可达(Reconnect 探测)。 */
    fun checkConnection() {
        val st = _ui.value
        if (st.baseUrl.isBlank()) {
            _ui.update { it.copy(checkMessage = "尚未配置服务器地址") }
            return
        }
        _ui.update { it.copy(checking = true, checkMessage = null) }
        viewModelScope.launch {
            val result = pairingRepository.checkHealthy(st.baseUrl)
            _ui.update {
                it.copy(
                    checking = false,
                    checkMessage = when (result) {
                        is PairingRepository.Result.HealthOk ->
                            "连接正常(服务器 v${result.version})"
                        is PairingRepository.Result.Failure -> result.message
                        else -> "连接结果未知"
                    },
                )
            }
        }
    }

    /** 清除已保存服务器配置与配对 token(保留 deviceId),供重新连接。 */
    fun clearAndReconnect(onCleared: () -> Unit) {
        viewModelScope.launch {
            pairingRepository.clear()
            _ui.update { it.copy(paired = false, baseUrl = "", checkMessage = "配置已清除,请重新连接") }
            onCleared()
        }
    }
}
