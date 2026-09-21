package com.mediareview.app.feature.v2.viewer

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest

/**
 * 图片预加载：当前 N 的前 1 / 后 2（N-1、N+1、N+2）。
 * 绝不整文件夹预加载，避免一次 decode 全部图片。
 */
@Composable
fun ImagePreloader(
    uris: List<String>,
    currentIndex: Int,
) {
    val context = LocalContext.current
    val targets = remember(uris, currentIndex) {
        buildList {
            if (currentIndex - 1 >= 0) add(uris[currentIndex - 1])
            if (currentIndex + 1 < uris.size) add(uris[currentIndex + 1])
            if (currentIndex + 2 < uris.size) add(uris[currentIndex + 2])
        }.distinct()
    }
    LaunchedEffect(targets) {
        targets.forEach { uri ->
            runCatching {
                context.imageLoader.enqueue(
                    ImageRequest.Builder(context).data(uri).build(),
                )
            }
        }
    }
}

/** 供非 Composable 场景（如调试）使用：返回图片 URI 列表。 */
fun imageUris(context: Context, uris: List<String>): List<String> = uris
