package com.mediareview.app.feature.v2.organize.duplicates

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.mediareview.app.feature.v2.organize.data.DuplicateMemberUi
import com.mediareview.app.feature.v2.organize.formatBytes
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.feature.v2.ui.V2Radius
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaBackground
import com.mediareview.app.ui.theme.MediaDanger
import com.mediareview.app.ui.theme.MediaSuccess
import com.mediareview.app.ui.theme.MediaSurfaceRaised
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary

/**
 * 重复对比页（Stage 8C §34/§35）。
 *
 * - 详情一次请求（`GET /duplicates/{group_id}`）拿全组成员与媒体摘要，**客户端零 N+1**；
 * - 双列卡片：封面 / 文件名 / 大小 / 时长 / 分辨率 / 保留状态；
 * - 点击"保留此文件"调用 `POST /duplicates/{group_id}/keep`，**Server 成功后**才更新 UI；
 * - keep 只是人工整理选择，不会自动删除其它文件（§36）。
 */
@Composable
fun DuplicateCompareScreen(
    groupId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: DuplicateCompareViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(groupId) { vm.load(groupId) }
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    Box(modifier = modifier.fillMaxSize().background(MediaBackground).testTag("duplicate_compare_screen")) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = V2Spacing.Sm, vertical = V2Spacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MediaTextPrimary,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "重复对比",
                        style = MaterialTheme.typography.titleLarge,
                        color = MediaTextPrimary,
                    )
                    val detail = ui.detail
                    if (detail != null) {
                        Text(
                            text = "${if (detail.type == "exact") "完全重复" else "疑似重复"} · ${detail.members.size} 份",
                            style = MaterialTheme.typography.bodySmall,
                            color = MediaTextSecondary,
                        )
                    }
                }
            }

            when {
                ui.loading -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MediaTextSecondary)
                }
                ui.error != null || ui.detail == null -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("分组加载失败", color = MediaTextSecondary)
                        Text(
                            text = ui.error.orEmpty(),
                            color = MediaTextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(
                                top = V2Spacing.Xs,
                                start = V2Spacing.Lg,
                                end = V2Spacing.Lg,
                            ),
                        )
                        TextButton(onClick = { vm.load(groupId) }) {
                            Text("重新加载", color = MediaTextPrimary)
                        }
                    }
                }
                else -> {
                    val detail = ui.detail ?: return@Column
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(
                            start = V2Spacing.Lg,
                            end = V2Spacing.Lg,
                            top = V2Spacing.Sm,
                            bottom = V2Spacing.Xl,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(V2Spacing.Md),
                        verticalArrangement = Arrangement.spacedBy(V2Spacing.Md),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                if (detail.detail.isNotBlank()) {
                                    Text(
                                        text = detail.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MediaTextSecondary,
                                    )
                                }
                                Text(
                                    text = "点击下方卡片选择要保留的文件；保留选择只记录在服务器，不会自动删除其它文件。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MediaTextSecondary,
                                    modifier = Modifier.padding(top = V2Spacing.Xs),
                                )
                            }
                        }
                        items(detail.members, key = { it.media.id }) { member ->
                            MemberCard(
                                member = member,
                                keepBusy = ui.keepBusy,
                                onToggleKeep = {
                                    vm.setKeep(groupId, member.media.id, !member.keep)
                                },
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun MemberCard(
    member: DuplicateMemberUi,
    keepBusy: Boolean,
    onToggleKeep: () -> Unit,
) {
    val media = member.media
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(V2Radius.Card))
            .background(MediaSurfaceRaised),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(V2Colors.Skeleton),
        ) {
            // §20：扫描后已失效的成员不展示封面（避免假封面/假可用状态）
            if (member.available && member.coverUri.isNotBlank()) {
                AsyncImage(
                    model = member.coverUri,
                    contentDescription = media.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (!member.available) {
                Text(
                    text = "文件已不可用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MediaDanger,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (member.keep) {
                Text(
                    text = "已保留",
                    style = MaterialTheme.typography.labelSmall,
                    color = MediaBackground,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(V2Spacing.Sm)
                        .clip(RoundedCornerShape(V2Radius.Sm))
                        .background(MediaSuccess)
                        .padding(horizontal = V2Spacing.Sm, vertical = 2.dp),
                )
            }
        }
        Column(modifier = Modifier.padding(V2Spacing.Md)) {
            Text(
                text = media.name,
                style = MaterialTheme.typography.titleSmall,
                color = MediaTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = memberMetaText(member),
                style = MaterialTheme.typography.labelSmall,
                color = MediaTextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            TextButton(onClick = onToggleKeep, enabled = !keepBusy) {
                Text(
                    text = if (member.keep) "取消保留" else "保留此文件",
                    color = if (member.keep) MediaSuccess else MediaTextPrimary,
                )
            }
        }
    }
}

/** 成员摘要：大小 · 时长 · 分辨率（只展示用户能理解的三项，不堆技术参数）。 */
private fun memberMetaText(member: DuplicateMemberUi): String {
    val media = member.media
    val parts = mutableListOf(formatBytes(media.sizeBytes))
    if (media.durationMs > 0) parts += formatDuration(media.durationMs)
    if (media.naturalWidth > 0 && media.naturalHeight > 0) {
        parts += "${media.naturalWidth}×${media.naturalHeight}"
    }
    return parts.joinToString(" · ")
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) {
        val hours = minutes / 60
        "%d:%02d:%02d".format(hours, minutes % 60, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}