package com.mediareview.app.feature.v2.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2SortField
import com.mediareview.app.feature.v2.model.V2SortOrder
import com.mediareview.app.feature.v2.ui.V2Radius
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaBackground
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary

/** 文件夹内部页：返回 + 标题/计数 + 排序下拉 + 双列媒体网格。 */
@Composable
fun FolderScreen(
    vm: V2HomeViewModel,
    folderId: String,
    folderName: String,
    onBack: () -> Unit,
    onOpenMedia: (V2Media) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list by vm.currentList.collectAsState()
    val sortSpec by vm.sortSpec.collectAsState()

    LaunchedEffect(folderId) {
        vm.selectFolder(folderId)
    }

    var sortMenuOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().background(MediaBackground)) {
        // 顶部
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
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
                    text = folderName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MediaTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${list.size} 项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MediaTextSecondary,
                )
            }
            // 排序下拉
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(V2Radius.Button))
                        .clickable { sortMenuOpen = true }
                        .padding(horizontal = V2Spacing.Md, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${sortFieldLabel(sortSpec.field)} ${sortOrderLabel(sortSpec.order)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MediaTextSecondary,
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = MediaTextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    V2SortField.entries.forEach { field ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = sortFieldLabel(field),
                                    color = if (field == sortSpec.field) MediaTextPrimary else MediaTextSecondary,
                                )
                            },
                            onClick = {
                                val newOrder = if (field == sortSpec.field &&
                                    sortSpec.order == V2SortOrder.DESC
                                ) V2SortOrder.ASC else sortSpec.order
                                vm.setSortSpec(sortSpec.copy(field = field, order = newOrder))
                                sortMenuOpen = false
                            },
                        )
                    }
                    Spacer(modifier = Modifier.size(4.dp))
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "顺序：${sortOrderLabel(sortSpec.order)}",
                                color = MediaTextSecondary,
                            )
                        },
                        onClick = {
                            vm.setSortSpec(
                                sortSpec.copy(order = if (sortSpec.order == V2SortOrder.DESC) V2SortOrder.ASC else V2SortOrder.DESC),
                            )
                            sortMenuOpen = false
                        },
                    )
                }
            }
        }

        // 双列网格
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = V2Spacing.Lg, end = V2Spacing.Lg, top = V2Spacing.Sm, bottom = V2Spacing.Xl,
            ),
            horizontalArrangement = Arrangement.spacedBy(V2Spacing.Md),
            verticalArrangement = Arrangement.spacedBy(V2Spacing.Md),
        ) {
            items(list, key = { it.id }) { media ->
                MediaCard(
                    media = media,
                    coverUri = vm.coverUri(media),
                    spriteUri = vm.spriteUri(media),
                    manifest = vm.spriteManifest(media),
                    onClick = { onOpenMedia(media) },
                )
            }
        }
    }
}
