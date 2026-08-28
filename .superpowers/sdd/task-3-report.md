# MediaReview 1.1 Task 3 Report

## 状态与范围

结论：代码与隔离自动化验收合格；真实 LAN/组播、真机 Jellyfin 与 Android Keystore
instrumentation 未执行，不能作为物理设备验收。

实现范围严格限定于 Task 3：局域网 URL、发现、手动地址、稳定 installation ID、重复配对
upsert、Alembic 0012、Android Keystore credential、连接状态模型。未进入 Task 4 视觉、Task 6
播放凭据、HLS/播放器状态机、删除/重复、admin 重构或 live deployment。

## 实现合同

- MediaReview canonical TCP `8766`；UDP discovery `35001`。
- UDP 回复解析 advertised name/port，host 只取数据包来源；回环/未指定/组播/畸形/旧端口忽略，
  去重后通过 `/api/v1/system/health` 才显示。socket 使用 `use`，短 receive timeout 观察取消。
- 手动输入支持 hostname、IPv4、括号 IPv6、可选 HTTP(S)/port，缺省补 8766；网络前校验。
- `jellyfin.client_url` 可选且与 `jellyfin.url` 共用严格 HTTP(S)/secret-exclusion 规则。缺省时
  仅替换 Jellyfin host 为客户端访问 MediaReview 所用 host，保留 scheme/port/base path。
- Task 2 图片仍经认证相对代理；FastAPI 不代理视频。Task 6 前 playback 仍返回
  `requires_jellyfin_auth=true`，不下发 server key。
- Android `MediaUrlResolver` 集中处理 MediaRepository 的 media/review/favorite/delete/sprite/
  playback DTO，最终交给 Coil/Media3 的地址已解析；拒绝非 HTTP(S)、userinfo、畸形与凭据 URL。
- Android installation ID 先生成并持久化再提交，无 `android-default`。Server trim+casefold
  installation ID；三次重复配对一行、token rotate、旧 token 立即失效。
- 0012 从 0010 链接，不创建 0011 placeholder。仅合并规范化后完全相同的历史 ID，并保留最新
  有效 credential；later Task 7 `0011_duplicate_groups` 必须从届时 current head 链接。
- Android DataStore 只保存 AES-GCM ciphertext；key 在 Android Keystore。首次读取迁移并删除旧
  plaintext key；clear 删除 credential/base URL，保留 installation ID。crypto boundary 可注入测试。
- ConnectionState 分别表达 MediaReview online、Jellyfin online、sync 和 authentication；无 Task 4 UI。

## TDD 证据

### Server RED

命令：

```powershell
$env:MEDIAREVIEW_DATA_ROOT='E:\aihome\codex\temp\mediareview-task3-red-server'
.\.venv\Scripts\python.exe -m pytest tests\test_connection_identity_11.py -q
```

初次结果：`7 failed`。失败分别证明旧 8765、缺 `client_url`、remote playback 保留回环、
重复安装身份未规范化、缺 revision 0012。测试隔离时发现宿主 SOCKS 环境缺 socksio，随后通过
httpx MockTransport 和清空测试进程 proxy vars 消除非功能性网络错误，未访问真实网络。

诊断字段另按 RED→GREEN 增加测试：初次明确失败于 `/system/info` 回传 `127.0.0.1`，修复为
只报告 URL 是否 configured 后通过。

### Android RED

命令：

```powershell
$env:JAVA_HOME='C:\Users\30566\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2'
$env:ANDROID_HOME='D:\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest --tests "*MediaUrlResolverTest" --tests "*ServerDiscoveryContractTest" --tests "*CredentialPolicyTest" --tests "*ConnectionStateTest" --tests "*UrlNormalizeTest"
```

初次 Android SDK 环境定位后，稳定 RED 为 Kotlin 编译失败：缺 `MediaUrlResolver`、发现解析/
health gate、`CredentialCipher`/迁移、`ConnectionState` 和 8766 合同。实现后首轮 focused
`15/15` 通过；括号 IPv6 曾以单项运行失败暴露重复括号，修复后复跑全绿。自审又以独立 RED
证明 clear 只清 DataStore、未清内存 token，补齐后最终 focused `16/16`。

## 最终验证

- Server focused Task 3：独立审查返修后 `9 passed`（含诊断脱敏与 loopback Host/client URL 拒绝）。
- Server full pytest：`244 tests`，0 failure；仅既有 Starlette/httpx deprecation warning。
- Server quality：`ruff check .`、`ruff format --check .`、`git diff --check` 均 exit 0。
- Android focused：独立审查返修前 `16 tests`，0 failure；返修新增 server-only host、advertised
  port、实际 Jellyfin/sync/auth 状态断言后 focused BUILD SUCCESSFUL。
- Android full：`52 tests`，0 failure；`:app:assembleDebug` BUILD SUCCESSFUL。
- 无真实网络、设备、`C:\ProgramData`、运行中 0.8.1 服务或真实密钥访问。

## 自审与关注点

1. 保持 Task 2 图片代理：server 仍只代理 `image/*`、25 MiB、no redirect、nosniff；无视频代理。
2. 保持 server-key 边界：client URL 构造前后、playback 最终序列化、diagnostic config 均不回传 key。
3. `client_url` 未配置时依赖认证请求的 Host；生产 LAN 请求应使用手机实际访问的 IP/hostname，
   反向代理若改写 Host 必须保留用户可达值。
4. JVM fake crypto 验证迁移策略与可注入边界，assemble 验证 Keystore API；真实硬件 key 创建、
   OS 备份/恢复和损坏密文行为仍需 instrumentation/真机验收。
5. 三个不同 historical IDs 保留三行是有意 cleanup limitation；没有足够证据自动合并。
6. 自动发现默认仍是 canonical 8766，但接受服务端广告的合法 1..65535 自定义端口，并对该端口
   实际执行 `/health`；非法范围、回环来源与健康失败仍拒绝。

## 独立审查返修

提交前独立 review 报告 5 个 Important，均以新增 RED 后修复：Server 拒绝 loopback
`client_url` 与 Host-derived fallback；Android 默认识别 reserved server-only hostname；pairing status
异常保持 Unknown；已认证连接通过 `/jellyfin/status` 与 media sync 实际填充 Jellyfin/sync 状态并
把 401 标为 Rejected；discovery 接受合法 advertised custom port。返修后 Server focused `9 passed`、
Android focused BUILD SUCCESSFUL，ruff check/format 与 diff check 均通过。提交 SHA：
`033602f9af0a281a24789340008c1ea2493e9676`。

阶段结论：合格（自动化范围）；真实 LAN/真机边界待后续验收。

## Formal review I1–I8 / M1 返修（2026-08-28）

审查源：`.superpowers/sdd/task-3-review.md`。逐项核对当前源码后确认 I1–I8 与 M1 均可复现，
没有机械接受或需要技术反驳的项目。返修保持 Task 2 图片代理、无 server-key 合同和无视频代理，
未进入 Task 4 视觉或 Task 6 播放凭据。

### RED 证据

- Server 聚合 RED：
  `pytest -q tests/test_connection_identity_11.py tests/test_deployment_diagnostics_contract.py`
  初次稳定为 `7 failed`：I1 的 port 0、单标签、`.internal`、`.local` 四例，I2 的较新短 hash
  覆盖有效 64hex，I8 的诊断 URL/异常原文，M1 的活跃计划 8765。
- Android 聚合 RED：focused Gradle 首次稳定编译失败，缺失损坏 credential invalidation、受限 health
  payload/资源关闭、paired-origin policy、恢复状态 helper 等合同；实现基础 API 后，严格 UTF-8
  query 回归以 `%FF` 单例稳定 `1 failed`，证明旧 `URLDecoder` 会接受非法编码。
- Server full 首轮在新 request-host 策略下稳定暴露 4 个 `testserver` 单标签测试夹具失败；只在测试
  fixture 显式 allowlist `testserver` 后四项 focused 转绿，生产默认拒绝策略未放宽。

### GREEN 结果与逐项关闭

- I1：Server `client_url` 与 request-host 共用 host policy；拒绝 port 0、单标签及 `.internal/.local/.lan`，
  本地域名只能进入规范化 `client_host_allowlist`。Android 保持相同 server-only 边界。
- I2：0012 仅把未 revoked 且精确 64 位十六进制值视为有效 credential；按有效性、时间选择 winner，
  覆盖有效/无效/撤销碰撞及 0012→0010 降级。
- I3：decrypt/Keystore 异常转成可恢复 invalidation，删除坏密文与遗留明文、清 TokenProvider，保留
  base URL/installation ID，并表达 Rejected/Unpaired；fake cipher JVM 测试覆盖 bad tag/key loss。
- I4：discovery health 禁 redirect，限定 8 KiB `application/json`，只接受
  `success=true/data.status=ok/version/components`；timeout/错误 payload/content-type/oversize/
  cancellation/close 均有隔离合同测试，连接在 finally 关闭。
- I5：IPv6 字面量不再走“无点号 hostname”替换；query 采用有界、严格 UTF-8 percent 解码，拒绝
  编码/重复编码 credential name、歧义 `;` 与非法 octet；MediaRepository 生产调用实际传入受控
  server-only host 集合。
- I6：PairingRepository 维护共享 `StateFlow<ConnectionState>`；Connect/Settings/Home 保存实际 probe
  结果。本地 credential 只表示待验证，不能直接成为 Paired；offline 与 401 分别进入 Offline/
  Rejected，401 同时清内存 token。
- I7：TokenProvider 同时绑定 token 与规范化 paired origin；API 和 Coil 只在 scheme/host/effective
  port 精确一致时附 Bearer。health、pairing status、verify 使用独立无凭据且禁 redirect client，
  成功保存新 pairing 后才启用认证 client。
- I8：diagnose.ps1 删除 `url/client_url` 原值，只写 `url_configured/client_url_configured` 布尔值；
  health/Jellyfin 失败只写固定分类，不写异常原文。
- M1：`docs/DEVELOPMENT_PLAN.md` 活跃检测端口更新为 8766。

### Formal review 最终验证

- Server focused：`17 passed`；full：`253 tests`、0 failure，仅既有 Starlette/httpx deprecation warning。
- Server quality：`ruff check .`、`ruff format --check .`、`git diff --check 033602f --` 全部 exit 0。
- Android focused：`20 tests`、0 failure；full unit：`62 tests`、0 failure；
  `:app:testDebugUnitTest :app:assembleDebug --rerun-tasks` `BUILD SUCCESSFUL`，51 tasks executed；仅既有
  `PlayerViewModel.kt` delicate API warning。
- 全部验证使用 worktree `.venv`、既有 JDK 21/Gradle/Android SDK 缓存与隔离测试数据；未访问真实
  LAN/服务/设备、`C:\ProgramData`、0.8.1 运行服务、真实配置或密钥。

### 剩余验收边界 / concerns

1. 未执行真实 UDP/LAN、反向代理 Host 保留、物理 Android 或真实 Jellyfin；自动化不能替代真机验收。
2. Android Keystore 真机 key 创建、系统备份恢复与硬件层 key loss 仍需 instrumentation；本轮只以
   fake cipher 和 assemble 验证恢复策略及 API 可编译。
3. HttpURLConnection 的取消由 1200 ms connect/read timeout 提供上界；fake transport 已验证取消不被
   吞掉且资源关闭，但真实无线网络的即时中断行为未做设备测量。
4. 使用单标签或私有后缀的客户端可解析 DNS 名时，部署者必须显式配置
   `jellyfin.client_host_allowlist`；默认拒绝是安全边界。

Formal review 阶段结论：I1–I8/M1 已按自动化合同关闭；保留上述真实环境验收边界。

## 第二轮独立复审 I1–I4 返修（2026-08-28）

审查源：`.superpowers/sdd/task-3-rereview.md`。第二轮指出显式 `client_url`
与 Android host 猜测冲突、首次 installation ID 并发竞争、配对业务失败被误报为
Offline，以及 401 后持久密文复活四项问题。

### RED → GREEN

- Server playback DTO 新增明确 URL provenance/authoritative/rewrite 合同；显式且已验证的
  `client_url` 标记为 authoritative，Android 仍检查 scheme/userinfo/credential，但不再
  猜测替换其 host。fallback 派生 URL 才携带受控 rewrite 信息。allowlisted 单标签和
  `.internal/.local/.lan` 由 Server/Android 参数化合同共同覆盖。
- installation ID 的首次 read-generate-write 由进程内互斥协调，两个并发 caller 返回并
  持久化同一非空 ID。
- `PairingRepository.Result.Failure` 不再默认等同 Offline；只有网络失败设置
  MediaReview Offline。无效/过期配对码保持 MediaReview Online 并表达认证失败，所有路径
  更新同一个 `StateFlow<ConnectionState>`，Connect/Settings/Home 消费该状态。
- 已认证 probe 收到 401 时同时清除 DataStore 密文/遗留明文和内存 token，保留 base URL 与
  installation ID；当前 UI 会话保持 Rejected，重启不会重新发送旧凭据。

### 主代理 fresh 验证

- Server full：`258 passed`，仅既有 Starlette/httpx deprecation warning。
- Server focused：`tests/test_connection_identity_11.py` 为 `20 passed`。
- Server quality：`ruff check .`、`ruff format --check .`、`git diff --check` 通过；
  初次 format check 正确发现一个新增测试换行，格式化后 focused 与全部静态检查复跑通过。
- Android：`:app:testDebugUnitTest :app:assembleDebug --rerun-tasks` 为
  `BUILD SUCCESSFUL`；XML 汇总 `69 tests`、0 failure/0 error/0 skipped，51 tasks executed。

第二轮自动化合同已关闭。仍未执行真实 LAN/UDP、反向代理、物理 Android、真实 Jellyfin
或 Keystore instrumentation，因此阶段只能在自动化范围内验收。
