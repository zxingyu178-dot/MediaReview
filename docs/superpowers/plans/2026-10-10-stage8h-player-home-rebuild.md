# Stage8H Player V2 与首页重建实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. 用户已确定完整设计并授权普通选择自行执行，不再等待分段设计审批。

**Goal:** 完成规格103节与全部自动化门禁，交付唯一Alpha11 APK、Server1.3.0及Stage8H交接。

**Architecture:** 保留MediaRepository、Review Session、Player GSY内核与Server SQLite。增量FolderTree service/cache/DTO；Android独立FolderBrowser、LRU窗口、Coil封面；Full/Review共用pointer方向锁、seek engine与速度状态机。

**Tech Stack:** Python/FastAPI/SQLAlchemy/pytest/ruff；Kotlin/Compose/GSY Exo2/DataStore/Coil/JUnit/instrumentation。

## Global Constraints

- 基线be2ee3be11875df0f13b8af7badf11e261a3a43d；分支feature/mediareview-v2-stage8h-player-home-rebuild，起始clean。
- 不重写Server、播放器框架、Review Session、Delete Queue、Duplicate、Jellyfin Adapter；不引入Room。
- Stage8G独立搜索/分页/收藏Context、Album分页、播放链全部保留。
- Android code18 / 2.0.0-alpha11；Server1.3.0 / Contract2，增加folder_tree_v1。
- 禁止生产部署、真实Jellyfin修改、真实媒体删除、永久删除、清手机数据、修改ControlHub生命周期或直接SCM。
- DORMANT SERVER CAPABILITY：Server sprite保留缓存/DB，Android删除全部首页sprite调用路径。
- PiP、外挂SRT/ASS Deferred；轨道公共API审查后无法稳定实现则隐藏入口并说明，不留假按钮。
- 开发只目标测试；最终全pytest/ruff、testDebugUnitTest/lintDebug/connectedDebugAndroidTest/assembleRelease；唯一用户APK。
- 六个主实现commit；最终push、十文档Handoff，最高CODE_READY / READY_FOR_USER_VALIDATION，不进入下一阶段。

## Task 1 Server层级树（规格37–44,81–82,89,92,95）

**Files:** server/app/services/folder_tree.py（新增）；api/v1/media.py（新/folder-tree与媒体树节点范围查询）；services/media_index.py（缓存失效）；app/__init__.py、health配置（版本能力）；server/tests/test_folder_tree.py（新增）。
**Interfaces:** FolderNode字段folder_id,parent_id,library_id,name,child_count,direct_media_count,recursive_media_count,direct_image_count,cover_url,direct_cover_url；虚拟Library root；opaque id不泄露路径；兼容旧/folders。返回Envelope[list[FolderNode]]。folder_id媒体查询支持目录本身/后代分页，旧flat id行为保留。
- [x] RED合成Movies/Action/SciFi、Photos/2025/2026、UNC/Windows/Linux路径、多Library、无图片、直接媒体/仅子目录、共同前缀折叠、1000媒体100folder4层、缓存命中/同步与selection失效。
- [x] 运行 `.venv/Scripts/python -m pytest tests/test_folder_tree.py -q` 保存RED。
- [x] 实现规范化、ancestor与统计、按(selected libraries,media generation)隔离缓存；只发送metadata、封面版本URL。
- [x] 目标pytest与ruff GREEN，独立规格与质量审查，DEV_LOG更新后commit `feat(server): add hierarchical folder tree capability`。

## Task 2 首页分类与布局（45–46,55–66,85–86）

**Files:** V2MediaMapper、server DTO/MediaApi/Repository、model V2Album与FolderNode、home/FolderBrowserScreen.kt（新增）、HomeScreen/MediaGrid/MediaCard、V2HomeViewModel、偏好DataStore、V2MainScreen导航。
**Interfaces:** folderTree capability独立检测，1.2.2仅分类提示不影响播放；单Library直接一级、多Library先root；最多若干一级chip+全部分类；HomeDisplayMode ONE_COLUMN/TWO_COLUMN。
- [x] RED验证folder parent-child映射、legacy能力降级、显示模式持久化、anchor保持与header阈值。
- [x] 真FolderBrowser面包屑+分页媒体；首页不再flat展开。单/双列同LazyVerticalGrid且保存mediaId/offset；单列16:9封面/标题/文件夹/时长类型。
- [x] NestedScroll累计24dp方向阈值，固定overlay translation header，折叠56dp；保留grid布局与anchor/y。
- [x] 目标JVM/compile及instrumentation，记录真pointer切布局/滚动坐标；审查与commit `feat(android): rebuild home categories and browsing layout`。

## Task 3 缓存、封面及移除Sprite（47–54,67–72,83–84,87–90）

**Files:** AlbumPagingController、home/AlbumScreen/ShelfGrid/V2HomeViewModel/MediaGrid；model V2Album、mapper；新增WindowCache；删除SpritePreviewController/State/Composable与调用及相关测试。
**Interfaces:** 两个LRU各8项；Album保存items/page/total/sort/anchor/updatedAt；Browse key(sourceGeneration,folderId,sort,type)。close保存窗口取消晚到；再进同步返回cache再SWR。
- [x] RED缓存再进即时items、3秒delay无全屏loading、失败留旧、LRU淘汰、generation切源/selection/delete失效、favorite只patch。
- [x] coverUrl从Server直接映射交Coil，不依赖media cache；关闭crossfade；首屏与下一屏低优先级bounded prefetch。
- [x] 无cache skeleton；cached success静默更新/失败轻提示。删除Android sprite全部UI/请求，Server保留。
- [x] 目标JVM/compile/封面真实显示及长按无spriteHTTP UI验收，审查与commit `perf(android): cache albums and browsing windows`。

## Task 4 Full Player共享引擎（3–28,73–76,77–79）

**Files:** player/native/state新增SpeedGestureState、DoubleTapSeekState、GestureDirectionLock、SeekEngine；ui/PlayerSeekBar（Canvas，32dp hit/4dp轨道）；GsyNativePlayerScreen/BottomBar/CenterControls/Sheets/Indicators；player VM endpoint预解析。
**Interfaces:** speed Normal/Temp2x(previous)/Locked2x(previous)，350ms长按、48dp上滑锁、Release/Unlock/FormalSpeedChanged；同pointer锁速度排斥seek/brightness/volume。seek down捕获播放状态/暂停，move仅preview、up只一次seek并恢复。
- [x] RED速度1.0/1.5恢复、锁/解除/haptic一次、seek映射0/25/50/100clamp、方向比例1.2与长按独占。
- [x] 控制顶部返回/标题/更多、底时间+seek、prev/speed/scale/lock/next/fullscreen；播放无中央按钮/暂停单play，完成replay/next不自动。Full tap显隐、3秒播放隐藏/暂停与sheet及gesture例外。
- [x] body双击左右10s累计500–800ms边缘提示、横滑seek提示进度附近、上下亮度音量边缘提示。手动横屏/回竖屏、lock仅unlock、resume15秒/暂停/退出上报、退出无声音。
- [x] 仅rememberGSYPlayerController(url=null,autoPlay=false)由MediaReview统一headers+setUp播放源；poster保留到首帧，300ms后轻loading；稳定后仅endpoint预解析下一条，不启动第二Player。
- [x] 目标真实>=30秒Demo/HTTP视频播放position前进、pointer drag20→70读真实currentPosition、double±10、longpress恢复/锁/解除。审查轨道公共API，支持才实现，否则删所有占位用户入口，Known Issues记录。
- [x] 审查与commit `refactor(android): rebuild full player controls and gestures`。

## Task 5 Review共享手势（29–36,75,80）

**Files:** review真实播放surface/VerticalPager与更多、共享PlayerSeekBar/状态机/gesture engines，目标Review instrumentation。
- [x] RED方向锁水平>1.2倍垂直及垂直>1.2倍水平，确定后整个sequence不改变；LongPress专属。
- [x] Review tap播放暂停，播放中心无按钮/暂停play；常驻细进度2–3dp/32dp hit共享seek。更多正式倍速；全套double/longhold/upLock/unlock与Full同一引擎。
- [x] 旧视频停止/旧source拒绝/Poster到首帧/当前优先稳定播放，长按上滑不翻页；真实水平seek不翻页与垂直翻页不seek。
- [x] 目标instrumentation及审查，commit `feat(android): unify review seek and speed gestures`。

## Task 6 最终门禁、产物与交接（91–103）

**Files:** android build.gradle.kts、ReleaseNotesCatalog/相关测试、Server版本/发布工具、docs/DEV_LOG/TASKS/REVIEW_SUMMARY/Stage8H验收路线与Known Issues。
- [x] 版本18alpha11/Server1.3.0/Contract2/folder_tree_v1，七条真实中文notes与首次/未读聚合/关闭测试。
- [x] 1000媒体100文件夹四层100相册fixture；确认不全量下载媒体，Favorites无证据不优化。
- [x] 完整pytest与ruff，最终JVM/lint/connected统一执行，保留全部失败修复证据；全分支独立审查C0/I0。
- [ ] 唯一assembleRelease并校验签名/版本/hash，MediaReview_2.0.0-alpha11_stage8h.apk；构建并隔离核验Server1.3.0后MediaReview_Server_1.3.0_stage8h.zip，不部署生产。
- [ ] 最终commit `test(release): regression gates and alpha11` 并push，remote/clean/构建输入冻结hash一致。
- [ ] 最终HEAD后生成MediaReview_Stage8H_Handoff_<date>_<sha>.zip：00_HANDOFF/01_ACCEPTANCE/02_TEST_REPORT/03_PLAYER_V2/04_REVIEW_GESTURES/05_FOLDER_TREE/06_HOME_GALLERY_LOADING/07_KNOWN_ISSUES/08_USER_VALIDATION/09_RELEASE_NOTES + logs/git/source/SHA256SUMS；无APK/私密/真实media，zip完整hash。用户仅一条10–15分钟15点路线。

## 验收审计

对STAGE8H_PLAYER_HOME_SPEC.md全部编号逐项记录代码位置、测试命令和真实结果；未有证据的项目保持未完成。所有运行与系统限制如实报告，不以semantics节点存在代替实际pointer手势与真实position。阶段最终状态不包含REAL_LAN_PASS或PRODUCTION_READY。
