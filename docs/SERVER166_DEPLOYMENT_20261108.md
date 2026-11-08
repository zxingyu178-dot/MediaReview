# Server 1.6.6 人工维护部署结果（2026-11-08）

用户本次明确授权“允许本次人工维护升级”。这是一项一次性维护例外，既有自动升级入口仍保留OFFICIAL_COORDINATOR_BRIDGE_REQUIRED，不声称该官方升级事务能力已接通。实际停启均经官方ControlHub lifecycle CLI、integration=mediareview、原AIHome.MediaReview服务身份；未更改服务配置、端口或Jellyfin生命周期。

## 部署与回滚证据

- 已核验1.6.3基线EXE哈希、健康身份；1.6.6候选ZIP/逐文件清单/EXE哈希匹配。停服后复查没有inflight删除，再完整备份当前程序、配置与活动DataRoot（不重复历史.old/.stage和backup/backups）。受保护目录只给System/Administrators访问；源/副本逐文件校验通过，另外执行SQLite backup API及quick_check。
- 管理员收据RUNNING_HEALTH_VERIFIED、backup_verified=true、installed_hashes_verified=true。保留原1.6.3程序及同批次数据备份；自动回滚路径本次未触发，不能称已实演恢复。
- 实际部署EXE SHA256=38068db4fbccf25f93f1cd0675f101df7b204abd880b3da8a0fc2dfa681a777a；健康1.6.6/Contract2/windows_service/AIHome.MediaReview/数据库ok。生产SQLite迁移头0018、quick_check=ok；删除队列仍failed54/pending55，维护起始后delete审计0。
- 私有操作收据位于review_meta/ops-20261108，包含程序/数据库同批次备份路径及旧程序位置，未提交配置或密钥。若需回滚：先经ControlHub停服并确认8766释放，恢复同批次旧程序及0017数据库，保留失败候选数据库另行审查，再经ControlHub启动并核验1.6.3/0017/数据库ok；不要只降程序不恢复数据库。

## 实际手机验证

复用已安装Alpha34、保留原配对。原失败MOV同一卡片重试：Server新任务成功，MR_PERF记录MISS、967.6ms、2201字节；手机重试按钮消失，目视确认封面显示。打开后看到视频画面并到达结束；只读元数据已更新8401ms、3840×2160。短样本不代表全部来源、长时播放或所有编码通过。无保留/待删除/评分/最终删除操作，未移动原媒体。

F盘恢复前33根全不可达，恢复后33/33可达；这个环境原因与FFmpeg不可寻址输入缺陷分开处理。手机端仍须继续优化磁盘离线提示、陈旧元数据刷新及其他体验。截图仅作私有分析后清理，未进入Git，辅助会话结束，无新增ADB forward。

## 唯一版本入口

已部署包按原字节归档在deploy_handoff/recovery-1.6.6；ZIP为112038206字节，SHA256=95d9fbb2d37e17ca5b1411f5805bea71c9fab2bd6da4a93ebf3fb7f25eb21e89，184文件。冻结包manifest的未部署标记属于构建时快照，当前运行事实以上述部署收据/健康及CURRENT_RELEASE为准，不原地改包。包内APK仍Alpha33，当前手机独立APK为Alpha34/code41；不重装包内旧APK。
