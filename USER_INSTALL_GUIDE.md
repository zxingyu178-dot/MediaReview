# MediaReview 1.6.5 候选安装入口

本轮目标配套为Server1.6.5 / API Contract2 / 数据库0018 + Android Alpha27/code34。只有完成产物验证并更新deploy_handoff/CURRENT_RELEASE.json后，才作为当前交付版本；实际运行状态见docs/PROJECT_STATUS.md。

1. 从CURRENT_RELEASE.json读取包路径、版本、SHA256，不按文件日期猜包。Server的RELEASE_MANIFEST.json及SHA256SUMS.txt必须逐项匹配；包内与独立APK均应为Alpha27/code34。
2. 生产升级经ControlHub停止AIHome.MediaReview，确认端口释放，完整备份程序、配置、配对和活动数据；校验备份后替换程序，不清除媒体库或配对。
3. 新程序在必要时执行0018回执迁移。重新通过ControlHub启动，核对health版本1.6.5、Contract2、15项能力（包括delete_result_v1）、database=ok及windows_service身份；仅HTTP200不算成功。
4. 手机覆盖安装前核对APK版本、哈希和原发布证书；签名不一致就停止，不卸载或清数据。本轮未连接手机，覆盖安装及视觉交互待验收。
5. 删除保持两阶段确认；超时后查询原批次。结果未知不等于失败或成功，不自动再次删除。生图审核需独立配对NSFW审核服务（默认8877），不会覆盖视频服务连接；本包不会修改或启动NSFW。

本轮只准备交付，不部署或安装。回滚见UPGRADE_ROLLBACK.md，人工验证见USER_LAN_VALIDATION.md。
