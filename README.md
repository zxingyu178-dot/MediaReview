# MediaReview（家庭媒体管家）

MediaReview 是一个面向家庭局域网的媒体浏览、批阅与整理系统：Android 手机 App 通过自建中间层读取家庭 Jellyfin 媒体库，提供媒体墙浏览、视频/图片播放、雪碧图预览、批量点赞/待删除批阅、重复检测与 Web 管理后台。

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

## 主要功能

- Jellyfin 多媒体库读取与勾选
- 媒体墙：封面大小调节、排序/筛选/搜索、分页加载
- 视频/图片普通浏览（Media3 播放器 + Image Viewer）
- 雪碧图快速预览（FFmpeg 生成）
- 批阅模式：视频/图片混合、P0/P1 预加载、断点恢复
- 收藏（点赞）
- 待删除队列 → 二次确认 → 最终删除
- 完全重复检测（full SHA-256）+ 疑似重复独立页面
- 局域网自动发现 + 手动 IP 兜底 + 一次配对码
- Web 管理后台（操作台）
- Windows 服务部署（事务式升级/回滚）+ ControlHub 接入（实例身份健康检查）

## 当前版本

`1.1.0-rc2`（Android versionCode 7；正式 `1.1.0` 待真机验收门通过）

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

## License

[MIT](LICENSE) © 2026 MediaReview
