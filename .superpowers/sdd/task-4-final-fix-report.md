# MediaReview 1.1 Task 4 Final Fix Report

日期：2026-08-30

基线：`c4a7d3aff523a8e84b9b26e46fa2a8834d3136ea`

实现提交：`f560d29650ddec41525a372be97fa19dcfc5d20b`

范围：仅 Android Task 4 F1/F2/F3。未修改 server、deployment、运行中的旧服务、用户数据、
Task 5 paging/media query 或 Task 6 playback/HLS 状态机。

## 根因与修复

### F1 Review deactivation cancellation boundary

- 根因一：`onRootDeactivated()` 只 cancel settle job，没有使 `LatestWinsScheduler` 当前 token 失效；
  若下层把取消转成普通返回，旧协程仍能同步通过 token 检查并调用 settle。
- 根因二：`MediaRepository` 多个 suspend 路径使用普通 `runCatching`，会把
  `CancellationException` 包成失败 Result，再由 `getOrNull/getOrDefault` 转为普通结果。
- 修复：失活顺序改为 scheduler `reset()` → cancel job → `deactivateReview()`；所有 repository
  suspend failure boundary 统一使用 `runSuspendCatching`，先重新抛出取消，再处理普通失败。
- 回归：真实 `ReviewViewModel` + 可控挂起 repository + fake playback。挂起期间失活后，即使旧 lookup
  在 `NonCancellable` 中恢复也不会 settle；重新进入后新 token 可 settle。latest-wins 与 position
  channel 代码路径未改变。

### F2 Complete mutation-to-content invalidation graph

- 根因：content area 缺少 Media；mutation 在各 ViewModel 内零散递增单一区域；final delete 没有把
  Media/Favorites/Duplicates 纳入影响图，且任意非 null commit 都会失效。
- 修复：新增 `ContentArea.Media` 与 `ContentMutation` 显式矩阵：
  - LibrarySelection → Libraries + Media
  - Favorite → Favorites + Media
  - DeleteQueue → DeleteQueue
  - FinalDelete → DeleteQueue + Media + Favorites + Duplicates
- 只有已确认成功且确有变化时失效。Library 相同选择为 no-op；favorite/enqueue/dequeue 返回 false
  不失效；final commit 只有至少一个 `deleted` 或 `missing` 才按完整下游矩阵失效，允许部分成功。
- `MediaWallViewModel` 使用 Media revision gate；生产主壳激活 Media 时刷新现有实例的 libraries 与
  当前 query/filter，不重建 ViewModel。搜索流跳过初始空值，避免初始化重复请求。

### F3 Production shell integration coverage

- 新增窄 seam：`MediaDataSource`、`ReviewPlaybackController`、`MediaWallSettingsDataSource`，以及
  `MainShellScreen` 的 connection/root-content test override。生产 Hilt 构造仍使用原有
  `MediaRepository`、`PlayerCore`、`MediaWallSettingsStore`。
- `MainShellProductionIntegrationTest` 直接组合生产 `MainShellScreen`，点击生产导航项，并注入真实
  Review/MediaWall/Favorites/Library/DeleteQueue/Duplicates ViewModel 与受控 fake。
- 覆盖 Review root 切离调用真实 `onRootDeactivated()`、成功收藏 mutation 推进 Favorites/Media、
  返回生产 Media root 恰好一次 revision reload、无关切换不重载。
- 生产 Media 分支显式将传入的 `MediaWallViewModel` 交给 `MediaWallScreen`。
- 删除 F1/F2 的源码字符串行为断言；source contract 只保留纯视觉、token、资源与可见符号规则。

## TDD 证据

### RED

1. Media content area：`1 test completed, 1 failed`，
   `ContentInvalidationStoreTest.mediaIsARevisionedContentArea` 在第 11 行断言失败。
2. F1/F2 real ViewModel focused：`5 tests completed, 5 failed`：
   - 旧 settle 在 root 失活后仍落地；
   - Media area 不存在；
   - no-op library save 仍推进 revision；
   - all-failed final commit 仍推进 DeleteQueue revision。
3. Repository cancellation 反证：临时恢复旧吞取消实现后，
   `MediaRepositoryCancellationTest.suspendFailureBoundaryRethrowsCancellation` 为 `1 failed`。
4. Media 精确一次刷新：新增测试首次得到 expected 1 / actual 2，定位为搜索流初始空值二次 reload。

### GREEN

- 聚焦 F1/F2/Media/repository：11 tests，0 failures。
- JVM 全量：100 tests，0 failures，0 errors，0 skipped（26 suites）。
- `MainShellProductionIntegrationTest` 编译并打入 androidTest APK；见设备限制。

## Fresh Android 验证

环境：JDK 21 `E:\aihome\tools\jdk\jdk-21.0.5+11`、Android SDK
`E:\aihome\tools\android-sdk`、offline Gradle；未访问网络或真实服务。

```powershell
.\gradlew.bat --offline --no-daemon `
  :app:testDebugUnitTest :app:assembleDebug :app:assembleAndroidTest :app:lintDebug `
  --rerun-tasks
```

结果：`BUILD SUCCESSFUL in 2m 5s`，91 actionable tasks 全执行。

- JVM：100 tests / 0 failures / 0 errors / 0 skipped（26 suites）。
- debug APK：`app-debug.apk`，22,344,728 bytes。
- androidTest APK：`app-debug-androidTest.apk`，1,019,345 bytes。
- lint：0 errors / 47 warnings。
- `git diff --check`：通过；仅有本机 LF→CRLF 策略提示，无 whitespace error。

## 文件与范围

- 生产：content invalidation、MediaRepository cancellation、6 个 Task 4 ViewModel 的可测边界、
  Review lifecycle、Media root activation、MainShell/MediaWall 实例接线。
- 测试：真实 ViewModel mutation/lifecycle/Media revision JVM 测试；生产 MainShell Compose integration。
- 文档：`TASKS.md`、`android/README.md`、`docs/DEV_LOG.md`、Task 4 状态报告。
- 未触碰：`server/`、`deployment/`、端口/服务注册、旧服务进程、`C:\ProgramData\MediaReview`。

## 限制

- `adb devices` 无连接设备；Compose instrumentation 仅构建，未在模拟器/真机执行。不能据此声称
  production shell integration 在设备上运行通过，也不能替代 740x360、font scale、TalkBack 或 OEM
  launcher 的设备验收。
- 未访问真实 MediaReview/Jellyfin/LAN，未验证真实网络取消时序；JVM 使用可控 fake 确定性覆盖竞态。
- lint 的 47 条 warning 与基线数量相同，未阻断构建；本任务没有扩展处理既有 lint debt。
