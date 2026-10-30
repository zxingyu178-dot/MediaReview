# Alpha26 三项体验修复

基线 `97ec56b`，目标 Android **2.0.0-alpha26/code33**。本轮按用户反馈修复媒体库批量选择、相册加载与生图图片占比；不改服务端、NSFW源码、数据库或真实媒体。目标“合格产品”仍在持续推进，本批不是整款产品的最终验收。

**最终交付**：构建源码`85a551dfb99a7ac7f3b761fe0400d0ad3cf3730d`，最终Release 5m2s成功；既有证书与实际code33/Alpha26核验通过。APK `deploy_handoff/MediaReview_2.0.0-alpha26_album_review.apk`，20,813,624字节，SHA256 `0c0364607d12b0da1d7d54108517eb68beb523340de637cadb131746e30f414e`；CURRENT_RELEASE已更新并保留Alpha25指针备份。构建前49项与补漏后6项分轮通过，非单轮55全绿。手机未连接，Alpha26未安装手机；Git同步状态见下文。

## 修改及边界

- 媒体库管理增加“全选 / 清空选择”和已选数量。只改草稿，点“应用”才提交；空选仍拦截，保存中禁止改单项或批量草稿。保留失败不假成功、以响应为正式状态的原有规则。
- 书架元数据推迟到首次进入相册时读取，取消视频首页的相册封面推测预取；目录树不再阻塞书架调用。书架传入真实滚动位置，封面和照片预取附近最多8项、最多2并发，滚动/离页取消旧窗口。已有照片50条分页、自选封面及配对缓存隔离不变。封面显示/预取采用相同解码尺寸，图片网格改用轻量AsyncImage。原来的自定义缓存键实际会被鉴权拦截器覆盖，不能将其写成已确认的重复下载根因。
- 相册首载显示加载轮廓，刷新提示跟随真实请求，重复刷新合并，刷新失败保留旧书架并提示。视频页原700ms刷新提示属于下一批待办，本轮未更改。
- 生图去掉压在图片上的信息层，提示词/时间/尺寸从“图片信息”查看；缩略图默认折叠，可从“更多操作”展开。收藏、重置、加载更多移入菜单，主操作保留保留/淘汰及翻页；撤销入口仍可见。图片保持Fit完整显示，预览就绪才可保留/淘汰，换图/URL更新重置就绪状态。评分标签和1–5按钮分行，按钮均分宽度。

生产文件均相对`android/`：`app/build.gradle.kts`；其余Kotlin文件位于`app/src/main/java/com/mediareview/app/`：`feature/v2/organize/libraries/LibraryManager{Screen,ViewModel}.kt`、`feature/v2/home/{HomeScreen,ShelfGrid,AlbumScreen,WindowLoading,V2HomeViewModel}.kt`、`feature/v2/nsfw/NsfwReviewScreen.kt`、`feature/v2/releasenotes/ReleaseNotesCatalog.kt`、`feature/v2/data/{MediaRepository,V2MediaRepositoryRouter}.kt`及`feature/v2/data/server/V2ServerMediaRepository.kt`。调整LibraryManagerViewModelTest、WindowCachePolicyTest、Stage8A11LoadingClosureTest和ReleaseNotes版本合同。

## 已执行验证

前置真机Alpha25：相册书架有可见封面；打开一个143张相册后返回50条，服务端日志请求约5964ms，稍后有18个可见照片节点。没有把两次人工检查间隔71.5秒当成加载耗时。生图图片节点高1679px，屏幕1440×3200。私有截图/层级只留忽略目录，不提交仓库、不描述媒体内容。真机未进行审核、评分、删除或保存全选配置。

定向命令（登记JDK17/Gradle8.9，单worker串行）：

```text
gradle :app:testDebugUnitTest --tests '*LibraryManagerViewModelTest' --tests '*WindowCachePolicyTest' --tests '*AlbumPagingControllerTest' --tests '*AlbumColdCoverContractTest' --tests '*NsfwLoadingPolicyTest' --tests '*ReleaseNotesContractTest' --offline --max-workers=1 --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8'
```

**49项单轮通过，6m56s**：媒体库6、窗口8、相册分页10、自选封面4、生图加载4、版本合同17；Android生产/测试编译通过。新增接口实现前测试编译报告缺少selectAll/clearSelection/windowSize（2m24s），未运行用例，不称功能断言RED。早前Gradle原生锁、daemon日志和Kotlin临时文件写权限失败属于环境失败；仅将本次进程的构建缓存和TEMP放入项目temp，已有依赖只读复用，未改ACL/系统环境或安装工具。全局只读缓存参数初次目录层级错误亦已纠正，未计测试结果。保留原有SDK/测试名称warning，未顺手修改。

没有重跑全量pytest/JVM/connected或构建EXE；没有部署、启停生产服务。证据位于忽略目录`review_meta/album-fix-phone-20261030/`：启动诊断、环境失败、编译失败、jvm-green与私有手机层级。Release与新APK手机复核记录将在实际执行后补充，不以编译通过替代。

### 首候选模拟器复核与补漏

源码`81d5de5`的首次签名Release构建8m24s成功，code33/Alpha26、原证书592c…3564匹配。候选20,813,624字节、SHA256 `3d63408e1486bca98ff154958a1c6af488af76baef9f86fb210d7c62fe19dbf9`，复制哈希一致。手机已断开，未安装。复用登记Android35 AVD的独立数据盘，以只读叠加层测试，生产服务不变。

媒体库全选后两个Checkbox均true，清空后均false；只在隔离夹具点应用，DB确认两库均selected=1。视频页冷启动没有`/media/folders`相册摘要请求。生图图片区域在同一1080×2400 AVD从1198px增至1524px（约27%），无信息覆盖；评分1–5在四条可见评分行均可见。第6张合成预览404后保留/淘汰父节点均enabled=false，未提交审核或评分。最初用第2张模拟404时命中已缓存预览，未误计失败门禁通过，改用原本未成功缓存的第6张验证。

同时发现空书架没有可下拉的滚动子节点，滑动没有新摘要请求。夹具原先只有一个视频索引，最初“暂无相册”属正确空态；随后仅在该隔离DB补一张合成照片，空态仍无法靠下拉恢复。故首候选未进入CURRENT_RELEASE，已移入本轮忽略诊断目录保留。补“重新加载相册”操作，增加仅失效相册摘要的端口并经路由转发，避免使用清空全部媒体资源的全局失效。新定向命令仅`--tests '*Stage8A11LoadingClosureTest'`，**6项通过，6m6s**（含TTL内主动重新取摘要），与前49项分轮记录。最终包需重建并复核空态按钮恢复；此重建因新发现并修复缺陷，不是为重复收集报告。

Git普通推送多次连接reset，GitHub连接器create_tree另返回403 Resource not accessible by integration；未成功同步，未创建替代远端提交。当前代码仍有本地未推送提交，网络恢复后需同步，不能写已push。

最终包再次保留数据安装到隔离AVD，确认code33。空相册显示重新加载入口；仅在夹具DB重新开放那张合成图片后，点击按钮取得1张照片的相册，封面实际渲染为蓝色合成图；非空书架下拉新增1次摘要请求。空态手势本身未改成可滚动，恢复由明确按钮完成。启动时曾有一次Android **System UI**无响应弹窗，选择Wait后继续；未据此指称App崩溃，采样App日志无FATAL，不代表全时段无异常。最终复核只针对补漏，相同生图/评分组件复用首候选证据，不为日志重复测试。

模拟器及临时后端均按已验证的PID/命令行关闭，uiautomator退出、ADB设备/forward及5584/5585/18766监听均为空；未启停生产服务。临时数据盘沿用此前审批拒绝清理的保留状态，本轮未重试删除；构建缓存用于后续增量构建。首候选APK在忽略诊断目录，交付目录只有最终Alpha26，唯一指针哈希复核通过。全部新图均为合成样本，无真实图片审核、评分、媒体删除或真实选择配置写入。

## 后续门槛

相册查询的服务端约6秒等待尚未消除，当前不能声称加载性能已全面达标。下一批需定位数据库查询/锁竞争，同时继续处理范围草稿重置、只读生图同步禁用审核及无确认清配对；原图缩放和目录搜索随后推进。完整全库、长时断线、实际首帧/流畅度与耗电仍未验收，不声称PRODUCTION_READY/REAL_LAN_PASS。
