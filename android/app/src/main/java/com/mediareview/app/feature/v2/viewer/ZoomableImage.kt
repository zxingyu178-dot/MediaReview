package com.mediareview.app.feature.v2.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary

/**
 * 可缩放图片：
 * - 单击显隐 UI；双击 1.0x ↔ 2.5x 且以点击点为中心；
 * - 双指缩放 1.0x ~ 5.0x；放大后单指拖动（带边界 clamp）；
 * - scale == 1 时不消费手势，交给 Pager 翻页；scale 回到 1 时 offset 归零；
 * - 成为当前页时重置缩放状态。
 */
@Composable
fun ZoomableImage(
    uri: String,
    isCurrent: Boolean,
    naturalWidth: Int,
    naturalHeight: Int,
    onTap: () -> Unit,
    onScaleChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewport by remember { mutableStateOf(Size.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var retryKey by remember { mutableIntStateOf(0) }
    val scaleRef = rememberUpdatedState(scale)
    val offsetRef = rememberUpdatedState(offset)

    // graphicsLayer 默认 TransformOrigin.Center => origin = viewport 中心
    fun viewportOrigin(v: Size): Offset = Offset(v.width / 2f, v.height / 2f)

    // 翻页成为当前页时统一重置缩放（不记忆每张图独立缩放位置）
    LaunchedEffect(isCurrent) {
        if (isCurrent) {
            scale = 1f
            offset = Offset.Zero
            onScaleChange(1f)
        }
    }

    val imageSize = remember(viewport, naturalWidth, naturalHeight) {
        ZoomMath.fitImageSize(naturalWidth, naturalHeight, viewport)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(uri) {
                var lastDist = 0f
                var multiTouch = false
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    lastDist = 0f
                    multiTouch = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        when {
                            pressed.size >= 2 -> {
                                multiTouch = true
                                val c0 = pressed[0].position
                                val c1 = pressed[1].position
                                val dist = (c0 - c1).getDistance()
                                val centroid = (c0 + c1) / 2f
                                if (lastDist > 0f) {
                                    val newScale = ZoomMath.clampScale(scale * dist / lastDist)
                                    val effectiveK = newScale / scale
                                    if (effectiveK != 1f) {
                                        // 与 graphicsLayer 默认中心 TransformOrigin 对齐的焦点缩放
                                        offset = ZoomMath.zoomAround(
                                            offset, centroid, effectiveK, viewportOrigin(viewport),
                                        )
                                    }
                                    scale = newScale
                                    offset = ZoomMath.resetOffsetIfScaleOne(scale, offset)
                                    offset = ZoomMath.clampOffset(offset, viewport, imageSize, scale)
                                    onScaleChange(scale)
                                }
                                lastDist = dist
                                pressed.forEach { it.consume() }
                            }
                            pressed.size == 1 -> {
                                val change = pressed[0]
                                // scale>1（或曾双指）时单指拖动图片；scale==1 时不消费，交给 Pager
                                if (multiTouch || scale > 1f) {
                                    if (change.positionChanged()) {
                                        offset += change.positionChange()
                                        offset = ZoomMath.clampOffset(offset, viewport, imageSize, scale)
                                        change.consume()
                                    }
                                }
                            }
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
            .pointerInput(uri) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { pos ->
                        val target = ZoomMath.doubleTapTarget(scale)
                        if (target > 1f) {
                            offset = ZoomMath.zoomAround(
                                offset, pos, target / scale, viewportOrigin(viewport),
                            )
                        } else {
                            offset = Offset.Zero
                        }
                        scale = target
                        // 双击放大后必须立即 clamp，边缘双击不能跳出有效边界
                        offset = ZoomMath.resetOffsetIfScaleOne(scale, offset)
                        offset = ZoomMath.clampOffset(offset, viewport, imageSize, scale)
                        onScaleChange(scale)
                    },
                )
            },
    ) {
        key(retryKey) {
            SubcomposeAsyncImage(
                model = uri,
                contentDescription = "图片",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scaleRef.value
                        scaleY = scaleRef.value
                        translationX = offsetRef.value.x
                        translationY = offsetRef.value.y
                    },
                loading = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = MediaTextPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                },
                error = {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Text("图片加载失败", style = MaterialTheme.typography.bodyMedium, color = MediaTextSecondary)
                        TextButton(onClick = { retryKey++ }) {
                            Icon(Icons.Default.Refresh, null, tint = MediaTextPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("重试", color = MediaTextPrimary)
                        }
                    }
                },
            )
        }
    }
}
