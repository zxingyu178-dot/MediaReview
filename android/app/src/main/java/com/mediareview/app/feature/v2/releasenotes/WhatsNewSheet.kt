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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
 * 「本次更新」底部 Sheet（Stage 8C.1 §42；Stage 8C.2 §25/§26）。
 *
 * - 始终**只有一个 Sheet**：跨版本升级时把 `(lastSeen, current]` 所有版本的
 *   更新日志聚合在同一份内容里，按版本升序分区展示（避免连续弹窗）；
 * - 单版本（首次安装 / 设置页主动打开）只显示一份列表；
 * - 内容保持简洁：产品名 + 当前版本号 + 各版本条目 + [知道了]；
 * - 是 Overlay，不阻塞 Home 加载，也不在 Splash 阶段弹出（§43/§44）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(
    state: WhatsNewUiState,
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
                .verticalScroll(rememberScrollState())
                .testTag("whats_new_sheet"),
        ) {
            Text(
                text = "MediaReview",
                style = MaterialTheme.typography.titleMedium,
                color = MediaTextPrimary,
            )
            Text(
                text = state.currentVersionName,
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

            state.notes.forEach { note ->
                if (state.isMultiVersion) {
                    // 跨版本升级：标明每条更新来自哪个版本（§25）
                    Text(
                        text = "来自 ${note.versionName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = V2Colors.Accent,
                        modifier = Modifier.padding(top = V2Spacing.Md),
                    )
                }
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
        }
    }
}