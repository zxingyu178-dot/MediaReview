package com.mediareview.app.feature.v2.player.native

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.home.V2HomeViewModel
import com.mediareview.app.feature.v2.player.V2NativePlayerViewModel
import com.mediareview.app.feature.v2.viewer.state.ViewerPendingDeleteState

/** 正式 Full 入口：与 Viewer 共用 VM 的服务端队列，不在播放器层发网络请求。 */
@Composable
fun GsyNativePlayerWithPendingDelete(
    vm: V2HomeViewModel,
    playerViewModel: V2NativePlayerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by playerViewModel.state.collectAsState()
    val mediaId = state.currentMediaId.orEmpty()
    val dataMode by vm.dataMode.collectAsState()
    val pendingState by vm.viewerPendingDeleteState.collectAsState()
    val pendingBusy by vm.viewerPendingDeleteBusy.collectAsState()
    var session by remember(vm, state.currentMediaId, dataMode) { mutableStateOf<Long?>(null) }
    val snackbar = remember { SnackbarHostState() }
    DisposableEffect(vm, state.currentMediaId, dataMode) {
        val opened = vm.openViewerPendingDelete()
        session = opened
        onDispose { vm.closeViewerPendingDelete(opened); snackbar.currentSnackbarData?.dismiss() }
    }
    LaunchedEffect(vm, session) {
        vm.pendingDeleteMessages.collect { event ->
            if (event.session != session) return@collect
            val result = snackbar.showSnackbar(event.text, actionLabel = event.undoMediaId?.let { "撤销" })
            if (result == SnackbarResult.ActionPerformed) {
                event.undoMediaId?.let { vm.undoPendingDelete(event.session, it) }
            }
        }
    }
    Box(modifier.fillMaxSize()) {
        GsyNativePlayerScreen(
            state, playerViewModel::moveTo, playerViewModel::onPlaybackFailed,
            playerViewModel::retry, playerViewModel::reportProgress, onBack,
            onPlaybackStable = playerViewModel::onPlaybackStable,
            onReportLoadedProgress = playerViewModel::reportLoadedProgress,
            pendingDeleteEnabled = session != null && mediaId.isNotBlank() && pendingState is ViewerPendingDeleteState.Ready && !pendingBusy,
            isPendingDelete = (pendingState as? ViewerPendingDeleteState.Ready)?.ids?.contains(mediaId) == true,
            pendingDeleteLabel = when (pendingState) {
                ViewerPendingDeleteState.Loading -> "正在加载…"
                is ViewerPendingDeleteState.Error -> "状态未知 · 重试"
                else -> null
            },
            onTogglePendingDelete = { session?.let { vm.toggleViewerPendingDelete(it, mediaId) } },
            onRetryPendingDelete = if (pendingState is ViewerPendingDeleteState.Error) {
                { session?.let(vm::retryViewerPendingDelete); Unit }
            } else null,
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 160.dp))
    }
}
