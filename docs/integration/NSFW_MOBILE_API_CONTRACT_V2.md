# NSFW Studio Mobile API v2 · 客户端冻结契约

- 版本：`NSFW_MOBILE_CONTRACT_V2 = 2`
- 冻结方：MediaReview Android（`feature/nsfw-image-review-v2`）
- 状态：**客户端已冻结 / 服务端未实现**。本文件是客户端唯一端点与字段事实源；UI 不得临时拼接其它版本端点。
- 来源需求：`docs/integration/NSFW_MOBILE_API_REQUEST_V2.md`（原文归档，未改动）。
- 基线：MediaReview `codex/mediareview-stage9-native-source` @ `37e93e2`；NSFW Studio 只读参考（未修改任何 NSFW 代码、数据库、部署或端口）。

> 真实 NSFW Mobile Review Bridge（8877）当前只声明 `api_contract=1`，能力集为
> `image_review / review_session / favorite / thumbnail / preview / original`，
> **不含本文件所需的 v2 feed / details / 按 image_id 审核 / 提示词评分能力**。
> 因此真实端到端联调在本轮被明确阻断；本轮只交付客户端契约、能力门禁与模拟接口（MockWebServer）测试。

## 1. 传输与鉴权

| 项 | 约定 |
| --- | --- |
| 基址 | 用户输入的 Bridge 地址，默认端口 8877；客户端只保存 origin（scheme + host + port） |
| 传输 | HTTP/JSON，UTF-8；图片二进制沿用现有 `/mobile/images/{image_id}/thumbnail|preview|original` |
| 鉴权 | `Authorization: Bearer <token>`（沿用现有 8877 配对 token），仅对已配对 origin 注入 |
| 信封 | `{ "success": bool, "data": T?, "error": { "code": string, "message": string, "details": any? }?, "request_id": string }` |
| 版本 | 健康端点 `api_contract`；本客户端要求 `>= 2` |
| 时间 | 所有时间为 UTC ISO 8601（带 `Z` 或偏移）；客户端不把接收时间当生成时间 |

### 1.1 能力门禁（配对前置）

`GET /mobile/system/health` 必须同时满足：

1. `product` 以 `nsfw-studio` 开头（兼容 `nsfw-studio` 与 `nsfw-studio-v2`）；
2. `api_contract >= 2`；
3. `capabilities` 包含以下全部项：
   - `mobile_feed_v2`
   - `mobile_image_details_v2`
   - `mobile_image_decision_v2`
   - `mobile_prompt_review_v2`
   - `mobile_prompt_evaluation_v2`

任一不满足 → **不发起配对**，UI 展示缺失项；不得把"旧 Bridge 可连"当作可审核。

已保存配对恢复时（App 启动）同样重新执行本条能力门禁：旧 Bridge/降级服务端保留 token，
但标记 `Incompatible` 并阻止进入审核页；只有明确的 401/吊销才清除 NSFW token。
若健康检查因断网等原因失败，兼容性标记为 **Unknown**（不是 Compatible）：保留 token，
但不得启动 feed 与轮询；用户重试健康检查成功后才允许进入审核页。

### 1.2 错误码（客户端冻结）

| code | HTTP | 含义 | 客户端行为 |
| --- | --- | --- | --- |
| `AUTH_REQUIRED` / `AUTH_REVOKED` | 401 | 未配对 / 已被吊销 | 只清除 NSFW 凭据并回到配对页；不影响 MediaReview Server |
| `CURSOR_EXPIRED` | 410 | 游标过期 | 提示"已从头重新同步"，丢弃游标从空游标重拉，尽量保持当前 image_id 位置 |
| `REVIEW_CONFLICT` | 409 | 图片状态被他处修改 | 刷新详情与权威状态，**不自动重发同一次 decision** |
| `PROMPT_UNLINKED` | 422 | 图片没有可靠 prompt 关联 | 禁用评分入口并说明原因 |
| `PROMPT_VERSION_UNKNOWN` | 422 | 版本未知且请求强行指定 | 拒绝提交；要求用户显式选择"对提示词整体评分" |
| `VALIDATION_ERROR` | 422 | 参数越界（分数/维度等） | 展示服务端消息，不重试 |
| `IDEMPOTENCY_CONFLICT` | 409 | 同一 `client_request_id` 载荷不一致 | 查询回执，不重复提交 |
| `IMAGE_NOT_FOUND` | 404 | 图片不存在 | 提示并从队列移除/刷新；不得当作"Bridge 缺能力" |
| `PROMPT_NOT_FOUND` | 404 | 提示词不存在 | 禁用评分入口并说明 |
| `CAPABILITY_MISSING` | 404/501 | 服务端显式未实现该能力 | 阻断该动作，提示 Bridge 版本过旧 |
| `INTERNAL_ERROR` | 5xx | 服务端错误 | 可重试；标注"未确认"而不是成功 |

错误码映射规则（双端兼容）：

- Bridge v1 旧码 `UNAUTHORIZED` → 客户端规范化为 `AUTH_REQUIRED`；`DEVICE_REVOKED` → `AUTH_REVOKED`。旧配对有效时不得误判为已吊销。
- 无业务 code 的 404：`/mobile/images/*`（feed 除外）→ `IMAGE_NOT_FOUND`；`/mobile/prompts/*` → `PROMPT_NOT_FOUND`；
  其它路由（含 `/mobile/system/health`）→ 原样 `HTTP_404`，**不得捏造 `CAPABILITY_MISSING`**。
- 无业务 code 的 409：`/decision` → `REVIEW_CONFLICT`；`/evaluations` → `IDEMPOTENCY_CONFLICT`；其它 → `HTTP_409`。
- 无业务 code 的 410：仅 `/mobile/images/feed` → `CURSOR_EXPIRED`；其它路由 → `HTTP_410`。

## 2. 端点（客户端冻结）

### 2.1 `GET /mobile/images/feed`

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `cursor` | string? | 空/缺省 = 从头（首屏按服务端默认窗口，必须分页）；非空 = 服务器签发的 opaque 游标 |
| `limit` | int | 1..100，客户端固定 50 |

响应 `data`：

```json
{
  "items": [{
    "image_id": "img_...",
    "revision": "17",
    "event": "UPSERT",
    "status": "NEW",
    "is_favorite": false,
    "kind": "original",
    "job_id": "job_...",
    "seed": 123456,
    "width": 1024,
    "height": 1024,
    "created_at": "2026-10-28T12:00:00Z",
    "updated_at": "2026-10-28T12:00:03Z",
    "prompt": {
      "prompt_id": "prm_...",
      "prompt_version_id": "prmv_...",
      "link_state": "EXACT"
    },
    "thumbnail_url": "/mobile/images/img_.../thumbnail",
    "preview_url": "/mobile/images/img_.../preview",
    "original_url": "/mobile/images/img_.../original"
  }],
  "next_cursor": "c_...",
  "has_more": true,
  "resumed": false
}
```

游标与顺序：

- 顺序由服务端定义（建议 `(in_epoch_ms, monotonic_seq)` 编码为 opaque 字符串），客户端**不解析**、不按手机本地时间排序。
- 同一个 `image_id` 是唯一身份；`revision` 必须是**十进制非负整数字符串**（可超过 Long 上限），单调递增。
  - 客户端按十进制长度/字典序可靠比较；非法 revision（负数、字母、空串）记录直接拒绝，不污染状态。
  - 未知 `event` 值一律拒绝，不得默认当作 `UPSERT`。
- `event`：`UPSERT`（默认，创建或更新）或 `REMOVE`（队列 tombstone）。
  - `REMOVE` 只表示该图从手机队列移除；**不删除文件、不改变数据库审核记录**。
  - tombstone revision 参与顺序校验：旧 `REMOVE` 不得删除较新图片；旧 `UPSERT` 不得复活已移除图片；
    只有 revision 更大的 UPSERT 才能重新加入。
  - 移除未知 ID 幂等；重复 REMOVE 不报错。
  - 当前图片被移除时，客户端保持索引位置选择下一条（无则上一条 / 空态）；移除位置在当前图之前时当前图片不变。
- 新 ID 追加到队尾；已存在 ID 原位替换，**不得插到当前浏览位置之前**，不得清空或跳转。
- 客户端只接受 `revision` 前进的更新；同 revision 幂等忽略，旧 revision 绝不覆盖新状态。
- 空增量（items 为空、has_more=false）表示"本轮没有变化"，不代表任何旧图被删除；删除只通过 REMOVE 事件表达。
- `has_more=true` 时客户端可用同一 `next_cursor` 继续追平（有界、可恢复）；`next_cursor` 在 `has_more=false` 时仍应返回，供下一轮增量轮询。
- `has_more=true` 但 `next_cursor` 缺失或等于本轮 cursor = 服务端游标未推进，客户端停止本轮并提示错误（`CURSOR_MISSING` / `CURSOR_STALLED`）。
- 终态 `has_more=false` 且未返回 `next_cursor`：客户端清空游标并提示"下次从头同步"，**不得静默沿用旧游标**。
- 游标过期 → `410 CURSOR_EXPIRED`；客户端只重置一次，再次 410 明确报错，不无限重试；成功后清除过期提示。
- 断线重放：客户端保存最后成功 `next_cursor`（当前仅内存）；重连后用该游标继续；重复卡片按 `image_id` 去重。
- 首次历史同步（空游标）与 410 重建历史都不计入"新到图片"计数；只有真正增量（调用方游标非空且未重置）才计数。

### 2.2 `GET /mobile/images/{image_id}/details`

响应 `data`：

```json
{
  "image_id": "img_...",
  "created_at": "2026-10-28T12:00:00Z",
  "generated_at": "2026-10-28T12:00:02Z",
  "width": 1024,
  "height": 1024,
  "positive_prompt": "生成时快照",
  "negative_prompt": null,
  "prompt_id": "prm_...",
  "prompt_version_id": "prmv_...",
  "link_state": "EXACT",
  "job_id": "job_...",
  "module_id": "basic_generate",
  "seed": 123456,
  "model": "qwen-image-2.1",
  "size_bytes": 1048576,
  "mime": "image/webp",
  "updated_at": "2026-10-28T12:00:03Z",
  "revision": "17"
}
```

- `positive_prompt / negative_prompt` 是**生成时快照**；`null` = 未知，`""` = 明确为空，两者不可混淆。
- `link_state`：`EXACT`（prompt + version 都可靠）/ `UNRESOLVED`（prompt 可靠、生成版本未知）/ `UNLINKED`（连 prompt 也不可靠）。
- 图片提示词被编辑后，旧图仍显示旧快照；客户端禁止用当前 Prompt 正文替换历史快照，也禁止按文本相同自动认领 prompt_id。

### 2.3 `POST /mobile/review/images/{image_id}/decision`

```json
{ "action": "KEEP", "favorite": true, "expected_updated_at": "2026-10-28T12:00:03Z", "client_request_id": "uuid" }
```

- `action` ∈ `KEEP / REJECT / RESET` **或 null**。
- **favorite-only**：`action` 缺失/为 null 且 `favorite` 有值；服务端只改收藏，**必须保留原 `status`**（NEW/KEEP/REJECT/ARCHIVED 均不变）。
- `action` 与 `favorite` 不能同时缺失；客户端在发送前就拒绝这种请求。
- `expected_updated_at` 必填（与 feed 的 `updated_at` 对齐）；不一致 → `409 REVIEW_CONFLICT`，返回权威 `data`。
- `REJECT` 只改状态，不删除文件；客户端不提供永久删除入口。
- 成功 `data`：`{ image_id, status, is_favorite, updated_at, revision }`；`status` 是权威值，favorite-only 时必须等于原状态。
- `client_request_id` 用于写请求幂等：同 ID 同载荷重复提交返回原结果，不重复改状态。

### 2.4 `GET /mobile/prompts/{prompt_id}/review-summary?version_id=...`

```json
{
  "prompt_id": "prm_...",
  "prompt_version_id": "prmv_...",
  "link_state": "EXACT",
  "evaluation_count": 3,
  "version_latest": { "evaluation_id": "pev_...", "overall_score": 4, "prompt_version_id": "prmv_...", "created_at": "...", "notes": "" },
  "prompt_latest": { "evaluation_id": "pev_...", "overall_score": 3, "prompt_version_id": null, "created_at": "...", "notes": "" },
  "images_total": 12,
  "seen_count": 8,
  "keep_count": 6,
  "reject_count": 2,
  "has_new_samples": true
}
```

- `prompt_latest` 是跨版本聚合结果，必须带所属 `prompt_version_id`（可为 null）；**不得**不加标识地当作本版本分数。
- 没有版本时 `link_state=UNRESOLVED`，评分对象必须由用户显式选择 `prompt_version_id=null`。

### 2.5 `GET /mobile/prompts/{prompt_id}/images?version_id=...&cursor=...&limit=...`

```json
{
  "items": [{"image_id": "img_...", "thumbnail_url": "/mobile/images/img_.../thumbnail", "status": "KEEP", "is_current": true}],
  "total": 12,
  "current_index": 3,
  "next_cursor": "c_...",
  "has_more": false
}
```

### 2.6 `POST /mobile/prompts/{prompt_id}/evaluations`

```json
{
  "prompt_version_id": "prmv_...",
  "overall_score": 4,
  "dimensions": {"subject": 4, "lighting": 3},
  "notes": "第 3 批 seed 更稳",
  "client_request_id": "uuid"
}
```

- `overall_score` 必填，整数 1..5。
- `dimensions` 可选；只提交用户实际填写的维度，白名单：
  `subject / clothing / scene / composition / lighting / style / quality`，每维 1..5；**不得默认填 3**。
- 客户端序列化会省略未填写的可选字段（`dimensions` / `prompt_version_id` / `notes` / `favorite`），
  服务端必须把"字段缺失"与 `null` 等价处理；不得把缺失维度补成 3 分。
- `prompt_version_id` 允许 null，但只有在用户明确选择"对提示词整体评分（生成版本未知）"时才可以传 null。
- 追加历史，不覆盖旧评分；评分不改变 prompt 状态。
- 同一 `client_request_id` 重试必须幂等；载荷不一致 → `409 IDEMPOTENCY_CONFLICT`。
- 客户端在首次发送后**冻结请求载荷与 requestId**：重试必须复用同一载荷；用户改分必须显式"新建评分请求"生成新 ID，禁止同键改 payload。
- 未确认请求以 `server_origin + prompt_id + version_id + client_request_id` 绑定保存，互不覆盖；客户端最多保留 5 条未确认记录，
  达到上限时拒绝继续提交并要求先核对旧回执（保证请求不会丢失）。
- "新建评分请求"只更换新请求号，**旧未确认记录继续保留**：客户端清除记录不代表服务端取消旧请求，不能把换 ID 当作已取消。
- 未确认请求写入 `SavedStateHandle`（进程重启后仅提示、不自动重发）；提交成功或回执确认后清除。
  `SavedStateHandle` 只覆盖系统重建/进程回收，不保证强制停止后的持久化；若需要更强恢复，服务端阶段 B 后改为独立 DataStore
  （按 origin + epoch 绑定），本阶段不实现。
- 成功 `data`：`{ evaluation_id, prompt_id, prompt_version_id, overall_score, created_at, client_request_id }`。

### 2.7 `GET /mobile/prompts/evaluations/{client_request_id}`（回执查询）

```json
{ "found": true, "evaluation": { "evaluation_id": "pev_...", "prompt_id": "prm_...", "prompt_version_id": "prmv_...", "overall_score": 4, "created_at": "...", "client_request_id": "..." } }
```

- 提交超时/结果未知时，客户端用同一 `client_request_id` 查询；`found=false` 才允许重新提交（仍用同一 ID）。

## 3. 客户端实现边界

- 只新增独立 NSFW 配对存储（DataStore `nsfw_studio_profile`）、独立 token、独立 Retrofit 客户端；**不覆盖** MediaReview Server 的 DataStore / token / 401 处理。
- 不将 NSFW 加入全局 `V2DataMode`；`DEMO / SERVER` 含义不变。
- 不复用依赖视频播放、Jellyfin 与删除队列的 `V2ReviewViewModel`；只复用通用图片缩放、时间格式化、Snackbar。
- 不提供永久删除；不调用 Desktop 8000 路由；不新增端口。
- 服务端未实现前：真实 Bridge 配对会在能力门禁处被拒绝，这是**预期行为**，不是客户端缺陷。

## 4. 契约变更流程

任何字段/路径变更必须先改本文件与 `docs/integration/fixtures/`，再改代码；客户端 DTO 与端点只在 `NsfwModels.kt` / `NsfwStudioApi.kt` 两处定义。

## 5. 与原需求单的差异对照（阶段 A 冻结）

| 议题 | 原需求单 | 当前冻结契约 | 理由 |
| --- | --- | --- | --- |
| 图片移除 | A.19 要求 create/update/remove 事件语义 | feed 增加 `event=REMOVE` tombstone；只移除队列，不删文件；未知/重复移除幂等 | 满足源需求，同时不扩大删除权限 |
| 收藏与审核 | 收藏必须独立于审核结果 | decision `action` 允许 null；favorite-only 保留原 `status` | 防止收藏误改 NEW/REJECT |
| revision | 只要求可去重 | 客户端只接受 revision 前进；同版本幂等、旧版本忽略 | 防止旧页覆盖审核结果 |
| 游标异常 | 要求 410 与恢复 | 客户端只重置一次；再次 410 报错；`has_more` 但游标不推进 → 停止并报错 | 防无限循环与静默积压 |
| 404 语义 | 未细化 | 无 code 的 404 按路由映射 IMAGE/PROMPT_NOT_FOUND，其它保持 HTTP_404 | 不把"图片不存在"误报为 Bridge 缺能力 |
| 旧 401 码 | NSFW 现有 `UNAUTHORIZED` / `DEVICE_REVOKED` | 客户端规范化为 `AUTH_REQUIRED` / `AUTH_REVOKED` | 双端兼容，避免误清有效旧配对 |
| 评分幂等 | 建议 client_request_id + 回执 | 首次发送冻结载荷与 ID；改分必须新建请求号；pending 持久化仅提示不自动重发 | 防止同键改 payload 与重复计分 |
| 图片 URL | 仅同源相对 URL | 客户端只接受 `/mobile/images/` 相对路径，拒绝外部 Origin/协议相对/穿越/query | 防 SSRF 与外部资源加载 |

配套提案：`docs/integration/openapi-mobile-review-v2.proposal.json` 与 `docs/integration/fixtures/`（含 `feed_remove.json`、`decision_favorite_only.json`）。
