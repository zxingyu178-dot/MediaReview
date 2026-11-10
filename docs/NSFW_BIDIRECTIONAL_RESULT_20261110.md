# 生图双向续审：手机源码收口

2026-11-10。用户已批准原序号、总数与双向按需分页。后端候选NSFW f2f3a77，能力mobile_gallery_bidirectional_v1；本轮不部署，不更改真实媒体/审核/评分，不覆盖Alpha35或CURRENT_RELEASE。

## 修改与行为

- NsfwGalleryQuery.kt接previous_page_cursor与可选start_index，旧桥缺字段不伪造范围序号。
- NsfwReviewViewModel.kt维护前后游标、原范围起始序号与总数；已有邻图直接选择，边界请求成功才切相邻图，失败保留原图可重试。加载前页不切图，保留后页游标；旧查询晚返回/401不能覆盖新范围或清配对。
- NsfwFeedStore.kt前插只采用按revision接受的新身份，保留已有版本与tombstone规则，不把增量新图插进当前审核顺序。
- NsfwGallery.kt提供加载前页；沿用稳定图片key和现有滚动锚点。NsfwReviewScreen.kt显示范围序号，删除事件后标记位置待校验；旧桥仍标明已加载窗口。
- 未改审核/评分提交、播放器、原图加载、数据库或生命周期。

## 定向验证

复用登记Gradle8.9/JDK17与既有项目temp/gradle-home2，TEMP/TMP为项目temp；离线、单worker、Kotlin进程内编译，未改系统权限/环境。首次wrapper锁拒绝未执行测试，改用既有已解压入口；没有删除锁或重装工具。

```text
:app:testDebugUnitTest --tests '*NsfwReviewViewModelTest.galleryBidirectional*'
```

实现前3项实际失败（8分38秒）：边界上一张不请求前页、无法失败重试，旧前页用例的请求断言失败。随后最小实现，增强同场景范围序号断言。

```text
:app:testDebugUnitTest --tests '*NsfwReviewViewModelTest.gallery*' --tests '*NsfwRepositoryContractTest.gallery*'
```

本轮XML：VM10项、真实HTTP合同3项，共13通过，0失败/错误，构建6分21秒；没有全量Server/JVM/connected或设备测试。日志在忽略temp/gallery-bidirectional-android-red-20261110.log与green同名前缀文件。原有warning保留。

只读定向审查发现1项Important：新增按钮将!syncing套到所有导航，慢网增量同步期间已加载邻图也被禁用。已修正仅边界联网分支要求!syncing，已有邻图保持可点；未为两行布尔条件添加镜像测试，不重复13项，用:app:compileDebugKotlin补界面编译验证：BUILD SUCCESSFUL，4分24秒，16任务中2执行、14最新；日志temp/gallery-navigation-compile-20261110.log。

## 限制与后续

上述13项通过对应按钮修正前，不能声称修正后同轮全绿。源码界面编译不等于最终APK/实机：滚动锚点真实视觉稳定性、大字体/中文日期控件、断网/多设备组合仍待同产物设备验证。只读审查不替代实测。正式仍Alpha34，Alpha35旧候选保留；新候选编号及统一部署在9图/对比评分和筛选收口后决定。不得为缺设备反复重跑软件测试或绕过既有模拟器审批限制。
