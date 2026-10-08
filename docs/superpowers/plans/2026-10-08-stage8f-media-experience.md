# Stage8F 媒体体验实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development。每项独立审查，最后全分支审查。

**Goal:** 在既有Android架构中一次补齐Server雪碧图、Viewer真实待删除和相册错误/跨页浏览。

**Architecture:** 沿用Repository、V2HomeViewModel、Compose和既有Server API；异步工作与状态在Repository/ViewModel，UI不轮询API。三项开发串行避免共享文件冲突；模拟器和发布验收统一收尾。

**Tech Stack:** Kotlin、Compose、coroutines、Retrofit/MockWebServer、Coil、JVM/Android instrumentation、Gradle。

## Global Constraints

- Base HEAD 98a03527c2aea2855de034f7ceb472a2c3c666a5；branch feature/mediareview-v2-stage8f-media-experience。
- Server继续1.2.2，API Contract继续2；无明确bug不改server/app/**，只跑sprite/task合同targeted tests，不重复561项全量。
- ControlHub BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT保持；不改upgrade-aihome-managed.ps1/IPC/lease，不新建8E.3。
- 最终Android versionCode16/versionName2.0.0-alpha9；只在完成后一次assembleRelease产出MediaReview_2.0.0-alpha9_stage8f.apk，不重签旧APK、不安装真实手机。
- 开发相关JVM/compile/目标instrumentation，最后统一四个Android gate。
- Real Phone/Real Home LAN NOT TESTED且本阶段NOT REQUIRED；只CODE_READY/READY_FOR_USER_VALIDATION，不PRODUCTION_READY。
- 用户APK不邮件发送；重要Handoff按既有授权发QQ；完成停止等待审查。

## Task1：服务器雪碧图正式接通

Files: MediaRepository.kt、DemoMediaRepository.kt、V2MediaRepositoryRouter.kt、V2ServerMediaRepository.kt；home/V2HomeViewModel.kt、MediaCard.kt、HomeScreen.kt、SearchPanel.kt；新增相关JVM和目标instrumentation。

Interfaces: suspend prepareSprite(mediaId:String):SpritePrepareResult；结果Ready(uri,manifest)/Failed(message)，UI有Idle/Preparing/Ready/Error。同步spriteUri/Manifest只读已Ready缓存。命名细节可遵循既有模型。

- [x] MockWebServer先写red：ready/pending→succeeded/failed/cancelled/network/missing manifest/task/cache/server switch/invalid DTO/finite timeout/client cancel。
- [x] Repository ensure→有限task轮询→manifest正确单位映射；经MediaUrlResolver解析，同server+缓存generation，拒绝跨身份晚到结果；Demo立即Ready。
- [x] VM preview job控制Preparing/Ready/Error；滚动/切数据源/离页取消客户端job，无cancelTask；卡片不以hasSprite阻断首次长按，失败保留poster/普通点击。
- [x] 相关JVM+compile绿色，保留red/green原始日志；独立审查并修复后主提交 feat(android): connect V2 server sprite preview。

## Task2：Viewer真实待删除

Files: viewer/state/ImageViewerState.kt、viewer/V2ImageViewer.kt、viewer/ImageViewerBottomBar.kt；home/V2HomeViewModel.kt及独立Viewer pending状态模型；相关JVM/UI tests。

Interfaces: ViewerPendingDeleteState Loading/Ready(ids)/Error；VM进入reload、setPendingDelete确认成功后更新；ImageViewerState仅currentIndex/uiVisible。

- [ ] 先red验证已有pending、加入/撤销成功与失败、读取失败非empty、退出重开repository真值、与Organize双向一致。
- [ ] VM负责读取/写入、busy防重复、source/session晚到结果保护，取消传播；UI禁用未知/正在写状态且可重试，失败中文Snackbar不乐观更新。
- [ ] 删除本地fake pending与旧测试，更新实际行为断言；相关JVM+compile，独立审查；主提交 fix(android): persist viewer pending-delete through repository。

## Task3：相册错误与Viewer续页

Files: home/AlbumScreen.kt、home/V2HomeViewModel.kt、viewer/V2ImageViewer.kt、V2Root导航调用、viewer/state新增Album分页上下文；相关JVM/UI tests。

Interfaces: 明确openAlbumImage(albumId,loadedImages,total,mediaId,loadedPage,sortSpec)，50/page；状态保留窗口、页码/total/errors；接近末尾3项加载nextPage。

- [ ] 先red验证page1错误≠空、total0空、page2失败保留且不增加页码、重试append、封面/书架失败保留旧数据；120照片48→51不中断不跳图且仅50/page。
- [ ] 移除albumPage empty fallback；第一页/更多错误与重试明确，old shelf不清空；Viewer动态ID页数且stable key/currentId，去重和单在途请求，失败保留窗口允许再次重试，不全量。
- [ ] 相关JVM+compile/目标instrumentation，独立审查；主提交 fix(android): preserve album errors and add paged viewer continuation。

## Task4：Alpha9最终回归与交付

Files: android/app/build.gradle.kts、ReleaseNotes数据及测试、Stage8F instrumentation fixture；DEV_LOG/TASKS/REVIEW_SUMMARY/交接九份说明。

- [ ] 添加用户指定Alpha9四条中文说明，首次仅Alpha9、跨版本单Sheet聚合未读；不新增其它模块。
- [ ] 最终一次testDebugUnitTest/lintDebug/connectedDebugAndroidTest/assembleRelease；targeted Server sprite/task合同；用户APK仅最后生成一次。
- [ ] Clean模拟器安装最终Release，更新日志/首页/Server sprite真实sheet帧变化及滚动/缓存/认证、Demo与Review视频、Viewer删除与大相册跨页smoke；原始截图/指标仅测试证据。
- [ ] 核对签名/版本/hash、无Server/ControlHub改动，最终独立全分支审查；主提交 test/release: regression gates and alpha9。
- [ ] Push/remote/clean，生成九说明与git/logs/source/SHA完整Handoff，重要ZIP发QQ、APK不发；完成后停止。
