# MediaReview 项目状态（唯一活文档 / SSOT）

> 更新时间：2026-10-24 19:00 (+08:00)
> 分支：`codex/mediareview-stage9-native-source` ｜ 与 `origin` 同步（2026-10-24 已成功推送；以 `git log -1` / `git status -sb` 为准）
> 工作树：干净（tracked 无改动）｜ GitHub：2026-10-24 网络恢复，已推送 `2dd9ff2..5786f8f`（含 `747e704` 与本次统一文档）
>
> **本文件是"当前状态"的唯一事实源。** `README` 只保留摘要；阶段文档（STAGE8*/STAGE9*、REVIEW_SUMMARY、TAKEOVER_READ_FIRST、HANDOVER 等）都是历史快照，不再代表当前状态。任何代码提交、发布、部署、服务恢复都必须同步更新本文件与末尾变更记录。

## 0. 一句话状态

源码已达到 **Server 1.5.2 / Contract 2 / 迁移 0016 + Android 2.0.0-alpha15/code22**，生产机已安装 1.5.2；但**官方 Windows 服务当前 Stopped，8766 由一个来源未确认的交互式实例占用**，Alpha15 **从未上真机**，已发布 1.5.2 **不包含** 10-23 的图片 404 修复（该修复只在源码 `747e704`，已于 2026-10-24 推送到 GitHub）。项目下一阶段的产品方向（是否跳过 Jellyfin、改为纯本地媒体源）**待用户决策**。

## 1. 权威版本表（SSOT）

| 组件 | 权威值 | 证据（文件:行 / 来源） |
| --- | --- | --- |
| Server 版本 | `1.5.2` | `server/app/__init__.py:3`、`deployment/version.ps1:12`、`server/pyproject.toml:3`（三处一致） |
| API Contract | `2` | `server/app/__init__.py:8`、`deployment/version.ps1:13` |
| Server 能力（13 项） | `native_sources_v1`、`native_range_v1`、`native_read_only_v1`、`folder_tree_v1`、`thumbnail_profiles_v1`、`review_folder_filter_v1`、`delete_receipt_v1`、`review_session`、`review_nearest`、`organize`、`delete_nonce`、`duplicates_paged`、`library_selection` | `server/app/__init__.py:11-25`；生产 health 与 `RELEASE_MANIFEST.json` 一致 |
| 数据库迁移 head | `0016_media_library_membership`（0001–0016，缺 0011） | `server/app/db/migrations/versions/`；`review_meta/stage9/repair-exe-proof.json`（`schema=0016`） |
| Android 版本 | `versionCode 22` / `versionName 2.0.0-alpha15` | `android/app/build.gradle.kts:32-33`；`review_meta/stage9/repair-apk-proof.json` |
| Android 包名 | `com.mediareview.app` | `android/app/build.gradle.kts:27` |
| 生产已装版本 | `1.5.2` | `C:\Program Files\MediaReviewServer\VERSION.txt` |

## 2. 正式产物（2026-10-24 实测哈希，与清单一致）

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

## 4. 验证矩阵（证据级别：A 实机/生产｜B 构建/隔离｜C 文档自述｜D 未执行）

| 项目 | 结果 | 级别 | 证据 |
| --- | --- | --- | --- |
| 10-23 Server 全量 pytest（含 404 修复） | **693 通过 / 2 失败 / 695 项 / 422.70s**；2 项为本机 ffmpeg 完整性级别环境问题，换可写 basetemp 单独复跑通过 | A（本机） | `review_meta/ops-20261023/server-full-green2.log`、`docs/DEV_LOG.md:2881-2885` |
| 10-23 改动前全量 pytest | 690 通过 / 4 失败 / 484.50s（失败均已分类） | A（本机） | `review_meta/ops-20261023/server-full-baseline.log` |
| 10-23 图片代理定向回归 | 16 通过（含新增 404 用例） | A（本机） | `docs/DEV_LOG.md:2883` |
| Android JVM | 75 suites / 498 tests / 0 失败（10-23 基线；之后未改 Android，未重跑） | C（复用基线） | `review_meta/ops-20261023/`（记录）、DEV_LOG |
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
| **S0** | 已发布 1.5.2 不含 `747e704` 404 修复 | 产物 `git_head=2858d3c` | 缺封面仍报 502，客户端 404 文案仍是死代码 | 决策是否重新打包 1.5.3 |
| **S0** | 官方服务 Stopped，8766 被来源未确认的 interactive 实例占用 | PID 3896（10-23 曾为 3916） | 无自启、ControlHub 不可管理、重启后手机不可达 | 管理员结束占用进程 → ControlHub 启动 → 3 次 health 核验 |
| **S1** | Alpha15 未上真机 | ADB 无设备 | 0016 跨库归属、UI 修复、手机/LAN 行为均无新版实机证据 | 装机后按 `USER_LAN_VALIDATION.md` 定向验收 |
| **S1** | seek 后 `Invalid NAL length` | 根因未确认，未做猜测性修改 | 拖动播放可能中断 | 按 `seek-nal-report.md` 取证后再修 |
| **S1** | `media_folders` 慢性慢查询 + 5s 台阶 | 已定位，未修 | 目录页 2.6–11.5s；缩略图 53 次 >5s | 先做零风险分段打点，再做结构性优化 |
| **S2** | 本地源永久删除被双端硬拒绝；本地源无重复检测/无转码 | 设计如此（Stage9 约定） | 用户"删除不想留的视频"需求当前不可行 | 见 §7 产品方向决策 |
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
| 10-24 进度统一 + 跳过 Jellyfin 评估 | 10-24 | 本次文档提交 | — | ✅（只读评估 + 文档） |

## 7. 产品方向：是否跳过 Jellyfin（待用户决策）

用户真实需求：**同一局域网下用手机看电脑视频，并能审核、删除不想留的视频。**

评估结论（详见 `review_meta/ops-20261024/jellyfin-skip-assessment.md`）：

- **可以跳过 Jellyfin**：不配置 API Key 时 Server 仍可登记本地根、扫描、出封面/原图、认证 Range 播放、记录进度；批阅/收藏/媒体墙/文件夹树已来源中立。
- **唯一真正阻塞需求的是"本地永久删除"**：当前 Server 能力表、入队、最终提交、Android 仓库与 UI 共 4+ 道门禁都硬拒绝本地删除。
- 第二大缺口是**无转码/HLS 兜底**：本地只能原盘直读，手机解码不了的编码会播不了（自带 `ffmpeg 7.1.3-Jellyfin`，具备自建 remux/转码的技术条件）。
- 可选缺口：本地雪碧图、本地重复检测、索引扩展名白名单偏窄。

推荐路线（方案 B 的产品方向 + 保留 Jellyfin 代码的工程形态）：

| 阶段 | 内容 | 规模 |
| --- | --- | --- |
| P0 | 去 Jellyfin 依赖收口（引导/诊断/部署文案、扩展名白名单） | S，1–2 人日 |
| P1 | **本地安全永久删除**（能力开关 + 复用 nonce/路径链/身份复验/审计/逐条失败 + Android 门禁改造） | M，3–5 人日 |
| P2（可选） | 删除可回滚（Windows 回收站或延迟删除 + 撤销窗口） | M，2–4 人日 |
| P3 | 播放兜底（按需 remux / HLS 转码，复用现有 FFmpeg 与单任务队列） | L，7–12 人日 |
| P4（可选） | 本地雪碧图 / 本地重复检测 / 平面目录修正 | S–M |
| P5 | 发布（Server 1.6.0 + Android alpha16/code23 + 回滚说明） | S，1–2 人日 |

需用户拍板：① 是否授权修改 `AGENTS.md` 的"本地来源永久删除双端拒绝"约定；② 删除是否必须可恢复；③ 是否授权本地视频转码/HLS（`AGENTS.md` 原禁止中间层转发视频流）；④ 本地删除开关默认开还是默认关；⑤ 转码只 remux 还是允许重编码。

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
| `deploy_handoff/*` | 产物 | 有效产物 = 1.5.2 Server ZIP + Alpha15 APK |
| `review_meta/` | 本地证据（git 忽略） | 回执/日志/调查报告，不入库 |

## 9. 变更记录

| 日期 | 变更 | 证据 |
| --- | --- | --- |
| 2026-10-23 | 图片 404 归类 + 失败诊断补齐；修正陈旧版本断言；全量 pytest 693/695 | `747e704`、`docs/DEV_LOG.md:2870-2890` |
| 2026-10-24 | 建立本文件为唯一活状态源；修订 README/TAKEOVER/HANDOVER/REVIEW_SUMMARY/TASKS/deployment 等 S 级漂移；完成跳过 Jellyfin 可行性评估；GitHub 恢复后推送 `2dd9ff2..5786f8f` | `review_meta/ops-20261024/progress-audit.md`、`jellyfin-skip-assessment.md`；`git push` |
| 2026-10-29 | 新增 NSFW Studio 生图审核 V2 客户端（独立配对 / feed / 详情 / 按 image_id 审核 / 提示词版本评分）；真实 Bridge 缺 v2 能力，联调 BLOCKED；生产版本与产物未变 | 分支 `feature/nsfw-image-review-v2`；`docs/integration/NSFW_MOBILE_API_CONTRACT_V2.md`；`REVIEW_SUMMARY.md` |

## 10. 2026-10-29 分支：NSFW 生图审核 V2（客户端）

- **范围**：只在 MediaReview Android 客户端新增「生图审核」一级页面；不改 NSFW Studio 仓库、Bridge、数据库或部署，不改 MediaReview Server/Jellyfin 接口，不加端口。
- **状态**：客户端契约冻结、独立配对与凭据隔离、feed/详情/审核/评分 UI 与模拟接口测试已完成；JVM 80 suites / 554 tests 全绿，`assembleDebug` 通过，lint 0 errors / 37 warnings。
- **真实联调：BLOCKED**。当前 NSFW Bridge `/mobile/system/health` 为 `api_contract=1`，能力集不含 `mobile_feed_v2 / mobile_image_details_v2 / mobile_image_decision_v2 / mobile_prompt_review_v2 / mobile_prompt_evaluation_v2`；客户端按契约在配对前阻断并列出缺失能力。
- **未验证**：无 ADB 设备，五入口 360dp 仪器测试已编译未执行；无真实 Bridge，未做手机端到端、未生成真机截图、未声明正式可用。
- **版本**：Android 仍为 `2.0.0-alpha15 / code22`；未发版、未改生产服务与数据。
- 契约细节见 `docs/integration/NSFW_MOBILE_API_CONTRACT_V2.md`；审查摘要见 `REVIEW_SUMMARY.md` 顶部。
