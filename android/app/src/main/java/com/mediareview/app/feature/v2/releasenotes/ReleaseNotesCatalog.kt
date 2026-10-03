package com.mediareview.app.feature.v2.releasenotes

/**
 * 版本更新日志目录（Stage 8C.1 §33~§45；Stage 8C.2 §23~§29）。
 *
 * 硬规则（VERSION_POLICY + §34）：
 * - 每次产品版本更新必须同步更新 versionCode / versionName（见 docs/VERSION_POLICY.md），
 *   并在本目录中补充对应 [ReleaseNote]；
 * - `ReleaseNotesContractTest` 强制校验：当前版本必须有更新日志、
 *   versionCode / versionName 唯一且 versionCode 严格递增 —— 忘记写更新日志直接测试失败；
 * - 跨版本升级（§24~§26）：自动弹窗展示 `(lastSeen, current]` 范围内**所有**未读版本的
 *   更新日志，但始终**只弹一个 Sheet**，绝不逐版本连续弹窗；
 * - 首次安装（lastSeen=null）只展示当前版本（§27），不倾倒历史全部日志。
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
        ReleaseNote(
            versionCode = 10,
            versionName = "2.0.0-alpha3",
            title = "MediaReview 2.0 Alpha 3",
            highlights = listOf(
                "优化重复媒体扫描：重新扫描不再丢失人工保留的选择",
                "修复媒体库变化期间重复扫描结果不一致的问题",
                "支持跨版本更新说明：升级后一次看到所有未读版本的更新内容",
                "提升整理中心数据稳定性",
            ),
        ),
        ReleaseNote(
            versionCode = 11,
            versionName = "2.0.0-alpha4",
            title = "MediaReview 2.0 Alpha 4",
            highlights = listOf(
                "完成主要功能模块整合，整体使用流程更加统一",
                "优化播放器、图片查看和批阅之间的页面衔接",
                "优化整理中心和重复媒体管理稳定性：重复分组身份更稳定，重新扫描不再丢失人工保留的选择",
                "清理旧版功能路径，降低异常和状态冲突",
                "提升 Demo / Server 数据源切换可靠性",
            ),
        ),
        ReleaseNote(
            versionCode = 12,
            versionName = "2.0.0-alpha5",
            title = "MediaReview 2.0 Alpha 5",
            highlights = listOf(
                "增加手机与电脑端版本兼容检查",
                "修复深色界面顶部状态图标显示问题",
                "优化重复媒体升级后的保留选择迁移",
                "提升真实服务器升级与连接稳定性",
            ),
        ),
    )

    /** 查询某个 versionCode 的更新日志；不存在时返回 null（Release 不崩溃、不展示）。 */
    fun forVersionCode(versionCode: Int): ReleaseNote? =
        notes.firstOrNull { it.versionCode == versionCode }

    /**
     * 自动升级弹窗应展示的更新日志（§28）：`(lastSeenVersionCode, currentVersionCode]`。
     *
     * - `lastSeenVersionCode == null`（首次安装，§27）→ 只返回当前版本；
     * - 其他情况 → 返回该区间内**所有**已登记版本，按 versionCode 升序；
     * - `lastSeen >= current`（同版本再次启动 / 异常回退）→ 返回空列表（不展示）；
     * - 当前版本缺失更新日志 → 返回空列表（§46 不崩溃、不展示）。
     */
    fun notesAfter(lastSeenVersionCode: Int?, currentVersionCode: Int): List<ReleaseNote> {
        if (forVersionCode(currentVersionCode) == null) return emptyList()
        val lowerBound = lastSeenVersionCode ?: (currentVersionCode - 1)
        return notes
            .filter { it.versionCode > lowerBound && it.versionCode <= currentVersionCode }
            .sortedBy { it.versionCode }
    }
}