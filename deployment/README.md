# MediaReview 发布与部署

候选目标Server1.6.5 / Contract2 / 数据库0018 + Alpha27/code34。当前已验证交付入口始终是deploy_handoff/CURRENT_RELEASE.json；候选源码或包目录名称不能替代版本及完整性核验。

安装见根目录USER_INSTALL_GUIDE.md；成对回滚见UPGRADE_ROLLBACK.md；人工验收见USER_LAN_VALIDATION.md。运行入口仍为ControlHub的mediareview / AIHome.MediaReview，不双击EXE，不另建后台实例。本轮未部署生产。

本版优化相册查询及普通请求响应，支持delete_result_v1查询所属设备的原删除批次；结果未知不虚报释放空间。数据库仍为0018，从更旧版本升级需备份后迁移。生图审核使用独立NSFW审核服务（默认8877），不随本包升级或重启NSFW。实际状态见docs/PROJECT_STATUS.md。
