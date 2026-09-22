package com.mediareview.app.feature.v2.home

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import coil.request.ImageRequest
import com.mediareview.app.feature.v2.model.V2SpriteManifest
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.ui.theme.MediaTextPrimary
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay

/** 帧切换间隔（约 200ms，视觉测试后可调：不能闪图也不能像 PPT）。 */
private const val FRAME_INTERVAL_MS = 200L

/** 安全停止时长：用户不操作约 6.5s 后自动恢复 Poster（避免永久动画）。 */
private const val AUTOPLAY_SAFE_STOP_MS = 6_500L

/** 由 0..1 进度映射雪碧图帧索引（保留兼容旧逻辑/单测）。 */
fun spriteFrameIndexForProgress(progress: Float, frameCount: Int, columns: Int, rows: Int): Int {
    val safe = progress.coerceIn(0f, 1f)
    val fc = frameCount.coerceAtLeast(1)
    return (safe * fc).toInt().coerceIn(0, fc - 1)
}

/** 自动播放帧索引：tick 从 0 递增，逐帧推进并回绕（0→1→…→last→0…），可 JVM 单测。 */
fun spriteFrameIndexAtTick(tick: Int, frameCount: Int): Int {
    val fc = frameCount.coerceAtLeast(1)
    return (tick % fc).coerceIn(0, fc - 1)
}

/**
 * 雪碧图自动预览内容：
 * - 从 sprite sheet 中真实裁剪当前帧 cell 绘制到整张封面区域（Canvas + drawImage，沿用 Stage4 crop）；
 * - Timer 自动逐帧推进（0→1→2…→frameCount-1→0…）；
 * - 底部仅保留小型时间胶囊 + 2dp 极细进度条（核心是看内容，不是操作播放器）；
 * - 播放约 [AUTOPLAY_SAFE_STOP_MS] 后调用 [onAutoStop]（安全停止，防止无限播放）。
 */
@Composable
fun SpritePreviewContent(
    spriteUri: String,
    manifest: V2SpriteManifest,
    durationMs: Long,
    onAutoStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val frameCount = manifest.frame_count.coerceAtLeast(1)
    val cols = manifest.columns.coerceAtLeast(1)
    val cellW = manifest.cell_width.coerceAtLeast(1)
    val cellH = manifest.cell_height.coerceAtLeast(1)

    var tick by remember { mutableIntStateOf(0) }
    val frameIndex = spriteFrameIndexAtTick(tick, frameCount)
    // 当前帧对应时间 = 进度 (frameIndex+1)/frameCount 对应的时长
    val currentTimeMs = if (frameCount <= 1) durationMs else durationMs * (frameIndex + 1) / frameCount

    // 自动播放：逐帧推进；到达末尾回绕；达到安全时长后请求停止
    LaunchedEffect(spriteUri) {
        var start = SystemClock.uptimeMillis()
        while (true) {
            delay(FRAME_INTERVAL_MS)
            tick++
            if (SystemClock.uptimeMillis() - start >= AUTOPLAY_SAFE_STOP_MS) {
                onAutoStop()
                break
            }
        }
    }

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

        // 底部：时间胶囊 + 2dp 极细进度条（不占两行空间）
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = formatDuration(currentTimeMs),
                    color = MediaTextPrimary,
                    fontSize = 10.sp,
                )
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp)
                    .height(2.dp),
            ) {
                drawRoundRect(Color.White.copy(alpha = 0.3f))
                drawRect(
                    color = V2Colors.Accent,
                    size = androidx.compose.ui.geometry.Size(
                        width = size.width * ((frameIndex + 1) / frameCount.toFloat()),
                        height = size.height,
                    ),
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