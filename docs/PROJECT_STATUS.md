# MediaReview 项目状态（唯一活文档 / SSOT）

> 更新时间：2026-10-27（加载专项开发与定向验证中）
> 分支：`feature/mediareview-local-only` ｜ 调查基线`1247ee4`，诊断提交`b2b0e9e`已push
> 修复源码提交`6a74876`、交付记录`d18c31d`；本轮两次push均因GitHub443连接失败，远端交付未确认，不能声称已push。下方历史检查点按各自日期理解。
>
> **本文件是"当前状态"的唯一事实源。** `README` 只保留摘要；阶段文档（STAGE8*/STAGE9*、REVIEW_SUMMARY、TAKEOVER_READ_FIRST、HANDOVER 等）都是历史快照，不再代表当前状态。任何代码提交、发布、部署、服务恢复都必须同步更新本文件与末尾变更记录。

## 0. 一句话状态

**最新开发中状态**：用户确认加载专项，雪碧图保持删除。源码目标为Server1.6.2 + Alpha18/code25；图片缓存统一、可见区域预取取消、缓存I/O线程化、播放源/续播并行已实现。Server13项与复核后缓存5项分别通过；JVM首轮57通过/1旧版本断言失败，修正后相关20项通过，非单轮全绿。隔离API35模拟器两项图片HTTP检查通过：合成小图冷304ms、内存4ms、磁盘命中，慢响应滚动新窗口23ms，账号隔离有效。模拟器已关闭，正式产物待构建。详见[加载专项结果](LOADING_RESULT_20261027.md)。未部署生产，下文1.6.1是本日上一轮交付记录。

当前源码为 **Server1.6.1/Contract2/迁移0017 + Android Alpha17/code24**。已修复同用户Jellyfin库级递归漏项：后台改为有界的逐层分页、同目录分页异常失败闭合、跨目录稳定ID去重，完整成功后才失效旧索引。最终客户端/索引45项、目录/API52项通过；四个真实上游样本只读计数132/288/101/1866均符合对照。既有Alpha17在隔离模拟器连到修复版后显示全局5项、原递归0库3项及子目录2项，图片实际渲染；合成视频出帧未确认（goldfish Surface错误），不计播放通过。生产仍为**1.5.2/0016/windows_service，Running且DB正常**，未升级或刷新。详见[修复与测试结果](FOLDER_ZERO_REPAIR_RESULT_20261027.md)。§3、§5中的旧运行态与旧产物待办属于历史检查点，以本段最新实测为准。

本轮Server1.6.1包已生成：`deploy_handoff/folder-fix-1.6.1/MediaReview_Server_1.6.1_stage9.zip`（134,336,115字节，SHA256 `b644616f20535b4a80fb432ecfd206866e3e8681c5d3c210e280c2b0966618c6`）；实际EXE health及182项文件校验通过，构建源码`6a74876`。复用Alpha17，不重建APK；产物未经生产部署或手机/HomeLAN验收。

## 1. 权威版本表（SSOT）

加载调查及用户裁剪决定见[调查与建议](LOADING_PREVIEW_ASSESSMENT_20261027.md)。[专项方案](superpowers/specs/2026-10-27-media-loading-design.md)已获确认，正在实施；以本页最新段落及专项结果为准。

| 组件 | 权威值 | 证据（文件:行 / 来源） |
| --- | --- | --- |
| Server 版本（源码目标） | `1.6.2` | `server/app/__init__.py:3`、`deployment/version.ps1:12`、`server/pyproject.toml:3`（三处一致） |
| API Contract | `2` | `server/app/__init__.py:8`、`deployment/version.ps1:13` |
| Server 能力（源码 14 项；已发布 1.5.2 为 13 项） | `native_sources_v1`、`native_range_v1`、`native_read_only_v1`（legacy 名）、`native_delete_v1`、`folder_tree_v1`、`thumbnail_profiles_v1`、`review_folder_filter_v1`、`delete_receipt_v1`、`review_session`、`review_nearest`、`organize`、`delete_nonce`、`duplicates_paged`、`library_selection` | `server/app/__init__.py`、`scripts/build_deploy.py`；生产 1.5.2 仍为 13 项 |
| 数据库迁移 head | `0017_delete_queue_token` | `server/app/db/migrations/versions/0017_delete_queue_token.py`；`status` 历史兼容包含 `delete_done` / `delete_pending` |
| Android 版本（源码目标） | `versionCode 25` / `versionName 2.0.0-alpha18` | `android/app/build.gradle.kts:32-33`、`ReleaseNotesCatalog.kt` |
| Android 包名 | `com.mediareview.app` | `android/app/build.gradle.kts:27` |
| 生产已装版本 | `1.5.2` | `C:\Program Files\MediaReviewServer\VERSION.txt` |

## 2. 产物状态

**Alpha17 APK已构建：43,655,762字节，交付见§21；用户确认已安装，未独立实机核验。** Alpha16/Server1.6.0产物见§15；下表保留更早的1.5.2/Alpha15发布记录：

| 产物 | 字节 | SHA256（实测） | 备注 |
| --- | --- | --- | --- |
| `deploy_handoff/MediaReview_2.0.0-alpha15_stage9.apk` | 43,606,610 | `b950d25f88d8a520fdc36afff3400b35c422567da6b3498b4bcf853a8f93ee44` | 签名与 Alpha14 同证书；**未安装到手机** |
| `deploy_handoff/MediaReview_Server_1.5.2_stage9.zip` | 134,260,522 | `50fb541bda5c5cdf9fb52c5f246ab60cd59560c7c6f723a11e4c250b526d7095` | 180 项 SHA 清单自洽；包内 README 为 Stage8I 旧文（待发布前修） |
| 包内 `MediaReviewServer/MediaReviewServer.exe` | 14,559,389 | `e0dbb6d07b65b58731e844ef33625792af8dbb5401f848708e9b5381e7513b17` | 构建 `git_head=2858d3c`、Server 1.5.2/Contract 2/0016 |
| 生产已安装 EXE | 14,559,389 | 同上 `e0db…7513b17` | 与 1.5.2 正式包**逐字节一致** |

**关键事实**：已发布 1.5.2 的构建基线是 `2858d3c`；`747e704`（图片 404 归类修复）与 `2dd9ff2`（仅文档）都在其之后。因此**生产 1.5.2 不含 404 修复**，缺封面仍返回 502。要改变必须重新打包发版。

## 3. 生产运行态（2026-10-24 实测）

| 项 | 实测值 | 检查方式 |
| --- | --- | --- |
| SCM 服务 `AIHome.MediaReview` | **Stopped / Manual** | `Get-Service` |
| 8766 监听 | `0.0.0.0:8766` 由 **PID 3896** `MediaReviewServer.exe` 持有（会话 0，创建于 2026-10-23 17:32:00） | `Get-NetTCPConnection` / `Get-CimInstance Win32_Process` |
| 该实例 health | `status=ok`、`1.5.2`、`api_contract=2`、DB ok、13 能力，但 `instance_mode=interactive`、`lifecycle_target=interactive` | `GET /api/v1/system/health` |
| ControlHub | integration `mediareview` = approved；lifecycle 状态 `offline`（识别到的是 interactive 身份，不是官方服务） | `control_hub.lifecycle_cli status` |
| 最后一次官方服务正常 | 2026-10-20 08:14 升级 1.5.2 后三次 health 均为 `windows_service / AIHome.MediaReview` | `review_meta/stage9/production-152-upgrade.json` |
| 手机 | ADB 无设备；Alpha15 未安装 | `adb devices` |

> 风险：交互式实例不是官方生命周期对象，无自启、不受 ControlHub 管理；历史记录中它已换过一次 PID（3916 → 3896），来源仍未确认（见 `review_meta/ops-20261023/service-restore-log.md`）。

## 4. 验证矩阵（证据级别：A 实机/生产｜B 构建/隔离｜T 源码自动化测试｜C 文档自述｜D 未执行）

| 项目 | 结果 | 级别 | 证据 |
| --- | --- | --- | --- |
| 10-23 Server 全量 pytest（含 404 修复） | **693 通过 / 2 失败 / 695 项 / 422.70s**；2 项为本机 ffmpeg 完整性级别环境问题，换可写 basetemp 单独复跑通过 | T | `review_meta/ops-20261023/server-full-green2.log`、`docs/DEV_LOG.md:2881-2885` |
| 10-23 改动前全量 pytest | 690 通过 / 4 失败 / 484.50s（失败均已分类） | T | `review_meta/ops-20261023/server-full-baseline.log` |
| 10-23 图片代理定向回归 | 16 通过（含新增 404 用例） | T | `docs/DEV_LOG.md:2883` |
| Android JVM | 75 suites / 498 tests / 0 失败（10-23 基线；之后未改 Android，未重跑） | C（复用基线） | `review_meta/ops-20261023/`（记录）、DEV_LOG |
| **10-24 P0/P1 本地删除后 Server 全量** | **709 通过 / 2 失败 / 711 项 / 430.01s**；2 项为本机 ffmpeg 完整性级别环境限制（默认 Temp 不可写），换项目内可写 basetemp 单独复跑 2 通过 | T | `review_meta/ops-20261024/server-full-p1-fast.log` |
| **10-24 P0/P1 后 Android 全量 JVM** | **75 suites / 507 tests / 0 失败 / 0 错误 / 0 跳过**（BUILD SUCCESSFUL） | T | `review_meta/ops-20261024/android-full-p1.log`、`android/app/build/test-results/` |
| **10-24 新增本地删除安全测试** | 12 类场景（两阶段成功/审计无路径、missing、root 停用/缺失、同尺寸替换、越界/绝对/UNC/目录、跨根、junction、逐条失败、nonce 快照、file_in_use、扩展分类） | T | `server/tests/test_native_sources.py` |
| **10-25 P5 发版准备定向验证** | Server 指定四文件 **79 passed / 0 failed / 0 skipped**；Android 指定过滤 **20 tests / 0 failures / 0 errors / 0 skipped**，BUILD SUCCESSFUL | T | `review_meta/codex-review-20261025-round7/p5-report.md` |
| 8I 完整 AVD connected | 第三轮 178/178 通过（2026-10-17） | B | `REVIEW_SUMMARY.md` 历史摘要、`review_meta/stage8i/final-connected-round3` |
| 9D Alpha14/1.5.0 交付 | APK 签名 PASS；隔离 EXE health 1.5.0/Contract2/13 能力；ZIP 162 文件核验 | B | `review_meta/stage9/delivery-receipt.json` |
| 1.5.0/1.5.1/1.5.2 生产升级 | 均经授权、停服备份、逐文件核验、health 通过 | A | `STAGE9_RESULT.md`、`review_meta/stage9/production-152-*.json` |
| 10-20 0016 回填 | 56,566 条归属；升级前后 配对19/收藏4/批阅会话1/待删38/缓存56,566/库选择21 数量一致 | A | `production-152-before.json` / `verified.json` |
| Alpha15 装机与真机/LAN 验收 | **未执行** | D | ADB 无设备 |
| seek 后 `Invalid NAL length` | **未复现、未修复**（证据仅来自 Alpha14/code21） | D | `review_meta/ops-20261023/seek-nal-report.md` |
| `media_folders` 慢性慢查询 | **已定位、未修复**（56k 行基线 2.6–3.1s，峰值 11.5s；0 目录仍 3.8s） | A（调查） | `review_meta/ops-20261023/image-perf-report.md` |
| 图片 404→502 修复 | 源码已修（`747e704`），**未发版、未进生产** | 部分 | `docs/DEV_LOG.md:2870-2890` |
| GitHub 推送 | **2026-10-24 已成功推送**（`2dd9ff2..5786f8f`，含 `747e704` 与统一文档提交） | A（远端） | `git push` 输出、`git status -sb` |

## 5. 已知问题（按严重度）

| 级别 | 问题 | 状态 | 影响 | 下一步 |
| --- | --- | --- | --- | --- |
| **S0** | 已发布 1.5.2 不含 `747e704` 404 修复 | 产物 `git_head=2858d3c`；P5 源码已包含该修复 | 现有产物缺封面仍报 502 | 后续获准时构建并验证 1.6.0 / Alpha16 产物 |
| **S0** | 官方服务 Stopped，8766 被来源未确认的 interactive 实例占用 | PID 3896（10-23 曾为 3916） | 无自启、ControlHub 不可管理、重启后手机不可达 | 管理员结束占用进程 → ControlHub 启动 → 3 次 health 核验 |
| **S1** | Alpha16 产物未构建，未上真机 | 本轮仅源码准备 | 0017、本地 POSIX-only 删除与手机/LAN 行为均无新版实机证据 | 另行获准构建/安装后按 `USER_LAN_VALIDATION.md` 定向验收 |
| **S1** | seek 后 `Invalid NAL length` | 根因未确认，未做猜测性修改 | 拖动播放可能中断 | 按 `seek-nal-report.md` 取证后再修 |
| **S1** | `media_folders` 慢性慢查询 + 5s 台阶 | 已定位，未修 | 目录页 2.6–11.5s；缩略图 53 次 >5s | 先做零风险分段打点，再做结构性优化 |
| **S2** | 本地源永久删除：源码已实现两阶段安全删除；本地源仍无重复检测/转码 | P1 第七轮复审通过，P5 版本/契约源码已同步 | 已发布 1.5.2/Alpha15 仍不能删本地媒体，1.6.0/Alpha16 产物未构建 | 后续授权构建 + 真机隔离样本验证 |
| **S2** | 0016 无法恢复历史已被覆盖的跨库归属 | 仅回填现存归属 | 部分子库目录需刷新同步后才恢复 | 升级后确认库勾选并刷新 |
| **S2** | 本机 2 项 pytest 环境失败 + `server/app/main.py` 2 条既有 ruff 违规 | 环境级 / 既有 | 不阻塞产品，但不能宣称全绿 | 修本机工具目录完整性标签 / 另开小提交清 ruff |
| **S3** | GitHub 不可达（已于 2026-10-24 恢复） | 已解决：`2dd9ff2..5786f8f` 推送成功 | 远端交接渠道恢复 | 保持每次提交后核对 `git status -sb` |
| **S3** | 文档/registry 口径漂移 | 本次已统一 S 级（见 §8） | 交接误判 | 保持本文件更新 |

## 6. 阶段时间线（索引）

| 阶段 | 日期 | 代表 commit | 交付版本 | 状态 |
| --- | --- | --- | --- | --- |
| 1.0 基线导入 | 2026-08-22 | `cf8f363` | — | ✅ |
| 1.1 Task 0A–4 / Task F | 08-24～09-02 | `ef528d3`、`fac901e` | 1.1 开发 / Server 1.1.0-rc1 | ✅（真机门未过） |
| 1.1.0-rc1 / rc2 | 09-03 / 09-06 | tag `1.1.0-rc1`/`rc2` | Android code7 | ◐ 未发布正式 1.1.0 |
| 2.0 Stage1–3 | 09-19～09-22 | `633e31c`、`5f196fd` | 2.0.0-alpha1 | ✅（JVM/构建级） |
| Stage 8A–8J | 09-24～10-17 | `0f63090` … `cbf56ba` | Alpha1→Alpha13/code20；Server 1.2.2→1.4.1 | ✅（8J 手机故障未复现） |
| Stage 9A–9D 原生本地源 | 10-18 | `f4ae162`、`62f5abf`、`ddd6b00`、`838941d`、`f1a4ac8` | Alpha14/code21；Server 1.5.0 | ✅ 定向/隔离验证；仅 2 段视频真机出帧 |
| 1.5.1 锁竞争热修 | 10-18～10-19 | `d704f51` | Server 1.5.1 | ✅ 生产升级；拖动恢复未确认 |
| 10-19 完整体检 / UI 审查 | 10-19 | `91c0ed3`、`c581830`、`9ef0719` | 1.5.1/alpha14 | ◐ 确认跨库覆盖、seek NAL、慢请求、UI 缺陷 |
| 10-20 0016 修复 + 1.5.2 发版与生产升级 | 10-20 | `2858d3c`、`2dd9ff2` | Alpha15/code22；Server 1.5.2/0016 | ✅ 发布/升级有回执；Alpha15 未装机 |
| 10-23 图片 404 归类修复 | 10-23 | `747e704` | 源码修复，未发版 | ◐ 全量 pytest 693/695；未推送 |
| 10-23 服务恢复尝试 | 10-23 | — | — | ✗ 未恢复（端口冲突、无管理员权限） |
| 10-24 进度统一 + 跳过 Jellyfin 评估 | 10-24 | `5786f8f`、`37e93e2` | — | ✅（只读评估 + 文档统一 + 推送） |
| 10-24 本地源为主 P0/P1 开发 | 10-24 | `2e59b9d`、`b3543a3`、`e5fe3c5`（已推送 `feature/mediareview-local-only`） | 源码：Server 能力 14（+`native_delete_v1`）/ Android 未升版 | ◐ 源码完成并验证；未发版、未真机 |

## 7. 产品方向：本地源为主 + 本地安全删除（2026-10-24 已批准，P0/P1 源码完成）

用户真实需求：**同一局域网下用手机看电脑视频，并能审核、删除不想留的视频。** 2026-10-24 用户批准按推荐方案开始开发。

**已确认决策**
- 方向：**本地源为唯一必需来源，Jellyfin 代码保留、配置可选**（方案 B 产品方向 + C 工程形态）。
- 本地删除：**不可恢复但全链路安全**（两阶段 + nonce + 二次确认 + 路径链/身份复验 + 审计 + 逐条失败）；可恢复的回收站/延迟删除列为 P2 可选，未做。
- 删除开关：默认启用（仅对已登记且 enabled 的本地根生效；后续如需可加配置项）。
- 转码：P3 暂缓，等真机 A/B 实测后再决定 remux 或重编码。
- 规则：已修订项目 `AGENTS.md` 的 Stage9 约定，允许"两阶段安全删除"。

**开发分支**：`feature/mediareview-local-only`（从 `37e93e2` 创建），Server 与 Android 并行开发；Lead 已复核并跑全量验证，提交后推送。

| 阶段 | 内容 | 规模 | 状态 |
| --- | --- | --- | --- |
| P0 | 去 Jellyfin 依赖收口（引导/诊断/运维文案、扩展名白名单、MIME） | S，1–2 人日 | **源码完成并验证（未发版）** |
| P1 | **本地安全永久删除**（Server 能力 `native_delete_v1` + 入队/提交本地分支 + Android 门禁改造 + 测试） | M，3–5 人日 | **第七轮复审通过，进入 P5** |
| P2 可选 | 删除可回滚（Windows 回收站或延迟删除 + 撤销窗口） | M，2–4 人日 | 未开始（可选） |
| P3 | 播放兜底（按需 remux / HLS 转码） | L，7–12 人日 | 待真机 A/B 后决策 |
| P5 | 发布（Server 1.6.0 + Android alpha16/code23 + 回滚说明） | S，1–2 人日 | **历史 Alpha16/Server 1.6.0 产物已构建；未部署、未真机/LAN/生产验收，见 §15** |

验收标准（P1）：只能经"入队 → prepare nonce → 确认"删除；文件被替换/换链/junction/root 禁用/快照外项全部拒绝且不删；成功项审计可查；失败逐条不中断；**只在临时目录与合成文件上验证，真机删除样本需用户另行授权**。

## 8. 文档地图（谁代表当前状态）

| 文档 | 角色 | 说明 |
| --- | --- | --- |
| **`docs/PROJECT_STATUS.md`（本文件）** | **唯一活状态源** | 所有"当前版本/当前状态/待办"以此为准 |
| `README.md` | 活摘要 | 只保留项目简介+当前版本摘要并链接本文件 |
| `AGENTS.md`（项目） | 规则 | 工程规则；顶部有指向本文件的进度声明 |
| `docs/PRODUCT_SPEC.md`、`docs/ARCHITECTURE.md` | 规格 | 定义产品与技术架构（含 Stage9 来源扩展）；进度不在此 |
| `docs/DEV_LOG.md` | 流水账 | 按时间追加的开发记录（历史证据） |
| `TASKS.md` | 任务清单 | 当前/历史任务；顶部指向本文件 |
| `docs/ACCEPTANCE.md` | 1.1 历史验收清单 | 已被 2.0 实际进度取代，仅供追溯 |
| `TAKEOVER_READ_FIRST.md`、`HANDOVER.md`、`REVIEW_SUMMARY.md` | **历史快照** | 分别停在 1.1 接管期 / 1.1.0 / Stage8I |
| `STAGE8J_RESULT.md`、`STAGE9_RESULT.md`、`docs/STAGE9_*` | **历史快照** | 阶段交付/体检记录 |
| `deploy_handoff/*` | 历史产物 | 包含 1.5.2/Alpha15 与 1.6.0/Alpha16 历史产物；Alpha17 产物未构建 |
| `review_meta/` | 本地证据（git 忽略） | 回执/日志/调查报告，不入库 |

## 9. 变更记录

| 日期 | 变更 | 证据 |
| --- | --- | --- |
| 2026-10-23 | 图片 404 归类 + 失败诊断补齐；修正陈旧版本断言；全量 pytest 693/695 | `747e704`、`docs/DEV_LOG.md:2870-2890` |
| 2026-10-24 | 建立本文件为唯一活状态源；修订 README/TAKEOVER/HANDOVER/REVIEW_SUMMARY/TASKS/deployment 等 S 级漂移；完成跳过 Jellyfin 可行性评估；GitHub 恢复后推送 `2dd9ff2..5786f8f` | `review_meta/ops-20261024/progress-audit.md`、`jellyfin-skip-assessment.md`；`git push` |
| 2026-10-24 | 本地源为主 P0/P1：新增 `native_delete_v1` 与本地两阶段安全删除、去 Jellyfin 诊断/文案收口、Android 只信任 `can_delete`；Server 全量 709/711（2 项环境）、Android 507/507 | `feature/mediareview-local-only`、`docs/DEV_LOG.md`、`review_meta/ops-20261024/*.log` |
| 2026-10-25 | Codex 复审 C1/I4/M1；完成 C1 句柄绑定删除、I1 队列代次(0017)、I2 状态机+恢复(含 Lead 修复本地恢复误判)、I3 Android fail-closed、M1 审计；Server 721/723（2 环境）、Android 511/511 | `review_meta/codex-review-20261025/`、`review_meta/codex-fix-20261025/`、`docs/DEV_LOG.md` |
| 2026-10-25 | 第七轮复审后进入 P5；源码版本统一为 Server 1.6.0 / Alpha16-code23 / Contract2 / 0017，builder 要求 14 能力；产物未构建 | `review_meta/codex-review-20261025-round7/p5-report.md`、`docs/DEV_LOG.md` |
| 2026-10-26 | UI P1–P4 完成后准备 Alpha17 源码：Android code24、中文 UI 重构 Release Notes、版本/兼容文档同步；受影响 Android 20/20、Server 32/32 通过，产物未构建 | `review_meta/ui-refactor-20261025/alpha17-report.md`、`docs/DEV_LOG.md` |

## 10. Codex 独立复审（2026-10-25，本地删除阻断项）

Codex `gpt-5.6-sol` 对 `37e93e2..373025b` 做独立安全复审（182,126 tokens），报告 `review_meta/codex-review-20261025/local-delete-review.md`。结论 **C1 / I4 / M1，阻断发布**：

| 项 | 级别 | 摘要 | 处置 |
| --- | --- | --- | --- |
| C1 | Critical | 复验句柄关闭后仍按路径 `os.remove`，未绑定 st_dev/st_ino；并发换链可删除登记根外文件或同尺寸替换文件 | **已修（源码，未提交）**：Windows 句柄 disposition 删除 + fail-closed + 确定性竞态测试（本机 junction 实测执行） |
| I1 | Important | nonce 只绑定 media_id；撤销后同 ID 重新入队会被旧 nonce 删除 | **已修（源码，未提交）**：队列代次 queue_token + 迁移 0017 + nonce 绑定 + 同 ID 回归 |
| I2 | Important | 文件删除与 nonce/审计/索引事务不原子；删除后失败会"文件已删但请求 500、nonce 未消耗" | **已修（源码，未提交）**：先提交 claim/committing → 逐项独立事务 → 启动恢复 + 逐项异常隔离；另修本地源恢复误判（Lead 发现） |
| I3 | Important | Android can_delete 缺字段/缓存 miss 时 fail-open | **已修（源码，未提交）**：DTO 可空、仅 `==true` 放行、缓存 miss/缺字段 fail-closed + 测试 |
| I4 | Important | 安全测试缺竞态/同 ID/删除后事务失败/Android 缺字段用例 | **已补**：竞态/同 ID/旧快照/异常恢复/迁移回填/Android 缺字段与缓存 miss |
| M1 | Minor | 快照中跳过/非 pending 项无逐条审计 | **已修**：稳定审计码（无路径） |

**验证**：Server 全量 **721 通过 / 2 失败 / 723 项 / 437.51s**（2 项为本机 ffmpeg 完整性环境限制，换可写 basetemp 单独复跑 2 通过）；Android 全量 JVM **511 tests / 0 失败**；ruff 改动文件通过。

**P5 发版继续暂停**：待 Codex 额度复位后（2026-10-25 05:44）做**二次独立复审**，通过后提交/推送并进入 P5。当前分支 `feature/mediareview-local-only`。

## 11. 第二轮独立复审（2026-10-25 05:52）

Codex `gpt-5.6-sol` 对修复提交 `373025b..cfe7ca5` 做第二轮复审，报告 `review_meta/codex-review-20261025-round2/round2-review.md`。结论 **BLOCK：C0 / I2 / M2**（C1、I1、I3 判定已关闭）。

| 编号 | 级别 | 问题 | 处置 |
| --- | --- | --- | --- |
| I5 | Important | Windows delete-on-close：disposition 成功后若其他 FILE_SHARE_DELETE 句柄未关，文件仍可见；旧实现会落 `failed`，但文件稍后永久消失 | **已修（源码）**：disposition 成功即按 success 收敛，不再用 lstat/CloseHandle 改判；真实第二共享句柄测试 |
| I6 | Important | 0017 downgrade 未处理 `committing` 行 | **已修（源码）**：存在 committing 时 fail-closed 拒绝降级且不改写；补回滚测试 |
| M2 | Minor | 非执行快照分支审计失败被静默吞掉 | **已修（源码）**：三类审计并入 claim 同事务，失败整体回滚、nonce 不消费 |
| M3 | Minor | Win32 ERROR_ACCESS_DENIED(5) 被误报为 file_in_use | **已修（源码）**：5→`delete_access_denied`，32/33→file_in_use；Android 中文映射 |

P1 发版仍阻断；待第三轮独立复审通过后再评估 P5。提交 `f3f2149`/`90498cc`/`cfe7ca5` 已于 2026-10-25 05:46 推送到 GitHub（`373025b..cfe7ca5`）。

## 12. 第三轮复审与 I5-A/I5-B 修复（2026-10-25 07:12）

第三轮 Codex 复审（`review_meta/codex-review-20261025-round3/round3-review.md`）判定 **BLOCK**：I6/M2/M3 已关闭，但 I5 与 I2 组合仍存在 "delete-pending 后元数据事务失败先落 failed、文件后消失"（I5-A）与 "CloseHandle 失败被忽略却清理元数据报 success"（I5-B）。

修复（Codex，`review_meta/codex-fix-20261025-round3/fix-report.md`）：
- Windows 优先 `FILE_DISPOSITION_INFO_EX` + POSIX 语义：disposition 后关闭句柄并以 `lstat` 验证名字消失才算 success；不支持才回退传统 disposition，回退路径只返回 `pending`。
- 新增持久状态 `delete_done` / `delete_pending` 与 receipt `pending` / `pending_count`；`delete_done` 先独立提交再清理元数据，清理失败保持 `delete_done`（success，恢复补清理并审计）；`delete_pending` 由恢复只观察，名字消失后转 `delete_done`；重复恢复幂等、不再次删除。惰性恢复入口加入 `GET /delete-queue` 与 `prepare`。
- CloseHandle 失败：POSIX 名字已验证消失仍 success；传统路径保持 pending；不写路径。
- Android 支持 `Pending` 状态与"删除请求已提交，等待系统完成"，旧 Server（无 pending 字段）兼容。

验证：Codex 定向四文件 **94 passed**；真实 Windows 专项 5 passed；Android 3 类 36/36；Lead 全量 Server **736/739**（2 项本机 ffmpeg 环境 + 1 项 10 万行性能抖动，三项单独复跑均通过）、Android **511/511**；ruff 通过。

P1 仍待第四轮定点复审通过后发版。

## 13. 当前等待项（2026-10-25 07:29）

- 第三轮修复已提交并推送：`485e2e7`（POSIX disposition + delete_done/delete_pending 状态机）、`48aa26f`（Android pending 展示）、`03083da`（文档）；远端 `cfe7ca5..03083da`。
- **第四轮 Codex 定点复审因 5 小时额度用尽顺延**（复位 2026-10-25 10:47；Codex CLI 报 usage limit）。P5 发版继续暂停，等复审结论。
- Lead 全量：Server 736/739（2 项本机 ffmpeg 环境、1 项 10 万行性能抖动，三项单独复跑均通过）；Android 511/511；ruff 改动文件通过。

## 14. 当前等待项（2026-10-25 12:45）

- 第六轮修复提交 `5aa367a`（Jellyfin 恢复父链/身份证据收紧）本地领先 origin 1；push 因 GitHub 网络暂时失败，待重试。
- 第七轮 Codex 定点复审因 5 小时额度用尽顺延（复位 15:49；Codex CLI 报 usage limit）。P5 发版继续暂停。
- Lead 全量：Server 743/745（2 项本机 ffmpeg 环境，单独复跑通过）；Android 511/511；ruff 改动文件通过。

## 15. P5 Alpha16 发版产物（历史，2026-10-25 17:32）

第九轮 Codex 复审结论 **PASS_WITH_MINOR**（C0/I0/M1，M8-T 为测试覆盖缺口，不阻断）。P5 源码与产物已完成：

| 项 | 值 |
| --- | --- |
| Server | 1.6.0 / API Contract 2 / 迁移 0017 / 14 能力（含 `native_delete_v1`） |
| Android | 2.0.0-alpha16 / code 23 / `com.mediareview.app` |
| 构建 HEAD | `ced2f07`（build 与源码同 HEAD；工作区 clean） |
| Server ZIP | `deploy_handoff/MediaReview_Server_1.6.0_stage9.zip`，133,242,411 bytes，SHA256 `04be46db617d4f238e485055e339aaf500cfb4f05172940a30e367ef69bdffdf` |
| APK | `deploy_handoff/MediaReview_2.0.0-alpha16_stage9.apk`，43,606,606 bytes，SHA256 `42d188cb37b4b45c3bcedacc8ef5b8670ae61844b78e066d8b477a40be3b1dfa`，signature PASS |
| EXE | SHA256 `2a9f5cfe45f98b8c24bf644245dcef29fcb85240703206c59d3e495feb73064d` |
| 部署/真机 | **部署 NOT PERFORMED；家庭 LAN/真机 NOT TESTED**（需用户管理员停服备份、连接手机验收） |

升级/回滚边界见 `deploy_handoff/MediaReview_Server_1.6.0_stage9/UPGRADE_ROLLBACK.md`：0017 在存在 `committing`/`delete_done`/`delete_pending` 行时降级 fail-closed；Windows 本地删除最低 Windows 10 1709（POSIX 语义）。

## 16. Android UI 大规模重构（2026-10-26，P1–P4）

用户确认方向：**Google Photos 式相册体验 + 文件夹分类**；批阅优先“视觉极简、进度与导航、更好的预览”。需求基线 `docs/UI_REFACTOR_PLAN.md`，Codex 实现、Lead 规划/复核/模拟器走查。

| 阶段 | 提交 | 内容 | 验证 |
| --- | --- | --- | --- |
| P1 | `be3c92d` | 设计 token + 公共组件；首页顶栏收敛/更多菜单/无边框网格；目录折叠顶栏+横滑面包屑；搜索全屏化 | 513 tests / 0 fail；Lead rerun 通过 |
| P2 | `66ca2f9` | 相册按日期分节网格；沉浸式查看器（缩放/翻页/下滑关闭/动画/±1 预取）；顶底栏与信息层重做 | 522 tests / 0 fail |
| P3 | `3abc77b` | 批阅极简顶栏+进度、底部五项动作、左右滑切换、上下滑决策、撤销、快进一屏 | 529 tests / 0 fail；Lead rerun 通过 |
| P4 | `ab9b393` | 打磨：沉浸页隐藏底栏、segmented/边缘渐隐、下拉刷新、触觉反馈、空/错/载态、无障碍与性能；修复批阅原生 Surface inset 时序 | 79 suites / 533 tests / 0 fail；Lead rerun 通过 |

模拟器（MediaReview_Test，demo 数据）截图：`review_meta/ui-refactor-20261025/shots/`（home、album、imgviewer、review）。
Alpha17 源码准备将 UI 重构写入 Release Notes，并把 Android 源码版本提升到 code24；未做：真机/家庭 LAN 验收、长按多选批量、Alpha17 APK 构建与发布。

## 17. Jellyfin 库接入与扫描（2026-10-26）

用户反馈“更新了库但不知道成功没”，账号 moviehub 已确认（仅本机 Jellyfin 使用）。排查结果与处置：

- 根因：Jellyfin 同时存在「视频合集（混合）」库（指向整个 `D:\视频合集`）和 19 个分类库（各自指向子目录）；Jellyfin 报 `Found duplicate path`，分类库因路径重复全部 0 项，扫描长时间卡在 ~63%。
- 处置（用户确认“删混合库，保留 19 个分类库”）：取消卡住的扫描 → 删除混合库（仅 Jellyfin 索引，未动文件）→ 触发全库刷新。当前 19 个分类库已开始正常索引。
- 进度：截至 2026-10-26 01:10，19 库合计约 **35,300 项**（`图片集` 31,863、`工作文件` 1,667、`高颜值素人泄密150套合集` 1,221 等），扫描仍在运行（~96%，照片多且部分图片损坏导致 TagLib 报错被记录并跳过）。监控日志：`temp/jellyfin_scan_monitor.log`。
- 后续：扫描完成后在 MediaReview App 的库选择里勾选需要的分类库并同步即可；本次未改 Server、未改 Jellyfin 媒体文件。

## 18. Android UI 重构状态（历史检查点，2026-10-26 01:10）

- P1 `be3c92d`、P2 `66ca2f9`、P3 `3abc77b` 已提交（本地 ahead 2，GitHub 网络不稳定待推送）；P4 打磨因 Codex 额度用尽于 04:33 复位后继续。
- 截图走查：`review_meta/ui-refactor-20261025/shots/`（home/album/imgviewer/review，模拟器 MediaReview_Test + demo 数据）。

## 19. Jellyfin 扫描完成与限制（2026-10-26 01:06）

- 扫描于 2026-10-26 01:05:46 完成（Completed），19 个分类库合计 **36,161 项**；监控日志 `temp/jellyfin_scan_monitor.log`。
- 库结构：19 个分类库（`图片集` 32,724、`工作文件` 1,667、`高颜值素人泄密150套合集` 1,221 等）。`D:\视频合集` 下仍有 5 个文件夹未建库：`BeautyBox`(55 文件)、`JX`(92)、`CS`(13)、`Maniacccccc`(79)、`大神胖哥迷玩`(407)；需要可再添加。
- 已知限制：部分含超大视频的库入库数远少于文件数（如 `12345` 131 个 mp4 → 1 项，`3165260528` 288 → 2 项）。Jellyfin 日志显示 ffmpeg 抽帧/探测超时（10s），大文件/异常封装会被跳过或延迟；这是 Jellyfin 媒体探测限制，不是扫描失败。
- 建议：MediaReview 优先使用**本地源**直接读取 `D:\视频合集`（不经 Jellyfin 探测）；Jellyfin 侧如需完整索引，可后续调大 ffprobe/ffmpeg 超时或改用兼容封装。
- 未修改任何媒体文件；未改动 Server/API。

## 20. Android UI P4 状态（2026-10-26）

- P4 打磨（沉浸页隐藏底栏、segmented/边缘渐隐、下拉刷新、触觉反馈、空/错/载态、无障碍、性能）已完成并提交为 `ab9b393`；Codex 与 Lead 复核均为 79 suites / 533 tests / 0 失败。
- 批阅视频黑屏在 P3 基线 APK 上同样复现（模拟器 goldfish/软解限制），不作为 P4 回归；真机视频画面待验收。

## 21. Android Alpha17 交付（2026-10-26）

- UI 重构 P1–P4 + 打磨已提交：`be3c92d`/`66ca2f9`/`3abc77b`/`ab9b393`；Alpha17 版本与 Release Notes：`4fe38d2`。
- 交付产物：`deploy_handoff/MediaReview_2.0.0-alpha17_stage9.apk`（43,655,762 bytes，SHA256 `8782f42b…6571f6`，versionCode 24 / 2.0.0-alpha17，signature PASS），已发送到用户确认的 QQ 邮箱（SMTP accepted + SEND_OK 附件字节/哈希一致）。
- Server 保持 1.6.0（本次仅 Android UI 变化）；`--package-verified` 因 1.6.0 同名发布包已存在而拒绝覆盖，故未生成新 Server ZIP。
- Jellyfin 已补建剩余 5 个分类库（共 24 库），重新扫描进行中（~96%）；真机验收仍待用户完成。

## 22. Jellyfin 24 库最终状态（2026-10-26 09:02）

- 24 个分类库全部建立并扫描完成（Completed），合计 **36,532 项**。
- 新增 5 库：`大神胖哥迷玩` 346、`JX` 19、`Maniacccccc` 4、`BeautyBox` 1、`CS` 1；超大视频仍受 Jellyfin ffprobe 超时限制（如 `12345` 131 文件→1 项），不是扫描失败。
- MediaReview 建议优先使用本地源直读 `D:\视频合集`；Jellyfin 作为可选源。
