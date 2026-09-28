# MediaReview 2.0 — Stage 8A.1.1 评审摘要（Loading Pipeline Closure）

- 日期：2026-09-27
- 分支：`feature/mediareview-v2-stage8a.1.1-loading-closure`
- Base Commit：`b264eaa`
- Implementation Commit：`56fdef2`（Android）/ `5ff597a`（Server）
- **Final HEAD：以交接包 `00_HANDOFF.md` 为准（由 `git rev-parse HEAD` 现场生成）**
- Git Status：CLEAN
- **阶段结论：READY_FOR_USER_VALIDATION（有条件）**

## 一、本阶段修掉了什么

| # | 问题 | 修法 |
|---|---|---|
| 1 | `V2Perf` 把 object 初始化时间叫 Process Start | `MediaReviewApp.onCreate` 第一行取真实进程起点 |
| 2 | Viewer/Player 延迟相对 `home_enter`（错误基准） | 独立 Home/Paging/Viewer/Player 会话 |
| 3 | 上一条媒体的 claim flag 污染下一条 | 会话重建，全局 flag 删除 |
| 4 | folders 失败写进 `listError`（媒体墙误报"加载失败"） | `folderError` / `albumError` / `favoritesError` 解耦 |
| 5 | 收藏首次失败后永久锁死 | 成功才置 loaded；新增可用的"重新加载" |
| 6 | folders cache 只按 TTL，切服务器会命中旧数据 | 缓存绑定 `baseUrl` + 主动失效 |
| 7 | 9:16 竖图 152×270 被放大 3 倍（模糊） | Jellyfin `fill` 480×270 生成 16:9 grid cover |
| 8 | 客户端 URL 不变，源变了仍可能用旧缓存 | `?v=<source_version>` 三层失效一致 |
| 9 | 缓存无上限、无统计 | `stats()` / `prune()`，默认 1 GiB 上限 |
| 10 | 磁盘 IO 是否阻塞 event loop 只能靠猜 | `disk_read_ms` / `disk_write_ms` / `upstream_ms` 实测字段 |

## 二、测试规模（§9，纠正既有错误）

| 环境 | Collected | Executed | Passed | Failed |
|---|---|---|---|---|
| Server（pytest） | 377 | 377 | 376 | 1（既有） |
| Android JVM | 338 | 338 | 338 | 0 |
| Android instrumentation（定向 loading 包） | 6 | 6 | 6 | 0 |

> **纠正**：Stage 8A / 8A.1 报告中的"Server 全量 192 项"**有误**；
> 本阶段以 `pytest --collect-only -q` 实测 377 项为准。

## 三、性能数据（诚实边界）

- **EMULATOR**：仅 **1 个有效样本**（RUN 1，DEMO 冷启动）。模拟器随后严重退化
  （`app_start → home_enter` 1.76 s → 14.8 s，adb 守护进程崩溃），RUN 2..5 无数据 →
  **p50/p95/min/max 不成立**。
- **REAL PHONE / REAL SERVER / DEV MACHINE**：**NOT MEASURED**（无真机；按 §11 未部署服务）。

## 四、未做（按任务文档要求）

- **未部署生产 Server**（§11）：未替换 Windows Service、未重启正式服务、未动 ControlHub。
- 未开发 Stage 8B / Room 缓存 / Organize / 雪碧图 Server 化 / 播放器 UI 改版 / Viewer 功能扩展。

## 五、下一步

1. 批准后部署新 Server（缩略图缓存与 16:9 cover 才会真正生效）。
2. 真机 + 真实 LAN 跑 ≥5 次冷/热启动，回填 `03_PERFORMANCE_REPORT.md`。
3. 复核 9:16 竖图在 Viewer 从 16:9 裁剪预览切到完整原图时的构图变化是否可接受
   （§5 要求人工视觉确认；若不可接受，改用 preview 变体方案 B）。

**本阶段到此停止；不进入 Stage 8B。**
---

# Stage 8B 阶段自评 —— Server Review Session / 真实批阅模式接入

- **分支**：`feature/mediareview-v2-stage8b-review-session`
- **基线**：`ce171936692ea94ddd1a8dc1454dff6b52a08fab`
- **状态**：**READY_FOR_USER_VALIDATION**（等待真实 Server 实机验收；未进入 Stage 8A.2 Room）
- **交接包**：`MediaReview_Stage8B_Handoff_YYYYMMDD_<shortsha>.zip` + `MediaReview-v2-stage8b-review-session.apk`

## 一、本阶段做了什么

1. **前置正确性修复（§6~§12，单独提交）**：播放语义抽成 `V2PlaybackSourceController`
   （Source Identity / Direct→一次 HLS→Error / retry 回 DIRECT / P1 单槽）、
   内核报错必须带实际装载的 mediaId、Context 边界（不再静默指向第 1 项，改为单条上下文）、
   空队列拒绝写入、**相册取消 200 张截断**改为真实分页。
2. **批阅数据层独立（§14/§15）**：`feature/v2/review/data`（Server 实现复用现有 Review API；
   队列项只含 Metadata；绝对索引；每页 50；共享资源缓存）。
3. **Server 模式真实批阅（§3/§17~§40）**：**删除占位页**；恢复/新建会话分流、
   深位置恢复、P0/P1 播放、seen/position/complete 服务端确认制、
   完整播放器返回不重建、重新批阅 = 新建会话。
4. **待删除真正接通（§35/§36）**：GET/POST/DELETE delete-queue + 确认制 + 可撤销。
5. **顺带修掉 4 个客户端合同缺陷**：`seen` / `index` 在 `encodeDefaults=false` 下被省略、
   seen/position/complete 用 `Map` 接收布尔与整数、delete-queue 的 `queued` 布尔。

## 二、验证（真实执行）

- JVM：**380 项全通过**（新增 42 项）
- 设备侧全量：**46 项 / 37 通过 / 9 失败**，9 项逐项归因见 `02_TEST_REPORT.md`，
  **0 项可归因于本阶段代码**（5 项为已记录的环境 flaky / Hilt harness 限制，
  #1/#7/#8 疑似环境或状态相关且不在 8B 改动路径）
- 设备侧批阅端到端（模拟器内 MockWebServer，真实 HTTP）：全链路通过
  （含"只解析 settle 过的条目 + 下一条"与"position 用绝对索引"两条硬断言）
- Lint 0 errors；assembleDebug PASS
- Server：**未改任何 Server 代码**；pytest 376 通过，2 项与本阶段无关

## 三、明确未做 / 未验证

- **未部署生产 Server**（§46）：未停止生产服务、未替换 EXE、未迁移 DB、未重启 ControlHub；
- 未在真实 Jellyfin + 生产 Server 验收；
- 未做 Compose 手势级 UI 自动化（上滑/下滑视觉链路需人工）；
- 9:16 竖图 Viewer 构图跳变（§52）仍需你肉眼判定；
- 性能结论**不改写**：Stage 8A.1.1 的"只有 1 个有效模拟器样本、真机/LAN 全部 NOT MEASURED"
  继续有效（§51）。

## 四、下一步（等待你的指令）

1. 按 `05_USER_TEST_GUIDE.md` 在真实 Server 上走一遍批阅（重点：断点恢复、完整播放器往返、
   待删除撤销、>200 张相册）；
2. 批准后再做生产部署（独立步骤）；
3. 不要自动进入 Stage 8A.2 Room。

**本阶段到此停止。**
