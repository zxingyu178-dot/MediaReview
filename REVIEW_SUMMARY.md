# REVIEW_SUMMARY — MediaReview 1.1 Task 0A：可移植阶段验收工具

## 阶段编号与名称

- 阶段 0A（takeover plan `docs/superpowers/plans/2026-08-30-mediareview-1.1-takeover.md`）
- 名称：补齐可移植阶段验收工具
- 基线：`fe4666b`（本阶段 diff = `git diff fe4666b` 基线到工作树）

## 阶段目标

按 `AGENTS.md` §6 与 `REVIEW_HANDOFF_RULES.md` 的要求补齐缺失的
`scripts/build_review_handoff.py`，使后续每个阶段都能生成统一的 ChatGPT 验收 ZIP；
脚本缺失时任何阶段不得宣称完成（2026-08-30 接管审计确认原规则引用的脚本不存在）。

## 实际完成内容

- 新增 `scripts/build_review_handoff.py`（仅标准库 argparse/subprocess/pathlib/zipfile）：
  - CLI：`--stage --name --base [--output-dir review_handoff] [--max-file-kb 1024] [--max-zip-mb 15]`。
  - 打包范围：`git diff --name-only <base>`（基线到工作树、仅已跟踪文件，不含未跟踪文件）
    + 8 个固定状态文档 + `review_meta/` 证据文件 + git status/log/diff stat/diff patch。
  - 只包含已批准文本扩展名（.py/.kt/.kts/.java/.xml/.toml/.yaml/.yml/.json/.sql/.md/.ps1/.bat/.gradle/.properties）。
  - 禁止类别（build/dist/.gradle/node_modules/logs、.db/.apk/.aab/.exe/.jar/.log/.so/.dll 等）
    与超过单文件上限的文件：不打包，逐条记录到 `review_meta/excluded_files.txt`。
  - fail-closed（非零退出且不产出 ZIP）：疑似密钥路径（`.env`/`*.env`/`secrets.env`/`*.jks`/
    `*.keystore`/`key.properties`/主机密钥/证书私钥）、仓库外或非法路径、git 不可用、无效或
    非祖先 `--base`、缺少 `REVIEW_SUMMARY.md` 或"阶段结论"行、缺少任何测试/静态检查证据、
    ZIP 写入失败、ZIP 超过 `--max-zip-mb`。
- 新增 `server/tests/test_review_handoff_builder.py`：15 个端到端测试，在临时 Git 仓库中
  真实运行构建器（隔离 GIT_CONFIG_GLOBAL/SYSTEM），覆盖必需条目、文件名格式、状态/补丁
  内容、删除路径、未跟踪排除、密钥 fail-closed、禁止类别排除、超限排除、ZIP 大小上限、
  非法 stage 参数与 git 不可用。
- 文档同步：`REVIEW_HANDOFF_RULES.md` 步骤 9 更新为可用状态与证据文件要求；
  `docs/TOOLCHAIN_AND_RELEASE_OPERATIONS.md` 工具表与缺口章节更新；
  `TASKS.md` 新增 Task 0A 节；`docs/DEV_LOG.md` 新增本阶段记录。

## 是否完整达到目标

是。构建器已可用并经端到端测试验证；本阶段自身的验收 ZIP 即由它生成（自举验证）。

## 核心架构 / API / 数据库变化

- 无生产架构、API、数据库变化；本阶段只新增构建工具与测试，不影响 Server/Android 运行行为。

## Android UI/交互变化

- 无。

## 已执行测试与结果（真实命令）

```powershell
# RED（实现前）
server\.venv\Scripts\python.exe -m pytest tests/test_review_handoff_builder.py
# => 12 failed, 3 passed (3 个为"脚本不存在即非零"的空真通过)

# GREEN（实现后）
server\.venv\Scripts\python.exe -m pytest tests/test_review_handoff_builder.py
# => 15 passed in 9.25s

# 全量
$env:MEDIAREVIEW_DATA_ROOT = <临时目录>
server\.venv\Scripts\python.exe -m pytest tests
# => 273 passed, 1 warning in 162.17s (exit 0)  [258 旧 + 15 新]
```

## lint / format / type check 结果

```powershell
server\.venv\Scripts\python.exe -m ruff check .          # => All checks passed!
server\.venv\Scripts\python.exe -m ruff format --check . # => 86 files already formatted
```

项目未配置独立 type check；ruff（含 pyflakes/UP/B/S 规则集）即当前静态检查门禁。

## 已知问题 / 遗留 TODO

- 证据文件（`review_meta/server_tests.txt` 等）由执行 Agent 手工写入；构建器只强制存在性，
  "记录真实命令与真实输出"仍依赖独立审查复核（诚实原则）。
- 密钥检查为路径级，不做内容级扫描；内容脱敏仍按 `REVIEW_HANDOFF_RULES.md` §6 人工执行。
- 构建器目前不打包 `docs/TOOLCHAIN_AND_RELEASE_OPERATIONS.md` 等规则清单之外的状态文档
  （规则 §3 B 为"优先包括"清单，未擅自扩大）。

## 是否建议进入下一阶段

建议进入 Task A：关闭 Task 4 的两个 Important（最终删除 `success` 协议错配、生产主壳
旧 settle token 竞态集成测试缺口），并以新独立审查 CLEAN 作为 Task 4 完成条件。

## Agent 自认为风险最高的 3 个点

1. **证据真实性依赖流程而非技术**：构建器无法验证 `review_meta/` 内测试输出是否真实执行，
   若 Agent 伪造证据文件，验收包表面仍然完整——必须靠独立审查对照命令与输出。
2. **`git diff <base>` 的范围语义**：打包范围是"基线到工作树"，若执行者在构建 ZIP 之后、
   提交之前再改动文件，ZIP 内容与最终提交会不一致；必须在构建后立即提交且不再改动。
3. **路径级密钥检测的盲区**：密钥若出现在非常规命名文件（如 `notes.md`、`config.json`）中，
   构建器不会拦截，仍会进入 ZIP——含密钥的配置脱敏责任在执行 Agent 与审查者。

## 敏感信息说明

本阶段未接触任何密钥、Token、真实配置或生产数据；所有测试使用临时目录与临时 Git 仓库。
`android/local.properties` 与 `server/.venv` 为本机环境文件，已被 Git 忽略，不进入提交与 ZIP。

阶段结论：合格
