package com.mediareview.app.feature.duplicates

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavController
import androidx.navigation.compose.composable
import com.mediareview.app.core.model.DuplicateGroupDto

/** 重复文件页导航路由。 */
object DuplicatesDestinations {
    const val DUPLICATES = "duplicates"
}

/** 注册重复文件页目的地。 */
fun NavGraphBuilder.duplicatesGraph(navController: NavController) {
    composable(DuplicatesDestinations.DUPLICATES) {
        DuplicatesScreen(onBack = { navController.popBackStack() })
    }
}

/** 重复文件页:完全重复 + 疑似重复分组(只读,绝不自动删除)。 */
@Composable
fun DuplicatesScreen(
    onBack: () -> Unit,
    viewModel: DuplicatesViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    LaunchedEffect(Unit) { viewModel.load() }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("重复文件", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { viewModel.load() }) { Text("刷新") }
            TextButton(onClick = onBack) { Text("返回") }
        }

        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            ui.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(ui.error!!)
            }

            ui.exact.isEmpty() && ui.similar.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { Text("暂无重复文件") }

            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.exact.isNotEmpty()) {
                    item { SectionHeader("完全重复(${ui.exact.size} 组)") }
                    items(ui.exact, key = { it.group_id }) { DuplicateGroupRow(it) }
                }
                if (ui.similar.isNotEmpty()) {
                    item { SectionHeader("疑似重复(${ui.similar.size} 组)") }
                    items(ui.similar, key = { it.group_id }) { DuplicateGroupRow(it) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun DuplicateGroupRow(group: DuplicateGroupDto) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
            .padding(12.dp),
    ) {
        Text(
            text = "${group.count} 个文件 · ${fmtBytes(group.size_bytes)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        group.duration_ms?.let { Text("时长 ${it / 1000}s", style = MaterialTheme.typography.labelSmall) }
        group.names.forEach { name ->
            Text(
                text = "· $name",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (group.detail.isNotBlank()) {
            Text(
                text = group.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun fmtBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> "%.1f GB".format(gb)
        mb >= 1 -> "%.1f MB".format(mb)
        else -> "%.0f KB".format(bytes / 1024.0)
    }
}
