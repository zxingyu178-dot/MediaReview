package com.mediareview.app.feature.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.core.network.TokenProvider
import com.mediareview.app.feature.connect.data.PairingRepository
import com.mediareview.app.feature.connect.data.ServerProfileView
import com.mediareview.app.feature.connect.discovery.DiscoveredServer
import com.mediareview.app.feature.connect.discovery.ServerDiscovery
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 连接页三种工作模式切换表。 */
enum class ConnectStep { Discover, ManualIp, Pairing }

/** 默认 MediaReview 端口:无显式端口时补全。 */
private const val DEFAULT_PORT = 8765

data class ConnectUiState(
    val step: ConnectStep = ConnectStep.Discover,
    val discovering: Boolean = false,
    val servers: List<DiscoveredServer> = emptyList(),
    val manualIp: String = "",
    val code: String = "",
    val deviceId: String = "",
    val selectedBaseUrl: String = "",
    val busy: Boolean = false,
    val message: String = "",
    val error: String? = null,
    val paired: Boolean = false,
)

/**
 * 把用户输入规范化为 base url:
 * - 已带 http:// 则保持;
 * - 否则补 http://;
 * - 无显式端口(host:port)时补 :8765,有端口则尊重用户输入。
 */
internal fun normalizeBaseUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "http://$trimmed"
    }
    // 解析 host[:port],若无端口则补默认
    val hostPort = withScheme.removePrefix("http://").removePrefix("https://")
    val hasPort = Regex(":[0-9]+$").containsMatchIn(hostPort)
    val authority = if (hasPort) hostPort else "$hostPort:$DEFAULT_PORT"
    return if (withScheme.startsWith("https")) "https://$authority" else "http://$authority"
}

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val repository: PairingRepository,
    private val tokenProvider: TokenProvider,
) : ViewModel() {

    private val _ui = MutableStateFlow(ConnectUiState())
    val ui: StateFlow<ConnectUiState> = _ui.asStateFlow()

    private val discovery = ServerDiscovery()

    init {
        viewModelScope.launch { restore() }
    }

    /** 启动时恢复已保存服务器(已配对则直接进入已配对态)。 */
    private suspend fun restore() {
        val saved: ServerProfileView = repository.load()
        _ui.update { it.copy(deviceId = saved.deviceId) }
        if (saved.paired && saved.baseUrl.isNotBlank()) {
            _ui.update { it.copy(paired = true, selectedBaseUrl = saved.baseUrl) }
        } else if (saved.baseUrl.isNotBlank()) {
            _ui.update { it.copy(manualIp = saved.baseUrl.removePrefix("http://")) }
        }
    }

    fun setStep(step: ConnectStep) = _ui.update { it.copy(step = step) }

    fun onManualIpChange(value: String) = _ui.update { it.copy(manualIp = value) }
    fun onCodeChange(value: String) = _ui.update { it.copy(code = value) }

    fun startDiscovery() {
        _ui.update { it.copy(discovering = true, error = null, servers = emptyList()) }
        viewModelScope.launch {
            val found = runCatching { discovery.discover() }.getOrElse {
                _ui.update { st -> st.copy(discovering = false, error = it.message) }
                return@launch
            }
            _ui.update {
                it.copy(discovering = false, servers = found, error = if (found.isEmpty()) "未发现服务器,请尝试手动输入 IP" else null)
            }
        }
    }

    fun pickServer(server: DiscoveredServer) {
        val baseUrl = normalizeBaseUrl(server.baseUrl)
        _ui.update {
            it.copy(
                selectedBaseUrl = baseUrl,
                manualIp = server.host,
                step = ConnectStep.Pairing,
            )
        }
    }

    /** 手动 IP:先健康检查再跳转到配对;无显式端口自动补 8765。 */
    fun testManualServer() {
        val ip = _ui.value.manualIp.trim()
        if (ip.isBlank()) {
            _ui.update { it.copy(error = "请输入服务器地址") }
            return
        }
        val baseUrl = normalizeBaseUrl(ip)
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.checkHealthy(baseUrl)) {
                is PairingRepository.Result.HealthOk -> {
                    _ui.update {
                        it.copy(
                            busy = false,
                            selectedBaseUrl = baseUrl,
                            step = ConnectStep.Pairing,
                        )
                    }
                }
                is PairingRepository.Result.Failure -> {
                    _ui.update { it.copy(busy = false, error = r.message) }
                }
                else -> _ui.update { it.copy(busy = false) }
            }
        }
    }

    /** 用配对码完成配对;deviceId 始终使用启动时生成的稳定 UUID。 */
    fun submitCode() {
        val code = _ui.value.code.trim()
        val baseUrl = _ui.value.selectedBaseUrl
        val deviceId = _ui.value.deviceId.ifBlank { "android-default" }
        if (code.isBlank()) {
            _ui.update { it.copy(error = "请输入配对码") }
            return
        }
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.verifyAndPair(baseUrl, deviceId, code)) {
                is PairingRepository.Result.Paired -> {
                    _ui.update { it.copy(busy = false, paired = true, message = "配对成功") }
                }
                is PairingRepository.Result.Failure -> {
                    _ui.update { it.copy(busy = false, error = r.message) }
                }
                else -> _ui.update { it.copy(busy = false) }
            }
        }
    }
}