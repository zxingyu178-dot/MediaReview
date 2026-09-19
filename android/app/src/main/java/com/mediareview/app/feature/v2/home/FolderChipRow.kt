package com.mediareview.app.feature.v2.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.model.V2Folder
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.feature.v2.ui.V2Radius
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaBackground
import com.mediareview.app.ui.theme.MediaSurface
import com.mediareview.app.ui.theme.MediaSurfaceRaised
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary

/** 顶部文件夹分类胶囊横滚：全部 / 最近 / 各文件夹 / 更多。 */
@Composable
fun FolderChipRow(
    folders: List<V2Folder>,
    selectedFolderId: String?,
    selectedRecent: Boolean,
    onSelectAll: () -> Unit,
    onSelectRecent: () -> Unit,
    onSelectFolder: (String) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = V2Spacing.Lg, vertical = V2Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chip(text = "全部", selected = !selectedRecent && selectedFolderId == null, onClick = onSelectAll)
        Spacer(modifier = Modifier.size(V2Spacing.Sm))
        Chip(text = "最近", selected = selectedRecent, onClick = onSelectRecent)
        folders.forEach { folder ->
            Spacer(modifier = Modifier.size(V2Spacing.Sm))
            Chip(
                text = folder.name,
                selected = !selectedRecent && selectedFolderId == folder.id,
                onClick = { onSelectFolder(folder.id) },
            )
        }
        Spacer(modifier = Modifier.size(V2Spacing.Sm))
        Chip(text = "更多", selected = false, onClick = onMore)
        Spacer(modifier = Modifier.size(V2Spacing.Md))
    }
}

@Composable
private fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(V2Radius.Chip))
            .background(if (selected) V2Colors.Accent else MediaSurfaceRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = V2Spacing.Lg, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MediaBackground else MediaTextPrimary,
        )
    }
}

/** "更多"文件夹 Bottom Sheet。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreFoldersSheet(
    folders: List<V2Folder>,
    counts: Map<String, Int>,
    selectedFolderId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var dismissed by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = {
            dismissed = true
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = MediaSurface,
    ) {
        if (!dismissed) {
            Column(modifier = Modifier.padding(bottom = V2Spacing.Xxl)) {
                Text(
                    text = "全部文件夹",
                    style = MaterialTheme.typography.titleMedium,
                    color = MediaTextPrimary,
                    modifier = Modifier.padding(horizontal = V2Spacing.Xl, vertical = V2Spacing.Md),
                )
                folders.forEach { folder ->
                    HorizontalDivider(color = MediaTextSecondary.copy(alpha = 0.1f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(V2Radius.Sm))
                            .clickable {
                                onSelect(folder.id)
                            }
                            .padding(horizontal = V2Spacing.Xl, vertical = V2Spacing.Lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = folder.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selectedFolderId == folder.id) V2Colors.Accent else MediaTextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${counts[folder.id] ?: 0} 项",
                            style = MaterialTheme.typography.bodySmall,
                            color = MediaTextSecondary,
                        )
                    }
                }
            }
        }
    }
}
