# MediaReview 1.1 Task 4 Post-fix Independent Review

日期：2026-08-30

审查范围：基线 `c4a7d3aff523a8e84b9b26e46fa2a8834d3136ea` 至候选
`65386413dd515ef5d11574a4c5fd66f383715d04`。审查输入为冻结包
`.superpowers/sdd/review-c4a7d3a..6538641.diff`，并独立追踪候选树中的生产调用路径、测试和服务端既有协议。

结论：**NOT CLEAN**。未发现 Critical；发现 2 个 Important；未发现 Minor。

## Findings

### Critical

无。

### Important

#### I1. F2 最终删除把生产成功状态 `success` 错认成 `deleted`，真实成功批次不会失效下游内容

`android/app/src/main/java/com/mediareview/app/feature/deletequeue/DeleteQueueViewModel.kt:81-90`
把成功计数和 `FinalDelete` 失效条件写成 `deleted`/`missing`。但生产服务在
`server/app/services/delete_queue.py:131-141` 明确定义并返回 `success`/`missing`/`failed`，其现有 API
回归也在 `server/tests/test_phase456_api.py:182-183` 断言 `{"del1": "success"}`。

因此，当最终删除批次全部真实删除成功时，`changed` 为 false，DeleteQueue、Media、Favorites、Duplicates
四个 revision 均不推进；返回主壳后仍可看到已经删除的媒体、收藏和重复分组旧快照。若批次同时含 `missing`，
虽然 revision 会因 `missing` 偶然推进，但 UI 仍把所有 `success` 计入失败数。现有
`MutationInvalidationViewModelTest.kt:73-89` 使用了生产不会返回的 `deleted` fake，正好掩盖了协议错配。

修复/验收要求：以服务端实际状态建立单一共享合同，至少把 `success` 与 `missing` 视为实际变更，并正确统计成功/
失败；Android 回归必须输入真实 `success`、`missing`、`failed` 组合，覆盖全成功、全失败、空 outcome、仅 missing
及部分成功，验证 revision 矩阵和用户可见汇总。修复后重跑聚焦 Android 测试、Server 删除协议测试和完整 Android
四目标命令。

#### I2. F3 生产主壳 instrumentation 仍无法捕获 F1 的旧 token 竞态

`android/app/src/androidTest/java/com/mediareview/app/ui/shell/MainShellProductionIntegrationTest.kt:71-89`
确实组合了生产 `MainShellScreen`、点击生产底栏并注入真实 `ReviewViewModel`，但 Review 根内容被 override 为静态文本；
测试没有加载可 settle 的批阅项，也没有调用 `onSettled()` 或挂起 playback lookup。它只在 Review → Favorites 后断言
`ShellPlaybackController.deactivations == 1`。对应 fake repository 在
`MainShellProductionIntegrationTest.kt:138-141` 返回空 playback/空 review queue。

这条测试在删除 `ReviewViewModel.kt:380` 的 `settleScheduler.reset()`、恢复原 F1 缺陷时仍会通过，因为
`:381-382` 的 cancel/deactivate 回调仍在，测试只观察到了 pause 边界，没有观察旧 token 是否还能 settle/play。
独立 JVM 生命周期测试能覆盖 ViewModel 内部 token 竞态，但不能替代 F3 所要求的生产 shell → root deactivation →
真实 ViewModel 取消路径集成门禁。

修复/验收要求：让该 production-shell test 使用可控挂起的 playback lookup 和至少一个真实 Review queue item；在
Review 根启动真实 `ReviewViewModel.onSettled()`，切到另一生产根，释放旧 lookup，并同时断言 deactivate 已调用、旧
settle/play 为 0；重新进入 Review 后的新 token 必须能 settle/play。应做 RED 反证：移除 token reset 时此测试必须
因旧 settle 落地而失败。若仍只能 build test APK，报告必须继续明确 instrumentation 未实际执行。

### Minor

无。

## F1/F2/F3 closure matrix

| 项目 | 最终复核 |
|---|---|
| F1 取消传播/旧 token 竞态 | **生产路径静态关闭，JVM 回归通过**：`onRootDeactivated()` 顺序为 token reset → job cancel → pause；`MediaRepository` 的 suspend failure boundary 重新抛出 `CancellationException`。未验证真实网络取消或设备执行。 |
| F2 mutation → content invalidation | **未关闭**：ContentArea/矩阵、Media activation、favorite/library/delete-queue no-op 基础结构已交付，但 final delete 与生产 `success` 协议错配，真实成功删除不推进任何下游 revision。 |
| F3 production shell integration | **未关闭**：测试确实组合生产 `MainShellScreen` 与真实 ViewModel，也能捕获 Favorite → Media reload 缺失；但不会启动在途 settle，无法捕获 F1 旧 token 竞态。 |

## Seam、Hilt 与范围复核

- 生产 Hilt 构造仍显式接收 `MediaRepository`、`PlayerCore`、`MediaWallSettingsStore`；新增接口只作为内部委托/
  测试 seam。fresh `kspDebugKotlin`、`hiltJavaCompileDebug`、debug/androidTest 构建均成功，未发现编译期 Hilt 图破坏。
- 生产 `MainShellScreen` 的 Media 分支在 `MainShell.kt:172-177` 将同一 `MediaWallViewModel` 实例传给
  `MediaWallScreen`；Media root activation 在 `MainShell.kt:153-164` 通过 revision gate 刷新。
- `c4a7d3a..6538641` 仅修改 Android Task 4 生产/测试及状态文档；未修改 `server/`、`deployment/`，未引入
  Task 5 paging/media-query 或 Task 6 playback/HLS 状态机范围扩张。
- 未运行 instrumentation、模拟器或真机，因此不能确认 Hilt 设备运行时创建、Compose 行为、740x360/font scale、
  TalkBack、OEM launcher 或真实 MediaReview/Jellyfin/LAN 行为。

## Fresh verification

环境：JDK 21 `E:\aihome\tools\jdk\jdk-21.0.5+11`、Android SDK
`E:\aihome\tools\android-sdk`、offline Gradle；ADB 无连接设备。

```powershell
.\gradlew.bat --offline --no-daemon `
  :app:testDebugUnitTest :app:assembleDebug :app:assembleAndroidTest :app:lintDebug `
  --rerun-tasks
```

结果：`BUILD SUCCESSFUL in 3m 4s`，91 actionable tasks 全执行。

- JVM：100 tests / 0 failures / 0 errors / 0 skipped（26 suites）。
- debug APK：22,344,728 bytes。
- androidTest APK：1,019,357 bytes。
- lint：0 errors / 47 warnings。
- F1/F2/Media 聚焦 JVM：8 tests / 0 failures（4 suites）。
- Server 删除协议聚焦：`test_delete_fav_service.py` + `test_phase456_api.py`，13 tests / 0 failures；确认成功状态为 `success`。
- `git diff --check c4a7d3a..6538641`：通过。

这些通过项证明候选可编译且现有自动化为绿，但现有门禁对上述协议错配和 F3 竞态集成缺口存在假阴性；在 I1/I2
修复并重新独立复核前，不接受候选作为 Task 4 CLEAN 基线。
