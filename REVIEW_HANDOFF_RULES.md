# MediaReview — ChatGPT 验收交接包规则

本规则加入项目根目录后，对所有后续开发阶段生效。

## 1. 目的

每个开发阶段完成后，Agent 必须在项目根目录生成一个专门供 ChatGPT 验收的轻量 ZIP。

用户以后只上传这个 ZIP，不需要上传整个项目目录。

建议输出目录：

`review_handoff/`

建议文件名：

`MediaReview_Review_Stage-XX_YYYYMMDD_HHMM.zip`

例如：

`MediaReview_Review_Stage-04_20260819_2230.zip`

---

## 2. 每个阶段结束时必须执行

Agent 在宣布“阶段完成”之前，必须依次：

1. 完成本阶段代码。
2. 运行本阶段全部测试。
3. 运行 lint / format / type check（项目已配置的全部检查）。
4. 更新 `docs/DEV_LOG.md`。
5. 更新 `TASKS.md`。
6. 记录 Git 状态和本阶段提交。
7. 生成本阶段代码 diff。
8. 生成 `REVIEW_SUMMARY.md`。
9. 执行 `scripts/build_review_handoff.py`。注意：2026-08-30 接管审计确认该脚本当前缺失；下一位 Agent 必须先按 takeover plan 的 Task 0A 用测试补齐。脚本不存在时不得伪造验收 ZIP 或宣称阶段完成。
10. 确认 ZIP 成功生成后，才允许向用户汇报阶段完成。

---

## 3. ZIP 必须包含

### A. `REVIEW_SUMMARY.md`

必须包含：

- 当前阶段编号与名称
- 本阶段目标
- 实际完成内容
- 是否完整达到阶段目标
- 主要新增/修改文件
- 核心架构变化
- API 变化
- 数据库变化
- Android UI/交互变化（如适用）
- 已执行测试
- 测试结果
- lint / format / type check 结果
- 已知问题
- 遗留 TODO
- 是否建议进入下一阶段
- Agent 自己认为风险最高的 3 个点

结尾必须明确写：

`阶段结论：合格 / 有条件合格 / 不合格`

### B. 项目状态文档

优先包括：

- `AGENTS.md`
- `TASKS.md`
- `docs/DEV_LOG.md`
- `docs/PRODUCT_SPEC.md`
- `docs/ARCHITECTURE.md`
- `docs/DEVELOPMENT_PLAN.md`
- `docs/ACCEPTANCE.md`
- `docs/API_CONVENTIONS.md`

如果某文件不存在，不要报错中断。

### C. Git 信息

自动生成：

- `review_meta/git_status.txt`
- `review_meta/git_log.txt`
- `review_meta/git_diff_stat.txt`
- `review_meta/git_diff.patch`

要求：

- `git_status.txt`：`git status --short`
- `git_log.txt`：最近 20 个提交
- `git_diff_stat.txt`：相对上一阶段基线的 diff stat；无法确定时至少输出 HEAD 相关信息
- `git_diff.patch`：本阶段真实代码 diff

禁止只写自然语言总结而不提供 diff。

### D. 测试信息

自动生成或复制：

- `review_meta/test_results.txt`
- `review_meta/lint_results.txt`

如果项目分别有 Server / Android：

建议分别输出：

- `review_meta/server_tests.txt`
- `review_meta/server_lint.txt`
- `review_meta/android_tests.txt`
- `review_meta/android_lint.txt`

必须记录真实命令和真实结果，不允许 Agent 编造“全部通过”。

### E. 本阶段关键源码

必须包含本阶段新增或实质修改的核心源码。

原则：

- 优先按 Git diff 自动选取。
- 只打包文本源码、配置和数据库 migration。
- 不需要把整个历史源码全部复制进去。
- 如果某个改动需要上下文才能验收，可额外加入相关文件。

典型扩展名：

`.py`
`.kt`
`.kts`
`.java`
`.xml`
`.toml`
`.yaml`
`.yml`
`.json`
`.sql`
`.md`
`.ps1`
`.bat`
`.gradle`
`.properties`

---

## 4. ZIP 禁止包含

无论如何不得打包：

- `.git/`
- `.venv/`
- `venv/`
- `node_modules/`
- `.gradle/`
- `build/`
- `dist/`
- APK
- AAB
- EXE
- 大型二进制依赖
- FFmpeg 二进制
- Jellyfin 媒体
- 视频
- 图片原文件
- 雪碧图缓存
- thumbnail cache
- SQLite 真实用户数据库
- 日志全集
- 临时文件
- IDE 缓存
- 密钥
- Token
- 密码
- `.env`
- 含真实 Jellyfin API Key 的配置
- 含真实局域网隐私信息且与验收无关的文件

如果配置文件包含 Secret，必须输出脱敏后的副本。

---

## 5. 大小目标

正常单阶段验收 ZIP：

`< 5 MB` 为优先目标。

代码改动特别大时：

`< 15 MB`。

除非本阶段确实需要验收资源文件，否则不得生成几十 MB / 几百 MB 的验收包。

---

## 6. 安全要求

打包前必须检查：

- API Key
- Authorization Header
- Bearer Token
- password
- secret
- private path
- `.env`

发现密钥时：

- 不得放入 ZIP。
- 在 `REVIEW_SUMMARY.md` 中写“敏感配置已脱敏”。

---

## 7. ChatGPT 验收目标

该 ZIP 必须让我能够判断：

1. 这个阶段的代码是否真的做完。
2. 是否符合既定产品和架构。
3. 测试是否真实通过。
4. 有没有明显技术债或危险实现。
5. 是否可以进入下一阶段。
6. 下一阶段应该怎么做。

不要为了“让验收包好看”隐藏失败测试或未提交改动。

---

## 8. 阶段完成后的用户汇报格式

Agent 对用户最终只需简洁汇报：

- 阶段 X 已完成/未完成
- 测试结果
- Git 工作区状态
- 验收 ZIP 文件路径
- 是否建议进入下一阶段

详细内容全部放进验收 ZIP。

---

## 9. 最终 Release 例外

最终 Release 阶段除了验收 ZIP，还需要另外输出真正的部署产物：

- Server ZIP
- Android APK
- HANDOVER
- Release Notes

“验收 ZIP”和“部署 ZIP”是两种不同文件，不得混为一谈。
