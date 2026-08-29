# MediaReview 1.1 Task 4 Independent Acceptance Review

日期：2026-08-29

审查范围：基线 `b56b547dbbef5ef203f7b91cdfe19b1ba61bc739` 至候选
`171f66dc746e9b09acd6bf17578e46597e9c7dd7` 的完整 diff。

结论：**NOT CLEAN / 不接受进入下一 Task**。未发现 Critical；发现 5 个 Important、1 个 Minor。

## Findings

### Critical

无。

### Important

#### I1. 切离“批阅”根不会暂停或释放正在播放的视频

- `android/app/src/main/java/com/mediareview/app/ui/shell/MainShell.kt:134-151` 通过条件组合移除当前根内容，
  但 `ReviewScreen` 的 ViewModel 仍归属于同一个 `main_shell` back-stack entry。
- `android/app/src/main/java/com/mediareview/app/feature/review/ReviewScreen.kt:124-125` 在页停稳后调用
  `onSettled`，而 `android/app/src/main/java/com/mediareview/app/core/media/PlayerCore.kt:134-162`
  明确对目标播放器调用 `play()`。
- 唯一停止两个播放器的路径在
  `android/app/src/main/java/com/mediareview/app/feature/review/ReviewViewModel.kt:378-381` 的
  `onCleared()`；普通的 root 切换不会清除这个 ViewModel，也没有 `DisposableEffect` 或 root-deactivation
  回调执行 pause/stop。

影响：用户从“批阅”切到媒体、收藏或整理时，视频音频可继续在不可见页面播放，播放器资源也一直保留到
整个主壳被销毁。需要在 Review root 失活时显式暂停/停止，并增加真实切根回归测试。

#### I2. `InitialLoadGate` 把跨根更新和整理计数永久冻结在旧快照

- `android/app/src/main/java/com/mediareview/app/feature/favorites/FavoritesViewModel.kt:26-39` 首次
  `claim()` 后永不再自动读取；Review 中新增/取消收藏后切回已访问过的收藏根，不会看到变化，且正常态没有刷新入口。
- `android/app/src/main/java/com/mediareview/app/ui/shell/MainShell.kt:230-240` 为整理页创建并仅首载
  `LibraryViewModel`、`DeleteQueueViewModel`、`DuplicatesViewModel`。用户进入独立的 library/delete/duplicates
  destination 时会得到该 destination 自己的 ViewModel；保存、恢复或删除后返回，主壳中的旧 ViewModel 不会刷新。

影响：先访问整理再去修改媒体库/待删除/重复项，返回后的“真实计数”仍是修改前数字；先访问收藏再在批阅中修改
收藏也会永久显示旧列表。旧 gate 的“避免切根重复加载”虽已实现，但状态一致性和“真实计数”仍未关闭。应采用共享
可观察状态/失效信号，或仅在相关业务发生变化、从独立 destination 返回时刷新，而不是每次普通切根都重载。

#### I3. 媒体根在 740x360 横屏下会被固定筛选区挤掉主要内容

`android/app/src/main/java/com/mediareview/app/feature/mediawall/MediaWallScreen.kt:118-171` 把搜索框、
四个筛选按钮（`FlowRow` 每行最多 2 个）、列数文字和 Slider 全部作为固定高度子项放在网格前，只有媒体网格使用
`weight(1f)`。在主壳 TopAppBar/NavigationBar 已占用高度的 740x360 横屏中，固定控件的最小触控高度已超过剩余
内容高度，`Box(weight(1f))` 可被压到 0 或近 0，媒体墙不可用；font scale 1.3 会进一步恶化。

这违反 brief 对 360x740、390x844 的 portrait/landscape 可用性要求。当前没有相应尺寸的 Compose layout test；
需要横屏响应式布局（例如折叠/横向滚动筛选、可滚动 header 或横屏专用 arrangement）并补尺寸回归。

#### I4. 已定义的 spacing/typography token 没有成为 UI 单一来源

`android/app/src/main/java/com/mediareview/app/ui/theme/Dimensions.kt:5-10` 已定义 4/8/16/24/32dp token，
`android/app/src/main/java/com/mediareview/app/ui/theme/Type.kt:8-32` 也定义四级排版；但改动过的生产 Compose
文件仍有 48 处对应硬编码。例如：

- `android/app/src/main/java/com/mediareview/app/feature/connect/ConnectScreen.kt:83-100` 继续直接使用
  `24.dp`、`8.dp`；
- `android/app/src/main/java/com/mediareview/app/feature/settings/SettingsScreen.kt:70-113` 继续直接使用
  `24.dp`、`16.dp`、`8.dp`；
- `android/app/src/main/java/com/mediareview/app/feature/player/PlayerScreen.kt:497-517` 和 `:602` 继续直接使用
  `14.sp`，没有复用 `MaterialTheme.typography.labelLarge`。

因此“有 token”测试只证明声明存在，没有证明批准的设计系统被采用；不满足“no hard-coded screen colors/sizes
where a token exists”。

#### I5. Compose root 测试和 Task 4 report 把合成内容误报为真实主壳内容

`android/app/src/androidTest/java/com/mediareview/app/ui/shell/MainShellComposeTest.kt:76-101` 的切根测试只组合
`MainShellScaffold` + `MainRootStateHost`，四个根统一渲染本地 `Button`/计数器；它没有组合
`MainShellScreen`，也未验证 Media/Review/Favorites/Organizer 的真实映射、ViewModel 生命周期、跨根加载或 I1/I2。
但 `.superpowers/sdd/task-4-report.md:45` 声称该测试验证了“真实 shell 内容切换”，`:86` 又声称 findings 已全部修复。

设置 chrome 与 reconnect/back-stack 测试确实使用了真实 `NavController`，但 prior gate 的“真实 root 内容与状态”仅被
合成 harness 覆盖，报告需要收窄表述，并补能捕获 I1/I2 的真实主壳集成测试。

### Minor

#### M1. 仍有可见文本符号控件，且 no-symbol 回归漏检

`android/app/src/main/java/com/mediareview/app/feature/mediawall/MediaWallScreen.kt:159-161` 用
`"未点赞 ✓"` 表示筛选状态；`android/app/src/test/java/com/mediareview/app/Task4SourceContractTest.kt:46-64`
的禁用列表没有 `✓`，因此测试仍会通过。这仍属于 brief 要求替换的 visible text-symbol control。建议改为纯中文状态
文案或 Material Icon + 中文 semantics，并让回归覆盖该符号。

## 六个既有 Important gate 复核

1. reconnect 重复 `main_shell` / Back：**关闭**。Settings → Connect 与 Connect → main shell 均做 inclusive
   `popUpTo` + `launchSingleTop`；Compose NavController 测试覆盖完整循环。
2. root state / reload：**部分关闭**。`rememberSaveableStateHolder` 和 `InitialLoadGate` 避免普通切根重载，但产生
   I1/I2 的生命周期与状态失效问题。
3. Player 文字菜单 48dp：**关闭**。倍速/比例使用 `PlayerTextMenuButton` 的 48dp minimum；音轨/字幕项同样
   `sizeIn(minHeight = 48dp)`，instrumentation 合同已构建。
4. 整理真实计数：**部分关闭**。初次载入使用真实 Library/DeleteQueue/Duplicates ViewModel，但修改返回后会因 I2
   显示旧快照。
5. 真实 Compose navigation/chrome：**部分关闭**。settings chrome、reconnect/back-stack 是真实 NavController 测试；
   root 内容仍是 I5 的合成 harness。测试 APK 只完成构建，未在 emulator/device 执行（brief 允许无设备时只构建）。
6. docs/report：**部分关闭**。所需 README/ARCHITECTURE/TASKS/DEV_LOG/report 均存在，但 report 对 I5 覆盖度和
   findings 全部关闭的表述不准确。

## 其余合同核对

- **通过静态/构建核对：**批准的 7 个核心色、固定 dark scheme、无 dynamic/light fallback；版本从 base 的
  versionCode 4 单调增加为 5，versionName 为 `1.1.0-alpha2`；app label 为 `家庭媒体管家`；standard/round/v33
  monochrome adaptive icon 均存在且为 code-native vector；技术 server URL/installation ID 只在 Settings；
  四个根顺序正确；settings/player/viewer 是主壳外独立 destination；Task 3 四部分连接状态仍由 connection model
  提供；完整 diff 未涉及 server、数据库、部署、Task 5 或 Task 6 文件。
- **未通过：**横屏布局（I3）、token 使用合同（I4）、visible symbol（M1）。
- **设备侧未验证：**360x740/390x844 实机渲染、font scale 1.3、TalkBack traversal、OEM icon mask 光学校准、
  instrumentation 实际执行；不能用 APK 构建代替这些结论。
- report 中的生产修改前 RED/TDD 顺序是历史陈述；候选为单一实现提交，本次只读审查不能独立重放其发生顺序。

## Fresh verification

在候选 `171f66d` 上运行：

```powershell
$env:JAVA_HOME='E:\aihome\tools\jdk\jdk-21.0.5+11'
$env:ANDROID_HOME='E:\aihome\tools\android-sdk'
.\gradlew.bat --offline --no-daemon `
  :app:testDebugUnitTest :app:assembleDebug :app:assembleAndroidTest :app:lintDebug `
  --rerun-tasks
```

结果：`BUILD SUCCESSFUL in 2m 41s`，91 actionable tasks 全部执行。

- JVM：88 tests，0 failures，0 errors，0 skipped（21 suites）。
- `assembleDebug`：成功；`app-debug.apk` 22,328,344 bytes。
- `assembleAndroidTest`：成功；`app-debug-androidTest.apk` 1,003,465 bytes。
- `lintDebug`：成功，0 errors / 47 warnings。
- `git diff --check b56b547..171f66d`：通过。
- 验证后工作树：干净（写本报告前）。

构建门禁通过不覆盖上述交互、状态一致性和合同 findings；本候选不能给出 CLEAN。
