package com.mediareview.app.feature.v2.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.mediareview.app.feature.v2.model.V2Folder
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.feature.v2.ui.V2Radius
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaSurfaceRaised
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary

/** 书架模式：文件夹表现为"书"，一行两个，支持单封面 / 四宫格封面。 */
@Composable
fun ShelfGrid(
    folders: List<V2Folder>,
    counts: Map<String, Int>,
    thumbFor: (String) -> String,
    onOpenFolder: (V2Folder) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = V2Spacing.Lg, end = V2Spacing.Lg, top = V2Spacing.Sm, bottom = V2Spacing.Xl,
        ),
        horizontalArrangement = Arrangement.spacedBy(V2Spacing.Md),
        verticalArrangement = Arrangement.spacedBy(V2Spacing.Md),
    ) {
        items(folders, key = { it.id }) { folder ->
            FolderShelfCard(
                folder = folder,
                count = counts[folder.id] ?: 0,
                thumbFor = thumbFor,
                onClick = { onOpenFolder(folder) },
            )
        }
    }
}

@Composable
private fun FolderShelfCard(
    folder: V2Folder,
    count: Int,
    thumbFor: (String) -> String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(V2Radius.Card))
            .background(MediaSurfaceRaised)
            .clickable(onClick = onClick),
    ) {
        // 封面区：1 个单封面 / 4 个四宫格
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(V2Spacing.Md),
        ) {
            val covers = folder.coverMediaIds.take(4)
            when (covers.size) {
                1 -> QuadrantCover(uris = listOf(thumbFor(covers[0])), emptyColor = V2Colors.CardScrim, showEmpty = false)
                else -> QuadrantCover(
                    uris = listOfNotNull(
                        covers.getOrNull(0)?.let { thumbFor(it) },
                        covers.getOrNull(1)?.let { thumbFor(it) },
                        covers.getOrNull(2)?.let { thumbFor(it) },
                        covers.getOrNull(3)?.let { thumbFor(it) },
                    ),
                    emptyColor = V2Colors.CardScrim,
                    showEmpty = true,
                )
            }
        }

        Text(
            text = folder.name,
            style = MaterialTheme.typography.titleMedium,
            color = MediaTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = V2Spacing.Md),
        )
        Text(
            text = "$count 项 · ${folder.description}",
            style = MaterialTheme.typography.bodySmall,
            color = MediaTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = V2Spacing.Md, end = V2Spacing.Md, bottom = V2Spacing.Md),
        )
    }
}

/** 四宫格封面：按可用封面填充，不足用占位色块。 */
@Composable
private fun QuadrantCover(
    uris: List<String>,
    emptyColor: androidx.compose.ui.graphics.Color,
    showEmpty: Boolean,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.weight(1f)) {
            CoverCell(uris.getOrNull(0), emptyColor, Modifier.weight(1f))
            CoverCell(uris.getOrNull(1), emptyColor, Modifier.weight(1f))
        }
        Row(modifier = Modifier.weight(1f)) {
            CoverCell(uris.getOrNull(2), emptyColor, Modifier.weight(1f))
            CoverCell(uris.getOrNull(3), emptyColor, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CoverCell(
    uri: String?,
    emptyColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(V2Radius.Sm))
            .background(emptyColor),
    ) {
        if (uri != null) {
            SubcomposeAsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
