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
        ReleaseNote(
            versionCode = 33,
            versionName = "2.0.0-alpha26",
            title = "MediaReview 2.0 Alpha 26",
            highlights = listOf(
                "媒体库支持全选和清空草稿，点应用后保存",
                "相册按进入页面和可见位置加载，减少后台封面请求",
                "生图审核扩大图片区域，信息不再遮挡图片",
                "图片显示成功后才能审核，评分1至5完整显示",
            ),
        ),
        ReleaseNote(
            versionCode = 34,
            versionName = "2.0.0-alpha27",
            title = "MediaReview 2.0 Alpha 27",
            highlights = listOf(
                "视频刷新跟随实际加载，失败时保留已有内容",
                "刷新批阅目录时保留勾选，目录失效会提示重新选择",
                "同步生图时仍可继续审核，旧回包不会覆盖新审核结果",
                "解除生图配对前增加确认，审核和评分提交中避免误断开",
            ),
        ),
        ReleaseNote(
            versionCode = 35,
            versionName = "2.0.0-alpha28",
            title = "MediaReview 2.0 Alpha 28",
            highlights = listOf(
                "生图支持点击查看原图，双指缩放和双击复位",
                "同步中断或图片移除时，新图数量与浏览位置更准确",
                "删除受媒体库选择限制时，提示检查选择并刷新",
            ),
        ),
        ReleaseNote(
            versionCode = 36,
            versionName = "2.0.0-alpha29",
            title = "MediaReview 2.0 Alpha 29",
            highlights = listOf(
                "暂停时从中央播放图标滑动也可调整进度",
                "中央播放图标不再阻挡画面手势",
                "保留单击继续播放与无障碍播放操作",
            ),
        ),
        ReleaseNote(
            versionCode = 37,
            versionName = "2.0.0-alpha30",
            title = "MediaReview 2.0 Alpha 30",
            highlights = listOf(
                "首页下拉刷新后，新接入的媒体库也会出现在文件夹中",
                "目录刷新独立进行，不等待目录加载才显示视频",
                "刷新目录时保留原有列表与媒体库选择",
            ),
        ),
        ReleaseNote(
            versionCode = 38,
            versionName = "2.0.0-alpha31",
            title = "MediaReview 2.0 Alpha 31",
            highlights = listOf(
                "生图审核默认进入图库，点选图片后开始审核",
                "保存上次浏览图片，提供继续上次入口",
                "图库按需加载缩略图，返回保留滚动位置",
            ),
        ),
        ReleaseNote(
            versionCode = 39,
            versionName = "2.0.0-alpha32",
            title = "MediaReview 2.0 Alpha 32",
            highlights = listOf(
                "短时间返回相册复用已加载照片和滚动位置，减少重复查询",
                "相册后台校验结束后重新检查是否需要继续加载",
                "生图图库的位置恢复失败提示保留至主动恢复",
            ),
        ),
        ReleaseNote(
            versionCode = 40,
            versionName = "2.0.0-alpha33",
            title = "MediaReview 2.0 Alpha 33",
            highlights = listOf(
                "生图审核序号保持单行，大字体下不再逐字竖排",
                "空间不足时审核工具栏自动换行，减轻对图片区域的挤压",
                "普通字号保持紧凑布局，保留翻页与图片信息入口",
            ),
        ),
        ReleaseNote(
            versionCode = 41,
            versionName = "2.0.0-alpha34",
            title = "MediaReview 2.0 Alpha 34",
            highlights = listOf(
                "修复批阅播放异常后错误提示可能丢失的问题",
                "当前视频报错后可继续进入已有的备用播放或重试流程",
                "继续拦截上一条视频的迟到错误，避免影响正在批阅的视频",
            ),
        ),
        ReleaseNote(
            versionCode = 42,
            versionName = "2.0.0-alpha35",
            title = "MediaReview 2.0 Alpha 35",
            highlights = listOf(
                "电脑端更新后，生图图库默认显示最新图片，可切换最早在前",
                "支持按今天、昨天、近7天或自选日期筛选生图",
                "继续上次审核时保留原日期范围和图片顺序",
                "新生成图片只显示提醒，点击查看最新后再加载",
                "完善视频播放失败提示，区分没有备用方式与备用也失败",
            ),
        ),
        ReleaseNote(
            versionCode = 43,
            versionName = "2.0.0-alpha36",
            title = "MediaReview 2.0 Alpha 36",
            highlights = listOf(
                "电脑端更新后，可按9张整页审核生图，勾选保留，其余已展示待审核图淘汰",
                "同一提示词版本支持6张或10张对比，批量审核与版本评分分别确认",
                "生图图库支持审核状态、分类、收藏、关键词和当前提示词文字标签筛选",
                "继续上次审核可按原范围向前和向后翻页，新图不会打断当前浏览",
                "评分面板可核对最新记录，保留尚未提交的打分和备注",
            ),
        ),
        ReleaseNote(
            versionCode = 44,
            versionName = "2.0.0-alpha37",
            title = "MediaReview 2.0 Alpha 37",
            highlights = listOf(
                "生图图库精简工具栏，筛选和说明按需展开",
                "看图可直接双指缩放，放大后自动收起信息和操作",
                "适屏时左右滑动切图，放大后拖动图片，缩回恢复信息",
                "后台同步保持看图布局与当前位置，减少文字跳动",
                "更新配套电脑端后可按最新生成排序和筛选日期",
            ),
        ),
        ReleaseNote(
            versionCode = 45,
            versionName = "2.0.0-alpha38",
            title = "MediaReview 2.0 Alpha 38",
            highlights = listOf(
                "修正适屏左右滑动被点击手势拦截的问题",
                "保留直接双指缩放、放大拖图和信息自动显隐",
                "配套电脑端已接通最新与最早排序入口",
            ),
        ),
        ReleaseNote(
            versionCode = 46,
            versionName = "2.0.0-alpha39",
            title = "MediaReview 2.0 Alpha 39",
            highlights = listOf(
                "补齐只看未审核与隐藏已淘汰筛选（需配套电脑端）",
                "记住审核状态筛选，空列表也保留选择",
                "修正筛选内审核、撤销和列表恢复",
            ),
        ),
        ReleaseNote(
            versionCode = 47,
            versionName = "2.0.0-alpha40",
            title = "MediaReview 2.0 Alpha 40",
            highlights = listOf(
                "统一深色媒体界面、导航与按钮层级",
                "图库后台同步保持工具栏稳定，刷新进度局部显示",
                "生图切换加入方向动效，优化看图操作布局",
            ),
        ),
        ReleaseNote(
            versionCode = 48,
            versionName = "2.0.0-alpha41",
            title = "MediaReview 2.0 Alpha 41",
            highlights = listOf(
                "精简视频批阅布局，媒体画面优先",
                "生图移除底部翻页按钮，保留左右滑动切换",
                "主要页面改用状态图标与短文案",
            ),
        ),
        ReleaseNote(
            versionCode = 49,
            versionName = "2.0.0-alpha42",
            title = "MediaReview 2.0 Alpha 42",
            highlights = listOf(
                "审核生图后保留画面，等待列表切换",
                "提前预取下一张图片的预览",
                "列表读取失败时保留仍可看的图片，方便重试",
            ),
        ),
        ReleaseNote(
            versionCode = 50,
            versionName = "2.0.0-alpha43",
            title = "MediaReview 2.0 Alpha 43",
            highlights = listOf(
                "刷新时在原位置显示单个进度图标",
                "生图库网格按屏幕宽度调整列数",
                "精简看图操作区与空状态提示",
            ),
        ),
        ReleaseNote(
            versionCode = 51,
            versionName = "2.0.0-alpha44",
            title = "MediaReview 2.0 Alpha 44",
            highlights = listOf(
                "保留或淘汰后更快切到下一张，临近页尾提前加载",
                "查看新图时保留画面和当前筛选",
                "看图底栏可直接撤销上次审核，并返回刚才那张",
                "大字号下保留审核按钮的完整文字",
            ),
        ),
        ReleaseNote(
            versionCode = 52,
            versionName = "2.0.0-alpha45",
            title = "MediaReview 2.0 Alpha 45",
            highlights = listOf(
                "打开目录时只查询所属媒体库，减少加载超时",
                "批阅入口等待进行中的服务器连接检测",
                "视频播放失败时可直接跳到下一条",
            ),
        ),
        ReleaseNote(
            versionCode = 53,
            versionName = "2.0.0-alpha46",
            title = "MediaReview 2.0 Alpha 46",
            highlights = listOf(
                "视频批阅支持按住临时两倍速，松手恢复",
                "审核保存结果明确显示，失败后可回原视频重试",
                "目录顶部保留筛选和排序，子目录按需展开",
                "生图单图审核优先预览，放大时按需加载原图",
            ),
        ),
        ReleaseNote(
            versionCode = 54,
            versionName = "2.0.0-alpha47",
            title = "MediaReview 2.0 Alpha 47",
            highlights = listOf(
                "修复旧版服务端媒体日期无法显示，相册可正常按日期分组",
                "单次读取超时后，仍可访问其他在线功能",
                "电脑端支持实时生图进度时，图库和单图页都可查看",
            ),
        ),
        ReleaseNote(
            versionCode = 55,
            versionName = "2.0.0-alpha48",
            title = "MediaReview 2.0 Alpha 48",
            highlights = listOf(
                "连接旧版电脑端时，校验配对无需加载媒体列表",
                "大图库加载较慢时，可先完成连接",
                "配对失效后，仍可从连接入口重新配对",
            ),
        ),
        ReleaseNote(
            versionCode = 56,
            versionName = "2.0.0-alpha49",
            title = "MediaReview 2.0 Alpha 49",
            highlights = listOf(
                "生图审核只显示电脑端真实支持的能力，移除未上线的进度入口",
                "配套电脑端升级到 0.14.0，健康检查可核对构建版本",
                "图库与批量审核契约已和电脑端实现对齐",
            ),
        ),
        ReleaseNote(
            versionCode = 57,
            versionName = "2.0.0-alpha50",
            title = "MediaReview 2.0 Alpha 50",
            highlights = listOf(
                "审图页顶部新增电脑端生图进度：第 X / Y 张、剩余 N 段，带呼吸提示动画",
                "进度仅只读显示，不提供生成控制；老电脑端无该能力时自动隐藏",
                "配对与图库流程保持 Alpha 49 的只读审核边界",
            ),
        ),
        ReleaseNote(
            versionCode = 58,
            versionName = "2.0.0-alpha51",
            title = "MediaReview 2.0 Alpha 51",
            highlights = listOf(
                "保留、淘汰与收藏加入轻触反馈，收藏图标切换时轻微弹跳",
                "新图提醒与主页面切换加入淡入缩放动画，过渡更连贯",
                "图库到审图的浏览动效保持原方向感，操作结果更直观",
            ),
        ),
        ReleaseNote(
            versionCode = 59,
            versionName = "2.0.0-alpha52",
            title = "MediaReview 2.0 Alpha 52",
            highlights = listOf(
                "审图页进度新增“已提交 / 生成中 / 已暂停”状态，排队任务数直接可见",
                "两张图之间显示“准备下一张”，不再误显示为没有任务",
                "旧电脑端缺少状态字段时保持原有计数展示，不影响使用",
            ),
        ),
        ReleaseNote(
            versionCode = 60,
            versionName = "2.0.0-alpha53",
            title = "MediaReview 2.0 Alpha 53",
            highlights = listOf(
                "生图进度条按“排队 / 生成 / 暂停”变色，并显示本段已完成比例",
                "缩略图条和审核提示改为平滑展开与收起",
                "进度文案切换加入淡入淡出，减少文字跳动",
            ),
        ),
        ReleaseNote(
            versionCode = 61,
            versionName = "2.0.0-alpha54",
            title = "MediaReview 2.0 Alpha 54",
            highlights = listOf(
                "电脑端 Bridge 恢复后“无法连接”提示自动消失，不再残留错误横幅",
                "下一次成功同步后自动清除网络错误，操作类提示保持不变",
                "连接恢复无需手动关闭提示或重开应用，进度展示直接回到正常状态",
            ),
        ),
        ReleaseNote(
            versionCode = 62,
            versionName = "2.0.0-alpha55",
            title = "MediaReview 2.0 Alpha 55",
            highlights = listOf(
                "电脑端生成中或暂停时，顶栏同时显示排队任务数",
                "多任务连续提交时可直接看到后续还有几张等待生成",
                "旧桥缺少排队字段时保持原有文案，不影响使用",
            ),
        ),
        ReleaseNote(
            versionCode = 63,
            versionName = "2.0.0-alpha56",
            title = "MediaReview 2.0 Alpha 56",
            highlights = listOf(
                "手动切到某张新图后，“新图 N”计数同步减少，不再长期残留",
                "新图计数只统计尚未查看的图片，逐张浏览也会自动更新",
                "仍可点击“新图 N”一次跳到最新图片",
            ),
        ),
        ReleaseNote(
            versionCode = 64,
            versionName = "2.0.0-alpha57",
            title = "MediaReview 2.0 Alpha 57",
            highlights = listOf(
                "生图库顶部也会显示电脑端生成进度，翻缩略图时无需进入单图",
                "有排队、生成或暂停任务时才出现，空闲时不占位",
                "进度条随任务状态自动淡入淡出",
            ),
        ),
        ReleaseNote(
            versionCode = 65,
            versionName = "2.0.0-alpha58",
            title = "MediaReview 2.0 Alpha 58",
            highlights = listOf(
                "单图审阅顶部显示生成时的提示词摘要，不必打开图片信息也能核对",
                "摘要只占一行，过长自动截断，空白提示词不占位",
                "点按摘要可直接打开图片信息",
            ),
        ),
        ReleaseNote(
            versionCode = 66,
            versionName = "2.0.0-alpha59",
            title = "MediaReview 2.0 Alpha 59",
            highlights = listOf(
                "提示词摘要右侧新增“复制”，一键复制生成时提示词",
                "复制后弹出轻提示，不打断图片浏览",
                "点按摘要正文仍可打开图片信息",
            ),
        ),
        ReleaseNote(
            versionCode = 67,
            versionName = "2.0.0-alpha60",
            title = "MediaReview 2.0 Alpha 60",
            highlights = listOf(
                "复制提示词后，按钮原位淡入“已复制”，2 秒后自动恢复",
                "复制成功带轻触反馈，不再弹出系统提示打断浏览",
                "点按摘要正文仍可打开图片信息",
            ),
        ),
        ReleaseNote(
            versionCode = 68,
            versionName = "2.0.0-alpha61",
            title = "MediaReview 2.0 Alpha 61",
            highlights = listOf(
                "单图审阅顶栏显示当前图片的审核状态（已保留 / 已淘汰 / 已归档）",
                "未审核图片不显示状态，避免顶栏常驻占位",
                "切换图片或撤销审核后状态同步更新",
            ),
        ),
        ReleaseNote(
            versionCode = 69,
            versionName = "2.0.0-alpha62",
            title = "MediaReview 2.0 Alpha 62",
            highlights = listOf(
                "生图进度显示当前这张的真实采样完成度，如“第 1 / 6 张 · 42%”",
                "进度条下方新增一条细进度，直观看到单张推进",
                "旧版电脑端不返回该字段时自动隐藏，不影响原有显示",
            ),
        ),
        ReleaseNote(
            versionCode = 70,
            versionName = "2.0.0-alpha63",
            title = "MediaReview 2.0 Alpha 63",
            highlights = listOf(
                "生图库支持下拉刷新，和顶部刷新按钮使用同一次增量同步",
                "“新图 N”提示带淡入缩放动画，数量变化时平滑切换",
                "筛选、状态与排序保持不变，刷新后仍停留在当前浏览位置",
            ),
        ),
        ReleaseNote(
            versionCode = 71,
            versionName = "2.0.0-alpha64",
            title = "MediaReview 2.0 Alpha 64",
            highlights = listOf(
                "9 图批量审核页顶部也显示电脑端生成进度，批审时不必返回图库查看",
                "进度与图库、单图审阅共用同一份只读快照，当前张百分比同步刷新",
                "电脑端空闲时自动隐藏，不占用批审空间",
            ),
        ),
        ReleaseNote(
            versionCode = 72,
            versionName = "2.0.0-alpha65",
            title = "MediaReview 2.0 Alpha 65",
            highlights = listOf(
                "单图沉浸浏览时顶部保留紧凑进度胶囊，隐藏控制层也能看到当前张百分比",
                "胶囊随生成进度淡入更新，暂停与排队状态一并提示",
                "控制层显示时自动让位给完整进度条，电脑端空闲时不出现",
            ),
        ),
        ReleaseNote(
            versionCode = 73,
            versionName = "2.0.0-alpha66",
            title = "MediaReview 2.0 Alpha 66",
            highlights = listOf(
                "单图审阅空闲时不再显示“暂无生成任务”，顶栏更干净",
                "沉浸进度胶囊在排队时显示“排队 N”，与完整进度条信息一致",
                "电脑端有任务时完整进度条与胶囊都会平滑出现",
            ),
        ),
        ReleaseNote(
            versionCode = 74,
            versionName = "2.0.0-alpha67",
            title = "MediaReview 2.0 Alpha 67",
            highlights = listOf(
                "生图库无结果时给出直接操作：筛选导致为空可一键“清除筛选”",
                "没有筛选时显示“刷新图库”，与下拉刷新共用同一次增量同步",
                "清除筛选后立即回到完整图库，无需逐项手动恢复",
            ),
        ),
        ReleaseNote(
            versionCode = 75,
            versionName = "2.0.0-alpha68",
            title = "MediaReview 2.0 Alpha 68",
            highlights = listOf(
                "筛选面板的搜索框支持键盘“搜索”键直接应用关键词与标签",
                "搜索框右侧新增搜索按钮，键盘弹出时也能直接点按",
                "应用后自动收起面板与键盘，结果立即可见；再次展开可继续调整",
            ),
        ),
        ReleaseNote(
            versionCode = 76,
            versionName = "2.0.0-alpha69",
            title = "MediaReview 2.0 Alpha 69",
            highlights = listOf(
                "生图库加载完成时，空结果与“清除筛选/刷新图库”按钮淡入出现",
                "同步中的加载提示与空结果之间平滑切换，不再突然跳变",
                "有图时直接显示网格，不增加额外等待",
            ),
        ),
        ReleaseNote(
            versionCode = 77,
            versionName = "2.0.0-alpha70",
            title = "MediaReview 2.0 Alpha 70",
            highlights = listOf(
                "“筛选已启用 / 清除”提示行改为展开淡入，不再突然挤出图库",
                "清除筛选时提示行平滑收起，“新图 N”仍单独动画",
                "筛选行与电脑端生成进度条使用一致的展开节奏",
            ),
        ),
        ReleaseNote(
            versionCode = 78,
            versionName = "2.0.0-alpha71",
            title = "MediaReview 2.0 Alpha 71",
            highlights = listOf(
                "搜索关键词与标签超过上限时自动截断，不再提交后才提示错误",
                "输入框提示与服务端校验共用同一上限（搜索 200 字 / 标签 100 字）",
                "其余筛选行为不变，应用搜索后仍立即收起面板",
            ),
        ),
        ReleaseNote(
            versionCode = 79,
            versionName = "2.0.0-alpha72",
            title = "MediaReview 2.0 Alpha 72",
            highlights = listOf(
                "自选日期时结束早于开始，“应用日期”直接禁用",
                "日期选择框内即时提示：未选结束日期或结束早于开始都会被说明，不会被筛选面板挡住",
                "合法区间仍可一键应用，筛选行为不变",
            ),
        ),
        ReleaseNote(
            versionCode = 80,
            versionName = "2.0.0-alpha73",
            title = "MediaReview 2.0 Alpha 73",
            highlights = listOf(
                "9 图批量审核没有可审图片时显示专门空状态，不再堆叠无效操作",
                "有后续页时直接“加载下一批”，否则一键“返回图库”",
                "空状态下全选、清空与提交按钮自动隐藏",
            ),
        ),
        ReleaseNote(
            versionCode = 81,
            versionName = "2.0.0-alpha74",
            title = "MediaReview 2.0 Alpha 74",
            highlights = listOf(
                "筛选面板把搜索区放到顶部，键盘弹出时输入框与搜索按钮保持可见",
                "小屏 / 大字体下不再出现“正在输入却看不到内容”的情况",
                "常规尺寸下的筛选行为不变",
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
