package com.mediareview.app.feature.v2.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.player.native.ui.GsyNativeVideoInfoSheet
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.ui.theme.MediaDanger
import com.mediareview.app.ui.theme.MediaImmersiveBackground
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary
import com.shuyu.gsyvideoplayer.compose.native_.GSYPlayerSurface
import com.shuyu.gsyvideoplayer.compose.native_.rememberGSYPlayerController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 页面停稳后延迟标记批阅（settle + 400~600ms）。 */
private const val MARK_REVIEWED_DELAY_MS = 480L

/**
 * 批阅模式 V1（抖音式）：竖屏 VerticalPager，视频优先。
 *
 * - 单 GSY Player 共享：仅当前页组合 GSYPlayerSurface，其余页展示 Poster；
 * - 页面停稳（settledPage 变化）后换源自动播放；无横向手势，
 *   手势 = 竖向滑动 + 单击显隐控制层；
 * - 停稳 + ~480ms 自动 markReviewed（队列不重排，避免跳动）；
 * - 右侧动作：❤ 收藏 / 🗑 待删除（可撤销）/ ⋯ 更多（打开完整播放器 / 视频信息）；
 * - 全部滑完（末页已批阅）→ 完成浮层 + "重新批阅"回到第一页。
 */
@Composable
fun V2ReviewScreen(
    vm: V2ReviewViewModel,
    onOpenFullPlayer: (V2Media, List<V2Media>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val queue by vm.queue.collectAsState()
    val ready by vm.ready.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.loadIfNeeded() }

    if (!ready) {
        Box(modifier = modifier.fillMaxSize().background(MediaImmersiveBackground))
        return
    }

    val pendingDeleteIds by vm.pendingDeleteIds.collectAsState()
    val reviewedIds by vm.reviewedIds.collectAsState()
    val favoriteIds by vm.favoriteIds.collectAsState()

    var controlsVisible by remember { mutableStateOf(true) }
    var infoMedia by remember { mutableStateOf<V2Media?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 队列空 → 完成页（重新批阅）
    if (queue.isEmpty()) {
        ReviewCompletePage(
            emptyQueue = true,
            total = 0,
            onRestart = { vm.restartReview() },
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    val pagerState = rememberPagerState(initialPage = 0) { queue.size }

    // 单共享 controller：初始源 = 第一条；停稳后换源（不重造内核）。
    // note: rememberGSYPlayerController 本身是 @Composable（内部带 remember），
    // 不能放进 remember{} 块；后续通过 setUp 换源。
    val controller = rememberGSYPlayerController(
        url = demoVideoUrl(context, queue.first()),
        cacheWithPlay = false,
        title = queue.first().name,
        autoPlay = false,
        autoPauseResume = true,
    )
    var lastPlayedId by remember { mutableStateOf("") }

    // 停稳 → 换源播放 + 延迟自动批阅
    LaunchedEffect(pagerState.settledPage) {
        val media = queue.getOrNull(pagerState.settledPage) ?: return@LaunchedEffect
        if (media.id != lastPlayedId) {
            lastPlayedId = media.id
            controller.setUp(demoVideoUrl(context, media), false, media.name, true)
        }
        delay(MARK_REVIEWED_DELAY_MS)
        vm.markReviewed(media.id)
    }

    // 完成判定：滑动到末页且末页已批阅
    val lastMediaId = queue.lastOrNull()?.id
    val allDone = lastMediaId != null && lastMediaId in reviewedIds && pagerState.settledPage >= queue.lastIndex

    // 全屏容器：Pager + 各 overlay 都放这里，保证 align 在 BoxScope 内有效
    Box(modifier = modifier.fillMaxSize().background(MediaImmersiveBackground)) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val media = queue[page]
            val isCurrent = page == pagerState.settledPage
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                if (isCurrent) {
                    GSYPlayerSurface(
                        controller = controller,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // 周边页：Poster + 半透明遮罩（保证上下滑时的视觉承接）
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(demoPosterUrl(context, media))
                            .crossfade(false)
                            .size(720, 1280)
                            .build(),
                        contentDescription = media.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                    )
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MediaTextPrimary.copy(alpha = 0.7f),
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
            }
        }

        // 单击画面任意处显隐控制层（抖音手势：仅竖向滑动 + 单击）。
        // 始终挂载（而非仅 controlsVisible 时），保证点第二下能唤回控制层；
        // 顶部/底部/右侧按钮在上层，点击按钮不会被此层吞掉。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                },
        )

        // 控制层（顶栏 + 底部信息 + 右侧动作）
        if (controlsVisible && !allDone) {
            // 顶栏：返回 / 计数 / 已批阅
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        tint = MediaTextPrimary,
                    )
                }
                Text(
                    text = "${pagerState.settledPage + 1} / ${queue.size}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MediaTextPrimary,
                    modifier = Modifier.align(Alignment.Center),
                )
                Text(
                    text = "已批阅 ${reviewedIds.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MediaTextSecondary,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp),
                )
            }

            // 底部信息
            val media = queue[pagerState.settledPage]
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            ) {
                Text(
                    text = media.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MediaTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${media.code} · ${media.folderName} · ${media.durationSeconds}s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MediaTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // 右侧动作栏：收藏 / 待删除(撤销) / 更多
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ReviewActionButton(
                    icon = if (media.id in favoriteIds) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    tint = if (media.id in favoriteIds) V2Colors.Favorite else MediaTextPrimary,
                    label = if (media.id in favoriteIds) "已收藏" else "收藏",
                    onClick = { vm.toggleFavorite(media.id) },
                )
                ReviewActionButton(
                    icon = if (media.id in pendingDeleteIds) Icons.Default.Undo else Icons.Default.DeleteOutline,
                    tint = if (media.id in pendingDeleteIds) MediaDanger else MediaTextPrimary,
                    label = if (media.id in pendingDeleteIds) "撤销" else "待删除",
                    onClick = {
                        if (media.id in pendingDeleteIds) {
                            vm.undoPendingDelete(media.id)
                        } else {
                            vm.addPendingDelete(media.id)
                            scope.launch {
                                val result = snackbar.showSnackbar(
                                    message = "已加入待删除",
                                    actionLabel = "撤销",
                                    duration = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    vm.undoPendingDelete(media.id)
                                }
                            }
                        }
                    },
                )
                ReviewActionButton(
                    icon = Icons.Default.MoreVert,
                    tint = MediaTextPrimary,
                    label = "更多",
                    onClick = { infoMedia = media },
                )
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )

        // 更多 → 视频信息 Sheet
        infoMedia?.let { media ->
            GsyNativeVideoInfoSheet(
                title = media.name,
                durationMs = media.durationMs,
                positionMs = 0L,
                speedLabel = "1x",
                fileName = media.assetPath.substringAfterLast('/'),
                mediaId = media.id,
                resolution = if (media.naturalWidth > 0) "${media.naturalWidth} × ${media.naturalHeight}" else "未知",
                onDismiss = { infoMedia = null },
            )
        }

        // 完成浮层
        if (allDone) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(enabled = false) {},
            ) {
                ReviewCompleteOverlay(
                    total = queue.size,
                    onRestart = {
                        vm.restartReview()
                        scope.launch { pagerState.scrollToPage(0) }
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/** 抖音式右侧动作按钮：圆形半透明底 + 图标 + 小字。 */
@Composable
private fun ReviewActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    label: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(44.dp)
                .background(V2Colors.CardScrim, CircleShape),
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MediaTextPrimary,
        )
    }
}

/** 完成浮层：批阅完成 + 重新批阅。 */
@Composable
private fun ReviewCompleteOverlay(
    total: Int,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.78f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = null,
                tint = V2Colors.Accent,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = "批阅完成",
                style = MaterialTheme.typography.titleLarge,
                color = MediaTextPrimary,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = "共 $total 条视频已全部浏览",
                style = MaterialTheme.typography.bodyMedium,
                color = MediaTextSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = V2Colors.Accent,
                onClick = onRestart,
                modifier = Modifier.padding(top = 18.dp),
            ) {
                Text(
                    text = "重新批阅",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** 无未审视频时的完成页。 */
@Composable
private fun ReviewCompletePage(
    emptyQueue: Boolean,
    total: Int,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(MediaImmersiveBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = null,
                tint = V2Colors.Favorite,
                modifier = Modifier.size(52.dp),
            )
            Text(
                text = if (emptyQueue) "没有待批阅的视频" else "批阅完成",
                style = MaterialTheme.typography.titleLarge,
                color = MediaTextPrimary,
                modifier = Modifier.padding(top = 14.dp),
            )
            Text(
                text = if (emptyQueue) "全部视频都已批阅完成" else "共 $total 条视频已全部浏览",
                style = MaterialTheme.typography.bodyMedium,
                color = MediaTextSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = V2Colors.Accent,
                onClick = onRestart,
                modifier = Modifier.padding(top = 20.dp),
            ) {
                Text(
                    text = "重新批阅",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** Demo 视频播放 URI（与播放器一致的 android.resource 方案）。 */
internal fun demoVideoUrl(context: android.content.Context, media: V2Media): String {
    val base = media.assetPath.substringAfterLast('/').removeSuffix(".mp4")
    return "android.resource://${context.packageName}/raw/demo_$base"
}

/** Demo 视频 Poster URI（与封面系统一致）。 */
internal fun demoPosterUrl(context: android.content.Context, media: V2Media): String {
    val num = media.assetPath.substringAfterLast('/').takeWhile { it.isDigit() }.ifEmpty { "01" }
    return "android.resource://${context.packageName}/raw/demo_video_${num}_poster"
}