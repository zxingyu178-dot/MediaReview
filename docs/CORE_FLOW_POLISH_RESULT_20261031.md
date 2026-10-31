# 核心流程打磨记录（进行中）

基线 `e253aa0`，分支 `codex/core-flow-polish`。当前交付仍为 Alpha26/code33；本批源码尚未形成新 APK 或部署包，生产服务未变，不声称真实手机已经修复。

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

未执行全量测试、connected 全套、Release 重建、生产部署、真实媒体删除和评分。后续完成剩余交互后统一版本及构建，不反复重建 APK 收集证据。

## 未收口事项

解除生图配对的确认入口、视频刷新圈跟随真实请求仍按计划待做。真实家庭局域网/手机加载、首帧与长期连接稳定性未在本批复核；相册目录定位仍需按索引范围聚合，不能承诺所有大库瞬时打开。当前目标继续进行，本记录不作为产品最终合格结论。
