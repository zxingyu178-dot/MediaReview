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
        ReleaseNote(
            versionCode = 13,
            versionName = "2.0.0-alpha6",
            title = "MediaReview 2.0 Alpha 6",
            highlights = listOf(
                "修复连接旧版电脑端时仍可能继续加载数据的问题",
                "优化服务器启动与切换时的兼容检查",
                "提升服务器升级和失败回滚可靠性",
                "完善手机与电脑端版本状态显示",
            ),
        ),
        ReleaseNote(
            versionCode = 14,
            versionName = "2.0.0-alpha7",
            title = "MediaReview 2.0 Alpha 7",
            highlights = listOf(
                "优化连接电脑端后的视频在线播放链路",
                "增加手机到 Jellyfin 的播放连接检测",
                "修复播放凭据失效后可能无法恢复的问题",
                "完善电脑端与手机端播放地址配置",
            ),
        ),
        ReleaseNote(
            versionCode = 15,
            versionName = "2.0.0-alpha8",
            title = "MediaReview 2.0 Alpha 8",
            highlights = listOf(
                "提供新版手机安装包与电脑端升级包",
                "增加手机到 Jellyfin 的播放链路检测",
                "完善电脑端与手机端播放地址配置说明",
                "加强安装包版本、签名与升级文件完整性校验",
            ),
        ),
        ReleaseNote(
            versionCode = 16,
            versionName = "2.0.0-alpha9",
            title = "MediaReview 2.0 Alpha 9",
            highlights = listOf(
                "视频封面长按预览正式支持服务器媒体",
                "图片查看器的待删除操作现已同步到整理中心",
                "优化大相册连续翻页体验",
                "修复网络异常时相册被误显示为空的问题",
            ),
        ),
        ReleaseNote(
            versionCode = 17,
            versionName = "2.0.0-alpha10",
            title = "MediaReview 2.0 Alpha 10",
            highlights = listOf(
                "搜索结果支持连续加载更多内容",
                "搜索和首页状态现在互不干扰",
                "优化从收藏进入播放器和图片查看器后的连续浏览",
                "清理无效页面路径并提升浏览稳定性",
            ),
        ),
        ReleaseNote(
            versionCode = 18,
            versionName = "2.0.0-alpha11",
            title = "MediaReview 2.0 Alpha 11",
            highlights = listOf(
                "首页支持按媒体库与文件夹逐级浏览",
                "首页支持单列、双列切换并保留浏览位置",
                "相册和分类再次打开时优先显示缓存内容",
                "封面按需加载，首页不再长按生成雪碧图预览",
                "全屏播放器支持拖动进度与双击快进快退",
                "长按临时二倍速，上滑可锁定并恢复原倍速",
                "批阅播放器统一进度与倍速手势，区分横向操作和纵向翻页",
            ),
        ),
        ReleaseNote(
            versionCode = 19,
            versionName = "2.0.0-alpha12",
            title = "MediaReview 2.0 Alpha 12",
            highlights = listOf(
                "提升图片缩略图和图库浏览速度",
                "图片查看器会预加载相邻原图",
                "播放器新增待删除操作并优化拖动手感",
                "优化批阅模式循环播放和上下滑动体验",
                "批阅现在可以按文件夹选择范围",
                "修复待删除视频大小显示并增强删除结果确认",
                "修复部分层级分类无法正常使用的问题",
            ),
        ),
        ReleaseNote(
            versionCode = 20,
            versionName = "2.0.0-alpha13",
            title = "MediaReview 2.0 Alpha 13",
            highlights = listOf(
                "图片加载失败时显示具体状态和排查编号，重试会重新请求预览",
                "待删除文件大小未知时明确提示，避免误显示预计释放零空间",
                "增强删除请求防重处理，结果未确认时提示先核对队列",
            ),
        ),
        ReleaseNote(
            versionCode = 21,
            versionName = "2.0.0-alpha14",
            title = "MediaReview 2.0 Alpha 14",
            highlights = listOf(
                "接入本地只读图片与视频来源；在电脑管理后台登记目录",
                "媒体墙可按来源筛选，图片、播放与批阅沿用现有流程",
                "本地视频支持拖动续读和进度保存，本地永久删除已禁用",
                "本地来源需要电脑端 Server 1.5.0，手机真机验收仍待完成",
            ),
        ),
        ReleaseNote(
            versionCode = 22,
            versionName = "2.0.0-alpha15",
            title = "MediaReview 2.0 Alpha 15",
            highlights = listOf(
                "修正媒体库管理和重复检测页顶部遮挡",
                "目录诊断默认收起，空页面提供筛选与同步检查提示",
                "区分待删除内容与失败历史，未扫描不再显示未发现重复",
                "改善筛选朗读状态和图片大小未知提示",
                "电脑端更新至 Server 1.5.2 并刷新索引后，可恢复重叠媒体库的目录内容",
            ),
        ),
        ReleaseNote(
            versionCode = 23,
            versionName = "2.0.0-alpha16",
            title = "MediaReview 2.0 Alpha 16",
            highlights = listOf(
                "本地来源支持先加入待删除队列、再二次确认的两阶段安全删除",
                "Windows 仅使用 POSIX 安全删除语义，不支持时返回 delete_not_supported 且文件保持不变",
                "只有电脑端明确返回 can_delete=true 的来源才允许执行删除",
                "本版本尚未完成真实手机与家庭局域网验收",
            ),
        ),
        ReleaseNote(
            versionCode = 24,
            versionName = "2.0.0-alpha17",
            title = "MediaReview 2.0 Alpha 17",
            highlights = listOf(
                "首页收敛顶栏，搜索改为全屏，并加入分类标签、无边框大图网格和下拉刷新",
                "目录页支持折叠顶栏与横滑面包屑，包含子文件夹选项移入菜单",
                "相册按日期分节；查看器支持沉浸大图、横滑翻页、双击或双指缩放、下滑关闭和信息层",
                "批阅采用极简顶栏与进度、底部五项动作，支持手势切换、保留或标记待删除、撤销和快进一屏",
                "本版本仍是 Alpha，尚未完成真实手机与家庭局域网验收",
            ),
        ),
        ReleaseNote(
            versionCode = 25,
            versionName = "2.0.0-alpha18",
            title = "MediaReview 2.0 Alpha 18",
            highlights = listOf(
                "图片墙、相册和图片查看共享缩略图缓存，减少重复加载",
                "快速滚动时取消过期预取，优先加载当前区域",
                "改善切换服务器或重新配对时的图片缓存隔离",
                "本版本仍是 Alpha，加载效果待真实手机与家庭局域网验证",
            ),
        ),
        ReleaseNote(
            versionCode = 26,
            versionName = "2.0.0-alpha19",
            title = "MediaReview 2.0 Alpha 19",
            highlights = listOf(
                "批阅改为上下滑切换视频，单击暂停或继续",
                "取消滑动保留和待删除，保留明确操作按钮",
                "取消双击跳进度和长按快捷操作，进度条仍可左右拖动",
                "批阅播放失败时显示错误与重试，并可重新选择批阅范围",
            ),
        ),
        ReleaseNote(
            versionCode = 27,
            versionName = "2.0.0-alpha20",
            title = "MediaReview 2.0 Alpha 20",
            highlights = listOf(
                "文件夹默认只显示当前目录媒体，保留直接存放的图片和视频",
                "文件夹新增全部、视频、图片快捷筛选和独立排序",
                "支持按文件名、最近添加、时长、大小正倒序浏览目录媒体",
                "可选择包含子文件夹媒体，目录总数与当前目录数量明确区分",
            ),
        ),
        ReleaseNote(
            versionCode = 28,
            versionName = "2.0.0-alpha21",
            title = "MediaReview 2.0 Alpha 21",
            highlights = listOf(
                "首页仅显示视频，照片集中从相册进入",
                "批阅支持画面左右滑动快进或倒退，上下滑动切换",
                "改善进度条松手后的显示，避免旧位置短暂回跳",
                "文件夹批阅支持直接勾选，移除上一项和下一项按钮",
                "网络短暂断开后有界重试连接，配对信息保留",
                "正式安装包移除内置演示图片和视频",
            ),
        ),
        ReleaseNote(
            versionCode = 29,
            versionName = "2.0.0-alpha22",
            title = "MediaReview 2.0 Alpha 22",
            highlights = listOf(
                "配合新版电脑端，删除超时后可继续核对原批次结果，不自动重复删除",
                "明确区分删除完成与结果未确认，重新打开待删除页可继续核对",
                "优化相册加载提示、长目录名称和底部操作区",
                "新增独立生图审核入口与连接设置，图片和评分待生图服务接口接入",
            ),
        ),
        ReleaseNote(
            versionCode = 30,
            versionName = "2.0.0-alpha23",
            title = "MediaReview 2.0 Alpha 23",
            highlights = listOf(
                "生图审核支持独立配对、持续发现新图并追加到队尾",
                "可查看生成提示词和图片信息，保留、驳回与收藏分别操作",
                "提示词按生成版本评分，多张图片共享评分与历史记录",
                "评分请求中断后可核对回执，避免重复记录",
            ),
        ),
        ReleaseNote(
            versionCode = 31,
            versionName = "2.0.0-alpha24",
            title = "MediaReview 2.0 Alpha 24",
            highlights = listOf(
                "生图审核预取下一张预览，切图和退后台时取消过期请求",
                "生图图片缓存按配对身份隔离，并限制资源占用",
                "空闲同步自动降频，回到前台立即同步",
                "图片加载失败时可直接重试",
            ),
        ),
        ReleaseNote(
            versionCode = 32,
            versionName = "2.0.0-alpha25",
            title = "MediaReview 2.0 Alpha 25",
            highlights = listOf(
                "批阅稳定播放后提前缓冲下一条兼容的视频",
                "预缓冲限制内存和请求时长，切页、拖动及退后台取消",
                "缓存按配对身份隔离，播放前核对权限与文件版本",
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
