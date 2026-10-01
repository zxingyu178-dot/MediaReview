package com.mediareview.app.feature.v2.organize.libraries

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import com.mediareview.app.feature.v2.organize.data.LibrarySelectionItem
import com.mediareview.app.feature.v2.ui.V2Radius
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaAccent
import com.mediareview.app.ui.theme.MediaBackground
import com.mediareview.app.ui.theme.MediaSurfaceRaised
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary

/**
 * 媒体库管理（Stage 8C §37~§42）。
 *
 * - 复用 `GET /libraries` + `PUT /libraries/selection`（不增加第二套 Library API）；
 * - 勾选是本地编辑，点「应用」才提交；失败保持页面与草稿；
 * - 成功后由上层失效首页辅助缓存（folders/albums）并刷新首页数据（§41）；
 * - 全取消在 UI 提前阻止（「至少选择一个媒体库」），Server 仍最终校验（§42）。
 */
@Composable
fun LibraryManagerScreen(
    onBack: () -> Unit,
    onSelectionApplied: () -> Unit,
    modifier: Modifier = Modifier,
    vm: LibraryManagerViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        vm.applied.collect { onSelectionApplied() }
    }

    Box(modifier = modifier.fillMaxSize().background(MediaBackground).testTag("library_manager_screen")) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = V2Spacing.Sm, vertical = V2Spacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MediaTextPrimary,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "媒体库管理",
                        style = MaterialTheme.typography.titleLarge,
                        color = MediaTextPrimary,
                    )
                    Text(
                        text = "选择参与媒体墙与批阅的媒体库",
                        style = MaterialTheme.typography.bodySmall,
                        color = MediaTextSecondary,
                    )
                }
            }

            when {
                ui.demoUnavailable -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("媒体库管理仅在服务器模式可用", color = MediaTextPrimary)
                        Text(
                            text = "切换到「我的服务器」后可管理媒体库勾选",
                            color = MediaTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(
                                top = V2Spacing.Xs,
                                start = V2Spacing.Xl,
                                end = V2Spacing.Xl,
                            ),
                        )
                    }
                }
                ui.loading -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MediaTextSecondary)
                }
                ui.error != null -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("媒体库加载失败", color = MediaTextSecondary)
                        Text(
                            text = ui.error.orEmpty(),
                            color = MediaTextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(
                                top = V2Spacing.Xs,
                                start = V2Spacing.Lg,
                                end = V2Spacing.Lg,
                            ),
                        )
                        TextButton(onClick = { vm.load() }) {
                            Text("重新加载", color = MediaTextPrimary)
                        }
                    }
                }
                ui.libraries.isEmpty() -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("服务器上还没有媒体库", color = MediaTextSecondary)
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(
                            start = V2Spacing.Lg,
                            end = V2Spacing.Lg,
                            top = V2Spacing.Sm,
                            bottom = V2Spacing.Xl,
                        ),
                        verticalArrangement = Arrangement.spacedBy(V2Spacing.Md),
                    ) {
                        items(ui.libraries, key = { it.jellyfinId }) { library ->
                            LibraryRow(
                                library = library,
                                checked = library.jellyfinId in ui.draftSelected,
                                onToggle = { vm.toggle(library.jellyfinId) },
                            )
                        }
                    }
                    Button(
                        onClick = { vm.apply() },
                        enabled = ui.dirty && !ui.saving,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MediaAccent,
                            contentColor = MediaBackground,
                        ),
                        shape = RoundedCornerShape(V2Radius.Button),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = V2Spacing.Lg, vertical = V2Spacing.Md),
                    ) {
                        Text(if (ui.saving) "保存中…" else "应用")
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun LibraryRow(
    library: LibrarySelectionItem,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(V2Radius.Card))
            .background(MediaSurfaceRaised)
            .clickable(onClick = onToggle)
            .padding(horizontal = V2Spacing.Md, vertical = V2Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = MediaAccent,
                uncheckedColor = MediaTextSecondary,
                checkmarkColor = MediaBackground,
            ),
        )
        Text(
            text = library.name,
            style = MaterialTheme.typography.titleSmall,
            color = MediaTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}