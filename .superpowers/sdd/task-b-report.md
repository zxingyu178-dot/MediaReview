# MediaReview 1.1 Task B 执行报告

日期：2026-08-30 | 基线：`d542e08`（Task A 之后）→ 工作树
提交拆分：B1 `feat(android): page media wall`（6577939，双端分页与文件夹视图）
冻结 diff：`.superpowers/sdd/review-6577939-taskb.diff`（B2：图片查看器 + 雪碧图任务闭环）
独立审查：第一轮 NOT CLEAN（2 Important）→ 修复 → 第二轮复审（结论见
`.superpowers/sdd/task-b-independent-review.md`）

## 实现内容（对照 takeover plan Task B）

### B1 媒体墙 Paging 3（提交 6577939）

- 新增 `MediaQuery`（不可变查询快照，含 folderId）与 `MediaPagingSource`
  （键=服务端页码；loadSize 无关、固定 pageSize=50；异常包装 LoadResult.Error，
  CancellationException 原样上抛；onSyncLoaded 回传 sync.state）。
- `MediaWallViewModel` 重写：`combine(query, refresh).flatMapLatest { Pager(...) }.cachedIn(scope)`
  ——新查询/新刷新代数创建新 Pager，旧流自动取消；cachedIn 是唯一页缓存；
  保留 2-5 列、类型/媒体库筛选、400ms 防抖搜索、六种排序、未点赞模式、设置持久化、
  revision 失效门（loadGate）；新增文件夹筛选（服务器 ID，非路径）。
- `MediaWallScreen` 换 `collectAsLazyPagingItems`：骨架/空态/离线重试由 LoadState 驱动，
  追加失败在网格尾部就地重试；`FolderFilterMenu` 在媒体墙内部（非底部导航）。
- Server：`GET /media/folders`（父目录 SQL 聚合，folder_id=SHA-256 前 16 hex，响应无任何路径）、
  `GET /media` 新增 `folder_id` 参数（筛选范围内反查，未知 404）；100k 无扫描门禁
  （`test_media_pagination_100k_no_rescan_and_responsive`）。
- 接口变更：`MediaDataSource.loadMedia` 增加 `folderId`、新增 `loadMediaFolders`——
  生产 MediaRepository、测试 FakeMediaDataSource、androidTest ShellRepository 全部同步。

### B2 图片查看器与雪碧图任务闭环（工作树）

- 图片查看器：详情失败重试按钮（`retry()`）；重复 load 取消在途请求；离开页面
  `DisposableEffect → cancelDecoding()` 取消加载；Coil `ImageRequest.size(viewport)`
  按视口解码（≥1px 兜底），超大原图不整幅载入内存；clampPanOffset 回归测试。
- 雪碧图服务端：POST ensure 返回 **202 {task_id,status}**；处理器进度里程碑
  20（指纹校验后）→ 40（探测后）→ 100；生成前/后两处协作取消检查；
  终态写入改 **CAS 条件 UPDATE**（仅 pending/running 可转 succeeded，输掉即回滚并丢产物）。
- 雪碧图 Android：`ensureSprite` 返回任务引用；轮询任务进度（SpriteScrubState.progress/
  taskStatus/taskId）；等待覆盖层显示"雪碧图生成中 N%"+ 取消按钮（`/tasks/{id}/cancel`）；
  失败/取消有中文终态文案并停止轮询；轮询耗尽自动复位可重试；[0f,1f]→帧映射提取为
  `tileIndexFor` 纯函数。

## 审查发现与修复（第一轮 NOT CLEAN → 修复）

- **I-1a 生产缺陷（审查发现的既有缺陷）**：TaskManager._loop 对 _tick 无异常保护，
  一次瞬时 SQLite 锁冲突即永久杀死后台任务引擎。修复：捕获+记日志+继续；
  回归 `test_task_manager.py`（RED：异常逃逸 → GREEN：循环存活）。
- **I-1b 100k 测试 flaky**：批量插种与 1s 轮询写事务争锁。修复：迁移+预插种全部在
  client 启动前完成；连续 3 次运行全过 + 全量 282 passed。
- **I-2 ruff 4×W605**：heredoc 写入的测试夹具反斜杠被转义层吃掉一层，改 raw string。
- M-1 终态 TOCTOU（CAS 修复）、M-2 失败/取消误导文案（终态中文文案）、M-3 轮询耗尽
  锁死（复位可重试）、M-5 死代码 ErrorBox（删除）、M-7 CancellationException 无测试
  （补测试）。
- 保持原状并如实标注：M-4 位图级加载失败无重试 UI（仅详情级重试闭环）；
  M-6 folders 100k 全库聚合 0.6-1.1s，超出 1s 目标但远低于 2.0s 硬上限（文件夹视图
  非翻页热路径）；M-8 loadMediaFolders 失败静默保留旧列表。

## TDD 证据（真实命令与输出）

1. Server RED：文件夹/100k 测试初次 6 failed（/media/folders 被 /{media_id} 捕获、folder_id 未实现）。
2. Android RED：MediaPagingSourceTest 编译失败（Unresolved MediaPagingSource/MediaQuery/MediaFolderItem）。
3. task_manager 回归 RED：RuntimeError 从 _loop 逃逸（`RuntimeError: transient database is locked` 传播到测试）→ 修复后循环存活 GREEN。
4. Server 全量：**282 passed**（273 + 9 新增）；ruff check / format 全绿（87 files）。
5. Android JVM：**122 tests / 0 failures**；四目标 `--rerun-tasks` BUILD SUCCESSFUL（91 tasks，
   修复前后各一次）；lint 0 errors。
6. flake 验证：100k 测试连续 3 次 passed。
7. 第一轮审查员独立复核：Android 120 tests（当时）重跑通过、assembleDebugAndroidTest 成功、
   Server focused 43/44（暴露 100k flake）、ruff 4 errors（即 I-2）。

## 执行边界

- instrumentation 仍未在设备执行（无设备/模拟器）：androidTest 编译验证；设备级验收属 Task G。
- "Android heap never contains 50k DTOs" 无专门测试，结构性达标（pageSize=initialLoadSize=50，
  cachedIn 仅保留页级数据），由独立审查确认。
- unprocessed modes 计划文本系笔误，任何版本从未存在该能力，非回归。
