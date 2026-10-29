package com.mediareview.app.feature.v2.nsfw

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.ImageLoader
import coil.compose.AsyncImage
import dagger.hilt.android.EntryPointAccessors
import com.mediareview.app.feature.v2.nsfw.data.NsfwFeedItem
import com.mediareview.app.feature.v2.nsfw.data.NsfwImageDetails
import com.mediareview.app.feature.v2.nsfw.data.NsfwPromptLink
import com.mediareview.app.feature.v2.nsfw.di.NsfwEntryPoint
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaBackground
import com.mediareview.app.ui.theme.MediaSurface
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary

/**
 * 「生图审核」一级页面（独立于视频批阅）：
 * - 未配对：独立 NSFW Bridge 配对（默认 8767），能力门禁不通过时明确列出缺失项；
 * - 已配对：单图浏览 + 队尾追加 + 原位更新 + 详情 + 提示词版本评分；
 * - 不提供永久删除；401 只清 NSFW 凭据。
 */
@Composable
fun NsfwReviewScreen(
    vm: NsfwReviewViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val imageLoader = remember(context) {
        EntryPointAccessors.fromApplication(context, NsfwEntryPoint::class.java)
            .nsfwImageLoader().loader
    }
    var baseUrlInput by rememberSaveable { mutableStateOf("") }
    var codeInput by rememberSaveable { mutableStateOf("") }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.setScreenVisible(true)
                Lifecycle.Event.ON_STOP -> vm.setScreenVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            vm.setScreenVisible(true)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.setScreenVisible(false)
        }
    }

    LaunchedEffect(state.baseUrl) {
        if (baseUrlInput.isBlank() && state.baseUrl.isNotBlank()) baseUrlInput = state.baseUrl
    }

    Column(modifier = modifier.fillMaxSize().background(MediaBackground)) {
        when (state.phase) {
            NsfwPhase.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = V2Colors.Accent)
            }
            NsfwPhase.UNPAIRED -> PairingPane(
                state = state,
                baseUrl = baseUrlInput,
                code = codeInput,
                onBaseUrlChange = { baseUrlInput = it },
                onCodeChange = { codeInput = it },
                onPair = { vm.pair(baseUrlInput, codeInput) },
                onRetry = { vm.pair(baseUrlInput, codeInput) },
            )
            NsfwPhase.READY -> ReadyPane(
                state = state,
                imageLoader = imageLoader,
                onRefresh = vm::refresh,
                onLoadMore = vm::loadMore,
                onSelect = vm::select,
                onPrevious = vm::previous,
                onNext = vm::next,
                onDecision = vm::decision,
                onToggleFavorite = vm::toggleFavorite,
                onOpenDetails = { detailsOpen = true },
                onDisconnect = vm::clearPairing,
            )
        }
    }

    val detail = state.detail
    if (detailsOpen && detail != null) {
        DetailsDialog(
            detail = detail,
            item = state.currentItem,
            onDismiss = { detailsOpen = false },
            onScore = {
                detailsOpen = false
                vm.openPromptPanel()
            },
        )
    }

    state.panel?.let { panel ->
        PromptScoreDialog(
            panel = panel,
            vm = vm,
            baseUrl = state.baseUrl,
            imageLoader = imageLoader,
        )
    }
}

@Composable
private fun PairingPane(
    state: NsfwUiState,
    baseUrl: String,
    code: String,
    onBaseUrlChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onPair: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(V2Spacing.Lg),
    ) {
        Text("生图审核", style = MaterialTheme.typography.titleLarge, color = MediaTextPrimary)
        Text(
            "连接 NSFW Studio 手机审核桥（默认端口 8767）。它与 MediaReview 服务器完全独立，凭据互不影响。",
            style = MaterialTheme.typography.bodySmall,
            color = MediaTextSecondary,
            modifier = Modifier.padding(top = V2Spacing.Sm, bottom = V2Spacing.Lg),
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            label = { Text("Bridge 地址（如 192.168.1.20:8767）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            label = { Text("6 位配对码") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = V2Spacing.Md),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = V2Spacing.Lg),
            horizontalArrangement = Arrangement.spacedBy(V2Spacing.Md),
        ) {
            Button(
                onClick = onPair,
                enabled = !state.pairingBusy && baseUrl.isNotBlank() && code.trim().length == 6,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.pairingBusy) "检查中…" else "检查能力并配对")
            }
            OutlinedButton(onClick = onRetry, enabled = !state.pairingBusy && baseUrl.isNotBlank()) {
                Text("重试")
            }
        }

        if (state.missingCapabilities.isNotEmpty()) {
            Text(
                "当前 Bridge 缺少生图审核 v2 能力：",
                color = V2Colors.Warning,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = V2Spacing.Lg),
            )
            state.missingCapabilities.forEach { capability ->
                Text("· $capability", color = MediaTextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "请先升级 NSFW Studio Mobile Review Bridge；本轮客户端不会把旧 Bridge 当成可用。",
                color = MediaTextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = V2Spacing.Sm),
            )
        }

        state.message?.takeIf { state.missingCapabilities.isEmpty() }?.let { message ->
            Text(
                message,
                color = if (message.contains("失败") || message.contains("无法")) {
                    Color(0xFFFF6B6B)
                } else {
                    MediaTextSecondary
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = V2Spacing.Md),
            )
        }

        Spacer(Modifier.weight(1f))
        Text(
            "配对只读写 NSFW 自己的 DataStore；MediaReview Server 的连接与 token 不会被覆盖。",
            style = MaterialTheme.typography.labelSmall,
            color = MediaTextSecondary,
        )
    }
}

@Composable
private fun ReadyPane(
    state: NsfwUiState,
    imageLoader: ImageLoader,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSelect: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDecision: (String, Boolean?) -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenDetails: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = V2Spacing.Md, vertical = V2Spacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("生图审核", style = MaterialTheme.typography.titleLarge, color = MediaTextPrimary)
                Text(
                    state.baseUrl.ifBlank { "已配对" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MediaTextSecondary,
                )
            }
            TextButton(onClick = onRefresh, enabled = !state.busy) {
                Text(if (state.busy) "同步中…" else "同步新图")
            }
            TextButton(onClick = onDisconnect) { Text("断开") }
        }

        state.message?.let { Banner(it, warning = false) }
        state.conflictMessage?.let { Banner(it, warning = true) }
        if (state.cursorExpired) Banner("游标已过期，已从头同步；当前图片位置保持不变", warning = true)
        state.evaluationRecoveryHint?.let { Banner(it, warning = true) }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
        ) {
            val item = state.currentItem
            if (item == null) {
                Text(
                    "暂无待审核图片。新图会自动追加到队尾。",
                    color = MediaTextSecondary,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                AsyncImage(
                    model = safeNsfwImageUrl(state.baseUrl, item.previewUrl),
                    imageLoader = imageLoader,
                    contentDescription = "待审核图片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color(0x99000000))
                        .padding(V2Spacing.Md),
                ) {
                    Text(
                        "提示词关联：" + linkStateLabel(item.prompt),
                        color = MediaTextPrimary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        item.prompt?.promptId ?: "无可靠提示词关联",
                        color = MediaTextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        "生成时间：" + formatIso(item.createdAt) +
                            " · " + (item.width ?: 0) + "×" + (item.height ?: 0),
                        color = MediaTextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (state.detailLoading) {
                    CircularProgressIndicator(
                        color = V2Colors.Accent,
                        modifier = Modifier.align(Alignment.TopEnd).padding(V2Spacing.Md).size(22.dp),
                    )
                }
            }
        }

        if (state.newImageCount > 0) {
            Text(
                "已追加 ${state.newImageCount} 张新图到队尾（当前图片位置不变）",
                color = V2Colors.Accent,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = V2Spacing.Md, vertical = V2Spacing.Xs),
            )
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth().height(76.dp),
            contentPadding = PaddingValues(horizontal = V2Spacing.Md, vertical = V2Spacing.Xs),
            horizontalArrangement = Arrangement.spacedBy(V2Spacing.Xs),
        ) {
            items(state.items, key = { it.imageId }) { item ->
                Thumbnail(
                    url = safeNsfwImageUrl(state.baseUrl, item.thumbnailUrl),
                    imageLoader = imageLoader,
                    selected = item.imageId == state.currentId,
                    onClick = { onSelect(item.imageId) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = V2Spacing.Md),
            horizontalArrangement = Arrangement.spacedBy(V2Spacing.Sm),
        ) {
            Button(
                onClick = { onDecision("KEEP", null) },
                enabled = !state.busy && state.detail != null,
                modifier = Modifier.weight(1f),
            ) { Text("保留", style = MaterialTheme.typography.labelLarge) }
            Button(
                onClick = { onDecision("REJECT", null) },
                enabled = !state.busy && state.detail != null,
                modifier = Modifier.weight(1f),
            ) { Text("淘汰", style = MaterialTheme.typography.labelLarge) }
            OutlinedButton(
                onClick = { onDecision("RESET", null) },
                enabled = !state.busy && state.detail != null,
                modifier = Modifier.weight(1f),
            ) { Text("重置", style = MaterialTheme.typography.labelLarge) }
            OutlinedButton(
                onClick = onToggleFavorite,
                enabled = !state.busy && state.detail != null,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (state.currentItem?.isFavorite == true) "取消收藏" else "收藏",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = V2Spacing.Md, vertical = V2Spacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onPrevious, enabled = state.currentIndex > 0) { Text("上一张") }
            Text(
                if (state.items.isEmpty()) "0 / 0" else "${state.currentIndex + 1} / ${state.items.size}",
                color = MediaTextSecondary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            if (state.hasMore) {
                TextButton(onClick = onLoadMore, enabled = !state.busy) {
                    Text(if (state.busy) "加载中…" else "加载更多")
                }
            }
            TextButton(onClick = onOpenDetails, enabled = state.detail != null) { Text("图片信息") }
            TextButton(onClick = onNext, enabled = state.currentIndex + 1 < state.items.size) { Text("下一张") }
        }
    }
}

@Composable
private fun Banner(text: String, warning: Boolean) {
    Text(
        text,
        color = if (warning) V2Colors.Warning else MediaTextSecondary,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(MediaSurface)
            .padding(horizontal = V2Spacing.Md, vertical = V2Spacing.Xs),
    )
}

@Composable
private fun Thumbnail(
    url: String,
    imageLoader: ImageLoader,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(if (selected) V2Colors.Accent else Color.Transparent)
            .padding(2.dp)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = url,
            imageLoader = imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun DetailsDialog(
    detail: NsfwImageDetails,
    item: NsfwFeedItem?,
    onDismiss: () -> Unit,
    onScore: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("图片信息") },
        text = {
            Column(Modifier.height(420.dp).verticalScroll(rememberScrollState())) {
                InfoLine("图片 ID", detail.imageId)
                InfoLine("审核状态", item?.status ?: "未知")
                InfoLine("尺寸", "${detail.width ?: 0}×${detail.height ?: 0}")
                InfoLine("入库时间", formatIso(detail.createdAt))
                InfoLine("生成时间", formatIso(detail.generatedAt ?: "未知"))
                InfoLine("提示词关联", linkStateText(detail.linkState))
                InfoLine("提示词 ID", detail.promptId ?: "无")
                InfoLine("提示词版本", detail.promptVersionId ?: "未知")
                InfoLine("生成快照（正向）", detail.positivePrompt ?: "未知")
                InfoLine("生成快照（负向）", detail.negativePrompt ?: "未知")
                InfoLine("任务 / 模块", (detail.jobId ?: "无") + " / " + (detail.moduleId ?: "无"))
                InfoLine("Seed", detail.seed?.toString() ?: "未知")
                InfoLine("模型", detail.model ?: "未知")
                InfoLine("大小 / 类型", (detail.sizeBytes?.toString() ?: "未知") + " / " + (detail.mime ?: "未知"))
                InfoLine("更新时间 / revision", formatIso(detail.updatedAt) + " / " + detail.revision)
            }
        },
        confirmButton = {
            Button(onClick = onScore, enabled = detail.promptId != null) { Text("提示词评分") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun PromptScoreDialog(
    panel: NsfwPromptPanel,
    vm: NsfwReviewViewModel,
    baseUrl: String,
    imageLoader: ImageLoader,
) {
    var overall by remember(panel.requestId) {
        mutableStateOf(panel.pendingRequest?.overallScore ?: 0)
    }
    var dimensions by remember(panel.requestId) {
        mutableStateOf(panel.pendingRequest?.dimensions.orEmpty())
    }
    var notes by remember(panel.requestId) {
        mutableStateOf(panel.pendingRequest?.notes ?: "")
    }

    AlertDialog(
        onDismissRequest = vm::closePromptPanel,
        title = { Text("提示词评分") },
        text = {
            Column(Modifier.height(460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "评分对象：版本 " + (panel.versionId ?: "未知（整体评分）"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MediaTextSecondary,
                )
                panel.promptSnapshot?.let {
                    Text(
                        "生成快照：" + it.take(120),
                        style = MaterialTheme.typography.bodySmall,
                        color = MediaTextSecondary,
                        modifier = Modifier.padding(top = V2Spacing.Xs),
                    )
                }

                panel.summary?.let { summary ->
                    Text(
                        "本版本共 ${summary.imagesTotal} 张 · 已看 ${summary.seenCount} · " +
                            "保留 ${summary.keepCount} · 驳回 ${summary.rejectCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MediaTextSecondary,
                        modifier = Modifier.padding(top = V2Spacing.Sm),
                    )
                    if (summary.hasNewSamples) {
                        Text(
                            "评分后有新样本，可自愿复评（不会自动改分）",
                            style = MaterialTheme.typography.labelSmall,
                            color = V2Colors.Warning,
                        )
                    }
                    summary.versionLatest?.let {
                        Text("本版本最新评分：${it.overallScore} 分", style = MaterialTheme.typography.labelSmall, color = MediaTextPrimary)
                    }
                    summary.promptLatest?.let {
                        Text(
                            "提示词整体最近评分：${it.overallScore} 分（版本：${it.promptVersionId ?: "未知"}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MediaTextSecondary,
                        )
                    }
                }

                if (panel.images.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(vertical = V2Spacing.Sm),
                        horizontalArrangement = Arrangement.spacedBy(V2Spacing.Xs),
                    ) {
                        items(panel.images, key = { it.imageId }) { image ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier
                                        .size(56.dp)
                                        .background(if (image.isCurrent) V2Colors.Accent else Color.Transparent)
                                        .padding(2.dp),
                                ) {
                                    AsyncImage(
                                        model = safeNsfwImageUrl(baseUrl, image.thumbnailUrl),
                                        imageLoader = imageLoader,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                Text(
                                    if (image.isCurrent) "当前" else image.status,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MediaTextSecondary,
                                )
                            }
                        }
                    }
                }

                if (!panel.scoringEnabled) {
                    Text(
                        "该提示词可靠，但生成版本未知。请先明确选择评分范围。",
                        style = MaterialTheme.typography.bodySmall,
                        color = V2Colors.Warning,
                    )
                    TextButton(onClick = vm::confirmWholePromptScope) {
                        Text("对提示词整体评分（生成版本未知）")
                    }
                }

                Text(
                    "总分（1–5）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MediaTextPrimary,
                    modifier = Modifier.padding(top = V2Spacing.Md),
                )
                ScoreRow(label = "总分", value = overall, onChange = { overall = it })

                Text(
                    "维度（可选，只提交填写项）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MediaTextPrimary,
                    modifier = Modifier.padding(top = V2Spacing.Md),
                )
                SCORE_DIMENSIONS.forEach { (key, label) ->
                    ScoreRow(
                        label = label,
                        value = dimensions[key] ?: 0,
                        onChange = { value ->
                            dimensions = if (value == 0) dimensions - key else dimensions + (key to value)
                        },
                    )
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth().padding(top = V2Spacing.Md),
                )

                panel.message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (panel.unconfirmed) V2Colors.Warning else MediaTextSecondary,
                        modifier = Modifier.padding(top = V2Spacing.Sm),
                    )
                }
                if (panel.unconfirmed || panel.message?.contains("新建评分请求") == true) {
                    TextButton(onClick = vm::resetEvaluationDraft) {
                        Text("新建评分请求（旧请求保留回执）", color = V2Colors.Accent)
                    }
                }
                panel.submitted?.let {
                    Text(
                        "已保存：${it.overallScore} 分 · ${formatIso(it.createdAt)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = V2Colors.Accent,
                        modifier = Modifier.padding(top = V2Spacing.Sm),
                    )
                }
                if (panel.loading) {
                    Text("正在加载提示词信息…", style = MaterialTheme.typography.labelSmall, color = MediaTextSecondary)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { vm.submitEvaluation(overall, dimensions, notes) },
                enabled = panel.scoringEnabled && overall in 1..5 && !panel.submitting && panel.submitted == null,
            ) {
                Text(if (panel.submitting) "保存中…" else "保存评分")
            }
        },
        dismissButton = { TextButton(onClick = vm::closePromptPanel) { Text("关闭") } },
    )
}

@Composable
private fun ScoreRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MediaTextSecondary,
            modifier = Modifier.width(48.dp),
        )
        (1..5).forEach { score ->
            val selected = value == score
            TextButton(
                onClick = { onChange(if (selected) 0 else score) },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    score.toString(),
                    color = if (selected) V2Colors.Accent else MediaTextPrimary,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MediaTextSecondary, modifier = Modifier.width(112.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MediaTextPrimary, modifier = Modifier.weight(1f))
    }
}

private val SCORE_DIMENSIONS = listOf(
    "subject" to "人物",
    "clothing" to "服饰",
    "scene" to "场景",
    "composition" to "构图",
    "lighting" to "光影",
    "style" to "风格",
    "quality" to "画质",
)

private fun linkStateText(linkState: String): String = when (linkState) {
    NsfwPromptLink.LINK_EXACT -> "精确（提示词 + 生成版本）"
    NsfwPromptLink.LINK_UNRESOLVED -> "提示词可靠，生成版本未知"
    else -> "无可靠关联"
}

private fun linkStateLabel(link: NsfwPromptLink?): String = when {
    link == null -> "无"
    link.isExact -> "精确"
    link.isUnresolved -> "版本未知"
    else -> "未关联"
}

private fun formatIso(value: String): String =
    if (value.isBlank()) "未知" else value.take(19).replace('T', ' ')
