package com.mediareview.app.feature.v2.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mediareview.app.ui.theme.MediaControlScrim
import com.mediareview.app.ui.theme.MediaTextPrimary

@Composable
fun ImageViewerTopBar(
    name: String,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePendingDelete: () -> Unit,
    isFavorite: Boolean,
    isPendingDelete: Boolean,
    pendingDeleteEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth().statusBarsPadding().background(MediaControlScrim)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("viewer_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = MediaTextPrimary)
        }
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            color = MediaTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("viewer_more")) {
                Icon(Icons.Default.MoreVert, "更多", tint = MediaTextPrimary)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("信息") }, onClick = { menuOpen = false; onInfo() })
                DropdownMenuItem(
                    text = { Text(if (isFavorite) "取消收藏" else "收藏") },
                    onClick = { menuOpen = false; onToggleFavorite() },
                )
                DropdownMenuItem(
                    text = { Text(if (isPendingDelete) "撤销待删除" else "加入待删除") },
                    enabled = pendingDeleteEnabled,
                    onClick = { menuOpen = false; onTogglePendingDelete() },
                )
            }
        }
    }
}
