# V2 Runtime Inventory（Stage 8D）

> 目的：在删除旧代码之前，先把 **MediaReview 2.0 正式运行时** 唯一、真实地登记下来。
> 本文档只记录**当前生产代码实际可达**的类与文件；旧 1.x UI / 旧自研播放器已在本阶段删除。
>
> 生成时间：Stage 8D（versionCode 11 / 2.0.0-alpha4）
> 基线分支：`feature/mediareview-v2-stage8d-integration`

## 0. 判定方法

- 入口只有两个：`MainActivity`、`MediaReviewApp`。
- 「可达」= 从入口经 **Compose 调用 + Hilt 注入** 能到达的真实生产类。
- 旧 `feature/*`（connect / deletequeue / duplicates / favorites / home / library /
  mediawall / player / review / settings / viewer）与 `ui/shell/MainShell`、
  `ui/components/MediaComponents` 经逐目录 `rg/import/reference` 审计后确认：
  **除下列两处公共能力外，生产运行时均不可达**，已在 Stage 8D 删除（见 `04_LEGACY_CLEANUP.md`）。

## 1. Production Entry

| 角色 | 类 / 文件 |
| --- | --- |
| 进程入口 | `com.mediareview.app.MediaReviewApp`（`MediaReviewApp.kt`） |
| 唯一 Activity | `com.mediareview.app.MainActivity`（`MainActivity.kt`） |
| 根 Composable | `com.mediareview.app.feature.v2.V2MainScreen` |
| 全局 DI | `com.mediareview.app.di.AppModule`、`com.mediareview.app.feature.v2.di.V2DataModule` |

`MainActivity` 只做一件事：`MediaReviewTheme { V2MainScreen(hiltViewModel<V2HomeViewModel>()) }`。
**不存在**任何回到旧 Home / MainShell / ConnectScreen / 旧 Review 的分支。

## 2. Production Navigation

| 角色 | 类 / 文件 |
| --- | --- |
| 导航中心 | `com.mediareview.app.feature.v2.MediaNavigator` |
| 一级 Tab | `feature/v2/home/V2BottomNavBar.kt` + `feature/v2/home/V2MainTab` |

路由（`NavHost` in `V2MainScreen.kt`）：

```text
home / review / favorites / organize            （四个一级 Tab，显示 BottomNav）
folder/{folderId}  → FolderScreen
album/{albumId}    → AlbumScreen
player/{mediaId}   → GsyNativePlayerScreen（视频）
viewer/{mediaId}   → V2ImageViewer（图片）
organize/delete | organize/duplicates | organize/duplicates/{groupId} | organize/libraries
```

`MediaNavigator.openMedia()` 统一按媒体类型自动路由（VIDEO→Player / IMAGE→Viewer）。

## 3. Production Repository（数据层）

| 角色 | 接口 | 生产实现（路由） | 分支实现 |
| --- | --- | --- | --- |
| 媒体 | `feature/v2/data/MediaRepository` | `V2MediaRepositoryRouter` | `V2ServerMediaRepository` / `DemoMediaRepository` |
| 批阅会话 | `feature/v2/review/data/V2ReviewSessionRepository` | `V2ReviewSessionRepositoryRouter` | `V2ServerReviewSessionRepository` / `DemoReviewSessionRepository` |
| 整理中心 | `feature/v2/organize/data/V2OrganizeRepository` | `V2OrganizeRepositoryRouter` | `V2ServerOrganizeRepository` / `V2DemoOrganizeRepository` |
| 配对（公共能力） | `core/pairing/PairingRepository` | —（Stage 8D 由 `feature/connect/data` 迁入 `core/pairing`） | — |

辅助：`V2DataMode` / `V2DataModeStore`（DataStore 持久化 Demo/Server 模式）、
`feature/v2/data/server/V2ServerResourceCache`（媒体墙与批阅共享同一份媒体映射）、
`V2ServerSessionBootstrap`、`V2PlaybackResolver`、`V2MediaMapper`。

## 4. Production Player

| 角色 | 类 / 文件 |
| --- | --- |
| 正式播放页 | `feature/v2/player/native/GsyNativePlayerScreen.kt` |
| ViewModel | `feature/v2/player/V2NativePlayerViewModel.kt` |
| 播放源状态 | `V2PlaybackUiState` / `V2PlayerState`（同文件） |
| 源决策 | `feature/v2/player/V2PlaybackSourceController.kt`（`V2PlaybackDecision`） |
| native 状态层 | `feature/v2/player/native/state/**`（10 个文件） |
| native UI 层 | `feature/v2/player/native/ui/**`（6 个文件） |
| 应用级初始化 | `feature/v2/player/gsy/GsyPlayerInitializer.kt`（`MediaReviewApp` 调用，固定 Exo2 内核） |

**唯一正式播放引擎**：GSY Exo2（Compose Native）。Stage 2.1 Wrapper（`gsy/GsyPlayerScreen`）
与 Stage 2.2 自研 Media3 路径（`V2PlayerScreen` / `V2PlayerViewModel` / `PlayerController` /
`core/media/PlayerCore` 等）已删除。批阅内联播放复用同一 `V2PlaybackSourceController` 决策。

## 5. Production Viewer（图片）

| 角色 | 类 / 文件 |
| --- | --- |
| 图片查看器 | `feature/v2/viewer/V2ImageViewer.kt` |
| 缩放 | `feature/v2/viewer/ZoomableImage.kt` + `ZoomMath.kt` |
| 顶栏 / 底栏 / 信息 | `ImageViewerTopBar.kt` / `ImageViewerBottomBar.kt` / `ImageInfoSheet.kt` |
| 上下文状态 | `feature/v2/viewer/state/ImageViewerContext.kt` / `ImageViewerState.kt` |

## 6. Production Review（批阅）

| 角色 | 类 / 文件 |
| --- | --- |
| 批阅页 | `feature/v2/review/V2ReviewScreen.kt` |
| ViewModel | `feature/v2/review/V2ReviewViewModel.kt` |
| UI 状态 | `feature/v2/review/V2ReviewUiState.kt` |
| 稳定门 | `feature/v2/review/ReviewStableGate.kt` |
| 数据层 | `feature/v2/review/data/**`（`ReviewSessionModels`、3 个仓储实现 + Router） |

## 7. Production Favorites（收藏）

| 角色 | 类 / 文件 |
| --- | --- |
| 收藏页 | `feature/v2/V2MainScreen.kt` 内 `FavoritesPage`（同文件私有 Composable） |
| 数据来源 | 复用 `V2HomeViewModel.favorites`（`MediaRepository.favorites()`） |
| 卡片 | `feature/v2/home/MediaCard.kt` |

## 8. Production Organize（整理中心）

| 角色 | 类 / 文件 |
| --- | --- |
| 入口页 | `feature/v2/organize/OrganizePage.kt` + `OrganizeViewModel.kt` + `OrganizeUiState.kt` |
| 待删除 | `organize/delete/**`（`DeleteQueueScreen` / `DeleteQueueViewModel` / `DeleteResultSheet`） |
| 重复媒体 | `organize/duplicates/**`（`DuplicatesScreen` / `DuplicateCompareScreen` / `DuplicatesViewModel` / `DuplicateScanState`） |
| 媒体库 | `organize/libraries/**`（`LibraryManagerScreen` / `LibraryManagerViewModel`） |
| 数据层 | `organize/data/**`（`V2OrganizeRepository` + Router + Server/Demo 实现） |

## 9. Production Settings（设置 / 数据源）

| 角色 | 类 / 文件 |
| --- | --- |
| 数据源 Sheet | `feature/v2/settings/V2DataSourceSheet.kt` |
| 更新日志 | `feature/v2/releasenotes/**`（`ReleaseNotesCatalog` / `ReleaseNote` / `ReleaseNotesStore` / `WhatsNewSheet` / `WhatsNewViewModel`） |

> 旧 `feature/settings/SettingsScreen`（1.x 设置页）已删除；V2 设置是 `V2DataSourceSheet`。

## 10. 入口不被阻塞的启动顺序

```text
MediaReviewApp.onCreate（V2Perf.onProcessStart → Coil → GsyPlayerInitializer.init）
→ MainActivity.setContent → MediaReviewTheme → V2MainScreen
→ HomeScreen 先渲染（DataStore 默认 DEMO，无网络等待）
→ 后台再恢复数据源模式 / 更新日志 / 整理状态
```

`Server health` / `Release Notes` / `Duplicate status` / `Review status`
**都不阻塞首屏**；更新日志是 Overlay，不挡导航。
