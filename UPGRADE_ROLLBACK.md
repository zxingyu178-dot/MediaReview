# MediaReview 升级与回滚说明（Server 1.2.2 / Alpha8）

## Stage 8E 受控升级边界

现有 ControlHub WinSW 服务必须保留唯一身份 AIHome.MediaReview；不能改成平行计划任务。
USER_INSTALL_GUIDE.md 给出该路径：停止服务后备份 config/database/旧二进制/服务定义，
生成包含时间、旧版本、文件与SHA256的backup_manifest.json，再校验同盘staging并切换。
health必须1.2.2/Contract2/能力齐全，readiness必须显式client_url权威、Direct可读。
失败时先停止新版本，再恢复旧二进制、配置与完整数据库（含WAL/SHM），启动原服务并
核验旧版本健康；不得仅靠CURRENT_VERSION文本判断回滚成功。
没有可靠备份或服务控制权限时禁止开始覆盖。本阶段未执行生产升级或永久删除媒体。
既有计划任务部署仍由原事务式安装器处理；它检测到WinSW服务时在写文件前拒绝。

以下保留各历史阶段的升级规则和证据。

## Stage 8D.2 服务器变更补充（2026-10）

- Server 版本：`1.2.0 → 1.2.1`（**API Contract 保持 2**，无 API 语义变化）；
- 数据库迁移：**NO SCHEMA MIGRATION**（Alembic head 仍为 `0014_task_d_delete_nonce_duplicates`）；
- 部署包命名：`MediaReview_Migration_1.2.1_<时间戳>.zip`；
- **部署 Gate（新增）**：`install.ps1` 的成功条件从"health status=ok"升级为
  `status=ok` + `version=1.2.1` + `api_contract>=2` + 六项 capabilities 齐全
  （版本/Contract 事实源 = `deployment/version.ps1`）；
  - 升级失败 → 停止新版本 → 自动回滚旧二进制/数据 → 校验旧版本重新健康 →
    `exit != 0`，报告 `UPGRADE FAILED` + `ROLLBACK PASS|FAIL`；
  - 首次安装失败 → 停止坏进程、删除自启任务、标记 `INSTALL FAILED`、`exit != 0`，
    **不再打印"安装完成"**；
  - `status.ps1` 会输出 `健康 / Server Version / API Contract / Compatibility`，
    contract 不达标即非零退出。

## Stage 8D.1 服务器变更补充（2026-10）

- Server 版本：`1.1.0 → 1.2.0`（`server/app/__init__.py`）；
- 新增 **API Contract**：`SERVER_API_CONTRACT = 2`，并在 `GET /api/v1/system/health`
  返回 `api_contract` 与 `capabilities`（见 `docs/SERVER_VERSION_POLICY.md`）；
- 数据库迁移：**NO SCHEMA MIGRATION**。Alembic head 仍为 `0014_task_d_delete_nonce_duplicates`；
  - Stage 8D 的 group_id 稳定化与 Stage 8D.1 的**旧 Keep 首次安全迁移**都是
    **运行时业务数据迁移**（首次成功重扫后 DB 自然全部为稳定 ID），不改表结构；
- 升级/回滚流程：沿用下文的事务式升级与失败自动回滚（备份 DB → 升级 → 健康检查 → 失败回滚）；
  部署包命名为 `MediaReview_Migration_1.2.0_<时间戳>.zip`。

### 版本兼容行为（§38）

| 组合 | 行为 |
| --- | --- |
| **App 新 + Server 旧** | App health 判定 `api_contract(1) < 2` → **禁止进入 Server Ready**；设置页提示"服务器版本过旧"；**不清 Token**；Server Repository 读取类接口 fail-fast（不再产生 404 串） |
| **App 新 + Server 新** | 正常连接（health → pairing → jellyfin → media） |
| **App 旧 + Server 新** | Server 1.2.0 **保留** 1.1.0 的全部旧接口与字段语义，只**新增** `api_contract`/`capabilities`；旧 App 忽略未知字段，行为与连 1.1.0 相同 → 继续可用（**不猜测**：本阶段 Server 未删除/改名任何旧接口） |

### 回滚影响

- 从 1.2.0 回滚到 1.1.0：App（alpha5）会因缺少 `api_contract` 判定为 Incompatible 并提示升级；
  **不**清 Token，升级回 1.2.0 后原配对继续可用；
- 数据库无需回滚（无 schema 变更；group_id 已是稳定 ID，1.1.0 代码对其按普通字符串处理）。

本说明覆盖 `MediaReview_Migration_1.1.0` 部署包的事务式升级与回滚流程。升级由 `scripts/install.ps1` 自动完成，失败时自动回滚，正常情况下无需人工干预。

## 事务式升级流程

再次运行 `scripts/install.ps1`（或新版部署包）时，脚本检测到旧版本（存在 `VERSION.txt` / `CURRENT_VERSION` / 已安装程序）即进入升级路径：

1. **停止旧服务**：按 `MediaReviewServer.exe` 绝对路径精确停止本服务进程，并停止同名计划任务；不会误杀无关进程。
2. **备份**：将 `config/`、`database/` 与旧二进制复制到 `%ProgramData%\MediaReview\backup\<时间戳>\`。
3. **staging**：新程序复制到安装目录下的 `.new`，不与运行中文件冲突。
4. **迁移 + 健康检查**：以新程序 + 同一数据根启动，等待数据库自动迁移；轮询 `http://127.0.0.1:<port>/api/v1/system/health` 直至 `status=ok`。
5. **原子提升**：健康检查通过后，用新程序替换旧程序目录，写入 `VERSION.txt` 与 `CURRENT_VERSION`，清理 staging。
6. **重启**：通过计划任务拉起新版本并再次健康检查。

## 失败自动回滚

若第 4 步健康检查未通过（超时或状态异常），脚本自动执行 `Restore-Previous`：

1. 停止新 staging 进程与计划任务。
2. 删除已替换的新程序目录。
3. 从备份目录恢复旧二进制、`config/`、`database/`。
4. 重新启动旧版本并写回版本标记。

升级失败时旧数据与旧版本保持不变，部署报告记录 `restore: restored old version from <backup>`。

## 手动回滚（紧急情况）

如果升级后运行异常，可手动回滚到备份：

1. 停止服务：`scripts\stop.ps1`
2. 恢复二进制：将 `%ProgramData%\MediaReview\backup\<时间戳>\binaries\*` 覆盖回安装目录。
3. 恢复数据：将备份中的 `config/`、`database/` 覆盖回 `%ProgramData%\MediaReview\`。
4. 重启：`scripts\start.ps1`
5. 用 `scripts\status.ps1` 验证健康状态与版本。

## 备份位置

- 自动备份：`%ProgramData%\MediaReview\backup\<时间戳>\{binaries,config,database}`
- 手工备份建议：完整复制 `config` 与 `database` 两个目录到安全位置。

## 验证

- `scripts\status.ps1` — 显示进程、端口、健康状态与版本。
- `%ProgramData%\MediaReview\deploy_report.txt` — 最近一次安装/升级的步骤报告。
- `%ProgramData%\MediaReview\config\CURRENT_VERSION` — 当前运行版本。
# Stage 8E.1：离线升级事务验证

新增 `deployment/scripts/upgrade-aihome-managed.ps1`。默认及 `-WhatIf` 为只读预览；生产执行在任何写入和生命周期操作前封锁。正式 ControlHub 生产适配器尚未开放，不能据此执行既有服务升级。

隔离事务在 mock 服务确认停止、端口释放之后建立完整快照，备份配置、数据库及当时存在的 WAL/SHM、旧二进制、版本文件与 WinSW 定义；隐藏文件也纳入字节数、完整文件集合及 SHA256。切换后的 health/readiness 失败会停回同一实例、校验备份、恢复停服时快照并验证旧服务状态；恢复失败明确报告 `UPGRADE_FAILED_ROLLBACK_FAILED`，保留恢复资料。

这些验证只使用假二进制及假数据库，不证明真实数据库迁移、实际 WinSW/ControlHub 升级或 Jellyfin 播放链已经通过。生产升级、手机安装迁移和家庭 LAN 验收仍待另行执行。

## Stage 8E.2：来源门禁与官方合同阻断

纳管入口在 Stop 前调用 verified-rc.ps1，要求 manifest、完整 checksum 集合及实际 EXE/版本/Contract 一致，拒绝新增、缺失、篡改、路径大小写歧义和 reparse。初始二进制清单保持不变，Stop 前及 staging 前后重新核对；停服期间来源改变会拒绝并按既有事务恢复旧 fixture。

本地 UpgradePlan 包含 exact target、当前/目标版本、Contract、来源与 EXE 摘要、install/dataRoot、动作序列和可选 release HEAD。PS5.1/7 canonical 摘要一致，但不是官方审批。

当前生产执行仍 BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT。现有单动作锁不能覆盖 Stop 后至 Start/恢复之间的文件事务；正式平台需提供同 GUI coordinator IPC、事务 reservation/lease、计划执行绑定和关联审计。不得直接调用 Helper/SCM 或新建独立 Controller 绕过。上述历史非纳管安装说明不构成既有 AIHome.MediaReview 的升级授权。
