# 核心流程打磨记录（进行中）

基线 `e253aa0`，分支 `codex/core-flow-polish`。正式签名 Alpha27/code34 与 Server1.6.5 已构建并完成下述隔离核验，主项目唯一交付指针已更新；生产服务未变，正式APK未安装手机或模拟器，不声称真实手机已经修复。

查询/草稿/同步源码 `c242d99`、后续交互及Alpha27源码 `fbf554a`、发布输入源码 `c7c53eb` 已push；`codex/core-flow-polish` 与日常 `feature/mediareview-local-only` 均已对齐c7c53eb。后续收口提交只改状态文档，不改变已测试业务/发布输入。

## 已确认原因与修改

- 相册：隔离 5 万条 SQLite 索引复现秒级查询。旧目录摘要分别做计数和封面扫描，重复计算规范化目录；旧归属过滤先物化全部可见 ID，拖慢有序分页。改为一次物化目录、窗口聚合计数与封面，以及相关 EXISTS 归属过滤。保留多库去重、仅无归属记录时兼容旧 library_id、图片封面排序及筛选边界，无数据库迁移。
- HTTP：目录树、摘要、媒体列表路由原为 async，却直接执行同步 SQL。三个无 await 的读取路由改为同步，由 FastAPI 工作线程执行，避免慢查询挡住异步事件循环。
- 批阅范围：再次选择文件夹会用实际会话范围重置草稿。现保留已打开草稿及包含子目录设置；目录刷新后缺失的选中项保留并报错，阻止创建，不静默放大到全部视频。用户可明确重选或改为全部。
- 生图审核：只读同步与审核共用 busy。拆分 syncing 与写入 busy，增量和加载更多共用单读锁；离页取消手动读取，连接世代隔离旧返回，读取失败/取消不放开写锁。沿用已有严格 revision 合并，旧增量不得撤销新审核结果；同步成功提示不覆盖期间收到的审核回执。

修改集中在 Server 的 `media_index.py`、`api/v1/media.py`，Android 的两个 ViewModel 与对应页面，复用现有测试文件，无 NSFW 修改。

## 验证

证据保留于项目 `review_meta/product-polish-20261031/`（不提交临时 SQLite 或完整日志）。全部媒体样本为隔离合成索引或临时可丢弃文件。

- 修改前：目录摘要 SQL 次数回归失败（2 次而非 1 次）；慢目录查询并发健康探针约 2.03 秒，回归失败。Android 新增 4 项回归全部按目标缺陷失败，3m22s，日志 `android-red.log`。
- Server：`python -m pytest tests/test_folder_tree.py tests/test_media_index_11.py tests/test_review_folder_scope.py tests/test_delete_nonce_11.py tests/test_delete_fav_service.py tests/test_duplicate_persist_11.py tests/test_duplicate_group_id_stability_8d.py -q`，87 项通过（exit 0）。覆盖目录/分页/多库、批阅范围及相关删除 nonce、路径/重复请求、重复扫描行为。沿用现有 Starlette/httpx 弃用警告，未修改依赖。
- Server 接口补充：`python -m pytest tests/test_media_api.py -k 'folders or filter_by_folder or unknown_folder or folder_identity' -o addopts='' -q`，13 项通过、16 项未选，251.02 秒，日志 `server-covers.log`。验证图片优先封面、最新时间及同时间 media_id 排序、纯视频无图片封面、同筛选计数、未知目录拒绝及目录身份。与上面的 87 项是分轮验证。
- Android：`:app:testDebugUnitTest --tests '*V2ReviewViewModelTest' --tests '*NsfwReviewViewModelTest' --tests '*NsfwFeedStoreTest' --offline --max-workers=1 --no-daemon -Pkotlin.compiler.execution.strategy=in-process`，单轮 128 项通过（43+67+18，零失败/错误/跳过），业务编译通过，6m5s，日志 `android-green.log`；新增 4 项故障用例由红转绿。未顺便处理既有图标、编译 SDK、测试命名及旧条件 warning。
- 最终源码性能对照：同一份 5 万条合成索引，24 库/296 目录，原实现摘要 3539.64ms、分页 1300.85ms；工作树最终源码摘要 840.64ms、分页 286.09ms。全部目录摘要（含封面）、定位目录、总数及该相册所测一页 ID 的联合 SHA256 相同（`acb052bc…`）。单库摘要 712.00→351.85ms、分页 409.82→368.77ms，同范围摘要也相同（`a1e865d7…`）；单库 available count 30.07→70.31ms，存在约 40ms 代价，目录定位约 247→252ms 基本不变。不是所有查询都更快，也不是全库逐页验收。原临时试验有不同耗时，以上以实际生产函数源码测量为准；未使用生产数据库，不外推为手机/LAN性能通过。证据 `album-sql-baseline-{24,1}.json`、`album-sql-production-{24,1}.json` 及 profile。

未执行全量测试、connected全套、生产部署、真实媒体删除和评分。正式APK仅构建一次，未为报告重建；其设备证据来自同生产源码的Debug包，正式签名包本轮未安装设备。

## 后续交互验证

后续交互源码已修改：解除生图配对移到更多菜单、确认影响、审核/评分提交时 UI 与 ViewModel 同时禁止解除。新增 JVM 用例在原实现复现提交中仍可清除凭据；原迟到评分保护用例改为模拟真正的凭据失效，并保留请求回执清理与旧回包不能复活页面的检查。`:app:testDebugUnitTest --tests '*NsfwReviewViewModelTest' --tests '*ReleaseNotes*Test' :app:assembleDebug :app:assembleDebugAndroidTest` 单轮 85 项（68+17）通过，编译及两个测试包构建成功，7m38s，日志 `interaction-green-build-retry.log`。与此前 128 项是分轮，不相加称单轮全绿。

视频刷新圈改为真实请求状态；设备回归另发现 `retryList()` 清空当前浏览缓存，慢刷新先把列表变空（14.29 秒的一项设备运行在“保留已有内容”断言失败，不把当时圈仍可见误记为 700ms 问题通过）。现只保留当前可见窗口作为刷新底图，其余窗口依然失效，继续增加代次隔离旧回包；成功替换、失败保留、离页清理、分页不显示顶部圈。随后 6.944 秒设备回归发现顶部刷新与页尾分页双圈，限制页尾圈只用于非刷新分页。最后仅重建 App（2m52s），未改 ViewModel/版本逻辑，复用 85 项；最终调试包上的同一慢刷新用例通过（6.011秒），覆盖等待超过原700ms仍显示唯一刷新圈、内容保留、成功收起、失败保留、离页收起。另运行同类原有启动优先级/收藏惰性2项通过（4.138秒），与前一项分轮，未跑178全套。证据 `refresh-device-green-final.log`、`home-startup-related.log`。

生图确认界面已在隔离模拟器复核并看图：常用页没有裸“断开”；更多菜单→解除→取消，保持第1/6张与READY；再确认才返回配对页。最近一次合成配对后的8条请求中，审核/评分 POST 为0。证据 `unpair-confirm.png/xml`、`unpair-after-confirm.png`、`nsfw-unpair-check.json`。最终调试包配对页文案复核与截图通过：生图服务地址、连接并配对、独立连接说明可见，没有 DataStore/token 内部文案（`pairing-copy-final.png/xml`）；确认逻辑未再变更。真机未连接、NSFW生产项目与进程未改；临时桥已按PID/父进程/命令行核对关闭，两个uiautomator会话均退出，模拟器关闭，ADB设备/forward及测试端口为空。

环境记录：默认调试签名锁目录不可写，采用进程级 `ANDROID_USER_HOME` 指向项目临时目录，未改 ACL 或系统环境。一次 Gradle 选项放错任务导致配置失败，已调整，不计作业务回归。首次模拟器入口遗漏显式 AVD 目录，后续启动又漏了旧测试盘所需参数，ADB 长期离线；核对本聊天的上轮成功命令后精确停掉本次实例，恢复 `-datadir`、SDK 自带 `-encryption-key` 与 `-gpu swiftshader`，约两分钟后 `sys.boot_completed=1`。它们是测试盘启动参数，不能再仅凭记忆拼接；不读取或输出私钥。只读模拟器临时覆盖层因签名不同更换测试 App，未触碰真机或持久测试盘。ADB/模拟器验收以实际设备状态为准，不把这些环境失败归到 App。

## 剩余与边界

发布入口：Server声明/builder统一1.6.5，Android builder统一Alpha27/code34；随包说明不再指向Alpha22或声称生图未接通。保持Server隔离重建，Android移除clean并支持进程级显式Gradle路径，Python使用调用解释器。新增两项回归原实现均失败；发布/部署/健康首轮88通过3失败（两项旧断言、1项脚本策略未执行到SCM），调整旧断言并验证“失败构建撤销receipt，旧缓存不可发布”后91通过、1项SCM排除（65.05秒）。三个相关pytest文件 `-k 'not scm_query_failure' -o addopts='' -q`，日志package-red/green/final.log；未改机器策略，不称SCM失败安全已验证。既有FFmpeg双工具7.1.3-Jellyfin复制哈希一致，非全局安装。

交付：源码c7c53eb，已登记builder执行 `--prepare-only`（串行EXE→Release，APK4m51s）与 `--package-verified`（复核同一二进制，未重建）。Git初次推送多次连接重置，直连亦不可达，先完成本地构建；交付ZIP在源码push确认后生成。未改全局代理/凭据，最后两个分支push成功。真实EXE隔离健康为1.6.5/API2/15cap；另一次独立HTTP合成查询验证新SQL、3媒体/2图片、最新图片封面、图片顺序和实际迁移head0018，无真实媒体访问，实例已关闭。APK原证书SHA256 `592c2595…65973564`、code34及演示媒体0核验通过；ZIP184项文件、包内APK与独立APK相同、主目录复制后逐文件复核通过。证据release-prepare/package.log、exe-sql-result.json。

主项目 `deploy_handoff/` 新产物：APK `MediaReview_2.0.0-alpha27_stage9.apk`（20,830,008字节，SHA256 `a3cc30e0735462f95ebc25d9cc1469397e3f23c9dc113c20d382cc1e6f4fbe52`）；ZIP `MediaReview_Server_1.6.5_stage9.zip`（112,039,672字节，SHA256 `4561c3952e1638e7616c4a03c7f398e4ab459cc304e7b0a64fc6de4ef4e07c86`）。EXE SHA256 `a3017ad3c75d0e1d8fc5e9c1054b3b758b2d9f6d0f564e5cdc9847287417d5a2`。CURRENT_RELEASE已原子切换新配套，旧指针备份保留；旧包未覆盖。

生产于交付收口只读复查仍为ok / 1.6.3 / API2 / 14cap / windows_service，尚不含本批SQL优化；新产物就绪不等于已安装/部署。真实家庭局域网/手机加载、首帧与长期稳定性未复核；相册目录定位仍需按索引聚合，不能承诺大库瞬开。当前目标继续，后续还需新正式APK的设备、对应Server及真实使用验收，不作为产品最终合格结论。


### 2026-10-31 正式 Alpha27 三项反馈复核

ADB仅发现emulator-5584，未发现实体手机。对原发布签名安装使用`adb -s emulator-5584 install -r deploy_handoff/MediaReview_2.0.0-alpha27_stage9.apk`，返回Success，实际包code34/Alpha27。没有清除数据、卸载或重建APK。

隔离Python HTTP夹具（源码Server1.6.5，127.0.0.1:18766，非打包EXE）仅提供合成媒体：媒体库清空后两个Checkbox=false、计数0/2；全选后均true、计数2/2，未提交真实库选择。相册书架显示1张合成图片的目录，进入后照片节点出现，摘要和缩略图HTTP200；只证明小型隔离库能显示，不证明真实大库性能。生图使用夹具配对，图片成功显示，图片区域[0,360][1080,1884]，高1524px；截图确认信息/主操作不覆盖图片，未提交审核、评分或删除。

证据：忽略目录`review_meta/release-integration-20261031/`中library-all.png、album.png、nsfw.png及夹具日志；既有49+6和128/85等测试结果复用，不重跑全套。此次没有运行打包EXE与APK端到端，不使用源码夹具替代此项；实体手机/HomeLAN、生产部署仍未执行。相册按可视位置预取最多8项及2并发沿用已通过的定向用例，本次小数据集不重新声称滚动压力验收。


### 2026-10-31 生图增量同步中途失败闭环（源码修复，尚未发包）

上一目标轮完成正式Alpha27的三项界面证据，属于有效进展；本轮继续核心流程，不声称产品最终合格。

确认并修复：新增计数原先累计UPSERT事件、整轮末尾才发布，导致同页新增后REMOVE、后续页REMOVE仍显示不存在的新图，后页失败又漏报已收到的新图。新增ID集合仅保留还在队列中的新增身份；每页原子发布列表与计数，解除配对/认证失效/查看最新时清理对应计数。另确认第一页移除当前图、第二页失败会让当前位置留在已移除图片；每页及时校正当前位置、清旧详情并读取有效图片详情，保留正在加载的详情状态，避免整轮末尾重复读取。无删除文件、无审核/评分协议变更。

改动：`android/app/src/main/java/com/mediareview/app/feature/v2/nsfw/NsfwReviewViewModel.kt`及同名ViewModelTest。新增4个最小回归，复用原队列与并发测试。过程：前三项旧实现3/3按预期失败（计数0→1、0→1、1→0），修复后89项通过2m35s；追加当前图中途失败用例，修复前期望img_b实际img_a，修复后最终**90项单轮通过（ViewModel72、FeedStore18），0失败/0错误，2m16s**。早前3项RED4m49s、追加1项RED1m5s。不是将多轮测试拼成单轮。

命令：登记Gradle8.9/JDK17、项目内既有进程级TEMP/Gradle缓存，`:app:testDebugUnitTest --tests '*NsfwReviewViewModelTest' --tests '*NsfwFeedStoreTest' --offline --max-workers=1 --no-daemon -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8'`。生产Kotlin与测试编译通过；没有运行全量JVM/pytest/connected或构建新APK，已有Alpha27和Server1.6.5二进制不含本次源码修复。物理手机/HomeLAN未测、生产未部署、NSFW项目未改。证据在开发worktree忽略目录`review_meta/new-image-count-{red,green}.log`、`removed-current-red.log`、`new-image-sync-final.log`及对应最终XML。保留原有compileSdk/测试名称warning，未改全局环境。
