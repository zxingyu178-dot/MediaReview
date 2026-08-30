# MediaReview

一个面向个人局域网使用的 Android 媒体浏览、播放与批阅工具。

核心目标：

- 手机通过局域网连接电脑上的 Jellyfin。
- 以媒体墙为主要浏览方式，文件夹为辅助入口。
- 支持视频与图片。
- 支持类似短视频 App 的上下滑批阅模式。
- 支持点赞、待删除、重复文件识别、批阅断点恢复。
- 支持视频雪碧图快速预览。
- Android 端负责 UI 与播放；电脑端中间层负责 Jellyfin 适配、批阅数据、缓存、FFmpeg、重复检测、删除队列与管理后台。
- 视频流原则上由 Android 直接从 Jellyfin 拉取，中间层不转发大流量视频。

## 给 Agent 的第一句话

开始开发前，必须完整阅读：

1. `TAKEOVER_READ_FIRST.md`
2. `AGENTS.md`
3. `docs/HANDOFF_STATUS_2026-08-30.md`
4. `docs/superpowers/plans/2026-08-30-mediareview-1.1-takeover.md`
5. `.superpowers/sdd/task-4-post-fix-review.md`
6. `REVIEW_HANDOFF_RULES.md`
7. `docs/TOOLCHAIN_AND_RELEASE_OPERATIONS.md`
8. `docs/HERMES_APK_EMAIL_DELIVERY.md`

未完成阅读前，不允许开始写核心业务代码。

## 项目原则

- 保留并复用已取得独立 CLEAN 的 Task 0–3，以及当前 Task 4 候选实现；不要推倒重写。外部旧项目代码只有在许可证、兼容性和安全边界审查通过后才可复用。
- 所有用户界面与用户可见文案默认中文。
- 原始媒体目录不得被缩略图、雪碧图、日志、缓存文件污染。
- 开发电脑和最终部署电脑不同，禁止写死开发机绝对路径、IP、用户名和环境。
- 最终电脑端必须能打包成 ZIP，并通过自动部署脚本完成安装。
- 高风险删除动作必须经过“待删除队列 → 最终确认”。
- 点赞只写入本项目数据库，不修改原始媒体文件。
