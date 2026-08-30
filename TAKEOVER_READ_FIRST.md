# 家庭媒体管家 1.1 — 接管入口

本目录是 MediaReview / “家庭媒体管家” 1.1 的完整 Git 项目。下一位 Agent 开始任何修改前，必须按顺序阅读：

1. `AGENTS.md`
2. `docs/HANDOFF_STATUS_2026-08-30.md`
3. `docs/superpowers/plans/2026-08-30-mediareview-1.1-takeover.md`
4. `.superpowers/sdd/task-4-post-fix-review.md`
5. `REVIEW_HANDOFF_RULES.md`
6. `docs/TOOLCHAIN_AND_RELEASE_OPERATIONS.md`
7. `docs/HERMES_APK_EMAIL_DELIVERY.md`

## 当前冻结点

- 分支：`feature/mediareview-1.1`
- 交接副本建立时 HEAD：`fac901e`；其历史必须包含 Task 4 源码提交 `ef528d363f4cca158c6fdbe60a59424fa0615c4a`。接管文档修正可能产生更晚的 HEAD，不应以 HEAD 等于某个旧提交作为门禁。
- 本 D 盘副本应保持无 Git remote。只有用户明确指定新的托管位置后才能添加 remote；禁止推送回 E 盘原源码目录。
- Task 0–3：独立审查 CLEAN。
- Task 4：**NOT CLEAN**，仍有 2 个 Important；当前 HEAD 只能作为继续返修的候选树，不能作为发布基线。
- Task 5–10：尚未按 1.1 规格实施和验收。
- 真实手机、真实 Jellyfin、真实局域网、Windows 干净机部署均未完成最终验收；没有真机证据时最多发布 RC。

## 绝对禁止

- 不得启动、停止、升级当前电脑上已安装的旧 MediaReview 服务。
- 不得读取、覆盖或复制 `C:\ProgramData\MediaReview`、真实媒体、真实密钥或配对 Token。
- 不得把 FastAPI 改成完整视频代理。
- 不得跳过 RED、focused GREEN、全量回归和独立审查。
- 不得把构建成功、模拟器或历史报告冒充真机验收。
- 不得直接用当前 Hermes `send_mail.py` 发送 APK；它尚不能可靠附加非图片文件。

接管后的第一项代码任务不是 Task 5，而是完成计划中的 Task A：修复最终删除 `success` 协议和生产主壳旧 settle token 集成回归，并取得新的独立 CLEAN 结论。
