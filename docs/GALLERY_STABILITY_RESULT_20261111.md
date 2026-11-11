# 图库稳定性与前台追新：本轮结果

日期2026-11-11，手机开发工作区 `codex/core-flow-polish`，起点 `472d9c3`。本轮不是界面重做或完整实时通知系统交付。

## 前端重做交接

交接单为 [FRONTEND_REDESIGN_HANDOFF_20261111.md](FRONTEND_REDESIGN_HANDOFF_20261111.md)，覆盖手机和电脑核心功能、需求、数据语义及提交/验收标准；允许开发者重排界面，不固定布局、组件与视觉样式。执行范围见 `docs/superpowers/plans/2026-11-11-gallery-stability.md`。

## 手机改动与测试

默认前台增量周期由15秒改3秒；成功空响应不再逐轮降频到60秒，只有失败退避（默认6/12秒上限，回焦或成功重置）。沿用页面可见性门禁、单轮有界增量读取、当前图片保持与新图计数；未实现事件推送、后台常驻或“顶部自动插入”新交互。

新增 `foregroundDefaultChecksNewImagesWithinThreeSecondsEvenAfterEmptyPolls`，验证实际默认周期、连续空响应及离屏停止。修复前真实运行1失败；修复后该项+既有新图保持/查看最新2项+加载策略4项共7通过，XML为VM3/Policy4，均0失败0错误。只运行这些目标，未全量/connected。源文件及对应单测的只读定向审查无未解决Critical/Important。

命令：`:app:testDebugUnitTest --tests '*NsfwReviewViewModelTest.foregroundDefaultChecksNewImagesWithinThreeSecondsEvenAfterEmptyPolls' --tests '*NsfwReviewViewModelTest.galleryNewImagesOnlyNotifyAndReviewUpdatesStayInPlace' --tests '*NsfwReviewViewModelTest.galleryViewLatestSwitchesOldestToNewestAndKeepsDates' --tests '*NsfwLoadingPolicyTest*' :app:assembleDebug --offline --no-daemon --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process'`。

早期几次命令在测试之前失败：旧项目Gradle缓存缺插件、用户缓存native/daemon日志不可写、PowerShell未引用带点号的-P参数。改用既有Gradle8.9/JDK17、项目可写缓存及TEMP/native，用户已有依赖作为只读缓存，正确引用参数后实际运行。未安装工具或修改系统PATH/代理。只读依赖缓存机制参考 [Gradle官方说明](https://docs.gradle.org/current/userguide/dependency_caching.html)。日志分别为忽略temp中的 `foreground-poll-red-*.log`，有效RED为 `foreground-poll-red-actual-20261111.log`，GREEN/Debug为 `foreground-poll-green-debug-20261111.log`。

既有compileSdk/Android metrics权限、Kotlin data copy未来可见性、图标弃用等warning保留，不以修告警扩大范围。测试通过不证明真实网络总耗时在3秒内。

## 电脑图库配套修复

Studio开发工作区修复离屏img释放、旧错误隔离、失效分页加载状态、去重后的页号，以及本地删除导致offset前移/在途晚回包覆盖边界。独立审查发现的Important已关闭。

结果是17项单轮通过后，针对最后的删除并发改动再跑9项通过；最终类型检查与 `npm run build` 成功，不能合并称最终18项单轮全绿。详见Studio `docs/GALLERY_LOADING_RESULT_20261111.md`；项目规则要求Codex修改由Lead审查提交，本轮未commit/push该仓库，未改PR39或部署。

## 验证与发布边界

当前实体手机未连接。电脑现场图片失败仍需区分缺失原文件、损坏、缩略图生成和网络层原因，本轮没有用真实媒体做审核/评分/删除。

Alpha36签名候选和CURRENT_RELEASE保持原样，正式仍Alpha34。测试Debug APK不作为新正式候选或安装交付；后续发布需递增编号、正式签名、源码输入核对。没有生产服务启停、数据库迁移、工作流或新长期对象变更。不声称PRODUCTION_READY/REAL_LAN_PASS，Phone/HomeLAN NOT TESTED。

## Debug及隔离模拟器回执

上述7项及assembleDebug同一次Gradle命令完成，BUILD SUCCESSFUL，7分41秒（51任务16执行）。Debug SHA256 `9df08ad0cc13efd034cf180f28762464d29127d96dff0469ae18806705a03b54`。这是修改后源码的测试Debug，不是原签名Alpha36交付包；原交付SHA256仍为 `679362e77eb0932bfd70d319fcfefef678a865dd42e63e0ec61b8bc67431e2c4`。

已有 `MediaReview_Test`（API35/x86_64）以read-only、无快照保存模式启动。本轮只在其临时覆盖层操作；原Alpha15与当前Debug签名不同，第一次覆盖安装失败后，仅该临时模拟器移除旧测试App再安装成功，核对code43。未操作实体手机或清除原AVD基础数据。启动阶段出现System UI ANR，选择Wait后恢复；不能用此环境评估实体手机流畅度。

使用本轮loopback一次性HTTP夹具，只含24张256px合成色块和内存事件，不接Studio数据库/媒体。实际界面核对：

1. [默认最新](evidence/gallery-stability-20261111/01-latest-default.png)：显示24张、最新在前，HTTP请求sort=created_desc；仅加载可见附近缩略图。
2. [新增提示](evidence/gallery-stability-20261111/02-new-image-notice.png)：加入fixture_25后出现1张新图提示，旧24张及首图颜色/当前位置保持，未插入当前范围。
3. [查看最新](evidence/gallery-stability-20261111/03-latest-after-refresh.png)：主动点击后25张，fixture_25缩略图被请求并排到首位，旧首图成为第二项。
4. [批量选择保持](evidence/gallery-stability-20261111/04-batch-selection-kept.png)：9图页加载就绪，全选显示保留9/淘汰0/未就绪0；加入fixture_26并确认feed游标到2后，仍为原9图和原9个选择。未点击提交，没有任何真实审核/评分/删除。

截图已逐张查看并保存在docs/evidence。完整连续feed请求间隔统计保存在 [emulator-proof.json](evidence/gallery-stability-20261111/emulator-proof.json)：72个间隔，平均3.043秒，范围0.597–6.021秒，包含用户主动刷新后的立即同步。不是生成到显示延迟测量，也不是长时资源性能基准，不能称严格3秒或2秒实时保障。

本次只验上述UI子集，**未验**：本轮Debug的6/10图评分、批量POST/未知回执恢复、磁盘重启/旋转、断网重连、真实桥和大图库长时内存。保留原7项自动化与既有分轮证据，不外推Alpha36整体产品验收。

结束已停止辅助uiautomator（ADB forward为空）、本轮AVD及身份核验的一次性夹具；ADB设备列表为空，5554/5555/18877无监听。生产服务与日用工作流未动。一次性夹具/操作脚本随后清理，验证日志保留在忽略temp，必要截图和摘要已持久化到项目文档。
