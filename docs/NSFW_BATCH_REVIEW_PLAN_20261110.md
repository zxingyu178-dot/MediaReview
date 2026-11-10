# 生图批量审核、提示词对比与两端统一

2026-11-10。用户已确认整页提交：默认9张，选中保留、其余淘汰，显示数量后确认；全未选需额外确认；仅处理本页已显示且加载成功的待审核图，成功后翻页，失败/冲突保持原页。

## 产品行为

1. 通用批量审核默认3×3，每页最多9张；点选只修改本地草稿，点图的放大入口独立，不因查看原图提交决定。提供全选/清空，清空不提交；返回/切换筛选前有未提交草稿提示。最后一页按实际张数确认。
2. 缩略图未加载成功的图片不进入本次提交；界面明确“X张未加载，未处理”，保留待审核，不把不可见图片自动淘汰。真正进入提交的ID、revision、KEEP/REJECT在确认时冻结，新生成图片不替换当前页。
3. 只默认审核NEW。电脑已更改图片时，不静默覆盖；整页版本校验失败返回冲突，不应用任何一项，不自动换请求号或重发。失败保留选择，刷新后由用户重新确认；无法确认结果时用原请求号查回执，禁止盲目重试。
4. 淘汰是REJECT审核状态，不是物理删除；保留现有独立清理规则，不能调用MediaReview真实媒体删除队列。
5. 提示词对比按prompt_id+version_id分组，可选6张（首选）/10张，一张界面只对应一个提示词版本。提示词全文与版本可展开，图片保留/淘汰仍各自记录。无法确定版本的图片不猜测归组，也不自动给整个提示词打分。
6. 评分复用已有提示词版本评价：对这一版本提交一条评价，不给每张图重复计分，不把本页保留比例自动当分数。保留原评分尺度、历史与维度。审核与评分分别显示回执；审核成功而评分失败时仅重试评分，不能重放图片审核。

## 数据统一与库管理

- Studio数据库和现有资产ID为共同事实源，手机缓存只作浏览与待提交草稿，不建立独立评分/分类库或复制原图。
- 新批量审核沿用Bearer设备认证、revision乐观并发控制、事务内审核事件与设备级幂等回执。手机和桌面都消费同一图片状态/评分记录；桌面写路径必须覆盖事件同步，不能仅证明手机写入可见。
- 新增整页混合操作接口及能力声明，不把现有同状态batch-status伪装成混合原子批处理。一次事务要么整页成功，要么整页失败；无静默跳过、无成功伪报。回执只允许原设备查询，同请求号不同载荷409。
- 查询按审核状态（待审核/保留/淘汰）、收藏、日期、提示词/版本与现有category组织。搜索先覆盖提示词名称/内容与图片已有可搜索字段；明确可用范围，不假装文件内容搜索。
- 分类与标签优先复用现有category/Prompt tags，不另建目录树；首批先读与筛选，编辑能力必须沿用桌面字段和事件，不在手机偷偷产生不兼容标签。
- 新图只提示刷新；双向续审继续原scope，不被新图或评分重排。查询条件、状态和分类须绑定分页游标，切换后丢弃旧请求回包。

## 实施次序与最小验证

### A：完成现有双向续审

- [ ] 审查已有3文件NSFW在研改动与5项RED记录，只跑相关gallery目标验证。
- [ ] 完成手机前页合并/序号/边界交互；3项新增VM先RED后GREEN，补最小序号与旧桥兼容断言；重型进程串行。

### B：批量提交后端合同

- [ ] 现有mobile图片路由/审核服务/幂等回执上补整页请求、查询回执和能力，不改数据库结构/技术栈/生命周期。
- [ ] 临时隔离数据验证：混合KEEP/REJECT、整页冲突回滚、重复请求不重复事件、不同载荷409、跨设备回执拒绝、未审核前置条件、未知ID/超限拒绝。
- [ ] 查明桌面审核/评分事件写入与手机增量投影覆盖；缺失则补直接相关写路径，测试两方向一致性。

### C：手机9图页与恢复

- [ ] 复用图库网格、图像加载器与原图页；新增批量页状态，接入选择/确认/全未选二次确认及失败草稿恢复。
- [ ] 回归：仅成功加载/当前页提交、未加载不淘汰、请求载荷冻结、未知结果恢复、不自动翻页/重发、切换范围旧回包不覆盖。

### D：提示词6/10对比、评分与筛选

接入调查补充：现有promptImages只返回image_id/thumbnail/status，不含审核revision或完整预览元数据，且按offset分页。对比审核改复用现有gallery keyset，在同一查询中绑定prompt_id+version_id、审核状态、现有category/收藏及必要搜索条件；空筛选保留旧scope兼容，新能力必须显式声明。Android复用GalleryQuery/Repository和BatchDraft可选pageSize（6或10），仅在EXACT版本身份下进入版本对比；不把不明版本图片混进一页。评分继续现有版本评价服务，独立于图片审核回执，可见期/返回时校验远端评分而不重置未提交草稿。先补真实V2 details的权威status字段（旧详情另一端点有状态不代表V2也有），再实现此阶段。

- [ ] 版本分组分页、评分面板复用、分别显示审核和评分结果；不额外建立图片评分体系。
- [ ] 查询状态/category/prompt绑定游标；同版本分组无跨页漏重、新图不混入、双端修改冲突可恢复。

### E：同一产物验收与交付

- [ ] 修改范围定向回归、必要编译后构建一个正式签名候选；按当时的唯一版本指针决定候选编号，不复用旧Alpha35名称覆盖不同内容。
- [ ] 手机可用时验证9图/6图/10图显示与触控、图片放大返回、桌面与手机双向变化、断网回执恢复；评分/审核只用隔离样本或用户明确授权数据。
- [ ] 部署前重新核对生成队列/日用工作流与实例身份，备份并经官方生命周期维护；本计划不直接停服、升级或改真实媒体。

## 本轮边界

### 2026-11-10 9图页源码结果

新增NsfwBatchDraft（页面选择/展示资格/请求冻结）、NsfwBatchCoordinator（保存先于POST、恢复只GET原回执、核对后确认清除）、NsfwBatchReviewScreen；复用ProfileStore DataStore，按origin+installationId隔离，不覆盖未知旧批次。已有图库进入9图页，放大复用原图页；全选/清空、数量确认、全淘汰二次确认、失败留页、成功后处理剩余分页均已接入。审核仅改状态，不物理删除。当前队列来自已加载的原图库范围，后续按需续页；服务端NEW/category/prompt过滤及6/10图分组尚未完成，不称库管理已完成。

新增选择3项最初缺类编译失败44s，补实现后3通过3m19s；新增协调3项最初缺类编译失败44s，补持久化/协调后两类共6通过3m19s。随后明确冲突不释放冻结记录的1项运行时RED（1m54s），仅REVIEW_CONFLICT+409才清未写请求，网络/未知/幂等冲突保留；合入界面曾因按钮插入if/else间编译失败38s，修正后7项通过3m42s。

只读审查2项Important均修正：原页冲突后须读取原ID权威详情更新revision/status，保留仍NEW选择，不插新图；图片须加载成功且实际进入视口（单元格可见面积至少50%）才纳入提交。仅预取未展示不可淘汰的1项先运行失败58s；修正加入交集和原页重绑回归，曾因详情DTO漏status编译失败43s，补字段（缺状态拒绝刷新）及HTTP解析断言后，共10项通过0失败/错误（Draft5、Coordinator4、HTTP详情1，2m39s）。具体命令为`:app:testDebugUnitTest --tests '*NsfwBatchDraftTest' --tests '*NsfwBatchCoordinatorTest' --tests '*NsfwRepositoryContractTest.batchRefresh*'`，日志temp/batch-page-reviewed-status-green-20261110.log。

最终又把确认弹窗改为使用打开时冻结的请求清单/数量，防弹窗期间新加载图加入本批；复用已有冻结不变回归，仅`:app:compileDebugKotlin`另轮通过（1m29s），日志temp/batch-confirmation-compile-20261110.log。10项与最终编译分轮记录，不称最后改动后重跑全绿。原有warning保留；新Draft私有data构造器copy可见性有未来Kotlin2.1告警，当前编译通过，未为告警做扩展改造。

没有全量/connected、真实媒体审核、评分、删除、生产升级或新APK。内存Journal用例验证协调器重建后的核对逻辑；DataStore接入只编译，手机磁盘重启、小屏/横屏真实可见性、触控/放大返回及部署组合均NOT TESTED。两项静态复核闭环不替代设备验收。下一步同版本对比与评分、库筛选、双端隔离联通，再统一候选发布。

### 2026-11-10 手机HTTP接入阶段结果

后端f2f3a77草稿PR39（develop目标）已只读核对CI运行42735006473成功：Backend pytest/工作流G0/资源登记校验、Frontend类型检查/Vitest/build及store回归均完成success。没有为写报告另起CI，也未将草稿转正式、合并或部署；该证据不覆盖Android或设备。

NsfwModels/NsfwStudioApi/NsfwRepository已接批量POST和原请求号GET回执，仍沿用认证API工厂和Envelope错误映射。请求限定1..10个不同图片、KEEP/REJECT及数字revision；回执校验图片/操作/计数，格式或身份不符不显示成功；409不自动重试。现有假实现默认抛不支持，不使旧桥伪装具备批量能力。

新增4项既有NsfwRepositoryContractTest：混合载荷/Idempotency-Key、只GET原回执、错图拒绝、409不重试。首次接口/DTO不存在导致test Kotlin编译失败（2m19s），不是4项运行时失败；补实现后`:app:testDebugUnitTest --tests '*NsfwRepositoryContractTest.batchReview*'`实际4通过、0失败/错误，BUILD SUCCESSFUL（2m53s）。复用登记Gradle8.9/JDK17、离线/单worker/in-process/项目temp与缓存，没有全量/设备/真实审核测试，既有warning保留。

日志在忽略temp/batch-android-contract-{red,green}-20261110.log。请求/回执接入完成不等于9图页完成；界面状态、持久化冻结批次、断网核对与同版本提示词评分仍待实现。双端源码核对另见NSFW_SYNC_AUDIT_20261110.md，评分实时刷新缺口不能隐去。

本文件是已确认主流程的执行约定，不代表功能已实现。正式仍Alpha34；Alpha35候选及现有在研改动保留。库管理先复用并贯通现有字段，不扩展为新资产中心；不停止ComfyUI，不自动删除REJECT图片。未执行的测试与手机验收必须明确保留。

### 2026-11-10 手机图库筛选合同

手机端新增 prompt/version/status/category/favorite 可选筛选，false收藏值不会丢失；要求服务端 filters 回显与请求相同，忽略条件或返回其他版本范围时拒绝响应。未筛选请求仍兼容旧桥不返回 filters；能力 mobile_gallery_filters_v1 控制带条件请求，旧桥明确提示升级。复用现有日期范围、游标及位置保存结构。

新增2项 HTTP 合同用例最初因模型字段未实现而编译失败；补实现后运行 `:app:testDebugUnitTest --tests '*NsfwRepositoryContractTest*' --offline --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process`，XML证据40项、0失败、0错误，BUILD SUCCESSFUL（10m33s）。没有全量、connected或真实审核写入。能力保护尚待ViewModel专项用例；过滤UI、同版本6/10图和评分同步仍在后续范围，不称已经完成。

真机已连接，当前安装Alpha34/code41；生图入口连接失败，电脑Studio8000/桥8877直连无监听。官方ControlHub第一次start就绪超时，载体正常清理其创建进程。构建结束后受控重试尚待结果；未升级、重配对或操作真实图片。详细一次性诊断在忽略temp中。

### 同版本对比接入顺序（执行细化）

1. 先完成分类入口与旧桥保护，保持状态/分类/收藏与日期排序同时生效；不把已有日期筛选替换掉。
2. 从有EXACT关联的图片打开同版本对比，固定prompt_id/version_id和日期排序；默认6张，可切10张。缺少可靠版本时禁止混组，并说明原因。
3. 复用图库keyset分页与批量协调器；比较浏览覆盖该版本所有状态，批量提交仍仅处理NEW且实际展示/加载成功的图片，已审核图片显示结果。评分独立于保留比例。
4. 对比页评分绑定冻结的prompt/version，不跟随主页面currentId；复用现有评分面板及未确认请求Journal。刷新远端汇总不覆盖未提交草稿。
5. 分类增量必须识别范围变化：现有feed没有category，不能直接用日期匹配虚报分类内新增数。先补可验证字段或受控查询，不把无证据的推断当事实。
6. 保持运行实例旧能力明确降级，待完整候选准备好后再统一两端版本并做隔离样本联通，不借机覆盖日用工作流。

分类筛选UI及旧桥保护：运行 `:app:testDebugUnitTest --tests '*NsfwReviewViewModelTest.galleryFilters*'`，实际XML 1项、0失败/错误，BUILD SUCCESSFUL（10m20s），同时编译状态/分类/收藏菜单。用例证明无能力旧桥拒绝筛选、不多发请求、不清空既有query/items。新菜单尚未安装到真机，当前日用桥不声明筛选能力，因此不把旧版图库恢复称为新功能验收。后端分类集合与手机增量范围匹配继续开发。

### 分类增量与对比范围基础验证

后端分类集合已经提交a4ff273并push，相关gallery文件14项通过。手机新增nullable categories，区分未知和明确空分类；query.matches组合日期/prompt/version/status/favorite/category。同步后明确不匹配的已加载图片移出，并使全局位置计数待校验；新增通知只计确认匹配项，缺分类时提示刷新，不把未知冒称匹配。同版本对比范围模型支持6/10张、要求EXACT关联、固定query并拒绝混版本页；此阶段只有范围模型，尚无完整对比界面/评分入口。

新增用例首次缺字段/模型造成单测编译失败（2m55s），不是运行时断言失败。补实现后运行 `:app:testDebugUnitTest --tests '*NsfwGalleryQueryTest*' --tests '*NsfwReviewViewModelTest.galleryFiltersSync*' --tests '*NsfwPromptComparisonTest*'`，BUILD SUCCESSFUL（8m49s）；对应XML共9项、0失败/错误。覆盖5项日期与成员范围、1项同步移出/新增计数、3项对比范围。没有全量、设备、真实审核/评分测试，没有部署。旧桥范围更新、断网和UI仍需组合验收；仅有此模型不代表6/10图功能已经交付。

### 同版本6/10图对比与评分入口

新增独立同版本对比页，从精确关联图片的信息面板进入；固定原日期/排序与prompt/version，读取当前6/10张，原图复用既有Dialog。前后keyset按需请求，失败保留原页；新图不插入旧scope。已审核图片可比较；“审核本页待审核图”复用原BatchDraft/Coordinator，仅NEW且加载/展示合格者进入冻结批次，评分独立复用原面板、评分服务和Journal。openComparisonScore绑定被冻结版本，不依赖主图库currentId。

首次评分绑定用例因缺openComparisonScore单测编译失败（2m10s）。补实现及屏幕后运行 `:app:testDebugUnitTest --tests '*NsfwReviewViewModelTest.comparisonScore*' --tests '*NsfwPromptComparisonTest*' --tests '*NsfwBatchDraftTest*'`，实际9项通过（1+3+5）、0失败/错误，BUILD SUCCESSFUL（9m33s），日志temp/comparison-screen-green-20261110.log。

定向只读审查发现2项Important：张数切换失败时scope先变而旧page保留；批量成功onRefresh空回调使主图库滞后。已改为requestedSize与成功scope分离，验证成功才提交scope/page；onChanged接vm.refresh。复核确认静态闭环、无新增Important。最终仅补 `:app:compileDebugKotlin` 通过（3m19s，temp/comparison-review-compile-20261110.log），未声称最终修改后重跑9项，也未做全量/connected。原有warning及未来Kotlin2.1私有data copy告警保留。

未安装APK或升级桥，真机布局、失败/离线交互、DataStore重启与两端组合仍待验收。搜索/Prompt tags筛选、评分可见期远端校验仍未完成；不能把这个页面提交等同整体目标完成。
