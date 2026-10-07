# Stage8E.2 官方升级桥接实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. 独立任务完成后分别审查，最后整体审查。

**Goal:** 按用户Stage8E.2规范验证官方生命周期入口，补齐可信RC来源与部署工具重打包；能力不足保持封锁。

**Architecture:** 生命周期只能包装ControlHub当前GUI持有的同一个coordinator。MediaReview保留升级文件事务、readiness与恢复。来源验证、计划摘要和官方能力握手全部在Stop之前；正式入口无法在允许的最小façade范围内获得时，交付BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT与已完成部分。

**Tech Stack:** 既有Python发布工具，PowerShell5.1/7升级事务，ControlHub现有Python/PySide6协调器。

## Global Constraints

- 基线bf0e64d93e7000526523cfa17947434dd8a30fac；目标分支feature/mediareview-v2-stage8e.2-controlhub-upgrade-bridge。
- Android code15/2.0.0-alpha8，Server1.2.2，Contract2；运行时代码零修改。
- APK保持600aa647aae8731e3b241592db9b303295f2cb04c4be6cb0c5011b418185bfab；不重签、不重建，不发送APK。
- 不执行生产升级/停服，不读真实DB内容，不修改Jellyfin。用户确认Jellyfin由其启动。
- ControlHub主工作区dirty，不清理无关修改，不替换当前安装版，不自行批准。
- 不能新建独立Controller、复制平台互斥/审批/审计实现、调用Helper/SCM旁路。

## Task1：官方能力审查与设计判定

- [x] 读取Provider、正式onboarding/schema/service contract与真实coordinator、approval、audit。
- [x] 检查CLI/IPC与GUI共享实例、升级全过程互斥、plan digest审批和审计关联。
- [x] 以源码具体接口证明可复用；如必须超出最小façade改造平台合同，记录缺口，不声称桥接完成。

## Task2：可信来源Gate

Files: deployment/scripts/upgrade-aihome-managed.ps1；deployment/scripts/verified-rc.ps1；deployment/tests/test_verified_rc.ps1；deployment/tests/test_managed_upgrade.ps1。

Interface: Test-VerifiedRc($SourcePackage,$ExpectedServerVersion,$RequiredApiContract) 返回sourcePackageHash/serverExeHash/releaseGitHead；Get-UpgradePlanDigest($Plan) 返回canonical SHA256。只读，不读取真实DB。

- [x] 先写并运行失败测试：正确RC、缺manifest、版本/contract错误、EXE hash错误、文件缺失/篡改/新增/路径逃逸，所有非法来源Stop calls=0。
- [x] 实现逐项SHA256与完整集合验证、manifest版本/contract/实际EXE hash，拒绝reparse与重复/越界路径。
- [x] 验证来源身份接入现有事务Stop之前；固定target的不可变计划包含current/targetVersion、contract、source/exeHash、install/dataRoot与动作序列。
- [x] fixture增加计划变化导致摘要变化、PS5.1/7、既有事务恢复回归；不放宽断言。

## Task3：Verified Repackage

Files: scripts/build_deploy.py；server/tests/test_repackage_integrity_8e2.py。

Interface: --repackage-verified 消费原正式RC manifest与同一APK/Server完整dist，分离runtime_build_git_head与package_git_head；不构建产品二进制，不覆盖历史RC/独立APK。

- [x] 先写失败测试：旧manifest缺失、APK/EXE改动、receipt后部署脚本漂移拒绝，ZIP完整集合/hash通过。
- [x] 复用现有package_sources/package_inputs与最终staging/ZIP门禁，明确产品二进制来源与部署工具HEAD，禁止旧receipt静默升级。
- [x] 包装专项与相关部署测试通过，原始失败与通过日志保留。
- [x] 只有官方bridge取得时才实际生成新的部署RC；否则仅交付可验证实现，标记RC未生成及原因。

## Task4：整合与交付

- [x] 独立任务审查、必要失败修复、最终全量Server/部署/PS5.1/7/Ruff/diff检查。
- [x] 正式WhatIf只读记录身份/版本/source/digest/官方能力，不把文件存在视为能力成功。
- [x] 更新DEV_LOG/TASKS/REVIEW_SUMMARY，记录用户启动Jellyfin与手机全部NOT TESTED。
- [ ] 提交/push/核对远端与clean，生成8E.2规定十一份说明、git/logs/source/SHA256交接ZIP。
- [ ] 不自动升级生产，不自动进入下一功能阶段；重要无APK交接邮件按既有用户授权及Hermes入口执行。
