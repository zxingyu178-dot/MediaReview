# MediaReview 当前发布与部署

当前唯一配套：**Server1.6.2 / Contract2 / 0017 + Alpha18 / code25**。

- 当前发布入口：项目 `deploy_handoff/CURRENT_RELEASE.json`，它指向 `deploy_handoff/current/` 的已验证包，包含版本、哈希和来源提交。不要从历史目录按“最新文件”猜包。
- 实际程序及APK字节沿用已验证加载专项产物，只修正发布指引与默认入口，不为文档变更重建EXE/APK。源构建为 `bb02671`，当前文档收口有独立提交与清单。
- 安装/升级只依当前 `USER_INSTALL_GUIDE.md`；回滚只依 `UPGRADE_ROLLBACK.md`；回家短验证依 `USER_LAN_VALIDATION.md`。三份文档位于项目根及当前发布包根。
- 运行入口固定ControlHub既有`mediareview`/`AIHome.MediaReview`，不双击EXE或旧交互启动脚本。无本次升级授权时只准备与核验，不改生产。
- 默认自动升级桥 `scripts/upgrade-aihome-managed.ps1` 仍有正式合同边界，不能将它的fixture或WhatIf当成生产许可。单次人工维护必须有明确授权、ControlHub停启、完整停服备份与逐文件校验，不能旁路到SCM/Helper或另建任务。
- 升级后实际health必须是1.6.2/Contract2/14项能力/database=ok/windows_service/AIHome.MediaReview；若仍1.5.2则尚未升级完成。

本地删除安全、成对回滚与Windows最低版本见当前升级指南。本次不更改Jellyfin配置、防火墙、服务身份或端口；视频直连Jellyfin，本地源通过原Server的认证Range接口，Server不代理完整Jellyfin视频。

旧文档完整备份位于 `docs/history/20261027-before-release-closure/`；旧包保留作历史/回滚，不属于当前默认入口。真实运行和交付状态只看 `docs/PROJECT_STATUS.md` 最新段落。
