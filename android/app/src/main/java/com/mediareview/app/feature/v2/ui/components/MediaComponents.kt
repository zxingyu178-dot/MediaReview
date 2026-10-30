package com.mediareview.app.feature.v2.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.mediareview.app.core.network.ThumbnailProfile
import com.mediareview.app.core.network.imageFailureDescription
import com.mediareview.app.core.network.thumbnailProfileUrl
import com.mediareview.app.feature.v2.home.HomeDisplayMode
import com.mediareview.app.feature.v2.home.MediaTitlePresentation
import com.mediareview.app.feature.v2.home.formatDuration
import com.mediareview.app.feature.v2.home.mediaTitlePresentation
import com.mediareview.app.feature.v2.model.V2FolderNode
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.ui.theme.*

@Composable
fun MediaThumb(
    media: V2Media,
    coverUri: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    displayMode: HomeDisplayMode = HomeDisplayMode.TWO_COLUMN,
    pendingDelete: Boolean = false,
) {
    val context = LocalContext.current
    var retry by remember(coverUri) { mutableIntStateOf(0) }
    val profile = if (displayMode == HomeDisplayMode.ONE_COLUMN) ThumbnailProfile.GRID_LARGE else ThumbnailProfile.GRID_SMALL
    val request = remember(coverUri, profile, retry) {
        ImageRequest.Builder(context)
            .data(thumbnailProfileUrl(coverUri, profile))
            .size(profile.width, profile.height)
            .memoryCacheKey("$coverUri:${profile.name}:$retry")
            .crossfade(false)
            .build()
    }
    val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Crop)
    val talkBackLabel = remember(media.id, media.name, media.isVideo, media.durationMs, media.isFavorite, pendingDelete) {
        buildString {
            append(media.name)
            append(if (media.isVideo) "，视频，时长${formatDuration(media.durationMs)}" else "，图片")
            if (pendingDelete) append("，已加入待删除")
            if (media.isFavorite) append("，已收藏")
        }
    }
    Column(
        modifier.fillMaxWidth()
            .semantics { contentDescription = talkBackLabel }
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(MediaRadii.XSmall))
                .background(MediaSkeleton),
        ) {
            when (val state = painter.state) {
                is AsyncImagePainter.State.Error -> {
                    Box(
                        Modifier.fillMaxSize().clickable { retry++ }.testTag("media_retry_${media.id}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.BrokenImage, null, tint = MediaTextSecondary)
                            Text(imageFailureDescription(state.result.throwable), style = MaterialTheme.typography.labelSmall,
                                color = MediaTextSecondary, maxLines = 1)
                            Text("点击重试", style = MaterialTheme.typography.labelSmall, color = MediaAccent)
                        }
                    }
                }
                is AsyncImagePainter.State.Success -> Image(
                    painter = painter,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> LoadingSkeleton(Modifier.fillMaxSize(), showIcon = true)
            }
            if (pendingDelete) {
                Surface(
                    color = MediaDanger.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(MediaRadii.Small),
                    modifier = Modifier.align(Alignment.TopStart).padding(MediaSpacing.XSmall)
                        .testTag("pending_delete_${media.id}"),
                ) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DeleteOutline, null, modifier = Modifier.size(13.dp), tint = MediaTextPrimary)
                        Text("待删除", style = MaterialTheme.typography.labelSmall, color = MediaTextPrimary)
                    }
                }
            }
            if (media.isVideo) {
                Surface(
                    color = MediaControlScrim,
                    shape = RoundedCornerShape(MediaRadii.Small),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(MediaSpacing.XSmall),
                ) {
                    Text(formatDuration(media.durationMs), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = MediaOnImmersive, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (media.isFavorite) {
                Icon(
                    Icons.Default.Favorite,
                    "已收藏",
                    tint = MediaFavorite,
                    modifier = Modifier.align(Alignment.TopEnd).padding(MediaSpacing.Small).size(18.dp),
                )
            }
            if (mediaTitlePresentation(media.isVideo) == MediaTitlePresentation.IMAGE_OVERLAY) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.64f))))
                        .padding(start = MediaSpacing.Small, end = MediaSpacing.Small, top = 22.dp, bottom = 6.dp),
                ) {
                    Text(
                        media.name,
                        color = MediaOnImmersive.copy(alpha = 0.86f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (mediaTitlePresentation(media.isVideo) == MediaTitlePresentation.VIDEO_LABEL) {
            Text(
                media.name,
                modifier = Modifier.padding(top = MediaSpacing.XSmall, start = 2.dp, end = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MediaTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (displayMode == HomeDisplayMode.ONE_COLUMN) {
            Text(
                listOf(media.folderName, if (media.isVideo) "视频" else "图片").filter(String::isNotBlank).joinToString(" · "),
                modifier = Modifier.padding(start = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MediaTextSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun FolderTile(
    node: V2FolderNode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    displayMode: HomeDisplayMode = HomeDisplayMode.TWO_COLUMN,
) {
    val profile = if (displayMode == HomeDisplayMode.ONE_COLUMN) ThumbnailProfile.GRID_LARGE else ThumbnailProfile.GRID_SMALL
    Column(
        modifier.fillMaxWidth()
            .semantics { contentDescription = "${node.name}，文件夹，共${node.recursiveMediaCount}项，当前目录${node.directMediaCount}项，${node.childCount}个子目录" }
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(MediaRadii.Small)).background(MediaSurfaceRaised),
        ) {
            val cover = node.coverUrl ?: node.directCoverUrl
            if (cover.isNullOrBlank()) {
                Icon(Icons.Default.Folder, "${node.name}文件夹", tint = MediaTextSecondary,
                    modifier = Modifier.size(42.dp).align(Alignment.Center))
            } else {
                val context = LocalContext.current
                val request = remember(context, cover, profile) {
                    val data = thumbnailProfileUrl(cover, profile)
                    ImageRequest.Builder(context).data(data).size(profile.width, profile.height)
                        .memoryCacheKey("folder:$data:${profile.name}").crossfade(false).build()
                }
                val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Crop)
                when (painter.state) {
                    is AsyncImagePainter.State.Success -> Image(
                        painter, null, Modifier.fillMaxSize().testTag("folder_cover_${node.id}"),
                        contentScale = ContentScale.Crop,
                    )
                    is AsyncImagePainter.State.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Folder, "封面加载失败", tint = MediaTextSecondary, modifier = Modifier.size(42.dp))
                    }
                    else -> LoadingSkeleton(Modifier.fillMaxSize(), showIcon = true)
                }
            }
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(MediaControlScrimMedium).padding(horizontal = MediaSpacing.Small, vertical = 6.dp),
            ) {
                Column {
                    Text(node.name, color = MediaOnImmersive, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("共 ${node.recursiveMediaCount} 项 · 当前 ${node.directMediaCount} 项 · ${node.childCount} 个子目录",
                        color = MediaOnImmersive.copy(alpha = 0.78f), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = MediaDimensions.MinimumTouchTarget),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MediaTextPrimary, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun FilterChipRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MediaSpacing.Small)) {
        options.forEachIndexed { index, label ->
            FilterChip(selected = index == selectedIndex, onClick = { onSelect(index) }, label = { Text(label) })
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) = StateMessage(title, message, modifier, Icons.Default.Folder, actionLabel, onAction)

@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind = remember(message) { classifyUiError(message) }
    val title = when (kind) {
        UiErrorKind.NETWORK -> "网络连接失败"
        UiErrorKind.SOURCE -> "媒体源不可用"
        UiErrorKind.PERMISSION -> "没有访问权限"
        UiErrorKind.UNKNOWN -> "加载失败"
    }
    val icon = when (kind) {
        UiErrorKind.NETWORK -> Icons.Default.CloudOff
        UiErrorKind.SOURCE -> Icons.Default.Storage
        UiErrorKind.PERMISSION -> Icons.Default.Lock
        UiErrorKind.UNKNOWN -> Icons.Default.BrokenImage
    }
    StateMessage(title, message, modifier, icon, "重试", onRetry)
}

enum class UiErrorKind { NETWORK, SOURCE, PERMISSION, UNKNOWN }

fun classifyUiError(message: String): UiErrorKind {
    val normalized = message.lowercase()
    return when {
        listOf("网络", "连接", "超时", "timeout", "offline", "socket", "dns").any(normalized::contains) -> UiErrorKind.NETWORK
        listOf("权限", "未授权", "forbidden", "unauthorized", "permission", "401", "403").any(normalized::contains) -> UiErrorKind.PERMISSION
        listOf("媒体源", "数据源", "媒体库", "source", "library", "jellyfin").any(normalized::contains) -> UiErrorKind.SOURCE
        else -> UiErrorKind.UNKNOWN
    }
}

@Composable
private fun StateMessage(
    title: String,
    message: String,
    modifier: Modifier,
    icon: ImageVector,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    Column(modifier.fillMaxSize().padding(MediaSpacing.Large), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = MediaTextSecondary, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MediaTextPrimary,
            modifier = Modifier.padding(top = MediaSpacing.Regular))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MediaTextSecondary,
            modifier = Modifier.padding(top = MediaSpacing.XSmall))
        if (actionLabel != null && onAction != null) TextButton(
            onClick = onAction,
            modifier = Modifier.heightIn(min = MediaDimensions.MinimumTouchTarget),
        ) { Text(actionLabel) }
    }
}

@Composable
fun LoadingSkeleton(modifier: Modifier = Modifier, showIcon: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(0.45f, 0.8f,
        infiniteRepeatable(tween(MediaMotion.StandardMillis), RepeatMode.Reverse), label = "skeleton_alpha")
    Box(modifier.background(MediaSkeleton.copy(alpha = alpha)), contentAlignment = Alignment.Center) {
        if (showIcon) Icon(Icons.Default.BrokenImage, null, tint = MediaTextSecondary.copy(alpha = 0.34f))
    }
}

@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = MediaSpacing.XSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.testTag("folder_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = MediaTextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = MediaSpacing.Small))
        actions()
    }
}
