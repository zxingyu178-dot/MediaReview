# MediaReview 1.1 Task 4 / Task A 修复轮独立审查报告

日期：2026-08-30 | 审查对象：冻结 diff `.superpowers/sdd/review-f0c797c-taska.diff`（基线 f0c797c → 当前工作树，分支 feature/mediareview-1.1）
审查人：独立 reviewer agent（与执行 agent 分离，对抗性审查）

## 结论：**CLEAN**（0 Critical / 0 Important / 4 Minor）

先验证冻结性：`git diff HEAD` 与冻结 diff 逐行一致（仅 4 个声称的文件，311 insertions / 35 deletions）；`git status` 中 ReviewViewModel.kt 的 M 状态经 `git diff HEAD -- <file>` 确认为**纯行尾符噪声，内容与基线 f0c797c 逐字节一致**，与执行 agent"与基线无 diff"的声明相符。

## Critical

无。

## Important

无。

## Minor

**M1. commit 网络失败时摘要误显示"删除完成"（既有缺陷，本轮未修复亦未引入）**
`android/app/src/main/java/com/mediareview/app/feature/deletequeue/DeleteQueueViewModel.kt:85-86`：`result?.outcome ?: emptyMap()` 在 `commitDeleteQueue()` 返回 null（`MediaRepository.kt:238-239` 对任何 API 失败 `getOrNull()`）时走 `summarize(empty)` → "删除完成"，且不设置 `ui.error`。基线代码行为完全相同（旧 `?: "删除完成"`），非本轮回归，故不计入 Important。修复要求：`result == null` 时显示失败文案（如"删除请求失败，请重试"）并保留队列，不显示"删除完成"。

**M2. Unknown 线协议值的中文摘要写"失败"，语义略不精确**
`DeleteQueueViewModel.kt:100`：`failed = count { !it.successful && it != Missing }` 把 Unknown 与 Failed 合并为"失败"。fail-closed 方向正确（不推进、不计成功，且 `MutationInvalidationViewModelTest.kt:92` 用 `unknown-wire` 钉死该行为），但"未知状态"与"删除失败"对用户含义不同（后者提示可重试，前者应提示升级/诊断）。修复要求：摘要中为 Unknown 单列"未知 N 项"。

**M3. androidTest 播放计数器为普通 `var` 跨线程读写**
`MainShellProductionIntegrationTest.kt:195-211`：`settles/plays/prepares/deactivations` 由主线程写、instrumentation 线程读，无 `@Volatile`。实际安全——compose test rule 的 `waitForIdle`/`performClick` 内部经 `runOnMainSync` 的 latch 建立 happens-before——但与同文件 `parkedLookup`（AtomicReference）、`lookupImmediate`（@Volatile）的严谨度不一致。修复要求：统一加 `@Volatile`。

**M4.（边界重申，非代码缺陷）androidTest 从未在设备上执行**
本轮所有 instrumentation 断言（含新竞态测试）仅经 `assembleAndroidTest` 编译验证，未在真机/模拟器运行。报告必须继续携带此限制（执行 agent 已如实标注，本审查再次独立确认）。

## I1 / I2 closure matrix

| 项目 | 状态 | 理由 |
|---|---|---|
| **I1** 删除合同错配 | **closed** | 唯一共享解析器 `DeleteOutcomeStatus.fromWire`（`ApiModels.kt:188-203`），生产代码仅 `DeleteQueueViewModel.kt:85` 一处调用，全仓 grep 无残留 `deleted` 字符串散判（唯一出现是测试钉死 `fromWire("deleted")→Unknown` 的反协议回归闸）。全成功批次：`parsed.values.any { it.changed }` → `invalidate(ContentMutation.FinalDelete)`（`DeleteQueueViewModel.kt:88-90`），`FinalDelete` 映射 DeleteQueue/Media/Favorites/Duplicates 四区（`ContentInvalidationStore.kt` mutation 枚举），四个 revision 全部推进。摘要语义 `success=成功、missing=缺失不计成功、failed/未知=失败`（`DeleteQueueViewModel.kt:96-106`），与服务端 `server/app/services/delete_queue.py:103-150` 的 `success/missing/failed` 逐值核对一致。 |
| **I2** 生产壳竞态测试缺口 | **closed**（附 M4 边界） | 新测试 `productionShellGuardsInFlightSettleAcrossRootSwitch`（`MainShellProductionIntegrationTest.kt:123-187`）满足 I2 全部要素：真实队列项经生产 `loadIfNeeded→load→reviewQueue` 路径加载（fake 返回 1 条真实 `ReviewQueueItemDto`，非空队列）；`loadPlayback` 挂在不可取消 `suspendCoroutine` 停车点；Review 根按钮触发真实 `ReviewViewModel.onSettled(0)`；切根走**生产**失活路径（`MainShell.kt:239-253` 的 `MainRootStateHost` DisposableEffect → `onReviewDeactivated = reviewViewModel::onRootDeactivated`，非测试绕过）；断言 deactivate=1、旧 settle/play/prepare=0；重入后新 token settle/play=1/1。 |

## 逐项审查证据

**1. 真实调用路径（I1）** — 见上表。摘要示例：全成功 → "删除成功 1 项" 且四 revision=1；仅 missing → "删除成功 0 项，缺失 1 项" 且四 revision=1（服务端对 missing 仍清理索引/收藏/队列行，内容确实变更）；全失败/未知 → 不推进。

**2. 既有语义未破坏** — `restore()`（dequeue→invalidate→load）与 `loadIfNeeded()`（RevisionLoadGate）未动。`load()` 从整体替换改为 `update` 保留 `commitResult`（`DeleteQueueViewModel.kt:60-67`）——这实际修复了执行 agent 发现的第三个真实缺陷：基线 commit() 的摘要会被自己的尾随 `load()` 立即抹成 null（UI 上对话框闪现即消）。UI 消费方 `DeleteQueueScreen.kt:142-151` 是以 `commitResult` 为键的模态 AlertDialog，关闭即 `clearCommitResult()`；模态阻挡其他操作，系统返回键触发 `onDismissRequest` 清除，不存在陈旧摘要跨页面复现路径。自洽。

**3. 表驱动测试逐案例 vs 服务端合同** — 六案例（全 success / 全 failed / 空 / 仅 missing / 混合 / unknown-wire）的期望摘要与期望 changed 与 `delete_queue.py:113-149`（success=真实删除、missing=幂等清理仍清索引、failed=guard/OSError 拒绝且行保留）及 `server/app/api/v1/delete_queue.py:48-54`、既有回归 `test_phase456_api.py:183`（`{"del1": "success"}`）全部一致；空 outcome 恰对应无 pending 行时服务端返回 `{}`。每案例新建 repository/store/VM，无状态泄漏。解析器测试额外钉死大小写宽容与 `deleted→Unknown` 反协议闸，Unknown 的 changed/successful 回归由案例 6 间接覆盖（若 `Unknown.successful=true` 或 `changed=true`，案例 6 必失败）。

**4. JVM 竞态测试与 RED 必然性** — 技术正确性：`suspendCoroutine` 停车不可取消（对比 `CompletableDeferred`/可取消 await 会被 `cancel()` 直接杀死而复现不了窗口，代码注释准确）；取消后 resume 进入已取消 Job 的协程会继续同步执行至 `isValid`，中间无任何挂起点或取消检查——因此**唯一拦截手段就是 reset()**，测试测的正是该不变量。沿 `ReviewViewModel.onSettled(260-295)/onRootDeactivated(379-383)/LatestWinsScheduler(14-33, reset 前移 1 shl 40 永不复用)` 逐步推演：移除 `settleScheduler.reset()` 后 resume 时 `isValid(oldToken)==true` → `playback.settle` 必然落地 → `assertEquals("旧 settle 不得落地", 0, playback.settles)` 必然失败（"expected 0 but was 1"），androidTest 同理。RED 是确定性的，非概率性。断言完整性：deactivate=1（:198）、旧 settle=0（:203）、旧 play=0（:204）、旧 prepare=0（:205）、重入 settle=1/play=1（:211-212）——I2 要求全覆盖。一个易错点被正确规避：旧 lookup release 后 `streamUrlCache` 已缓存 URL，重入走缓存（`lookupsStarted` 保持 1），测试未错误断言 `lookupsStarted==2`。

**5. androidTest 真实性与停车机制** — 生产 `MainShellScreen`（真底栏、真 `MainRootStateHost`、真激活/失活回调）+ 注入的真实 `ReviewViewModel`；`rootContentOverride` 是既有许可的测试接缝（`MainShell.kt:131`），仅替换根内容组合，不绕过失活路径。停车无死锁：挂起协程不占主线程，`latch.await` 只阻塞 instrumentation 线程；`releaseHeldLookup` 从 instrumentation 线程 resume → 派发主线程 → `waitForIdle` 消费。顺序确定性：nav 点击后 `waitForIdle` 保证 DisposableEffect 失活（deactivate）先于 release 执行。`isVideo` 链路核实（`MediaModels.kt:46: media_type == "video"`，fake 提供 "video"）确保 lookup 真会挂起。边界 M4 如实。残留观察（不阻塞）：生产 `ReviewScreen` pager→`onSettled` 连线仍未被该测试组合（I2 要求的字面范围已满足）。

**6. 对抗性检查** — 生产代码线程安全：`_ui` 全部经 `MutableStateFlow.update` CAS，`ContentInvalidationStore` 用 AtomicLongArray，枚举纯函数；continuation 强引用持有、`getAndSet(null)`/置 null 防双 resume，无 GC 泄漏；测试间每案例新对象、`MainDispatcherRule` 每测试 setMain/resetMain；`missing` 文案"缺失"准确不误导。未发现本轮引入的新 Critical/Important。

**7. 执行 agent 证据核实（逐条）**

| 声称 | 核实结果 |
|---|---|
| JVM focused RED：all-success 案例 `expected 删除成功 1 项 but was null` | **一致（无法重放，解析验证）**。本审查不修改文件，但从冻结 diff 的基线代码可精确复算：基线摘要逻辑对全 success 算出"删除成功 0 项，失败 1 项"，随后基线 `load()` 整体替换把 commitResult 抹成 null → 断言必然以该确切文本失败；同时证实第三缺陷（load 抹摘要）真实存在且修复必要。 |
| JVM focused GREEN：MutationInvalidationViewModelTest 7/7 | **独立验证通过**。本机重跑 `:app:testDebugUnitTest --tests ...MutationInvalidationViewModelTest --rerun-tasks` → `tests="7" failures="0" errors="0" skipped="0"`。 |
| RED 反证（移除 `settleScheduler.reset()` 后竞态测试失败，已恢复） | **解析验证成立**。推演见第 4 节——失败是确定性的；`ReviewViewModel.kt` 经 git diff 确认与基线逐字节一致（恢复属实）。 |
| androidTest APK 编译成功、无设备、instrumentation 未执行 | **独立验证通过（编译层面）**。本机重跑 `:app:assembleAndroidTest` → BUILD SUCCESSFUL；无设备连接，边界如实标注。 |
| Server 聚焦删除协议测试 13/13 | **独立验证通过**。本机重跑 `pytest tests/test_delete_fav_service.py tests/test_phase456_api.py` → `13 passed`。 |

无任何声称与代码矛盾。

## Fresh verification

本审查实际执行的命令：`git status`/`git diff HEAD`（工作树 ≡ 冻结 diff）、聚焦 JVM 测试（7/7）、全套件 `:app:testDebugUnitTest --rerun-tasks`（**102 tests / 0 failures / 0 errors / 0 skipped**，较上轮净增 2，与新增测试数吻合，无回归）、`:app:assembleAndroidTest`（BUILD SUCCESSFUL）、Server 聚焦 pytest（13 passed）。未运行 instrumentation/模拟器（无设备）——androidTest 的设备级执行仍是留存的验证缺口（M4）。

**最终结论：CLEAN。** I1/I2 均关闭，无 Critical/Important；4 项 Minor 均不阻塞（M1/M2 建议随下一阶段顺手修复，M4 的设备执行缺口需在 Task 4 最终验收或真机阶段补上）。
