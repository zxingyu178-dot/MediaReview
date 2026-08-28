package com.mediareview.app.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.feature.connect.data.AuthenticationState
import com.mediareview.app.feature.connect.data.ConnectionState
import com.mediareview.app.feature.connect.data.OnlineState
import com.mediareview.app.feature.connect.data.SyncState
import com.mediareview.app.feature.deletequeue.DeleteQueueDestinations
import com.mediareview.app.feature.deletequeue.DeleteQueueViewModel
import com.mediareview.app.feature.duplicates.DuplicatesDestinations
import com.mediareview.app.feature.duplicates.DuplicatesViewModel
import com.mediareview.app.feature.favorites.FavoritesScreen
import com.mediareview.app.feature.home.HomeViewModel
import com.mediareview.app.feature.library.LibraryDestinations
import com.mediareview.app.feature.library.LibraryViewModel
import com.mediareview.app.feature.mediawall.MediaWallScreen
import com.mediareview.app.feature.player.PlayerDestinations
import com.mediareview.app.feature.review.ReviewScreen
import com.mediareview.app.feature.settings.SettingsDestinations
import com.mediareview.app.feature.viewer.ImageViewerDestinations
import com.mediareview.app.ui.components.MediaCard
import com.mediareview.app.ui.components.ConnectionStatusBanner
import com.mediareview.app.ui.components.MediaTopBar
import com.mediareview.app.ui.components.SyncStatusBanner
import com.mediareview.app.ui.theme.MediaDimensions
import com.mediareview.app.ui.theme.MediaSpacing

enum class MainRoot(val label: String) {
    Media("媒体"),
    Review("批阅"),
    Favorites("收藏"),
    Organizer("整理"),
}

val DEFAULT_MAIN_ROOT = MainRoot.Media

data class RootSelection(val root: MainRoot, val changed: Boolean)

fun rootSelection(current: MainRoot, requested: MainRoot): RootSelection =
    RootSelection(root = requested, changed = current != requested)

object MainShellDestinations {
    const val ROUTE = "main_shell"
}

fun shouldShowMainChrome(route: String?): Boolean = route == MainShellDestinations.ROUTE

fun NavGraphBuilder.mainShellGraph(navController: NavController) {
    composable(MainShellDestinations.ROUTE) {
        MainShellScreen(
            onOpenSettings = { navController.navigate(SettingsDestinations.ROUTE) },
            onOpenLibrary = { navController.navigate(LibraryDestinations.LIBRARY) },
            onOpenDeleteQueue = { navController.navigate(DeleteQueueDestinations.DELETE_QUEUE) },
            onOpenDuplicates = { navController.navigate(DuplicatesDestinations.DUPLICATES) },
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

@Composable
fun MainShellScreen(
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenDeleteQueue: () -> Unit,
    onOpenDuplicates: () -> Unit,
    onOpenMedia: (MediaSummary) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    var selectedName by rememberSaveable { mutableStateOf(DEFAULT_MAIN_ROOT.name) }
    val selected = MainRoot.valueOf(selectedName)
    val banner = shellBanner(ui.connection)

    MainShellScaffold(
        selectedRoot = selected,
        banner = banner,
        onSelect = { requested ->
            val selection = rootSelection(selected, requested)
            if (selection.changed) selectedName = selection.root.name
        },
        onOpenSettings = onOpenSettings,
    ) {
        MainRootStateHost(selected) { root ->
            when (root) {
                MainRoot.Media -> MediaWallScreen(
                    onBack = {},
                    onItemClick = onOpenMedia,
                    showHeader = false,
                )
                MainRoot.Review -> ReviewScreen(onBack = {}, showHeader = false)
                MainRoot.Favorites -> FavoritesScreen(
                    onBack = {},
                    onOpenMedia = onOpenMedia,
                    showHeader = false,
                )
                MainRoot.Organizer -> OrganizerOverview(
                    onOpenLibrary = onOpenLibrary,
                    onOpenDeleteQueue = onOpenDeleteQueue,
                    onOpenDuplicates = onOpenDuplicates,
                )
            }
        }
    }
}

@Composable
fun MainShellScaffold(
    selectedRoot: MainRoot,
    banner: ShellBanner?,
    onSelect: (MainRoot) -> Unit,
    onOpenSettings: () -> Unit,
    content: @Composable () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    Scaffold(
        topBar = { MediaTopBar(selectedRoot.label, onOpenSettings) },
        bottomBar = {
            MainBottomBar(selectedRoot = selectedRoot, onSelect = onSelect)
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            banner?.let {
                val modifier = Modifier.padding(
                    horizontal = MediaSpacing.Medium,
                    vertical = MediaSpacing.Small,
                )
                if (it.kind == ShellBannerKind.Syncing) {
                    SyncStatusBanner(text = it.text, modifier = modifier)
                } else {
                    ConnectionStatusBanner(text = it.text, modifier = modifier)
                }
            }
            content()
        }
    }
}

@Composable
fun MainRootStateHost(
    selectedRoot: MainRoot,
    content: @Composable (MainRoot) -> Unit,
) {
    val stateHolder = rememberSaveableStateHolder()
    stateHolder.SaveableStateProvider(selectedRoot.name) { content(selectedRoot) }
}

@Composable
fun MainBottomBar(
    selectedRoot: MainRoot,
    onSelect: (MainRoot) -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        MainRoot.entries.forEach { root ->
            val selected = root == selectedRoot
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(root) },
                icon = {
                    Icon(
                        imageVector = root.icon(selected),
                        contentDescription = "${root.label}导航",
                    )
                },
                label = { Text(root.label, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun OrganizerOverview(
    onOpenLibrary: () -> Unit,
    onOpenDeleteQueue: () -> Unit,
    onOpenDuplicates: () -> Unit,
    libraryViewModel: LibraryViewModel = hiltViewModel(),
    deleteQueueViewModel: DeleteQueueViewModel = hiltViewModel(),
    duplicatesViewModel: DuplicatesViewModel = hiltViewModel(),
) {
    val libraries by libraryViewModel.ui.collectAsState()
    val deleteQueue by deleteQueueViewModel.ui.collectAsState()
    val duplicates by duplicatesViewModel.ui.collectAsState()
    androidx.compose.runtime.LaunchedEffect(deleteQueueViewModel, duplicatesViewModel) {
        deleteQueueViewModel.loadIfNeeded()
        duplicatesViewModel.loadIfNeeded()
    }
    val statuses = organizerStatuses(
        selectedLibraries = libraries.selectedIds.size,
        pendingDeletes = deleteQueue.pendingCount,
        exactGroups = duplicates.exact.size,
        similarGroups = duplicates.similar.size,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(MediaSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(MediaSpacing.Medium),
    ) {
        OrganizerCard(
            title = "媒体库选择",
            status = if (libraries.loading) "正在读取媒体库" else statuses.library,
            icon = Icons.Outlined.Folder,
            onClick = onOpenLibrary,
        )
        OrganizerCard(
            title = "待删除",
            status = if (deleteQueue.loading) "正在读取待删除队列" else statuses.deleteQueue,
            icon = Icons.Outlined.DeleteSweep,
            onClick = onOpenDeleteQueue,
        )
        OrganizerCard(
            title = "重复文件",
            status = if (duplicates.loading) "正在读取重复分组" else statuses.duplicates,
            icon = Icons.Outlined.ContentCopy,
            onClick = onOpenDuplicates,
        )
    }
}

data class OrganizerStatuses(
    val library: String,
    val deleteQueue: String,
    val duplicates: String,
)

fun organizerStatuses(
    selectedLibraries: Int,
    pendingDeletes: Int,
    exactGroups: Int,
    similarGroups: Int,
): OrganizerStatuses = OrganizerStatuses(
    library = "已选择 $selectedLibraries 个媒体库",
    deleteQueue = "$pendingDeletes 项待确认删除",
    duplicates = "$exactGroups 组完全重复，$similarGroups 组疑似重复",
)

@Composable
private fun OrganizerCard(
    title: String,
    status: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    MediaCard(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = MediaDimensions.MinimumTouchTarget)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MediaSpacing.Medium),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    status,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

enum class ShellBannerKind { Syncing, Attention }

data class ShellBanner(val text: String, val kind: ShellBannerKind)

fun shellBanner(connection: ConnectionState): ShellBanner? = when {
    connection.authentication == AuthenticationState.Rejected -> ShellBanner(
        "配对凭据已失效，请在设置中重新连接",
        ShellBannerKind.Attention,
    )
    connection.mediaReview == OnlineState.Offline -> ShellBanner(
        "家庭媒体服务暂时离线",
        ShellBannerKind.Attention,
    )
    connection.jellyfin == OnlineState.Offline -> ShellBanner(
        "Jellyfin 暂时离线，已缓存内容仍可查看",
        ShellBannerKind.Attention,
    )
    connection.sync == SyncState.Failed -> ShellBanner(
        "上次媒体同步失败，可在设置中重试",
        ShellBannerKind.Attention,
    )
    connection.sync == SyncState.Syncing -> ShellBanner("媒体正在同步", ShellBannerKind.Syncing)
    else -> null
}

private fun MainRoot.icon(selected: Boolean): ImageVector = when (this) {
    MainRoot.Media -> if (selected) Icons.Filled.VideoLibrary else Icons.Outlined.VideoLibrary
    MainRoot.Review -> if (selected) Icons.Filled.RateReview else Icons.Outlined.RateReview
    MainRoot.Favorites -> if (selected) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder
    MainRoot.Organizer -> if (selected) Icons.Filled.Build else Icons.Outlined.Build
}
