package com.mediareview.app.feature.v2.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mediareview.app.feature.v2.ui.components.FolderTile
import com.mediareview.app.feature.v2.model.V2FolderNode

/** 与媒体卡相同的有界 profile 图片管线，封面来自 FolderTree。 */
@Composable
internal fun FolderCard(node: V2FolderNode, mode: HomeDisplayMode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FolderTile(node = node, onClick = onClick, modifier = modifier, displayMode = mode)
}
