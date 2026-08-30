# MediaReview 1.1 Task 4 / Task A 修复执行报告

日期：2026-08-30 | 执行基线：`f0c797c`（Task 0A 之后）→ 工作树
冻结 diff：`.superpowers/sdd/review-f0c797c-taska.diff`（4 文件，+311/-35）
独立审查结论：**CLEAN**（`.superpowers/sdd/task-4-task-a-independent-review.md`）

## 修复内容

### I1 最终删除协议错配（closed）

- 新增共享解析器 `DeleteOutcomeStatus`（`core/model/ApiModels.kt`）：
  `Success(changed=true,successful=true)`、`Missing(changed=true,successful=false)`、
  `Failed(changed=false,successful=false)`、`Unknown(changed=false,successful=false)`；
  `fromWire()` 大小写宽容，未知值 fail-closed 归 Unknown。
- `DeleteQueueViewModel.commit()` 全部改走解析器：全成功批次推进
  `ContentMutation.FinalDelete`（DeleteQueue/Media/Favorites/Duplicates 四区 revision）；
  全失败/全未知不推进。摘要按合同统计：success 计成功、missing 单列"缺失"、
  failed/Unknown 计失败，空 outcome 显示"删除完成"。
- **第三个真实缺陷（执行中发现）**：基线 `commit()` 设置摘要后调用的 `load()` 用
  `DeleteQueueUiState(...)` 整体替换状态，`commitResult` 被立即抹成 null——摘要从未真正
  显示过。RED 证据即 `expected:<删除成功 1 项> but was:<null>`。修复：`load()` 改用
  `_ui.update{copy(...)}` 保留未清除的摘要。

### I2 生产主壳 settle 竞态测试缺口（closed）

- `MutationInvalidationViewModelTest.kt` 新增 JVM 竞态测试
  `reviewRootDeactivationTokenGuardsInFlightSettleResumption`：fake `loadPlayback` 挂起在
  **不可取消** `suspendCoroutine` 停车点（可取消的 CompletableDeferred.await 会被 cancel()
  直接杀死，无法复现"响应晚于取消到达"的生产窗口）；取消后释放，已恢复协程必须被失效
  token 拦截（旧 settle/play=0），重新 settle 新 token 落位并播放（1/1）。
- `MainShellProductionIntegrationTest.kt` 新增生产壳竞态测试
  `productionShellGuardsInFlightSettleAcrossRootSwitch`：生产 `MainShellScreen` + 真实
  `ReviewViewModel` + 真实底栏切根失活路径（`MainRootStateHost` → `onRootDeactivated`）；
  fake 提供真实队列项（1 条 video）；Review 根按钮触发真实 `onSettled(0)`；断言 deactivate=1、
  旧 settle/play/prepare=0；重入批阅后新 token settle/play=1/1。

## TDD 证据（真实命令与输出）

1. 删除合同 RED（实现前）：`:app:testDebugUnitTest --tests "*MutationInvalidationViewModelTest"`
   → 5 tests, 1 failed：`outcome={a=success} 摘要 expected:<删除成功 1 项> but was:<null>`。
2. 实现后 focused GREEN：同命令 → 7 tests / 0 failures（含解析器测试与 JVM 竞态测试）。
3. RED 反证：临时移除 `onRootDeactivated()` 的 `settleScheduler.reset()` →
   `reviewRootDeactivationTokenGuardsInFlightSettleResumption` 失败
   （`旧 settle 不得落地 expected:<0> but was:<1>`）；随后恢复，`ReviewViewModel.kt`
   与基线逐字节一致（git diff 为空）。
4. Server 删除协议聚焦：`pytest tests/test_delete_fav_service.py tests/test_phase456_api.py`
   → 13 passed（确认 `success` 是唯一合同）。
5. Android 全量四目标：`--offline --no-daemon :app:testDebugUnitTest :app:assembleDebug
   :app:assembleAndroidTest :app:lintDebug --rerun-tasks` → BUILD SUCCESSFUL in 3m 54s
   （91 tasks 全执行）；JVM 102 tests / 0 failures / 0 errors / 0 skipped；debug APK
   22,344,728 bytes；androidTest APK 1,023,373 bytes；lint 0 errors。
6. Server 全量与 ruff：本阶段未改 Server 代码；全量 273 passed + ruff check/format 全绿
   （Task 0A 回合实测，见 review_meta/server_*.txt）。

## 执行边界（必须如实报告）

- **instrumentation 未实际执行**：ADB 无连接设备、无模拟器/系统镜像。新生产壳竞态测试
  仅经 `assembleAndroidTest` 编译验证（Kotlin 编译 + APK 打包成功）。设备级执行属于
  Task G 真机验收，未以构建成功冒充执行证据。
- RED 反证在 JVM 层完成并记录失败输出；androidTest 层的反证按推演成立
  （独立审查第 4 节确认确定性），但同样未在设备执行。

## 独立审查 Minor 结论（不阻塞，遗留到后续阶段）

- M1：commit 网络失败（result=null）仍显示"删除完成"（基线即有，非本轮回归）——建议
  下阶段改为失败文案并保留队列。
- M2：Unknown 线协议值在摘要中与 failed 合并计"失败"，建议单列"未知 N 项"。
- M3：androidTest 计数器统一加 `@Volatile`（当前经 compose test rule 同步实际安全）。
- M4：androidTest 设备执行缺口（同上）。

## 结论

I1/I2 均关闭；新独立审查 0 Critical / 0 Important（CLEAN）。Task 4 达成进入完成状态的
自动化与独立审查条件；设备级 instrumentation 与真机验收按交接规则继续作为 Task 4 最终
完成的前置（记录于 M4 / HANDOFF_STATUS 边界）。
