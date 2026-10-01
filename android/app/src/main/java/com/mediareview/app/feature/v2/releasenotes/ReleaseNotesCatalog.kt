package com.mediareview.app.feature.v2.releasenotes

/**
 * 版本更新日志目录（Stage 8C.1 §33~§45）。
 *
 * 硬规则（§30/§47）：
 * - 每次产品版本更新必须同步更新 versionCode / versionName（见 docs/VERSION_POLICY.md），
 *   并在本目录中补充对应 [ReleaseNote]；
 * - `CurrentVersionHasReleaseNotesTest` 会强制校验"当前版本的更新日志必须存在"，
 *   改了版本号却忘记写更新日志会直接测试失败；
 * - 多版本跳跃（如 8 → 11）只展示当前版本的更新日志（§45），不逐版本连续弹窗。
 */
object ReleaseNotesCatalog {

    val notes: List<ReleaseNote> = listOf(
        ReleaseNote(
            versionCode = 8,
            versionName = "2.0.0-alpha1",
            title = "MediaReview 2.0 Alpha 1",
            highlights = listOf(
                "全新的批阅体验：首页、批阅、收藏、整理四大板块",
                "支持批阅进度保存与断点恢复",
                "支持收藏内容集中查看",
            ),
        ),
        ReleaseNote(
            versionCode = 9,
            versionName = "2.0.0-alpha2",
            title = "MediaReview 2.0 Alpha 2",
            highlights = listOf(
                "整理中心全面接入真实服务器数据",
                "新增待删除管理与安全的永久删除确认",
                "新增重复媒体扫描与文件对比",
                "新增媒体库选择管理",
                "优化批阅恢复与大媒体库稳定性",
            ),
        ),
    )

    /** 查询某个 versionCode 的更新日志；不存在时返回 null（Release 不崩溃、不展示）。 */
    fun forVersionCode(versionCode: Int): ReleaseNote? =
        notes.firstOrNull { it.versionCode == versionCode }
}