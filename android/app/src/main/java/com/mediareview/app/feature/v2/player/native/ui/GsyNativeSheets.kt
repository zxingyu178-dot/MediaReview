package com.mediareview.app.feature.v2.player.native.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.home.formatDuration
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary
import kotlin.math.abs

/** 倍速显示：整数值显示整数（2x），否则一位小数（1.5x）。 */
fun formatSpeedLabel(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}" else speed.toString()

/** 更多菜单。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GsyNativeMoreSheet(
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSpeed: () -> Unit,
    onScale: () -> Unit,
    onRotation: () -> Unit,
    onSubtitle: () -> Unit,
    onAudio: () -> Unit,
    onInfo: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetTitle("更多")
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            MoreRow("上一条", enabled = hasPrevious, onClick = onPrevious)
            MoreRow("下一条", enabled = hasNext, onClick = onNext)
            MoreRow("播放速度", onClick = onSpeed)
            MoreRow("画面比例", onClick = onScale)
            MoreRow("旋转画面", onClick = onRotation)
            MoreRow("字幕", onClick = onSubtitle)
            MoreRow("音轨", onClick = onAudio)
            MoreRow("视频信息", onClick = onInfo)
        }
    }
}

@Composable
private fun MoreRow(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MediaTextPrimary else MediaTextSecondary,
        )
    }
}

/** 播放速度面板：0.5x ~ 2.0x，当前倍速高亮。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GsyNativeSpeedSheet(
    current: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetTitle("播放速度")
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            speeds.forEach { s ->
                SelectableRow(label = "${formatSpeedLabel(s)}x", selected = abs(s - current) < 0.001f, onClick = { onSelect(s) })
            }
        }
    }
}

/** 画面比例面板：映射 GSY SCREEN_TYPE。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GsyNativeScaleSheet(
    current: com.mediareview.app.feature.v2.player.native.state.VideoScaleState.ScaleMode,
    onSelect: (com.mediareview.app.feature.v2.player.native.state.VideoScaleState.ScaleMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetTitle("画面比例")
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            com.mediareview.app.feature.v2.player.native.state.VideoScaleState.ScaleMode.entries.forEach { mode ->
                SelectableRow(label = mode.label, selected = mode == current, onClick = { onSelect(mode) })
            }
        }
    }
}

/** 视频信息面板：Demo 有什么显示什么，以后接 Jellyfin 再扩展。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GsyNativeVideoInfoSheet(
    title: String,
    durationMs: Long,
    positionMs: Long,
    speedLabel: String,
    fileName: String,
    mediaId: String,
    resolution: String,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetTitle(title)
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            InfoRow("时长", formatDuration(durationMs))
            InfoRow("分辨率", resolution)
            InfoRow("当前播放位置", formatDuration(positionMs))
            InfoRow("当前倍速", "${speedLabel}x")
            InfoRow("文件名", fileName)
            InfoRow("媒体 ID", mediaId)
        }
    }
}

/** 字幕 / 音轨 占位面板：UI READY / BACKEND DEFERRED（本阶段不伪造不存在的轨道）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GsyNativeStatusSheet(
    title: String,
    statusLine: String,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetTitle(title)
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MediaTextSecondary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SheetTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MediaTextPrimary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun SelectableRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) V2Colors.Accent else MediaTextPrimary,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(Icons.Default.Check, "当前选项", tint = V2Colors.Accent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MediaTextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MediaTextPrimary)
    }
}
