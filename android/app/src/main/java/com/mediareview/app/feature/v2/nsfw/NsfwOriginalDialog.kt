package com.mediareview.app.feature.v2.nsfw

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mediareview.app.feature.v2.nsfw.data.NsfwFeedItem
import com.mediareview.app.feature.v2.nsfw.di.NsfwImageLoader
import com.mediareview.app.feature.v2.viewer.ZoomableImage
import com.mediareview.app.feature.v2.viewer.ZoomImageSource

/** 打开时固定图片身份；离开只关闭查看器，不改变队列、审核或评分。 */
@Composable
internal fun NsfwOriginalDialog(
    item: NsfwFeedItem,
    baseUrl: String,
    imageLoader: NsfwImageLoader,
    onDismiss: () -> Unit,
) {
    val preview = safeNsfwImageUrl(baseUrl, item.previewUrl)
    val original = safeNsfwImageUrl(baseUrl, item.originalUrl)
    val source = remember(item.imageId, preview, original, imageLoader) {
        ZoomImageSource(imageLoader.loader, imageLoader.request(preview), imageLoader.request(original, original = true))
    }
    var scale by remember { mutableFloatStateOf(1f) }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("关闭原图") }
                Text("原图", color = Color.White, modifier = Modifier.weight(1f))
                Text("${(scale * 100).toInt()}%", color = Color.White, modifier = Modifier.padding(16.dp))
            }
            if (original.isBlank()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("当前图片未提供可用原图，请返回继续审核", color = Color.White)
                }
            } else {
                ZoomableImage(preview, original, true, true, item.width ?: 0, item.height ?: 0,
                    onTap = {}, onScaleChange = { scale = it }, modifier = Modifier.weight(1f), source = source)
            }
            Text("双指缩放 · 放大后可拖动 · 双击放大或复位", color = Color.White,
                modifier = Modifier.padding(12.dp))
        }
    }
}
