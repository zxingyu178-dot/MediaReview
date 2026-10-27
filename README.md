# MediaReview（家庭媒体管家）

MediaReview 是一个面向家庭局域网的媒体浏览、批阅与整理系统：Android 手机 App 通过自建中间层读取家庭 Jellyfin 媒体库，提供媒体墙浏览、视频/图片播放、批量点赞/待删除批阅、重复检测与 Web 管理后台。

## 架构

```
Android App (Compose + Media3)
        │  配对码 / HTTP(8766) / UDP 发现(35001)
        ▼
MediaReview Server (FastAPI + SQLite + FFmpeg)
        │  Jellyfin API Key（仅 Server→Jellyfin，不进 URL/JSON）
        ▼
Jellyfin Server
```

**视频播放原则**：Android 尽量直接连接 Jellyfin 播放（Direct Play，失败回退 HLS），中间层不转发完整视频流，只负责播放 URL 与设备级凭据签发。

Server 访问 Jellyfin 使用 `jellyfin.url`（可为回环地址）；手机播放使用 `jellyfin.client_url`（例如 `http://192.168.1.20:8096`）。后者留空时按手机请求 host 推导，设置中的“检测播放链路”显示最终地址和两段连接结果。LAN 配置、端口检查与诊断说明见 [部署说明](deployment/README.md#手机播放地址与局域网检查)。

## 主要功能

- Jellyfin 多媒体库读取与勾选
- 媒体墙：封面大小调节、排序/筛选/搜索、分页加载
- 视频/图片普通浏览（Media3 播放器 + Image Viewer）
- 批阅模式：视频/图片混合、P0/P1 预加载、断点恢复
- 收藏（点赞）
- 待删除队列 → 二次确认 → 最终删除
- 本地源与 Jellyfin 均支持两阶段删除（本地源仅显式 `can_delete=true` 时可删）
- 完全重复检测（full SHA-256）+ 疑似重复独立页面
- 本地媒体源：登记电脑目录、扫描/增量索引、封面与原图、认证 Range 播放、本地播放进度与两阶段安全删除
- 局域网自动发现 + 手动 IP 兜底 + 一次配对码
- Web 管理后台（操作台）
- Windows 服务部署（事务式升级/回滚）+ ControlHub 接入（实例身份健康检查）

## 当前版本

唯一当前发布入口为 `deploy_handoff/CURRENT_RELEASE.json`，指向 `deploy_handoff/current/`；旧包只保留历史/回滚，不从目录时间猜测部署版本。安装、备份和回家验证按项目根的三份当前指引执行。

- 源码发布目标：Android `2.0.0-alpha18`（versionCode 25）
- 源码发布目标：Server `1.6.2` / API Contract `2` / 数据库迁移 `0017`
- 加载专项沿用现有播放器与批阅流程，雪碧图保持删除；构建、定向测试与交付状态见项目状态页
- 生产环境：Server1.6.2，经用户确认、完整备份后升级；Alpha18邮件已获SMTP接受

> **运行态提醒**：2026-10-27终检只有一个ControlHub管理的MediaReview Windows服务实例，安装程序与当前发布清单一致。旧包仅供历史/回滚；以后通过ControlHub启动。手机/HomeLAN实际播放仍待验证。
> **当前完整状态、验证证据、生产运行态与待办，以 [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md) 为唯一事实源。** 本文只保留项目简介与当前版本摘要。

## 仓库结构

```
MediaReview/
├─ android/     # Android 客户端完整工程（Kotlin / Jetpack Compose / Media3）
├─ server/      # FastAPI 服务端完整工程（Python 3.12 / SQLite / Alembic）
├─ deployment/  # Windows 部署脚本（install/start/stop/upgrade/rollback）与配置模板
├─ scripts/     # 验收包/部署包构建脚本
├─ docs/        # 产品规格、架构、API 契约、验收清单、开发日志
├─ AGENTS.md    # 项目开发总规则
├─ TASKS.md     # 任务清单
├─ LICENSE      # MIT
└─ README.md
```

## 开发环境

| 组件 | 要求 |
|---|---|
| Android | Android Studio（SDK 35），JDK 17+，Gradle（见 `android/`） |
| Server | Python 3.12，依赖见 `server/pyproject.toml`；SQLite 内置 |
| FFmpeg | 雪碧图生成所需（`server/third_party/` 或系统 FFmpeg） |
| Jellyfin | 局域网内可访问的 Jellyfin 实例（测试用 8096） |

常用命令：

```bash
# Server 测试 / lint
cd server
python -m pytest            # 全量测试
ruff check . && ruff format --check .

# Android 构建 / 测试
cd android
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

## 配置

服务端使用 JSON 配置，不采用 `.env`。复制模板并填写自己的值：

```bash
cp deployment/config.example.json <data_root>/config.json
```

```json
{
  "jellyfin": {
    "url": "http://127.0.0.1:8096",
    "api_key": "YOUR_JELLYFIN_API_KEY"
  }
}
```

- `api_key` 仅用于 Server→Jellyfin 的 Authorization header，不会进入 URL/JSON/播放直连地址。
- 首次安装见 `deployment/scripts/install.ps1`；管理后台 `http://<电脑IP>:8766/admin` 生成配对码。

## 安全说明

- 本仓库为公开源码仓库，**不包含**任何真实 Token、密码、API Key、媒体数据库或个人配置；所有密钥由用户本地配置。
- Jellyfin API Key、配对凭据、Android 签名文件（`key.properties`/`*.jks`）均被 `.gitignore` 排除且不入库。
- 中间层不转发大流量视频流；图片/封面代理严格校验并脱敏错误。
- 二进制发布物（APK / Windows 部署包）通过 **GitHub Releases** 分发，不进入 Git 历史。

## 交接与开发流程（2026-09-19 起）

- **GitHub 公开仓库是项目唯一主要源码交接渠道**。
- 流程：任务 → 开发 → `git commit` → `git push` → 真机测试 → 基于 commit / diff 验收并规划下一阶段。
- 不再单独为验收制作完整源码 ZIP；每阶段仍保留 `TASKS.md` / `docs/DEV_LOG.md` 更新与清晰 commit message。

> **2026-10-24 现状**：GitHub 已恢复，`747e704` 与本次统一文档已推送（`git status -sb` 应与 origin 同步）。正式产物仍以 `deploy_handoff/` 本地文件为准，推送/交付结果以实际命令输出为准确认。

## License

[MIT](LICENSE) © 2026 MediaReview
