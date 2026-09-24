# REVIEW_SUMMARY — MediaReview 2.0 V2 Stage 8A（Production Data Bridge）

- 阶段编号与名称：**Stage 8A — Production Data Bridge / V2 真实 Server 数据接入第一阶段**
- 分支：`feature/mediareview-v2-stage8a-production-bridge`（base：Stage 7.1 HEAD `27727bf`）
- 日期：2026-09-24
- 目标：第一次打通 V2 UI → V2 Production Repository → 现有 MediaReview API → FastAPI → Jellyfin，
  同时完整保留 DEMO 离线模式；**V2 仍是唯一正式 UI**；本阶段不接真实 Review Session。

## 1. 实际完成内容（是否达到目标）

达到（就"真实 Server 数据接入第一阶段"而言完整达成）：

1. **数据源不再写死**：`V2DataMode { DEMO, SERVER }` + `V2DataModeStore`（DataStore，默认 DEMO）；
   启动只读本地值，不联网、不被 ConnectScreen / 健康检查阻塞；`MainActivity` 只进 V2。
2. **Repository Router**：Hilt 绑定不变（仍是 `MediaRepository`），`V2MediaRepositoryRouter` 按模式委托
   Demo / Server 实现；不存在"接 Server 就回退旧 UI"的路径。
3. **Server 仓库分层**：`V2ServerMediaRepository` / `V2MediaMapper` / `V2PlaybackResolver` /
   `V2ServerSessionBootstrap` / `V2ServerModels`（+ 资源缓存）/ `V2ServerStatus`(+HealthMonitor)；
   复用既有 `ApiFactory` / `TokenProvider` / `ServerProfileStore` / `MediaUrlResolver` / `MediaReviewApi`，
   **未新建任何 Retrofit / Token / Jellyfin 客户端**。
4. **分页与查询下推**：`mediaPage(V2MediaQuery)` 为正式列表路径（50 条/页 + 滚动加载）：
   search / sort / media_type / folder_id / page 全部由 Server 执行；Demo 仍内存排序后分页。
5. **文件夹与书架**：`GET /media/folders` 提供 `count` / `image_count` / `cover_media_id` / `cover_url`；
   App 端不做 N+1；书架相册封面优先用户本地选择，否则用服务端代表封面。
6. **资源解析**：cover / original 经 `MediaUrlResolver` 解析为绝对地址并只存于资源缓存；
   `V2Media` 的 Demo 语义字段在 Server 模式保持空（不把 URL 塞进 Demo 字段）。
7. **播放异步化**：`suspend fun resolvePlayback()`；`PlaybackContext` 只存 id/title/queue/index；
   `V2NativePlayerViewModel` 收敛 Loading/Ready/Error；只解析当前条 + 预取下一条（窗口 ≤2）；
   Direct → 一次 HLS → Error；`resume_position_ms` Prepared 后 seek；进度按 开始/暂停/退出/15s 上报。
8. **收藏**：服务器确认制（成功才改 UI，失败保持原状态 + Snackbar）。
9. **UI**：顶部轻量数据源状态条 + `V2DataSourceSheet`（Demo / 我的服务器 + 手动 IP + 配对码，
   复用 `PairingRepository.checkHealthy / verifyAndPair`）+ 整理页数据源入口 + 根 Snackbar。
10. **Stage 7.1 两个竞态修复**：§17.1 postDelayed 改协程 `delay(120)` + 取消后二次确认；
    §17.2 `hasPlaybackAdvanced` 判定（Paused/Completed 不再无条件认为已出画）。
11. **Review 保持 Demo**：Server 模式显示"真实批阅接入将在 Stage 8B 完成"占位，不建立队列、不批量 resolve。
12. **Server 合同扩展**：`MediaSummary` + `folder_id`/`folder_name`；`FolderSummary` + 3 个字段（窗口函数一次查询）。

## 2. 主要新增 / 修改文件

**新增（Android）**：`feature/v2/data/V2DataMode.kt`、`V2MediaRepositoryRouter.kt`、
`feature/v2/data/server/{V2ServerMediaRepository,V2MediaMapper,V2PlaybackResolver,V2ServerSessionBootstrap,V2ServerModels,V2ServerStatus}.kt`、
`feature/v2/model/V2Playback.kt`、`feature/v2/player/V2NativePlayerViewModel.kt`、
`feature/v2/player/native/state/PlayerHostReadiness.kt`、`feature/v2/settings/V2DataSourceSheet.kt`、
测试：`V2ServerMediaRepositoryContractTest.kt`、`V2TestViewModels.kt`、`Stage8AServerModeTest.kt`。

**修改（Android）**：`MainActivity`、`feature/v2/{V2MainScreen,V2Models,MediaRepository}`、
`data/{DemoMediaRepository,V2DataModule}`、`home/{HomeScreen,V2HomeViewModel,MediaCard}`、
`player/native/{GsyNativePlayerScreen,GsyNativePlayerScreen 依赖的 PlaybackContext,ui/GsyNativeIndicators}`、
`review/{V2ReviewScreen,V2ReviewViewModel}`、`core/model/{MediaModels,ApiModels}`、`core/network/MediaReviewApi`、
`app/build.gradle.kts`、`gradle/libs.versions.toml`；删除 `feature/v2/V2AppMode.kt`。

**修改（Server）**：`app/services/media_index.py`、`app/api/v1/media.py`、`tests/test_media_api.py`。

## 3. 核心架构变化

- 单一 UI + 双数据源实现（Router 委托），模式是**运行时状态**而非编译期常量；
- 播放源从"同步 URL/headers 快照"改为"异步解析 + UiState 三态"，播放器组合函数不发网络请求；
- 数据合同（列表/文件夹/播放/收藏/进度）全部有 MockWebServer 合同测试与真实 HTTP 证据；
- 新增 2 个必须修复的真实缺陷（见 §5）。

## 4. API / 数据库变化

- Server：`FolderSummary` + `image_count`/`cover_media_id`/`cover_url`；`MediaSummary` + `folder_id`/`folder_name`（均可空、向后兼容）；
- Android 共享层：`addFavorite`/`removeFavorite`/`reportProgress` 的 DTO 由 `Map<String,String>` 改为 `MutationResultDto`（布尔结果）；
- **数据库：无 schema 变更、无 migration**。
- 详见 `07_SHARED_API_CHANGES.md`。

## 5. Android UI / 交互变化

- 首页顶部新增数据源状态条（点击打开数据源 Sheet）；媒体墙底部新增分页状态（加载中 / 继续下滑 / 已加载全部 N 项）；
- 数据源 Sheet：Demo ↔ 我的服务器、首次配置（地址 + 配对码）、状态行、重新检测 / 断开连接；
- 整理页新增"数据源"卡片入口；
- 播放器：加载中 / 播放失败（含原因文案 + 重试）三态；图片 Viewer / 批阅 UI 未改手势与布局。

## 6. 已执行测试与结果（真实输出见 02_TEST_REPORT.md 与 logs/）

| 项目 | 命令 | 结果 |
|---|---|---|
| Android JVM | `gradlew :app:testDebugUnitTest` | 326 tests / 0 failures |
| Android 构建 | `gradlew :app:assembleDebug` | BUILD SUCCESSFUL |
| Android Lint | `gradlew :app:lintDebug` | 0 errors / 43 warnings（与 Stage 7.1 同） |
| Server 单文件 | `pytest tests/test_media_api.py -q` | 29 passed |
| Server 全量 | `pytest -q` | 360 tests / 1 failed（既有无关失败） |
| Server Lint | `ruff check app tests` | All checks passed |
| 模拟器 DEMO V2 UI | `connectedDebugAndroidTest`（3 个类） | 13/16（3 项在 base 上同样失败，已复验） |
| 模拟器 Server 模式端到端 | `Stage8AServerModeTest`（Mock Server） | 4/4 passed |
| 真机 App 手动流程 | 数据源连接 → 媒体墙 → Viewer → 播放 | 通过（Mock Server） |
| DEMO 批阅回归 | 模拟器实测 | `PlaybackReady ok=true advanced=true` |

## 7. 已知问题与遗留 TODO

见 `05_KNOWN_ISSUES.md`。要点：真实 Jellyfin 端到端 NOT TESTED（Mock Server 已覆盖合同）、
真机 NOT VERIFIED、3 个既有 Instrumentation 失败（非本阶段回归）、兼容路径有界 200 条、
其它布尔结果型接口（delete-queue / duplicates / review seen）存在同类 DTO 风险待后续统一修复。

## 8. 是否建议进入下一阶段

**建议**：先由用户在真实 Jellyfin 与真机上验证本期（浏览 / 播放 / 收藏 / 数据源切换），
通过后进入 **Stage 8B — Production Review Session**。

## 9. Agent 自认为风险最高的 3 个点

1. **真实 Jellyfin 差异**：Mock 验证的是 MediaReview 合同；真实 Jellyfin 的 Direct URL/Headers/转码行为
   仍可能带来播放差异（尤其 HLS 回退成功路径未在真实转码环境验证）。
2. **GSY headers 时序**：本次修复依赖 `GSYVideoOptionBuilder.setMapHeadData`；若后续升级 GSY 或改变换源顺序，
   需要用 `logs/` 中的真实 HTTP 证据重新验证（已加入合同测试，但播放器层仍属经验性修复）。
3. **大库体验**：分页与滚动加载逻辑在新库可用，但几千条媒体的封面并发、内存（资源缓存 LRU 2048）
   与相册窗口（200 条上限）在真实大库下的表现需真机验证。

---

**阶段结论：有条件合格**

（条件：真实 Jellyfin / 真机验证尚未完成；Mock Server 端到端与全部自动化测试已 PASS。）