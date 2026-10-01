package com.mediareview.app.feature.v2.releasenotes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary

/**
 * 「本次更新」底部 Sheet（Stage 8C.1 §42）。
 *
 * 内容保持简洁：产品名 + 版本号 + 本次更新条目 + [知道了]；
 * 是 Overlay，不阻塞 Home 加载，也不在 Splash 阶段弹出（§43/§44）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(
    note: ReleaseNote,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("whats_new_sheet"),
        ) {
            Text(
                text = "MediaReview",
                style = MaterialTheme.typography.titleMedium,
                color = MediaTextPrimary,
            )
            Text(
                text = note.versionName,
                style = MaterialTheme.typography.bodySmall,
                color = MediaTextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = "本次更新",
                style = MaterialTheme.typography.titleSmall,
                color = MediaTextPrimary,
                modifier = Modifier.padding(top = V2Spacing.Lg),
            )
            // 条目圆点用真实 UI 元素而不是 ✓/• 等符号字符（项目源码合同禁止符号占位）
            note.highlights.forEach { highlight ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = V2Spacing.Sm),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(V2Colors.Accent),
                    )
                    Text(
                        text = highlight,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MediaTextPrimary,
                        modifier = Modifier.padding(start = V2Spacing.Sm),
                    )
                }
            }
            Spacer(modifier = Modifier.height(V2Spacing.Lg))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("知道了", color = V2Colors.Accent)
                }
            }
            Text(
                text = note.title,
                style = MaterialTheme.typography.labelSmall,
                color = MediaTextSecondary,
                modifier = Modifier.align(Alignment.Start),
            )
        }
    }
}