# 当前修复交付与安装边界

> 2026-10-27：当前修复目标为Server1.6.1/Contract2/迁移0017，手机可继续使用已交付的Alpha17/code24，无需卸载或清数据。实际交付须核对包内RELEASE_MANIFEST和SHA256。生产当前1.5.2，升级须另行授权，通过ControlHub停服、完整备份程序和活动数据、替换校验后再启动；确认当前所需库选择后重新同步。下文为Alpha12历史说明，不是当前验收清单。

> **本地安全删除最低系统要求**：Windows 10 版本 1709 或更高版本。旧版本不支持
> Server 要求的 POSIX 安全删除语义时，文件会保持原样，App 提示“当前 Windows 版本不支持
> 安全本地删除，请升级系统或使用电脑端删除”。不会自动改用传统延迟删除方式。

## Alpha12 历史记录

当前发布目标：Android code19 / `2.0.0-alpha12`；Server `1.4.0` / API Contract `2`。

本次只交付离线产物，不部署生产服务器、不安装用户手机、不清手机数据，不操作 SCM 或 ControlHub 生命周期。生产升级桥仍为 `BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT`。保留当前运行环境，等待官方入口具备条件后再安排安装与升级。

## 收到产物后

- Android：`MediaReview_2.0.0-alpha12_stage8i.apk`。
- Server：`MediaReview_Server_1.4.0_stage8i.zip`。
- 对照 `RELEASE_MANIFEST.json`、签名验证记录及 SHA256 清单核对文件；交接中有证据才认定产物已生成。
- 全部自动化、签名、实际二进制隔离核验及产物一致性门禁通过后，可标记 `CODE_READY` / `READY_FOR_USER_VALIDATION`。这些状态不表示已安装、生产已升级或真实家庭 LAN 已通过。

目标 Server 的 health 必须为 `status=ok`、`version=1.4.0`、`api_contract=2`，并包含十项能力：`folder_tree_v1`、`review_session`、`review_nearest`、`organize`、`delete_nonce`、`duplicates_paged`、`library_selection`、`thumbnail_profiles_v1`、`review_folder_filter_v1`、`delete_receipt_v1`。

后续获准安装时保留 App 数据。首次安装只显示 Alpha12；18→19 只显示 Alpha12；17→19 在一个 Sheet 聚合 Alpha11/12。关闭保存19，重启不再自动弹出，设置页「本次更新」只显示当前版本。

## 后续升级前提

沿用既有 `AIHome.MediaReview` 身份，不创建平行服务或计划任务，不直接调用 SCM/Helper。官方升级桥开放且获得相应授权后，先确认完整安全备份、来源完整性与官方事务控制；回滚边界见 `UPGRADE_ROLLBACK.md`。

`jellyfin.url` 用于电脑端访问；`jellyfin.client_url` 用于手机直连，必须在后续部署时确认实际家庭网络地址，保留代理路径前缀。不要复用历史电脑 IP，不记录凭据；电脑健康不能证明手机可播。本次不修改配置或防火墙。

用户体验验收仅使用 [Stage8I路线](USER_LAN_VALIDATION.md)，前提是官方升级入口和后续安装条件已具备。该文件随服务器包一起交付，与项目 Stage8I 验收路线一致。

## 历史记录

Stage8E Alpha8：Android code15 / `2.0.0-alpha8`，Server `1.2.2` / Contract2 / 六项能力。其旧安装与服务操作说明属于历史，不构成本次部署授权。Stage8E.1/8E.2验证了隔离升级事务与可信来源门禁；正式生产桥仍因外部 ControlHub 合同阻塞。
