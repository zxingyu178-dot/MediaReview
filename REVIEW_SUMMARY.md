# MediaReview Stage 8G 审查摘要

阶段：Search / Browse State / Context Closure。基线ba19d01aba692f0ed010c625f801738ba306bae4；分支feature/mediareview-v2-stage8g-search-context。

## 实际改动

独立SearchUiState和SearchPagingController经repository.mediaPage提供50/page、300ms防抖与代次隔离；首页列表不参与搜索。空词只历史/提示，近尾部16项追加、ID去重，更多失败保留成功窗口并显式重试。首页和搜索独立保存GridState/对应关键词；关闭不重载首页，详情返回不回顶。

搜索与收藏点击建立已加载同类型冻结队列，视频上下条与图片滑动使用正确来源窗口，取消收藏保持当前位置与队列。首页以外图片收藏采用服务器确认后的可观察元数据，切源失效与晚到代次门控。删除FolderScreen和无入口路由/参数。没有修改Player/Viewer手势核心、Review Session、Organize、Duplicate、Delete Commit、Server API/数据库或部署桥。

Android code17/2.0.0-alpha10与四条中文说明；首次仅当前、升级聚合、关闭持久化由现有机制验证。Server1.2.2/Contract2不升版，没有新增服务或长期脚本。ControlHub仍BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT。

## 已执行验证

- 相关搜索9 JVM有效RED/GREEN、Context/版本有效RED/GREEN；相关33通过。独立初审I1/I2及测试T1-T3均修复，最终源码审查C0/I0。
- 实际MainActivity/生产Repository/HTTP的1000媒体/220搜索fixture；I1 Back后v71不可见、旧源晚到确认回填状态为有效RED；I2认证DELETE后旧图标为有效RED。正常Got it确认后I2完整1/1通过17.117s，未伪造系统确认。失败和环境前提日志均保留。
- 最终JVM410/57suite，0失败/错误/跳过；lint0错误/40警告/8信息，Debug/AndroidTest同轮BUILD SUCCESSFUL 6m21s。仅既有AGP/SDK/弃用警告，未升级工具环境。
- connected全82首轮80PASS/2FAIL，0错误/跳过，XML241.92s，Gradle5m19s。搜索返回/图片原位收藏/切源晚到已通过。旧BrowserFake mediaPage忽略搜索词、视频切换标题先于按钮就绪两项测试前提已修；增量lint/AndroidTest构建1m44s成功，报告0错误/40警告/8信息，原失败两项补验2/2通过33.081s（Gradle2m9s）。静态补审通过，不称单轮82全绿。

精确300ms/旧query隔离由虚拟时间JVM证明；真实App输入操作可能超过300ms，只验证最终abc。首页非默认folder/sort由ViewModel测试覆盖；App用不同home20/search70锚点与bounds验证独立恢复。semantics按钮调用只证明状态连接，不等同实际触摸命中验证。

- 唯一assembleRelease成功3m5s；用户APK 43520050B，SHA256 c4afe13228d3ee37d343288649ce88abb7780e174af7e9df515f541f151ec53f，code17/alpha10、不可调试、正式签名验证通过；DEX含新搜索/上下文/确认状态且无FolderScreen，169个冻结产品输入未漂移。没有第二次Release构建或重签产品；Release UI未另跑，交给用户亲自验收。

## 风险与边界

1. 真实手机与家庭LAN产品行为未验收；已只读核实连接手机为Alpha4/code11 Debug及证书，未安装、卸载或清用户数据。正式签名兼容性须以最终APK实际证书核对。
2. 收藏全量API与N+1为P1 PERFORMANCE DEBT，无性能退化证据不预先优化；当前连续浏览仅点击时已加载窗口，非全库导航。
3. APK已完成，Git/交接打包按最终HEAD与远端一致性执行，真实结果见Handoff。确认收藏映射对未来多入口/外部状态更新存在维护边界，本阶段无据此扩大修改。

生产服务、用户媒体、手机数据及官方升级桥均未操作；最高CODE_READY / READY_FOR_USER_VALIDATION。Stage8G交接后停止，不自动进入8H。

阶段结论：合格（CODE_READY / READY_FOR_USER_VALIDATION；手机安装审批与真实手机/家庭LAN验收待完成）。
