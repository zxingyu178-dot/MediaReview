package com.mediareview.app.feature.v2.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mediareview.app.feature.v2.player.gesture.PlayerGestureHandlers
import com.mediareview.app.feature.v2.player.gesture.playerGestures

/**
 * 播放器手势覆盖层：铺满画面，把原始手势事件路由给 [PlayerGestureHandlers]。
 * 位于视频层之上、控制层之下，因此控制按钮的点击优先于本层手势。
 */
@Composable
fun PlayerGestureOverlay(
    handlers: PlayerGestureHandlers,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .playerGestures(handlers),
    )
}
