# 手机五项反馈及测试内容清理（Alpha21）

## 修改与根因

| 反馈 | 已确认原因与改动 |
| --- | --- |
| 主页仍有照片 | Alpha20仅改目录逻辑，主页请求仍为ALL。本轮主页首屏、缓存键及后续分页统一VIDEO；首级标签改“视频”，照片仍从相册进入，独立目录浏览保留类型筛选。没有移动真实照片。 |
| 经常断开 | 一次业务IOException即Offline，Guard随后禁止请求且原先缺自动恢复。新增2/5/15秒有界健康探测，认证/不兼容不重试，不清配对；前台恢复或网络重新可用可开启新一组重试。健康探测串行化并丢弃旧配置结果。另查实ControlHub offline、Windows服务Stopped，而残留1.6.3实例为interactive；用户授权后核对路径/启动时间/哈希/端口，精确关闭残留，再由ControlHub启动成功，实际health为1.6.3/windows_service、服务Running。最初启动来源未确证，不称所有断线根除。 |
| 拖动进度回跳 | 松手先清preview，异步内核尚未更新，界面会读旧位置。新增SeekPositionLatch，保留已提交目标直至位置确认；切源/取消/后台清理；5秒仍未定位则明确提示并恢复真实位置，不永久虚报成功。 |
| 画面横滑不能调进度 | Alpha19按前次用户选择取消了横向seek；本轮按最新要求恢复专用水平拖动识别，复用原SeekDragEngine/PlayerSeekMapping；垂直仍由VerticalPager换片，单击暂停，未恢复滑动决策/双击跳进度/长按倍速。 |
| 文件夹批阅/多余按钮 | Checkbox原本onCheckedChange=null，只能点大卡片；面板默认折叠，底部操作区可能不可达。改为直接勾选、行可选、完整展开与有界滚动；保留原范围/替换确认协议。移除上一项/下一项，保留保留/待删除/撤销。 |

改动位于V2HomeViewModel/HomeScreen、ServerRecovery、V2ServerStatus、V2MainScreen、ReviewScopeSheet、V2ReviewScreen、SeekPositionLatch、数据源设置/模式及版本说明。Server业务代码、NSFW工程和真实媒体未改。

## 验证

- 相关JVM首轮58通过：V2ReviewViewModelTest40、V2ServerAccessGuardTest9、SeekEngineTest4、ServerRecoveryTest3、SeekPositionLatchTest2。后补网络/前台唤醒场景，ServerRecoveryTest定向4项通过，包含重试耗尽后新网络恢复；两轮存在重叠，不混称同轮59全绿。
- 版本说明ReleaseNotesContractTest另16通过。对应Gradle采用登记8.9/JDK17、项目GRADLE_USER_HOME/TEMP、offline、max-workers=2、Kotlin in-process；未改环境或处理无关warning。
- 模拟器4项在用户要求停止前已通过（56.564秒）：真实HTTP长MP4画面左右seek且暂停保持、真实进度条拖动、上下换片/横向不换片且不写待删除、文件夹范围选择与确认及恢复真实HTTP请求。随后立即关闭MediaReview_Test；按用户要求后续只用已连接手机，不再启动模拟器。
- 首次新增SeekPositionLatch用例因缺类型编译失败，不称旧UI运行时RED；中途新增Job类型遗漏导致一次编译失败，修正后58项通过。日志在忽略目录review_meta/five-20261028。
- 原服务恢复后5次本机健康请求成功，先前interactive下5次也成功（最慢104ms），短探针不足以证明家庭Wi-Fi长期稳定。手机日志抽样未抓到明确Socket异常，不猜测具体网络故障。
- 未跑全量Server/JVM/connected；无Server业务变更，重用既有夹具，仅覆盖改动链路。没有用真实媒体测试删除。

## 演示素材与清理

用户明确要求删除电脑旧测试内容及手机内置演示图片/视频。26项raw素材共22,948,662字节及demo_media资产移入debug专用源集，正式Release不打包，正式版数据源强制SERVER并移除演示入口，保留既有服务器配置与配对。测试源码/开发夹具保留，不混入正式APK；正式包还需Zip内容检查及手机检查。

已按固定路径核验并清理两个旧pytest目录及旧Debug/AndroidTest APK目录，共72,855,807字节（约73MB）。最初动态批量清理命令被自动检查拒绝，未执行；随后读取固定路径及重解析点清单后，逐个原生PowerShell删除成功。当前发布包、必要证据、用户数据及备份保留。模拟器已停止；构建结束后停止本项目Gradle守护进程以释放运行内存。

## 交付进度

Alpha21/code28已完成签名构建、同Alpha20证书校验和手机install -r覆盖安装，dumpsys确认28/2.0.0-alpha21。源码8d18d80已push。正式APK为`deploy_handoff/current/MediaReview_2.0.0-alpha21_stage9.apk`，20,649,784字节，SHA256 `47ea49bf65fdcf56bfd8ba33a6df780c91d635ab32bedc34db30b4c232717531`；CURRENT_RELEASE已切换，Zip检查演示资源0项。原Alpha20为43,655,758字节，本次减少23,005,974字节。Server保持1.6.3，未重建EXE；原Server ZIP仍附Alpha18，不能用其回装手机。

手机+实际服务抽样：主页“视频/相册”标签及视频时长标记可见；相册4个照片数量标签，设置显示服务器在线且无演示选项。批阅第61项进度前进，暂停位置两次均0.7580845；进度条到70%后连续8次均0.7，页码不变。画面左滑0.7→0.6079282，右滑→0.7005729，页码不变，单击恢复0.7005729→0.7043634；旧上下项按钮不存在。上下滑页码61→62→61；第62项短采样位置0/0，未作为第二项出帧通过，也未遍历全部3987项。文件夹勾选false→true、开始按钮在屏内可达，本次取消而未替换原会话；创建/确认链路由前述合成HTTP设备用例覆盖。切后台返回前台正常，未强制切断真实Wi-Fi；长期稳定性仍待观察。进度条为无障碍读数采样，不能证明每帧或解码缓冲零延迟。

脱敏证据`review_meta/five-20261028/alpha21-phone.json`及签名证明；最终补丁构建9分36秒，补充4项恢复测试通过。原来的58项、另16项版本测试与后补4项有重复范围，不合并称单轮全绿。用户要求后没有再启动模拟器。模拟器、uiautomator会话、本项目Gradle守护进程已停止，ADB forward为空；手机已回到视频页供用户继续使用，未改配对/设置或真实媒体。

用户本轮授予MediaReview开发期间必要操作权限，不再对例行修复/受管服务恢复重复询问；仍核对对象、保留恢复路径并经ControlHub管理生命周期，Windows UAC可能需本人允许。此授权不作为擅自修改NSFW或使用真实媒体测试删除的依据。
