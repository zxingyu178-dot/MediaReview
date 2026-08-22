package com.mediareview.app.feature.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import coil.compose.AsyncImage

/** 图片查看器导航路由:只传 mediaId,详情由页面自行获取。 */
object ImageViewerDestinations {
    const val ROUTE = "image_viewer/{mediaId}"
    fun build(mediaId: String): String = "image_viewer/$mediaId"
}

/** 注册图片查看器目的地。 */
fun NavGraphBuilder.imageViewerGraph(navController: NavController) {
    composable(
        route = ImageViewerDestinations.ROUTE,
        arguments = listOf(navArgument("mediaId") { type = NavType.StringType }),
    ) { entry ->
        val mediaId = entry.arguments?.getString("mediaId").orEmpty()
        ImageViewerScreen(
            mediaId = mediaId,
            onBack = { navController.popBackStack() },
        )
    }
}

/**
 * 图片查看器:全屏大图(原图优先),支持双击缩放、双指缩放、拖动平移;
 * 平移受边界约束,缩回 1x 时自动归零,图片不会拖出屏幕。
 */
@Composable
fun ImageViewerScreen(
    mediaId: String,
    onBack: () -> Unit,
    viewModel: ImageViewerViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()

    LaunchedEffect(mediaId) { viewModel.load(mediaId) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var container by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { container = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    val newOffset = offset + pan
                    scale = newScale
                    val (x, y) = clampPanOffset(
                        newOffset.x, newOffset.y, newScale,
                        container.width.toFloat(), container.height.toFloat(),
                    )
                    offset = Offset(x, y)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2f
                        }
                    },
                )
            },
    ) {
        when {
            ui.loading -> CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )

            ui.error != null || ui.imageUrl.isNullOrBlank() -> Text(
                text = ui.error ?: "图片地址缺失",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> AsyncImage(
                model = ui.imageUrl,
                contentDescription = ui.media?.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }

        // 顶栏:返回 + 文件名
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color(0x88000000))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("返回", color = Color.White) }
            Spacer(Modifier.weight(1f))
            Text(
                text = ui.media?.name ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}
