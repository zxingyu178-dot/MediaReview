@file:androidx.media3.common.util.UnstableApi

package com.mediareview.app.feature.review

import android.view.ViewGroup
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavController
import androidx.navigation.compose.composable
import coil.compose.AsyncImage
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.core.model.ReviewQueueItemDto
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.ui.graphics.vector.ImageVector
import com.mediareview.app.ui.theme.MediaAccent
import com.mediareview.app.ui.theme.MediaDanger
import com.mediareview.app.ui.theme.MediaDimensions
import com.mediareview.app.ui.theme.MediaSpacing
import com.mediareview.app.ui.theme.MediaControlScrim
import com.mediareview.app.ui.theme.MediaControlScrimSoft
import com.mediareview.app.ui.theme.MediaControlSurface
import com.mediareview.app.ui.theme.MediaImmersiveBackground
import com.mediareview.app.ui.theme.MediaOnImmersive

/** 批阅模式导航路由。 */
object ReviewDestinations {
    const val ROUTE = "review"
}

/** 注册批阅模式目的地。 */
fun NavGraphBuilder.reviewGraph(navController: NavController) {
    composable(ReviewDestinations.ROUTE) {
        ReviewScreen(onBack = { navController.popBackStack() })
    }
}

/**
 * 批阅模式:竖屏上下刷(视频/图片混合),横屏视频居中,
 * 页面停稳后才播放(P0),下一条 P1 预加载;右侧 ❤ / 🗑 / ⋯。
 */
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
    showHeader: Boolean = true,
) {
    val ui by viewModel.ui.collectAsState()
    val activeIsPreload by viewModel.core.activeIsPreload.collectAsState()

    LaunchedEffect(viewModel) { viewModel.loadIfNeeded() }

    // 页数 = 已加载数量(而非 total),快速滑动不会越界出现空白页
    val pagerState = rememberPagerState(initialPage = 0) { ui.items.size }

    // 批阅断点恢复:会话加载完成后跳到恢复位置(本地索引)
    LaunchedEffect(ui.loading, ui.startIndex) {
        if (!ui.loading && ui.startIndex in 1 until ui.items.size) {
            pagerState.scrollToPage(ui.startIndex)
        }
    }

    // 向前分页:loadPrev 前置了新分页后,让 pager 下移 added 页保持绝对位置不变
    var prevAdded by remember { mutableIntStateOf(0) }
    LaunchedEffect(prevAdded) {
        if (prevAdded > 0) {
            val target = pagerState.currentPage + prevAdded
            pagerState.scrollToPage(target.coerceIn(0, (ui.items.size - 1).coerceAtLeast(0)))
            prevAdded = 0
        }
    }

    // 页面停稳后才切换播放;滑动中不播放下一条
    val settled by remember { derivedStateOf { pagerState.settledPage } }
    LaunchedEffect(settled, ui.sessionId) {
        if (ui.items.isNotEmpty()) viewModel.onSettled(settled) { added -> prevAdded = added }
    }
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress) viewModel.onSwipeStarted()
    }

    // 待删除成功后自动滑向下一条(仅服务器 enqueue 成功后经事件触发,撤销提示仍保留)
    var advanceAfterDelete by remember { mutableStateOf(false) }
    LaunchedEffect(advanceAfterDelete, settled) {
        if (advanceAfterDelete) {
            val target = settled + 1
            if (target < ui.items.size) {
                pagerState.animateScrollToPage(target)
            }
            advanceAfterDelete = false
        }
    }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is ReviewEvent.DeleteSucceeded) advanceAfterDelete = true
        }
    }

    // 播放进度周期上报(每 10s,读取实时状态);离开当前视频/页面时补最后一条
    LaunchedEffect(settled, ui.items.size) {
        val media = ui.items.getOrNull(settled)?.media
        if (media != null && media.isVideo) {
            try {
                while (true) {
                    viewModel.reportPosition(media.media_id)
                    kotlinx.coroutines.delay(10_000)
                }
            } finally {
                viewModel.reportPosition(media.media_id)
            }
        }
    }

    val activePlayer = if (activeIsPreload) viewModel.core.preload else viewModel.core.player

    var moreMedia by remember { mutableStateOf<MediaSummary?>(null) }

    Box(Modifier.fillMaxSize().background(MediaImmersiveBackground)) {
        when {
            ui.loading -> CircularProgressIndicator(
                color = MediaOnImmersive,
                modifier = Modifier.align(Alignment.Center),
            )

            ui.error != null -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(ui.error ?: "加载失败", color = MediaOnImmersive)
                Spacer(Modifier.padding(MediaSpacing.Small))
                Button(onClick = { viewModel.load() }) { Text("重试") }
            }

            ui.items.isEmpty() -> Text(
                "暂无媒体可批阅",
                color = MediaOnImmersive,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                ReviewPage(
                    item = ui.items.getOrNull(page),
                    isCurrentVideo = page == settled &&
                        ui.items.getOrNull(page)?.media?.isVideo == true,
                    activePlayer = activePlayer,
                )
            }
        }

        if (showHeader) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(MediaControlScrim)
                    .padding(horizontal = MediaSpacing.XSmall, vertical = MediaSpacing.XSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("返回", color = MediaOnImmersive) }
                if (ui.resumed) {
                    TextButton(onClick = { viewModel.startNew() }) {
                        Text("新批阅", color = MediaAccent)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (ui.total > 0) {
                    val absolute = ui.baseIndex + settled
                    Text(
                        text = "批阅 ${(absolute + 1).coerceIn(1, ui.total)}/${ui.total}",
                        color = MediaOnImmersive,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(end = MediaSpacing.Regular),
                    )
                }
            }
        }

        // 右侧动作栏
        val cur = ui.items.getOrNull(settled)?.media
        if (cur != null) {
            RightActionBar(
                liked = cur.media_id in ui.likedSet,
                onLike = {
                    viewModel.onLike(settled)
                },
                onDelete = { viewModel.onDelete(settled) },
                onMore = { moreMedia = cur },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = MediaSpacing.Regular),
            )
        }

        // 删除撤销提示(基于真实 lastDeletedMediaId,不依赖当前索引)
        if (ui.lastDeletedMediaId != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = MediaSpacing.Large)
                    .background(MediaControlSurface)
                    .padding(horizontal = MediaSpacing.Medium, vertical = MediaSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已加入待删除", color = MediaOnImmersive)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { viewModel.undoDelete() }) { Text("撤销", color = MediaAccent) }
                TextButton(onClick = { viewModel.clearSnackbar() }) { Text("知道了", color = MediaOnImmersive) }
            }
        }
    }

    // ⋯ 更多:媒体信息
    moreMedia?.let { m ->
        AlertDialog(
            onDismissRequest = { moreMedia = null },
            title = { Text(m.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MediaSpacing.XSmall)) {
                    Text("类型:${if (m.isVideo) "视频" else "图片"}")
                    m.duration_ms?.let { Text("时长:${fmtMs(it)}") }
                    m.size_bytes?.let { Text("大小:${fmtBytes(it)}") }
                    Text("分辨率:${m.width ?: "-"}×${m.height ?: "-"}")
                }
            },
            confirmButton = { TextButton(onClick = { moreMedia = null }) { Text("关闭") } },
        )
    }
}

/** 单页:视频停稳后显示 PlayerView,否则显示封面;图片显示原图。 */
@Composable
private fun ReviewPage(
    item: ReviewQueueItemDto?,
    isCurrentVideo: Boolean,
    activePlayer: Player,
) {
    val media = item?.media
    Box(Modifier.fillMaxSize().background(MediaImmersiveBackground)) {
        when {
            media == null -> Unit
            media.isVideo && isCurrentVideo -> {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }
                    },
                    update = { v -> v.player = activePlayer },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            media.isVideo -> {
                AsyncImage(
                    model = media.cover_url,
                    contentDescription = media.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "播放视频",
                    tint = MediaOnImmersive,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(MediaDimensions.MinimumTouchTarget),
                )
            }

            else -> AsyncImage(
                model = media.original_url ?: media.cover_url,
                contentDescription = media.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 右侧动作栏:❤ / 🗑 / ⋯。 */
@Composable
private fun RightActionBar(
    liked: Boolean,
    onLike: () -> Unit,
    onDelete: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MediaDimensions.ReviewActionGap),
    ) {
        LikeHeart(liked = liked, onClick = onLike)
        ActionItem(
            icon = Icons.Outlined.DeleteOutline,
            contentDescription = "加入待删除",
            onClick = onDelete,
        )
        ActionItem(
            icon = Icons.Outlined.MoreVert,
            contentDescription = "更多操作",
            onClick = onMore,
        )
    }
}

/** 点赞项:轻量弹跳动画(点赞时缩放跳动,取消时回落)。 */
@Composable
private fun LikeHeart(liked: Boolean, onClick: () -> Unit) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(liked) {
        scale.snapTo(if (liked) 0.6f else 1f)
        scale.animateTo(1f, animationSpec = spring(dampingRatio = 0.35f, stiffness = 500f))
    }
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .scale(scale.value)
            .background(MediaControlScrimSoft, MaterialTheme.shapes.medium)
            .sizeIn(
                minWidth = MediaDimensions.MinimumTouchTarget,
                minHeight = MediaDimensions.MinimumTouchTarget,
            ),
    ) {
        Icon(
            imageVector = if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = if (liked) "取消收藏" else "收藏",
            tint = if (liked) MediaDanger else MediaOnImmersive,
        )
    }
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .background(MediaControlScrimSoft, MaterialTheme.shapes.medium)
            .sizeIn(
                minWidth = MediaDimensions.MinimumTouchTarget,
                minHeight = MediaDimensions.MinimumTouchTarget,
            ),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = MediaOnImmersive)
    }
}

private fun fmtMs(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun fmtBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> "%.1f GB".format(gb)
        mb >= 1 -> "%.1f MB".format(mb)
        else -> "%.0f KB".format(kb)
    }
}
