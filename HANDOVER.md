# MediaReview 1.1.0 部署交接说明

## 版本

- Server: 1.1.0-rc1（`MediaReviewServer.exe`）
- Android: 1.1.0（versionCode 6，应用名「家庭媒体管家」）
- 构建日期: 2026-09-02
- Git commit: `2613400`（feature/mediareview-1.1）

## 包内结构

```
MediaReview_Migration_1.1.0/
├── server/MediaReviewServer/    # PyInstaller 自包含服务端(MediaReviewServer.exe + _internal)
├── ffmpeg/                      # 随包 FFmpeg(ffmpeg.exe + ffprobe.exe)
├── scripts/                     # install/start/stop/restart/status/repair/diagnose/uninstall
├── app-release.apk              # 签名 Release APK(家庭媒体管家)
├── config.example.json          # 配置模板
├── SHA256SUMS.txt               # 全量 SHA-256 校验和
├── README.md
├── LICENSE
├── THIRD_PARTY_NOTICES.md
├── HANDOVER.md
└── UPGRADE_ROLLBACK.md
```

## 部署

1. 解压 ZIP 到目标电脑（局域网内、装有 Jellyfin 的 Windows 机器）。
2. 右键 `scripts/install.ps1` → 使用 PowerShell 以管理员身份运行。
3. 首次安装会生成配置模板；按需编辑 `%ProgramData%\MediaReview\config\config.json` 确认 Jellyfin 地址与 API Key（也可在管理后台完成）。
4. 浏览器打开 `http://<电脑IP>:8766/admin` 生成配对码。
5. 手机安装 `app-release.apk`，自动发现电脑，输入配对码完成连接。

常用参数：

```powershell
# 自定义端口或数据目录
.\scripts\install.ps1 -Port 9000 -DataRoot D:\MediaReviewData
```

## 数据位置

默认 `%ProgramData%\MediaReview`：

- `config/` — 配置与 `CURRENT_VERSION` 版本标记
- `database/` — SQLite 数据库（媒体索引、点赞、待删除、批阅会话、重复分组）
- `cache/` — 雪碧图/缩略图缓存（可重建）
- `logs/` — 运行日志

## 备份

至少备份 `config` 与 `database` 两个目录；`cache` 可以重建，不属于必须备份数据。

## 升级 / 回滚

见 `UPGRADE_ROLLBACK.md`。升级为事务式：失败自动回滚并重启旧版本，无需手工干预。

## 故障

1. 先运行 `scripts/diagnose.ps1` 导出诊断包。
2. 再检查：
   - Jellyfin 是否在线、API Key 是否正确
   - MediaReview 服务进程状态（`scripts/status.ps1`）
   - TCP 8766 / UDP 35001 防火墙规则
   - 日志 `%ProgramData%\MediaReview\logs\server.log`

## 安全说明

- 仅开放 TCP 8766（服务）与 UDP 35001（局域网自动发现）两条入站规则。
- 卸载默认保留数据（加 `-DeleteData` 才删除）。
- 配置与密钥文件不随源码分发；`key.properties`/keystore 不进仓库。
