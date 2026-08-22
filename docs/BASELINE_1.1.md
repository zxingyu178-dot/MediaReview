# MediaReview 1.1 开发基线

记录时间：2026-08-22（Asia/Shanghai）

## 运行状态

- MediaReview Server：`0.8.1`
- 健康检查：`status=ok`、`database=ok`、`jellyfin=configured`
- TCP：`0.0.0.0:8766`，PID `31952`
- UDP 自动发现：`0.0.0.0:35001`，PID `31952`
- Android：`1.0.0-rc2`，versionCode `4`

## 脱敏数据规模

- Jellyfin Library 记录：20
- 本地媒体索引：56,533
- 配对设备记录：3
- 收藏：0
- 待删除：0
- 批阅会话：0

不记录 Jellyfin API Key、配对 Token、真实媒体路径或设备标识。

## 可恢复备份

位置：`C:\ProgramData\MediaReview\backups\pre-1.1.0-20260822-implementation\`

- `mediareview.db`：通过 SQLite 在线 backup API 生成的一致性快照。
- `diagnostics-redacted.zip`：由运行中 Server 的脱敏诊断接口生成，包含掩码配置和日志摘要。
- 当前运行配置未被修改，生产服务未因备份中断。

## 基线验证

### Server

数据目录通过 `MEDIAREVIEW_DATA_ROOT=E:\aihome\codex\temp\mediareview-baseline-20260822` 隔离，避免测试触碰生产日志和数据库。

```text
.venv\Scripts\python.exe -m pytest
150 passed, 1 dependency deprecation warning

.venv\Scripts\python.exe -m ruff check .
All checks passed!

.venv\Scripts\python.exe -m ruff format --check .
75 files already formatted
```

未设置隔离数据目录时有 2 项测试因运行中的生产 `server.log` 文件锁而失败；业务断言没有执行。该现象证明测试必须始终使用隔离数据根，不作为产品缺陷计数。

### Android

环境：JDK 21、Android SDK 35、Gradle 8.9。

```text
gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon
BUILD SUCCESSFUL in 4m 21s
51 actionable tasks: 50 executed, 1 up-to-date
```

已有警告：`PlayerViewModel.kt` 使用一个 Kotlin delicate API；进入播放器重构阶段时消除。

## 已确认的首要故障

1. `/media` 冷请求同步拉取全部选中媒体库，56,533 条规模超过 Android 15 秒读取超时。
2. 批阅会话创建重复执行全量 Jellyfin 采集。
3. Jellyfin 后端 URL 配置为回环地址时，返回给手机的封面、原图和视频 URL 同样使用回环地址。
4. 当前服务日志文件为空，生产故障缺乏可用观测证据。

本文件作为 `main` 原始导入提交的验收基线；后续所有性能、功能和发布结论均相对此基线验证。
