# MediaReview 1.1 Task 4 Final Acceptance Review

日期：2026-08-29

审查范围：修复前审查提交 `3d9a21a1e5c22aa4ebf1a1d206f91dd441188262` 至候选
`71fc56d3788c25fc9da60431bfa784666445c948`，并以 Task 4 基线
`b56b547dbbef5ef203f7b91cdfe19b1ba61bc739` 复核范围边界。

结论：**NOT CLEAN / 不接受作为远程分包基线**。未发现 Critical；发现 3 个 Important；未发现 Minor。

## Findings

### Critical

无。

### Important

#### I1. 批阅根失活仍存在“暂停后被在途 settle 重新播放”的取消竞态

`android/app/src/main/java/com/mediareview/app/feature/review/ReviewViewModel.kt:262-278` 的 settle
任务先异步解析播放 URL，再用 `settleScheduler.isValid(token)` 决定是否调用 `core.settle()`；但
`:363-367` 的 `onRootDeactivated()` 只执行 `settleJob?.cancel()` 和 `core.deactivateReview()`，没有调用
`settleScheduler.reset()` 令当前 token 失效。

取消任务本身不足以关闭竞态：`android/app/src/main/java/com/mediareview/app/feature/home/data/MediaRepository.kt:135-148`
用 `runCatching { ... }.getOrNull()` 包住挂起的播放信息请求，会同时捕获 `CancellationException`。当用户在 URL
请求期间切离“批阅”时，取消可被该层转成 `null` 返回；协程随后仍可同步通过仍有效的 token 检查，执行
`core.settle()`，从而在 `deactivateReview()` 已暂停 P0/P1 后再次调用播放器 `play()`。

现有 `Task4SourceContractTest` 只检查源码中出现 cancel、pause 与回调接线；Compose harness 也只累计停用回调次数，
均不能覆盖“请求挂起 → root 失活 → 请求取消返回 → 旧 settle 尝试落地”的时序。因此原 I1 未关闭。

验收要求：root 失活必须原子地使所有在途 settle token 失效，并确保取消异常不被当作普通失败吞掉；增加可控挂起
repository/player fake 的确定性回归，证明失活后旧任务即使恢复也不会调用 `settle()`/`play()`，重新进入 Review 后的
新 token 仍可正常播放。

#### I2. revision 失效图遗漏 Media 及跨业务影响，返回根页面仍会显示旧内容

`android/app/src/main/java/com/mediareview/app/core/ui/ContentInvalidationStore.kt:8` 只定义
Favorites/Libraries/DeleteQueue/Duplicates，没有 Media。`MainShell.kt:145-154` 激活 Media 时也不执行任何刷新。
因此持久化主壳中的 `MediaWallViewModel` 只依赖其 init 首载，以下成功变更返回 Media 后仍保留旧列表或旧筛选结果：

- `LibraryViewModel.save()` 只递增 Libraries；媒体库选择改变后媒体墙的 libraries/items 不刷新。
- `ReviewViewModel.onLike()` 只递增 Favorites；媒体墙启用“仅看未点赞”时，点赞/取消点赞后返回仍显示旧结果。
- 最终删除成功后媒体墙仍可保留已删除条目。

跨业务失效也不完整。`DeleteQueueViewModel.kt:66-77` 在 commit 返回非空时只递增 DeleteQueue；但既有服务端
`server/app/services/delete_queue.py:141-148` 对 success/missing 会删除 `MediaCacheIndex` 和关联 Favorite。
所以已访问过的 Favorites 与 Duplicates ViewModel 不会被失效：返回收藏可能继续显示已删除媒体，返回整理时重复
分组计数也可能保持删除前快照。该路径正是原 I2 要解决的“独立 destination 修改后，主壳旧 ViewModel 精确刷新”，
因此 I2 未关闭。

验收要求：建立明确的 mutation → affected content areas 依赖矩阵，并只在已确认成功的实际变更后递增所有受影响
revision；Media root 也必须在 activation 时按 revision 精确刷新。至少覆盖 library save、review like/unlike、
delete enqueue/dequeue/commit（含部分成功）对 Media/Favorites/DeleteQueue/Duplicates 的真实 ViewModel 回归。

#### I3. I5 所要求的真实主壳集成回归仍由合成 harness 和源码字符串替代

`MainShellComposeTest.kt:121-170` 直接组合生产 `MainRootStateHost`，但停用处理只是本地整数累加，收藏加载只是本地
`RevisionLoadGate`；它没有组合 `MainShellScreen`，也没有运行 `ReviewViewModel`、`FavoritesViewModel`、
`DeleteQueueViewModel` 或真实/可控 fake repository。`:90-119` 名为 `clickingRootsChangesRealShellContent...` 的测试
仍只渲染本地计数 Button。`Task4SourceContractTest.kt:92-143` 则用字符串包含断言验证失效调用与暂停接线。

上述测试在 I1 的取消竞态和 I2 的失效依赖缺口同时存在时仍全部通过，已经实际证明它们不是相应缺陷的回归门禁。
因此 `.superpowers/sdd/task-4-report.md:122-123` 所称 I1-I5/M1 都已进入对应 regression contract，以及
`TASKS.md:126` 对 I5 integration harness 的完成勾选，超出了当前证据。报告虽然已正确承认 harness 不运行 Hilt/
网络业务页面，但原 I5 要求的、能够捕获 I1/I2 的真实主壳集成覆盖仍未交付。

验收要求：至少用可控 fake repository/player 运行生产 ViewModel 生命周期与 mutation/invalidation 路径；主壳测试需
覆盖生产 `MainShellScreen` 的 root → ViewModel 接线，而不是仅调用 host callback。测试名称、TASKS 和报告必须与
实际覆盖层级一致。

### Minor

无。

## I1-I5 / M1 closure matrix

| 原 finding | 最终复核 |
|---|---|
| I1 Review 失活停播 | **未关闭**：pause 已接入，但旧 settle token/取消传播竞态可重新播放。 |
| I2 跨根失效与整理计数 | **未关闭**：基本 revision gate 已有，但 Media 与 delete-commit 跨区域依赖缺失。 |
| I3 740x360 横屏布局 | **静态/编译层关闭**：横屏筛选改为单行横向滚动，结构为 grid 保留 weight 高度；设备渲染未验证。 |
| I4 token 单一来源 | **关闭（Task 4 修改范围）**：对应 production Compose 文件的等价 dp/sp 字面量已清理并有 source contract。 |
| I5 真实主壳测试/报告 | **未关闭**：报告已收窄，但行为测试仍未运行生产 shell + ViewModel 集成路径。 |
| M1 `✓` 符号控件 | **关闭**：可见符号已移除并加入生产 Kotlin source regression。 |

## Scope and regression review

- `3d9a21a..71fc56d` 共 27 个文件，范围为 Android Task 4 生产代码、测试及项目状态文档。
- 未修改 `server/`、`deployment/`，未进入 Task 5 Paging/media query 或 Task 6 playback/HLS 状态机。
- `git diff --check 3d9a21a..71fc56d` 通过。
- 审查开始及 fresh 验证结束前工作树均干净；本报告是唯一计划提交的新增文件。

## Fresh verification

环境：JDK 21 `E:\aihome\tools\jdk\jdk-21.0.5+11`、Android SDK
`E:\aihome\tools\android-sdk`，离线 Gradle；未访问真实 MediaReview/Jellyfin/LAN 服务。

```powershell
.\gradlew.bat --offline --no-daemon `
  :app:testDebugUnitTest :app:assembleDebug :app:assembleAndroidTest :app:lintDebug `
  --rerun-tasks
```

结果：`BUILD SUCCESSFUL in 3m 55s`，91 actionable tasks 全部执行。

- JVM：93 tests，0 failures，0 errors，0 skipped（22 suites）。
- `:app:assembleDebug`：成功；`app-debug.apk` 22,344,728 bytes。
- `:app:assembleAndroidTest`：成功；`app-debug-androidTest.apk` 1,012,097 bytes。
- `:app:lintDebug`：成功，0 errors / 47 warnings。
- ADB：无连接设备；instrumentation test APK 仅完成构建，未执行。

构建、JVM 与 lint 通过只证明当前可编译的自动化门禁通过，不能覆盖上述取消时序、跨业务失效和真实主壳集成缺口。
在 I1-I3 修复并重新独立验收前，不应把 `71fc56d` 打包为无需复查的远程 Agent 开发基线。
