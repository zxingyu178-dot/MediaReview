# 加载与资源专项结果（2026-10-30）

计划：[实施计划](superpowers/plans/2026-10-30-loading-resource-optimization.md)。本轮串行执行，复用现有框架，不改真实媒体、数据库结构、播放器内核、ControlHub契约或生产运行实例。

## 已实现

| 部分 | 改动与验证边界 |
|---|---|
| 生图增量读取 | 按页读取图片/任务/提示词身份，状态与revision在同一SQL快照中返回。隔离样本5/20图SELECT由12/42降到3/3；并发写入不再把旧状态标成新版本。 |
| 历史事件查询 | 利用既有(image_id,seq)索引取末条版本，避免整段历史聚合。10000事件夹具从约110000采样VM指令降到未触发100指令采样回调；不解释为零成本或手机速度提升倍数。 |
| 手机图片 | 当前预览成功后才预取下一张，单任务，切图/离开/审核忙/评分面板打开时取消。预取与展示使用同一Coil实例、尺寸和凭据+URL散列缓存键；缩略图256、预览1536，内存缓存32MB、磁盘128MB。未配对时不读写缓存。 |
| 同步与反馈 | 回前台立即同步；空闲/失败逐步退避至60秒，有事件恢复基础间隔；手动刷新立即执行。图片显示加载状态、失败可点重试。 |
| 桌面后台 | 状态查询并发合并、快照最多3秒，生命周期动作不合并/不缓存且前后失效。状态面板10秒观察，慢请求不叠加，隐藏暂停，回焦立即刷新；旧观察不覆盖操作结果。SSE健康每60秒校准、断线保留10秒兜底，隐藏不定时轮询，退出移除监听。 |

## 执行过的验证

证据在review_meta/nsfw-integration-20261029；均为隔离夹具，不操作用户照片。

- NSFW：`pytest tests/backend/test_mobile_v2_feed.py tests/backend/test_mobile_v2_scoring.py`，最终14项通过，loading-feed-final2.xml/log。先观察查询次数、并发快照及历史扫描RED后修复。
- NSFW：`pytest tests/backend/test_controlhub_comfy_target.py tests/backend/test_engine_runtime_api.py`，19项通过。TTL/并发用例先观察RED。
- NSFW前端：Vitest仅EngineControlPopover和jobStore.liveProgress两个文件，19项通过（先4项RED）；tsc --noEmit与vite build通过。使用已登记Node24.18.1，项目engines仍声明22，本轮未更换工具环境。
- Android：加载策略4项+审核ViewModel65项，69项单轮通过。Alpha24补充版本门禁时93项首轮91通过/2历史预期失败；仅修正WhatsNew测试目标为当前构建版本后，相关7项重跑通过。属于分轮收口，不称单轮93项全绿。历史目录范围用例保留，未为测试增加生产开关。
- 没有重跑全量pytest、全量JVM或connected；手机不可连接，不使用模拟器替代，不声明首帧/流畅度/耗电已改善。

## 视频字节预缓冲：未启用

已核对本机GSY13.2.1实际AAR/API/字节码：buildCacheKey(String)只使用DataSpec(Uri)和DEFAULT工厂；当前批阅和普通播放器均显式关闭cacheWithPlay。存在全局数据源拦截扩展点，但当前尚无按认证身份隔离、可供预取和实际播放器共同消费的缓存数据源。

本轮保留地址预取，不打开默认URL缓存、不发送后丢弃Range来冒充预加载。后续需单独实现共享CacheDataSource.Factory、凭据域缓存键、可取消且有字节上限的Range预取，并验证播放器确实消费缓存、拖动/换源/登出安全；涉及公共播放网络链，须配合真机验收后启用。这部分是明确的剩余项。

## 交付状态

- MediaReview开发分支codex/loading-resource-optimization，Alpha24/code31，构建源码4cc2565255d65de349ffba812cc30b156df3a046。Release一次构建成功（5m4s）；aapt确认版本，apksigner校验成功、证书与旧版相同；APK内核对到新缓存命名空间及轮询策略。
- NSFW性能分支codex/mobile-loading-resources，最终9115cb6（含7b8d9dd批量化、59e7a75后台轮询、9115cb6快照与索引修正），均已push；未部署到日用实例，不覆盖其他人的工作流修改。
- 独立APK：deploy_handoff/MediaReview_2.0.0-alpha24_loading.apk，20,797,240字节，SHA256 227af76b44d73fa5699a8bc0628efcdc23ce020c788f9ed0923d0710b5342b30，复制后复核一致；CURRENT_RELEASE已更新且保留旧指针备份。现有Server1.6.4包未改动、内置Alpha22事实保留，手机使用独立APK字段。生产/已安装版本保持原状，手机验收待设备连接。
