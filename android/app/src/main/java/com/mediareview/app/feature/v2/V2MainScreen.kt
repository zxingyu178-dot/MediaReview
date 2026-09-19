package com.mediareview.app.feature.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mediareview.app.feature.v2.home.FolderScreen
import com.mediareview.app.feature.v2.home.HomeScreen
import com.mediareview.app.feature.v2.home.V2BottomNavBar
import com.mediareview.app.feature.v2.home.V2HomeViewModel
import com.mediareview.app.feature.v2.home.V2MainTab
import com.mediareview.app.feature.v2.player.V2PlayerScreen
import com.mediareview.app.feature.v2.viewer.V2ImageViewer
import com.mediareview.app.feature.v2.ui.V2Radius
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaBackground
import com.mediareview.app.ui.theme.MediaSurface
import com.mediareview.app.ui.theme.MediaSurfaceRaised
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary

/** V2 主界面：主导航（首页/批阅/收藏/整理）+ 首页内部导航（home/folder/player/viewer）。 */
@Composable
fun V2MainScreen(
    vm: V2HomeViewModel,
    modifier: Modifier = Modifier,
) {
    var mainTab by remember { mutableStateOf(V2MainTab.HOME) }

    when (mainTab) {
        V2MainTab.HOME -> V2HomeNav(
            vm = vm,
            onSwitchTab = { mainTab = it },
            modifier = modifier,
        )
        V2MainTab.REVIEW -> PlaceholderPage(
            icon = Icons.Default.RateReview,
            title = "批阅模式",
            subtitle = "Stage 2",
            onSwitchTab = { mainTab = it },
            modifier = modifier,
        )
        V2MainTab.FAVORITES -> FavoritesPage(
            vm = vm,
            onSwitchTab = { mainTab = it },
            onOpenMedia = { m ->
                vm.openMedia(m.id)
            },
            modifier = modifier,
        )
        V2MainTab.ORGANIZE -> OrganizePage(
            vm = vm,
            onSwitchTab = { mainTab = it },
            modifier = modifier,
        )
    }
}

/** 首页内部导航：home / folder / player / viewer。 */
@Composable
private fun V2HomeNav(
    vm: V2HomeViewModel,
    onSwitchTab: (V2MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBottomBar = route == null || route == "home" || route == "folder/{folderId}"

    Box(modifier = modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = "home", modifier = Modifier.fillMaxSize()) {
            composable("home") {
                HomeScreen(
                    vm = vm,
                    onOpenMedia = { m ->
                        vm.openMedia(m.id)
                        if (m.isVideo) {
                            navController.navigate("player/${m.id}")
                        } else {
                            navController.navigate("viewer/${m.id}")
                        }
                    },
                    onOpenFolder = { f ->
                        navController.navigate("folder/${f.id}")
                    },
                )
            }
            composable("folder/{folderId}") { entry ->
                val folderId = entry.arguments?.getString("folderId") ?: ""
                val folderName = vm.folders.value.find { it.id == folderId }?.name ?: ""
                FolderScreen(
                    vm = vm,
                    folderId = folderId,
                    folderName = folderName,
                    onBack = { navController.popBackStack() },
                    onOpenMedia = { m ->
                        vm.openMedia(m.id)
                        if (m.isVideo) {
                            navController.navigate("player/${m.id}")
                        } else {
                            navController.navigate("viewer/${m.id}")
                        }
                    },
                )
            }
            composable("player/{mediaId}") { entry ->
                val mediaId = entry.arguments?.getString("mediaId") ?: ""
                val media = vm.mediaById(mediaId)
                if (media != null) {
                    V2PlayerScreen(
                        media = media,
                        playbackUri = vm.playbackUri(mediaId),
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable("viewer/{mediaId}") { entry ->
                val mediaId = entry.arguments?.getString("mediaId") ?: ""
                V2ImageViewer(
                    vm = vm,
                    initialMediaId = mediaId,
                    onBack = { navController.popBackStack() },
                )
            }
        }

        if (showBottomBar) {
            V2BottomNavBar(
                current = V2MainTab.HOME,
                onSelect = onSwitchTab,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** 收藏页：直接显示 Demo 收藏内容。 */
@Composable
private fun FavoritesPage(
    vm: V2HomeViewModel,
    onSwitchTab: (V2MainTab) -> Unit,
    onOpenMedia: (com.mediareview.app.feature.v2.model.V2Media) -> Unit,
    modifier: Modifier = Modifier,
) {
    val favorites by vm.favorites.collectAsState()
    Column(modifier = modifier.fillMaxSize().background(MediaBackground)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(vertical = V2Spacing.Md),
        ) {
            Text(
                text = "收藏",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = MediaTextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (favorites.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("暂无收藏内容", color = MediaTextSecondary, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = V2Spacing.Lg, end = V2Spacing.Lg, top = V2Spacing.Sm, bottom = V2Spacing.Xl),
                horizontalArrangement = Arrangement.spacedBy(V2Spacing.Md),
                verticalArrangement = Arrangement.spacedBy(V2Spacing.Md),
            ) {
                items(favorites, key = { it.id }) { media ->
                    com.mediareview.app.feature.v2.home.MediaCard(
                        media = media,
                        thumbUri = vm.thumbUri(media),
                        spriteUri = vm.spriteUri(media),
                        manifest = vm.spriteManifest(media),
                        onClick = { onOpenMedia(media) },
                    )
                }
            }
        }
        V2BottomNavBar(
            current = V2MainTab.FAVORITES,
            onSelect = onSwitchTab,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 整理页：按妙搭原型显示 Mock 卡片（待删除/重复媒体/已批阅/媒体库管理）。 */
@Composable
private fun OrganizePage(
    vm: V2HomeViewModel,
    onSwitchTab: (V2MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val all = vm.currentList.collectAsState().value
    val total = remember { 60 }
    val reviewedCount = all.count { it.isReviewed }
    Column(modifier = modifier.fillMaxSize().background(MediaBackground)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(vertical = V2Spacing.Md),
        ) {
            Text(
                text = "整理",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = MediaTextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = V2Spacing.Lg, end = V2Spacing.Lg, top = V2Spacing.Sm, bottom = V2Spacing.Xl),
            verticalArrangement = Arrangement.spacedBy(V2Spacing.Md),
        ) {
            item { OrganizeCard(Icons.Default.DeleteSweep, "待删除", "0 项待最终删除", MediaTextSecondary) }
            item { OrganizeCard(Icons.Default.CopyAll, "重复媒体", "2 组疑似重复（Mock）", MediaTextSecondary) }
            item { OrganizeCard(Icons.Default.CheckCircle, "已批阅", "$reviewedCount 项已批阅", MediaTextSecondary) }
            item { OrganizeCard(Icons.Default.Folder, "媒体库管理", "$total 项 · 6 个文件夹", MediaTextSecondary) }
        }
        V2BottomNavBar(
            current = V2MainTab.ORGANIZE,
            onSelect = onSwitchTab,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun OrganizeCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(V2Radius.Card))
            .background(MediaSurfaceRaised)
            .clickable(enabled = false) {}
            .padding(V2Spacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            icon,
            null,
            tint = tint,
            modifier = Modifier.size(28.dp),
        )
        Column(modifier = Modifier.padding(start = V2Spacing.Lg)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = MediaTextPrimary)
            Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = MediaTextSecondary)
        }
    }
}

/** 占位页（批阅 Stage 2）。 */
@Composable
private fun PlaceholderPage(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onSwitchTab: (V2MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(MediaBackground)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.Icon(
                    icon,
                    null,
                    tint = MediaTextSecondary,
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    title,
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                    color = MediaTextPrimary,
                    modifier = Modifier.padding(top = V2Spacing.Md),
                )
                Text(
                    subtitle,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = MediaTextSecondary,
                    modifier = Modifier.padding(top = V2Spacing.Xs),
                )
            }
        }
        V2BottomNavBar(
            current = V2MainTab.REVIEW,
            onSelect = onSwitchTab,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
