# NSFW Studio Mobile API v2 · 客户端冻结契约

- 版本：`NSFW_MOBILE_CONTRACT_V2 = 2`
- 冻结方：MediaReview Android（`feature/nsfw-image-review-v2`）
- 状态：**客户端已冻结 / 服务端未实现**。本文件是客户端唯一端点与字段事实源；UI 不得临时拼接其它版本端点。
- 来源需求：`docs/integration/NSFW_MOBILE_API_REQUEST_V2.md`（原文归档，未改动）。
- 基线：MediaReview `codex/mediareview-stage9-native-source` @ `37e93e2`；NSFW Studio 只读参考（未修改任何 NSFW 代码、数据库、部署或端口）。

> 真实 NSFW Mobile Review Bridge（8767）当前只声明 `api_contract=1`，能力集为
> `image_review / review_session / favorite / thumbnail / preview / original`，
> **不含本文件所需的 v2 feed / details / 按 image_id 审核 / 提示词评分能力**。
> 因此真实端到端联调在本轮被明确阻断；本轮只交付客户端契约、能力门禁与模拟接口（MockWebServer）测试。

## 1. 传输与鉴权

| 项 | 约定 |
| --- | --- |
| 基址 | 用户输入的 Bridge 地址，默认端口 8767；客户端只保存 origin（scheme + host + port） |
| 传输 | HTTP/JSON，UTF-8；图片二进制沿用现有 `/mobile/images/{image_id}/thumbnail|preview|original` |
| 鉴权 | `Authorization: Bearer <token>`（沿用现有 8767 配对 token），仅对已配对 origin 注入 |
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
| `CAPABILITY_MISSING` | 404/501 | 服务端未实现该能力 | 阻断该动作，提示 Bridge 版本过旧 |
| `INTERNAL_ERROR` | 5xx | 服务端错误 | 可重试；标注"未确认"而不是成功 |

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
- 同一个 `image_id` 是唯一身份；`revision` 单调递增，用于判断"同 ID 元数据更新"。
- 新 ID 追加到队尾；已存在 ID 原位替换，**不得插到当前浏览位置之前**，不得清空或跳转。
- 空增量（items 为空、has_more=false）**不代表任何旧图被删除**；v2 不定义 remove 事件。
- `has_more=true` 时客户端可用同一 `next_cursor` 继续追平；`next_cursor` 在 `has_more=false` 时仍应返回，供下一轮增量轮询。
- 游标过期 → `410 CURSOR_EXPIRED`。
- 断线重放：客户端保存最后成功 `next_cursor`（内存，不落盘）；重连后用该游标继续；重复卡片按 `image_id` 去重。

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

- `action` ∈ `KEEP / REJECT / RESET`；`favorite` 可选（不传 = 不改）。
- `expected_updated_at` 必填（与 feed 的 `updated_at` 对齐）；不一致 → `409 REVIEW_CONFLICT`，返回权威 `data`。
- `REJECT` 只改状态，不删除文件；客户端不提供永久删除入口。
- 成功 `data`：`{ image_id, status, is_favorite, updated_at, revision }`。

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
