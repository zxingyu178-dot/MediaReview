# MediaReview Alpha11 / Server1.3.0 升级与回滚边界

当前发布目标：Android code18 / `2.0.0-alpha11`；Server `1.3.0` / Contract2。health应包含七项能力：`folder_tree_v1`、`review_session`、`review_nearest`、`organize`、`delete_nonce`、`duplicates_paged`、`library_selection`。

## Stage8H本次边界

仅交付官方离线产物；不部署生产Server，不安装用户手机或清数据，不操作SCM或ControlHub生命周期，不修改真实Jellyfin，不删除真实媒体。

正式生产升级桥仍为 `BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT`。不得以直接调用Helper/SCM、新建Controller或平行计划任务绕过；保留既有 `AIHome.MediaReview` 唯一身份。外部桥接阻塞不禁止获授权的离线产物构建。全部自动化、签名、实际二进制隔离核验与一致性门禁通过才可标记 `CODE_READY` / `READY_FOR_USER_VALIDATION`，不代表真实LAN或生产就绪。

本次没有实际升级，因此不执行回滚。后续官方入口开放且获得部署授权时，才按官方事务约束确认停止、完整备份、可信staging、切换及恢复；无可靠备份或官方事务控制时不得覆盖生产。

备份需包含旧二进制、配置、完整数据库及停服时存在的WAL/SHM、版本标记与服务定义，记录时间、版本及完整文件SHA256。含凭据的备份保存在用户安全位置，不进入Git、交接ZIP或邮件。失败恢复必须校验备份并核验旧版本实际health，不能只看版本文本；恢复失败保留证据并明确报告。

真实用户体验仅使用 [Stage8H的15步路线](USER_LAN_VALIDATION.md)，等待后续官方升级及安装条件具备后执行。

## 历史记录（不构成本次部署授权）

### Stage8D.1：Server1.1.0→1.2.0

引入API Contract2，health新增api_contract/capabilities；新增字段保持旧接口语义。App遇到Contract不足时禁止Server Ready，不清Token。该历史阶段无schema迁移，Alembic head为 `0014_task_d_delete_nonce_duplicates`；稳定group_id与旧Keep迁移属于运行时业务数据处理。回退1.1.0时当时的新App会提示不兼容，恢复1.2.0后可继续原配对。

### Stage8D.2：Server1.2.0→1.2.1

Contract保持2，历史schema head不变。部署门禁增加实际health版本、Contract及六项能力核验；升级失败要求恢复旧二进制与数据并验证旧health，首次安装失败不得报告成功。版本事实源为 `deployment/version.ps1`。

### Stage8E：Alpha8 code15 / Server1.2.2

历史受控升级要求保留WinSW身份、完整停服快照、来源SHA256、显式client_url与readiness；既有任务安装器发现纳管服务时拒绝建立平行对象。本文不保留历史电脑IP或机器绝对路径，后续应从当前官方运行事实取得路径与地址。

历史事务式安装器曾使用停止→备份→staging→迁移与health→提升→重启的流程，失败恢复旧程序、配置和数据库。此说明只记录旧流程，不保证本阶段数据库迁移或真实恢复已验证，也不授权运行包内start/stop/install脚本。

### Stage8E.1：离线升级事务

`deployment/scripts/upgrade-aihome-managed.ps1` 默认及 `-WhatIf`只读；生产执行在写入与生命周期动作前封锁。隔离fixture验证完整文件快照（包括隐藏文件、WAL/SHM、WinSW定义）、备份校验、切换后health/readiness失败恢复；恢复失败报告 `UPGRADE_FAILED_ROLLBACK_FAILED`。假二进制/假数据库结果不能代替真实生产验收。

### Stage8E.2：来源门禁与官方合同阻断

Stop前校验manifest、完整checksum、实际EXE/版本/Contract；拒绝额外/缺失/篡改文件、大小写歧义和reparse。停服及staging期间重复核验来源，异常按原fixture事务恢复。

本地UpgradePlan包含精确目标、版本、Contract、来源摘要、目录与动作序列；PS5.1/7摘要一致不等于官方批准。官方同实例IPC、升级全过程reservation/lease、执行批准绑定与关联审计未取得，生产执行保持 `BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT`。
