package com.mediareview.app.feature.v2.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.ui.theme.MediaControlScrim
import com.mediareview.app.ui.theme.MediaTextPrimary

/**
 * 图片查看器底部控制层：收藏 / 信息 / 待删除。
 */
@Composable
fun ImageViewerBottomBar(
    isFavorite: Boolean,
    isPendingDelete: Boolean,
    onToggleFavorite: () -> Unit,
    onInfo: () -> Unit,
    onTogglePendingDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MediaControlScrim)
            .navigationBarsPadding()
            .padding(horizontal = 32.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomAction(
            icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            label = if (isFavorite) "已收藏" else "收藏",
            tint = if (isFavorite) V2Colors.Favorite else MediaTextPrimary,
            tag = "viewer_favorite",
            modifier = Modifier.weight(1f),
            onClick = onToggleFavorite,
        )
        BottomAction(
            icon = Icons.Default.Info,
            label = "信息",
            tint = MediaTextPrimary,
            tag = "viewer_info",
            modifier = Modifier.weight(1f),
            onClick = onInfo,
        )
        BottomAction(
            icon = Icons.Default.DeleteOutline,
            label = if (isPendingDelete) "已标记" else "待删除",
            tint = if (isPendingDelete) V2Colors.Accent else MediaTextPrimary,
            tag = "viewer_delete",
            modifier = Modifier.weight(1f),
            onClick = onTogglePendingDelete,
        )
    }
}

@Composable
private fun BottomAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(26.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}
