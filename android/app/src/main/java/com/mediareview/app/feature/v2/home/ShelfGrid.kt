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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.mediareview.app.feature.v2.model.V2Album
import com.mediareview.app.feature.v2.ui.V2Radius
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaSurfaceRaised
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary
import com.mediareview.app.feature.v2.ui.components.EmptyState
import com.mediareview.app.feature.v2.ui.components.ErrorState

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
    error: String? = null,
    onRetry: () -> Unit = {},
) {
    if (albums.isEmpty()) {
        if (error != null) ErrorState(error, onRetry, modifier.fillMaxSize())
        else EmptyState("暂无相册", "有图片的文件夹会显示在这里", modifier.fillMaxSize())
        return
    }
    WindowCoverPrefetch(albums.map { it.coverUrl ?: coverFor(it.coverImageId.orEmpty()).orEmpty() }, albumCovers = true)
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = V2Spacing.Lg, end = V2Spacing.Lg, top = V2Spacing.Sm, bottom = V2Spacing.Xl,
        ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(V2Spacing.Lg),
    ) {
        items(albums, key = { it.id }) { album ->
            AlbumBookCard(
                album = album,
                coverUri = album.coverUrl?.takeIf { it.isNotBlank() } ?: coverFor(album.coverImageId ?: ""),
                onClick = { onOpenAlbum(album.id) },
            )
        }
    }
}

/** 相册书封使用固定ALBUM320×427变体。 */

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
            .semantics { contentDescription = "${album.name}，相册，${album.imageCount} 张" }
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Google Photos 式相册入口：大封面、弱装饰、信息紧贴内容。
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MediaSurfaceRaised),
            ) {
                if (coverUri != null) {
                    // 书封请求与窗口预取使用同一ALBUM URL和尺寸。
                    val context = LocalContext.current
                    val request = remember(context, coverUri) {
                        val data = com.mediareview.app.core.network.thumbnailProfileUrl(
                            coverUri,
                            com.mediareview.app.core.network.ThumbnailProfile.ALBUM,
                        )
                        ImageRequest.Builder(context).data(data).crossfade(false).size(320, 240)
                            .memoryCacheKey("album-book:$data").build()
                    }
                    SubcomposeAsyncImage(
                        model = request,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().testTag("album_cover_${album.id}"),
                    )
                }
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
