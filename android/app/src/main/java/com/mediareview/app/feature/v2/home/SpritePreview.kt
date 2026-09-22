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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import coil.request.ImageRequest
import com.mediareview.app.feature.v2.model.V2SpriteManifest
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.ui.theme.MediaControlScrimMedium
import com.mediareview.app.ui.theme.MediaTextPrimary
import androidx.core.graphics.drawable.toBitmap

/** 由 0..1 进度映射雪碧图帧索引（用于真实 crop 渲染，可单测）。 */
fun spriteFrameIndexForProgress(progress: Float, frameCount: Int, columns: Int, rows: Int): Int {
    val safe = progress.coerceIn(0f, 1f)
    val fc = frameCount.coerceAtLeast(1)
    return (safe * fc).toInt().coerceIn(0, fc - 1)
}

/**
 * 雪碧图预览内容：从 sprite sheet 中"真实裁剪"当前帧 cell 再绘制到整张封面区域
 * （Canvas + drawImage srcOffset/srcSize → dstSize，满幅绘制），
 * 不再使用 scale/translation 模拟裁剪。底部带当前时间 / 总时长 / 进度指示。
 */
@Composable
fun SpritePreviewContent(
    spriteUri: String,
    manifest: V2SpriteManifest,
    progress: Float,          // 0..1（手指绝对位置 / 卡宽）
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val safeProgress = progress.coerceIn(0f, 1f)
    val frameCount = manifest.frame_count.coerceAtLeast(1)
    val frameIndex = spriteFrameIndexForProgress(safeProgress, frameCount, manifest.columns, manifest.rows)
    val cols = manifest.columns.coerceAtLeast(1)
    val cellW = manifest.cell_width.coerceAtLeast(1)
    val cellH = manifest.cell_height.coerceAtLeast(1)
    val col = frameIndex % cols
    val row = frameIndex / cols

    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, spriteUri) {
        value = try {
            val request = ImageRequest.Builder(context)
                .data(spriteUri)
                .allowHardware(false) // 需要软件位图以便按 cell 裁剪
                .build()
            context.imageLoader.execute(request).drawable?.toBitmap()?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val bmp = bitmap
            if (bmp != null) {
                // 从图片中裁剪当前 cell（srcOffset + srcSize）并绘制到整张封面区域
                drawImage(
                    image = bmp,
                    srcOffset = IntOffset(col * cellW, row * cellH),
                    srcSize = IntSize(cellW, cellH),
                    dstOffset = IntOffset(0, 0),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                )
            } else {
                // 位图尚未解码：绘制浅色占位，避免整块变黑
                drawRect(V2Colors.Skeleton)
            }
        }

        // 底部时间 + 进度（00:14 / 00:40 ━━━━━━━●━━━━━）
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MediaControlScrimMedium)
                .padding(horizontal = 12.dp, vertical = 6.dp),
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(safeProgress.coerceAtLeast(0.01f))
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.75f)),
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .size(6.dp)
                        .background(V2Colors.Accent, CircleShape),
                )
                Box(
                    modifier = Modifier
                        .weight((1f - safeProgress).coerceIn(0.01f, 1f))
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.3f)),
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