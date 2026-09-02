# REVIEW_SUMMARY — MediaReview 1.1 Task D：批阅、收藏、安全删除与重复整理

## 阶段编号与名称

- 阶段 D（takeover plan `docs/superpowers/plans/2026-08-30-mediareview-1.1-takeover.md`）
- 名称：批阅、收藏、安全删除与重复整理（`1.1.0-beta1`）
- 提交：
  - `ca9f75a feat(delete): add nonce two-phase commit contract`（Server 两阶段永久删除）
  - `8e824b7 feat(duplicates): persist scan groups as background task`（Server 重复分组持久化后台任务）
  - `afa6b0b feat(duplicates): dual-column compare + keep`（Android 双栏对比 + 保留选择，本阶段收口提交）
- 基线：`6485881`（Task C 终点）

## 阶段目标

在既有“去重/待删除”功能基础上完成 1.1 安全与体验收口：批阅/收藏一致性核查；
两阶段永久删除（消除“点击即永久删除”与 TOCTOU 风险，nonce 一次性合同）；
重复分组持久化 + 后台扫描（exact/similar，可暂停/继续/取消，绝不自动删除）；
Android 重复文件双栏对比 + 保留选择 UI。

## 实际完成内容

- **D1–D3 核查（无代码缺口）**：批阅窗口行为（混合图/视频、稳定 pager、P0 缓冲停 P1、
  横屏视频居中、删除失败停留当前项）与收藏一致性（Media/Player/Review/Favorites 经
  revision 图与成功变更才更新）逐项核查，确认既有实现无缺口。
- **D4 两阶段永久删除（`ca9f75a`）**：
  - `POST /delete-queue/commit/prepare` 返回一次性 nonce（`secrets.token_hex(16)`，TTL
    10 分钟，服务端落库）；`POST /delete-queue/commit` 仅接受 nonce，客户端不提交任何
    media_id/路径。
  - 服务端从 DB 重取媒体路径（`media_cache_index`），逐项校验库归属/队列成员/文件身份
    （存在、普通文件、size 一致、mtime 3s 容差），身份不符 → `failed` 并写审计，绝不删除。
  - 逐项独立 try/except 执行，单项失败不影响其余；快照绑定 prepare 时刻 pending 集合，
    prepare 后新入队项绝不删除（TOCTOU 实测）；nonce 一次性/过期/未知 → 409/422。
  - 逐项审计 `audit.log_action("delete_commit", …)`。
- **D5 迁移 `0014_task_d_delete_nonce_duplicates`**：down_revision=0013（链路
  0012→0013→0014 单一线性 head），新增 `delete_commit_nonce` / `duplicate_group` /
  `duplicate_group_member`；upgrade/downgrade 对称；round-trip 测试实测保留旧行。
- **D6a Server 重复分组持久化 + 后台任务（`8e824b7`）**：exact = size+duration+分段 quick
  fingerprint+combined SHA-256；疑似 = duration/size/resolution（1.1 无 pHash）；任务
  pause/resume/cancel/progress；`_claim_next` 原子认领防重复运行；`_finish_scan` CAS 防
  覆盖终态；cancel 真正停止循环；绝不自动删除（全服务端唯一媒体删除点在 delete_queue，
  已守卫）。
- **D6b Android 双栏对比 + 保留选择（`afa6b0b`）**：契约层
  （DuplicateGroupDto(members/keep)/DuplicateScanStatusDto/DuplicateKeepRequest +
  scan/status/keep/pause/resume API + MediaDataSource/MediaRepository/TestFakes）；
  `DuplicatesViewModel`（分组加载 revision 门、扫描触发→轮询 1500ms/20 次 miss 兜底、
  暂停/继续/取消、双栏对比取摘要、保留选择成功才落本地）；`DuplicatesScreen`（扫描状态卡、
  完全/疑似重复分组列表、双栏对比网格 + 保留勾选、中文提示“重复文件不会自动删除，删除仍需
  在待删除页确认”）。

## 是否完整达到目标

是。D1–D6 全部完成；D7 全量门禁 + 独立审查（CLEAN）。instrumentation/真机/真实 Jellyfin
验证不在本机能力范围，已明确留待 Task G（不冒充完成）。

## 核心架构 / API / 数据库变化

- API（Server）：
  - `POST /delete-queue/commit/prepare` → `{nonce, …}`；`POST /delete-queue/commit` → 逐项
    `success|missing|failed`。
  - `POST /duplicates/scan`（幂等）/ `GET /duplicates/status` / `GET /duplicates`
    （exact/similar 分组含成员与 keep）/ `POST /duplicates/{group_id}/keep`。
  - `POST /tasks/{task_id}/pause|resume`（通用任务控制）。
- 数据库：迁移 `0014_task_d_delete_nonce_duplicates`（三张新表，单一线性 head）。
- Android：新增 `DuplicatesViewModel` / `DuplicatesScreen` / 重复 DTO；`MediaDataSource`
  新增 8 个方法；`DeleteQueueViewModel.commit` 先 prepare 取 nonce 再 commit。

## Android UI/交互变化

- 重复文件页：后台扫描状态卡（进度/暂停/继续/取消）、完全重复/疑似重复分组列表、
  双栏对比网格 + 保留勾选；文案明确“保留仅作整理记录，删除仍需在待删除页确认”。
- 待删除页：最终删除按钮必须经 AlertDialog 二次确认后才提交（无“点击即永久删除”）。

## 已执行测试与结果（真实命令）

```powershell
# Server 全量
server\.venv\Scripts\python.exe -m pytest -q            # => exit 0；308 tests collected（全过）
server\.venv\Scripts\python.exe -m ruff check .          # => All checks passed!
server\.venv\Scripts\python.exe -m ruff format --check .  # => 92 files already formatted
# Android 四目标（--rerun-tasks，干净重跑）
android\.\gradlew.bat --offline --no-daemon :app:testDebugUnitTest :app:assembleDebug `
  :app:assembleAndroidTest :app:lintDebug --rerun-tasks
# => BUILD SUCCESSFUL (6m37s, 91 tasks)；JVM 154 tests / 0 failures / 0 errors（33 套件）；
#    lintDebug 0 Error（47 条既有 Warning：依赖版本/图标/清单类，与 Task D 无关，诚实记录）
# 注：Server 性能门禁（100k 响应性 <2.0s）与 Android 构建并行时出现过一次 2.09s 抖动失败，
#     单跑复测 exit 0 通过（CPU 竞争所致，非代码问题）
```

## 独立审查

- 审查报告：`.superpowers/sdd/task-d-independent-review.md`（对抗性破坏安全审查）。
- 结论 **CLEAN（0 Critical / 0 Important / 4 Minor）**：nonce 合同（一次性/过期/防伪/
  服务端重取 DB 身份/逐项独立/逐项审计/TOCTOU 快照）成立；Android 需 AlertDialog 二次确认，
  无单步永久删除；重复扫描全链路只读不删；任务控制无双重运行/死锁；迁移 0014 单一线性 head、
  升降对称；禁止工件检索仅命中文档文件名。
- 4 Minor（不阻塞）：M1 OSError 文本可能含绝对路径写入本地 SQLite 审计/错误字段（不外发）；
  M2 重扫清空旧分组并清掉 keep 标记；M3 resume 后 progress 重置、paused 时 POST /scan 为
  no-op；M4 任务书文字写 down_revision=0012，实际 0014 为 0013（链路线性成立，文字误差）。

## 已知问题 / 遗留 TODO

- instrumentation 仍未在设备执行（本机无 ADB 设备/模拟器/system image）；真机/真实
  Jellyfin 场景属 Task G。
- 4 Minor（M1–M4）记录在案不阻塞，建议后续阶段处理 M1（OSError 去路径化）与
  M2（重扫保留旧分组与 keep 标记）。
- 重复扫描对超大媒体库（10 万+）的耗时与内存未经生产规模实测；quick fingerprint 对真实
  多样格式的覆盖待真机/真实库验证（Task G）。

## 是否建议进入下一阶段

建议进入 Task E（1.1 剩余收口项，依 takeover plan 顺序）。Task D 已闭环批阅/收藏一致性、
安全删除 nonce 合同与重复整理全链路，门禁全绿且破坏安全审查 CLEAN。

## Agent 自认为风险最高的 3 个点

1. **nonce 合同的真实并发与崩溃恢复**：TOCTOU/一次性/过期/防伪均已由测试覆盖，但
   “commit 中途异常 → DB 回滚而文件已删 → 重试按 missing 幂等清理”的恢复路径与多进程
   并发写 SQLite（WAL + busy_timeout）行为未在多设备/多客户端真实场景压测。
2. **重复扫描的规模与耗时**：扫描任务在真实大媒体库（10 万+）上的内存占用、快速哈希分段
   策略对异构编码/容器（如 HEVC、多音轨、嵌套字幕）的判定准确度未经验证；误判率需真机
   数据评估。
3. **Android 双栏对比/扫描状态 UI 未经真机验证**：扫描轮询/暂停/继续/取消、双栏对比与
   保留勾选在真实网络延迟、后台限制（Doze/杀进程）下的行为，仅由 JVM 单测覆盖，未在
   Android 15 模拟器/真机走查（Task G 验收）。

## 敏感信息说明

本阶段未接触任何真实密钥、Token、真实配置或生产数据；删除测试全部使用 `tmp_path` 一次性
夹具；重复/删除均无自动删除真实媒体；服务端唯一媒体删除点已守卫并审计。nonce 仅存在于
服务端数据库与请求体。

阶段结论：合格
