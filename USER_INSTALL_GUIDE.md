# Alpha8 安装与电脑端升级

候选版本：Android code15 / 2.0.0-alpha8；Server 1.2.2 / Contract2。
状态 READY_FOR_USER_VALIDATION，不代表用户手机或生产环境已经验收。

## 安装前

检查 RELEASE_MANIFEST.json、APK签名验证记录与 SHA256SUMS.txt。备份旧APK/版本信息，
使用单独的 MediaReview_2.0.0-alpha8_stage8e.apk 原位安装；保留App数据。
首次安装显示Alpha8；从旧版本升级只显示一个Sheet，合并所有未读版本的更新说明。

电脑端必须显式设置 jellyfin.client_url，不能依赖请求主机回退。
jellyfin.url 用于电脑访问Jellyfin；client_url用于手机直连，保留代理base path。
不要填写127.0.0.1、WSL/VPN地址或凭据，不向公网开放8766/8096。
本机只读网络证据：WLAN是唯一在线物理接口且有默认网关；候选地址
http://192.168.31.107:8096。该地址可能随DHCP变化，用户手机尚未验证，安装时重新核对。
只读检查显示现有client_url已经是该地址，本阶段没有修改生产配置。

## 当前电脑：既有 ControlHub Windows 服务

当前服务身份为 AIHome.MediaReview，不要运行会建立平行任务的安装流程。
RC安装脚本发现该服务时会在写文件前停止。升级只沿用已有服务身份：

1. 读取 PRE_DEPLOYMENT_REPORT，确认实际安装目录、数据根、旧版本与Jellyfin可达性。
2. 通过ControlHub停止此服务，确认其所属进程退出、8766释放；不按端口批量杀进程。
3. 备份完整 config/、database/（停止后含WAL/SHM）、旧Server目录、WinSW XML/EXE与服务定义。
   备份保留在用户本机安全位置；生成backup_manifest.json，记录时间、旧版本、文件SHA256。
   配置含凭据，备份不得进Git、交接ZIP或邮件。
4. 将RC的MediaReviewServer目录复制到同盘staging；保留旧目录作为回滚，不覆盖旧备份。
   校验SHA256后替换已有服务指向的同一Server目录；不新建任务或服务。
5. 在现有安全配置中设置确认过的client_url；保留原api_key、user_id与其他设置。
6. 通过ControlHub启动原服务。health必须是status=ok、1.2.2、Contract2、六项capabilities完整。
7. 配对后readiness必须Jellyfin可达、设备Key有效、Direct Range可读、client_url权威。
   不满足即升级失败，按UPGRADE_ROLLBACK.md恢复旧二进制及数据。

以上操作需要本机服务管理权限。本次未执行生产覆盖或备份；启动Jellyfin既有服务的尝试失败，
不能把候选地址或电脑端验证写成手机可达。

## 其他电脑：全新安装或既有计划任务

先确认不存在同名运行对象。管理员运行包内scripts/install.ps1，保持其唯一生命周期目标。
既有计划任务安装按原事务式备份/升级/回滚流程执行，不触及用户媒体。
在AIHome纳管时须按当前ControlHub官方接入流程登记，未审批不能声称已接入。
编辑配置后再验证health、readiness及手机直连。不要在Jellyfin未启动或地址未确认时宣称升级完成。

回家后按 USER_LAN_VALIDATION.md 验收；先做安全播放测试，禁止永久删除真实媒体。
