package com.mediareview.app.feature.v2.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mediareview.app.core.network.ThumbnailProfile
import com.mediareview.app.core.network.thumbnailProfileUrl
import com.mediareview.app.feature.v2.model.V2FolderNode
import com.mediareview.app.ui.theme.*

/** 与媒体卡相同的有界 profile 图片管线，封面来自 FolderTree。 */
@Composable
internal fun FolderCard(node: V2FolderNode, mode: HomeDisplayMode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val profile = if (mode == HomeDisplayMode.ONE_COLUMN) ThumbnailProfile.GRID_LARGE else ThumbnailProfile.GRID_SMALL
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MediaSurfaceRaised).clickable(onClick = onClick)) {
        AsyncImage(model = ImageRequest.Builder(LocalContext.current)
            .data(node.coverUrl?.let { thumbnailProfileUrl(it, profile) }).size(profile.width, profile.height).crossfade(false).build(),
            contentDescription = "${node.name}封面", contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).testTag("folder_cover_${node.id}"))
        Column(Modifier.padding(12.dp)) {
            Text("文件夹 · ${node.name}", color = MediaTextPrimary, maxLines = 2)
            Text("${node.recursiveMediaCount} 项 · ${node.childCount} 个子目录", color = MediaTextSecondary)
        }
    }
}
