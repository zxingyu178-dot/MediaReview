package com.mediareview.app.feature.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.material3.Scaffold
import com.mediareview.app.feature.v2.home.AlbumScreen
import com.mediareview.app.feature.v2.home.FolderScreen
import com.mediareview.app.feature.v2.home.HomeScreen
import com.mediareview.app.feature.v2.home.V2BottomNavBar
import com.mediareview.app.feature.v2.home.V2HomeViewModel
import com.mediareview.app.feature.v2.home.V2MainTab
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.organize.OrganizePage
import com.mediareview.app.feature.v2.organize.delete.DeleteQueueScreen
import com.mediareview.app.feature.v2.organize.duplicates.DuplicateCompareScreen
import com.mediareview.app.feature.v2.organize.duplicates.DuplicatesScreen
import com.mediareview.app.feature.v2.organize.libraries.LibraryManagerScreen
import com.mediareview.app.feature.v2.player.V2NativePlayerViewModel
import com.mediareview.app.feature.v2.player.native.GsyNativePlayerScreen
import com.mediareview.app.feature.v2.player.native.state.PlaybackContext
import com.mediareview.app.feature.v2.player.native.state.PlaybackQueueItem
import com.mediareview.app.feature.v2.releasenotes.WhatsNewSheet
import com.mediareview.app.feature.v2.releasenotes.WhatsNewViewModel
import com.mediareview.app.feature.v2.review.V2ReviewScreen
import com.mediareview.app.feature.v2.review.V2ReviewUiState
import com.mediareview.app.feature.v2.review.V2ReviewViewModel
import com.mediareview.app.feature.v2.settings.V2DataSourceSheet
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.feature.v2.viewer.V2ImageViewer
import com.mediareview.app.ui.theme.MediaBackground
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary

/**
 * V2 主界面（Root Scaffold + NavHost）：
 * 一级 Tab：home / review / favorites / organize；
 * 全局详情：folder/{folderId} / album/{albumId} / player/{mediaId} / viewer/{mediaId}。
 *
 * - Root Scaffold 提供 bottomBar（仅 Tab route 显示），innerPadding 下发给 Tab 内容，
 *   内容区域天然结束在 BottomNav 上方，最后一行不再被遮挡；
 * - Player / Viewer / Album 不显示底部导航，Back 返回原 Tab（收藏→Viewer→Back→收藏）；
 * - 所有 Tab 内容共享该 NavController，统一经 MediaNavigator 路由。
 */
@Composable
fun V2MainScreen(
    vm: V2HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val reviewViewModel: V2ReviewViewModel = hiltViewModel()
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = MediaNavigator.isTabRoute(currentRoute)
    val dataMode by vm.dataMode.collectAsState()
    // 新版本首次启动的更新日志（Stage 8C.1 §38~§44）：Overlay，不阻塞首页加载
    val whatsNewViewModel: WhatsNewViewModel = hiltViewModel()
    val whatsNew by whatsNewViewModel.state.collectAsState()

    // 数据源设置（Demo / 我的服务器 + 首次配置）：V2 内部 Sheet，任何时候都能切换
    var dataSourceSheetOpen by remember { mutableStateOf(false) }
    // 一次性用户提示（收藏失败等）统一在根 Scaffold 上显示，任何页面都能看到
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.messages.collect { message -> snackbar.showSnackbar(message) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MediaBackground,
        // 页面顶部各自的 statusBarsPadding、BottomNav 内部 navigationBarsPadding 已处理 inset；
        // Scaffold 只负责下发 bottomBar 高度，避免双重 padding 或底部大块空白。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showBottomBar) {
                V2BottomNavBar(
                    current = currentTab(currentRoute),
                    onSelect = { tab ->
                        navController.navigate(MediaNavigator.tabRoute(tab)) {
                            popUpTo(MediaNavigator.ROUTE_HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = MediaNavigator.ROUTE_HOME,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(MediaNavigator.ROUTE_HOME) {
                HomeScreen(
                    vm = vm,
                    onOpenMedia = { m ->
                        vm.stopSpritePreview()
                        // 阶段 8B §10：建立上下文失败（目标不在当前列表）时不静默打开第 1 项
                        if (vm.openMedia(m.id)) MediaNavigator.openMedia(navController, m)
                    },
                    onOpenFolder = { f ->
                        vm.stopSpritePreview()
                        MediaNavigator.openFolder(navController, f.id)
                    },
                    onOpenAlbum = { albumId ->
                        vm.stopSpritePreview()
                        MediaNavigator.navigateAlbum(navController, albumId)
                    },
                    onOpenDataSource = { dataSourceSheetOpen = true },
                )
            }
            composable(MediaNavigator.ROUTE_REVIEW) {
                V2ReviewScreen(
                    vm = reviewViewModel,
                    onOpenFullPlayer = { mediaId ->
                        // 完整播放器：队列 = 批阅**已加载窗口**（§38：绝不为了上下条拉完整队列），
                        // 返回仍回批阅原页
                        val media = vm.mediaById(mediaId)
                        if (media != null) {
                            val windowVideos = (reviewViewModel.state.value as? V2ReviewUiState.Ready)
                                ?.items
                                ?.mapNotNull { item -> vm.mediaById(item.mediaId) }
                                .orEmpty()
                            if (vm.openMediaInVideoQueue(windowVideos, mediaId)) {
                                MediaNavigator.openMedia(navController, media)
                            }
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(MediaNavigator.ROUTE_FAVORITES) {
                FavoritesPage(
                    vm = vm,
                    onOpenMedia = { m ->
                        if (vm.openMedia(m.id)) MediaNavigator.openMedia(navController, m)
                    },
                )
            }
            composable(MediaNavigator.ROUTE_ORGANIZE) {
                OrganizePage(
                    dataMode = dataMode,
                    onOpenDataSource = { dataSourceSheetOpen = true },
                    onOpenDelete = { navController.navigate(MediaNavigator.organizeDelete()) },
                    onOpenDuplicates = { navController.navigate(MediaNavigator.organizeDuplicates()) },
                    onOpenLibraries = { navController.navigate(MediaNavigator.organizeLibraries()) },
                )
            }
            composable(MediaNavigator.ROUTE_ORGANIZE_DELETE) {
                DeleteQueueScreen(
                    onBack = { navController.popBackStack() },
                    // §24：success/missing 的媒体已从服务端消失，V2 同步刷新并丢弃缓存
                    onFinalDeleteCommitted = { changedMediaIds ->
                        vm.refreshAfterFinalDelete(changedMediaIds)
                    },
                )
            }
            composable(MediaNavigator.ROUTE_ORGANIZE_DUPLICATES) {
                DuplicatesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenCompare = { groupId ->
                        navController.navigate(MediaNavigator.organizeDuplicateCompare(groupId))
                    },
                )
            }
            composable(MediaNavigator.ROUTE_ORGANIZE_DUPLICATE_COMPARE) { entry ->
                val groupId = entry.arguments?.getString("groupId") ?: ""
                DuplicateCompareScreen(
                    groupId = groupId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(MediaNavigator.ROUTE_ORGANIZE_LIBRARIES) {
                LibraryManagerScreen(
                    onBack = { navController.popBackStack() },
                    // §41：保存成功后失效辅助缓存并刷新首页数据
                    onSelectionApplied = { vm.refreshAfterLibraryChange() },
                )
            }
            composable(MediaNavigator.ROUTE_FOLDER) { entry ->
                val folderId = entry.arguments?.getString("folderId") ?: ""
                val folderName = vm.folders.value.find { it.id == folderId }?.name ?: ""
                FolderScreen(
                    vm = vm,
                    folderId = folderId,
                    folderName = folderName,
                    onBack = { navController.popBackStack() },
                    onOpenMedia = { m ->
                        if (vm.openMedia(m.id)) MediaNavigator.openMedia(navController, m)
                    },
                )
            }
            composable(MediaNavigator.ROUTE_ALBUM) { entry ->
                val albumId = entry.arguments?.getString("albumId") ?: ""
                AlbumScreen(
                    vm = vm,
                    albumId = albumId,
                    onBack = { navController.popBackStack() },
                    onOpenImage = { m, window ->
                        // Viewer 队列 = 相册**已加载窗口**（IMAGE ONLY，§12），左右滑只在窗口内翻页
                        if (vm.openMediaIn(window, m.id)) {
                            MediaNavigator.openMedia(navController, m)
                        }
                    },
                )
            }
            composable(MediaNavigator.ROUTE_PLAYER) { entry ->
                val mediaId = entry.arguments?.getString("mediaId") ?: ""
                val media = vm.mediaById(mediaId)
                if (media != null) {
                    // Stage 8A：正式播放路径进入 GSY Native Compose 播放器。
                    // 队列优先取 contextQueue（批阅/收藏/相册进入时按来源限定），
                    // 兜底取首页当前列表视频；队列只保存 id / 标题，
                    // URL 与 headers 由 ViewModel 按需异步解析（resolvePlayback），
                    // 绝不为整个队列一次性请求 PlaybackInfo。
                    val videos = remember(mediaId) {
                        val ids = vm.contextQueue?.mediaIds?.takeIf { it.isNotEmpty() }
                            ?: vm.currentList.value.filter { it.isVideo }.map { it.id }
                        ids.mapNotNull { id -> vm.mediaById(id)?.takeIf { it.isVideo } }
                    }
                    val playbackContext = remember(videos, mediaId) {
                        PlaybackContext(
                            queue = videos.map { PlaybackQueueItem(mediaId = it.id, title = it.name) },
                            currentIndex = videos.indexOfFirst { it.id == mediaId }.coerceAtLeast(0),
                            source = if (vm.isServerMode) "server" else "demo",
                        )
                    }
                    val playerViewModel: V2NativePlayerViewModel = hiltViewModel()
                    LaunchedEffect(playbackContext) { playerViewModel.open(playbackContext) }
                    val playerState by playerViewModel.state.collectAsState()
                    GsyNativePlayerScreen(
                        state = playerState,
                        onRequestIndex = { index -> playerViewModel.moveTo(index) },
                        onPlaybackFailed = { failingMediaId ->
                            playerViewModel.onPlaybackFailed(failingMediaId)
                        },
                        onRetry = { playerViewModel.retry() },
                        onReportProgress = { positionMs, isPaused ->
                            playerViewModel.reportProgress(positionMs, isPaused)
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable(MediaNavigator.ROUTE_VIEWER) { entry ->
                val mediaId = entry.arguments?.getString("mediaId") ?: ""
                V2ImageViewer(
                    vm = vm,
                    initialMediaId = mediaId,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }

    // 数据源设置 Sheet（V2 内部，不跳出 V2）：Demo / 我的服务器 + 首次连接服务器
    if (dataSourceSheetOpen) {
        V2DataSourceSheet(
            vm = vm,
            onDismiss = { dataSourceSheetOpen = false },
            // §52：设置页「本次更新」——关掉设置 Sheet 后打开更新日志
            onOpenWhatsNew = {
                dataSourceSheetOpen = false
                whatsNewViewModel.open()
            },
        )
    }

    // 新版本首次启动更新日志（§38/§42）：根页面完成 Composition 后才可能显示
    if (whatsNew.visible) {
        whatsNew.note?.let { note ->
            WhatsNewSheet(note = note, onDismiss = { whatsNewViewModel.dismiss() })
        }
    }
}

/** 由当前路由推导底部导航选中项。 */
private fun currentTab(route: String?): V2MainTab = when (route) {
    MediaNavigator.ROUTE_HOME -> V2MainTab.HOME
    MediaNavigator.ROUTE_REVIEW -> V2MainTab.REVIEW
    MediaNavigator.ROUTE_FAVORITES -> V2MainTab.FAVORITES
    MediaNavigator.ROUTE_ORGANIZE -> V2MainTab.ORGANIZE
    else -> V2MainTab.HOME
}

/** 收藏页：显示收藏媒体，点击经 MediaNavigator 进入 Player / Viewer，返回仍回收藏页。 */
@Composable
private fun FavoritesPage(
    vm: V2HomeViewModel,
    onOpenMedia: (V2Media) -> Unit,
    modifier: Modifier = Modifier,
) {
    val favorites by vm.favorites.collectAsState()
    val favoritesError by vm.favoritesError.collectAsState()
    // Stage 8A.1: 收藏列表首次进入本页才加载,不参与首页启动
    LaunchedEffect(Unit) { vm.ensureFavoritesLoaded() }
    Column(modifier = modifier.fillMaxSize().background(MediaBackground)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(vertical = V2Spacing.Md),
        ) {
            Text(
                text = "收藏",
                style = MaterialTheme.typography.titleLarge,
                color = MediaTextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (favorites.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (favoritesError != null) {
                    // 阶段 8A.1.1 §3: 首次加载失败不再永久锁死，提供真正可用的重试
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "收藏加载失败",
                            color = MediaTextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = favoritesError.orEmpty(),
                            color = MediaTextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = V2Spacing.Xs, start = V2Spacing.Lg, end = V2Spacing.Lg),
                        )
                        TextButton(onClick = { vm.retryFavorites() }) {
                            Text("重新加载", color = MediaTextPrimary)
                        }
                    }
                } else {
                    Text("暂无收藏内容", color = MediaTextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (favoritesError != null) {
                    // 已有成功数据时临时失败：保留旧数据，只给一条可重试的提示
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = V2Spacing.Lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "收藏刷新失败，显示的是上次结果",
                            color = MediaTextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.retryFavorites() }) {
                            Text("重新加载", color = MediaTextPrimary)
                        }
                    }
                }
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
                            coverUri = vm.coverUri(media),
                            spriteUri = vm.spriteUri(media),
                            manifest = vm.spriteManifest(media),
                            isSpritePreviewing = false,
                            onSpritePreviewRequest = {},
                            onClick = { onOpenMedia(media) },
                        )
                    }
                }
            }
        }
    }
}

/** 占位页（批阅 Stage 2，已由 V2ReviewScreen 取代，保留供 4 号 Tab 复用）。 */
@Composable
internal fun PlaceholderPage(
    icon: ImageVector,
    title: String,
    subtitle: String,
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
                Icon(
                    icon,
                    null,
                    tint = MediaTextSecondary,
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MediaTextPrimary,
                    modifier = Modifier.padding(top = V2Spacing.Md),
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MediaTextSecondary,
                    modifier = Modifier.padding(top = V2Spacing.Xs),
                )
            }
        }
    }
}