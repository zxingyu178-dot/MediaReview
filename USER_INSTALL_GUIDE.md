# MediaReview 安装入口

当前独立客户端为 Android Alpha31/code38，安装文件以 `deploy_handoff/CURRENT_RELEASE.json` 的 `apk` 字段为准。最近生产核对为 Server1.6.3；Server1.6.5 / API Contract2 / 数据库0018是尚未部署的候选，不能把交付清单中的候选版本当成电脑正在运行的版本。实际运行状态见 `docs/PROJECT_STATUS.md`。

## 手机更新

从唯一指针找到独立APK，核对版本、SHA256和原发布证书后覆盖安装，保留配对与设置。签名不一致就停止，不卸载或清数据。不要从历史Server ZIP取出内置的旧APK。

Alpha31的图库及位置恢复已有隔离模拟器证据，本轮尚未实机验收。后续源码 `ec3cc1d` 的图库位置读取失败提示修正尚未包含在该APK中；Git最新提交不等于已安装版本。手机安装完成后在更新说明中确认实际版本。

这次独立客户端更新不代表Server已升级。新服务端能力以实际健康信息为准；需要1.6.5的能力须完成下述单独部署流程，不能仅通过安装手机APK获得。

## Server1.6.5候选部署

以下步骤仅用于已获授权的电脑端升级，不是每次更新手机都要执行的步骤。

1. 从CURRENT_RELEASE.json读取包路径、版本、SHA256，不按文件日期猜包。Server的RELEASE_MANIFEST.json及SHA256SUMS.txt必须逐项匹配；独立APK按CURRENT_RELEASE的android_version核对；包内APK按server_zip_bundled_android_version核对。
2. 生产升级经ControlHub停止AIHome.MediaReview，确认端口释放，完整备份程序、配置、配对和活动数据；校验备份后替换程序，不清除媒体库或配对。
3. 新程序在必要时执行0018回执迁移。重新通过ControlHub启动，核对health版本1.6.5、Contract2、15项能力（包括delete_result_v1）、database=ok及windows_service身份；仅HTTP200不算成功。
4. 手机覆盖安装前核对APK版本、哈希和原发布证书；签名不一致就停止，不卸载或清数据。覆盖安装与视觉交互的实测范围见docs/PROJECT_STATUS.md，不用旧版截图代替。
5. 删除保持两阶段确认；超时后查询原批次。结果未知不等于失败或成功，不自动再次删除。生图审核需独立配对NSFW审核服务（默认8877），不会覆盖视频服务连接；本包不会修改或启动NSFW。

Server本轮仅准备交付、未部署；手机安装情况以实际版本检查为准。回滚见UPGRADE_ROLLBACK.md，人工验证见USER_LAN_VALIDATION.md。

Alpha31为独立客户端更新；此前已生成的Server1.6.5 ZIP内仍为Alpha27，原ZIP/manifest/哈希保持不变，本轮不重建EXE或重打Server包。手机安装使用CURRENT_RELEASE的apk字段指向的独立APK，不从历史Server包另取旧APK。
