package com.mediareview.app.feature.v2.nsfw

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class GeneratedReviewViewModel @Inject constructor(private val store: GeneratedBridgeStore) : ViewModel() {
    // 唯一生产绑定：尚无已冻结的增量/详情/评分合同，因此不能调用提议端点。
    private val controller = GeneratedReviewController(UnavailableGeneratedRepository())
    val state = controller.state
    private val mutableAddress = MutableStateFlow("")
    val address = mutableAddress.asStateFlow()
    private val mutableSettingsMessage = MutableStateFlow<String?>(null)
    val settingsMessage = mutableSettingsMessage.asStateFlow()
    private var foregroundJob: Job? = null

    init {
        viewModelScope.launch {
            try { mutableAddress.value = store.current().baseUrl }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableSettingsMessage.value = "生图连接配置不可用，请重新填写" }
        }
    }

    fun start() {
        if (foregroundJob?.isActive == true) return
        foregroundJob = viewModelScope.launch {
            do {
                controller.refresh()
                // 失败或能力缺失立即停止轮询；历史分页由用户显式加载。
                if (state.value.phase != GeneratedPhase.READY || state.value.hasMore) break
                delay(15_000)
            } while (true)
        }
    }
    fun stop() { foregroundJob?.cancel(); foregroundJob = null }
    fun select(id: String) = controller.select(id)

    fun saveConnection(address: String, token: String) {
        viewModelScope.launch {
            try {
                stop()
                store.save(address, token)
                mutableAddress.value = store.current().baseUrl
                mutableSettingsMessage.value = "连接配置已保存；图片审核能力仍待服务端提供"
                start()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableSettingsMessage.value = "保存失败，请检查服务根地址和独立凭据" }
        }
    }
}
