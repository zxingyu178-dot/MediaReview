package com.mediareview.app.feature.v2.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.mediareview.app.core.network.ThumbnailProfile
import com.mediareview.app.core.network.thumbnailProfileUrl
import com.mediareview.app.core.network.imageFailureDescription
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.perf.V2Perf
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.feature.v2.ui.V2Radius
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaSurfaceRaised
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary

/** V2 媒体卡片：只显示静态封面，普通点击浏览。 */

@Composable
fun MediaCard(
    media: V2Media,
    coverUri: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    displayMode: HomeDisplayMode = HomeDisplayMode.TWO_COLUMN,
) {
    val context = LocalContext.current

    // 下载变体与解码目标一致；单列480×270、双列256×144。
    val coverRequest = remember(coverUri, displayMode) {
        val profile = if (displayMode == HomeDisplayMode.ONE_COLUMN) ThumbnailProfile.GRID_LARGE else ThumbnailProfile.GRID_SMALL
        ImageRequest.Builder(context)
            .data(thumbnailProfileUrl(coverUri, profile))
            .crossfade(false)
            .size(profile.width, profile.height)
            .build()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(V2Radius.Card))
            .background(MediaSurfaceRaised)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 封面
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = V2Radius.Card, topEnd = V2Radius.Card))
                    .background(V2Colors.CardScrim),
            ) {
                // Skeleton 固定铺底：不依赖 Subcompose slot，图片就绪后自然盖住它。
                // Stage 8A.1 §11：LazyVerticalGrid 卡片内不再使用 SubcomposeAsyncImage
                // （Subcompose 会在滚动时阻塞测量，是"封面一张张慢慢冒出来"的主因之一）。
                CoverSkeleton()
                val coverPainter = rememberAsyncImagePainter(
                    model = coverRequest,
                    contentScale = ContentScale.Crop,
                )
                // Stage 8A.1 性能打点: 首张封面请求 → 首批 8 张封面就绪(单调时钟)
                LaunchedEffect(coverPainter.state) {
                    when (coverPainter.state) {
                        is AsyncImagePainter.State.Loading -> V2Perf.coverRequest(media.id)
                        is AsyncImagePainter.State.Success -> V2Perf.coverSuccess(media.id)
                        else -> Unit
                    }
                }
                Image(
                    painter = coverPainter,
                    contentDescription = media.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (coverPainter.state is AsyncImagePainter.State.Error) {
                    CoverPlaceholder((coverPainter.state as AsyncImagePainter.State.Error).result.throwable)
                }

                // 时长胶囊（视频）
                if (media.isVideo) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(V2Spacing.Sm)
                            .clip(RoundedCornerShape(6.dp))
                            .background(V2Colors.TimeCapsule)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = formatDuration(media.durationMs),
                            color = MediaTextPrimary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                // 收藏角标
                if (media.isFavorite) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "已收藏",
                        tint = V2Colors.Favorite,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(V2Spacing.Sm)
                            .size(18.dp),
                    )
                }

            }

            // 标题 + 次信息（紧凑垂直内边距，提升首屏排数）
            Column(modifier = Modifier.padding(horizontal = V2Spacing.Sm, vertical = V2Spacing.Sm)) {
                Text(
                    text = media.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MediaTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = (if (displayMode == HomeDisplayMode.ONE_COLUMN)
                        listOf(media.folderName, if (media.isVideo) formatDuration(media.durationMs) else "图片")
                        else listOf(media.code, media.folderName))
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MediaTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 封面加载中 Skeleton：轻量呼吸色块 + 居中图标（非纯黑空块）。 */
@Composable
private fun CoverSkeleton() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(V2Colors.Skeleton),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.BrokenImage,
            contentDescription = null,
            tint = MediaTextSecondary.copy(alpha = 0.35f),
            modifier = Modifier.size(28.dp),
        )
    }
}

/** 封面加载失败统一占位（网络异常 / Server 缺失也不出现黑墙）。 */
@Composable
private fun CoverPlaceholder(error: Throwable) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(V2Colors.CardScrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.BrokenImage,
                contentDescription = null,
                tint = MediaTextSecondary.copy(alpha = 0.55f),
                modifier = Modifier.size(26.dp),
            )
            Text(
                text = imageFailureDescription(error),
                style = MaterialTheme.typography.labelSmall,
                color = MediaTextSecondary.copy(alpha = 0.6f),
            )
        }
    }
}
