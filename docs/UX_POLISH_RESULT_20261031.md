# Alpha26 三项体验修复

基线 `97ec56b`，目标 Android **2.0.0-alpha26/code33**。本轮按用户反馈修复媒体库批量选择、相册加载与生图图片占比；不改服务端、NSFW源码、数据库或真实媒体。目标“合格产品”仍在持续推进，本批不是整款产品的最终验收。

## 修改及边界

- 媒体库管理增加“全选 / 清空选择”和已选数量。只改草稿，点“应用”才提交；空选仍拦截，保存中禁止改单项或批量草稿。保留失败不假成功、以响应为正式状态的原有规则。
- 书架元数据推迟到首次进入相册时读取，取消视频首页的相册封面推测预取；目录树不再阻塞书架调用。书架传入真实滚动位置，封面和照片预取附近最多8项、最多2并发，滚动/离页取消旧窗口。已有照片50条分页、自选封面及配对缓存隔离不变。封面显示/预取采用相同解码尺寸，图片网格改用轻量AsyncImage。原来的自定义缓存键实际会被鉴权拦截器覆盖，不能将其写成已确认的重复下载根因。
- 相册首载显示加载轮廓，刷新提示跟随真实请求，重复刷新合并，刷新失败保留旧书架并提示。视频页原700ms刷新提示属于下一批待办，本轮未更改。
- 生图去掉压在图片上的信息层，提示词/时间/尺寸从“图片信息”查看；缩略图默认折叠，可从“更多操作”展开。收藏、重置、加载更多移入菜单，主操作保留保留/淘汰及翻页；撤销入口仍可见。图片保持Fit完整显示，预览就绪才可保留/淘汰，换图/URL更新重置就绪状态。评分标签和1–5按钮分行，按钮均分宽度。

生产文件均相对`android/`：`app/build.gradle.kts`；`feature/v2/organize/libraries/LibraryManager{Screen,ViewModel}.kt`；`feature/v2/home/{HomeScreen,ShelfGrid,AlbumScreen,WindowLoading,V2HomeViewModel}.kt`；`feature/v2/nsfw/NsfwReviewScreen.kt`；`feature/v2/releasenotes/ReleaseNotesCatalog.kt`（后十项位于`app/src/main/java/com/mediareview/app/`）。调整LibraryManagerViewModelTest、WindowCachePolicyTest和ReleaseNotes版本合同。

## 已执行验证

前置真机Alpha25：相册书架有可见封面；打开一个143张相册后返回50条，服务端日志请求约5964ms，稍后有18个可见照片节点。没有把两次人工检查间隔71.5秒当成加载耗时。生图图片节点高1679px，屏幕1440×3200。私有截图/层级只留忽略目录，不提交仓库、不描述媒体内容。真机未进行审核、评分、删除或保存全选配置。

定向命令（登记JDK17/Gradle8.9，单worker串行）：

```text
gradle :app:testDebugUnitTest --tests '*LibraryManagerViewModelTest' --tests '*WindowCachePolicyTest' --tests '*AlbumPagingControllerTest' --tests '*AlbumColdCoverContractTest' --tests '*NsfwLoadingPolicyTest' --tests '*ReleaseNotesContractTest' --offline --max-workers=1 --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8'
```

**49项单轮通过，6m56s**：媒体库6、窗口8、相册分页10、自选封面4、生图加载4、版本合同17；Android生产/测试编译通过。新增接口实现前测试编译报告缺少selectAll/clearSelection/windowSize（2m24s），未运行用例，不称功能断言RED。早前Gradle原生锁、daemon日志和Kotlin临时文件写权限失败属于环境失败；仅将本次进程的构建缓存和TEMP放入项目temp，已有依赖只读复用，未改ACL/系统环境或安装工具。全局只读缓存参数初次目录层级错误亦已纠正，未计测试结果。保留原有SDK/测试名称warning，未顺手修改。

没有重跑全量pytest/JVM/connected或构建EXE；没有部署、启停生产服务。证据位于忽略目录`review_meta/album-fix-phone-20261030/`：启动诊断、环境失败、编译失败、jvm-green与私有手机层级。Release与新APK手机复核记录将在实际执行后补充，不以编译通过替代。

## 后续门槛

相册查询的服务端约6秒等待尚未消除，当前不能声称加载性能已全面达标。下一批需定位数据库查询/锁竞争，同时继续处理范围草稿重置、只读生图同步禁用审核及无确认清配对；原图缩放和目录搜索随后推进。完整全库、长时断线、实际首帧/流畅度与耗电仍未验收，不声称PRODUCTION_READY/REAL_LAN_PASS。
