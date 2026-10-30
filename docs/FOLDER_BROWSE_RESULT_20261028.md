# 文件夹分类与排序整改（Alpha20）

## 审查发现及整改

1. 分类默认递归，子目录入口与深层图片/视频混排。现按用户确认改为文件管理器方式：子目录在前，默认仅当前目录，保留直接图片/视频，可显式开启递归。
2. 目录缺排序入口，请求及打开媒体借用首页sortSpec。现常驻全部/视频/图片筛选和排序入口，复用最近添加/文件名/时长/大小及正倒序；独立于首页，跨目录保持，媒体队列携带实际目录排序。
3. 单库无子目录时根内文件未直接展开。现单库根均可读直接媒体，多库先选库。类型筛选不隐藏子文件夹，名称排序跟随方向，其余字段下子目录固定名称正序。
4. 递归总数、当前数量和加载状态混淆。卡片明确总数/当前数/子目录数，页内注明范围及筛选结果；加载中不报0项，空筛选不再称整个文件夹为空。

改动集中于FolderBrowserScreen、FolderBrowsePolicy、FolderPagingController、V2HomeViewModel、SortFilterSheet、FolderTile及既有用例/版本声明。首页全部/最近仍为全库媒体墙；不移动媒体，不改Server/API/数据库或删除流程。沿用50项分页、排序/类型/范围缓存隔离和迟到响应防护，不在手机只对当前页重排。

## 执行验证

- RED：FolderPagingControllerTest 7项中2项按预期失败，覆盖默认范围和排序切换；`review_meta/folders-20261027/red-jvm.log`。
- GREEN：39项相关JVM通过，0失败：FolderPagingControllerTest 7、HomeLayoutPolicyTest 8、BrowseContextTest 6、FolderTreeHttpContractTest 2、ReleaseNotesContractTest 16。Debug/test编译成功，4分38秒。首轮新增枚举漏import导致编译失败，补齐后成功，不混称首轮全绿。
- Gradle目标：`:app:testDebugUnitTest --tests '*FolderPagingControllerTest' --tests '*HomeLayoutPolicyTest' --tests '*BrowseContextTest' --tests '*FolderTreeHttpContractTest' --tests '*ReleaseNotesContractTest' :app:assembleDebug :app:assembleDebugAndroidTest`；登记Gradle8.9/JDK17，`--offline --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process`，项目缓存/TEMP，无系统环境变更。
- 隔离MediaReview_Test：`FolderBrowserTouchTest`整类3项、`Stage8IFolderHttpTest#actualHttpHierarchyScopeCoverAndBack`1项，25.903秒，`OK (4 tests)`。覆盖实际点击、根内混合媒体、图片筛选仍能进入目录、大小倒序与正确队列、直接/递归范围、面包屑返回、50项分页、封面预取。HTTP用例经过配对bootstrap/生产Repository与合成接口，不是生产LAN验收。
- 演示界面人工复核目录分类、Downloads混合媒体和4个排序入口。截图及`device-green.log`保留于上述忽略目录，不含真实媒体。辅助会话已退出。
- 未跑全量JVM/connected/Server：改动仅Android目录链路；ADB无手机，Alpha20真机/HomeLAN未验证，无真实删除或清数据。

## 交付及限制

Alpha20/code27 Release构建成功（5分钟），同Alpha19发布证书，允许原位覆盖。实际文件`deploy_handoff/current/MediaReview_2.0.0-alpha20_stage9.apk`，43,655,758字节，SHA256 `218f7b07a185161bd76a3bfb3747fc6afd524ed152ed596d878dff68fa89dff6`。CURRENT_RELEASE已指向独立Alpha20，Server包未重建，内附Alpha18不能作为手机安装入口。源码`d0ba539`已push；生产EXE哈希与原1.6.3发布值一致，实际health1.6.3/windows_service。手机未连接，未安装Alpha20、未发邮件。

目录偏好在同ViewModel内保持，未增加跨重启存储。子目录计数表示全部类型总数，不跟随当前媒体筛选；页面已标清筛选范围。旧批阅会话失效项重选、长期刷新/掉线稳定性沿用前轮待观察项，本轮不声称解决。自然数字排序和其他面板可达性可独立评估，不扩成播放器/媒体源重构。
