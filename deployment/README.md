# MediaReview Server 1.4.0 离线发布

当前目标：Android code19 / `2.0.0-alpha12`；Server `1.4.0` / API Contract `2`。

本阶段仅准备与核验官方离线产物：`MediaReview_2.0.0-alpha12_stage8i.apk`、`MediaReview_Server_1.4.0_stage8i.zip`。不得执行生产部署、用户手机安装、SCM操作或ControlHub生命周期操作；不得修改真实Jellyfin或用户媒体。生产升级桥仍为 `BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT`，该阻塞不禁止获授权的离线构建。

## 既有发布入口

沿用 `scripts/build_deploy.py` 的 `--prepare-only` / `--package-verified` 两步入口。按Stage8I规格统一安排最终构建，APK只生成一次，不为文档更新重复构建。构建前后源/产物哈希、Git状态、实际Server二进制隔离health、APK版本与签名、完整文件集合及证据有效期均须通过原门禁；源码或产物变化需重新核验，禁止旧产物或Debug回退。

Server实际health应为 `status=ok`、`version=1.4.0`、`api_contract=2`，包含十项能力：`folder_tree_v1`、`review_session`、`review_nearest`、`organize`、`delete_nonce`、`duplicates_paged`、`library_selection`、`thumbnail_profiles_v1`、`review_folder_filter_v1`、`delete_receipt_v1`。

完整自动化、签名、隔离二进制与产物一致性门禁通过后，可标记 `CODE_READY` / `READY_FOR_USER_VALIDATION`；不等于 `REAL_LAN_PASS` / `PRODUCTION_READY`。

## 升级边界

`scripts/upgrade-aihome-managed.ps1` 的正式执行仍封锁。现有 `AIHome.MediaReview` 身份不得改为平行计划任务；不能以底层Helper/SCM代替官方协调器的同实例IPC、事务lease、批准绑定与关联审计。隔离fixture通过仅证明对应事务测试，不证明真实生产升级或数据库恢复。

后续网络配置应使用当时确认的地址：`jellyfin.url`给电脑端，`jellyfin.client_url`给手机直连，保留代理路径。手机视频直连Jellyfin，Server不中转完整视频流。本次不更改配置或防火墙。

安装边界、回滚记录和Stage8I验收路线分别见 `USER_INSTALL_GUIDE.md`、`UPGRADE_ROLLBACK.md`、`USER_LAN_VALIDATION.md`。这三份文件均位于交付包根目录；在源码仓库中位于本目录的上一级。

## 历史记录

- Stage8E：Alpha8 code15 / Server1.2.2 / Contract2六项能力；保留旧RC历史，不当作当前产物。
- Stage8E.1：隔离假二进制/数据库验证备份、切换与失败恢复，生产执行拒绝。
- Stage8E.2：来源manifest、完整checksum与实际EXE门禁，以及历史可信RC的两步重打包。历史repackage复用旧APK/Server字节，不是Alpha11/1.3.0新构建入口；当前生产桥仍外部合同阻塞。

历史部署包中的install/repair/uninstall脚本说明不构成本次运行授权。本阶段不创建基础设施。
