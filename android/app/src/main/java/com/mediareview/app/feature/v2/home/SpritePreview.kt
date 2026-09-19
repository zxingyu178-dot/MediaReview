package com.mediareview.app.feature.v2.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.mediareview.app.feature.v2.model.V2SpriteManifest
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.ui.theme.MediaControlScrimMedium
import com.mediareview.app.ui.theme.MediaTextPrimary

/**
 * 雪碧图预览内容：把 sprite sheet 放大到显示区域并平移到当前帧对应格子，
 * 底部带时间进度条与当前时间文字。
 */
@Composable
fun SpritePreviewContent(
    spriteUri: String,
    manifest: V2SpriteManifest,
    progress: Float,          // 0..1
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val safeProgress = progress.coerceIn(0f, 1f)
    val frameCount = manifest.frame_count.coerceAtLeast(1)
    val frameIndex = (safeProgress * frameCount).toInt().coerceIn(0, frameCount - 1)
    val cols = manifest.columns.coerceAtLeast(1)
    val rows = manifest.rows.coerceAtLeast(1)
    val col = frameIndex % cols
    val row = frameIndex / cols
    val cellW = manifest.cell_width.coerceAtLeast(1)
    val cellH = manifest.cell_height.coerceAtLeast(1)

    Box(modifier = modifier) {
        SubcomposeAsyncImage(
            model = spriteUri,
            contentDescription = "雪碧图预览",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val sx = size.width / (cols * cellW).toFloat()
                    val sy = size.height / (rows * cellH).toFloat()
                    scaleX = sx
                    scaleY = sy
                    translationX = -col * cellW * sx
                    translationY = -row * cellH * sy
                },
        )

        // 底部时间 + 进度条
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MediaControlScrimMedium)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatDuration((durationMs * safeProgress).toLong()),
                    color = MediaTextPrimary,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatDuration(durationMs),
                    color = MediaTextPrimary.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                )
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(4.dp),
            ) {
                val barH = 4.dp.toPx()
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.35f),
                    cornerRadius = CornerRadius(barH / 2),
                )
                val fillW = size.width * safeProgress
                drawRoundRect(
                    color = V2Colors.Accent,
                    size = Size(fillW, barH),
                    cornerRadius = CornerRadius(barH / 2),
                )
            }
        }
    }
}

/** 时长格式化：mm:ss。 */
fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    return "%02d:%02d".format(totalSec / 60, totalSec % 60)
}
