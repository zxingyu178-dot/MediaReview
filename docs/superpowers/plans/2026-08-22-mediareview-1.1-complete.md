# 家庭媒体管家 1.1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` or `executing-plans` to implement this plan task-by-task. Every production behavior change follows test-driven development, and every task ends with independent review.

**Goal:** 将现有 MediaReview 升级为稳定、可部署、可回滚的局域网媒体浏览与整理工具 1.1.0。

**Architecture:** Android 通过 MediaReview Server 完成控制、索引、批阅与整理；视频流由 Android 直连 Jellyfin，Direct Play 失败后仅回退一次 Jellyfin HLS。Server 使用 FastAPI、SQLite、FFmpeg 和后台任务，本地索引是普通请求的唯一列表数据源。

**Tech Stack:** Python 3.12、FastAPI、SQLAlchemy 2、Alembic、SQLite、pytest、Kotlin、Jetpack Compose、Material 3、Media3、Retrofit/OkHttp、DataStore、Hilt、Coil、Gradle。

## Global Constraints

- 局域网单用户；不增加公网、云同步或多人账户。
- 禁止 FastAPI 转发完整视频流。
- Android 用户可见文案全部为中文。
- 采用已批准的深色专业视觉：`#0B1118` 背景、`#141D27` 卡片、`#47D7E8` 强调、`#F4F7FA` 主文字。
- 电脑端只实现运维控制台，不复制完整媒体浏览体验。
- 现有配置、数据库、配对、收藏、待删除和批阅状态必须无损升级。
- 每一阶段先验证、再提交、再生成验收材料；没有真实手机验收只能标记 RC。

---

### Task 0: 安全基线与隔离开发

**Files:** `.gitignore`, `docs/BASELINE_1.1.md`, `.superpowers/sdd/progress.md`

- [ ] 使用 SQLite 在线备份运行数据库，并通过脱敏诊断包备份配置与日志。
- [ ] 记录进程、端口、媒体数量、现有版本和基线测试。
- [ ] 初始化项目本地 Git，提交原始源码基线，不推送远程。
- [ ] 创建被 `.gitignore` 排除的项目内工作树和 `feature/mediareview-1.1` 分支。

### Task 1: 数据库优先媒体索引与后台同步

**Files:** `server/app/db/models.py`, `server/app/services/media_index.py`, `server/app/api/v1/media.py`, Alembic `0010`, server tests

**Produces:** 数据库分页 `list_cached_media(...)`、持久化同步任务、`MediaSyncState`、后台 `refresh_media_index(...)`。

- [ ] 先增加失败测试，证明 `/media` 不访问 Jellyfin、10 万条分页性能、同步失败保留旧缓存。
- [ ] 增加同步状态、可用性和查询索引迁移。
- [ ] 将列表、过滤、搜索、排序、分页全部改成 SQLite 查询。
- [ ] 实现每批 500 条的后台 bulk upsert；完整成功后才标记失效项目。
- [ ] 增加 `POST /media/refresh`、通用任务查询与取消接口。

### Task 2: 批阅数据库建队与稳定任务

**Files:** `server/app/services/review.py`, `server/app/api/v1/review.py`, review tests

- [ ] 先增加失败测试，证明创建会话不访问 Jellyfin且可处理 10 万条索引。
- [ ] 使用数据库筛选结果批量写入批阅队列，并保留完全重复代表项规则。
- [ ] 保持 current index、seen、恢复和幂等行为兼容旧客户端。

### Task 3: 局域网 URL、发现和配对身份

**Files:** Jellyfin adapter/config、pairing service、Android network/DataStore、Alembic `0012`、对应测试

- [ ] 先增加回环 URL、同设备重复配对和迁移去重失败测试。
- [ ] 增加可选 `jellyfin.client_url`，服务端按客户端可达主机构造媒体 URL。
- [ ] Android `MediaUrlResolver` 在 Coil/Media3 前拒绝或安全改写回环 URL。
- [ ] 稳定保存 Android installation ID；同设备 verify 使用 upsert。
- [ ] 自动发现使用 UDP 35001，TCP 8766 健康确认，手动 IP 始终可用。

### Task 4: 深色设计系统、品牌与主导航

**Files:** Android theme、launcher resources、`MainActivity.kt`、共享 UI 组件和 Compose tests

- [ ] 先增加品牌资源、四入口导航和可访问性语义测试。
- [ ] 建立颜色、排版、间距、圆角、图标和状态组件。
- [ ] 首页改为媒体内容；IP/UUID 移至设置。
- [ ] 底部导航固定为媒体、批阅、收藏、整理；播放器与图片查看器全屏。
- [ ] 完善深色青色自适应应用图标，禁止 Emoji 充当图标。

### Task 5: 媒体墙、图片和雪碧图

**Files:** Android media wall/viewer/sprite、server media/cache API、tests

- [ ] 先增加分页乱序、筛选、列数持久化、图片错误和雪碧图手势测试。
- [ ] 使用 Paging 3 Compose，不一次性载入完整媒体库。
- [ ] 完成全部排序、筛选、搜索防抖、2/3/4/5 列和文件夹辅助视图。
- [ ] 增加认证 thumbnail/original API；图片降采样、缩放和平移。
- [ ] 雪碧图后台生成、查询、取消、缓存失效和长按 scrub 闭环。

### Task 6: Direct Play 与 HLS 播放器

**Files:** server playback DTO/Jellyfin adapter、Android PlayerCore/PlayerScreen/ViewModel、tests

- [ ] 先增加 Direct Play、单次 HLS 回退、错误分类和最终进度补报测试。
- [ ] Playback API 返回 direct、fallback_hls、headers、时长和恢复位置；保留 `stream_url` 一版。
- [ ] 建立可撤销的客户端 Jellyfin 凭据或正式 playback contract；完成前 `stream_url` 不含 server
  key，并以 `requires_jellyfin_auth=true` 明示不可播放，禁止新增 server-key 视频代理。
- [ ] 完成手势、倍速、字幕、音轨、比例、旋转、亮度、音量和锁定。
- [ ] 失败只回退一次；HLS 失败显示中文可操作错误。

### Task 7: 批阅、收藏、待删除与重复整理

**Files:** Android review/favorites/delete/duplicates、server delete/duplicates、Alembic `0011`、tests

- [ ] 先增加 P0/P1、断点、幂等动作、确认 nonce、逐项删除和重复任务测试。
- [ ] 完成视频/图片混合批阅、settled 后播放、P0/P1 带宽仲裁和继续会话。
- [ ] 收藏状态在所有页面一致。
- [ ] 删除 prepare 返回五分钟 nonce；commit 重新校验身份并逐项审计。
- [ ] 持久化完全/疑似重复组；绝不自动删除，1.1 不引入 pHash。

### Task 8: Windows 运维控制台

**Files:** `server/app/admin.py` 或拆分后的 admin static/templates、system/task APIs、tests

- [ ] 先增加鉴权、危险操作确认和脱敏输出测试。
- [ ] 实现服务/Jellyfin、配对、媒体库、同步、任务、缓存、重复扫描、日志和诊断面板。
- [ ] 后台复用同一 API，不复制业务逻辑，不加入媒体墙。

### Task 9: 部署、升级、控制脚本和发布

**Files:** deployment scripts、PyInstaller spec、build scripts、release docs/tests

- [ ] 先增加 PowerShell 静态/模拟测试和包结构测试。
- [ ] 统一 `MediaReviewServer.exe`，随包提供 ffmpeg/ffprobe并优先使用。
- [ ] 增加 start/stop/restart/status，完善 install/repair/diagnose/uninstall。
- [ ] 升级前备份数据库与配置，失败自动回滚；保留数据默认值。
- [ ] 生成 APK、迁移 ZIP、校验值、Release Notes、验收 ZIP 和回滚说明。

### Task 10: 全量验证与发布门禁

- [ ] Server：pytest、ruff check、ruff format --check、10 万条性能测试。
- [ ] Android：JVM tests、Compose instrumentation、Debug/Release 构建与升级安装。
- [ ] 实机环境：5.6 万媒体、Jellyfin/Server重启、Wi-Fi 切换和真实格式播放。
- [ ] 真实 Android 手机完成全部 P0/P1 后发布 `1.1.0`；否则停留 `rc1`。
- [ ] 最终全分支代码审查，修复全部 Critical/Important 发现项。
