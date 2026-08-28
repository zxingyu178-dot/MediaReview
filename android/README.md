# MediaReview Android

Kotlin + Jetpack Compose + Material 3 + Media3，用户可见界面全部中文。

## 1.1.0-alpha2 设计与导航基线

- 仅使用固定深色主题：背景 `#0B1118`、表面 `#141D27`、强调 `#47D7E8`，不启用动态色或浅色回退。
- `ui/theme` 统一提供颜色、四级排版、4/8 间距、圆角、阴影、动效和 48dp 最小触控尺寸 token。
- 配对后只进入一个 `main_shell`；底部固定为 `媒体 / 批阅 / 收藏 / 整理`，切换时保存根页面状态且不复制导航栈。
- `媒体` 直接承载媒体墙；`批阅`、`收藏` 复用既有真实业务；`整理` 汇总媒体库、待删除和重复文件的现有计数。
- 设置、播放器和图片查看器为独立全屏目的地，不显示主壳底栏。服务器 URL 与 installation ID 只在设置中显示。
- launcher 使用仓库内 vector/adaptive 资源，包含 standard、round 和 Android 13 monochrome 图层。
- 图标按钮使用 Material Icons 与中文语义；共享组件覆盖 top/bottom bar、媒体卡片、骨架屏、空态、离线/重试、连接/同步 banner 与 Snackbar host。

## Task 3 连接与安全边界

- TCP 默认端口 `8766`，UDP 发现端口 `35001`；手动 hostname/IPv4/IPv6 入口保留。
- `MediaUrlResolver` 集中解析媒体 URL；server API key 不进入 Android 可见 URL/JSON。
- bearer token 由 Android Keystore-backed AES-GCM 保护；清除连接保留稳定 installation ID。
- UI 分别消费 MediaReview、Jellyfin、同步和认证四部分状态，不从本地 URL/UUID 推断在线或已配对。

## 构建与验证

使用仓库既有 JDK 21、Android SDK 与离线 Gradle 缓存：

```powershell
$env:JAVA_HOME='E:\aihome\tools\jdk\jdk-21.0.5+11'
$env:ANDROID_HOME='E:\aihome\tools\android-sdk'
.\gradlew.bat --no-daemon :app:testDebugUnitTest
.\gradlew.bat --no-daemon :app:assembleDebug :app:assembleAndroidTest :app:lintDebug
```

Task 4 同时构建 Compose instrumentation 行为测试；本检查点未连接模拟器或真机，APK 也未访问真实服务。

## 目录

- `core/model`、`core/network`、`core/datastore`、`core/ui`：共享数据、网络、安全存储与幂等加载门
- `feature/*`：连接、媒体墙、播放器、批阅、收藏、整理子页面与设置
- `ui/theme`、`ui/components`、`ui/shell`：设计 token、共享状态组件与四入口主壳
