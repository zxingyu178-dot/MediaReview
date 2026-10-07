# MediaReview Stage 8E.2 审查摘要

阶段：Official ControlHub Upgrade Bridge / Trusted RC。基线 bf0e64d93e7000526523cfa17947434dd8a30fac；分支 feature/mediareview-v2-stage8e.2-controlhub-upgrade-bridge。

## 结果与实际变更

离线实现可交接；正式桥接为 **BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT**。当前官方 coordinator 有单次动作互斥、目标绑定、manifest 审批和生命周期审计，但缺少连接既有 GUI 实例的官方 IPC、覆盖整个升级文件事务的 reservation/lease、实际 UpgradePlan 执行绑定及计划步骤审计关联。薄 façade 不能独立补齐这些 core 合同，按用户规范退出条件交付缺口，不复制平台能力。

新增 verified-rc.ps1 在任何 Stop 前校验来源 RC 完整文件集合、SHA256、manifest、EXE、版本/Contract、大小写与 reparse 边界。升级计划采用固定 schema 和跨 PS5.1/7 一致 canonical 摘要，明确 LOCAL_PLAN_ONLY_NOT_OFFICIAL_APPROVAL。来源在 staging 前后重新校验，Stop 回调改变来源会拒绝并恢复原 fixture。

build_deploy.py 新增两步 verified repackage；固定原 RC 完整清单信任锚，保留 APK/整个 Server dist 原字节，区分 runtime build、runtime release 和 package Git HEAD。receipt 仅允许部署输出根的独占新 JSON，拒绝覆盖旧 manifest/APK、链接、路径逃逸及竞争覆盖。未实际生成新部署 RC，因为官方桥接未成立。

修改仅部署脚本、发布工具、测试及说明；Android Alpha8/code15、Server1.2.2/Contract2 不变，产品运行时代码、UI、API 和数据库 schema 零修改。脚本已登记既有 Registry；未新增服务或 binding。

## 已执行验证

- 最终 Server 全量：561 passed，0 errors/failures/skipped，312.084 秒；1 条既有 Starlette/httpx 弃用警告。
- PS5.1/7：来源与 canonical 专项各 164 断言；既有事务与来源变化恢复各 129 断言；AST 通过。
- 重打包专项与既有包装回归 90 passed；独立复审再次 90 passed，原 manifest/APK 覆盖探针均拒绝、原字节不变。
- 现有 ControlHub 合同测试 88 passed，只证明既有合同，不能证明新桥接成立。
- 正式 WhatIf 两引擎均 DRY_RUN：当前1.1.0、目标1.2.2/Contract2；来源摘要与本地计划摘要一致；官方能力 NOT_PROVEN，生产执行封锁。
- Ruff check/format、diff 检查通过；原 APK、EXE 哈希保持。任务独立复审关闭 canonical 转义、来源路径大小写和 receipt 覆盖问题。
- Android JVM/lint/Release Smoke 本阶段未重跑；8E.1 的 Alpha8 Release 4/4 仅历史证据，不写成当前手机验收。

## 遗留与最高风险

1. 官方升级全过程互斥、计划审批执行绑定和同实例 IPC 缺口阻断生产升级。
2. 旧手机实际安装证书仍 NOT VERIFIED；用户决定暂不迁移 App。
3. Real Phone、Real Home LAN、Real Jellyfin Playback、Real First Frame、Real Review 5-item Playback 均 NOT TESTED。

用户确认 Jellyfin 由其启动；历史退出码1根因仍 UNKNOWN，本阶段没有启动/修复 Jellyfin。生产 Execute NOT PERFORMED；未读取/复制真实数据库内容、未删除真实媒体。当前生产 MediaReview1.1.0与目标产物1.2.2须分别表述。

原始失败、通过及复审证据保留。部分早期 PowerShell 调试输出被后续同名日志覆盖，这一证据限制写入交接；必需的 TDD red、最终通过及独立探针保留。临时目录清理遭自动审批拒绝，目录保留且不绕过重试。

建议先由官方平台补齐合同，再继续受控生产升级与家庭真机验收；不自动进入下一功能阶段。最终 Git、远端和 ZIP/邮件结果由最终交接记录确认。

阶段结论：有条件合格（离线部分可交接，正式桥接 BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT）。
