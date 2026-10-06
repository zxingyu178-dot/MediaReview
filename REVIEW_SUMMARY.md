# MediaReview Stage 8D.3 审查摘要

当前阶段：Server Playback Reachability & Real Streaming。
基线：20069bae1804db1f8affcb5170b27a5765c37d77。
分支：feature/mediareview-v2-stage8d.3-server-playback-closure。
版本：Server 1.2.2 / API Contract 2；Android code 14 / 2.0.0-alpha7。

## 目标与实际变更

保持手机直接访问 Jellyfin，修复设备 Key 失效恢复、手机地址合同、HLS 真实源 ID，并增加独立手机诊断和真实 HTTP 播放测试。
新增认证 GET /api/v1/system/playback-readiness；只进行最多1024字节的 Range GET，禁跟随跳转、禁管理认证回退，严格验证元数据，不返回 Token。
Android 复用生产 Resolver，隔离业务 Bearer，支持受控错误分类、取消及会话隔离；仅在 Server 数据源设置区增加诊断。
新增真实 MP4 instrumentation，生产 GSY Full/Review 页面要求真实播放进度持续增长，批阅需上滑第二项；另测 Demo Full/Review。

主要文件：JellyfinClient、media.py、playback_readiness.py、PlaybackReadinessDto、V2PlaybackDiagnostic、V2PlaybackDiagnosticPanel、3个androidTest文件及对应JVM/pytest回归。
数据库：无schema变更，沿用设备行凭据字段。公共GSY/Demo实现无变更。
部署配置模板公开client_url/client_host_allowlist，部署README补充LAN端口检查；既有版本预检/构建版本同步1.2.2。
少量原有Python文件只按已配置Ruff规则清理排版/注解；不改变其业务逻辑。

## 验证进度（最终）

- Server 全量 pytest：**471 passed / 0 failed / 0 error（exit 0）**。
- Android JVM：**363 项通过，0 失败/错误**（51 个结果文件聚合），含 12 项诊断测试。
- lintDebug / compileDebugKotlin / compileDebugAndroidTestKotlin / assembleDebug / assembleDebugAndroidTest：**通过**；lint **0 errors / 40 warnings**。
- Ruff check . / format --check .：通过，162 文件已格式化。
- 隔离真实 Jellyfin 10.11.11：Direct 206/1024 bytes、HLS 真实 MediaSourceId 及有效 manifest、删除 Key 后重建通过。
- 独立静态复审：未解决 Critical 0 / Important 0。
- **真实 HTTP 播放 instrumentation：4/4 通过**（Server 完整播放器真实起播、Server 批阅上滑第二条真实起播、Demo 完整播放器、Demo 批阅）。
- 8D.2 回归 instrumentation：**8/8 通过**（Compatibility UI / What's New / Browser）。
- 部署沙箱生命周期：**17 通过 / 0 失败**（版本 1.2.2 动态取自 version.ps1）。
- 诊断 302/403 分类回归：已执行（Server 端 403→`forbidden`、3xx→`redirect`，Android 端独立分类断言）。
- 最终 HEAD：`__FINAL_HEAD_SHORT__`（提交后写入交接包）。

### 主 Agent 在本轮修复的执行期缺陷（诚实记录）

1. 初次 Server 全量为 6 失败/460 通过（旧断言 + 高负载性能/调度测试）；初次 Android 全量 4 失败/359 通过（metadata 预期 RED、取消 fixture shutdown 超时、旧 alpha6 断言）。原始日志全部保留。
2. **真实 HTTP 播放测试基建两处缺陷（非产品缺陷）**：① fixture 用 `127.0.0.1` 下发播放地址，被生产 `MediaUrlResolver` 正确拒绝（禁止回环目的地）→ 播放永远无法起播；改为设备 LAN 地址后仍失败，因 MockWebServer 只监听回环 → 改 `start(0.0.0.0, 0)` 后通过。② 原测试用反射读 GSY 内部 URL（`GSYComposeHostPlayer` 无公开 URL 访问器，恒为 null）→ 无论播放是否成功都会超时；改为核对**播放器真实发出的视频请求 URL == 生产解析 endpoint**（更强的事实证据）。
3. 回归 instrumentation 首轮 2 项失败为模拟器 **SystemUI ANR 弹窗**抢占焦点（Espresso `RootViewWithoutFocusException`），关闭弹窗后 8/8 通过；与产品代码无关。
4. 最终 Server 全量曾再次出现 2 项高负载偶发（100k 行排序 1.153s > 1.0s、task_manager 循环），单独复跑均通过，空闲后全量 471 passed/exit 0。

原始日志全部保留，不以定向通过代替最终全量。

## 已知问题与运维边界

生产Deployment：NOT PERFORMED；Real User LAN Playback：NOT TESTED；NO USER APK。
本机8766公开health返回Server1.1.0且无api_contract，未读取生产配置/媒体/凭据，也未升级服务。
沿用既有Registry Service与ControlHub binding，本阶段不新增长期对象或生命周期入口。
隔离Jellyfin已停止；自动审批拒绝删除本次临时目录，目录未清理并排除交接包。

最高风险三项：用户真实LAN不可达仍需真实设备证据；Jellyfin命名API Key沿用其既有权限范围，不代表细粒度隔离；PlaybackInfo返回源与具体媒体编码/HLS转码兼容性仍取决于Jellyfin及媒体。
不自动进入8E。最终交接包只在执行门禁完成、commit/push与Git clean之后生成。

阶段结论：**合格（CODE_READY）** —— 执行门禁已闭合（Server pytest / Android JVM / lint / compile /
真实 HTTP 播放 instrumentation / 8D.2 回归 instrumentation / 部署沙箱 全部 PASS，Git clean）。
同时如实声明：**Real User LAN Playback: NOT TESTED**、生产部署 NOT PERFORMED、NO USER APK。
