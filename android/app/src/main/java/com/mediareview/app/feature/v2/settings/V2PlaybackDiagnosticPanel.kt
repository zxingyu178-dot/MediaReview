package com.mediareview.app.feature.v2.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.data.V2DataModeStore
import com.mediareview.app.feature.v2.data.server.V2DiagnosticCode
import com.mediareview.app.feature.v2.data.server.V2DiagnosticStage
import com.mediareview.app.feature.v2.data.server.V2StreamProbeResult
import com.mediareview.app.feature.v2.data.server.V2PlaybackDiagnostic
import com.mediareview.app.feature.v2.data.server.V2PlaybackDiagnosticReport
import com.mediareview.app.ui.theme.MediaTextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

data class V2PlaybackDiagnosticState(val busy: Boolean = false, val report: V2PlaybackDiagnosticReport? = null)

@HiltViewModel
class V2PlaybackDiagnosticViewModel @Inject constructor(
    private val diagnostic: V2PlaybackDiagnostic,
    profiles: ServerProfileStore,
    modes: V2DataModeStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(V2PlaybackDiagnosticState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var generation = 0L
    private var enabled = false
    init {
        viewModelScope.launch {
            // 凭据仅用于识别会话变化；不会进入诊断状态、UI 或日志。
            combine(profiles.profile, modes.mode) { profile, mode ->
                Triple(profile.baseUrl, profile.token, mode)
            }.distinctUntilChanged().collect { (_, _, mode) ->
                enabled = mode == V2DataMode.SERVER
                cancel()
            }
        }
    }
    fun start() {
        if (!enabled) return
        cancel()
        val expected = generation
        mutableState.value = V2PlaybackDiagnosticState(busy = true)
        request = viewModelScope.launch {
            try {
                val report = diagnostic.run()
                if (expected == generation) mutableState.value = V2PlaybackDiagnosticState(report = report)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (expected == generation) mutableState.value = V2PlaybackDiagnosticState(report = V2PlaybackDiagnosticReport(
                    V2DiagnosticStage.HEALTH,
                    V2StreamProbeResult(V2DiagnosticCode.SERVER_ERROR),
                ))
            }
            finally {
                if (expected == generation && mutableState.value.busy) mutableState.value = V2PlaybackDiagnosticState()
            }
        }
    }
    fun cancel() {
        generation++
        request?.cancel()
        request = null
        mutableState.value = V2PlaybackDiagnosticState()
    }
}

/** 只在 Server 设置区调用；离开时主动取消，避免结果串到另一次会话。 */
@Composable
fun V2PlaybackDiagnosticPanel(vm: V2PlaybackDiagnosticViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    DisposableEffect(vm) { onDispose { vm.cancel() } }
    Column {
        TextButton(onClick = vm::start, enabled = !state.busy) {
            Text(if (state.busy) "播放诊断中…" else "检查手机播放链路")
        }
        state.report?.let { report ->
            Text("${report.stage.label}：${report.phone.code.label}", style = MaterialTheme.typography.bodySmall)
            report.serverReachable?.let {
                Text("电脑端 → Jellyfin：${if (it) "可达" else "不可达"}；视频流：${report.serverStreamStatus}", style = MaterialTheme.typography.bodySmall)
                Text("设备播放凭据：${report.credentialStatus}", style = MaterialTheme.typography.bodySmall)
                Text("地址来源：${report.addressSource}；${if (report.authoritative) "权威地址" else "回退地址"}", style = MaterialTheme.typography.bodySmall)
            }
            if (report.destination.isNotBlank()) {
                Text("手机播放目标：${report.destination}", style = MaterialTheme.typography.bodySmall)
                Text(if (report.stage == V2DiagnosticStage.PHONE)
                    "手机已读取 ${report.phone.bytesRead} 字节（最多 1024）" else "手机直连：未检查",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("链路检查不代表真机持续播放验收。", style = MaterialTheme.typography.bodySmall, color = MediaTextSecondary)
        }
    }
}
