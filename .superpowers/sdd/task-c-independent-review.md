# Task C 独立代码审查报告 — Direct Play 与单次 HLS 回退

- 审查人: 独立审查员（子代理）
- 仓库: `D:\MediaReview_1.1_Handoff`，分支 `feature/mediareview-1.1`，HEAD=`757a33d`（工作树含未提交改动）
- 审查基线: `e04ebc4` 起（含 89644fd、814740e、757a33d 三个提交 + 未提交工作树改动）
- 审查日期: 2026-09-02

---

## 1. 审查范围与方法

### 1.1 审查对象（逐条追踪）

| 提交 | 内容 |
|---|---|
| `89644fd` | review 会话单调 ID 前置修复（`server/app/services/review.py` + 测试） |
| `814740e` | Server 安全播放合同（`server/app/adapters/jellyfin/client.py`、`server/app/api/v1/media.py`、迁移 `0013_device_playback_key.py`、`server/app/db/models.py`、`server/tests/test_playback_contract_11.py` 等） |
| `757a33d` | Android 状态机（`PlayerCore.kt`、`ApiModels.kt`、`PlayerViewModel.kt`、`PlaybackStateMachine.kt`、`PlaybackStateMachineTest.kt`） |
| 工作树未提交 | `PlaybackStateMachine.kt`（新增 `errorEventFor` 纯函数）、`PlayerViewModel.kt`（改用 `errorEventFor`）、`PlaybackStateMachineTest.kt`（新增 `ErrorEventMappingTest`） |

### 1.2 方法

- `git show` 逐提交读 diff，再读取工作树当前完整文件做调用路径追踪（`client.py`、`media.py`、`PlayerCore.kt`、`PlayerViewModel.kt`、`PlaybackStateMachine.kt`、`ReviewViewModel.kt`、`MediaRepository.kt`、`MediaUrlResolver.kt`、`pairing.py`、`system.py` 等）。
- 用网络资料核对真实 Jellyfin `/Auth/Keys` API 合同（官方 SDK 生成客户端 + Jellyfin API 文档 + 第三方客户端实现），确定 mock 与真实服务的差异。
- 运行 server focused 测试复现关键数字（见第 4 节）。
- 未运行 Android 全量构建（耗时过长），Android 侧结论基于源码追踪与状态机纯函数测试。

---

## 2. 逐文件调用路径追踪

### 2.1 `server/app/adapters/jellyfin/client.py`（814740e）

- `item_hls_url()`（L55-64）：`{base}/Videos/{id}/master.m3u8?MediaSourceId={id}&VideoCodec=h264&AudioCodec=aac`，无凭据，路径段经 `_path_segment`（URL-encode）。**OK**。
- `hls_stream_url()`（L260-264）：构造 + `ensure_jellyfin_url_excludes_api_key` 双重校验。**OK**（服务端 key 不进 URL）。
- `ensure_device_stream_key()`（L268-285）：读取 `GET /Auth/Keys`，按 `item.get("Name") == key_name` 匹配；不存在则 `POST /Auth/Keys`（`params={"Name": key_name}`）后回读；仍读不到则抛 `JellyfinError`（fail-closed）。**存在严重缺陷，见 C1**：真实 Jellyfin 的 POST 参数是 `app`（不是 `Name`），GET 返回项的字段是 `AppName`（不是 `Name`）。因此真实服务器上**永远**匹配不到、永远抛 `JellyfinError`。
- `revoke_device_stream_key()`（L287-295）：按名读后 `DELETE /Auth/Keys/{AccessToken}`。Jellyfin 真实契约是 `DELETE /Auth/Keys/{key}`（key=AccessToken），**正确**。但生产代码没有任何调用方（仅测试使用），见 I2。
- `item_resume_position_ms()`（L297-309）：读 `/Users/{uid}/Items/{id}` 的 `UserData.PlaybackPositionTicks`，失败返回 0（fail-open）。`user_id` 为空时请求 `/Users//Items/...` → 404 → 0，无害。**OK（Minor：user_id 未配置时无意义请求）**。

### 2.2 `server/app/api/v1/media.py`（814740e）

- `PlaybackEndpoint`/`PlaybackInfo`（L305-341）：`direct` + `fallback_hls`（`url`+`headers`）+ `resume_position_ms` + 兼容字段 `stream_url`。**OK**。
- `_device_key_name()`（L347-350）：`mediareview-<installation_id[:48]>`；无 installation_id（开发模式 `pairing_required=False`）→ 共享名 `mediareview-shared-playback`。installation_id 前缀截断 48 字符，理论上前 48 字符相同的两台设备共享一个 key（概率极低）。**Minor**。
- `_resolve_stream_key()`（L353-367）：设备行有 `jellyfin_key_value` 则复用；否则签发并持久化到 `paired_device`（明文 SQLite）。并发首次签发时两台请求可能各自 POST 出重复 key，但幂等收敛、设备行终态一致，**可接受（Minor 竞态）**。
- `get_playback_info()`（L370-415）：`require_auth` 返回 `PairedDevice`（`auth.py` L32-44，配对关闭时返回 None）。签发失败 `ConfigError`(500) fail-closed，**安全姿势正确**；但因 C1，真实服务器上该端点恒 500。
- 凭据只在 `direct.headers`/`fallback_hls.headers` 的 `X-Emby-Token`；服务端 API key 只经 `ensure_jellyfin_url_excludes_api_key` 校验，不进入任何序列化输出。测试断言 `SERVER_KEY not in resp.text`。**OK**。

### 2.3 迁移 `0013_device_playback_key.py` + `models.py`（814740e）

- `0013.revises = "0012"`；`0012.revises = "0010"`（文件名跳过 0011，链靠 down_revision 定义）。`alembic heads` 实测只输出 `0013 (head)` → **单一线性 head**。
- upgrade 加 3 列 / downgrade 对称删 3 列。`test_migration_0013_preserves_devices_and_rolls_back` 实测通过（升级 0012→建行→0013→检查列+行→downgrade 0012→检查列消失+行仍在）。**OK**。

### 2.4 `server/app/services/review.py`（89644fd）

- `_new_session_id()`：进程内 `threading.Lock` + `_last_session_stamp` 强制 20 位时间戳（秒+微秒）严格递增，+`token_hex(4)` 防跨进程碰撞；`latest_active_session` 按 `session_id.desc()` 字典序平局打破，字典序 == 创建序（定宽十进制）。Windows 时钟粒度问题被进程内守卫解决。`test_session_ids_strictly_monotonic_under_frozen_clock`（冻结时钟 64 个）实测通过。**OK**（跨进程重启后时钟回拨的理论边界未覆盖，可接受）。

### 2.5 Android `PlayerCore.kt`（757a33d + 工作树）

- 单例 `DefaultHttpDataSource.Factory` 两槽共用（L69-73），`setDefaultRequestProperties(headers)`（L124-127）**只在 `playStream()` 中调用**——即只有普通播放器路径注入设备凭据。
- `errors: MutableSharedFlow(extraBufferCapacity=4)`（L88-89），`onPlayerError` 仅当 `p === active` 时 `tryEmit`（L320-324）。**OK**（review 模式的 preload 错误不误报）。
- `playStream(mediaId, url, headers, startPositionMs)`（L110-136）：headers 变更才重设；`startPositionMs>0` 用 `setMediaItem(item, ms)`。**OK**。
- `settle()`/`prepareNext()`/`prepareSilent()`（L180-225, L276-285）：**review 模式 P0/P1 从不传 headers、不传凭据**。见 I1。

### 2.6 Android `PlaybackStateMachine.kt`（757a33d + 工作树）

- 纯转移 `PlaybackTransitions.reduce`（L101-135）：
  - Idle/DirectPlaying + `DirectStartFailed` → FallbackHls(1)；FallbackHls + `DirectStartFailed`（迟到/重复）→ Terminal；FallbackHls + `HlsStartFailed` → Terminal；Terminal 忽略一切。**无回退循环**，符合"恰好一次"。
  - 迟到 `DirectStartSucceeded`（FallbackHls 上）被忽略。**OK**。
- 工作树新增 `errorEventFor()`（L69-78）：Idle/DirectPlaying→`DirectStartFailed`、FallbackHls→`HlsStartFailed`、Terminal→null。**修复了 757a33d 提交版中"Direct 数据源准备期即失败（stage 仍 Idle）→ 错误被丢、UI 卡 loading、永不回退"的缺陷**。方向正确。
- `RetryRequested`/`Cancelled` 事件：仅测试使用，生产从未 dispatch（UI 重试走 `load()`→`machine.reset()`）。见 M2。

### 2.7 Android `PlayerViewModel.kt`（757a33d + 工作树）

- init 双 collector：错误→`errorEventFor`→dispatch→（FallbackHls→`startHls()`；Terminal→中文错误 UI）；status Ready/Playing→按阶段登记 Direct/HLS 成功。全部主线程顺序执行，基本有序。
- `load()`（L78-95）：`machine.reset()` 后异步取 `/playback`，`direct` 缺失时回退 `stream_url` 构造无 headers 端点。**OK**（兼容一版）。
- `startHls()`（L98-108）：唯一一次回退；无 hlsEndpoint → 终态中文错误。HLS 与 Direct 均复用 `resumePositionMs`（Jellyfin 续播位），HLS 不从 Direct 实际进度续播。见 M6。
- 竞态：错误 collector 只按 `machine.stage` 映射、不按会话/媒体身份过滤，存在"上一媒体迟到错误在 `machine.reset()` 后、新媒体加载期间被当作新会话 Direct 失败"的窗口，见 I3。
- `usingFallback` 只写不读，见 M1。

### 2.8 Android review 路径 `ReviewViewModel.kt`

- `resolvePlayable()`（L393-405）：`repository.loadPlayback(...)?.stream_url`，**丢弃 `direct`/`fallback_hls`/`resume_position_ms`/headers**；`settle`/`prepareNext`→`prepareSilent`→`setMediaItem(url)` 无凭据。见 I1、M4。

### 2.9 Android `MediaRepository.kt` + `MediaUrlResolver.kt`

- `loadPlayback()`（L168-181）只对 `stream_url` 做 `MediaUrlResolver.resolve`（主机改写/凭据拒绝），`direct`/`fallback_hls` 原样返回 → 普通播放器用的 `direct.url` **绕过了** resolver 的安全网。服务端 `client_base_url()`（config.py L238-252）与 `_client_reachable_hostname`（L90-99）已拒绝 loopback/unspecified/multicast，故当前生产无实际影响，属纵深防御不对称。见 M3。

---

## 3. 发现的问题列表（分级）

### Critical（1）

#### C1 — `ensure_device_stream_key` 与真实 Jellyfin `/Auth/Keys` API 合同不符，Task C 播放链路在真实服务器上恒失败
- 位置：`server/app/adapters/jellyfin/client.py:276-285`
- 事实核对（真实 Jellyfin 契约，Jellyfin 10.10/10.11 官方生成 SDK 与 API 文档一致）：
  1. `POST /Auth/Keys` 的必需 query 参数是 **`app`**（"Name of the app using the authentication key"），不是 `Name`。代码发 `params={"Name": key_name}`。
  2. `GET /Auth/Keys` 返回 `Items[]`，每项的字段是 **`AppName`**（无 `Name` 字段）。代码匹配 `item.get("Name")`。
- 路径推演：真实 Jellyfin 上，首次 GET 匹配不到（字段名错）→ POST 用错参数名创建出 `AppName=""` 的 key → 回读仍匹配不到 `Name` → 抛 `JellyfinError` → `get_playback_info` 捕获后抛 `ConfigError`(HTTP 500，media.py:396-399)。Android `loadPlayback` 走 `runSuspendCatching` 返回 null → 普通播放器显示"无法获取播放地址"，**Direct 与 HLS 都不下发，播放完全不可用**。
- 影响：Task C 的核心交付（设备级播放凭据 → Direct Play + 单次 HLS 回退）在真实 Jellyfin 上整体不可用；fail-closed 安全姿势本身正确（不泄漏任何凭据），但功能被 C1 整体击穿。
- "测试通过但生产走不到"：`test_playback_contract_11.py:33-44/61-66`、`conftest.py:103-115`、`test_media_image_proxy_11.py:28-39`、`test_connection_identity_11.py:104-119` 的 mock 全部复刻了错误字段（`Name`/POST `Name`），与真实 Jellyfin 不一致，故全部通过。
- 修复方向：POST 参数改 `app`；读回匹配改 `item.get("AppName")`；并新增一个"按真实 Jellyfin 字段形状"的集成式 mock 测试。

### Important（3）

#### I1 — 批阅模式（P0/P1 预加载）播放从不注入设备级凭据，直接进入批阅则视频 401
- 位置：`android/app/src/main/java/com/mediareview/app/feature/review/ReviewViewModel.kt:401-404`；`PlayerCore.kt:276-285`（`prepareSilent`）、`:124-127`（headers 只在 `playStream` 设置）
- 路径：review 的 `resolvePlayable` 只取 `stream_url`（无 headers）；`settle`/`prepareNext`→`prepareSilent`→`setMediaItem(MediaItem.fromUri(url))`，共享 `HttpDataSource.Factory` 的 `currentHttpHeaders` 初始为空，仅普通播放器 `playStream` 会设置。
- 影响：同一进程内先开过普通播放器（且期间签发过凭据）时，review 碰巧共享 headers 可用；直接进入批阅（如"批阅会话断点恢复"、从媒体墙直达批阅）则 Jellyfin 流请求无 `X-Emby-Token` → 401 → 批阅视频无法播放。Task C 的凭据合同未接入批阅路径（该路径此前同样无凭据、非本任务回归，但 Task C 未完成其承诺）。
- 另见 M4（review 也无 HLS 回退与续播位）。

#### I2 — "可撤销"设备播放凭据在生产没有撤销路径，撤销设备后 Jellyfin key 仍有效且 DB 残留明文
- 位置：`server/app/services/pairing.py:126-137`（`revoke_device`）；`server/app/api/v1/pairing.py:107-113`（`POST /pairing/revoke`）；`client.py:287-295`（`revoke_device_stream_key` 无生产调用方）
- 影响：`revoke_device` 只置 `revoked=True`、清 `token_hash`，既不调用 `revoke_device_stream_key` 撤销 Jellyfin 侧命名 key，也不清 `jellyfin_key_value`。已撤销设备仍持有有效 Jellyfin 播放凭据（可继续直连 Jellyfin），与合同宣称的"按设备签发/复用的命名 key（可撤销）"安全属性不符；key value 明文滞留 DB 行。属于安全生命周期缺陷。

#### I3 — 状态机错误 collector 不区分会话身份：上一媒体迟到错误在 `machine.reset()` 后污染新会话
- 位置：`PlayerViewModel.kt:48-61`（错误 collector）、`:78-95`（`load`→`machine.reset()`）、`:98-108`（`startHls`）；`PlaybackStateMachine.kt:69-78`（`errorEventFor` 对 Idle 也映射 DirectStartFailed）
- 时序推演（全主线程）：媒体 A 播放中 onPlayerError → `_errors.tryEmit(errorA)`（缓冲）→ 用户立即切媒体 B → `load(B)` 先 `machine.reset()`（Idle）再挂起等网络 → collector 处理缓冲的 errorA：`errorEventFor(Idle)=DirectStartFailed` → FallbackHls → `startHls()` 用**旧媒体 A 的 hlsEndpoint**（`load(B)` 尚未返回，`hlsEndpoint` 仍是 A 的）以 `currentMediaId=B` 起播 → 之后 `load(B)` 完成才真正 `playStream(B.direct)` 覆盖。若 A 的错误后 `hlsEndpoint` 为空则直接进 Terminal；B 之后的真实错误在 Terminal 态被 `errorEventFor` 忽略（无回退、无提示）。
- 影响：`errorEventFor` 修复"Idle 准备期失败"的同时，把"上一会话迟到错误"误判为新会话 Direct 失败，破坏"迟到旧事件被忽略/恰好一次回退"的保证；`Cancelled` 事件设计用于此类重置但生产从未 dispatch。窗口窄（需错误缓冲 + 快速切媒体），无任何集成测试覆盖。

### Minor（4+）

- **M1** `usingFallback`（`PlayerViewModel.kt:24,106`）只写不读，UI（`PlayerScreen.kt`）无任何 HLS 回退中提示；回退过程对用户不可见。
- **M2** `PlaybackEvent.RetryRequested`/`Cancelled`（`PlaybackStateMachine.kt:93-97,132-133`）在生产从未 dispatch（重试走 `load()`→`machine.reset()`），终态只能靠整页重载退出；事件存在仅服务测试。
- **M3** 普通播放器使用原始 `direct.url`/`fallback_hls.url`，绕过 `MediaUrlResolver`（对 `stream_url` 生效的主机改写/凭据拒绝安全网，`MediaRepository.kt:171-179`）。服务端已拒绝 loopback/组播（`config.py`），当前无实际影响，但两条播放路径对主机处理不对称（纵深防御缺口）。
- **M4** 批阅路径只取 `stream_url`（Direct），不消费 `fallback_hls`/`resume_position_ms`：批阅无 HLS 回退、无续播。
- **M5** `item_resume_position_ms` 在 `user_id` 未配置时请求 `/Users//Items/...`（404→0），无害但属无效请求（fail-open）。
- **M6** Direct 中途失败转 HLS 时从原始 `resumePositionMs` 重启而非 Direct 实际进度，丢失本次会话进度/seek 位置。

---

## 4. 验证过的关键数字（真实运行）

均在 `D:\MediaReview_1.1_Handoff\server`，先设 `$env:MEDIAREVIEW_DATA_ROOT=Join-Path $env:TEMP 'mr-review*'`：

```
$ .\.venv\Scripts\python.exe -m pytest tests\test_playback_contract_11.py -q        → 8 passed
$ .\.venv\Scripts\python.exe -m pytest tests\test_review_service.py \
    tests\test_playback_contract_11.py::test_migration_0013_preserves_devices_and_rolls_back -q → 6 passed
$ .\.venv\Scripts\python.exe -m pytest tests\test_connection_identity_11.py \
    tests\test_media_image_proxy_11.py tests\test_phase7_capabilities.py -q         → 42 passed
$ .\.venv\Scripts\python.exe -m alembic heads                                       → 0013 (head)  （单一线性 head）
```

结论：server focused 测试全部通过，但 **C1 表明这些测试的 mock 与真实 Jellyfin 契约不一致**，属于"测试通过、生产走不到"。Android 状态机纯函数测试覆盖完整（含工作树新增 `ErrorEventMappingTest`），未发现回退循环；未运行 Android 全量构建。

---

## 5. 已确认无问题的重点项（对照任务清单）

- **服务端 API key 不进任何 URL/JSON/headers/日志/诊断**：`ensure_jellyfin_url_excludes_api_key` 在 `client.py:254-264`、`media.py:401-407` 双重校验；`paired_device` 列表（pairing.py:67-80）与诊断导出（system.py:139-215，只导出表计数 + 脱敏日志）均不含 key；日志脱敏覆盖 `api_key`/bearer/配对码。**确认 CLEAN**。
- **X-Emby-Token 只进 headers**：Android `PlayerCore.kt:124-127` 注入共享 HttpDataSource.Factory，不进入 URL；`MediaUrlResolver` 对凭据 query 有拒绝逻辑。**确认 CLEAN**（普通播放器路径）。
- **fail-closed**：签发失败 → ConfigError(500)，不下发直连地址，测试 `test_playback_fails_closed_when_key_provisioning_fails` 通过。**确认 CLEAN**。
- **迁移 0013**：单一线性 head（0010→0012→0013）、upgrade/downgrade 对称、不丢设备行。**确认 CLEAN**。
- **状态机无回退循环 / 恰好一次 / 迟到成功事件忽略 / 取消与切换重置**：`PlaybackTransitions.reduce` 与 `PlaybackStateMachineTest` 全覆盖。**确认 CLEAN**（纯函数层面）。
- **工作树 `errorEventFor`** 修复了提交版"Idle 准备期失败被忽略"缺陷，方向正确（其残留竞态见 I3）。
- **89644fd 单调 ID**：锁 + 进程内守卫，冻结时钟 64 个 ID 严格递增测试通过。**确认 CLEAN**。

---

## 6. 风险最高的 3 个点

1. **C1（Jellyfin API 契约错配）**：Task C 主功能在真实部署中完全不可用，且测试体系复刻了错误契约，是最需要优先修复并补真实形状 mock 的点。
2. **I1（批阅模式无凭据）**：V1 P0 的批阅（含 P0/P1 预加载、断点恢复）在真实 Jellyfin 上无法播放，且与普通播放器的"碰巧共享 headers"耦合脆弱。
3. **I3（迟到错误污染新会话）**：状态机"恰好一次回退/迟到事件忽略"的保证在切换媒体竞态下被击穿，无集成测试覆盖。

---

## 7. 结论（第一轮）

审查结论：NOT CLEAN（1 Critical / 3 Important / 6 Minor）

Critical 与 Important 摘要：
- **C1** `client.py:276-285`：`ensure_device_stream_key` 用错 Jellyfin `/Auth/Keys` 契约（POST 应为 `app`、读回应为 `AppName`），真实服务器上 `/playback` 恒 500，Direct Play 与 HLS 回退整体不可用；mock 复刻错误字段导致测试全绿。
- **I1** review 模式（P0/P1）从不注入设备凭据，直接进入批阅则视频 401。
- **I2** 设备撤销流程不撤销/不清除 Jellyfin 播放 key，"可撤销"安全属性未落地。
- **I3** 错误 collector 不区分会话身份，上一媒体迟到错误在 reset 后污染新会话回退配额（窄窗口、无测试覆盖）。

---

## 8. 返修复审（第二轮，2026-09-02）

首轮发现的 C1/I1/I2/I3 已全部修复并重跑门禁，复审结论 **CLEAN**。

### 8.1 修复核对（逐条追踪当前工作树）

| 首轮问题 | 修复位置 | 复审核对 |
|---|---|---|
| C1 `/Auth/Keys` 契约 | `server/app/adapters/jellyfin/client.py`：POST `params={"app": ...}`、GET 匹配 `item.get("AppName")`；同步修正 `conftest.py`、`test_connection_identity_11.py`、`test_media_image_proxy_11.py`、`test_playback_contract_11.py` 四处 mock；新增锁定测试 | 已核对官方契约（POST 参数 `app`、GET 返回项 `AppName`）。新增测试断言 POST 收到 `app=mediareview-shared-playback`、回读按 `AppName` 命中。**CLOSED** |
| I1 review 无凭据 | `PlayerCore.kt`：`ReviewPlayable` 加 `headers`、`applyHttpHeaders` 供 `playStream`/`prepareSilent` 共用；`ReviewViewModel.resolvePlayable` 缓存 PlaybackInfoDto 并经 `directPlaybackEndpoint` 提取 URL+headers；新增 `ReviewPlayableEndpointTest` 3 例 | 追踪 `resolvePlayable→ReviewPlayable(headers)→prepareSilent→applyHttpHeaders→httpDataSourceFactory.setDefaultRequestProperties`，凭据在批阅 P0/P1 均注入。**CLOSED** |
| I2 可撤销未落地 | `pairing.revoke_device` 清空 jellyfin_key_* 列；新增 `device_stream_key_name`；`POST /pairing/revoke` 经 `optional_jellyfin_client` 尽力撤销 Jellyfin 命名 key；新增 HTTP 级测试 | 测试断言本地列清空 + fake client 收到命名 key 撤销。**CLOSED** |
| I3 迟到错误污染 | `PlayerCore` 错误流改 `PlayerErrorEvent(mediaId, error)`（携带槽位媒体 id）；`PlayerViewModel` 经 `isCurrentSessionError` 过滤；新增 `SessionErrorFilterTest` 3 例 | 事件在 `onPlayerError` 时捕获槽位 mediaId，切换媒体后旧媒体错误被丢弃；纯函数测试覆盖同媒体/异媒体/未标记。**CLOSED** |

### 8.2 复审门禁（真实运行）

- Server focused（`test_playback_contract_11.py` + `test_phase7_capabilities.py` +
  `test_connection_identity_11.py` + `test_media_image_proxy_11.py`）：**52 passed**。
- Server 全量 pytest：通过（退出码 0）；`ruff check .` All checks passed；
  `ruff format --check .` 89 files already formatted。
- Android JVM：**142 tests / 0 failures**（含新增 `SessionErrorFilterTest` 3 例 +
  `ReviewPlayableEndpointTest` 3 例 + `ErrorEventMappingTest` 4 例）；四目标
  `testDebugUnitTest/assembleDebug/assembleAndroidTest/lintDebug --rerun-tasks`
  BUILD SUCCESSFUL；lint 0 errors。
- 首轮其余确认项（服务端 key 不进 URL/JSON/日志、X-Emby-Token 只进 headers、
  fail-closed、迁移 0013 单一线性头、状态机无回退循环、89644fd 单调 ID）在修复后未回归。

### 8.3 遗留（6 Minor，不阻塞）

M1 usingFallback 只写不读（UI 无"转码回退中"提示）；M2 RetryRequested/Cancelled 生产未
dispatch；M3 普通播放器绕过 MediaUrlResolver（服务端已拒 loopback）；M4 批阅不消费
fallback_hls/resume_position_ms；M5 user_id 未配置发无效续播请求（404→0）；M6 HLS 从原始
续播位重启。建议随 Task D/E 顺手处理。

复审结论：CLEAN（0 Critical / 0 Important / 6 Minor）
