# REVIEW_SUMMARY — 阶段 16：预部署收口(8 项)+ 部署产物

> Stage 15 ChatGPT 深度验收通过后,按指令先完成 8 项预部署收口(媒体墙单击、认证边界、Coil 认证、Media Snapshot 缓存、latest-wins 令牌、position 串行、绝对索引、删除事件),再完成部署产物(Server EXE、FFmpeg 打包支持、install/repair/uninstall/diagnose、部署 ZIP)。**Android 真实编译 + 34 项单测、Server 150 项回归 + ruff 全绿、EXE 冒烟通过。**

## 1. 覆盖阶段与目标

- **媒体墙视频单击**:`longPressScrub(onTap = onClick)` 恢复媒体墙 → 普通播放器入口(修复 Stage 15 意外丢失)。
- **认证边界**:`/media/*`、`/libraries/*` 加 `require_auth`;`/cache/*`、`/jellyfin/*` 加 `require_localhost_or_auth`;health/discovery/pairing verify 保持匿名——防止未配对局域网设备经媒体接口拿到带 `?api_key=` 的 Jellyfin 直连 URL。
- **Android Coil 统一认证 ImageLoader**:雪碧图/服务器图片请求自动带 Bearer,不污染 Jellyfin 直连 URL。
- **Media Snapshot Cache**:TTL + LRU + 库选择失效,媒体墙翻页/切排序不再重复全库扫描 Jellyfin;验收:page1→3 全库只扫一次;10000 媒体验证。
- **LatestWinsScheduler token 永久单调递增**(reset 不复用历史 token)。
- **Review position Channel.CONFLATED 单消费者串行上报**(快速滑动不覆盖)。
- **PlayerCore 槽位改用服务端绝对 queue index**(prepend 不破坏匹配)。
- **待删除 enqueue 成功后才自动滑向下一条**(DeleteSucceeded 事件)。
- **部署产物**:Server EXE(PyInstaller onedir,冒烟通过)、FFmpeg 打包支持、4 个 PowerShell 部署脚本、部署 ZIP + HANDOVER。

## 2. 实际完成内容

- Server(0.8.1):
  - `api/v1/auth.py`:`require_localhost_or_auth`(上阶段已加,本阶段应用到 media/libraries/cache/jellyfin)。
  - `api/v1/media.py`:列表/详情/播放 3 个 GET 加 `require_auth`。
  - `api/v1/libraries.py`:list + selection 加 `require_auth`。
  - `api/v1/cache.py`:全部端点加 `require_localhost_or_auth`。
  - `api/v1/jellyfin.py`:status/users/libraries 加 `require_localhost_or_auth`,且 `_auth` 参数置于 `jellyfin_client` 之前(保证 401 先于配置校验)。
  - `services/media_index.py`:**Media Snapshot Cache**(内存 dict,TTL 180s,LRU 上限 8,`apply_selection` 时 `invalidate_media_snapshots`)。
  - `db/migrate.py`:冻结感知(`sys._MEIPASS`),供 PyInstaller 形态定位 alembic.ini/migrations。
  - 新增 `run_server.py`(uvicorn 入口)、`packaging/mediareview_server.spec`(PyInstaller onedir)。
- Android(0.9.1):
  - `feature/mediawall/MediaWallScreen.kt`:`longPressScrub(onTap = onClick)`。
  - `core/network/CacheAuthInterceptor.kt`(新):仅对 `/api/v1/` 附加 Bearer。
  - `di/AppModule.kt`:`@Named("cache") OkHttpClient` + 注入 `ImageLoader`。
  - `MediaReviewApp.kt`:onCreate 设 Coil 单例 ImageLoader。
  - `core/media/LatestWinsScheduler.kt`:reset 前移计数器,不复用 token。
  - `feature/review/ReviewViewModel.kt`:position 改 `Channel.CONFLATED` 单消费者;`onSettled` 用 `ReviewQueueItemDto.index` 绝对索引;`onDelete` 成功后才发 `DeleteSucceeded` 事件。
  - `feature/review/ReviewScreen.kt`:收集 `DeleteSucceeded` 事件后再翻页。
- 部署:
  - `deployment/scripts/{install,repair,uninstall,diagnose}.ps1`、`deployment/README.md`、`deployment/config.example.json`、`deployment/HANDOVER_TEMPLATE.md`。
  - `scripts/build_deploy.py`:构建 EXE + 组装部署包。
  - 产物:`server/dist/MediaReviewServer/Mediaserver.exe`(冒烟:health 200 + admin 200);`deploy_handoff/MediaReviewServer-0.8.1_deploy_*.zip`(25.8MB)。
- 测试(Android 新增):`LatestWinsSchedulerTest`(+2:token 永不复用、多次 reset 单调)、`PositionChannelConflationTest`(2:只收最新、串行有序)。
- 测试(Server 新增):认证边界 ×3 + jellyfin 401、快照只扫一次、10000 分页不重扫。

## 3. 是否完整达到目标

- 指令要求的 8 项预部署收口全部落地并通过真实编译/回归;部署产物(EXE/脚本/部署 ZIP)完成,EXE 已冒烟。
- 条件缺口:播放器手势冲突真机统一验证;FFmpeg 本机缺失需目标机提供;正式签名 APK 与 clean-machine 部署测试待迁移到真实 Jellyfin 电脑。
- 结论:**合格**(迁移到真实 Jellyfin 电脑的节点已到;后续 Stage 16/17 在真实环境跑通自动部署/Jellyfin/大视频/雪碧图/手机体验)。

## 4. 主要新增/修改文件

- 新增(Server):`run_server.py`、`packaging/mediareview_server.spec`。
- 修改(Server):`api/v1/{media,libraries,cache,jellyfin}.py`、`services/media_index.py`、`db/migrate.py`、`tests/{test_stage7_fix,test_media_api}.py`、`__init__.py`/`pyproject.toml`(0.8.1)。
- 新增(Android):`core/network/CacheAuthInterceptor.kt`、测试 `PositionChannelConflationTest.kt`。
- 修改(Android):`feature/mediawall/MediaWallScreen.kt`、`di/AppModule.kt`、`MediaReviewApp.kt`、`core/media/LatestWinsScheduler.kt`、`feature/review/{ReviewViewModel,ReviewScreen}.kt`、测试 `LatestWinsSchedulerTest.kt`。
- 新增(部署):`deployment/scripts/*.ps1`(4 个)、`deployment/README.md`、`scripts/build_deploy.py`、`server/dist/MediaReviewServer/`(EXE 产物)。
- 文档:`docs/DEV_LOG.md`、`TASKS.md`、`REVIEW_SUMMARY.md`。

清单以包内目录结构与 `review_meta/git_*.txt` 为准(本机无 git CLI,git 内容为空并作说明;源码与部署脚本已手动打包)。

## 5. 核心架构变化

- 认证边界从"业务接口匿名 + 危险接口 token"升级为"媒体/媒体库 token、缓存/Jellyfin 拓扑 localhost-or-auth",堵住 api_key 直连 URL 泄露。
- 媒体墙分页从"每页全库扫描"改为"快照缓存(内存 TTL+LRU)",大库滚动性能显著提升。
- 批阅 position 从"每 settle 一个 launch"改为"CONFLATED 通道 + 单消费者",消除顺序竞争。
- 部署链路:源码运行 → PyInstaller EXE + 部署脚本 + 部署 ZIP。

## 6. API 变化

- `/media`(GET 列表/详情/播放)、`/libraries`(GET/PUT)新增认证要求(未配对局域网 401)。
- `/cache/*`、`/jellyfin/*` 新增 localhost-or-auth(本机放行,局域网需 token)。

## 7. 数据库变化

- 无 Schema 变更(快照为内存结构)。

## 8. Android UI/交互变化

- 媒体墙视频单击恢复打开普通播放器。
- 雪碧图/服务器图片经认证 ImageLoader 自动携带 token。
- 待删除在服务器确认成功后才自动翻下一条(避免失败也翻)。

## 9. 已执行测试与结果(真实输出)

- **Android**:`gradlew :app:assembleDebug :app:testDebugUnitTest` → **BUILD SUCCESSFUL**;34 项单测 0 失败(LatestWins 5 + Slots 9 + QueueWindow 7 + ApiModels 4 + UrlNormalize 4 + PanClamp 3 + PositionConflation 2)。
- **Server**:`pytest` → **150 passed**,1 warning;`ruff check` All checks passed;`ruff format` 74 files formatted。
- **EXE 冒烟**:真实启动 `dist/MediaReviewServer/Mediaserver.exe`,`GET /api/v1/system/health` → 200(version 0.8.1, database ok),`GET /admin` → 200。
- 每次构建均在本机真实执行,无模拟。

## 10. lint / format / type check 结果

- Server ruff:All checks passed。
- Android:Gradle 编译期校验通过(assembleDebug 成功)。

## 11. 已知问题

- 播放器横纵拖动/单双击手势冲突需真机统一验证(必要时合并手势仲裁状态机)。
- FFmpeg 本机缺失,部署包不含 ffmpeg.exe;目标机需提供 `third_party/ffmpeg/` 或系统 PATH 有 ffmpeg,否则雪碧图不可用。
- 正式签名 APK 与 clean-machine 部署测试未执行(待迁移)。

## 12. 遗留 TODO

- 迁移到真实 Jellyfin 电脑:install.ps1 安装、真实 Jellyfin 联调、大视频/雪碧图/手机体验、手势真机验证。
- FFmpeg 落地与 config `storage.ffmpeg_dir` 确认。
- 正式签名 APK;git 提交待具备 git 的机器。

## 13. 是否建议进入下一阶段

建议迁移到真实 Jellyfin 电脑进行真机联调与部署验收(进入 Stage 17)。收口 8 项全部落地,Android 34 单测 + Server 150 回归 + EXE 冒烟全绿。

## 14. 风险最高的 3 个点

1. PyInstaller EXE 只在本机 Windows 冒烟;目标电脑的系统差异(Python 无关,但安全软件/缺 VC++ 运行库)需在真实机验证。
2. 认证边界收紧后,配对流程/管理后台/雪碧图首次上真机可能出现漏配 token 的调用点,需真机回归一遍。
3. Media Snapshot Cache 为内存态,服务器重启/库选择变化后的缓存一致性需在真实大库上复核。

## 15. 敏感配置脱敏

- 部署脚本与诊断脚本均对 token/api_key 脱敏;部署包不含密钥;`.gitignore` 排除 build/dist/venv 等。

## 阶段结论

**合格**

> 条件:① 迁移到真实 Jellyfin 电脑完成 install.ps1 部署 + 真机联调(播放器手势/雪碧图/自动发现/进度上报);② FFmpeg 落地;③ 正式签名 APK 与 clean-machine 部署测试;④ git 提交待具备 git 的机器。Android 34 单测全绿,Server 150 回归全绿,Server EXE 本机冒烟通过。
