package com.mediareview.app.feature.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavController
import androidx.navigation.compose.composable
import coil.compose.AsyncImage
import com.mediareview.app.core.model.FavoriteItemDto
import com.mediareview.app.feature.player.PlayerDestinations
import com.mediareview.app.feature.viewer.ImageViewerDestinations

/** 喜欢页导航路由。 */
object FavoritesDestinations {
    const val FAVORITES = "favorites"
}

/** 注册喜欢页目的地。 */
fun NavGraphBuilder.favoritesGraph(navController: NavController) {
    composable(FavoritesDestinations.FAVORITES) {
        FavoritesScreen(
            onBack = { navController.popBackStack() },
            onOpenMedia = { media ->
                if (media.isVideo) {
                    navController.navigate(PlayerDestinations.build(media.media_id))
                } else {
                    navController.navigate(ImageViewerDestinations.build(media.media_id))
                }
            },
        )
    }
}

/** 喜欢页:列表展示,可点击打开,可取消喜欢。 */
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onOpenMedia: (com.mediareview.app.core.model.MediaSummary) -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("喜欢", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("返回") }
            }
            when {
                ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                ui.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(ui.error!!)
                }

                ui.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有喜欢的内容")
                }

                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.items, key = { it.media_id }) { fav -> FavoriteRow(fav, onOpenMedia, viewModel::remove) }
                }
            }
        }
    }
}

@Composable
private fun FavoriteRow(
    fav: FavoriteItemDto,
    onOpen: (com.mediareview.app.core.model.MediaSummary) -> Unit,
    onRemove: (String) -> Unit,
) {
    val media = fav.media
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { if (media != null) onOpen(media) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = media?.cover_url,
            contentDescription = media?.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                text = media?.name ?: fav.media_id,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (media?.isVideo == true) "视频" else "图片",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "♥",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .clickable { onRemove(fav.media_id) }
                .padding(8.dp),
        )
    }
}
