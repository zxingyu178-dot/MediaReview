package com.mediareview.app.feature.v2.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.mediareview.app.feature.v2.model.V2Album
import com.mediareview.app.feature.v2.ui.V2Radius
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaSurfaceRaised
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary

/**
 * 书架 = 照片相册（Stage 5）。
 * - 数据：List<V2Album>（无照片的文件夹已由数据层过滤）；
 * - 外观：一行两个、约 3:4 的"书"，单图封面 + 侧边书脊 + 轻微阴影层叠感；
 * - 信息：相册名 + "XX 张"（不再显示媒体总数或描述）。
 */
@Composable
fun ShelfGrid(
    albums: List<V2Album>,
    coverFor: (String) -> String?,
    onOpenAlbum: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = V2Spacing.Lg, end = V2Spacing.Lg, top = V2Spacing.Sm, bottom = V2Spacing.Xl,
        ),
        horizontalArrangement = Arrangement.spacedBy(V2Spacing.Xl),
        verticalArrangement = Arrangement.spacedBy(V2Spacing.Xl),
    ) {
        items(albums, key = { it.id }) { album ->
            AlbumBookCard(
                album = album,
                coverUri = coverFor(album.coverImageId ?: ""),
                onClick = { onOpenAlbum(album.id) },
            )
        }
    }
}

/** 相册书封卡：目标解码尺寸 ≈ 168dp 宽 * 2.75x = 462×616px（3:4）。 */
private const val ALBUM_COVER_WIDTH = 462
private const val ALBUM_COVER_HEIGHT = 616

@Composable
private fun AlbumBookCard(
    album: V2Album,
    coverUri: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(V2Radius.Card))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 书封面：3:4，单图 + 右侧书脊 + 轻层叠阴影
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .shadow(elevation = 6.dp, shape = RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp, topEnd = 4.dp, bottomEnd = 4.dp))
                    .background(MediaSurfaceRaised),
            ) {
                if (coverUri != null) {
                    // Stage6 轻量封面：3:4 书封约 168dp 宽 → 缩小解码 + 关 crossfade
                    val request = ImageRequest.Builder(LocalContext.current)
                        .data(coverUri)
                        .crossfade(false)
                        .size(ALBUM_COVER_WIDTH, ALBUM_COVER_HEIGHT)
                        .build()
                    SubcomposeAsyncImage(
                        model = request,
                        contentDescription = album.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // 右侧书脊渐变
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxSize()
                        .width(14.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    androidx.compose.ui.graphics.Color.Transparent,
                                    androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.12f),
                                    androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.28f),
                                ),
                            ),
                        ),
                )
            }
        }

        Text(
            text = album.name,
            style = MaterialTheme.typography.titleMedium,
            color = MediaTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = V2Spacing.Sm),
        )
        Text(
            text = "${album.imageCount} 张",
            style = MaterialTheme.typography.bodySmall,
            color = MediaTextSecondary,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}