# 图片与视频加载专项结果（开发中）

源码目标：Alpha18/code25 + Server1.6.2/Contract2/0017，包含上一轮目录漏项修复。用户确认雪碧图保持删除。UI、GSY播放器、批阅/删除流程不变，无数据库迁移，不操作真实媒体，不部署生产。

## 改动

- 共享Coil入口统一同资源/版本/档位的内存和磁盘键；服务及配对凭据纳入不可逆命名空间。未配对不读图片缓存，请求发出前和返回后复验会话；原图校验策略不变。
- 可见区域立即进入预取，滚动取消旧窗口，最多16项/并发2。查看器继续用Small及只读Large图层，移除在Coil拦截器前读取的无认证URL占位键。
- Server缓存读写移出事件循环；原子写完成才释放同键锁，连续取消亦受保护。
- 设备凭据成功后并行读取PlaybackInfo和续播，保留HLS源ID、续播及凭据失败闭合。补凭据/媒体信息耗时。将虚假的“进度变化=首帧”日志更名`playback_position_started`。

主要文件：Android `core/network/ThumbnailCacheInterceptor.kt`、`TokenProvider.kt`、`CacheAuthInterceptor.kt`、`di/AppModule.kt`、`home/WindowLoading.kt`、`viewer/ZoomableImage.kt`、`player/native/GsyNativePlayerScreen.kt`；Server `services/thumbnail_cache.py`、`api/v1/media.py`。版本与更新说明及直接相关测试同步调整；完整清单见Git diff。

## 验证记录

| 组 | 结果 |
| --- | --- |
| Server播放合同及两项缓存回归 | 13项通过，0失败/跳过，491.958秒；`review_meta/ops-20261027/loading-server.xml`。 |
| 复核后缓存线程/取消/合并/原子写 | 5项通过，0失败/跳过，1.515秒；`loading-cache-final.xml`。包含双重取消RED→GREEN，与上一组有重叠，不能合计声称18项独立测试。 |
| Android定向JVM | 首轮58项，57通过/1旧Alpha17版本硬编码失败；同步Alpha18后受影响类20项全部通过。已分轮闭环，不是单轮58全绿。XML分别保存于`loading-jvm-first-results`、`loading-jvm-rerun-results`。 |
| 隔离模拟器图片HTTP链 | 两项通过，4.099秒；`loading-emulator-tests.txt`，Alpha18 Debug、API35独立数据盘、MockWebServer合成PNG。实际Coil冷请求304ms、内存4ms、磁盘命中无新请求；更换凭据和清除配对不复用旧图。旧响应body延迟3000ms，滚动新窗口23ms收到headers。 |
| 定向静态检查 | `ruff check app/api/v1/media.py app/services/thumbnail_cache.py tests/test_loading_pipeline_8a1.py tests/test_playback_contract_11.py --select E,F,I`通过。 |

Server命令：`python -m pytest tests/test_playback_contract_11.py tests/test_loading_pipeline_8a1.py::test_thumbnail_disk_io_does_not_run_on_event_loop tests/test_loading_pipeline_8a1.py::test_thumbnail_cancelled_writer_keeps_single_flight_until_atomic_write --junitxml=../review_meta/ops-20261027/loading-server.xml`；取消复核后仅跑`test_loading_pipeline_8a1.py -k 'disk_io or cancelled_writer or single_flight or write_is_atomic'`。此前3个图片相关文件的旧用例通过；新增2项曾因非法测试缓存键失败，夹具更正后已在以上组验证，不隐瞒失败。

Android使用已装Gradle8.9/JDK17、项目既有`review_meta/stage9/gradle-home`及可写TEMP，参数`--offline --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process'`。`:app:testDebugUnitTest`首组过滤`*core.network.*`、`*WindowCachePolicyTest`、`*Stage8IImagePolicyTest`、`*PairingRepositoryLifecycleTest`、`*ReleaseNotesContractTest`；后续只复跑受影响类。首次wrapper/Kotlin daemon目录拒写，改用已装发行版和进程内编译；一次PowerShell带点参数传递错误，测试未执行，修正引用继续。未改系统权限或安装第二套工具。

## 受控前后对照

一次性脚本仅使用合成数据，基线实现来自`48cbbb9`；原始结果为`review_meta/ops-20261027/loading-benchmark.json`。

| 场景 | 优化前 | 优化后 |
| --- | --- | --- |
| 每次磁盘操作模拟80ms，4次MISS+4次HIT：事件循环最大延迟 | 464.87ms | 3.19ms |
| 实际播放信息函数，两个独立上游各模拟200ms，3次中位数 | 403.03ms | 201.47ms |

随后仅加固重复取消，不改变该正常路径，未为报告重跑。不能外推为真实媒体、手机、Wi-Fi或视频首帧提速百分比。

## 剩余限制与交付状态

JVM已分轮闭环，两项模拟器HTTP图片检查通过。实际数据源为NETWORK→MEMORY_CACHE→DISK；首次及换账号共2次成功PNG，合计252字节（不含HTTP头）；清除配对后的第3次请求返回401。未测UI首图P95、视频首帧或取消下载的字节分布，不把单次小图数字当真实媒体基准。证据为`loading-emulator-evidence.txt`；测试APK用既有Debug key的临时副本解决默认锁目录拒写，Release使用原正式签名。模拟器已按隔离数据盘身份关闭，正式产物构建待完成。

GSY13.2.1本机字节码确认`ExoSourceManager.buildCacheKey`使用默认URL键，没有认证身份；本轮不直接打开全局视频缓存。安全共享下一条内容仍需验证数据源/缓存身份/播放器接管，按批准方案保留下一步，不重写播放器，不做独立文件头下载。`IjkExo2MediaPlayer.onRenderedFirstFrame`为空实现、内部Player受保护，保留可靠首帧指标与真实画面验收限制，不用时间轴前进冒充出帧。

不跑全量Server/JVM/178 connected，不全库FFmpeg，不重复制作大证据包。真实手机/HomeLAN未验证，生产未升级。最终产物与Git/push实况待本轮实际完成后记录。
