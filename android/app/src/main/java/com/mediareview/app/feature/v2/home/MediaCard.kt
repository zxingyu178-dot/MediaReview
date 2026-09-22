package com.mediareview.app.feature.v2.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2SpriteManifest
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.feature.v2.ui.V2Radius
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaSurfaceRaised
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary

private const val SPRITE_LONG_PRESS_MS = 280L

/**
 * V2 双列媒体卡片：
 * - 圆角 + 轻阴影 + 略微立体；封面 16:9，标题单行、次信息弱化（首屏约 4~5 排可见）
 * - 封面加载中显示轻量 Skeleton，失败显示统一占位（绝不纯黑）
 * - 长按约 280ms 进入雪碧图预览：轻震动、卡片 1.00→1.03、阴影增强；
 *   左右拖动按"手指绝对位置 / 卡宽"映射进度（非累计），松手恢复 Poster
 * - 与 LazyGrid 滚动竞争：长按判定前发生显著位移 → 不消耗事件，交给滚动
 */
@Composable
fun MediaCard(
    media: V2Media,
    coverUri: String,
    spriteUri: String?,
    manifest: V2SpriteManifest?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var previewing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var cardW by remember { mutableIntStateOf(0) }
    var cardH by remember { mutableIntStateOf(0) }
    // 长按拖拽会话标记：防止松手瞬间被 clickable 当作普通点击
    var dragSessionActive by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val hasSprite = spriteUri != null && manifest != null
    val activeByGesture = rememberUpdatedState(dragSessionActive)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                if (previewing) {
                    scaleX = 1.03f
                    scaleY = 1.03f
                    shadowElevation = 12.dp.toPx()
                }
            }
            .onSizeChanged { cardW = it.width; cardH = it.height }
            .pointerInput(media.id, hasSprite, cardW, cardH) {
                if (!hasSprite) return@pointerInput
                awaitEachGesture {
                    // 长按检测：awaitLongPressOrCancellation 由官方实现（含 slop 取消），按住不动直到
                    // 系统 longPressTimeout 或位移/抬起；返回非 null 即长按命中。
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val lp = awaitLongPressOrCancellation(down.id)
                    if (lp == null) return@awaitEachGesture

                    // 长按命中：进入预览（绝对位置起步）
                    previewing = true
                    dragSessionActive = true
                    var totalDx = 0f
                    var totalDy = 0f
                    progress = (down.position.x / cardW.coerceAtLeast(1)).coerceIn(0f, 1f)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        totalDx += change.positionChange().x
                        totalDy += change.positionChange().y
                        val outside = cardW > 0 && cardH > 0 &&
                            (change.position.x !in 0f..cardW.toFloat() || change.position.y !in 0f..cardH.toFloat())
                        val verticalDominant = totalDy > 24.dp.toPx() && totalDy > totalDx
                        if (outside || verticalDominant) {
                            // 纵向位移主导：结束预览、停止消费，把滚动交还给 LazyGrid
                            change.consume()
                            break
                        }
                        progress = (change.position.x / cardW.coerceAtLeast(1)).coerceIn(0f, 1f)
                        change.consume()
                    }
                    previewing = false
                    progress = 0f
                }
            }
            .clip(RoundedCornerShape(V2Radius.Card))
            .background(MediaSurfaceRaised)
            .clickable {
                // 长按结束后的松手不触发普通点击（避免误入详情页）
                if (activeByGesture.value) {
                    dragSessionActive = false
                } else {
                    dragSessionActive = false
                    onClick()
                }
            },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 封面
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = V2Radius.Card, topEnd = V2Radius.Card))
                    .background(V2Colors.CardScrim),
            ) {
                SubcomposeAsyncImage(
                    model = coverUri,
                    contentDescription = media.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = { CoverSkeleton() },
                    error = { CoverPlaceholder() },
                )

                // 时长胶囊（视频）
                if (media.isVideo) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(V2Spacing.Sm)
                            .clip(RoundedCornerShape(6.dp))
                            .background(V2Colors.TimeCapsule)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = formatDuration(media.durationMs),
                            color = MediaTextPrimary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                // 收藏角标
                if (media.isFavorite) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "已收藏",
                        tint = V2Colors.Favorite,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(V2Spacing.Sm)
                            .size(18.dp),
                    )
                }

                // 雪碧图预览（长按触发）
                if (previewing && hasSprite) {
                    SpritePreviewContent(
                        spriteUri = spriteUri!!,
                        manifest = manifest!!,
                        progress = progress,
                        durationMs = media.durationMs,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // 标题 + 次信息（紧凑垂直内边距，提升首屏排数）
            Column(modifier = Modifier.padding(horizontal = V2Spacing.Sm, vertical = V2Spacing.Sm)) {
                Text(
                    text = media.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MediaTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${media.code} · ${media.folderName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MediaTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 封面加载中 Skeleton：轻量呼吸色块 + 居中图标（非纯黑空块）。 */
@Composable
private fun CoverSkeleton() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(V2Colors.Skeleton),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.BrokenImage,
            contentDescription = null,
            tint = MediaTextSecondary.copy(alpha = 0.35f),
            modifier = Modifier.size(28.dp),
        )
    }
}

/** 封面加载失败统一占位（网络异常 / Server 缺失也不出现黑墙）。 */
@Composable
private fun CoverPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(V2Colors.CardScrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.BrokenImage,
                contentDescription = null,
                tint = MediaTextSecondary.copy(alpha = 0.55f),
                modifier = Modifier.size(26.dp),
            )
            Text(
                text = "封面不可用",
                style = MaterialTheme.typography.labelSmall,
                color = MediaTextSecondary.copy(alpha = 0.6f),
            )
        }
    }
}