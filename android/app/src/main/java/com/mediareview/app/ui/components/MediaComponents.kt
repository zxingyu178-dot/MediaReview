package com.mediareview.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mediareview.app.ui.theme.MediaDimensions
import com.mediareview.app.ui.theme.MediaElevation
import com.mediareview.app.ui.theme.MediaSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaTopBar(
    title: String,
    onOpenSettings: () -> Unit,
) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        actions = {
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.sizeIn(
                    minWidth = MediaDimensions.MinimumTouchTarget,
                    minHeight = MediaDimensions.MinimumTouchTarget,
                ),
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = "打开设置")
            }
        },
    )
}

@Composable
fun MediaCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = MediaElevation.Low),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box(Modifier.padding(MediaSpacing.Medium)) { content() }
    }
}

@Composable
fun LoadingSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MediaSpacing.Small),
    ) {
        repeat(3) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(MediaSpacing.Small),
                color = MaterialTheme.colorScheme.surfaceVariant,
                trackColor = MaterialTheme.colorScheme.surface,
            )
        }
    }
}

@Composable
fun MediaEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    StateLayout(
        modifier = modifier,
        icon = { Icon(Icons.Outlined.Inbox, contentDescription = null) },
        title = title,
        message = message,
    )
}

@Composable
fun MediaOfflineState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StateLayout(
        modifier = modifier,
        icon = { Icon(Icons.Outlined.CloudOff, contentDescription = null) },
        title = "暂时无法连接",
        message = message,
        action = {
            Button(onClick = onRetry) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Text("重试", modifier = Modifier.padding(start = MediaSpacing.Small))
            }
        },
    )
}

@Composable
fun SyncStatusBanner(
    text: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = MediaSpacing.Medium,
                vertical = MediaSpacing.Small,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LinearProgressIndicator(modifier = Modifier.weight(1f))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = MediaSpacing.Medium),
            )
        }
    }
}

@Composable
fun ConnectionStatusBanner(
    text: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = MediaSpacing.Medium,
                vertical = MediaSpacing.Small,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = MediaSpacing.Small),
            )
        }
    }
}

@Composable
private fun StateLayout(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(MediaSpacing.Large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MediaSpacing.Small),
    ) {
        icon()
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        action?.invoke()
    }
}
