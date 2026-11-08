# 本地视频封面尾部索引修复（2026-11-08）

真机Alpha33显示本地视频封面422；生产任务表只读计数失败281、成功132。不能据此断言所有失败同因，也不能据00:00断言视频不可播放。

使用隔离合成MOV（320×240、15fps、3秒、248186字节，索引在尾部），同一只读句柄输入：pipe:0提取失败且输出0字节；-fd 0 -i fd:成功输出13146字节JPEG。FFmpeg官方协议文档确认fd支持普通文件seek、pipe不保留该能力：https://ffmpeg.org/ffmpeg-protocols.html#fd 。

生产修改仅native_images.py输入协议：仍继承经过路径/身份验证的只读stdin句柄，不按路径重开；保留15秒超时、单张/尺寸/输出大小限制。

既有test_native_images.py视频样本参数化faststart/tail-index；通过MEDIAREVIEW_TEST_FFMPEG_DIR指定已登记部署工具，未安装重复工具。生成较大合成视频，避免小样本被缓冲完整掩盖seek问题。

验证命令：在server目录，设置测试FFmpeg目录后执行 python -m pytest tests/test_native_images.py 。先单跑video_cover：1失败（tail-index）/1通过；修复后整个文件6通过，140.26秒，1既有Starlette弃用警告。测试均使用临时来源，无真实媒体操作。

源码修复尚未打包或部署，生产Server仍1.6.3。未称281项失败全部解决，未重复全量测试、未构建Android。下一步核对打包FFmpeg协议能力及部署后真机封面恢复，仍需区分不支持编码、损坏文件、超时等其他错误。

## 1.6.6候选交付（2026-11-08）

版本源码c86ece4（封面修复afa21d0）；Server三处声明和builder已统一1.6.6，24项部署合同用例通过。使用既有builder的build_exe/verify_server_binary；实际EXE健康1.6.6、Contract2、能力校验通过。EXE SHA256：38068db4fbccf25f93f1cd0675f101df7b204abd880b3da8a0fc2dfa681a777a。

复用package_sources/release_manifest/write_sha256/verify_zip生成独立候选，复用已交付Alpha33并重新校验哈希、版本、签名，不重建APK。候选zip：deploy_handoff/candidates/MediaReview_Server_1.6.6_candidate.zip，112038206字节，SHA256 95d9fbb2d37e17ca5b1411f5805bea71c9fab2bd6da4a93ebf3fb7f25eb21e89，184文件校验通过。包内manifest明确CANDIDATE_NOT_PRODUCTION_VERIFIED。

CURRENT_RELEASE未改，生产仍1.6.3，未停启或部署；EXE健康不等于真实封面恢复或数据库升级验收。本轮验证过程曾因工作目录导致版本更新/pytest导入失败，已在正确目录重跑；APK校验首次缺ANDROID_HOME，设置已登记的SDK/JDK进程级变量后通过，未修改用户/系统环境。完整运行日志及候选清单位于review_meta/server166-*。

## 部署预检（2026-11-08）

- 已登记upgrade-aihome-managed.ps1默认Dry Run实际接受候选，识别CurrentVersion1.6.3、目标1.6.6、服务Running；SourcePackageHash=b511b66185cb3cce2402c4640fac31c29d4347712f2188e65b55f86b011407c5，EXE匹配。未Execute、未停服、未备份或修改生产。
- 生产迁移头只读为0017；升级会进入0018（新增删除回执字段）。仅聚合检查删除队列：failed54、pending55，无committing；没有读出或操作具体媒体。
- python -m pytest tests/test_delete_result_recovery.py -k migration：1通过、6未选，9.04秒，1既有警告。临时库验证0017→0018旧回执保留，running阻止downgrade，终态可退回0017并保留原大小。
- 生产入口明确返回BLOCKED_OFFICIAL_BRIDGE / OFFICIAL_COORDINATOR_BRIDGE_REQUIRED，缺同实例IPC、事务互斥、升级计划绑定、逐步审计；未将本地PlanDigest当作官方批准，未调用旧临时部署脚本替代该入口。
- 首轮-WhatIf触发PowerShell读包辅助操作被WhatIf抑制，返回SOURCE_PACKAGE_INVALID；改用脚本已有默认只读模式后候选验证通过。因此首次结果不是候选损坏证据。该WhatIf行为另记为未修复诊断问题。
- 仍未生产部署、未切换CURRENT_RELEASE、未称真机封面已恢复；正式升级入口需进一步核实。已形成可审查的候选、哈希与迁移/回滚证据。

## WhatIf可信包校验修复（2026-11-08）

当前官方lifecycle CLI只列status/start/stop/restart；官方onboarding与contract未发现可用于本项目的升级事务入口。此处只读核对，不修改ControlHub、不自行创建部署旁路。

修复部署脚本WhatIf误报：Windows PowerShell Get-FileHash的ProviderPath读取继承WhatIf后被抑制，导致有效包SOURCE_PACKAGE_INVALID。最小回归先失败，验证器改用已枚举文件的File.OpenRead和SHA256.ComputeHash，始终finally释放句柄；未关闭WhatIf、未更改路径边界/哈希/清单/审批要求。

既有test_verified_rc.ps1补充WhatIf有效包摘要一致与篡改包仍拒绝。Windows PowerShell5.1及当前PowerShell均166断言通过（分两轮），包含原有路径/清单/大小写/链接拒绝项。实际1.6.6候选在修复后-WhatIf返回DRY_RUN，当前1.6.3、目标1.6.6、SourcePackageHash保持b511b66185cb3cce2402c4640fac31c29d4347712f2188e65b55f86b011407c5。

新验证器仅在源码中；已冻结1.6.6候选不原地重打包，候选ZIP哈希未变。生产执行仍BLOCKED_OFFICIAL_BRIDGE，未停服、未部署。未重复构建EXE/APK或运行无关全量测试。
