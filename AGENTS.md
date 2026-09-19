# AGENTS.md — MediaReview 开发总规则

> **流程变更（2026-09-19）**：本项目已托管至 GitHub 公开仓库（远程 `origin`，默认分支 `main`），GitHub 为唯一主要源码交接渠道。交接流程改为：任务 → 开发 → `git commit` → `git push` → 真机测试 → 基于 GitHub commit / diff 验收。原则上不再单独为 ChatGPT 制作完整源码 ZIP；本文档第 6 节验收包流程保留为阶段自检参考。

本文件是本项目所有 Agent、Codex、Trae 或其他自动化开发代理的最高优先级工程规则。

## 1. 工作方式

Agent 必须：

- 在当前工作目录内完成全部开发，不在项目外创建依赖工程。
- 每开始一个阶段前阅读对应文档。
- 每完成一个阶段后运行测试并更新 `docs/DEV_LOG.md`。
- 任何架构级变更先更新文档，再改代码。
- 不得私自改变已经确认的产品逻辑。
- 不得将临时脚本、测试缓存、FFmpeg 生成物、APK、日志散落到根目录。
- 不得把真实 Token、密码、IP、绝对路径提交到仓库。
- 用户界面和用户可见提示全部中文；英文仅允许用于代码、日志、协议字段、技术名称。

## 2. Git 规则

建议分阶段提交：

- `feat/server-bootstrap`
- `feat/jellyfin-adapter`
- `feat/media-index`
- `feat/review-engine`
- `feat/sprite-service`
- `feat/duplicate-detection`
- `feat/android-shell`
- `feat/media-wall`
- `feat/player`
- `feat/review-mode`
- `feat/admin`
- `feat/deployment`
- `chore/release-hardening`

每个阶段提交前：

- 工作区必须可构建。
- 对应自动化测试通过。
- `docs/DEV_LOG.md` 写清完成内容、测试结果、遗留问题。
- 禁止一个提交同时混入大量无关重构。

## 3. 禁止事项

- 禁止中间层转发完整视频流，除非 Jellyfin 直连方案确认不可行。
- 禁止直接修改 Jellyfin 数据库。
- 禁止把点赞、批阅、待删除等状态写进原始媒体文件。
- 禁止把雪碧图、缩略图、JSON sidecar 写入媒体所在目录。
- 禁止删除文件时“点击即永久删除”。
- 禁止客户端依赖服务器真实文件绝对路径。
- 禁止 API 无版本号。
- 禁止耗时任务阻塞普通 HTTP 请求。
- 禁止在 Android UI 线程执行网络、Hash、文件或媒体分析任务。
- 禁止为了“优化”而提前加入公网访问、多人账号、云同步等 V1 未定义能力。

## 4. V1 核心范围

必须完成：

- Android 手机 App。
- Jellyfin 多媒体库读取与勾选。
- 媒体墙。
- 封面大小调节。
- 排序与筛选。
- 视频与图片普通浏览。
- Media3 普通视频播放器。
- 雪碧图预览。
- 视频/图片批阅模式。
- 当前视频 P0、下一视频 P1 预加载。
- 点赞。
- 待删除队列。
- 最终删除确认。
- 批阅会话断点恢复。
- 完全重复检测。
- 疑似重复基础能力和独立页面。
- 中间层 FastAPI。
- SQLite。
- FFmpeg。
- Web 管理后台。
- 自动发现 + 手动 IP 兜底 + 一次配对码。
- ZIP 自动部署。
- 日志、健康检查、诊断导出。
- 完整测试与验收。

V1 不强制：

- 公网访问。
- 多用户。
- 云账号。
- 复杂影视刮削。
- AI 内容识别。
- 自动删除疑似重复。
- iOS。

## 5. 完成定义

只有满足 `docs/ACCEPTANCE.md` 全部 P0/P1 条目，才允许标记 V1 完成。

## 6. 阶段交接与 ChatGPT 验收包

完整细则以项目根目录 `REVIEW_HANDOFF_RULES.md` 为准，此处为其强制摘要。自当前阶段起，每个阶段完成后必须遵守。

### 6.1 阶段完成前必须依次执行

Agent 在宣布“阶段完成”之前，必须按顺序完成：

1. 完成本阶段代码。
2. 运行本阶段全部测试。
3. 运行项目已配置的全部 lint / format / type check。
4. 更新 `docs/DEV_LOG.md`。
5. 更新 `TASKS.md`。
6. 记录 Git 状态与本阶段提交。
7. 生成本阶段代码 diff。
8. 生成 `REVIEW_SUMMARY.md`。
9. 在 takeover plan 的 Task 0A 已补齐并通过测试后，执行 `scripts/build_review_handoff.py --stage XX --name 阶段名` 生成验收 ZIP。脚本当前缺失时不得跳过、手工伪造或宣称阶段完成。
10. 确认 ZIP 成功生成后，才允许向用户汇报阶段完成。

验收 ZIP 输出到 `review_handoff/`，命名形如 `MediaReview_Review_Stage-XX_YYYYMMDD_HHMM.zip`。

### 6.2 REVIEW_SUMMARY.md 必须包含

- 当前阶段编号与名称、目标、实际完成内容、是否完整达到目标。
- 主要新增/修改文件、核心架构变化、API 变化、数据库变化。
- Android UI/交互变化（如适用）。
- 已执行测试、测试结果、lint / format / type check 结果。
- 已知问题、遗留 TODO、是否建议进入下一阶段。
- Agent 自认为风险最高的 3 个点。
- 结尾必须明确写：`阶段结论：合格 / 有条件合格 / 不合格`。

### 6.3 ZIP 内容与约束

必须包含 `REVIEW_SUMMARY.md`、关键项目状态文档、Git 信息（status/log/diff stat/diff patch）、测试与 lint 结果文件，以及本阶段关键源码。

禁止打包：`.git/`、`.venv/`、`venv/`、`node_modules/`、`.gradle/`、`build/`、`dist/`、APK/AAB/EXE、大型二进制、FFmpeg 二进制、媒体/视频/图片原文件、雪碧图缓存、SQLite 真实用户库、日志全集、密钥/Token/密码/`.env`、含真实 Jellyfin API Key 的配置、含隐私且与验收无关的文件。含 Secret 的配置文件必须输出脱敏副本。

大小目标：单阶段验收 ZIP `< 5 MB`（改动很大时 `< 15 MB`）。

### 6.4 诚实原则

不得为了“让验收包好看”而隐藏失败测试或未提交改动。测试/lint 结果必须记录真实命令与真实输出，禁止编造“全部通过”。

### 6.5 用户汇报格式

阶段完成后对用户只需简洁汇报：阶段 X 完成状态、测试结果、Git 工作区状态、验收 ZIP 文件路径、是否建议进入下一阶段。详细内容全部放进验收 ZIP。

### 6.6 最终 Release 例外

最终 Release 阶段除验收 ZIP 外，还需单独输出部署产物（Server ZIP、Android APK、HANDOVER、Release Notes）。验收 ZIP 与部署 ZIP 是两种不同文件。

## 7. 最终 APK 邮件交付（Hermes）

完整细则见 `docs/HERMES_APK_EMAIL_DELIVERY.md`，以下为强制规则：

1. 只有 Release/RC APK 完成对应自动化、签名、SHA-256、恶意软件扫描和真机边界说明后才能发送。
2. 发送前必须让用户确认收件地址；仓库、共享 handoff 和日志不得记录完整邮箱或 SMTP 凭据。
3. 项目 Agent 通过 `E:\aihome\shared\outbox\<agent>\` 交付 APK，并在 `shared\inbox\hermes\` 写无密钥请求；不得修改或读取 Hermes 的 `config/secrets.env`。
4. 写入 Hermes inbox 不会自动唤醒 Hermes；必须通过 AI Home 的 Agent 协作入口显式通知/唤醒 Hermes，并取得它对请求文件的接收回执。
5. Hermes 工具位于 `E:\aihome\hermes\scripts\send_mail.py`。截至 2026-08-30，它仍把附件当图片处理，**不能直接发送 APK**；必须由 Hermes 在自己房间先修复并验证非图片 MIME、邮件大小上限、拒收检查和 fail-closed 行为。
6. 交付成功必须同时出现 SMTP accepted 与 `SEND_OK attachment=<文件名> bytes=<准确字节数> sha256=<匹配值>`。字节数和哈希必须从实际附加的内容计算；正文发送成功但附件失败不算交付。
7. 邮件发送是最终外部动作，不得因“自动交付”绕过用户收件地址确认；输出和记录中的地址必须掩码。
8. 若执行 Agent 不在 AI Home 主机，只交回 APK、CHECKSUMS 和验证报告，由 AI Home 上的 Hermes 发送；禁止自建 SMTP 或复制 Hermes 凭据。
