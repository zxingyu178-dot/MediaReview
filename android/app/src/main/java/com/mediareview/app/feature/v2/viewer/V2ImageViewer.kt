package com.mediareview.app.feature.v2.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.mediareview.app.feature.v2.home.V2HomeViewModel
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.ui.theme.MediaBackground
import com.mediareview.app.ui.theme.MediaControlScrim
import com.mediareview.app.ui.theme.MediaImmersiveBackground
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * V2 图片查看器：
 * - 上下文队列（所在文件夹/排序/当前 index），像翻相册
 * - 单击显隐 UI、双击 2x/恢复、双指缩放、放大后拖动、左右滑翻页
 * - 预加载前 1 / 后 2
 * - 底部 ♡ 收藏 / ⓘ 信息 / 🗑 待删除（仅改本地 Demo State，不真删资源）
 */
@Composable
fun V2ImageViewer(
    vm: V2HomeViewModel,
    initialMediaId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val queue = vm.contextQueue
    val ids = queue?.mediaIds ?: listOf(initialMediaId)
    val initialIndex = (queue?.currentIndex ?: 0).coerceIn(0, (ids.size - 1).coerceAtLeast(0))

    val pagerState = rememberPagerState(initialPage = initialIndex) { ids.size }
    val currentMedia: V2Media? = ids.getOrNull(pagerState.currentPage)?.let { vm.mediaById(it) }

    var uiVisible by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf(false) }
    // 当前页缩放状态（用于禁用 pager 手势）
    var currentScale by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(pagerState.currentPage) {
        vm.updateQueueIndex(pagerState.currentPage)
        currentScale = 1f
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(MediaImmersiveBackground)) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = currentScale <= 1.01f,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 2, // 预加载前 1 / 后 2
        ) { page ->
            val media = ids.getOrNull(page)?.let { vm.mediaById(it) }
            if (media != null) {
                ZoomableImage(
                    uri = vm.thumbUri(media),
                    isCurrent = page == pagerState.currentPage,
                    onTap = { uiVisible = !uiVisible },
                    onScaleChange = {
                        if (page == pagerState.currentPage) currentScale = it
                    },
                )
            }
        }

        // 顶部：返回 + 编号/文件名
        if (uiVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .background(MediaControlScrim)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = MediaTextPrimary)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentMedia?.let { "第 ${it.code} 张" } ?: "图片",
                        style = MaterialTheme.typography.titleMedium,
                        color = MediaTextPrimary,
                    )
                    Text(
                        text = currentMedia?.name ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MediaTextPrimary.copy(alpha = 0.7f),
                    )
                }
                Text(
                    text = "${pagerState.currentPage + 1} / ${ids.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MediaTextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // 底部：收藏 / 信息 / 待删除
        if (uiVisible && currentMedia != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MediaControlScrim)
                    .navigationBarsPadding()
                    .padding(horizontal = 32.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BottomAction(
                    icon = if (currentMedia!!.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    label = if (currentMedia!!.isFavorite) "已收藏" else "收藏",
                    tint = if (currentMedia!!.isFavorite) V2Colors.Favorite else MediaTextPrimary,
                    modifier = Modifier.weight(1f),
                ) {
                    vm.setFavorite(currentMedia!!.id, !currentMedia!!.isFavorite)
                }
                BottomAction(
                    icon = Icons.Default.Info,
                    label = "信息",
                    tint = MediaTextPrimary,
                    modifier = Modifier.weight(1f),
                ) {
                    showInfo = true
                }
                BottomAction(
                    icon = Icons.Default.DeleteOutline,
                    label = if (pendingDelete) "已标记" else "待删除",
                    tint = if (pendingDelete) V2Colors.Accent else MediaTextPrimary,
                    modifier = Modifier.weight(1f),
                ) {
                    pendingDelete = !pendingDelete
                    if (pendingDelete) vm.markReviewed(currentMedia!!.id)
                }
            }
        }
    }

    if (showInfo && currentMedia != null) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(currentMedia!!.name) },
            text = {
                val m = currentMedia!!
                Text(
                    "编号：${m.code}\n" +
                        "文件夹：${m.folderName}\n" +
                        "类型：${if (m.isVideo) "视频" else "图片"}\n" +
                        "尺寸：${m.naturalWidth} × ${m.naturalHeight}\n" +
                        "大小：${m.sizeBytes / 1024} KB\n" +
                        "收藏：${if (m.isFavorite) "是" else "否"}\n" +
                        "已批阅：${if (m.isReviewed) "是" else "否"}",
                    color = MediaTextPrimary,
                )
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) { Text("关闭") }
            },
        )
    }
}

/** 可缩放图片：单击、双击 2x、双指缩放、放大后单指拖动。 */
@Composable
private fun ZoomableImage(
    uri: String,
    isCurrent: Boolean,
    onTap: () -> Unit,
    onScaleChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val scaleRef = rememberUpdatedState(scale)
    val offsetRef = rememberUpdatedState(offset)

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(uri) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { pos ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            // 以点击点为中心
                            offset = Offset.Zero
                        }
                        onScaleChange(scale)
                    },
                )
            }
            .pointerInput(uri) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var pinchStart = Offset.Zero
                    var lastDist = 0f
                    var lastCentroid = Offset.Zero
                    var multiTouch = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            multiTouch = true
                            val c0 = pressed[0].position
                            val c1 = pressed[1].position
                            val dist = (c0 - c1).getDistance()
                            val centroid = (c0 + c1) / 2f
                            if (lastDist == 0f) {
                                lastDist = dist
                                lastCentroid = centroid
                            } else {
                                val newScale = (scale * dist / lastDist).coerceIn(1f, 5f)
                                // 围绕 centroid 缩放（简化：直接改 scale）
                                scale = newScale
                                offset = offset + (centroid - lastCentroid)
                                lastDist = dist
                                lastCentroid = centroid
                                onScaleChange(scale)
                                pressed.forEach { it.consume() }
                            }
                        } else if (pressed.size == 1) {
                            val change = pressed[0]
                            if (multiTouch || scale > 1f) {
                                if (change.positionChanged()) {
                                    offset += change.positionChange()
                                    change.consume()
                                }
                            }
                            // scale==1 且单指：不 consume，交由 Pager 翻页
                        }
                        if (event.changes.none { it.pressed }) {
                            lastDist = 0f
                            break
                        }
                    }
                }
            },
    ) {
        SubcomposeAsyncImage(
            model = uri,
            contentDescription = "图片",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scaleRef.value
                    scaleY = scaleRef.value
                    translationX = offsetRef.value.x
                    translationY = offsetRef.value.y
                },
        )
    }
}

@Composable
private fun BottomAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(26.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}
