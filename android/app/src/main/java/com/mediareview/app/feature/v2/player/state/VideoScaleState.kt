package com.mediareview.app.feature.v2.player.state

/**
 * 画面比例状态：适应 / 裁剪 / 填充。
 * 映射到 PlayerView 的 AspectRatioFrameLayout RESIZE_MODE 常量
 * （FIT=0, FILL=3, ZOOM=4），不直接依赖 Media3 类型，纯逻辑可单测。
 */
class VideoScaleState {

    enum class ScaleMode(val label: String) {
        FIT("适应"),
        CROP("裁剪"),
        FILL("填充"),
        ;

        fun toResizeMode(): Int = when (this) {
            FIT -> 0 // RESIZE_MODE_FIT
            FILL -> 3 // RESIZE_MODE_FILL
            CROP -> 4 // RESIZE_MODE_ZOOM
        }
    }

    var mode = ScaleMode.FIT
        private set

    fun set(mode: ScaleMode) {
        this.mode = mode
    }

    /** 循环切换：适应 → 裁剪 → 填充 → 适应。 */
    fun cycle() {
        mode = ScaleMode.entries[(mode.ordinal + 1) % ScaleMode.entries.size]
    }
}
