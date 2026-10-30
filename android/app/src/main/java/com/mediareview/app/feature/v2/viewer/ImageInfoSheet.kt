package com.mediareview.app.feature.v2.viewer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 图片信息面板（ModalBottomSheet）。
 * Demo 有什么显示什么，不塞大量技术参数。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageInfoSheet(
    name: String,
    code: String,
    folderName: String,
    width: Int,
    height: Int,
    sizeBytes: Long,
    dateMillis: Long,
    isFavorite: Boolean,
    sourceType: String,
    relativePath: String,
    isPendingDelete: Boolean,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = MediaTextPrimary)
            InfoRow("编号", code)
            InfoRow("库 / 文件夹", folderName.ifBlank { "未提供" })
            InfoRow("来源", sourceLabel(sourceType))
            InfoRow("相对路径", relativePath)
            InfoRow("尺寸", if (width > 0) "${width} × ${height}" else "未知")
            InfoRow("大小", formatSize(sizeBytes))
            InfoRow("时间", formatDate(dateMillis))
            InfoRow("收藏", if (isFavorite) "是" else "否")
            InfoRow("待删除", if (isPendingDelete) "已加入" else "否")
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

private fun formatDate(millis: Long): String = if (millis <= 0L) "未知" else
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "未知"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.CHINA, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.CHINA, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

private fun sourceLabel(source: String): String = when (source.lowercase(Locale.ROOT)) {
    "native", "local" -> "本地媒体库"
    "jellyfin" -> "Jellyfin"
    "demo" -> "演示数据"
    else -> source.ifBlank { "未知" }
}
