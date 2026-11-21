# MediaReview 发布与部署

交付清单位于 MediaReview 主工作区的 `deploy_handoff/CURRENT_RELEASE.json`。开发 worktree 可能没有这份本地文件。2026-11-21 核对时，主工作区清单仍指向 Server 1.6.6（API Contract 2、数据库迁移 0018）与独立 APK Alpha34 / code41。候选版和最近真机记录见 [项目状态](../docs/PROJECT_STATUS.md)。

开发工作区中的历史 Server 1.6.7 ZIP 来自提交 `75266cb`，内附 Alpha37 / code44，构建输入的数据库迁移止于 0018。后续源码新增了迁移 0019 和 `pairing_check_v1`。核对候选包时应同时检查构建提交、版本、哈希、迁移和能力声明。Android 安装使用交付清单的独立 APK。

从 Server 1.6.8 起，新打包流程将 Android APK 和 APK 元数据文件单独导出。Server ZIP 的清单记录 EXE、源码迁移头、0018 合成数据升级验证和配对探针结果。执行打包入口需要干净的 Git 工作区。

使用项目的 Python 3.12 环境安装 `server[package]`，再运行 `scripts/build_deploy.py`。该依赖组包含已固定版本的 PyInstaller，并安装 Server 运行依赖。源码回归可使用 `python -m pytest server/tests/test_media_api.py -k stored_dates --basetemp temp/date-contract`。

安装步骤见 [安装指南](../USER_INSTALL_GUIDE.md)，成对回滚见 [升级与回滚](../UPGRADE_ROLLBACK.md)，人工验收见 [局域网验收](../USER_LAN_VALIDATION.md)。Windows 服务使用 ControlHub 的 `mediareview` / `AIHome.MediaReview` 实例。升级前按现有流程备份并校验配置与数据，升级后核对运行实例身份、健康检查和数据库迁移。

生图审核连接独立的 NSFW Studio 桥（默认端口 8877），它有单独的部署和能力验收流程。
