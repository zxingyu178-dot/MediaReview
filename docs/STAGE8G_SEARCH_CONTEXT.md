# Stage 8G：搜索、浏览状态与上下文闭合

用户提供的 Stage 8G A–S 是本阶段已确认设计。基线 ba19d01；分支 feature/mediareview-v2-stage8g-search-context。

搜索采用独立 SearchUiState / SearchPagingController，完全不写首页 currentList。关键词输入立即切换代次并取消旧请求，300ms 后通过 repository.mediaPage 请求第一页，每页50条。空关键词不联网；有历史只显示最近搜索，无历史只显示输入提示。接近已加载窗口末尾16项加载下一页，按 mediaId 去重；追加失败保留窗口并显式重试。

首页和搜索各持一个 LazyGridState，状态提升至 HomeScreen，避免条件分支移除网格导致位置丢失。关闭搜索只失效搜索请求，不重新请求首页；原首页筛选、排序、页数和滚动位置保持。

搜索和收藏通过专用入口以点击时已加载窗口构建不可变队列，按点击媒体类型过滤。取消收藏仅改变列表与媒体状态，不修改正在浏览的队列。保留现有 Player / Viewer 内核、Review Session、Organize、Duplicate、Delete Commit 与部署桥。

独立 FolderScreen 没有真实入口，采用方案A删除死路由、参数和组件，继续使用现有首页文件夹过滤与书架相册路径。

测试fixture提供1000媒体、220搜索匹配，记录分页请求；验证首屏只有50、尾部才续页、快速输入只有最终结果生效、首页保存与收藏上下条。Favorites全量API暂记 P1 PERFORMANCE DEBT；Server仍1.2.2 / Contract2，ControlHub仍BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT。

Android更新至code17 / 2.0.0-alpha10，四条用户更新说明；首次安装只显示当前、升级聚合未读、关闭后不再自动弹沿用并验证现有机制。开发期只相关JVM/compile/目标instrumentation，最后统一testDebugUnitTest/lintDebug/connectedDebugAndroidTest/assembleRelease。通过后只输出一次 MediaReview_2.0.0-alpha10_stage8g.apk。真实手机与家庭LAN未验证，最高CODE_READY / READY_FOR_USER_VALIDATION，交接后停止，不进入8H。

## 独立审查后的状态恢复修复

搜索GridState及它所对应的已定位关键词共同保存在HomeScreen的saveable状态中；只有关键词真实改变才回顶，详情Back重新组合面板不会再次清零。首页与搜索恢复测试采用不同锚点并比较可见节点位置，避免误共享状态导致假绿。

首页以外图片的收藏状态由ViewModel提供服务器确认后的可观察映射，Viewer仅覆盖当前媒体的收藏元数据，冻结队列不变。切源时清空映射，收藏请求捕获现有数据源代次；晚到旧请求不能回填或发出属于旧源的失败提示。原位双向收藏与切源晚到各有独立验证。
