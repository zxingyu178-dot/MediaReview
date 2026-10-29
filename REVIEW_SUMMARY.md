# NSFW 生图审核 V2 客户端审查摘要（2026-10-29）

- 分支：`feature/nsfw-image-review-v2`（基线 `codex/mediareview-stage9-native-source` @ `37e93e2`）
- 目标：MediaReview 新增与「批阅」并列的「生图审核」一级页面，独立连接 NSFW Studio Mobile Review Bridge，实现 v2 feed / 详情 / 按 image_id 审核 / 提示词版本评分。
- 实际完成：M0 契约与 fixtures、M1 独立配对与网络层、M2 五入口导航与独立页面、M3 feed/详情/审核/评分、M4 JVM 测试与构建。
- 是否达到目标：**客户端契约与模拟测试完成；真实 Bridge 尚未实现 v2，真实手机联调未完成**。

## 主要文件与 API

- `docs/integration/NSFW_MOBILE_API_REQUEST_V2.md`（需求单原文归档）
- `docs/integration/NSFW_MOBILE_API_CONTRACT_V2.md`（冻结端点、DTO、能力标记、错误码、游标语义）+ `fixtures/`
- `feature/v2/nsfw/`：`NsfwReviewScreen`、`NsfwReviewViewModel`、`NsfwProfileStore`、`NsfwPairingRepository`、`NsfwRepository`、`NsfwFeedStore`、`NsfwModels`、`network/NsfwStudioApi`、`di/NsfwModule`
- 修改：`V2MainScreen`、`MediaNavigator`、`V2BottomNavBar`（5 入口）、`CredentialCipher`（Keystore alias 可配置）、`UrlNormalize`（默认端口可配置）
- API：`GET /mobile/system/health`、`GET/POST /mobile/pairing/*`（沿用现有）、`GET /mobile/images/feed`、`GET /mobile/images/{id}/details`、`POST /mobile/review/images/{id}/decision`、`GET /mobile/prompts/{id}/review-summary`、`GET /mobile/prompts/{id}/images`、`POST /mobile/prompts/{id}/evaluations`、`GET /mobile/prompts/evaluations/{client_request_id}`
- 数据库：无新增、无迁移；NSFW 与 MediaReview 均未修改服务端代码。

## UI 与行为

- 底部导航变为 5 项：首页 / 批阅 / 生图审核 / 收藏 / 整理；未加入 `V2DataMode`，DEMO/SERVER 语义不变。
- 未配对时显示独立 NSFW 配对页；能力门禁失败时列出缺失能力，不发起配对。
- 已配对：单图浏览 + 缩略队列；新图只追加队尾，同 ID 原位更新，当前图片位置保持；`CURSOR_EXPIRED` 从头追平；409 只刷新权威状态不自动重提。
- 图片详情展示生成快照与关联状态；`UNLINKED` 禁用评分；`UNRESOLVED` 必须显式选择「整体评分（版本未知）」。
- 评分 1–5，仅提交填写维度（白名单 7 项），`client_request_id` 幂等，超时先查回执再决定是否重试。

## 阶段 A 客户端加固（2026-10-29）

针对审查计划 C-01…C-11 的修复（C-12 体验项按计划留待后续，不阻塞 P0/P1）：

- C-01 favorite-only：收藏不再发 `action=KEEP`，NEW/KEEP/REJECT 状态保持不变。
- C-02 revision 单调：旧 feed 页/重复回包不覆盖审核后的新状态；同版本幂等。
- C-04 前台轮询：仅生图审核页 START 状态每 15 秒增量同步，STOP/离开页即停止。
- C-05 游标：410 只重置一次，再次明确报错；游标不推进停止；300 张跨页追平与尾态 hasMore 正确。
- C-06 恢复重验：启动执行 health capability gate；旧 Bridge 保留 token 但阻止进入审核页。
- C-07 评分幂等：首次发送冻结 payload + requestId；改分必须新建请求号；pending 持久化仅提示不自动重发。
- C-08 generation 守卫：快速切图/关闭面板后旧 detail/summary 回包被丢弃。
- C-09 REMOVE tombstone：只移除队列不删文件；当前图被移除自动选下一条。
- C-10 图片 URL：仅同源 `/mobile/images/` 相对路径；拒绝外部 Origin/穿越/query。
- C-11 错误码：404/409/410 按路由映射；旧 `UNAUTHORIZED`/`DEVICE_REVOKED` 规范化。

## V2A.1 边界补漏（2026-10-29）

针对 V2A 审查发现的边界缺陷：

- **feed 事件顺序**：REMOVE 参与 revision 校验，保存 tombstone；旧 REMOVE 不删新图、旧 UPSERT 不复活已移除图；非法 revision/未知事件拒绝；超过 Long 的十进制按长度+字典序比较。
- **分页/游标**：sync 与 loadMore 共用 `fetchPageWithRecovery`；410 只重置一次；`CURSOR_MISSING`/`CURSOR_STALLED` 明确停止；终态缺 next_cursor 清空并提示；空队列安全；恢复成功清除过期提示；首次历史不计新到。
- **评分 pending**：多记录按 server_origin + prompt + version + request_id 绑定，互不覆盖；上限 5；换 ID 保留旧回执；SavedStateHandle 边界与 DataStore 提案写入契约。
- **连接生命周期**：兼容性三态（Unknown 不启动 feed/轮询）；connection epoch 防止断开后的旧 feed/审核恢复 READY；CancellationException 不转普通错误。
- **审核与关联**：旧 revision 审核结果改为"已过期，已刷新"；详情 image_id 不一致拒绝；EXACT/UNRESOLVED/未知关联严格失败关闭。

## V2A.2 最终客户端补漏（2026-10-29）

针对 V2A.1 审查给出的 4 组缺陷与 2 项发布前能力：

- **REMOVE 幂等**：只允许严格更大 revision 删除；同 revision（含前导零）一律忽略；补同版本/旧 REMOVE/重复 REMOVE/旧 UPSERT-tombstone 回归。
- **游标恢复**：空字符串/缺失/停滞 `next_cursor` 明确拒绝；410 后整段历史重建全程不计新图；`loadMore()` 使用 `resetApplied` 显示恢复提示；`CURSOR_EXPIRED/MISSING/STALLED` 清理内部水位，刷新从空游标恢复。
- **响应身份**：Repository 校验 decision `image_id`、evaluation `prompt_id/client_request_id/prompt_version_id`、receipt `client_request_id`、summary `prompt_id/version`；新增 `RESPONSE_MISMATCH`，不静默修正、不清 pending、不显示成功。
- **pending 生命周期**：成功回执与面板显隐解耦；成功一条后按剩余记录重算提示；删除按 `server_origin + client_request_id`；同一 prompt+version 多条记录可逐条切换核对。
- **持久化与恢复**：新增 `NsfwEvaluationJournal`（DataStore `pending_evaluations`），发送前先落盘；重启不自动重发，重试先查回执；旧 SavedState 记录一次性迁移。
- **连接恢复**：新增 `hasSavedPairing` + `retryConnectionCheck()`，已有配对健康检查失败后无需重新生成配对码。

## 测试与构建（实际结果）

- NSFW 定向：6 suites / 128 tests / 0 failures。
- JVM 全量：81 suites / 626 tests / 0 failures。
- `:app:assembleDebug`、`:app:assembleDebugAndroidTest` 通过（产品源码最终版本）。
- `:app:lintDebug` → 0 errors / 40 warnings / 17 information。
- 仪器测试：五入口 360dp 显示 + Tab 状态恢复在 `MediaReview_Test` AVD → `OK (2 tests)`，9.587s（V2A.1 既有结果，本轮未复跑设备）。
- 未执行真实 Bridge 联调：当前 `/mobile/system/health` 为 `api_contract=1`，缺 5 项 v2 能力，客户端在配对门禁处阻断（预期行为）。

## 已知问题 / 遗留

- 真实 Bridge v2 接口未实现（NSFW 维护者负责）；真实端到端、真机截图、正式可用均未验收。
- 游标仅保存在内存：进程终止后从头分页，可能重新混入旧图；持久化水位需绑定 `server_origin + epoch`，本阶段未落盘。
- 评分未确认记录已落 DataStore，但撤销配对不会删除旧来源记录；记录不会自动重发，需用户逐条核对。
- 评分面板当前用 AlertDialog 承载，未做分页加载更多；首期只展示该版本有界图片。
- 未实现图片手势缩放（复用需要 MediaReview 的图片 URL 规则，NSFW 路径不同）；当前为整图 Fit 展示。
- 未提供从 NSFW 图片跳转 MediaReview 媒体的能力（本轮不需要）。

## 风险最高的 3 个点

1. 真实 Bridge 字段/语义与冻结契约若有偏差，需按契约文档重新对齐（客户端已集中定义 DTO，改动面可控）。
2. 五入口在极窄屏 / 大字体下的布局只做了 360dp 仪器测试源码，尚未真机验证。
3. 评分版本绑定依赖服务端返回可靠 `prompt_id + prompt_version_id`；`UNRESOLVED` 的交互已显式限制，但若服务端错报 `EXACT` 会误绑版本。

阶段结论：有条件合格（阶段 A 客户端加固与模拟接口测试通过；真实 Bridge v2 未实现，真实联调、真机与正式可用未验收）。

---

# Stage8I 审查摘要（历史快照）

> **SUPERSEDED（2026-10-24）**：本文件是 Stage8I（Alpha12 / Server 1.4.0）的历史审查摘要，不代表当前版本或状态。当前进度、版本与已知问题见 [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md)；后续 Stage9 与 1.5.2 上线记录见 `docs/STAGE9_RESULT.md`、`docs/STAGE9_REPAIR_RESULT.md`、`docs/DEV_LOG.md`。

阶段：Media UX Polish & Review Reliability。分支 feature/mediareview-v2-stage8i-ux-polish；完整35节规格见 docs/STAGE8I_MEDIA_UX_SPEC.md。实现、最终设备门禁与正式离线产物已核验；最终推送和十二文档Handoff执行结果以旁置交付回执为准。

## 实现与架构

固定缩略图profile及完整变体缓存键；媒体墙/相册不请求原图。Viewer保留preview、当前原图优先、相邻source有界预取并取消过期任务。Full/Viewer共用服务端确认的待删除与撤销；共享有界横向Seek，保留双击±10秒和进度条绝对定位。

首页显示实际版本/API/能力诊断；文件夹封面与计数、子目录、递归范围和面包屑。Review使用原生VerticalPager、单手势最多一条、横向主导消费与子控件优先；当前视频循环，EOF竞态通过公开render恢复同一内核，稳定页预取相邻端点与poster。范围支持多选、库归属验证、冻结会话、显式重建及旧Server禁用文件夹选择。

Server解析MediaSources大小并回填索引/队列。删除receipt仅统计实际删除且路径已不存在的普通文件，整条路径链拒绝链接和重解析点；failed保留，missing/failed不计释放。Android传递实际字节，区分旧Server未知和Demo；整理刷新保留内容与重试。未重写现有架构、转发完整视频流或引入平行生命周期系统。核心改动位于图片profile/cache、共享pending/seek、folder/scope/review、delete_queue/receipt和现有构建/交接工具；无新增数据库migration。

Android code19 / 2.0.0-alpha12；Server1.4.0 / API2，新增thumbnail_profiles_v1、review_folder_filter_v1、delete_receipt_v1并保留旧七能力。七条中文更新说明、首装/跨版/关闭持久化同步。

## 当前证据

- Server完整round2：638项通过，Ruff通过，141输入无漂移。随后仅交接脚本/测试修正，受影响27项及Ruff通过，不冒称修正后全638重跑。
- Android完整round2：488 JVM项通过；lint零错误/42警告/16信息；Debug Main/Test构建通过，354输入无漂移。此后四处androidTest修改不改变产品源码、JVM或lint输入，复用匹配结果。
- 最终第三轮完整设备178项全部通过，零失败/错误/跳过，实际退出0，用时1276.588秒，354输入无漂移。保留首轮174/178与第二轮SystemUI ANR中断20项的历史；第三轮独立全178通过，不是跨轮相加。Main字节不变，新Test仅四文件窄修。真实6秒Direct/HLS循环/EOF、Full回归、快速五次翻页、原有blockedNext与图像布局均在最终XML中通过。
- Tasks1–8定向HTTP、真实6秒Direct/HLS循环/EOF、原生快慢五次翻页、Full手势、真实临时文件删除、范围与文件夹导航证据见DEV_LOG及review_meta/stage8i。只读AVD实际画面/位置/源证据与真实手机/家庭LAN分开。
- 全分支独立源码审查C0/I0/M1，四测试窄修补审C0/I0/M2。三个既有格式差异与测试诊断完备性为非阻断Minor；Ruff检查实际通过。Task8清理边界Minor已关闭。
- 真Alpha11→候选Alpha12图片100张body6785700→1329400字节，当前原图2355→967ms；首轮邻完成采样不足，新Test第三轮补齐真实HTTP及Coil完成1641/2079ms；其2048MB guest RAM与旧成对1536MB数据分列。Review首RGB代理波动，不宣称全面提速或硬件首帧SLA。

## 待完成与风险

唯一正式assembleRelease通过；APK code19/alpha12版本与签名核验通过，公共证书SHA256与正式Alpha11一致。新Server EXE隔离health为1.4.0/API2/十能力，正式Server ZIP逐项SHA核验通过。构建及打包提交为91b8406c6ab814326010736b3bf2d57f44723208；后续仅DEV_LOG/TASKS/REVIEW_SUMMARY/计划文档改变，不改变冻结源码及打包输入。最终push/clean与十二文档Handoff在最后文档提交后执行，以旁置终态回执为准。最高CODE_READY / READY_FOR_USER_VALIDATION，不表示真实手机/LAN或生产验收。

风险最高三点：首轮rapid末帧超时根因未确定，保留失败并以完整复验评估；真实手机/家庭网络及视觉触感待用户验证；生产升级依赖外部ControlHub正式合同。

继续BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT；不部署生产、不改真实Jellyfin、不删用户媒体、不清手机数据。不声称REAL_LAN_PASS或PRODUCTION_READY。

阶段结论：有条件合格（CODE_READY / READY_FOR_USER_VALIDATION；最终推送及Handoff以交付回执为准，真实手机/LAN待用户验收）。
