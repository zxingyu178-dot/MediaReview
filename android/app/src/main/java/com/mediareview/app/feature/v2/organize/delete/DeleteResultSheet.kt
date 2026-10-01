package com.mediareview.app.feature.v2.organize.delete

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaDanger
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary

/**
 * 最终删除结果面板（Stage 8C §22）：逐类显示 success / missing / failed。
 *
 * - **失败项绝不隐藏**：列出失败名称（最多 10 个）并注明"仍保留在待删除队列"；
 * - 用户关闭后停留在待删除中心，列表已刷新（失败项仍在）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeleteResultSheet(
    result: DeleteCommitResult,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = V2Spacing.Xl)
                .padding(bottom = V2Spacing.Xl),
            verticalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
        ) {
            Text(
                text = "删除完成",
                style = MaterialTheme.typography.titleMedium,
                color = MediaTextPrimary,
            )
            Text(
                text = "成功删除：${result.successCount}",
                style = MaterialTheme.typography.bodyMedium,
                color = MediaTextPrimary,
            )
            Text(
                text = "文件已不存在：${result.missingCount}",
                style = MaterialTheme.typography.bodyMedium,
                color = MediaTextSecondary,
            )
            if (result.failedCount > 0) {
                Text(
                    text = "删除失败：${result.failedCount}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MediaDanger,
                )
                result.failedNames.take(MAX_FAILED_NAMES).forEach { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MediaTextSecondary,
                    )
                }
                Text(
                    text = "失败项仍保留在待删除队列",
                    style = MaterialTheme.typography.bodySmall,
                    color = MediaTextSecondary,
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(androidx.compose.ui.Alignment.End)) {
                Text("知道了", color = MediaTextPrimary)
            }
        }
    }
}

private const val MAX_FAILED_NAMES = 10