# MediaReview 2.0 — Stage 8B.1 评审摘要（Review Correctness & Delivery Closure）

- 日期：2026-09-28
- 分支：`feature/mediareview-v2-stage8b.1-review-closure`
- Base Commit：`61832ed1664c5a38839cb765621357fa59932492`
- **Head：以交接包 `00_HANDOFF.md` 为准（由 `git rev-parse HEAD` 现场生成）**
- Git Status：CLEAN
- 结果：**CODE_READY / USER_VALIDATION_PENDING**
- **阶段结论：有条件合格**（代码、构建、自动测试全绿；真实手机 + 真实 Jellyfin 体验验收仍待用户）

## 一、目标与完成度

目标（评审 §2）：只解决 **批阅正确性 + 分页正确性 + Server 状态权威性 + 测试/交付证据可信性**，
不新增任何产品功能。

| 评审意见 | 状态 | 修法摘要 |
|---|---|---|
| §3 双向分页窗口 | 已修 | `ReviewQueueWindow` 改为 `firstLoadedPage/lastLoadedPage/pageSize/totalCount` |
| §4 next 后再 prev 请求错页 | 已修 | `next = lastLoadedPage+1`、`prev = firstLoadedPage-1`（新增两种顺序的测试） |
| §5 分页合并去重 | 已修 | `mergeItems` 按 absoluteIndex 去重 + 排序 |
| §6 缺项 localIndexOf | 已修 | 改 `indexOfFirst` 搜索，找不到返回 null |
| §7 失效 current_index 恢复 | 已修 | 先向后找 `>= current_index`，否则回退最近可用项，并把真实索引写回服务端 |
| §8 atEnd 依赖最后可用媒体 | 已修 | 改为页边界：`lastLoadedPage * pageSize >= totalCount` |
| §9 空页死循环 | 已修 | 空页推进页边界继续扫描（单次最多 8 页），到头返回窗口快照 |
| §10 分页 single-flight | 已修 | Repository 内 `Mutex` 串行化 next/prev |
| §11 Server queue 带 seen | 已修 | `QueueItem.seen` |
| §12 Android queue item 带 seen | 已修 | `ReviewQueueItemUi.seen`（服务端权威） |
| §13 seenCount 重复计算 | 已修 | 删除 `baseSeenCount + confirmedSeen.size` 推算 |
| §14 markSeen 返回权威进度 | 已修 | 返回 `seen_count / total_count`，Android 直接采用 |
| §15 markSeen in-flight 防重 | 已修 | `seenInFlight` 集合，同一媒体并发只发一次 |
| §16 新分页收藏同步 | 已修 | `applyOpened` / `applyPageResult` 合并 `favorite=true` |
| §17/§18 完成条件 | 已修 | `atEnd && seen_count == total_count` 才允许完成 |
| §19 Server complete fail-closed | 已修 | 未批阅 → 409 CONFLICT，会话保持 active |
| §20 到队尾但仍有未批阅 | 已修 | 提示"还有未批阅内容，会话未完成"，保持 active |
| §21 complete single-flight | 已修 | `completeInFlight` |
| §22 P1 预取身份校验 | 已修 | 写入单槽前校验当前 + next 身份与 source identity |
| §23 position latest-wins | 已修 | 序号化串行写入（30→31→32 最终停在 32） |
| §24 过时 version 合同 | 已修 | 合同测试更新为 `2.0.0-alpha1 / versionCode=8` |
| §25 Hilt harness | 已修 | `HiltTestActivity`（debug 源集 + debug manifest，生产 Application） |
| §26 Legacy Shell 测试 | 已归置 | 证明生产不走旧 Shell 后删除 6 项无意义合同（非 @Ignore） |
| §27 Search flaky | 已修 + 复跑 | 断言修正（关闭搜索后断言模式行），5/5 PASS |
| §28~§32 交付证据 | 已重做 | clean assembleDebug raw 日志 + exit_code=0 + APK SHA256 + git evidence 顺序修正 + pytest raw 日志 + PARTIAL 术语 |
| §33/§34 新增测试 | 已完成 | JVM +25 项、Server +8 项（清单见 §三） |

## 二、主要改动

### Server（`server/`）

- `app/services/review.py`：`mark_seen` 返回权威进度 dict；`complete` fail-closed（`UnfinishedReviewError`）；
  `advance` / `set_position` 自动完成同样 fail-closed；`session_queue_page` 返回 `(index, seen, media)`。
- `app/api/v1/review.py`：`QueueItem.seen`；seen 返回 `{media_id, seen, seen_count, total_count}`；
  complete 捕获 `UnfinishedReviewError` → 409 CONFLICT（中文 message）。
- 测试：新增 `tests/test_review_correctness_8b1.py`（8 项）；更新 `test_review_service.py`、
  `test_phase456_api.py`、`test_deployment_contract_11.py`（过时版本合同）。
- **API 变化**（向后兼容的新增/语义收紧）：queue item 新增 `seen`；seen 响应新增 `seen_count/total_count`；
  complete 在未批阅时由 200 变为 409。**数据库无 schema 变化**（`ReviewSessionItem.seen` 早已存在）。

### Android（`android/`）

- `ReviewSessionModels.kt`：`ReviewQueueItemUi.seen`、`ReviewSeenResult`、`ReviewQueueWindow` 重构、
  `ReviewQueuePaging`（page 边界 atEnd / mergeItems / firstAtOrAfter / nearestBefore / totalPages）。
- `V2ServerReviewSessionRepository.kt`：双向分页 + 空页推进 + 合并去重 + `Mutex` single-flight +
  失效锚点恢复与位置写回 + seen 权威结果 + 位置写入固定 sessionId。
- `V2ReviewViewModel.kt`：seen 权威/in-flight 去重、完成门槛 + single-flight + 未批阅提示、
  收藏跨页合并、position 序号化 latest-wins、P1 stale 身份校验。
- `V2ReviewSessionRepository.kt` / `V2ReviewSessionRepositoryRouter.kt` / `DemoReviewSessionRepository.kt`：
  `markSeen` 合同改为 `ReviewSeenResult?`；Demo 侧保持单页全量语义（pageSize=队列长度）。
- `ApiModels.kt`：`ReviewQueueItemDto.seen`、`ReviewSeenResultDto.seen_count/total_count`。
- `app/build.gradle.kts`：instrumentation runner 保持生产 Application；`src/debug/` 新增
  `HiltTestActivity` + debug manifest（测试宿主，Release 不包含）。
- 测试：`ReviewQueuePagingTest` / `V2ReviewViewModelTest` / `V2ServerReviewSessionRepositoryContractTest`
  重写扩充；`Stage4BrowserUiTest` 宿主与断言修正；`Stage8BReviewServerFlowTest` 合同字段补齐。

### UI / 交互变化

无新功能、无视觉变化。仅新增一条提示文案：到队尾仍有未批阅时 Snackbar
「还有未批阅内容，会话未完成」（会话保持 active，不进入完成页）。

## 三、测试规模（真实执行结果）

| 环境 | 结果 |
|---|---|
| Server pytest（全量） | **392 tests / 0 failed / 0 error / exit_code=0** |
| Android JVM | **405 tests / 0 failed**（65 suites） |
| Instrumentation（模拟器 `MediaReview_Test` API35） | **36/36 PASS（0 failed）**；搜索用例 5/5 复跑 |
| Lint | **0 errors**（41 warnings + 10 info） |
| assembleDebug | `:app:clean :app:assembleDebug` **exit_code=0**（原始日志 `logs/assembleDebug_raw.txt`） |

### 设备侧逐项状态（Stage 8B → 8B.1）

| 项 | 8B | 8B.1 |
|---|---|---|
| Hilt harness（favorites×2） | FAIL（裸 ComponentActivity 无 Hilt 工厂） | **PASS**（HiltTestActivity） |
| Legacy 1.1 Shell（6 项） | FAIL（旧壳触摸/布局断言） | **已删除**（证明不可达，见 05_KNOWN_ISSUES.md） |
| Search 单输入框 | FAIL（断言与设计不符） | **PASS**（断言修正 + 5/5 复跑） |
| 其余 27 项（含 Stage8A Server 模式 4 项、Stage8B 端到端 1 项、GSY 布局 8 项） | PASS | **PASS** |

## 四、已知问题 / 遗留

1. **真实手机 + 真实 Jellyfin 未验收** → `USER_VALIDATION_PENDING`（本阶段未部署生产 Server）。
2. Compose 手势级 UI 自动化仍缺失（设备侧测试驱动 ViewModel + 真实 HTTP，不含手指上滑）。
3. 空队列完成页的"重新批阅"在 Server 模式下会新建会话（既有语义，未改）。
4. 到队尾仍有未批阅时只提示、不自动跳回第一条未看（评审 §20 明确本阶段不做）。
5. 交付证据保留一项事实：pytest 终端日志缺少最终计数行，已在 raw 日志尾部**明确标注**追加
   JUnit XML 计数与 exit_code（wrapper 脚本 `run_stage8b1_pytest.py`，证据自证）。
6. 未进入 Stage 8C（整理中心去 Mock / 接 Server），按 §39 停止等待用户实机验收。

## 五、风险最高的 3 个点（Agent 自评）

1. **完成门槛收紧后的"不可达完成"边界**：若会话尾部媒体全部失效，`seen_count` 永远小于
   `total_count`，会话将保持 active 并持续提示"还有未批阅内容"。这是评审 §18/§19/§20 明确要求的
   fail-closed 语义，但真实 Jellyfin 上媒体频繁失效的场景需要产品确认是否需要"跳过失效项"的例外。
2. **位置 latest-wins 的会话切换时序**：ViewModel 在会话切换时取消写入器 + 仓库侧固定 sessionId，
   两处防线都做了，但"旧会话迟到写入"在极端网络下仍可能打到已完成的旧会话（被服务端拒绝）。
   真实弱网需要实测确认无副作用。
3. **设备侧测试的断言强度**：favorites/player 往返用例在"播放器进入播放失败错误层"时也视为
   "已打开播放器"（测试注入的媒体不在生产演示库中）。导航契约成立，但"真实播放画面"仍须人工确认。

## 六、是否建议进入下一阶段

建议：**先由用户实机验收 Stage 8B.1 的批阅正确性**（恢复位置、完成门槛、跨页收藏、seen 提示），
确认后再进入 Stage 8C（整理中心正式接 Server）。若要立刻推进，建议先做 8B.1 的真实环境验证
（真机 + 真实 Server），而不是直接开 8C。

---
阶段结论：有条件合格