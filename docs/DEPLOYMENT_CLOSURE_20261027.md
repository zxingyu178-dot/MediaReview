# 1.6.2 / Alpha18 电脑端与邮件收口

用户要求统一当前版本、准备电脑端并发送APK。本文件记录本轮实际操作，不替代PROJECT_STATUS。

## 唯一当前发布

- 目标：Server1.6.2/Contract2/0017 + Android2.0.0-alpha18/code25。
- 程序构建来源bb02671；EXE SHA256 `d34b2a88c5d1773326f3bd1e1a812652b00df137bf75917e259a78780c49a2d4`，APK SHA256 `3cc6d44504fe80961319c5a08b0fefac22b4b7e8ee3c4ae2cf3da2b48883a2ab`。
- 原先当前发布包内安装/回滚/验收指引仍包含1.6.1和历史1.4.0操作文字，已将四份现用指引收口到1.6.2/Alpha18。原文逐字备份于docs/history/20261027-before-release-closure，未删除旧包或备份。
- 当前入口拟为`deploy_handoff/CURRENT_RELEASE.json`，指向`deploy_handoff/current/`；旧目录仅历史/回滚，不按目录时间或版本猜包。
- 仅文档与发布元数据重打包，保留已验证APK/EXE字节及来源记录，不重建程序。

## 运行与授权

只读检查：ControlHub mediareview online；既有AIHome.MediaReview服务Running，health1.5.2/Contract2/windows_service/AIHome.MediaReview；端口8766归属该服务子进程。Jellyfin和管理界面待最终核对。

当前会话无管理员权限。项目规则要求单独授权生产升级，已向用户确认是否允许通过ControlHub停服、完整备份后升级1.6.2，以及是否使用既有QQ掩码收件人；确认前不停止生产、不发送邮件。正式自动升级桥保持封锁，单次已授权维护仍只使用官方ControlHub生命周期，不旁路SCM或Helper。

## APK发送准备

Windows Defender MpCmdRun对实际Alpha18 APK定向扫描完成，退出0，无威胁；实际文件大小43,655,762字节，与上列SHA256一致。复用现有严格APK发送器及既有邮件配置加载方法，不读取/复制凭据文件，不记录完整地址。发送前核对实际MIME中的附件字节、哈希、类型和收件人掩码；成功须同时有SMTP accepted和匹配的SEND_OK，不能仅靠正文发送成功。

发布包、备份、升级、打开界面与邮件的最终结果在实际完成后补录；目前不声称已升级或已发出。
