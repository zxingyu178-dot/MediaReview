# 批阅模式专项：Alpha19

## 根因与改动

Alpha18真机恢复的是整理媒体库前的冻结批阅会话，共56533项，其中52953项已失效。只读抽查当前附近4项：索引仍available但不属于当前勾选库，路径不存在、Jellyfin元数据200、原文件404。不能仅凭封面或元数据存在判断可播放。经正常界面重新选择“全部视频”并确认新会话后得到3987个视频，首条真机播放、进度条拖动成功；原会话结束但已有批阅记录保留，未删除媒体。

批阅页遗漏内核Error到既有播放源状态机的连接，导致404后既不回退也不显示明确错误。现补当前媒体、地址和host所有权检查，只回传当前源错误，继续沿用一次HLS回退及终态错误；错误页提供重试及重新选择批阅范围。未代理Jellyfin流、未修改Server或数据库。

用户明确确认抖音式核心交互：VerticalPager上下切换；单击暂停/继续；进度条左右拖动；取消滑动保留/待删除、双击跳进度、长按快捷操作。保留明确动作按钮及菜单正式倍速。仅改批阅页，完整播放器/共享手势默认行为不变；移除已无调用的滑动决策策略。

## 已执行验证

- 真机Alpha18 + Server1.6.3：旧会话连续项404且进度零；新有效会话3987项、首条进度前进，进度条拖动后仍第1项。没有用真实媒体验证删除。
- 既有`ServerReviewPlaybackTest`新增2项，旧代码有效RED：404未进入Failed；上下导航不符预期，2项失败。证据`device-red.log`。
- JVM：`V2ReviewViewModelTest`40、`ReviewUiPolicyTest`5、`V2PlaybackSourceControllerTest`10、`ReleaseNotesContractTest`16，合计71通过、0失败；debug/test APK编译成功。命令：Gradle `:app:testDebugUnitTest --tests '*V2ReviewViewModelTest' --tests '*ReviewUiPolicyTest' --tests '*V2PlaybackSourceControllerTest' --tests '*ReleaseNotesContractTest' :app:assembleDebug :app:assembleDebugAndroidTest`。
- 隔离MediaReview_Test模拟器，定向instrumentation 4项全部通过（23.079秒）：404终态且不标记已看/不写待删除；上下导航/横向不换页且不写待删除；进度条20%→70%及暂停后拖动仍暂停；单击暂停恢复不重载。证据`device-green.log`。以真实HTTP MP4和实际内核位置验证，不仅检查VM。
- 未跑全部connected/JVM/Server测试：Server没改，复用现有夹具，仅覆盖改动链路。新增测试只在模拟器安装，不替换手机为debug版。
- 构建环境限制：wrapper缓存锁与Kotlin daemon临时目录拒绝访问；复用现有Gradle8.9绝对入口、项目GRADLE_USER_HOME/TEMP，并对子进程传`-Pkotlin.compiler.execution.strategy=in-process`，未改系统权限、PATH或安装工具。初次参数被Shell拆分的构建失败不算测试失败。

## 交付状态与限制

Alpha19/code26 Release构建成功（5分6秒），签名有效且与Alpha18同证书，已通过`adb install -r`覆盖安装，dumpsys确认26/2.0.0-alpha19，未清数据。Server仍为1.6.3/windows_service，安装EXE哈希未变。代码提交`1abbeb0`已push。

真机+实际Server抽样：第5项播放进度持续前进；单击暂停后拖到69.9998%，两次间隔2秒读取保持相同；横向滑动画面仍第5项且进度不变；单击恢复后71.41%→76.10%；上滑第5→6项且5.57%→18.31%，下滑回第5项且1.59%→6.26%；播放中进度条拖到约60%后61.09%→65.77%，页码不变。进度取自播放器无障碍状态，未逐帧核验全部文件，也未遍历3987项。原始脱敏读数`alpha19-phone.json`；辅助会话已关闭，ADB forward为空。

唯一安装入口`deploy_handoff/CURRENT_RELEASE.json`已指向独立`current/MediaReview_2.0.0-alpha19_stage9.apk`，43,655,762字节，SHA256 `f553c6ca30fd845e9beead35cd3cbc9d7787d8a9ab0acb4b883b06e1f8ba7192`。Server包未重建，包内仍附Alpha18，不能用其回装手机。未发送邮件。

这不是全部抖音功能或像素复制；批阅范围与安全操作继续保留项目语义。大规模整理媒体后，旧会话仍是冻结队列；本轮提供明确错误和范围重选，不静默改写历史会话。已改动文件集中在批阅屏幕/策略、既有设备回归与版本说明、构建版本声明；证据位于忽略目录`review_meta/review-20261027`。

剩余限制：范围选择底部面板可能需要向上展开才能看到开始按钮，本轮未改其布局；真实旧条目的错误页仅复用模拟器404定向验证，升级后手机使用新有效会话，未刻意恢复旧会话制造失败。列表/文件夹性能与掉线长期稳定性沿用前轮未闭合项，不属于本次批阅修复完成声明。
