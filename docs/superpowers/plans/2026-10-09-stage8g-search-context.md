# Stage 8G 实施计划

> **For agentic workers:** Use subagent-driven-development task-by-task; all requirements are in docs/STAGE8G_SEARCH_CONTEXT.md and the user supplied Stage8G A–S.

**Goal:** 完成搜索独立分页、浏览状态恢复、搜索与收藏上下文并交付Alpha10。

**Architecture:** 纯Kotlin SearchPagingController持有StateFlow，ViewModel薄委托；Compose保存两个独立GridState。专用上下文入口冻结按类型过滤的加载窗口。

**Tech Stack:** Kotlin、Coroutines、Jetpack Compose、JUnit、Android instrumentation。

## Global Constraints
- repository.mediaPage(), pageSize50, debounce300ms, near-end items.size-16。
- 搜索不得写_currentList；关闭搜索不得请求首页page1。
- Player/Viewer核心、Review Session、Organize、Duplicate、Delete Commit、ControlHub、部署事务不改。
- Server1.2.2/Contract2；Favorites API不改，记录P1 PERFORMANCE DEBT。
- code17/2.0.0-alpha10；用户APK仅最终门禁后一次生成指定名称。
- 真实手机/家庭LAN未验证，禁止PRODUCTION_READY/REAL_LAN_PASS；结束不进入8H。

## Task 1：独立搜索与界面状态
- [x] 新增 SearchPagingController.kt / SearchUiState，控制代次、取消、首屏/追加错误、去重、重试与favorite状态同步。
- [x] SearchPagingControllerTest.kt覆盖空词、300ms防抖、a/ab/abc、不可取消晚到、页1/2/3、追加失败保留、关闭/换源失效。先建立有效RED再实现。
- [x] V2HomeViewModel.kt搜索薄委托，首页query.search始终null；数据源变化清搜索，关闭不刷新首页。
- [x] SearchPanel.kt互斥历史/提示/结果、独立尾部检测、加载/错误/重试；HomeScreen.kt提升首页与搜索LazyGridState并分别传入。
- [x] 相关JVM及compileDebugKotlin，记录真实日志到review_meta/stage8g，独立审查发现问题并修复，最终统一提交。

## Task 2：上下文与死路径
- [x] openSearchMedia(loadedSearchItems, mediaId) / openFavoriteMedia(favorites, mediaId)以纯上下文builder按类型过滤/去重，目标缺失拒绝。
- [x] Context JVM先RED，覆盖中间B进入队列A/B/C、混合媒体隔离、取消收藏队列不变、目标不在首页。
- [x] HomeScreen/SearchPanel传独立搜索打开回调，FavoritesPage传实际favorite窗口；V2MainScreen调用专用入口。
- [x] 删除MediaNavigator的Folder route/helper、V2MainScreen dead composable、HomeScreen dead parameter、FolderScreen.kt。
- [x] 相关JVM/compile，独立审查发现问题并修复，最终统一提交。

## Task 3：1000媒体fixture与回归
- [x] 复用既有instrumentation fixture风格，新增1000媒体/220匹配及请求计数，不读取真实Server或媒体。
- [x] 验证首屏分页、防抖、追加失败重试、空词历史互斥、搜索视频Back仍原窗口、关闭回原首页位置/筛选/排序。
- [x] 收藏视频及图片A/B/C从B进入/上下条与取消收藏返回列表消失；目标instrumentation先RED再GREEN。
- [x] Android code17/alpha10及四条说明，ReleaseNotes相关验证。

## Task 4：统一门禁与交接
- [x] testDebugUnitTest、lintDebug、connectedDebugAndroidTest，原始日志与XML统计。
- [x] 全分支独立审查，修复必要问题，仅复验受影响项。
- [x] 唯一assembleRelease及指定用户APK，验证版本/签名/SHA256。
- [x] DEV_LOG/TASKS/REVIEW_SUMMARY/Stage8G Handoff，记录所有未执行项、失败与限制；Git提交推送并核验远端。
- [x] 清本次无需保留临时内容，交接后停止。

最终交付执行结果按FINAL HEAD后置生成的Stage8G Handoff记录。本计划勾选表示实施和交付准备闭合，不替代Git/ZIP或真实手机验收证据。

清理例外：本次已停止AVD的temp/stage8g-emulator被自动审批拒绝递归清理，保留并交接，不绕过。
