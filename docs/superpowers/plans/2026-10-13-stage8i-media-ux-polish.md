# Stage8I Media UX Polish & Review Reliability 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. 每项先RED后GREEN，独立审查，再推进；根Agent统一构建和设备资源。

**Goal:** 完成STAGE8I_MEDIA_UX_SPEC.md全部35节与8类真机反馈，交付Alpha12、Server1.4.0和十二文档Handoff。
**Architecture:** 扩展现有FastAPI/SQLite/GSY/Compose/Coil；缩略图只使用固定白名单profile，原图相邻预取仅源缓存并限制解码；批阅沿用既有Session和FolderTree，采用原生Pager与循环内核；删除仍保持服务端两阶段协议。
**Tech Stack:** Python3.12/FastAPI/SQLAlchemy；Kotlin/Compose/Coil/GSY Exo2。

## Global Constraints
- 基线4ce9a878a2f300cfbcd0bc8e96dfebadc42367b7；分支feature/mediareview-v2-stage8i-ux-polish。
- Server1.4.0 / API Contract2；新capabilities thumbnail_profiles_v1、review_folder_filter_v1、delete_receipt_v1，保留folder_tree_v1。
- Android versionCode19 / 2.0.0-alpha12；正式Release仅生成一次，先完成所有源码和门禁。
- 禁止客户端任意width/quality制造无限变体；GRID_SMALL256×144/q60、GRID_LARGE480×270/q65、ALBUM320×427/q65。
- 列表与相册不访问original；Viewer仅当前和相邻原图源，N±2低清；原图并发有界，当前优先，远端预取取消，禁止并发解码超大原图。
- 不部署家庭生产Server、不修改真实Jellyfin配置、不永久删除真实媒体、不清手机数据、不绕过ControlHub；升级桥BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT。
- 只在当前项目工作；保留MediaRepository/Session/SQLite/GSY体系；不新增无关模块。使用合成fixture与项目TEMP隔离临时文件。
- 最高CODE_READY / READY_FOR_USER_VALIDATION；真实家庭LAN未执行时不得写REAL_LAN_PASS/PRODUCTION_READY。

## 设计决策与执行顺序
使用现有thumbnail接口增加profile查询参数，默认GRID_LARGE兼容Alpha11；variant包含profile名/尺寸/quality/fill，未知profile拒绝。客户端统一URL构造以保留v与认证，不改原图下载协议。选定current原图请求优先执行，相邻使用Coil源数据预取/受控decode尺寸，不在UI线程网络。Folder与Review Scope共享FolderTree数据；旧server按具体capability分别降级，不封锁图片/视频基本功能。用户明确授权普通技术选择直接实施，省略额外设计审批。

### Task 1: Server Thumbnail Profile合同（§2/27部分）
**Files:** server/app/services/thumbnail_cache.py；server/app/api/v1/media.py；server/app/adapters/jellyfin/client.py；server/tests/test_thumbnail_profiles_8i.py；既有image/loading tests。
**Interfaces:** /api/v1/media/{id}/thumbnail?profile=GRID_SMALL|GRID_LARGE|ALBUM；default GRID_LARGE；完整variant参与ETag/cache。Jellyfin thumbnail_image新增可选quality，保持旧调用默认行为。
- [ ] RED：认证真实HTTP调用三profile验证尺寸/quality/fill；默认兼容；未知/任意尺寸quality拒绝或不生效且无缓存变体；sameprofile HIT不访问上游，crossprofile互不污染，源指纹改变失效，304遵循profile。
```python
@pytest.mark.parametrize('profile,expected',[('GRID_SMALL',(256,144,60)),('GRID_LARGE',(480,270,65)),('ALBUM',(320,427,65))])
def test_profile_contract(profile,expected):
    # 使用项目真实TestClient+隔离DB/Cache，拦截Jellyfin上游尺寸而非替换路由。
    response = client.get(f'/api/v1/media/p1/thumbnail?profile={profile}',headers=auth)
    assert response.status_code == 200
    assert upstream.requested_variant == expected
```
- [ ] 在server目录运行 .venv/Scripts/python.exe -m pytest tests/test_thumbnail_profiles_8i.py，记录正确RED原因。
- [ ] 增加不可变白名单profile描述与variant；HTTP路由只查表，将完整参数传上游；保持single-flight/prune/认证。
- [ ] 同上新测试及tests/test_media_image_proxy_11.py、test_loading_pipeline_8a1.py、test_loading_closure_8a11.py、test_jellyfin_client.py全部GREEN，ruff相关文件PASS。
- [ ] 写DEV_LOG、提交feat(server): add bounded thumbnail profiles；独立规格/质量审查无C/I后登记。

### Task 2: Android图片档位与Viewer低清/原图邻接（§2–4/24–25/31IMAGE/32图片）
**Files:** core/network/MediaReviewApi.kt、feature/v2/data/server/V2ServerMediaRepository.kt及V2MediaMapper.kt/ResourceCache；home/MediaCard.kt/MediaGrid.kt/AlbumScreen.kt/ShelfGrid.kt；viewer/V2ImageViewer.kt/ZoomableImage.kt；相关JVM和androidTest。
**Interfaces:** 统一profile URL函数保留原v与base/header；低清URL不取original；Viewercurrent优先、邻±1源预取、±2无original，取消远端job；记录preview_visible/current_original_ready/neighbor_prefetch_ready。
- [ ] RED：MockWebServer真实HTTP100张列表original=0；三布局profile请求正确，当前N原图先于相邻，邻±1预取、±2无original、快滑远邻取消/并发上限。
- [ ] 实现固定aspectratio低清优先、无中央遮挡Spinner、无内容比例Skeleton、有界source缓存预取与当前单解码；保留zoom和Viewer上下文。
- [ ] JVM目标测试+Debug编译GREEN，真实Coil像素/HTTP验收交根统一设备轮次；记录冷热payload/time与Alpha11同fixture基线。
- [ ] 独立审查、DEV_LOG和feat(android): optimize image previews and adjacent originals提交。

### Task 3: PendingDelete与bounded Seek（§5/13–14/26/31相关）
**Files:** viewer/state/ViewerPendingDeleteController.kt泛化PendingDeleteController；viewer/V2ImageViewer.kt；player/native/ui/GsyNativeTopBar.kt/GsyNativePlayerScreen.kt；player/native/state/SeekEngine.kt/SeekGesturePreview.kt；V2MainScreen及相关测试。
**Interfaces:** Viewer/Full共用server-confirmed load/add/undo，Snackbar action撤销；横滑range=(duration*.02).coerceIn(30000,180000)，bar绝对定位，double±10000不变。
```kotlin
fun horizontalDelta(dx:Float,width:Float,durationMs:Long):Long =
    ((dx / width) * (durationMs * 0.02).coerceIn(30_000.0,180_000.0)).toLong()
```
- [ ] RED：5m/30m/2h/6h范围、一次seek与bar/double不变；待删除初始加载/add/undo成功server后更新、失败不假切UI、切源晚到不污染、撤销按钮真实HTTP。
- [ ] 实现Full顶部待删除及轻量偏移/起终时间提示；不直接删除文件，Viewer兼容。
- [ ] JVM/编译GREEN、真实player和Viewer HTTP操作轮次；审查/DEV_LOG/feat(android): share pending deletion and adaptive seek提交。

### Task 4: Jellyfin Size与DeleteReceipt Server（§15–17/31相关/27部分）
**Files:** server/app/adapters/jellyfin/models.py/mapper.py；services/media_index.py/delete_queue.py；api/v1/delete_queue.py；tests/test_jellyfin_mapper.py/test_delete_nonce_11.py及新test_delete_receipt_8i.py。
**Interfaces:** JFMediaSource id,size,run_time_ticks,container,path内部；有效顶Size>主MediaSource.Size>可信local sync stat；现有pending缺size在load/refresh索引回填。response保留outcome，新增deleted_count/deleted_bytes/missing_count/failed_count。
- [ ] RED：topSize缺失MediaSources4GB→index/queue4GB，refresh更新原index/pending，队列读取不N+1stat；真实tempfileremove后不存在且精确bytes，missing/failed不计bytes、失败项保留。
- [ ] 实现sync-only安全stat及回填；只计os.remove成功且后验不存在的文件，保留nonce两阶段验证/身份/白名单安全逻辑。
- [ ] 定向pytest+ruff GREEN，独立审查/DEV_LOG/feat(server): report verified disk deletion and media sizes提交。

### Task 5: Runtime Diagnosis与FolderBrowser（§6–8/28Folder/31FOLDER）
**Files:** feature/v2/data/server/V2ServerStatus.kt/V2ServerStatusStore.kt；home/HomeScreen.kt/FolderBrowserScreen.kt/FolderPagingController.kt/FolderChipRow.kt/V2HomeViewModel.kt；folder合同JVM/androidTest。
**Interfaces:** UI显示version/contract/capabilities，缺folder_tree_v1显示电脑端版本过旧/需要Server1.3.0+；子目录卡片cover/name/recursivecount/childcount；defaultinclude_descendants=true可切当前。
- [ ] RED：新fixture根→child→child→media实际folder_id=ft_xxx、breadcrumb/back；parentonlychildren不为空、卡片可点；旧cap提示且play/image/Review仍可用。
- [ ] 接通同一FolderTree/分页scope与封面，不新folder系统；保留anchor与SWR。
- [ ] JVM/DebugGREEN，统一device真实HTTP指针验收；审查/DEV_LOG/fix(android): restore folder browsing and runtime diagnosis提交。

### Task 6: Review Folder Scope Server与Android（§19–23/27–28/31SCOPE）
**Files:** server/app/api/v1/review.py/services/review.py/services/folder_tree.py；feature/v2/review/data/ReviewSessionModels.kt/V2ServerReviewSessionRepository.kt/V2ReviewSessionRepository.kt；V2ReviewViewModel.kt/V2ReviewUiState.kt/V2ReviewScreen.kt；相应tests。
**Interfaces:** folder_ids List<String><=100/include_descendants bool=true；验证selectedlibraries node，OR scope，创建Session固定快照；UI全部/多选Sheet，oldserverfailclosed，active先确认不覆盖，title不显示真实path。
- [ ] RED：one/descendants/multiOR/illegal/foreignlibrary/over100；已有session冻结；旧cap禁folder/提示升级；active继续恢复，reselect需明确确认，cancel不覆盖。
- [ ] 复用现有scope_condition和FolderTree；客户端会话API字段向后兼容；已有session不因新选择重新过滤。
- [ ] 定向Server/JVM/编译GREEN，Scope Sheet真实HTTPdevice验收；独立审查/DEV_LOG/feat(review): add frozen folder scope提交。

### Task 7: Review Loop与原生Pager预取（§9–12/24–26/31LOOP/PAGER/32）
**Files:** feature/v2/review/V2ReviewScreen.kt/V2ReviewViewModel.kt/ReviewStableGate.kt；player/gsy controller/native相关；shared gesture layer；androidTest真实短MP4/HLS fixture及JVM。
**Interfaces:** currentReview内核loop=true，Full不loop；EOFseek恢复，原生VerticalPager drag/fling/snap至多1，纵向交Pager横向确认后consume，长按独占；stable后邻±1endpoint/poster不二player。
- [ ] RED：真实5–8sMP4与HLS播放>1.5duration，positionwrap/画面/当前media/noTerminalCompleted；EOFrace seek重播；5次真实纵滑每gesture恰好1/nooldaudio/wrongsource，横seek不翻页。
- [ ] 删除Channel<Float>手动pagerState.scroll路径，用原生PagerSnapDistance.atMost(1)或当前等价；保持当前优先和旧ownedkernel释放隔离。
- [ ] JVM/编译GREEN，真实设备loop/pager/source/seek回归；记录settle_to_first_frame及Alpha11对比；审查/DEV_LOG/fix(review): loop playback and use native pager gestures提交。

### Task 8: Delete Result UI与整体反馈（§18/24–25/31receiptUI）
**Files:** core/model/DeleteQueueModels.kt及实际现有delete DTO；feature/v2/organize/OrganizePage.kt/OrganizeUiState.kt/OrganizeViewModel.kt；相关servermapperUItests。
**Interfaces:** 新receipt数据显示真实从盘删除count/释放bytes/missing/failed及failureitems，刷新success项消失，旧response不伪造释放空间；固定比例/pressed反馈，120–180ms仅短动画，haptic限2xlock/unlock和危险确认。
- [ ] RED：新/旧response准确展示、failed清楚列出、刷新queue成功项消失；已有内容不整页Spinner、anchor恢复。
- [ ] 最小UI/状态接通真实receipt，避免全局多crossfade；审查/DEV_LOG/fix(android): show disk deletion receipts and preserve content提交。

### Task 9: 版本/性能/最终门禁与正式交付（§1/27–35）
**Files:** android/app/build.gradle.kts、releasenotes/ReleaseNotesCatalog.kt；server/app/__init__.py/api/v1/system.py/server/pyproject.toml；scripts/build_deploy.py/deployment/version.ps1；根发布指南/docs/DEV_LOG/TASKS/REVIEW_SUMMARY与stage8i tests。
- [ ] RED正式19alpha12/Server1.4/Contract2/能力及7条真实中文notes/未读首次关闭；修正builderstage8i输出合同。
- [ ] 同fixtureAlpha11→12性能记录payload冷/热首屏、Viewer三个event、Reviewsettle/frame/连续5次，数据不虚设SLA。
- [ ] fullpytest/ruff、全JVM/lint无新增错误、fullconnected、真实Full/Reviewregression完整终态PASS；对35节要求逐项证据审计；全分支独立审查无C/I。
- [ ] 仅一次正式assembleRelease与签名/version/hash；freshEXE隔离health及ZIP所有payloadhash，生成MediaReview_2.0.0-alpha12_stage8i.apk和MediaReview_Server_1.4.0_stage8i.zip；不部署生产。
- [ ] commit/push/remote/clean/冻结输入来源核验；按既有builder生成MediaReview_Stage8I_Handoff_<date>_<sha>.zip，十二文档00_HANDOFF/01_ACCEPTANCE/02_TEST_REPORT/03_IMAGE_PIPELINE/04_PLAYER_DELETE_SEEK/05_REVIEW_LOOP_PAGER_SCOPE/06_FOLDER_BROWSER/07_DELETE_RECEIPT/08_PERFORMANCE/09_KNOWN_ISSUES/10_USER_VALIDATION/11_RELEASE_NOTES及证据源码Git/SHA；无秘密/真实media/二进制，正式SHA/成员/bytes回验。
- [ ] 最高CODE_READY / READY_FOR_USER_VALIDATION，完整audit通过后目标完成并停止。
