package com.mediareview.app.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mediareview.app.core.model.LibraryItem

/** 媒体库导航路由。 */
object LibraryDestinations {
    const val LIBRARY = "library"
}

/** 注册媒体库勾选目的地。 */
fun NavGraphBuilder.libraryGraph(navController: NavController) {
    composable(LibraryDestinations.LIBRARY) {
        LibraryScreen(
            onBack = { navController.popBackStack() },
        )
    }
}

/** 媒体库勾选页。 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(20.dp),
        ) {
            Text("选择媒体库", style = MaterialTheme.typography.titleLarge)
            Text("勾选后将用于媒体墙与批阅", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))

            when {
                ui.loading -> CircularProgressIndicator()
                ui.error != null -> Text(ui.error!!, color = MaterialTheme.colorScheme.error)
                else -> {
                    ui.libraries.forEach { lib ->
                        LibraryRow(
                            lib = lib,
                            checked = lib.jellyfin_id in ui.selectedIds,
                            onToggle = { viewModel.toggle(lib.jellyfin_id) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            if (ui.saved) {
                Text("已保存", color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = viewModel::save,
                enabled = !ui.loading && !ui.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (ui.saving) "保存中…" else "保存")
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("返回")
            }
        }
    }
}

@Composable
private fun LibraryRow(
    lib: LibraryItem,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column {
            Text(lib.name, style = MaterialTheme.typography.bodyLarge)
            lib.collection_type?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}