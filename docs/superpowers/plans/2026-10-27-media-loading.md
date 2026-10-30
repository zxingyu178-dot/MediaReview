# 图片与视频加载专项执行清单

> 执行方式：当前已授权项目工作区，串行按任务执行；用户已批准专项设计。复用现有工具与测试，不创建平行工程。

**Goal:** 修复图片缓存/调度浪费，缩短视频地址串行等待，用可重复的隔离场景验证收益。

**Architecture:** 保留Coil、GSY/Media3、现有Server与API合同；缓存统一在图片加载入口处理，后台I/O使用既有线程工具，播放信息独立读取并行但仍在鉴权成功后执行。

**Tech Stack:** Kotlin/Compose/Coil2、GSY13.2.1/Media3 1.10.1、Python/FastAPI/asyncio、pytest。

## 全局限制

- 雪碧图保持删除，UI/批阅/删除流程不变；不部署生产，不使用真实媒体。
- JVM、pytest、Gradle、模拟器及打包串行；仅改动相关用例。
- 认证隔离、版本失效、取消传播、错误语义不弱化。

## 任务1：Server缩略图文件I/O

文件：`server/app/services/thumbnail_cache.py`、`server/tests/test_loading_pipeline_8a1.py`。

- [x] RED：已有缓存服务中用线程ID断言read/write均不在事件循环线程，覆盖MISS和HIT；复用真实临时目录与同键并发测试。
- [x] GREEN：`await asyncio.to_thread(self.read, key)`；写入也offload，取消时锁保持到写入结束，避免旧线程和新请求同时落盘。
- [x] 跑相关loading/cache及thumbnail/API测试，记录结果与受控慢磁盘的事件循环响应差异。

## 任务2：图片缓存统一与滚动预取

文件：`core/network/ThumbnailProfile.kt`及图片Coil拦截器、`di/AppModule.kt`、`home/WindowLoading.kt`、现有图片显示组件；对应JVM测试。

- [x] RED：同资源/档位共享键；服务/配对会话/版本/档位隔离；滚动新位置取消首批未完成预取。
- [x] GREEN：在共享Coil入口统一带认证会话隔离的缩略图内存/磁盘键；保留详情原图策略；占位键转换一致。缓存命中也检查认证会话仍有效。
- [x] 去掉等待`warm(0)`后才订阅滚动的顺序依赖，Flow立即产生初始位置，`collectLatest`取消过期任务，最多16/并发2不变。
- [x] JVM定向测试与必要编译；能在隔离模拟器观测时比较冷/热缓存与快速切换，不冒充手机结果。

## 任务3：视频播放信息延迟

文件：`server/app/api/v1/media.py`、`server/tests/test_playback_contract_11.py`，复用既有性能日志。

- [x] RED：受控异步上游中PlaybackInfo与续播读取必须同时开始，凭据失败仍不下发地址；取消不遗留任务。
- [x] GREEN：凭据成功后并行独立读取源信息和续播，保留HLS源ID、续播字段与错误回退，增加脱敏阶段耗时。
- [x] 相关playback合同/安全测试；用同一受控延迟比较串行与并行等待，记录实际数字。

## 任务4：视频内容预缓冲接入验证

- [x] 检查已安装GSY/Media3依赖实际源码/API，确认共享数据源、缓存身份与认证隔离方式。
- [x] 只有能验证真实缓存复用且不改变当前播放安全/生命周期时实施下一条少量预缓冲；否则按已批准设计记录确切阻碍，不重写播放器。

## 任务5：交付

任务4结论：本机GSY默认缓存键仅URL，不含认证身份；已完成接入边界验证，依批准的停止条件保留下一条字节预缓冲，未实现或宣称该功能。当前交付收益来自共享图片缓存、取消和播放信息并行。

- [x] 定向复核改动，必要构建；更新专项结果、SSOT、DEV_LOG，标明模拟器/手机边界。
- [x] Git提交并尝试push，记录真实网络结果；Android有改动交付APK，Server有改动交付对应包，未经授权不升级生产。
