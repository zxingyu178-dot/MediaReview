package com.mediareview.app.feature.v2

/** 应用运行模式：本阶段固定 DEMO（离线内置数据）；后续可切换 PRODUCTION（真实 Server）。 */
enum class AppMode { DEMO, PRODUCTION }

object V2AppMode {
    /** Stage 1 默认 DEMO 模式。 */
    val CURRENT: AppMode = AppMode.DEMO
}
