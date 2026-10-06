# Stage 8D.3 Server 播放闭环实施计划

> 使用 subagent-driven-development 按任务执行，任务范围互不重叠；最终进行独立审查。

**目标：** 按用户提供的 Stage 8D.3 规格完成播放地址、设备凭据恢复、手机诊断与真实 HTTP 视频验证。

**架构：** 手机保持直连 Jellyfin；Server 提供地址和设备认证头，不代理视频。readiness 分开记录 Server 到 Jellyfin 与手机到 Jellyfin，有限 Range 读取。

**技术栈：** FastAPI/httpx/SQLite、Kotlin/Compose/Retrofit/OkHttp、GSY Exo2、MockWebServer。

## 全局约束

- 基线 20069bae1804db1f8affcb5170b27a5765c37d77；当前分支 feature/mediareview-v2-stage8d.3-server-playback-closure。
- 不重构播放器、不修改 Demo 源、不部署生产、不读取生产配置/媒体/密钥、不交付 APK、不进入 8E。
- Server 1.2.2 / Contract 2；Android code 14 / 2.0.0-alpha7。
- 先失败测试，再修复，再回归。凭据不出现在日志、异常或诊断输出。

## Task 1：设备凭据与 Server readiness（主 Agent）

- [ ] 在 server/tests/test_playback_contract_11.py 增加已配对设备 Key 删除、轮换与上游失败测试，证明旧 Key 永久信任的 RED。
- [ ] 修改 server/app/api/v1/media.py 的 _resolve_stream_key：每次按固定名称向 Jellyfin reconciliation，变更才更新 DB。
- [ ] 在 JellyfinClient 新增有限 direct_stream_probe，GET Range bytes=0-1023，禁用服务端管理认证头，最多读取 1024 bytes，不跟随跳转。
- [ ] 新增认证 GET /api/v1/system/playback-readiness，可指定 media_id 或取索引中的视频；输出地址来源、凭据状态、媒体与 Range 结果，不输出 Token。
- [ ] 运行 focused pytest，再全量 pytest/ruff。

## Task 2：Android 手机播放诊断（独立实现 Agent）

- [ ] 新增 readiness DTO/API 方法与 V2PlaybackDiagnostic；复用健康/兼容门禁，获取 VIDEO playback 后使用 V2PlaybackResolver 得到同一 URL/headers。
- [ ] 独立 OkHttp client 不携带 MediaReview Bearer，不跟随重定向；有限 Range 读，状态分类 401/403/404/5xx/网络/可读流。
- [ ] 独立诊断 ViewModel/Composable 接入 V2DataSourceSheet；不得扩大公共播放器改动。
- [ ] MockWebServer JVM 测试真实 Range/header 传递、状态分类、异常无密钥、取消。
- [ ] 运行 testDebugUnitTest/compileDebugKotlin，将实际日志保存 review_meta/stage8d3。

## Task 3：真实 HTTP 播放 instrumentation（独立实现 Agent）

- [ ] MockWebServer 使用现有小 Demo MP4 字节作为测试 fixture；提供 Range 200/206，记录视频请求认证头但不打印值。
- [ ] ServerFullPlayerPlaybackTest 走生产 Repository/Resolver/ViewModel/GSY，证明 prepared、playing、position >500 且持续增长。
- [ ] ServerReviewPlaybackTest 走批阅生产页面，上滑第二项后新 Source 真正播放并检查 header。
- [ ] Demo full/review 起播快速回归；不修改 Demo 实现，不用无法播放 URL 证明 PASS。
- [ ] 构建 androidTest，主 Agent 用已有 AVD 执行并记录真实结果。

## Task 4：HLS 源合同与发布文档（主 Agent）

- [ ] 对照 Jellyfin 官方 PlaybackInfo 协议，使用返回的 MediaSources[].Id；获取失败不下发猜测的 HLS URL。
- [ ] 配置模板增加 client_url/client_host_allowlist，README 给 LAN 示例与端口检查，禁止修改防火墙。
- [ ] 更新版本、Alpha7 Release Notes、DEV_LOG/TASKS/REVIEW_SUMMARY。
- [ ] 全量测试、lint、compile、instrumentation；独立审查并解决 Important。
- [ ] 检查既有 Registry/ControlHub 接入，保持生产运行对象与部署件不变，未部署版本不伪称已接入。
- [ ] 提交并按项目规则 push；生成 00~10、git/logs/source、SHA256SUMS 的无 APK 阶段 ZIP。
- [ ] Gate 未全部满足标记 NOT_CODE_READY，真实用户 LAN 一律 NOT TESTED，完成后停止。
