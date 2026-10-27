# 1.6.2 / Alpha18 电脑端与邮件收口

用户要求统一当前版本、准备电脑端并发送APK。本文件记录本轮实际操作，不替代PROJECT_STATUS。

## 唯一当前发布

- 目标：Server1.6.2/Contract2/0017 + Android2.0.0-alpha18/code25。
- 程序构建来源bb02671；EXE SHA256 `d34b2a88c5d1773326f3bd1e1a812652b00df137bf75917e259a78780c49a2d4`，APK SHA256 `3cc6d44504fe80961319c5a08b0fefac22b4b7e8ee3c4ae2cf3da2b48883a2ab`。
- 原先当前发布包内安装/回滚/验收指引仍包含1.6.1和历史1.4.0操作文字，已将四份现用指引收口到1.6.2/Alpha18。原文逐字备份于docs/history/20261027-before-release-closure，未删除旧包或备份。
- 当前唯一入口已生成为`deploy_handoff/CURRENT_RELEASE.json`，指向`deploy_handoff/current/`；旧目录添加历史标记，仅供历史/回滚，不按目录时间或版本猜包。
- 仅文档与发布元数据重打包，保留已验证APK/EXE字节及来源记录，不重建程序。

## 运行与授权

只读检查：ControlHub mediareview online；既有AIHome.MediaReview服务Running，health1.5.2/Contract2/windows_service/AIHome.MediaReview；端口8766归属该服务子进程。Jellyfin和管理界面待最终核对。

当前会话无管理员权限。项目规则要求单独授权生产升级，已向用户确认是否允许通过ControlHub停服、完整备份后升级1.6.2，以及是否使用既有QQ掩码收件人；确认前不停止生产、不发送邮件。正式自动升级桥保持封锁，单次已授权维护仍只使用官方ControlHub生命周期，不旁路SCM或Helper。

## APK发送准备

Windows Defender MpCmdRun对实际Alpha18 APK定向扫描完成，退出0，无威胁；实际文件大小43,655,762字节，与上列SHA256一致。复用现有严格APK发送器及既有邮件配置加载方法，不读取/复制凭据文件，不记录完整地址。发送前核对实际MIME中的附件字节、哈希、类型和收件人掩码；成功须同时有SMTP accepted和匹配的SEND_OK，不能仅靠正文发送成功。

## 已完成收口，等待执行确认

当前Server ZIP为134,333,980字节，SHA256 `cdc7df3b1d4fb3df5c394af6f23dd74366719fd199e3aa8dfe6cf9fd66ab0cd0`；182项发布文件校验通过。APK/EXE与上一轮字节完全一致，未重建。新包只修正指引和打包元数据，原ZIP保留，不能混用原包SHA256。当前文档来源提交595eb95，程序构建来源bb02671；完整指针和证据为`current-release-proof.json`。

APK MIME预检通过：一个`application/vnd.android.package-archive`附件，解码后的43,655,762字节及SHA256与当前指针一致，实际预构造邮件58,975,391字节，收件人掩码与已询问的既有QQ一致。`alpha18-email-preflight.json`明确smtp_accepted=false，未发送。

维护脚本已准备并有默认只读模式，锁定当前指针、ZIP/manifest/EXE哈希、旧1.5.2基线和同一ControlHub身份；语法检查0错误。原脚本只读预检通过；新增队列只读守卫确认无committing/delete_pending/delete_done，保留38条failed和1条pending，未处理队列。当前进程非管理员，正式执行还需要Windows管理员授权。

ControlHub GUI已启动，窗口Responding；ControlHub的MediaReview、Jellyfin均online，Jellyfin服务Running、health HTTP200。MediaReview管理页与Jellyfin页面已提交内嵌浏览器打开请求，工具返回queued，不能声称页面已在桌面可见。一次组合预检/打开命令被自动审批以blocked by policy拦截；未执行其中的停服或升级，后续仅分开完成允许的文件标记、只读语法/队列检查和官方页面打开请求，未绕过审批执行生产变更。

目前生产仍1.5.2/windows_service/Running；未执行备份、生产替换或邮件发送。用户尚未回答本次升级与QQ掩码收件人确认，因此不能称“回家可直接测试新版服务端”。确认到达后再执行已准备的维护步骤；不删除旧包，不清手机数据，不改变服务身份/端口，不操作真实媒体。
