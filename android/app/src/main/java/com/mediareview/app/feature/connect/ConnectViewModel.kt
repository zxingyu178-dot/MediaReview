package com.mediareview.app.feature.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.core.network.TokenProvider
import com.mediareview.app.feature.connect.data.PairingRepository
import com.mediareview.app.feature.connect.data.ServerProfileView
import com.mediareview.app.feature.connect.data.ConnectionState
import com.mediareview.app.feature.connect.data.AuthenticationState
import com.mediareview.app.feature.connect.discovery.DiscoveredServer
import com.mediareview.app.feature.connect.discovery.ServerDiscovery
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 连接页三种工作模式切换表。 */
enum class ConnectStep { Discover, ManualIp, Pairing }

/** 默认 MediaReview 端口:无显式端口时补全。 */
private const val DEFAULT_PORT = 8766

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
    val connection: ConnectionState = ConnectionState(),
)

/**
 * 把用户输入规范化为 base url:
 * - 已带 http:// 则保持;
 * - 否则补 http://;
 * - 无显式端口(host:port)时补 :8766,有端口则尊重用户输入。
 */
internal fun normalizeBaseUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    require(trimmed.isNotBlank() && '\\' !in trimmed) { "服务器地址格式无效" }
    val withScheme = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
        trimmed
    } else {
        "http://$trimmed"
    }
    val uri = runCatching { java.net.URI(withScheme) }
        .getOrElse { throw IllegalArgumentException("服务器地址无法解析") }
    require(uri.scheme.lowercase() in setOf("http", "https")) { "仅支持 HTTP 或 HTTPS" }
    require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "服务器地址包含不支持的内容"
    }
    require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "服务器地址不能包含路径" }
    val normalizedHost = uri.host.trim('[', ']')
    val host = if (':' in normalizedHost) "[$normalizedHost]" else normalizedHost
    val port = if (uri.port == -1) DEFAULT_PORT else uri.port
    require(port in 1..65535) { "服务器端口无效" }
    return "${uri.scheme.lowercase()}://$host:$port"
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
        viewModelScope.launch {
            repository.connection.collect { connection ->
                _ui.update { it.copy(connection = connection) }
            }
        }
        viewModelScope.launch { restore() }
    }

    /** 启动时恢复已保存服务器(已配对则直接进入已配对态)。 */
    private suspend fun restore() {
        val saved: ServerProfileView = repository.load()
        _ui.update { it.copy(deviceId = saved.deviceId, connection = saved.connection) }
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

    /** 手动 IP:先健康检查再跳转到配对;无显式端口自动补 8766。 */
    fun testManualServer() {
        val ip = _ui.value.manualIp.trim()
        if (ip.isBlank()) {
            _ui.update { it.copy(error = "请输入服务器地址") }
            return
        }
        val baseUrl = runCatching { normalizeBaseUrl(ip) }.getOrElse {
            _ui.update { state -> state.copy(error = it.message ?: "服务器地址无效") }
            return
        }
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.checkHealthy(baseUrl)) {
                is PairingRepository.Result.HealthOk -> {
                    _ui.update {
                        it.copy(
                            busy = false,
                            selectedBaseUrl = baseUrl,
                            step = ConnectStep.Pairing,
                            connection = r.connection,
                        )
                    }
                }
                is PairingRepository.Result.Failure -> {
                    _ui.update { it.copy(busy = false, error = r.message, connection = r.connection) }
                }
                else -> _ui.update { it.copy(busy = false) }
            }
        }
    }

    /** 用配对码完成配对;deviceId 始终使用启动时生成的稳定 UUID。 */
    fun submitCode() {
        val code = _ui.value.code.trim()
        val baseUrl = _ui.value.selectedBaseUrl
        val deviceId = _ui.value.deviceId
        if (code.isBlank()) {
            _ui.update { it.copy(error = "请输入配对码") }
            return
        }
        if (deviceId.isBlank()) {
            _ui.update { it.copy(error = "正在准备设备身份,请稍后重试") }
            return
        }
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.verifyAndPair(baseUrl, code)) {
                is PairingRepository.Result.Paired -> {
                    _ui.update {
                        it.copy(
                            busy = false,
                            paired = true,
                            message = "配对成功",
                            connection = it.connection.copy(
                                authentication = AuthenticationState.Paired,
                            ),
                        )
                    }
                }
                is PairingRepository.Result.Failure -> {
                    _ui.update { it.copy(busy = false, error = r.message, connection = r.connection) }
                }
                else -> _ui.update { it.copy(busy = false) }
            }
        }
    }
}
