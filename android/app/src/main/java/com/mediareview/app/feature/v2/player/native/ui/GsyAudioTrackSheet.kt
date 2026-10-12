package com.mediareview.app.feature.v2.player.native.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.player.native.tracks.AudioTrackKey
import com.mediareview.app.feature.v2.player.native.tracks.AudioTrackMenu
import com.mediareview.app.ui.theme.MediaTextPrimary

/** 仅显示内核实际存在且支持的音轨；勾选来自真实 selected 状态。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GsyAudioTrackSheet(menu: AudioTrackMenu, onSelect: (AudioTrackKey) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("音轨", style = MaterialTheme.typography.titleMedium, color = MediaTextPrimary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            menu.choices.forEach { choice ->
                Row(
                    Modifier.fillMaxWidth().testTag("audio_track_${choice.key.groupIndex}_${choice.key.trackIndex}")
                        .clickable { onSelect(choice.key) }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(choice.label, style = MaterialTheme.typography.bodyLarge, color = MediaTextPrimary,
                        modifier = Modifier.weight(1f))
                    if (choice.selected) Icon(Icons.Default.Check, contentDescription = "已选择", tint = MediaTextPrimary)
                }
            }
        }
    }
}
