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
