package com.mediareview.app.feature.v2.home

import com.mediareview.app.feature.v2.data.server.V2ServerStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 瞬时业务断线只触发一组有界健康探测，不重试认证错误或改变配对。 */
internal class ServerRecovery(
    private val scope: CoroutineScope,
    private val status: StateFlow<V2ServerStatus>,
    private val enabled: () -> Boolean,
    private val probe: suspend () -> Unit,
    private val recovered: suspend () -> Unit,
    private val generation: () -> Long = { 0L },
) {
    private var observer: Job? = null
    private var worker: Job? = null
    fun start() {
        if (observer != null) return
        observer = scope.launch {
            status.collect { current ->
                if (current == V2ServerStatus.Offline) retry()
            }
        }
    }
    /** 前台恢复或新网络可用时重新打开有界重试窗口，不在后台无限轮询。 */
    fun retry() {
                if (status.value == V2ServerStatus.Offline && enabled() && worker?.isActive != true) {
                    val source = generation()
                    worker = scope.launch {
                        for (backoff in listOf(2_000L, 5_000L, 15_000L)) {
                            delay(backoff)
                            if (!enabled() || source != generation() || status.value != V2ServerStatus.Offline) return@launch
                            probe()
                            if (!enabled() || source != generation()) return@launch
                            if (status.value == V2ServerStatus.Online) { recovered(); return@launch }
                            if (status.value != V2ServerStatus.Offline) return@launch
                        }
                    }
                }
    }
}
