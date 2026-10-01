# MediaReview 2.0 — Stage 8C 评审摘要（Organize Center → Real Server V1）

- 日期：2026-10-01
- 分支：`feature/mediareview-v2-stage8c-organize-center`
- Base Commit：`a99c593ee145b86b6721369791f1508b82774e48`
- **Head：以交接包 `00_HANDOFF.md` 为准（由 `git rev-parse HEAD` 现场生成）**
- Git Status：CLEAN
- 结果：**READY_FOR_USER_VALIDATION**（大阶段：生成 1 个用户 APK）
- **阶段结论：合格**

## 一、目标与完成度

目标（§3）：把 V2「整理」页从 Mock 壳改成真实 Server 整理中心，完成后 **OrganizePage
不允许再出现任何 Mock 数字**。

| 任务书条目 | 状态 | 修法摘要 |
|---|---|---|
| §4/§5 不复用旧 Repository、独立 V2 Organize 数据层 | 已做 | `feature/v2/organize/data/`（接口 + Server/Demo 实现 + Router） |
| §6 每个失败显式表达（禁止 catch→空/0） | 已做 | 所有仓库方法失败上抛；404 仅在有明确语义处解释 |
| §7~§10 Overview 真实数据 + 每卡独立 Loading/Error | 已做 | 四卡独立状态 + 单卡重试；不为数字拉全量列表 |
| §11 Delete Queue Summary API | 已做 | `GET /delete-queue/summary`（pending 单条 SQL 聚合） |
| §12 Duplicate Summary API | 已做 | `GET /duplicates/summary`（计数 + 扫描任务状态） |
| §13 Review 卡改名「批阅进度」 | 已做 | active → 「37 / 100 · 剩余 63」；无 active → 「暂无进行中的批阅」 |
| §14~§17 待删除列表/封面/恢复 | 已做 | 服务端随队列下发 `cover_url`；恢复 Server 确认制 |
| §18~§23 两步最终删除 + 防重复 | 已做 | prepare 快照 → 确认数字来自 prepare → 同 nonce 只 commit 一次 |
| §22 结果逐类显示 | 已做 | 成功删除 / 文件已不存在 / 删除失败（失败项留队列） |
| §23 不削弱删除安全协议 | 遵守 | nonce 一次性/10 分钟/快照绑定/allowlist/身份校验/审计全部保留 |
| §24/§25 最终删除后刷新且不自动退出 | 已做 | `refreshAfterFinalDelete` + `dropCachedMedia`；停留本页刷新 |
| §26~§36 重复媒体中心/对比/Keep | 已做 | exact/similar 分离；详情一次请求零 N+1；Keep 服务端确认 |
| §31/§36 不自动删除重复文件 | 遵守 | 只发现 / 对比 / 记录保留；不做"一键删除其余" |
| §37~§42 媒体库管理 | 已做 | 复用 libraries 接口；本地草稿 + 应用；失败保留草稿；全取消本地阻止 |
| §41 媒体库变化后缓存失效 | 已做 | `refreshAfterLibraryChange` → invalidate + 重载首页 |
| §43 Demo 模式不崩、不调真实 Server | 已做 | Demo 待删除走内存；重复媒体/媒体库明确「仅服务器模式可用」 |
| §46/§47 Navigation + 拆出 OrganizePage | 已做 | `organize/*` 路由；`OrganizePage.kt` 从 V2MainScreen 拆出 |
| §48/§49 V2 风格、不做视觉大改 | 遵守 | 复用 V2Spacing/V2Radius + Media* 主题 token |
| §50~§55 测试（Server/Android Repository/ViewModel/Instrumentation） | 已做 | 见「三、测试规模」 |
| §56 用户真实文件安全 | 遵守 | 测试全部 temp 目录 + test DB + test media，未指向 Jellyfin |
| §57 生产 Server NOT DEPLOYED | 遵守 | 未替换服务 / 未迁移 DB / 未重启 ControlHub |
| §58 8B.2 性能债不处理 | 遵守 | `mark_seen` 的重复聚合未改动（DO NOT OPTIMIZE YET） |
| §59/§60/§61 APK 政策与 6 个 commit | 遵守 | 全部完成后只生成 1 个 APK；6 个 commit 拆分 |

## 二、主要改动

### Server（`server/`）

- `app/services/delete_queue.py`：新增 `pending_summary()`（pending 的 count/total_bytes 单条 SQL）。
- `app/api/v1/delete_queue.py`：新增 `GET /summary`；`GET /delete-queue` 队列项媒体摘要补齐
  `cover_url`（带 `source_version`）+ `original_url`，与 `/media` 缓存版本语义一致。
- `app/services/duplicate_scanner.py`：新增 `persisted_group_counts()`（单条 SQL 计数）、
  `group_detail()`（成员媒体行一次 `IN (...)` 批量取回）。
- `app/api/v1/duplicates.py`：新增 `GET /duplicates/summary`、`GET /duplicates/{group_id}`
  （成员含 size/duration/分辨率/media_type/cover_url；未知分组 404）。
- 测试：新增 `tests/test_organize_center_8c.py`（6 项：摘要口径、封面语义、
  missing 清理、重复计数/状态、详情成员、keep 反映）。

### Android（`android/`）

- 新增 `feature/v2/organize/`：
  - `OrganizePage.kt` / `OrganizeViewModel.kt` / `OrganizeUiState.kt`（四卡独立状态 + 文案）；
  - `data/`：`V2OrganizeRepository.kt`（接口+模型）、`V2ServerOrganizeRepository.kt`、
    `V2DemoOrganizeRepository.kt`、`V2OrganizeRepositoryRouter.kt`；
  - `delete/`：`DeleteQueueScreen.kt` / `DeleteQueueViewModel.kt`（状态机 + single-flight）/
    `DeleteResultSheet.kt`；
  - `duplicates/`：`DuplicatesScreen.kt` / `DuplicatesViewModel.kt`（扫描轮询 + 对比 ViewModel）/
    `DuplicateCompareScreen.kt` / `DuplicateScanState.kt`；
  - `libraries/`：`LibraryManagerScreen.kt` / `LibraryManagerViewModel.kt`。
- `core/model/ApiModels.kt`：新增 `DeleteQueueSummaryDto / DuplicateSummaryDto /
  DuplicateGroupDetailDto / DuplicateDetailMemberDto / DuplicateKeepResultDto`（修正 keep 布尔解析缺陷）。
- `core/network/MediaReviewApi.kt`：新增 3 个 Organize 端点；修正 keep 返回类型。
- `feature/v2/data/MediaRepository.kt` + `V2ServerModels.kt` + 两处实现：新增
  `dropCachedMedia()`（最终删除后丢弃已删除媒体的缓存条目）。
- `feature/v2/MainScreen.kt`：OrganizePage 迁出 + 4 条新路由接线；`MediaNavigator.kt` 新增路由。
- `feature/v2/home/V2HomeViewModel.kt`：新增 `refreshAfterFinalDelete()` / `refreshAfterLibraryChange()`。
- `di/V2DataModule.kt`：绑定 `V2OrganizeRepository` 路由器。

### 测试（`android/app/src/test` 与 `androidTest`）

- JVM：`V2ServerOrganizeRepositoryContractTest`（MockWebServer，含"详情一次请求零 N+1"断言）、
  `OrganizeViewModelTest`（卡片独立失败/单卡重试）、`DeleteQueueViewModelTest`（single-flight/
  结果汇总/取消忽略/失败不刷新）、`DuplicatesViewModelTest`（轮询/成功重载/防重/keep 确认）、
  `LibraryManagerViewModelTest`（草稿/应用/失败保留/全取消阻止）。
- Instrumentation：`Stage8COrganizeUiTest`（Compose：真实数据卡片 + 三条导航往返 +
  两步删除确认 Sheet + 结果 Sheet + 恢复）、`Stage8COrganizeServerFlowTest`
  （设备内 MockWebServer + 真实 ViewModel：扫描生命周期 / 对比 keep / 媒体库保存 / 恢复）。

## 三、测试规模（真实执行）

| 环境 | 结果 |
|---|---|
| Server pytest（全量） | **413 tests / 0 failed / 0 error / exit_code=0** |
| Android JVM | **464 tests / 0 failed / 0 error** |
| Lint | **0 errors**（41 warnings，与 8B.2 持平，无新增 error） |
| compileDebugKotlin | BUILD SUCCESSFUL |
| Instrumentation（模拟器 API35 + 设备内 MockWebServer + 宿主 Mock 8799） | 见 `logs/android_instrumentation_raw.txt` |
| 用户 APK | **1 个**（`MediaReview-v2-stage8c-organize.apk`，clean assembleDebug 于最终 HEAD 构建） |

## 四、已知问题 / 遗留

1. Organize 首页在每次进入整理 Tab 时刷新四张卡（4 个轻量请求）——换取"删除/扫描/媒体库变更后数字即时同步"；
   若未来要降请求量，可改为 revision 失效机制（本阶段不引入）。
2. 「批阅进度」卡只显示 active Session 的绝对进度；历史累计（跨会话）**不伪造**，
   需要产品确认后再设计。
3. 重复扫描进度轮询为 1.5s（页面可见时）；切后台/离开页面停止（不引入 WorkManager 常驻轮询）。
4. 媒体库管理在 Demo 模式明确不可用（「仅服务器模式可用」），与任务书 §43 一致。
5. 未部署生产 Server；真机 + 真实 Jellyfin 验收由用户完成（本阶段交付 APK）。

## 五、风险最高的 3 个点（Agent 自评）

1. **两步删除的 UI 状态机**：prepare→确认→commit 的 single-flight 与"确认数字来自 prepare"
   已由 ViewModel 测试 + 设备侧 UI 测试覆盖；但真实大队列（数千项）下 prepare/commit 的等待时长
   与取消行为仍建议真机抽样。
2. **重复详情的"零 N+1"依赖服务端一次批量 SQL**：Server 端已按 `IN (...)` 实现并有测试，
   但超大分组（>200 成员）下响应体积值得真机观察。
3. **Organize 首屏 4 个并发请求**：任一失败只影响单卡（已测试）；但弱网下会出现
   "部分卡 Ready + 部分卡 Error"的混合状态——这是设计行为（§9），需要用户确认可接受。

## 六、是否建议进入下一阶段

建议：**先由用户安装 Stage 8C APK 完成实机验收**（整理 → 待删除/重复媒体/媒体库；
两步删除；重复扫描；Keep）。
验收通过后再进入：

```text
Stage 8D（任务书 §64 之后未定义，等待用户指令）
```

本阶段结束后停止，不自动进入下一阶段。

---

阶段结论：合格（READY_FOR_USER_VALIDATION）