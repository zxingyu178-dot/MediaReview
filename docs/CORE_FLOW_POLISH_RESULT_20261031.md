# 核心流程打磨记录（进行中）

基线 `e253aa0`，分支 `codex/core-flow-polish`。源码候选 Alpha27/code34 已通过下述隔离验证；当前正式交付仍为 Alpha26/code33，未生成新的正式签名交付 APK 或部署包，生产服务未变，不声称真实手机已经修复。

查询/草稿/同步源码 `c242d99`、状态收口 `9f02881`、后续交互及Alpha27版本源码 `fbf554a` 已成功 push 到 `origin/codex/core-flow-polish`。日常分支未合并这批改动；后续收口提交仅更新状态文档，不改变测试对应业务源码。

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

未执行全量测试、connected 全套、Release 重建、生产部署、真实媒体删除和评分。后续在源码提交/push 后生成正式 APK 与对应 Server 产物，维护唯一交付指针及构建 SHA，不反复重建同一 APK 收集证据。

## 后续交互验证

后续交互源码已修改：解除生图配对移到更多菜单、确认影响、审核/评分提交时 UI 与 ViewModel 同时禁止解除。新增 JVM 用例在原实现复现提交中仍可清除凭据；原迟到评分保护用例改为模拟真正的凭据失效，并保留请求回执清理与旧回包不能复活页面的检查。`:app:testDebugUnitTest --tests '*NsfwReviewViewModelTest' --tests '*ReleaseNotes*Test' :app:assembleDebug :app:assembleDebugAndroidTest` 单轮 85 项（68+17）通过，编译及两个测试包构建成功，7m38s，日志 `interaction-green-build-retry.log`。与此前 128 项是分轮，不相加称单轮全绿。

视频刷新圈改为真实请求状态；设备回归另发现 `retryList()` 清空当前浏览缓存，慢刷新先把列表变空（14.29 秒的一项设备运行在“保留已有内容”断言失败，不把当时圈仍可见误记为 700ms 问题通过）。现只保留当前可见窗口作为刷新底图，其余窗口依然失效，继续增加代次隔离旧回包；成功替换、失败保留、离页清理、分页不显示顶部圈。随后 6.944 秒设备回归发现顶部刷新与页尾分页双圈，限制页尾圈只用于非刷新分页。最后仅重建 App（2m52s），未改 ViewModel/版本逻辑，复用 85 项；最终调试包上的同一慢刷新用例通过（6.011秒），覆盖等待超过原700ms仍显示唯一刷新圈、内容保留、成功收起、失败保留、离页收起。另运行同类原有启动优先级/收藏惰性2项通过（4.138秒），与前一项分轮，未跑178全套。证据 `refresh-device-green-final.log`、`home-startup-related.log`。

生图确认界面已在隔离模拟器复核并看图：常用页没有裸“断开”；更多菜单→解除→取消，保持第1/6张与READY；再确认才返回配对页。最近一次合成配对后的8条请求中，审核/评分 POST 为0。证据 `unpair-confirm.png/xml`、`unpair-after-confirm.png`、`nsfw-unpair-check.json`。最终调试包配对页文案复核与截图通过：生图服务地址、连接并配对、独立连接说明可见，没有 DataStore/token 内部文案（`pairing-copy-final.png/xml`）；确认逻辑未再变更。真机未连接、NSFW生产项目与进程未改；临时桥已按PID/父进程/命令行核对关闭，两个uiautomator会话均退出，模拟器关闭，ADB设备/forward及测试端口为空。

环境记录：默认调试签名锁目录不可写，采用进程级 `ANDROID_USER_HOME` 指向项目临时目录，未改 ACL 或系统环境。一次 Gradle 选项放错任务导致配置失败，已调整，不计作业务回归。首次模拟器入口遗漏显式 AVD 目录，后续启动又漏了旧测试盘所需参数，ADB 长期离线；核对本聊天的上轮成功命令后精确停掉本次实例，恢复 `-datadir`、SDK 自带 `-encryption-key` 与 `-gpu swiftshader`，约两分钟后 `sys.boot_completed=1`。它们是测试盘启动参数，不能再仅凭记忆拼接；不读取或输出私钥。只读模拟器临时覆盖层因签名不同更换测试 App，未触碰真机或持久测试盘。ADB/模拟器验收以实际设备状态为准，不把这些环境失败归到 App。

## 剩余与边界

生产健康于10:06只读实查：ok / 1.6.3 / API2 / 14 capabilities / windows_service，尚不含本批SQL优化。正式 APK、包含本轮查询优化的 Server1.6.5 产物及唯一入口仍待交付，不把源码版本或历史1.6.4包当成已部署。真实家庭局域网/手机加载、首帧与长期连接稳定性未在本批复核；相册目录定位仍需按索引范围聚合，不能承诺所有大库瞬时打开。当前目标继续进行，本记录不作为产品最终合格结论。
