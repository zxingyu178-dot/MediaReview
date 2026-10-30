# 1.6.2 / Alpha18 电脑端与邮件收口

用户要求统一当前版本、准备电脑端并发送APK。本文件记录本轮实际操作，不替代PROJECT_STATUS。

## 唯一当前发布

- 目标：Server1.6.2/Contract2/0017 + Android2.0.0-alpha18/code25。
- 程序构建来源bb02671；EXE SHA256 `d34b2a88c5d1773326f3bd1e1a812652b00df137bf75917e259a78780c49a2d4`，APK SHA256 `3cc6d44504fe80961319c5a08b0fefac22b4b7e8ee3c4ae2cf3da2b48883a2ab`。
- 原先当前发布包内安装/回滚/验收指引仍包含1.6.1和历史1.4.0操作文字，已将四份现用指引收口到1.6.2/Alpha18。原文逐字备份于docs/history/20261027-before-release-closure，未删除旧包或备份。
- 当前唯一入口已生成为`deploy_handoff/CURRENT_RELEASE.json`，指向`deploy_handoff/current/`；旧目录添加历史标记，仅供历史/回滚，不按目录时间或版本猜包。
- 仅文档与发布元数据重打包，保留已验证APK/EXE字节及来源记录，不重建程序。

## 初始运行与授权（历史准备记录）

只读检查：ControlHub mediareview online；既有AIHome.MediaReview服务Running，health1.5.2/Contract2/windows_service/AIHome.MediaReview；端口8766归属该服务子进程。Jellyfin和管理界面待最终核对。

当前会话无管理员权限。项目规则要求单独授权生产升级，已向用户确认是否允许通过ControlHub停服、完整备份后升级1.6.2，以及是否使用既有QQ掩码收件人；确认前不停止生产、不发送邮件。正式自动升级桥保持封锁，单次已授权维护仍只使用官方ControlHub生命周期，不旁路SCM或Helper。

## APK发送准备

Windows Defender MpCmdRun对实际Alpha18 APK定向扫描完成，退出0，无威胁；实际文件大小43,655,762字节，与上列SHA256一致。复用现有严格APK发送器及既有邮件配置加载方法，不读取/复制凭据文件，不记录完整地址。发送前核对实际MIME中的附件字节、哈希、类型和收件人掩码；成功须同时有SMTP accepted和匹配的SEND_OK，不能仅靠正文发送成功。

## 确认前的收口准备（历史记录）

当前Server ZIP为134,333,980字节，SHA256 `cdc7df3b1d4fb3df5c394af6f23dd74366719fd199e3aa8dfe6cf9fd66ab0cd0`；182项发布文件校验通过。APK/EXE与上一轮字节完全一致，未重建。新包只修正指引和打包元数据，原ZIP保留，不能混用原包SHA256。当前文档来源提交595eb95，程序构建来源bb02671；完整指针和证据为`current-release-proof.json`。

APK MIME预检通过：一个`application/vnd.android.package-archive`附件，解码后的43,655,762字节及SHA256与当前指针一致，实际预构造邮件58,975,391字节，收件人掩码与已询问的既有QQ一致。`alpha18-email-preflight.json`明确smtp_accepted=false，未发送。

维护脚本已准备并有默认只读模式，锁定当前指针、ZIP/manifest/EXE哈希、旧1.5.2基线和同一ControlHub身份；语法检查0错误。原脚本只读预检通过；新增队列只读守卫确认无committing/delete_pending/delete_done，保留38条failed和1条pending，未处理队列。当前进程非管理员，正式执行还需要Windows管理员授权。

ControlHub GUI已启动，窗口Responding；ControlHub的MediaReview、Jellyfin均online，Jellyfin服务Running、health HTTP200。MediaReview管理页与Jellyfin页面已提交内嵌浏览器打开请求，工具返回queued，不能声称页面已在桌面可见。一次组合预检/打开命令被自动审批以blocked by policy拦截；未执行其中的停服或升级，后续仅分开完成允许的文件标记、只读语法/队列检查和官方页面打开请求，未绕过审批执行生产变更。

目前生产仍1.5.2/windows_service/Running；未执行备份、生产替换或邮件发送。用户尚未回答本次升级与QQ掩码收件人确认，因此不能称“回家可直接测试新版服务端”。确认到达后再执行已准备的维护步骤；不删除旧包，不清手机数据，不改变服务身份/端口，不操作真实媒体。


## 最终结果：用户确认后已执行

用户明确回复“确认升级并发送”，授权经ControlHub停服、完整备份、升级1.6.2并启动，以及向已确认的既有QQ掩码收件人发送Alpha18。以下结果取代上方确认前状态。

- 首次完整备份被旧手动实例占用数据库阻止，未替换程序，并经ControlHub恢复1.5.2。该次不完整备份已标记INCOMPLETE_BACKUP，不可用于回滚。
- Windows Restart Manager只读确认旧PID3896仍持有数据库；核对路径、创建时间、旧版程序哈希、非服务子进程且不持有监听端口后，先完成受保护的SQLite一致性快照（integrity_check=ok），再经ControlHub停服并只终止该已确认残留实例。没有批量结束进程。
- 重试完成受保护的停服全量备份、文件校验及升级，经ControlHub启动成功。有效完整备份目录名为`manual-1.6.2-20261027-180323`；另保留一致性快照`pre-stale-cleanup-20261027-180300`和替换前程序。路径与恢复凭据仅保留在本机受保护备份/忽略的运维记录中。
- 独立终检：Server1.6.2/Contract2，数据库ok，14项能力，windows_service/AIHome.MediaReview；只有一个MediaReview实例，服务子进程与8766监听归属一致，安装EXE哈希与CURRENT_RELEASE完全相同。服务身份、端口及配置保持原有值。旧包与回滚文件仍保留；以后从ControlHub启动，不手动运行历史EXE。
- Alpha18真实MIME附件核对通过，SMTP accepted、拒收0。附件43,655,762字节，SHA256与CURRENT_RELEASE一致。仅记录掩码，不复制凭据、不重复发送；SMTP接受不等于用户已收到或阅读。
- ControlHub GUI已启动并Responding；Jellyfin服务Running、健康200，管理页200。应用内浏览器打开请求返回queued，未确认桌面可见；外部浏览器启动被自动审批以`blocked by policy`拒绝，未绕过。

本机证据（review_meta/ops-20261027）：production-162-upgrade.json、production-162-final.json、stale-cleanup-receipt.json、database-lock-owners.json、alpha18-email-receipt.json。失败首轮凭据另存production-162-attempt1-failed.json，保留失败事实。

删除队列只读守卫无进行中提交，原38条failed与1条pending保留；未处理队列、未操作真实媒体、未清手机数据。没有为本轮文档/部署重复构建或重跑全量测试。未执行生产媒体索引刷新；若手机仍显示旧数量，先核对选库并按正常流程刷新，不能把服务健康当作目录完整性验收。真实手机/HomeLAN播放尚待用户回家验证。
