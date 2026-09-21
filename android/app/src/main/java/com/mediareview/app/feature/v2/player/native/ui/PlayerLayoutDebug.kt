package com.mediareview.app.feature.v2.player.native.ui

import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Debug-only 播放器三层（TOP / CENTER / BOTTOM）边界可视化工具。
 *
 * - [ENABLED] 默认关闭，仅开发排查布局交叠时临时改为 true；
 * - 调用方以 BuildConfig.DEBUG 守卫，release / demo 正式包恒不生效；
 * - 自动化的"三层不得交叠"断言见 androidTest PlayerLayoutSemanticTest，
 *   不依赖本可视化开关。
 */
object PlayerLayoutDebug {

    /** 开发开关：交付 APK 必须保持 false。 */
    const val ENABLED = false

    private val topColor = Color(0xFFFF5252)
    private val centerColor = Color(0xFF40C4FF)
    private val bottomColor = Color(0xFF69F0AE)

    fun boundsModifier(modifier: Modifier, layer: Layer = Layer.CENTER): Modifier =
        when (layer) {
            Layer.TOP -> modifier.border(2.dp, topColor)
            Layer.CENTER -> modifier.border(2.dp, centerColor)
            Layer.BOTTOM -> modifier.border(2.dp, bottomColor)
        }

    enum class Layer { TOP, CENTER, BOTTOM }
}
