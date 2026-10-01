package com.mediareview.app.feature.v2.releasenotes

/**
 * 单个产品版本的更新日志（Stage 8C.1 §35/§36）。
 *
 * 面向**真实用户**：只描述用户能感知的变化，禁止出现 Stage 编号、Repository、
 * DTO、Hilt 等开发内部词（§37）。
 */
data class ReleaseNote(
    val versionCode: Int,
    val versionName: String,
    val title: String,
    val highlights: List<String>,
)