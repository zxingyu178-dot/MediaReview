package com.mediareview.app.feature.v2.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mediareview.app.ui.theme.MediaControlScrim
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary

/**
 * 图片查看器顶部控制层：返回 + 名称/编号 + 当前序号/总数。
 */
@Composable
fun ImageViewerTopBar(
    name: String,
    code: String,
    currentIndex: Int,
    total: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(MediaControlScrim)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("viewer_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = MediaTextPrimary)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = MediaTextPrimary,
                maxLines = 1,
            )
            if (code.isNotBlank()) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall,
                    color = MediaTextPrimary.copy(alpha = 0.7f),
                )
            }
        }
        Text(
            text = "${currentIndex + 1} / $total",
            style = MaterialTheme.typography.labelLarge,
            color = MediaTextSecondary,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .testTag("viewer_count"),
        )
    }
}
