package com.mediareview.app.feature.v2.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
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

/**
 * V2 双列媒体卡片：
 * - 圆角 + 轻阴影 + 略微立体
 * - 时长叠封面右下角
 * - 标题一行、次信息弱化
 * - 长按约 250ms 进入雪碧图预览（轻微触觉反馈、卡片 1.00→1.03、阴影增强、左右拖动选时间、松手恢复）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCard(
    media: V2Media,
    thumbUri: String,
    spriteUri: String?,
    manifest: V2SpriteManifest?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var previewing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var cardW by remember { mutableIntStateOf(0) }
    var cardH by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current
    val hasSprite = spriteUri != null && manifest != null

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
            .pointerInput(media.id, hasSprite) {
                if (!hasSprite) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        previewing = true
                        progress = 0f
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { change, dragAmount ->
                        // 手指移出卡片边界时结束预览（不保持）
                        if (cardW > 0 && cardH > 0) {
                            val pos = change.position
                            if (pos.x !in 0f..cardW.toFloat() || pos.y !in 0f..cardH.toFloat()) {
                                previewing = false
                                progress = 0f
                                return@detectDragGesturesAfterLongPress
                            }
                        }
                        if (cardW > 0) {
                            progress = (progress + dragAmount.x / cardW).coerceIn(0f, 1f)
                        }
                        change.consume()
                    },
                    onDragEnd = {
                        previewing = false
                        progress = 0f
                    },
                    onDragCancel = {
                        previewing = false
                        progress = 0f
                    },
                )
            }
            .clip(RoundedCornerShape(V2Radius.Card))
            .background(MediaSurfaceRaised)
            .clickable { onClick() },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 封面
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(topStart = V2Radius.Card, topEnd = V2Radius.Card))
                    .background(V2Colors.CardScrim),
            ) {
                SubcomposeAsyncImage(
                    model = thumbUri,
                    contentDescription = media.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )

                // 时长胶囊（视频）
                if (media.isVideo) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(V2Spacing.Sm)
                            .clip(RoundedCornerShape(8.dp))
                            .background(V2Colors.TimeCapsule)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
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
                            .size(20.dp),
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

            // 标题 + 次信息
            Column(modifier = Modifier.padding(horizontal = V2Spacing.Md, vertical = V2Spacing.Sm)) {
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
