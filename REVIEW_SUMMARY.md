# MediaReview 2.0 — Stage 8A.1 评审摘要

- 日期：2026-09-27
- 分支：`feature/mediareview-v2-stage8a.1-loading-pipeline`
- 基线：`30ce89e`（Stage 8A Production Bridge + OpsConsole 修复）
- HEAD：`783452b`
- **阶段结论：READY_FOR_USER_VALIDATION（有条件）**

## 一、本阶段做了什么

只做加载管线优化，不开发新功能。13 项门槛中 **12 项达标**，第 13 项
（冷/热性能数据真实记录）为 **NOT MEASURED**（无真机、优化后 Server 未部署）。

## 二、最有价值的 5 个改动

1. **Jellyfin 客户端长生命周期**（原：每个 thumbnail 请求新建 AsyncClient）
   → 媒体墙封面复用 Keep-Alive 连接池。
2. **Server 缩略图磁盘缓存**（原：`cache/thumbnails` 存在但媒体墙从未使用）
   → 第二次请求不再回源 Jellyfin（测试硬门槛已验证）。
3. **启动优先级**（原：folders → albums → favorites → media）
   → P0 首屏媒体先发，P1 后台并行，收藏懒加载。
4. **媒体卡去 Subcompose**（原：`SubcomposeAsyncImage` 在 LazyVerticalGrid 中阻塞测量）。
5. **Viewer 渐进加载**（原：直接请求 original 且邻页一起下载）
   → 第一帧显示已缓存封面，邻页不发原图。

## 三、验证到什么程度（诚实边界）

| 维度 | 结论 |
|---|---|
| Server 自动化测试 | 192 项，仅 1 项**既有**失败（与本阶段无关） |
| Android JVM 测试 | 337 项全过（含本阶段新增 7 项） |
| Android instrumentation | 32 项 19 过；新增 `HomeStartupPriorityTest` 通过；6 项 1.1 壳层测试**未复验基线** |
| Lint | 0 errors / 43 warnings（无新增） |
| Build / APK | PASS，versionCode=8 / versionName=2.0.0-alpha1 |
| **真机冷/热性能数字** | **NOT MEASURED** |
| **真实 Jellyfin 端到端** | **NOT TESTED**（优化后构建未部署到服务） |

## 四、必须让验收人知道的三件事

1. **没有性能数字**：`03_PERFORMANCE_REPORT.md` 全表 NOT MEASURED，理由是缺真机/未部署，
   不是"忘了测"。`MR_PERF` 打点与 `benchmarks/thumbnail_benchmark.py` 已备好，验收即可取数。
2. **6 项 MainShell* 仪器测试失败未归因**：它们是 1.1 壳层测试，失败表象为触摸注入与
   布局高度断言，与本阶段改动路径无关；但 Stage 8A 从未记录全量基线，因此**不能声称是既有失败**。
3. **本阶段未部署 Server**：优化后的 Server 构建只在仓库/交接包中，生产服务仍是旧构建；
   需要在验收时决定是否升级（届时缩略图缓存才会真正生效）。

## 五、下一步

按 `07_USER_TEST_GUIDE.md` 真机验收 → 回填性能数字 → 决定是否升级 Server 构建。
**本阶段到此停止，不进入 Stage 8B。**
