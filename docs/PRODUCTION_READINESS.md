# MediaReview 生产就绪清单（Stage 8D.1 / 2.0.0-alpha5）

> 本文件只做**准备度登记**。Stage 8D / 8D.1 均**不自动部署生产**。

## 1. 版本

| 组件 | 版本 | 位置 |
| --- | --- | --- |
| Android App | `versionCode 12` / `versionName 2.0.0-alpha5` | `android/app/build.gradle.kts` |
| 更新日志 | Alpha 1~5 已登记 | `feature/v2/releasenotes/ReleaseNotesCatalog.kt` |
| Server | `1.2.0` | `server/app/__init__.py` |
| Server API Contract | `2` | `server/app/__init__.py` `SERVER_API_CONTRACT` |
| 生产 Server 状态 | **未部署** | — |

## 1.1 兼容性 Gate（Stage 8D.1 §37）

```text
Android Required API Contract = 2
Server API Contract           = 2
Compatibility                 = PASS
```

- App 只按 `api_contract` 判断兼容（见 `docs/SERVER_VERSION_POLICY.md`）；
- 部署前必须确认 Server health 返回 `api_contract = 2`；
- 兼容矩阵见 `docs/COMPATIBILITY_MATRIX.md`。

## 2. 数据库迁移

- Alembic head：`0014_task_d_delete_nonce_duplicates`；
- **NO SCHEMA MIGRATION**（Stage 8D 未新增/修改表结构）；
- 重复分组 `group_id` 生成算法升级为稳定摘要（业务身份变化，非结构变化）；
- 升级/回滚见项目根 `UPGRADE_ROLLBACK.md`。

## 3. 配置文件

| 项 | 位置 |
| --- | --- |
| Server 配置样例 | `deployment/config.example.json` |
| 实际配置（生产） | `%ProgramData%\MediaReview\config\`（运行时生成，不入库） |
| 端口 | 默认 `8766`（`server/app/core/config.py` `port`，范围 1–65535） |
| 数据根 | `%ProgramData%\MediaReview\`（`config/` + `database/`） |
| Jellyfin 凭据 | 生产 config（**禁止**写入仓库；仓库只保留脱敏样例） |

## 4. 健康检查

- 端点：`GET http://127.0.0.1:<port>/api/v1/system/health` → 期望 `status=ok`；
- 版本端点：`GET /api/v1/system/...` 返回 `version=1.1.0`；
- 部署脚本 `scripts/status.ps1` 显示进程 / 端口 / 健康 / 版本；
- Android 侧连接检查走 `core/pairing/PairingRepository.checkHealthy`（不在启动首屏阻塞）。

## 5. 回滚方式

- 部署脚本 `deployment/scripts/install.ps1` 内建事务式升级 + 失败自动回滚
  （备份 `config/` + `database/` + 二进制到 `%ProgramData%\MediaReview\backup\<时间戳>\`）；
- 手动回滚：`stop.ps1` → 覆盖 `binaries` + `config/` + `database/` → `start.ps1` → `status.ps1`；
- Android 回滚：重新安装上一版 alpha APK（versionCode 递减安装被系统拒绝，需先卸载或用更高 code）。

## 6. Windows Service / 计划任务

- Server 以受控计划任务 / 进程方式运行，由 `deployment/scripts/{start,stop,restart,status}.ps1` 管理；
- 生命周期事实源为 ControlHub 官方 Contract（见下）。

## 7. ControlHub

- Stage 8D **未新增任何长期运行对象**：只修改 Android App 与 Server 内部算法，
  未新增 Web/API Server、Worker、Windows Service、后台常驻进程或计划任务；
- 因此按 AIHome 全局规则 §8.1 **不触发新的 ControlHub 接入流程**；
- 已有 Server 运行对象的生命周期沿用既有 ControlHub / deployment 脚本，不新建平行系统。

## 8. 生产部署前的检查清单（本阶段不执行）

1. 备份生产 `config/` 与 `database/`；
2. 确认 Jellyfin 地址 / 凭据就绪（config 内，不入库）；
3. 部署 Server（无需 DB migration）；
4. 健康检查 `status=ok` + 版本正确；
5. 安装 Android APK（`MediaReview_2.0.0-alpha4_stage8d.apk`）；
6. 走一遍 Demo / Server 双模式与批阅、整理全链路；
7. 失败即按 `UPGRADE_ROLLBACK.md` 回滚。
