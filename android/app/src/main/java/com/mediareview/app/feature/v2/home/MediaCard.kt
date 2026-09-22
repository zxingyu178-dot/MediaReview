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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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

/**
 * V2 双列媒体卡片。
 *
 * 雪碧图交互（Stage 5 自动播放模式）：
 * - 长按视频卡片 → 轻震动 → 启动该卡片的自动雪碧图预览；
 * - 用户可立即松手，预览继续自动逐帧播放（不是拖动 Scrubber）；
 * - 同一时间只有 1 个 Preview（由 ViewModel 的 activePreviewMediaId 保证）；
 * - 松手不停止；滚动/切 Tab/打开媒体/超时由外部停止。
 */
@Composable
fun MediaCard(
    media: V2Media,
    coverUri: String,
    spriteUri: String?,
    manifest: V2SpriteManifest?,
    isSpritePreviewing: Boolean,
    onSpritePreviewRequest: () -> Unit,
    onSpritePreviewStop: () -> Unit = {},
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val hasSprite = spriteUri != null && manifest != null

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                if (isSpritePreviewing) {
                    scaleX = 1.03f
                    scaleY = 1.03f
                    shadowElevation = 12.dp.toPx()
                }
            }
            .pointerInput(media.id, hasSprite) {
                if (!hasSprite) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 长按 = 启动开关（含 slop 取消：纵向位移交还滚动）
                    val lp = awaitLongPressOrCancellation(down.id)
                    if (lp == null) return@awaitEachGesture
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSpritePreviewRequest()
                    // 消费本次手势直到抬起，避免松手被当作普通点击打开媒体
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                    }
                }
            }
            .clip(RoundedCornerShape(V2Radius.Card))
            .background(MediaSurfaceRaised)
            .clickable(onClick = onClick),
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

                // 雪碧图自动预览（长按启动，外部驱动帧索引）
                if (isSpritePreviewing && hasSprite) {
                    SpritePreviewContent(
                        spriteUri = spriteUri!!,
                        manifest = manifest!!,
                        durationMs = media.durationMs,
                        onAutoStop = onSpritePreviewStop, // 安全停止（约 6.5s 无操作）
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