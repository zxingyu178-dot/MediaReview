package com.mediareview.app.feature.v2.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mediareview.app.BuildConfig
import com.mediareview.app.core.pairing.REQUIRED_SERVER_API_CONTRACT
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.data.server.V2ServerStatus
import com.mediareview.app.feature.v2.home.V2HomeViewModel
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.feature.v2.ui.V2Radius
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaDanger
import com.mediareview.app.ui.theme.MediaSurfaceRaised
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary

/** Server 连接状态 → 用户可见中文（顶部状态提示与设置 Sheet 共用）。 */
fun V2ServerStatus.displayLabel(): String = when (this) {
    V2ServerStatus.Unconfigured -> "未配置服务器"
    V2ServerStatus.Probing -> "重新连接中"
    V2ServerStatus.Online -> "服务器在线"
    V2ServerStatus.Offline -> "服务器离线"
    V2ServerStatus.AuthRejected -> "认证失效"
    // §13：版本过旧 ≠ 离线 / 认证失败，必须单独表达。
    V2ServerStatus.Incompatible -> "服务器版本过旧"
}

/**
 * 数据源设置 Sheet（Stage 8A §31 / §32）：
 *
 * - 数据源单选：● Demo（离线演示）/ ○ 我的服务器；
 * - Server 模式无配置时**不跳出 V2**：在本 Sheet 内提供"连接服务器"（手动 IP + 配对码，
 *   复用 PairingRepository.checkHealthy / verifyAndPair）；
 * - Server 挂了也能随时切回 Demo 继续使用 App。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun V2DataSourceSheet(
    vm: V2HomeViewModel,
    onDismiss: () -> Unit,
    /** 「本次更新」入口（Stage 8C.1 §52）：打开当前版本的更新日志 Sheet。 */
    onOpenWhatsNew: () -> Unit = {},
) {
    val dataMode by vm.dataMode.collectAsState()
    val status by vm.serverStatus.collectAsState()
    val session by vm.serverSession.collectAsState()
    val connect by vm.connectState.collectAsState()
    val serverVersion by vm.serverVersion.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = "数据源",
                style = MaterialTheme.typography.titleMedium,
                color = MediaTextPrimary,
                modifier = Modifier.padding(bottom = V2Spacing.Sm),
            )

            DataSourceOption(
                title = "演示数据",
                subtitle = "APK 内置离线媒体，不需要电脑服务器",
                selected = dataMode == V2DataMode.DEMO,
                onClick = { vm.setDataMode(V2DataMode.DEMO) },
            )
            DataSourceOption(
                title = "我的服务器",
                subtitle = "连接家庭电脑上的 MediaReview 服务器",
                selected = dataMode == V2DataMode.SERVER,
                onClick = { vm.setDataMode(V2DataMode.SERVER) },
            )

            if (dataMode == V2DataMode.SERVER) {
                Spacer(modifier = Modifier.size(V2Spacing.Md))
                StatusRow(status = status, statusLabel = status.displayLabel())
                if (status == V2ServerStatus.Incompatible) {
                    // §13：明确区分"服务器在线"与"服务器版本过旧"，并给出双方版本。
                    IncompatibleNotice(
                        serverVersion = serverVersion,
                        requiredContract = REQUIRED_SERVER_API_CONTRACT,
                    )
                }
                if (session.configured) {
                    Text(
                        text = session.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MediaTextSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = if (session.paired) "已配对" else "尚未配对",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (session.paired) V2Colors.Accent else MediaDanger,
                    )
                    // §36：Server 模式已连接时同时显示手机端与电脑端版本，方便排查。
                    if (status == V2ServerStatus.Online && serverVersion.isNotBlank()) {
                        Text(
                            text = "手机：MediaReview ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MediaTextSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            text = "电脑端：MediaReview Server $serverVersion",
                            style = MaterialTheme.typography.bodySmall,
                            color = MediaTextSecondary,
                        )
                    }
                }

                // 首次配置 / 认证失效 → Sheet 内的连接表单（不跳出 V2）
                if (!session.configured || !session.paired) {
                    Text(
                        text = if (!session.configured) "尚未连接服务器" else "连接凭据已失效，请重新配对",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MediaTextPrimary,
                        modifier = Modifier.padding(top = V2Spacing.Md),
                    )
                    OutlinedTextField(
                        value = connect.address,
                        onValueChange = vm::onConnectAddressChange,
                        label = { Text("服务器地址（如 192.168.1.10）") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = V2Spacing.Sm),
                    )
                    OutlinedTextField(
                        value = connect.code,
                        onValueChange = vm::onConnectCodeChange,
                        label = { Text("配对码（在服务器端查看）") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = V2Spacing.Sm),
                    )
                    connect.error?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MediaDanger,
                            modifier = Modifier.padding(top = V2Spacing.Sm),
                        )
                    }
                    connect.message?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = V2Colors.Accent,
                            modifier = Modifier.padding(top = V2Spacing.Sm),
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(V2Radius.Chip),
                        color = V2Colors.Accent,
                        onClick = { if (!connect.busy) vm.connectServer() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = V2Spacing.Md),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 12.dp)) {
                            if (connect.busy) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        color = MediaTextPrimary,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = "连接中…",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MediaTextPrimary,
                                        modifier = Modifier.padding(start = V2Spacing.Sm),
                                    )
                                }
                            } else {
                                Text(
                                    text = "连接服务器",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MediaTextPrimary,
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.padding(top = V2Spacing.Sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { vm.probeServer() }) {
                            Text("重新检测", color = MediaTextPrimary)
                        }
                        TextButton(onClick = { vm.clearServerConfig() }) {
                            Text("断开连接", color = MediaDanger)
                        }
                    }
                }
            }

            // Stage 8C.1 §51/§52：设置底部显示当前 App 版本，并可主动查看本次更新
            Spacer(modifier = Modifier.size(V2Spacing.Lg))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "MediaReview ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MediaTextSecondary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenWhatsNew) {
                    Text("本次更新", color = MediaTextPrimary)
                }
            }
        }
    }
}

@Composable
private fun DataSourceOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(V2Radius.Card))
            .background(if (selected) MediaSurfaceRaised else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = V2Spacing.Sm, horizontal = V2Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = V2Spacing.Sm)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MediaTextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MediaTextSecondary)
        }
    }
}

/** §13/§36：旧 Server 的明确提示（含双方版本），不使用"认证失败"语义。 */
@Composable
private fun IncompatibleNotice(serverVersion: String, requiredContract: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = V2Spacing.Sm)
            .clip(RoundedCornerShape(V2Radius.Card))
            .background(MediaSurfaceRaised)
            .padding(V2Spacing.Md),
    ) {
        Text(
            text = "服务器版本过旧",
            style = MaterialTheme.typography.bodyLarge,
            color = V2Colors.Warning,
        )
        Text(
            text = "当前电脑端：${serverVersion.ifBlank { "未知" }}",
            style = MaterialTheme.typography.bodySmall,
            color = MediaTextSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "手机需要：API Contract $requiredContract",
            style = MaterialTheme.typography.bodySmall,
            color = MediaTextSecondary,
        )
        Text(
            text = "请升级 MediaReview Server",
            style = MaterialTheme.typography.bodySmall,
            color = MediaTextSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun StatusRow(status: V2ServerStatus, statusLabel: String) {
    val tint = when (status) {
        V2ServerStatus.Online -> V2Colors.Accent
        // §15：版本过旧使用警告色，而不是"认证失败"的红色。
        V2ServerStatus.Incompatible -> V2Colors.Warning
        V2ServerStatus.AuthRejected, V2ServerStatus.Offline -> MediaDanger
        else -> MediaTextSecondary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(tint),
        )
        Spacer(modifier = Modifier.width(V2Spacing.Sm))
        Text(
            text = statusLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
        )
    }
}