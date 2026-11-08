# Alpha34 正式签名候选（2026-11-08）

- 源码0fb1e5b8ac4e999a6e198f77f696233839654a7d；错误归属修复0d45252。版本2.0.0-alpha34/code41。
- 17项ReleaseNotesContractTest通过；5项打包入口门禁通过。复用上一轮12项错误归属/回退验证，不重复运行无关测试。版本门禁2m57s，正式assembleRelease一次5m43s（55任务，23执行、32已是最新）。
- 原签名SHA256=592c25955929ca7fa968f4450452a45919b9d8da6d91e8ca7274c5dd65973564匹配，280项builder记录输入前后无漂移（此数量是builder定义范围，不代表所有测试/文档输入）。APK演示媒体资产0。
- APK：deploy_handoff/MediaReview_2.0.0-alpha34_stage9.apk（独立候选），20862776字节，SHA256=dea0b01b6fc0ba8689aa6ec2feab629503873a992548ca582f5e700e8bd8fa16；复制后哈希一致。
- 已向同一USB手机install -r保留数据安装成功，dumpsys确认Alpha34/code41。未卸载、清配对或设置。启动后检查手机已锁屏、screenState=OFF，已请用户解锁；正式包批阅错误提示/回退与有效视频seek尚未验收。不能把安装成功当成播放修复已验证。
- CURRENT_RELEASE仍保持上轮交付Alpha33，候选待验收后再切换；Server未构建或部署（生产仍1.6.3，1.6.6是独立候选）。未发邮件、未操作真实媒体/审核决定/评分/删除。
- Android辅助会话结束，未新增ADB forward。待解锁后复用同一APK测试，不重建相同产物。构建输入、日志与哈希记录：review_meta/alpha34-*（忽略文件）。
