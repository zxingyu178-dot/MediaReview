# MediaReview 1.1 Task 4 Report

日期：2026-08-29

范围：Android 深色设计系统、品牌图标、四入口主导航与共享状态组件

基线：`b56b547dbbef5ef203f7b91cdfe19b1ba61bc739`

## 交付状态

Task 4 的批准设计已实现：应用版本为 `1.1.0-alpha2`/versionCode 5，配对后进入唯一
`main_shell`，根入口严格为 `媒体 / 批阅 / 收藏 / 整理`。媒体默认显示媒体墙，批阅与收藏调用
既有真实业务，整理组合既有媒体库、待删除和重复文件状态/计数。设置、播放器、图片查看器保持
独立全屏目的地。

设计系统使用固定深色核心色和 Compose token；launcher 为仓库内 code-native adaptive vector，
覆盖 standard、round 与 Android 13 monochrome。生产 Compose 的已知 emoji/文本符号控件已替换为
Material Icons 与中文语义，沉浸式界面散落色值已收敛为主题 token。

## TDD 证据

### 首轮 RED

- JVM focused 在生产修改前因 `MainRoot`、`MainShellDestinations`、`rootSelection`、
  `shouldShowMainChrome`、`shellBanner` 等批准合同尚不存在而稳定编译失败。
- `:app:compileDebugAndroidTestKotlin` 在生产修改前因 `MainBottomBar`、共享空态/离线/同步状态组件
  尚不存在而稳定编译失败。

### 审查修复 RED / GREEN

独立审查指出六个 Important gate：重新配对可能保留重复 `main_shell`、Review/Favorites 根切换重载、
Player 文字控件小于 48dp、instrumentation 未真实切根/检查 chrome/back stack、整理页没有实际计数，
以及散落颜色 token 清理不足。

- RED：新增 `InitialLoadGateTest` 后，focused JVM 因 `InitialLoadGate` 不存在而稳定编译失败。
- RED：扩充真实 root 切换/状态恢复、设置 chrome、重新配对 back stack 与播放器 48dp Compose 测试后，
  instrumentation 因 `MainShellScaffold`、`MainRootStateHost`、`PlayerTextMenuButton` 不存在而稳定失败。
- GREEN：focused JVM 合同通过，`:app:compileDebugAndroidTestKotlin` 通过。

对应修复：

1. Settings → Connect 与 Connect → main shell 都以 inclusive `popUpTo` + `launchSingleTop` 原子替换。
2. 首版 Review、Favorites、待删除和重复文件 ViewModel 使用线程安全 `InitialLoadGate`，自动首载幂等；
   后续接受审查发现 mutable 根需要业务版本失效，见下一节。
3. Player 倍速/比例和音轨/字幕文字交互使用 48dp 最小触控 token。
4. Compose 行为测试使用生产 `MainShellScaffold`/`MainRootStateHost`，以可观察测试内容验证根选择与
   `rememberSaveable` 状态恢复；它不冒充真实业务 ViewModel/页面集成。
5. Compose 行为测试导航到设置并确认主 chrome 消失，完整跑 settings/connect/shell 栈并确认根不可再 pop。
6. 整理页收集现有 Library/DeleteQueue/Duplicates ViewModel，显示媒体库选择数、待删除数与重复分组数。

### 独立接受审查 I1–I5 / M1 remediation

候选 `171f66d` 的独立审查结论为 NOT CLEAN：批阅播放器不会随根失活、一次性 gate 冻结收藏/整理快照、
横屏筛选区可挤掉网格、token 使用合同不足、root 测试/报告表述超出证据，以及 `✓` 漏检。

- RED：新增 `ContentInvalidationStoreTest` 与强化 source contracts 后，focused JVM 因
  `ContentInvalidationStore`/`RevisionLoadGate` 不存在而稳定编译失败。
- RED：真实生产 `MainRootStateHost` lifecycle/invalidation harness 与 740x360/font scale 1.3
  responsive Compose 合同先因 callback/layout API 不存在而稳定编译失败。
- focused GREEN：15 tests / 0 failures（3 suites）；`:app:compileDebugAndroidTestKotlin` 成功。

修复后的生产合同：

1. Review root dispose 会调用 `ReviewViewModel.onRootDeactivated()`，取消 settle 并由 PlayerCore 暂停 P0/P1。
2. Favorites/Libraries/DeleteQueue/Duplicates 使用独立业务 revision；只有对应成功变更递增，普通切根不重载，
   返回相关根时由生产 root activation 调用 revision gate 精确刷新。
3. MediaWall 横屏使用单行横向滚动筛选；主壳 chrome 内仍为网格保留可用高度。
4. Task 4 修改过的生产 Compose 文件不保留已有 spacing/typography token 的等价 dp/sp 字面量。
5. integration harness 直接使用生产 `MainRootStateHost`，能够在切离 Review 时观察停用回调，并验证收藏仅在
   对应 revision 变化后的下次激活重载；它仍不声称运行 Hilt/网络业务页面。
6. `✓` 改为纯中文筛选状态并加入 no-symbol 回归列表。

## 首版候选 Fresh 验证（`171f66d`，已被后续接受审查取代）

环境：仓库既有 JDK 21 (`E:\aihome\tools\jdk\jdk-21.0.5+11`)、Android SDK
(`E:\aihome\tools\android-sdk`) 与离线 Gradle 缓存；未访问网络、真实服务、模拟器或设备。

```powershell
.\gradlew.bat --offline --no-daemon `
  :app:testDebugUnitTest :app:assembleDebug :app:assembleAndroidTest :app:lintDebug `
  --rerun-tasks
```

结果：`BUILD SUCCESSFUL in 2m 40s`，91 actionable tasks 全部执行。

- JVM：88 tests，0 failures，0 errors，0 skipped（21 suites）。
- `:app:assembleDebug`：成功，`app-debug.apk` 22,328,344 bytes。
- `:app:assembleAndroidTest`：成功，`app-debug-androidTest.apk` 1,003,465 bytes。
- `:app:lintDebug`：成功，0 errors / 47 warnings。
- `git diff --check`：通过；仅 Git 输出工作树 LF 将按本机策略转 CRLF 的提示，无 whitespace error。

lint warnings 均未阻断构建：大部分为既有依赖可更新提示、Navigation lint registry 与当前 lint API
版本不匹配、targetSdk/backup/v26 目录提示。另有两条 `MonochromeLauncherIcon` 指向 v26 fallback；
标准与 round 的 v33 resource 均已显式引用 `@drawable/ic_launcher_monochrome`，资源合同测试与 APK
构建均通过，因此不把 API 33 元素错误下放到 v26 XML。

## 合同与范围检查

- Task 3 四部分状态继续来自现有 connection repository/ViewModel；未从 URL、UUID 或本地字段推断连接态。
- server API key、URL resolver、Keystore token 与 installation ID 清除语义未改。
- 未进入 Task 5 Paging/media-query 重写、Task 6 playback/HLS 认证状态机，也未改变删除/重复服务端行为。
- 未启动或访问真实 MediaReview/Jellyfin/LAN 服务，未操作设备或部署环境。

## 最终 remediation Fresh 验证

在全部 I1-I5/M1 修复后，从头运行同一条离线 `--rerun-tasks` 流水线。结果：

- JVM：93 tests，0 failures，0 errors，0 skipped（22 suites）。
- `:app:assembleDebug`：成功，`app-debug.apk` 22,344,728 bytes。
- `:app:assembleAndroidTest`：成功，`app-debug-androidTest.apk` 1,012,085 bytes。
- `:app:lintDebug`：成功，0 errors / 47 warnings。
- `git diff --check`：通过；只有本机 LF/CRLF 策略提示，无 whitespace error。

生成时间均为 2026-08-29，证明未复用首版候选的旧 APK/lint 产物。

## 验收与遗留关注

自动化与静态验收通过。Compose instrumentation 测试已构建为 test APK，但按任务约束和当前环境未在
模拟器/真机执行。因此 360x740、390x844、横屏、font scale 1.3、TalkBack traversal，以及 launcher
在不同 OEM circular/squircle mask 下的光学校准仍属于设备侧验收，不能由 JVM/构建证据替代。

`task-4-acceptance-review.md` 的 I1–I5/M1 均已进入对应 regression contract。本报告只陈述已运行的
自动化/构建证据，不把合成 harness 称为真实业务页面，也不声称获得独立二次 CLEAN 结论。
