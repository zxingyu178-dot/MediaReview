# REVIEW_SUMMARY — MediaReview 1.1 Task C：Direct Play 与单次 HLS 回退

## 阶段编号与名称

- 阶段 C（takeover plan `docs/superpowers/plans/2026-08-30-mediareview-1.1-takeover.md`）
- 名称：Direct Play 与单次 HLS 回退（`1.1.0-beta1`）
- 提交：前置 `89644fd fix(review): monotonic session ids`；Server
  `814740e feat(server): add secure playback contract`；Android
  `757a33d feat(android): add direct hls state machine`；收口修复
  `fix(android): fall back to hls on direct prepare failure`；文档
  `docs(handoff): record task C completion and review`
- 基线：`e04ebc4`（Task B 终点）

## 阶段目标

"手机视频看不了"的关键阶段：Server 提供安全播放合同（Direct Play + 唯一一次 HLS 回退 +
设备级可撤销凭据），服务端 API key 绝不进入任何 URL/JSON/headers/日志；Android 用纯状态机
驱动 Direct→恰好一次 HLS→中文终态，无回退循环；取消/切换媒体重置回退配额；legacy
`stream_url` 一版兼容。

## 实际完成内容

- **Server 播放合同**（`814740e`）：`GET /media/{id}/playback` 返回
  `direct` / `fallback_hls`（各为 `{url, headers}`）+ `resume_position_ms`；
  `stream_url` 保留为兼容字段恒等于 `direct.url`。
- **设备级播放凭据**：按设备签发/复用 Jellyfin 命名 key（`mediareview-<installation_id>`），
  value 只存服务端 `paired_device`（迁移 `0013_device_playback_key`，`down_revision=0012`
  单一线性头），仅经播放响应 headers `X-Emby-Token` 下发给该设备，支持按名幂等撤销；
  签发失败 fail-closed（中文错误，不下发任何直连地址）。
- **安全终检**：Direct/HLS URL 构造前后均做 server-key 排除检查；HLS 为 `master.m3u8`
  最小转码集（h264+aac），中间层不转发视频流。
- **Android 状态机**（`757a33d`）：`PlaybackStateMachine` 纯转移函数——Direct 成功→
  DirectPlaying；Direct 失败→恰好一次 FallbackHls；HLS 失败→中文终态
  （"无法播放该视频(直连与转码回退均失败):…"）；取消/切换媒体重置并重新获得回退配额；
  迟到的旧事件被忽略。`PlayerCore` 单例 `HttpDataSource.Factory` 共享请求头、
  `playStream(headers, startPositionMs)`、错误 SharedFlow 上抛；`PlayerViewModel` 错误/成功
  collector 驱动状态机，`usingFallback` 指示，legacy stream_url 兼容。
- **前置修复**（`89644fd`）：review 会话平局打破键单调化，关闭 Task B 遗留 M-A。
- **收口修复**（本阶段）：审查发现错误→事件映射忽略 Idle 态——Media3 数据源准备期失败
  （未经过 READY，stage 仍为 Idle）是最常见 Direct 失败形态，此前不会触发 HLS 回退。
  抽出共享纯函数 `errorEventFor(stage, reason)`（Idle/DirectPlaying→DirectStartFailed、
  FallbackHls→HlsStartFailed、Terminal→null），PlayerViewModel 改用之；RED
  `ErrorEventMappingTest` 4 例编译失败 → 实现后 GREEN。
- **独立审查返修**（首轮 NOT CLEAN → 复审 CLEAN）：
  - C1（Critical）Jellyfin `/Auth/Keys` 契约错配：POST 应为 `app`、GET 返回项应为
    `AppName`（官方 SDK/API 文档核实）。修复 `client.py` 并同步修正四处测试 mock，
    新增锁定测试 `test_device_key_provisioning_uses_real_jellyfin_app_contract`。
  - I1（Important）批阅模式无凭据：`ReviewPlayable` 加 `headers`，
    `resolvePlayable` 经纯函数 `directPlaybackEndpoint` 提取 direct URL + headers，
    `PlayerCore.applyHttpHeaders` 供 `playStream`/`prepareSilent` 共用注入；
    新增 `ReviewPlayableEndpointTest` 3 例。
  - I2（Important）撤销未清除/撤销 Jellyfin key：`pairing.revoke_device` 清空
    jellyfin_key_* 列；`POST /pairing/revoke` 经 `optional_jellyfin_client` 尽力撤销
    命名 key；新增 HTTP 级测试。
  - I3（Important）迟到错误污染新会话：`PlayerErrorEvent(mediaId, error)` 携带槽位媒体 id，
    `PlayerViewModel` 经 `isCurrentSessionError` 过滤；新增 `SessionErrorFilterTest` 3 例。

## 是否完整达到目标

是。计划 Step 1-5 完成；Step 6（格式矩阵/真机）因本机无设备/真机/真实 Jellyfin 留待 Task G
（已按交接规则明确记录，不冒充完成）；Step 7 全量门禁 + 独立审查达到 0 Critical /
0 Important。

## 核心架构 / API / 数据库变化

- API：`GET /media/{id}/playback` 响应新增 `direct`、`fallback_hls`、`resume_position_ms`；
  `requires_jellyfin_auth` 改为 `false`；`stream_url` 语义=direct.url（兼容）。
- 数据库：新增迁移 `0013_device_playback_key`（paired_device 加
  jellyfin_key_name/value/created_at），从 0012 延伸，单一线性 head。
- Android：新增 `PlaybackStateMachine` / `PlaybackEvent` / `PlaybackStage` /
  `errorEventFor` / `PlaybackEndpointDto`；`PlayerCore` 引入共享 HttpDataSource 与错误流。

## Android UI/交互变化

- 普通播放器起播失败自动回退一次转码 HLS；回退期间 `usingFallback` 提示（UI 尚未消费该
  字段，见遗留）；HLS 再失败显示中文终态错误。

## 已执行测试与结果（真实命令）

```powershell
# Server focused（播放合同 + 迁移 + 相关回归）
server\.venv\Scripts\python.exe -m pytest tests\test_playback_contract_11.py `
  tests\test_connection_identity_11.py tests\test_media_image_proxy_11.py tests\test_review_service.py -q
# => 41 passed
# Server 全量
server\.venv\Scripts\python.exe -m pytest -q          # => 通过（退出码 0，约 291 项）
server\.venv\Scripts\python.exe -m ruff check .        # => All checks passed!
server\.venv\Scripts\python.exe -m ruff format --check .  # => 89 files already formatted
# Android RED（收口修复）：ErrorEventMappingTest 编译失败（errorEventFor 未定义）
# Android focused（修复后）
android\.\gradlew.bat --offline --no-daemon :app:testDebugUnitTest --tests "*PlaybackStateMachineTest" --rerun-tasks
# => BUILD SUCCESSFUL
# Android 全量四目标（--rerun-tasks）
android\.\gradlew.bat --offline --no-daemon :app:testDebugUnitTest :app:assembleDebug `
  :app:assembleAndroidTest :app:lintDebug --rerun-tasks
# => BUILD SUCCESSFUL；JVM 142 tests / 0 failures；lint 0 errors；debug APK 已产出
```

## 独立审查

- 审查报告：`.superpowers/sdd/task-c-independent-review.md`（两轮）。
- 第一轮 **NOT CLEAN**（1 Critical / 3 Important / 6 Minor）：C1 Jellyfin `/Auth/Keys`
  契约错配（POST 应为 `app`、GET 返回项应为 `AppName`，mock 复刻错误字段致测试全绿、
  生产走不到）；I1 批阅路径无设备凭据；I2 撤销不撤销/不清除 Jellyfin key；
  I3 错误 collector 不区分会话身份。
- 返修复审 **CLEAN（0 Critical / 0 Important / 6 Minor）**：C1/I1/I2/I3 全部关闭并
  新增锁定测试；独立复审员复现关键数字（Server focused 52 passed、Android JVM 142/0、
  四目标构建成功、lint 0 errors）。

## 已知问题 / 遗留 TODO

- instrumentation 仍未在设备执行（本机无 ADB 设备/模拟器/system image）；Task C Step 6
  格式矩阵（MP4/H.264、MKV/HEVC、4K、多音轨、内嵌字幕、仅转码）与真机 Direct<3s/
  HLS<8s 属 Task G。
- 设备级命名 key 的创建/回读/撤销契约已按官方文档修正并锁定测试，但未在真实 Jellyfin
  服务器上端到端验证（本机无真实 Jellyfin）——Task G 真机阶段必须实测。
- HLS 最小转码集未经真实 Jellyfin 转码服务验证（本机无真实 Jellyfin）。
- 6 Minor 记录在案不阻塞：M1 `usingFallback` 只写不读；M2 Retry/Cancelled 生产未
  dispatch；M3 普通播放器绕过 MediaUrlResolver；M4 批阅不消费 fallback_hls/resume；
  M5 user_id 未配置发无效续播请求；M6 HLS 从原始续播位重启。

## 是否建议进入下一阶段

建议进入 Task D（批阅、收藏、安全删除与重复整理，`1.1.0-beta1`）。Task C 为
"手机视频看不了"补上 Direct/HLS 链路与设备级凭据安全边界；设备级播放体验与格式矩阵在
Task G 真机验收。

## Agent 自认为风险最高的 3 个点

1. **设备级命名 key 依赖 Jellyfin `/Auth/Keys`**：命名 key 的创建/回读/撤销契约未在真实
   Jellyfin 服务器上验证（本机无真实 Jellyfin）；不同 Jellyfin 版本对命名 key 与
   `X-Emby-Token` 直连流的支持可能有差异——Task G 真机阶段必须实测。
2. **HLS 最小转码集参数**：`VideoCodec=h264&AudioCodec=aac` 是否总能被服务器接受并产出
   可播 m3u8（含容器/DRM/字幕场景）未经验证；失败时状态机正确进入中文终态，但体验依赖
   真实转码服务。
3. **播放器状态机与 Media3 生命周期耦合**：Direct 失败在"起播成功判定"与"错误上抛"之间的
   竞争窗口（READY 与 onPlayerError 顺序）已用 Idle 态修复覆盖，但真机上 HLS 起播的
   READY 时序、预加载双实例（P0/P1）与普通播放器共用同一个 PlayerCore 的 headers 切换，
   仍需 Task G 真机验证。

## 敏感信息说明

本阶段未接触任何真实密钥、Token、真实配置或生产数据；测试全部使用临时数据根与
MockTransport / 内存命名 key 模拟。设备级 Jellyfin key 的 value 仅存在于服务端数据库
与播放响应 headers 的内存流转，不写日志、不进 URL/JSON。

阶段结论：合格
