package com.mediareview.app.feature.settings

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

data class SettingsUiState(
    val loading: Boolean = true,
    val baseUrl: String = "",
    val deviceId: String = "",
    val paired: Boolean = false,
    /** 连接检查结果:null=未检查;否则为错误消息或"连接正常"。 */
    val checkMessage: String? = null,
    val checking: Boolean = false,
    val connection: ConnectionState = ConnectionState(),
)

/** 设置页:查看服务器配置、检查连接、重新配对/清除配置。 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val pairingRepository: PairingRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            pairingRepository.connection.collect { connection ->
                _ui.update { it.copy(connection = connection) }
            }
        }
        viewModelScope.launch {
            val restored = pairingRepository.load()
            _ui.value = SettingsUiState(
                loading = false,
                baseUrl = restored.baseUrl,
                deviceId = restored.deviceId,
                paired = restored.paired,
                checkMessage = restored.message,
                connection = restored.connection,
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
                    connection = when (result) {
                        is PairingRepository.Result.HealthOk -> result.connection
                        is PairingRepository.Result.Failure -> result.connection
                        else -> it.connection
                    },
                    paired = result is PairingRepository.Result.HealthOk &&
                        result.connection.authentication == com.mediareview.app.feature.connect.data.AuthenticationState.Paired,
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
