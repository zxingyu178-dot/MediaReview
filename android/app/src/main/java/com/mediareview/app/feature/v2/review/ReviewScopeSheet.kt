package com.mediareview.app.feature.v2.review

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.home.FolderCard
import com.mediareview.app.feature.v2.home.HomeDisplayMode
import com.mediareview.app.feature.v2.model.folderBreadcrumbs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewScopeSheet(state: ReviewScopeSheetState, vm: V2ReviewViewModel) {
    if (!state.visible) return
    if (state.confirmReplacement) {
        AlertDialog(onDismissRequest = vm::cancelReviewScope,
            title = { Text("当前批阅尚未完成") },
            text = { Text("开始新的批阅会话？当前会话将结束，已有批阅记录会保留。") },
            confirmButton = { TextButton(vm::confirmReviewScope, enabled = !state.creating,
                modifier = Modifier.testTag("review_scope_confirm")) { Text("开始新会话") } },
            dismissButton = { TextButton(vm::cancelReviewScope, enabled = !state.creating,
                modifier = Modifier.testTag("review_scope_cancel")) { Text("取消") } })
        return
    }
    ModalBottomSheet(onDismissRequest = vm::cancelReviewScope) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("review_scope_sheet")) {
            Text("选择批阅范围", style = MaterialTheme.typography.titleLarge)
            TextButton(vm::chooseReviewScope, enabled = state.folderEnabled && !state.creating,
                modifier = Modifier.testTag("review_scope_choose_folders")) { Text("选择文件夹") }
            TextButton(vm::selectAllScope, enabled = !state.creating,
                modifier = Modifier.testTag("review_scope_all")) {
                if (state.scope.folderIds.isEmpty()) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "已选择", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text("全部视频")
            }
            if (!state.folderEnabled) Text("请升级服务器以选择文件夹批阅范围", Modifier.testTag("review_scope_upgrade"))
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.error != null && state.folderEnabled) TextButton(vm::chooseReviewScope) { Text("重试") }
            if (state.folderEnabled) {
                Row {
                    Checkbox(state.scope.includeDescendants, vm::setScopeDescendants, enabled = !state.creating,
                        modifier = Modifier.testTag("review_scope_descendants"))
                    Text("包含子文件夹", Modifier.padding(top = 12.dp))
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(state.folders, key = { it.id }) { node ->
                        Column(Modifier.padding(bottom = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = node.id in state.scope.folderIds,
                                    onCheckedChange = null, modifier = Modifier.size(40.dp))
                                Text(folderBreadcrumbs(state.folders, node.id).joinToString("/") { it.name })
                            }
                            FolderCard(node, HomeDisplayMode.ONE_COLUMN,
                                { vm.toggleScopeFolder(node.id) }, Modifier.testTag("review_scope_folder_${node.id}"))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(vm::cancelReviewScope, enabled = !state.creating,
                    modifier = Modifier.testTag("review_scope_cancel")) { Text("取消") }
                TextButton(vm::submitReviewScope, enabled = !state.loading && !state.creating,
                    modifier = Modifier.testTag("review_scope_start")) { Text("开始批阅") }
            }
        }
    }
}
