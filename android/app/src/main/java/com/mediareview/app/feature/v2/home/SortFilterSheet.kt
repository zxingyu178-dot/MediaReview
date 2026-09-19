package com.mediareview.app.feature.v2.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.model.V2SortField
import com.mediareview.app.feature.v2.model.V2SortOrder
import com.mediareview.app.feature.v2.model.V2SortSpec
import com.mediareview.app.feature.v2.model.V2TypeFilter
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.feature.v2.ui.V2Radius
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaSurface
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary

/** 排序字段中文名。 */
fun sortFieldLabel(field: V2SortField): String = when (field) {
    V2SortField.RECENT -> "最近添加"
    V2SortField.NAME -> "文件名"
    V2SortField.DURATION -> "时长"
    V2SortField.SIZE -> "大小"
}

fun sortOrderLabel(order: V2SortOrder): String = when (order) {
    V2SortOrder.ASC -> "正序"
    V2SortOrder.DESC -> "倒序"
}

fun typeFilterLabel(filter: V2TypeFilter): String = when (filter) {
    V2TypeFilter.ALL -> "全部"
    V2TypeFilter.VIDEO -> "视频"
    V2TypeFilter.IMAGE -> "图片"
}

/** 首页排序/筛选入口行。 */
@Composable
fun SortFilterRow(
    spec: V2SortSpec,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(V2Radius.Button))
            .background(MediaSurface.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = V2Spacing.Lg, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.SwapVert,
            contentDescription = null,
            tint = MediaTextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "${sortFieldLabel(spec.field)} · ${sortOrderLabel(spec.order)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MediaTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = V2Spacing.Sm),
        )
        Icon(
            imageVector = Icons.Default.FilterList,
            contentDescription = null,
            tint = MediaTextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = typeFilterLabel(spec.typeFilter),
            style = MaterialTheme.typography.bodyMedium,
            color = MediaTextSecondary,
            modifier = Modifier.padding(start = V2Spacing.Sm),
        )
    }
}

/** 排序/筛选 Bottom Sheet。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortFilterSheet(
    spec: V2SortSpec,
    onChange: (V2SortSpec) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MediaSurface,
    ) {
        Column(modifier = Modifier.padding(bottom = V2Spacing.Xxl)) {
            Text(
                text = "排序与筛选",
                style = MaterialTheme.typography.titleMedium,
                color = MediaTextPrimary,
                modifier = Modifier.padding(horizontal = V2Spacing.Xl, vertical = V2Spacing.Md),
            )

            // 排序字段
            Text(
                text = "排序字段",
                style = MaterialTheme.typography.labelMedium,
                color = MediaTextSecondary,
                modifier = Modifier.padding(horizontal = V2Spacing.Xl, vertical = V2Spacing.Sm),
            )
            Row(modifier = Modifier.padding(horizontal = V2Spacing.Xl)) {
                V2SortField.entries.forEach { field ->
                    OptionChip(
                        text = sortFieldLabel(field),
                        selected = spec.field == field,
                        onClick = { onChange(spec.copy(field = field)) },
                    )
                    Spacer(modifier = Modifier.size(V2Spacing.Sm))
                }
            }

            Spacer(modifier = Modifier.size(V2Spacing.Lg))
            Text(
                text = "顺序",
                style = MaterialTheme.typography.labelMedium,
                color = MediaTextSecondary,
                modifier = Modifier.padding(horizontal = V2Spacing.Xl, vertical = V2Spacing.Sm),
            )
            Row(modifier = Modifier.padding(horizontal = V2Spacing.Xl)) {
                V2SortOrder.entries.forEach { order ->
                    OptionChip(
                        text = sortOrderLabel(order),
                        selected = spec.order == order,
                        onClick = { onChange(spec.copy(order = order)) },
                    )
                    Spacer(modifier = Modifier.size(V2Spacing.Sm))
                }
            }

            Spacer(modifier = Modifier.size(V2Spacing.Lg))
            Text(
                text = "媒体类型",
                style = MaterialTheme.typography.labelMedium,
                color = MediaTextSecondary,
                modifier = Modifier.padding(horizontal = V2Spacing.Xl, vertical = V2Spacing.Sm),
            )
            Row(modifier = Modifier.padding(horizontal = V2Spacing.Xl)) {
                V2TypeFilter.entries.forEach { filter ->
                    OptionChip(
                        text = typeFilterLabel(filter),
                        selected = spec.typeFilter == filter,
                        onClick = { onChange(spec.copy(typeFilter = filter)) },
                    )
                    Spacer(modifier = Modifier.size(V2Spacing.Sm))
                }
            }
        }
    }
}

@Composable
private fun OptionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(V2Radius.Chip))
            .background(if (selected) V2Colors.Accent.copy(alpha = 0.18f) else MediaSurface.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = V2Spacing.Lg, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) V2Colors.Accent else MediaTextSecondary,
        )
    }
}
