# Stage8E.2 官方升级桥接与可信来源

## 阶段合同判定

本阶段正式桥接为 `BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT`，不是 `CONTROLLED_UPGRADE_READY`。ControlHub 当前没有绑定既有 GUI coordinator 的官方 IPC，也没有升级全过程的事务 lease、UpgradePlan 审批对象和关联计划的生命周期审计。现有 kernel 在单个 execute 返回时释放锁；Stop 与后续 Start 之间的文件事务不能由薄 façade 获得互斥。

这是官方合同缺口，不能由 MediaReview 另造 Controller、锁、审批、审计或直接调用 Helper/SCM 补足。正式平台需提供以下最小能力后再接入：

1. 由当前 GUI 实例持有的 coordinator 提供版本化本机 IPC/capabilities；没有实例或能力不足则封锁，不新建内核。
2. 官方 coordinator 的事务 reservation/lease 覆盖 Stop、调用方文件事务、Start/readiness、失败恢复；GUI 等同目标入口遵守同一锁；断连与超时有明确恢复语义。
3. 官方审批绑定 immutable UpgradePlan digest、manifest identity 和 exact target；任何版本、路径、来源包或动作计划变化使旧批准失效。
4. 官方审计关联 approval、plan digest、transaction 与各 Stop/Start request/result；平台写生命周期审计，MediaReview 只写文件事务日志。

只开放 Status/Stop/Start 和必要身份/结果。文件与数据库升级仍属于 MediaReview。上述 core 能力不是当前允许的薄 façade 能独立提供的，因此本阶段不私自扩展正式平台合同、不替换安装版、不自行批准。

## 当前 MediaReview 范围

- `verified-rc.ps1`：只读校验 RC 的完整 SHA256 文件集合、manifest 版本/contract 与实际 EXE；非法来源在 Stop 前拒绝。固定服务目标为 `AIHome.MediaReview`。
- 升级计划记录当前/目标版本、contract、包与EXE摘要、install/dataRoot、动作序列和release Git HEAD。摘要仅为本地 canonical plan identity，不能写成官方批准。
- 原事务的停服后备份、隐藏文件/sidecar、staging、readiness 与失败恢复继续保留；本阶段仅隔离 fixture 执行。生产 Execute 继续失败关闭。
- `build_deploy.py` 的 verified repackage 只复用已知 Stage8E 的原 APK 和整个 Server dist，不构建/重签产品二进制。旧 RC 完整 inventory 的固定信任锚避免修改 DLL 后重算 checksum 冒充原构建。
- 新 manifest 分开记录实际 `runtime_build_git_head`、原正式 `runtime_release_git_head`、当前 `package_git_head`；原实际编译头与原发布头不同，必须保留真实来源。
- repackage receipt 保护原来源、当前 runtime 输入、当前部署工具输入与 final HEAD；输入变化必须重新验证，历史 RC/APK 不覆盖。

## 退出与验证边界

官方入口未获得，因此本阶段不实际生成新部署 RC。交付可验证的 MediaReview 部分和具体上游合同缺口，不能宣称官方批准、GUI/升级全过程互斥或正式新审计通过。

生产 Execute：NOT PERFORMED。Real Phone、Real Home LAN、Real Jellyfin Playback、Real First Frame、Real Review 5-item Playback 均为 NOT TESTED。用户确认 Jellyfin 由其启动；历史退出码1根因仍 UNKNOWN，不把启动状态当作本阶段修复。

Android Alpha8/code15、Server1.2.2/Contract2不变；原APK保持原字节，旧手机证书仍 NOT VERIFIED，暂不迁移。准确测试、Git及交付结果由本阶段最终交接证据记录，不用本设计说明替代实际执行结果。
