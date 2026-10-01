package com.mediareview.app.feature.v2.organize

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.ui.V2Radius
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaBackground
import com.mediareview.app.ui.theme.MediaSurfaceRaised
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary

/**
 * 整理首页（Stage 8C）：数据源 + 四张真实 Server 卡片。
 *
 * - 所有数字来自真实接口（`/delete-queue/summary`、`/duplicates/summary`、
 *   `/review/sessions/latest`、`/libraries`），页面上**不允许出现任何 Mock 数字**；
 * - 每张卡独立 Loading / Ready / Error，可单卡重试；
 * - 接口失败绝不显示成 0（§45）；Demo 不支持的卡片显示「仅服务器模式可用」。
 */
@Composable
fun OrganizePage(
    dataMode: V2DataMode,
    onOpenDataSource: () -> Unit,
    onOpenDelete: () -> Unit,
    onOpenDuplicates: () -> Unit,
    onOpenLibraries: () -> Unit,
    modifier: Modifier = Modifier,
    vm: OrganizeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    // 每次进入整理页 + **数据源变化**都刷新四张卡（Stage 8C.1 §24/§25）：
    // Demo ↔ Server 切换后绝不保留旧数据源的卡片内容
    LaunchedEffect(dataMode) { vm.load() }

    Column(modifier = modifier.fillMaxSize().background(MediaBackground).testTag("organize_page")) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(vertical = V2Spacing.Md),
        ) {
            Text(
                text = "整理",
                style = MaterialTheme.typography.titleLarge,
                color = MediaTextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(
                start = V2Spacing.Lg,
                end = V2Spacing.Lg,
                top = V2Spacing.Sm,
                bottom = V2Spacing.Xl,
            ),
            verticalArrangement = Arrangement.spacedBy(V2Spacing.Md),
        ) {
            item {
                OrganizeCardRow(
                    icon = Icons.Default.Folder,
                    title = "数据源",
                    subtitle = if (dataMode == V2DataMode.SERVER) "我的服务器" else "演示数据（离线）",
                    tint = MediaTextPrimary,
                    onClick = onOpenDataSource,
                )
            }
            item {
                DataCard(
                    icon = Icons.Default.DeleteSweep,
                    title = "待删除",
                    card = state.deleteCard,
                    text = { vm.summaryText(it.count, it.totalBytes) },
                    onOpen = onOpenDelete,
                    onRetry = vm::retryDeleteCard,
                )
            }
            item {
                DataCard(
                    icon = Icons.Default.CopyAll,
                    title = "重复媒体",
                    card = state.duplicateCard,
                    text = { vm.duplicateText(it) },
                    onOpen = onOpenDuplicates,
                    onRetry = vm::retryDuplicateCard,
                )
            }
            item {
                DataCard(
                    icon = Icons.Default.CheckCircle,
                    title = "批阅进度",
                    card = state.reviewCard,
                    text = { vm.reviewText(it) },
                    // 批阅进度卡无独立详情页（批阅本体在批阅 Tab），只展示状态
                    onOpen = null,
                    onRetry = vm::retryReviewCard,
                )
            }
            item {
                DataCard(
                    icon = Icons.Default.Folder,
                    title = "媒体库管理",
                    card = state.libraryCard,
                    text = { vm.libraryText(it) },
                    onOpen = onOpenLibraries,
                    onRetry = vm::retryLibraryCard,
                )
            }
        }
    }
}

/** 一张真实数据卡：Loading / Ready / Error / Unavailable 四态，卡内独立重试。 */
@Composable
private fun <T> DataCard(
    icon: ImageVector,
    title: String,
    card: OrganizeCard<T>,
    text: (T) -> String,
    onOpen: (() -> Unit)?,
    onRetry: () -> Unit,
) {
    val subtitle: String
    val action: (() -> Unit)?
    when (card) {
        OrganizeCard.Loading -> {
            subtitle = "加载中…"
            action = null
        }
        is OrganizeCard.Ready -> {
            subtitle = text(card.data)
            action = onOpen
        }
        is OrganizeCard.Error -> {
            subtitle = "加载失败 · 点击重试"
            action = onRetry
        }
        is OrganizeCard.Unavailable -> {
            subtitle = card.message
            action = null
        }
    }
    OrganizeCardRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        tint = if (action == null) MediaTextSecondary else MediaTextPrimary,
        onClick = action,
    )
}

@Composable
private fun OrganizeCardRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(V2Radius.Card))
            .background(MediaSurfaceRaised)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(V2Spacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            tint = tint,
            modifier = Modifier.size(28.dp),
        )
        Column(modifier = Modifier.padding(start = V2Spacing.Lg)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MediaTextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MediaTextSecondary)
        }
    }
}