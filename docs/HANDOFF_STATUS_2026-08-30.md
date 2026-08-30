# 家庭媒体管家 1.1 交接状态

日期：2026-08-30（Asia/Shanghai）

## 一句话结论

项目已经完成并独立验收 Task 0–3；Task 4 的主体代码已存在，但最新独立审查仍为 **NOT CLEAN（2 个 Important）**；Task 5–10 尚未按 1.1 规格完成。下一位 Agent 应从当前 HEAD 继续返修 Task 4，不能直接打包发布。

## 项目与架构

```text
Android
 ├─ 控制、索引、批阅、整理 → MediaReview Server
 └─ 视频 → Jellyfin Direct Play / 单次 HLS 回退

MediaReview Server
 ├─ Jellyfin 元数据同步
 ├─ SQLite 本地索引与用户状态
 ├─ 图片和雪碧图缓存
 ├─ 安全删除与重复检测
 └─ Windows 运维后台
```

固定约束：局域网、单用户、Android 可见文字中文、电脑后台只做运维、完整视频不经 FastAPI 转发、最终版本 `1.1.0`、旧配置/数据库/配对无损升级。

## 当前 Git 状态

- 分支：`feature/mediareview-1.1`
- 交接前 HEAD：`ef528d363f4cca158c6fdbe60a59424fa0615c4a`
- 当前 HEAD 的最后一项内容是 Task 4 独立审查报告，不是通过结论。
- 可接受的已完成阶段基线：Task 3 终点 `b56b547dbbef5ef203f7b91cdfe19b1ba61bc739`。
- 下一位 Agent 应在当前 HEAD 上返修，保留完整历史；不要 reset 到 Task 3，也不要重新初始化仓库。

## 已完成且通过独立门禁

| 阶段 | 提交范围 | 已验收能力 | 历史验证 |
|---|---|---|---|
| Task 0 基线 | `cf8f363..62bc225` | 脱敏基线、隔离工作树、生产 SQLite 在线备份记录 | Server 150；Android Debug build |
| Task 1 索引 | `62bc225..ea740af` | `/media` 纯 SQLite；0010；500 条 bulk upsert；持久化 refresh/task；失败保旧缓存 | Server 176；ruff/format/diff clean |
| Task 2 批阅/安全 | `ea740af..619fe5e` | SQLite 建队；稳定绝对 index；图片认证代理；严格 SHA-256；server key 不进入响应 | Server 236；100k 建队约 0.970s；CLEAN |
| Task 3 连接/配对 | `619fe5e..b56b547` | TCP 8766；UDP 35001；手动 IP；client URL；稳定 installation ID；Keystore；0012 | Server 258；Android 77；Debug build；CLEAN |

这些数字是历史验收证据，不是本交接回合重新执行的实时结果。

## Task 4：主体已实现，但未通过

已经存在：固定深色设计令牌、中文品牌、自适应图标、四根导航、独立全屏设置/播放器/图片页、技术信息移入设置、Material Icons、状态组件、Review 失活 token reset、内容 revision 基础矩阵、生产主壳 instrumentation 测试框架。

最新自动化历史证据：Android JVM 100 tests；Debug APK 与 androidTest APK 构建成功；lint 0 errors / 47 warnings。ADB 无设备，instrumentation 只构建未执行。

最新权威报告：`.superpowers/sdd/task-4-post-fix-review.md`，结论 **NOT CLEAN**。

### Important 1：最终删除协议错配

- Server 实际 outcome：`success` / `missing` / `failed`。
- Android 当前错误使用：`deleted` / `missing`。
- 影响：真实全成功删除不会刷新 DeleteQueue、Media、Favorites、Duplicates；成功统计也会显示为失败。
- 必须用真实状态覆盖全成功、全失败、空 outcome、仅 missing、混合结果，并验证汇总文本和 revision 矩阵。

### Important 2：主壳集成测试不能捕获旧 token 竞态

- 当前测试虽然组合生产 `MainShellScreen` 和真实 `ReviewViewModel`，但 Review 根没有队列项、没有 `onSettled()`、没有挂起 playback lookup。
- 删除 `settleScheduler.reset()` 后该测试仍会通过。
- 必须在生产壳测试中启动真实 settle，挂起 lookup，切根，释放旧 lookup，断言旧 settle/play 为 0；重新进入后新 token 能播放，并完成删除 reset 的 RED 反证。

Task 4 只有在新独立审查达到 0 Critical / 0 Important 后才可更新为完成。

## 尚未完成

### Task 5 媒体墙、图片、雪碧图

旧版基础功能存在，但没有 Paging 3 Compose；文件夹辅助视图、全部排序/筛选、图片降采样/恢复、雪碧图任务取消/进度/失效没有达到 1.1 门禁。

### Task 6 Direct Play 与 HLS 播放器

这是“手机视频看不了”的关键未完成阶段。当前 playback 仍是安全过渡合同，只提供 legacy `stream_url` 并可能标记 `requires_jellyfin_auth=true`；没有正式客户端 Jellyfin 凭据、`direct`/`fallback_hls`/headers 合同、单次回退状态机和中文错误分类。

### Task 7 批阅、收藏、安全删除、重复整理

旧版基础页面存在，但五分钟删除 nonce、prepare/commit 安全复核、逐项审计、持久化重复组/任务、暂停/取消/恢复扫描、双列人工保留项尚未完成。现有迁移头是 `0012`；下一迁移必须从 `0012` 延伸，禁止重写已验证迁移或制造第二个 head。

### Task 8 Windows 运维控制台

旧 `/admin` 存在；1.1 的设备撤销、同步/任务/扫描进度、缓存策略、脱敏错误、危险操作确认和诊断整合未完成。

### Task 9 部署与升级

当前部署资产仍是旧版：EXE 名称、start/stop/restart/status、升级自动备份和失败回滚、FFmpeg 实包优先级、正式 APK/ZIP/checksums/回滚说明、干净机验证均未完成。

### Task 10 发布验证

未执行真实 5.6 万媒体、真实手机、真实 Jellyfin、格式矩阵、Wi-Fi/服务/Windows 重启、Release 覆盖安装和数据保留验证。没有这些证据只能标 RC。

## 生产与隐私边界

- 2026-08-22 历史快照记录旧服务使用 TCP `8766`、UDP `35001`、56,533 条索引；必须重新只读验证后才能引用为当前状态。
- 历史生产备份位于 `C:\ProgramData\MediaReview\backups\pre-1.1.0-20260822-implementation\`，不在本项目副本中。
- 项目交接不授权访问、复制、修改或部署生产数据。
- Jellyfin API Key、Bearer Token、配对码、真实路径不得进入聊天、提交、日志或交接包。

## 每阶段固定门禁

1. 建立可稳定失败的 RED 测试并记录失败原因。
2. 最小实现；不得顺手进入下一阶段。
3. focused tests GREEN。
4. Server 全量 `pytest`、`ruff check .`、`ruff format --check .`。
5. Android JVM、Debug、androidTest build、lint；对应阶段要求 Release 时再加 Release build。
6. `git diff --check`，工作树 clean，无密钥/运行数据/构建产物。
7. 更新 `TASKS.md` 和 `docs/DEV_LOG.md`，生成冻结 diff 与报告。
8. 独立 reviewer 追踪真实调用路径；修完全部 Critical/Important。
9. 只有 CLEAN 才进入下一 Task。

## 当前可复现命令

首次在另一台电脑准备环境：

```powershell
Set-Location server
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"

Set-Location ..\android
# 在 android/local.properties 写入该电脑自己的 sdk.dir；该文件已被 Git 忽略，不得提交。
.\gradlew.bat --no-daemon :app:tasks
```

不要从旧电脑复制 `.venv`、Gradle build cache、`local.properties` 或任何真实配置；这些不是可移植源码，也可能包含机器路径。

Server（必须隔离数据根）：

```powershell
Set-Location server
$env:MEDIAREVIEW_DATA_ROOT = Join-Path $env:TEMP 'mediareview-agent-test'
.\.venv\Scripts\python.exe -m pytest
.\.venv\Scripts\python.exe -m ruff check .
.\.venv\Scripts\python.exe -m ruff format --check .
```

Android：

```powershell
Set-Location android
.\gradlew.bat --offline --no-daemon `
  :app:testDebugUnitTest :app:assembleDebug :app:assembleAndroidTest :app:lintDebug `
  --rerun-tasks
```

其他电脑若没有已缓存依赖，首次去掉 `--offline`；JDK 21、Android SDK 35、Python 3.12 是已验证工具版本。
