# 视频加载专项（2026-10-30）

基线Alpha24/code31，加载专项`1a0b1ac`；开发分支`codex/video-prefix-prefetch`。

## 范围与实现

沿用GSY Exo2及现有批阅稳定播放回调。当前项稳定后才解析相邻地址，视频字节只预取下一项；切源、拖动、后台沿用原取消钩子。预取最大2MiB/项，最多两条缓存（4MiB存储预算，不代表整个播放器总内存），单一后台请求，不启动第二播放器、不写磁盘媒体。不支持可靠复用的端点继续原播放路径。

缓存绑定当前MediaReview配对身份、URL及请求头身份，键为摘要；预取只接受有明确长度/范围和强ETag的206视频响应，拒绝重定向、压缩、no-store、弱验证器等。播放通过GSY现有HTTP扩展共用读取器：先以If-Match校验尾段/小范围的权限与文件版本，再交付缓存前缀、读取网络尾段。认证失败不交付旧缓存，实体改变时丢弃前缀并沿用原网络读取；拖到缓存之外直接读取原网络。

主要文件：`VideoPrefixCache.kt`（缓存、可取消预取、共享读取器）、`VideoPrefixDataSource.kt`（GSY/Media3薄适配）、`V2ReviewViewModel.kt`（稳定窗口触发/取消）、`TokenProvider.kt`（配对摘要）、`AppModule.kt`（复用现有HTTP客户端）。`V2ReviewScreen.kt`补横向/进度条拖动即时取消，以及暂停/面板打开取消和恢复门禁；手势和进度计算保持原实现。不改变媒体源、服务协议、数据库、删除或UI结构。

## 验证与交付

Alpha25/code32独立Release APK已构建核验并更新交付指针。构建源码`cbe36c4af9392c4163e723ba1ef85be7cc7f4ca7`已push；分支`codex/video-prefix-prefetch`。首轮定向70项通过；另17项版本合同中16通过，1项旧版本预期修正后单项重跑通过，属于分轮闭环。

产物`deploy_handoff/MediaReview_2.0.0-alpha25_video_loading.apk`，20,813,628字节，SHA256 `7acb2228a3db6479cadadd495e3161013643aeb8ecbf5f10e17512f426ce98e1`。Release一次构建6m7s；aapt确认版本32/Alpha25，apksigner验证签名有效，证书SHA256 `592c25955929ca7fa968f4450452a45919b9d8da6d91e8ca7274c5dd65973564`匹配旧版。复制后哈希一致，DEX中确认读取器及GSY消费者均打包；Alpha24及旧指针备份保留。未安装手机。

- 首次Gradle调用因PowerShell未引用带点的`-P`参数而在任务选择阶段失败，未执行测试；后续引用完整参数，不算测试RED。
- 后续测试编译因缺配对摘要方法和预取入口构造参数失败（14m35s），属于接入缺失RED，未执行测试，不能当成运行期故障复现。补接入后运行定向目标。
- 对缓存与HTTP适配做过一次只读定向代码审查，无新增确定问题；不替代运行验证。
- `video-prefix-green.log`：单轮70项通过（配对3、播放决策10、真实HTTP前缀9、WhatsNew7、批阅41），无失败/跳过；构建16m40s。包含401/403拒绝及配对变化、206前缀+尾段真实消费、实体变化/不符范围/no-store回退、容量/TTL、取消真实Call、缓存外seek、重开游标等。XML和摘要已留存。
- 首轮`*ReleaseNotesTest`未匹配到实际类`ReleaseNotesContractTest`，不计为通过；补跑该类17项中16通过/1失败（`video-prefix-notes.log`，3m9s），原测试仍固定期待code31/Alpha24。仅将该版本门禁更新为32/Alpha25并重跑这一项通过（`video-prefix-notes-current-rerun.log/.xml`，2m21s，1项/0失败），不重复已通过目标。最终UI退出/拖动取消钩子编译通过；不得写为单轮87项通过。
- 按变更范围未跑全量JVM/Server或完整lint；Release自带lintVital通过。手机无法连接，按用户要求未运行模拟器，未跑connected；Phone/HomeLAN NOT TESTED，不声称PRODUCTION_READY/REAL_LAN_PASS或真实首帧/解码/拖动体验通过。既有AGP/compileSdk、图标弃用及Android统计权限warning保留，未顺手修改。

定向命令（既有JDK17/Gradle8.9/项目缓存，单worker、in-process Kotlin）：
```text
gradle :app:testDebugUnitTest --tests '*VideoPrefixCacheTest' --tests '*V2ReviewViewModelTest' --tests '*V2PlaybackSourceControllerTest' --tests '*PairedOriginPolicyTest' --tests '*ReleaseNotesTest' --tests '*WhatsNewViewModelTest' --offline --max-workers=1 --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8'
gradle :app:testDebugUnitTest --tests '*ReleaseNotesContractTest' --offline --max-workers=1 --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8'
gradle :app:testDebugUnitTest --tests '*ReleaseNotesContractTest.当前版本和更新说明匹配' --offline --max-workers=1 --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8'
gradle :app:assembleRelease --offline --max-workers=1 --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8'
```

生产MediaReview与NSFW实例均未升级；上一轮NSFW资源优化源码`9115cb6`仍未部署。Alpha24交付文件保留。

上轮临时测试目录清理被自动审批以`blocked by policy`拒绝，目录保留，未绕过限制；本轮没有真实媒体或手机数据操作。

## 剩余限制

仅可靠HTTP直放端点受益；没有强ETag、忽略Range或HLS/DASH清单不使用前缀。预取不替代真实请求的认证/实体校验，仍有一次网络往返；实际收益取决于封装布局与网络。移动端首帧、快速连续切片与真实服务恢复需在手机连接后定向核验。
