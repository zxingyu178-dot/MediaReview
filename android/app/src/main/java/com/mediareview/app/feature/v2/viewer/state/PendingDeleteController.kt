package com.mediareview.app.feature.v2.viewer.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ViewerPendingDeleteState {
    data object Loading : ViewerPendingDeleteState
    data class Ready(val ids: Set<String>) : ViewerPendingDeleteState
    data class Error(val message: String) : ViewerPendingDeleteState
}

/** VM 持有的真实队列状态；代次隔离离页/换源的在途结果。 */
data class PendingDeleteMessage(val text: String, val session: Long, val undoMediaId: String? = null)

internal class PendingDeleteController(
    private val scope: CoroutineScope,
    private val read: suspend () -> Set<String>,
    private val write: suspend (String, Boolean) -> Boolean,
    private val message: (String) -> Unit,
    private val eventMessage: ((PendingDeleteMessage) -> Unit)? = null,
) {
    private val _state = MutableStateFlow<ViewerPendingDeleteState>(ViewerPendingDeleteState.Loading)
    val state: StateFlow<ViewerPendingDeleteState> = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private var generation = 0L
    private var active = false
    private var job: Job? = null

    fun open(): Long {
        invalidate()
        active = true
        val session = generation
        load(session)
        return session
    }

    fun close(session: Long) {
        if (isCurrent(session)) invalidate()
    }

    fun invalidate() {
        generation++
        active = false
        job?.cancel()
        job = null
        _busy.value = false
        _state.value = ViewerPendingDeleteState.Loading
    }

    fun retry(session: Long) {
        if (isCurrent(session) && _state.value is ViewerPendingDeleteState.Error) load(session)
    }

    private fun load(session: Long) {
        _state.value = ViewerPendingDeleteState.Loading
        job = scope.launch {
            try {
                val ids = read()
                if (isCurrent(session)) _state.value = ViewerPendingDeleteState.Ready(ids.toSet())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (isCurrent(session)) _state.value = ViewerPendingDeleteState.Error("待删除状态加载失败")
            }
        }
    }

    fun toggle(session: Long, mediaId: String) {
        val known = _state.value as? ViewerPendingDeleteState.Ready ?: return
        setPending(session, mediaId, mediaId !in known.ids)
    }

    /** Snackbar 的撤销绑定原媒体和代次，绝不按当前页反向 toggle。 */
    fun undo(session: Long, mediaId: String) = setPending(session, mediaId, false)

    fun isCurrentSession(session: Long): Boolean = isCurrent(session)

    private fun setPending(session: Long, mediaId: String, target: Boolean) {
        if (!isCurrent(session) || _busy.value) return
        val known = _state.value as? ViewerPendingDeleteState.Ready ?: return
        if ((mediaId in known.ids) == target) return
        _busy.value = true
        job = scope.launch {
            try {
                val confirmed = write(mediaId, target)
                if (!isCurrent(session)) return@launch
                if (confirmed) {
                    _state.value = ViewerPendingDeleteState.Ready(
                        if (target) known.ids + mediaId else known.ids - mediaId,
                    )
                    val event = PendingDeleteMessage(
                        if (target) "已加入待删除" else "已撤销待删除", session,
                        undoMediaId = mediaId.takeIf { target },
                    )
                    if (eventMessage != null) eventMessage.invoke(event) else message(event.text)
                } else {
                    publishFailure(session, target)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (isCurrent(session)) publishFailure(session, target)
            } finally {
                if (isCurrent(session)) _busy.value = false
            }
        }
    }

    private fun publishFailure(session: Long, target: Boolean) {
        val text = if (target) "加入待删除失败" else "撤销待删除失败"
        message(text)
        eventMessage?.invoke(PendingDeleteMessage(text, session))
    }

    private fun isCurrent(session: Long) = active && generation == session
}
