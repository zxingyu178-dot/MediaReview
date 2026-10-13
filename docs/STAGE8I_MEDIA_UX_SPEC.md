项目：MediaReview 2.0
阶段：Stage 8I — Media UX Polish & Review Reliability

Base:
feature/mediareview-v2-stage8h-player-home-rebuild
4ce9a878a2f300cfbcd0bc8e96dfebadc42367b7

创建：
feature/mediareview-v2-stage8i-ux-polish

目标模式直接实施，不要只写计划。
普通技术细节自行决定，持续开发、测试、修复直到 Gate 完成。

==================================================
一、阶段目标
==================================================

解决用户 Alpha11 真机体验发现的 8 类问题：

1. 图片加载过慢
2. Full Player 缺待删除
3. Folder 分类不可用
4. Review 播完/Seek/上下滑体验
5. Delete Queue 大小和真实删除反馈
6. 全局使用手感
7. 横滑 Seek 灵敏度
8. Review Folder Scope

禁止本阶段增加与上述问题无关的新产品模块。

==================================================
二、图片加载管线重构
==================================================

Server 增加白名单 Thumbnail Profile。

禁止允许客户端传任意 width/quality 导致无限缓存变体。

至少：

GRID_SMALL
256×144 / q60

GRID_LARGE
480×270 / q65

ALBUM
320×427 / q65

ThumbnailCache key 必须包含完整 variant。

Android：

双列用 GRID_SMALL。
单列用 GRID_LARGE。
相册封面用 ALBUM。

媒体墙、相册绝对禁止访问 original URL。

增加 HTTP 合同测试：

列表浏览 100 张图片
→ /original 请求数量必须为 0。

==================================================
三、Viewer 原图预取
==================================================

当前：
只有当前 N 加载 original。

改成：

N：
最高优先级 original

N-1 / N+1：
后台预取 original source

N±2：
只保留低清 Preview

并发必须有界。

优先级：

Current
>
Next/Previous

快速连续翻页：

取消已经失去意义的远端预取。

禁止一次解码多个超大原图造成 OOM。

要求：

低清 Preview 立即显示；
Original Ready 后无感替换。

==================================================
四、图片加载 UX
==================================================

已有缩略图时：

禁止中央 Loading Spinner 遮住图片。

保持缩略图，
后台变清晰。

真正第一次没有任何内容：

使用和图片比例一致的 Skeleton。

禁止：

黑屏
→ Loading
→ 图片

优先：

低清
→ 高清。

==================================================
五、Full Player 待删除
==================================================

Full Player 控制层增加明确：

待删除

操作。

不要叫“永久删除”。

建议放在顶部控制区：

垃圾桶图标 + 更多

点击：

Server confirm
→ 已加入待删除

再次点击：
→ 撤销待删除

只有 Server 成功才切 UI。

增加 Snackbar：

已加入待删除
[撤销]

永久删除仍然只能：

整理 → 待删除 → 两阶段确认。

播放器绝不能直接 os.remove。

把 ViewerPendingDeleteController 泛化为：

PendingDeleteController

Viewer / Full Player 共用。

==================================================
六、Folder 分类故障诊断
==================================================

第一件事增加明确 Runtime Diagnosis。

显示：

Server Version
API Contract
Capabilities

如果缺：

folder_tree_v1

分类区域不能静默坏掉。

显示：

电脑端版本过旧
层级分类需要 MediaReview Server 1.3.0+

播放/图片/Review 继续可用。

==================================================
七、FolderBrowser 修复
==================================================

在具备 folder_tree_v1 的 fixture 下真正测试：

首页一级分类
→ 点击
→ FolderBrowser
→ 子文件夹
→ 子文件夹
→ 媒体

网络请求必须真实：

folder_id=ft_xxx

并验证面包屑 Back。

父目录只有子文件夹、没有直接媒体：

不能显示成“空文件夹”。

子文件夹卡片必须可点。

建议 Folder 页面：

顶部 = 子文件夹
下方 = 媒体

默认媒体范围允许：

包含子文件夹

用户可切：
仅当前文件夹 / 包含子文件夹

默认：
包含子文件夹

==================================================
八、Folder 卡片手感
==================================================

文件夹卡片增加：

封面
文件夹名
总媒体数
子目录数

不要继续使用纯文本灰卡片。

利用 folder-tree 的 cover_url。

==================================================
九、Review 循环播放
==================================================

批阅模式采用：

当前视频 LOOP。

一个视频自然播放完成：

自动从 0 继续播放。

不显示 Full Player 的：

重播 / 下一条

终态界面。

优先使用 GSY/Exo：

setLooping(true)

必须确保 Direct 与 HLS 都不会进入永久 Completed 黑屏。

==================================================
十、Review Completed Seek
==================================================

即使发生 EOF/Completed race：

用户拖进度：

必须可以重新开始播放。

禁止：

seek 后黑屏。

增加真实 5~8 秒 fixture：

播放超过视频时长 1.5 倍。

断言：

仍在当前媒体播放，
position 已发生 wrap，
画面存在，
无 terminal Completed。

==================================================
十一、Review 上下滑重构
==================================================

审查并删除当前人工：

Channel<Float>
→ pagerState.scroll()
→ 每帧驱动 Pager

如果这正是当前实现。

目标：

VerticalPager 尽可能使用 Compose 原生 drag/fling/snap。

Gesture Layer：

只有确认横向主导后才消费事件。

纵向主导：

交给 Pager。

不要在纵向时人工逐像素驱动 Pager。

使用稳定：

PagerSnapDistance.atMost(1)

或当前 Compose 等价方案。

一次手势最多切一条。

==================================================
十二、Review 预加载
==================================================

当前页稳定以后：

预取：

N+1 Playback Endpoint
N-1 Playback Endpoint
N+1 Poster
N-1 Poster

禁止：

创建第二播放器
提前播放
抢占当前视频音频。

目标：

上下滑后尽量直接 Prepared。

==================================================
十三、横向 Seek 灵敏度
==================================================

彻底删除：

delta = screenFraction * duration

新共享算法：

maxSeek =
(durationMs * 0.02)
.coerceIn(30_000, 180_000)

deltaMs =
(horizontalDx / screenWidth) * maxSeek

Full Player / Review 共用。

例：

2h video:
整屏约 ±144s

而不是 ±2h。

Double Tap：

继续固定 ±10s。

Progress Bar：

继续绝对定位。

==================================================
十四、Seek 提示
==================================================

横滑显示：

+01:12
03:24 → 04:36

但保持轻量。

不要挡住画面中心主体。

结束手势：

只 seek 一次。

==================================================
十五、Delete Queue Size 修复
==================================================

当前 Jellyfin 请求已经包含：

MediaSources

但是 JFItem 没解析。

新增最小：

JFMediaSource:
id
size
run_time_ticks
container
path（仅服务端内部）

JFItem:
media_sources

size_bytes 规则：

top-level Size 有效
→ 使用

否则：
primary MediaSource.Size
→ 使用

仍未知：
如果服务端有可信本地 media_path，
媒体同步阶段可安全 stat 获取实际大小。

不要在 DeleteQueue 列表每次 N+1 stat 文件。

==================================================
十六、已有索引回填
==================================================

媒体 refresh 后：

已有 media_cache_index.size_bytes
必须得到更新。

对于已经 pending 且 size_bytes=0/null：

load/refresh 后允许从媒体索引补齐。

增加测试：

Jellyfin top-level Size missing
MediaSources[0].Size = 4GB

→ Media index = 4GB
→ DeleteQueue = 4GB。

==================================================
十七、真实删除 Receipt
==================================================

扩展 commit response，保持旧 outcome 字段兼容。

增加：

deleted_count
deleted_bytes
missing_count
failed_count

deleted_bytes：

只能统计 os.remove 成功的真实文件。

删除后检查文件路径已不存在，
才计 success。

missing：
不计释放空间。

failed：
不计释放空间。

==================================================
十八、删除结果 UI
==================================================

Result Sheet：

不要只显示：

成功删除 3

改为：

已从磁盘删除：3 个文件
实际释放：6.42 GB

文件原本已不存在：1
删除失败：0

如果失败：

清楚列失败项。

刷新 DeleteQueue 后：

成功项必须消失。

==================================================
十九、Review 文件夹选择
==================================================

新增：

ReviewScope

默认：
全部视频

支持：
选择 1 个或多个 FolderTree 节点

默认：
包含子文件夹 = true

==================================================
二十、创建批阅 Session
==================================================

Server Review Source filter 新增：

folder_ids: List<String>
include_descendants: Boolean

最多合理数量，例如 100。

服务端必须验证：

folder id 合法
属于当前 selected libraries。

利用现有 folder_tree.scope_condition。

多个 folder：

OR。

Review Session 创建以后：

Folder Scope 快照固定。

后续文件夹选择变化：

不得修改已有 Session。

==================================================
二十一、Review UI
==================================================

已有 Active Session：

默认继续恢复。

增加：

重新选择批阅范围

新建 Session 前显示 Scope Sheet：

全部视频
选择文件夹

支持多选。

使用现有 FolderTree UI/数据源，
不要另造第二套文件夹系统。

==================================================
二十二、Active Session 行为
==================================================

用户已有 Active Session 时修改 Scope：

必须先明确：

当前批阅尚未完成
开始新的批阅会话？

确认后才创建新 Session。

禁止暗中覆盖。

==================================================
二十三、Review 顶部显示范围
==================================================

例如：

批阅 · 全部视频

或：

批阅 · 欧美/科幻

多选：

批阅 · 科幻 +2

不要显示真实磁盘路径。

==================================================
二十四、整体手感优化
==================================================

统一原则：

已有内容永远优先继续显示。

后台刷新不要：
清空
→ Loading
→ 重新出现。

短操作动画：

约 120~180ms。

不要大量 Crossfade。

列表/图片固定 aspect ratio，
避免图片回来后 Layout Jump。

返回页面：
恢复原 anchor。

按钮点击：
立即有 pressed feedback。

Haptic 只保留：
2×锁定/解除
危险删除确认等少数重要动作。

==================================================
二十五、Loading 策略
==================================================

首页：
缓存优先 + SWR。

图库：
低清优先。

Viewer：
低清 → 原图。

Review：
Poster → First frame。

播放器：
Poster/last frame 优先，
超过约300ms再出现轻量 Loading。

禁止能展示旧内容时用整页 Spinner。

==================================================
二十六、成熟播放器设计基线
==================================================

保持：

左双击 -10s
右双击 +10s

左竖滑亮度
右竖滑音量

横滑 Seek
但使用 bounded adaptive sensitivity。

这些行为与 VLC / Next Player / mpv-android 的成熟交互方向一致。

不要重新发明完全不同手势体系。

==================================================
二十七、Server Capability
==================================================

Server Runtime 有实际新增。

Server：
1.3.0 → 1.4.0

API Contract：
继续 2

Health capabilities 增加：

thumbnail_profiles_v1
review_folder_filter_v1
delete_receipt_v1

保留：
folder_tree_v1

==================================================
二十八、旧 Server 兼容
==================================================

没有 review_folder_filter_v1：

Scope UI 必须禁用文件夹选择，
清楚提示升级 Server。

不能：

用户选了文件夹
但旧 Server 实际仍批阅全部视频。

Fail closed。

==================================================
二十九、Android 版本
==================================================

code18
alpha11

→

versionCode 19
2.0.0-alpha12

==================================================
三十、Alpha12 更新日志
==================================================

建议用户文案：

MediaReview 2.0 Alpha 12

• 大幅提升图片缩略图和图库浏览速度
• 图片查看器会预加载相邻原图
• 播放器新增待删除操作并优化拖动手感
• 优化批阅模式循环播放和上下滑动体验
• 批阅现在可以按文件夹选择范围
• 修复待删除视频大小显示并增强删除结果确认
• 修复部分层级分类无法正常使用的问题

按最终真实实现调整。

==================================================
三十一、关键自动测试
==================================================

必须新增：

IMAGE
- media wall 100图 original requests = 0
- low thumbnail dimensions/profile correct
- Viewer open → N original first
- N-1/N+1 original prefetch
- N±2 no original
- rapid swipe stale prefetch cancelled

PLAYER DELETE
- load pending status
- add server-confirmed
- undo server-confirmed
- network failure does not fake-toggle

REVIEW LOOP
- 5~8秒真实MP4
- 播放 >1.5 duration
- video continues
- position wraps
- no permanent black frame

REVIEW PAGER
- rapid vertical swipe 5 items
- exactly one settled item per gesture
- no old audio
- no wrong source
- horizontal seek never flips page

SEEK
- 5m / 30m / 2h / 6h mapping
- never dozens of minutes
- double tap remains 10 seconds

DELETE SIZE
- top-level Size absent
- MediaSources.Size available
- queue shows actual bytes

DELETE RECEIPT
- real temp file remove
- file truly absent after success
- deleted_bytes exact
- failed file not counted

REVIEW SCOPE
- one folder
- folder + descendants
- multiple folders
- illegal folder
- old server capability missing
- active session remains frozen

FOLDER
- actual folder click route
- child navigation
- direct media
- recursive media mode
- breadcrumb/back

==================================================
三十二、性能证据
==================================================

记录但不要制造不现实 SLA：

图片列表：
缩略图平均 payload
冷/热缓存首屏时间

Viewer：
preview_visible
current_original_ready
neighbor_prefetch_ready

Review：
settle_to_first_frame

Vertical Pager：
连续5次切换无错误/串音。

用数据对比 Alpha11 → Alpha12。

==================================================
三十三、最终门禁
==================================================

Server：
full pytest PASS
ruff PASS

Android：
JVM PASS
lint no new errors
full connectedDebugAndroidTest PASS
assembleRelease PASS

真实播放 regression：
Full Player PASS
Review PASS

最终只生成一次：

MediaReview_2.0.0-alpha12_stage8i.apk

以及：

MediaReview_Server_1.4.0_stage8i.zip

==================================================
三十四、禁止操作
==================================================

不要：

自动部署家庭生产Server
修改真实Jellyfin配置
永久删除用户真实媒体
清用户手机数据
绕过ControlHub生命周期

ControlHub：
继续 BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT。

==================================================
三十五、最终交接
==================================================

MediaReview_Stage8I_Handoff_<date>_<sha>.zip

只需要：

00_HANDOFF.md
01_ACCEPTANCE.md
02_TEST_REPORT.md
03_IMAGE_PIPELINE.md
04_PLAYER_DELETE_SEEK.md
05_REVIEW_LOOP_PAGER_SCOPE.md
06_FOLDER_BROWSER.md
07_DELETE_RECEIPT.md
08_PERFORMANCE.md
09_KNOWN_ISSUES.md
10_USER_VALIDATION.md
11_RELEASE_NOTES.md

完成后停止。

状态最高：

CODE_READY
READY_FOR_USER_VALIDATION

没有真实家庭LAN复测时禁止写：
REAL_LAN_PASS
PRODUCTION_READY。