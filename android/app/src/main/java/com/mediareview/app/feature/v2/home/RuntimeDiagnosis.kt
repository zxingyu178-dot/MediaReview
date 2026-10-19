package com.mediareview.app.feature.v2.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mediareview.app.ui.theme.MediaTextSecondary

/** 来自本次 health probe 的事实；不参与全局 API contract Gate。 */
@Composable
internal fun RuntimeDiagnosis(vm: V2HomeViewModel) {
    val version by vm.serverVersion.collectAsState()
    val contract by vm.serverApiContract.collectAsState()
    val capabilities by vm.serverCapabilities.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("runtime_diagnosis")) {
        Text("电脑端 ${version.ifBlank { "未探测" }} · ${if (expanded) "收起诊断" else "查看诊断"}",
            color = MediaTextSecondary, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 8.dp))
        if (expanded) {
            Text("API Contract：${if (contract == 0) "未探测" else contract.toString()}",
                color = MediaTextSecondary, style = MaterialTheme.typography.labelSmall)
            Text("支持能力：${capabilities.joinToString().ifBlank { "无" }}", color = MediaTextSecondary,
                style = MaterialTheme.typography.labelSmall)
        }
        if (contract > 0 && "folder_tree_v1" !in capabilities) {
            Text("电脑端版本过旧，层级分类需要 MediaReview Server 1.3.0+", color = MediaTextSecondary,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("folder_upgrade_hint"))
        }
    }
}
