# 技术架构

## 1. 总体结构

```text
Android App
   |
   | 控制 API / JSON
   v
MediaReview Server (FastAPI)
   |
   +--> Jellyfin REST API
   +--> SQLite
   +--> FFmpeg / ffprobe
   +--> Cache
   +--> Duplicate Scanner
   +--> Review Engine
   +--> Delete Engine
   +--> Web Admin

Android Media3 Player
   |
   +----------------------> Jellyfin 视频流
```

核心原则：

- 控制层走中间层。
- 视频大流量尽可能 Android 直连 Jellyfin。
- 中间层不成为视频带宽代理。
- Jellyfin 是媒体数据源。
- MediaReview 自己管理批阅相关状态。

### 1.1 数据库优先媒体索引

`GET /api/v1/media` 的列表唯一数据源是 SQLite `media_cache_index`。请求内只执行
SQL count、筛选、搜索、排序与分页，也不构造 Jellyfin HTTP 客户端。若已选媒体库
尚无可用缓存，请求立即返回空页和 `pending/running` 同步元数据，并幂等编排一个
`media_refresh` 后台任务；未知或未选库只读缓存，不触发自动刷新。

后台处理器按 Jellyfin 每页 500 条读取，以 SQLite `ON CONFLICT DO UPDATE` 分批提交。
同一批量 upsert 内部也按 500 条安全分块，兼容旧批阅路径一次传入上万条媒体。
每次运行使用 generation ID；只有全部目标库所有分页都成功后，才在任务
`running -> succeeded` CAS 的同一写事务中把本代未见记录标记为
`is_available=false`。任一库失败或协作取消都不会隐藏旧缓存，错误只持久化为脱敏中文消息。
`media_refresh_target` 以 `library_id` 主键持有活动任务租约；重叠的目标集合会拆成不相交
任务，成功、失败或取消终态在同一事务释放租约。取消 API 自身也用条件 UPDATE CAS，陈旧
请求不能覆盖已提交的 succeeded 终态。

查询索引分别覆盖单库的 `available + library` 前缀和默认多库的全局顺序，两者均提供
有/无 `media_type` 的 name、created、size、duration、resolution 排序；固定排序关键路径
不使用 SQLite 临时 B-tree。10 万条索引的 50 项分页自动化门禁为测试机 `< 1s`，生产目标
为 `< 250ms`。

### 1.2 数据库优先批阅队列

`POST /api/v1/review/sessions` 只读取已选媒体库和 SQLite `media_cache_index`，不构造
Jellyfin/httpx 客户端，也不在请求内调用 Jellyfin `/Items`。`media_type`、`search`、
`sort_by`、`sort_order` 沿用媒体墙语义；随机排序把生成或传入的 seed 固化到会话快照。

筛选、稳定 NULL-last 排序、完全重复代表项选择和连续绝对索引均由 SQL 窗口函数完成，
队列通过单次 `INSERT ... SELECT` 写入。只有 full SHA-256 已确认的 exact 组才折叠，按请求
排序最先的成员为代表项；疑似重复保留，媒体文件和索引均不被删除或修改。完成旧 active
会话、新建会话和写队列处于同一事务，校验或插入失败会整体回滚。

`GET /api/v1/review/sessions/{session_id}/queue` 使用 SQL `COUNT` 和按绝对队列索引的
`OFFSET/LIMIT` 子查询，再一次 JOIN 当前可用媒体。缓存媒体缺失或不可用时，该页可少于
`page_size`，但保存的绝对 `index` 和队列 `total` 不压缩，保证旧客户端断点恢复语义稳定。

媒体列表、详情和批阅队列的 `cover_url` / `original_url` 只返回配对认证的 MediaReview
相对路径：`/api/v1/media/{media_id}/thumbnail` 与 `/api/v1/media/{media_id}/original`。
Jellyfin server API Key 只用于中间层到 Jellyfin 的 Authorization header，不进入 URL 或 JSON。
图片代理只接受上游 `image/*`，按 Content-Length 和实际累计字节限制 25 MiB，并返回
`X-Content-Type-Options: nosniff`；original 只允许图片媒体。图片上游禁止自动重定向，所有 30x
（含同源、跨源、链路本地与循环）都在第一跳返回脱敏错误，避免代理成为 SSRF 字节回传通道。
图片代理绝不转发视频流。

视频仍只提供 Jellyfin direct URL，中间层不建立任何 server-key 视频代理。Task 6 正式建立
可撤销的客户端凭据或 playback contract 前，兼容字段 `stream_url` 不含凭据，并同时返回
`requires_jellyfin_auth=true` 与中文可操作状态；客户端不得把该安全过渡响应视为可直接播放。

“confirmed exact”由共享哈希合同定义：full SHA-256 必须是精确 64 位十六进制文本。SQLite
连接注册同一个 Python 严格谓词为 deterministic UDF，SQL 还要求存储类型为 text、原始字节长度
精确为 64；因此 NUL、BLOB、Unicode、非 hex、读取失败哨兵、未完成和仅 quick hash 均不会折叠。
duplicate scanner 直接复用同一 Python 谓词，避免 SQL/Python 语义分叉。

## 2. 推荐技术栈

### Server

- Python 3.12+
- FastAPI
- Uvicorn
- httpx
- SQLAlchemy 2.x
- Alembic
- Pydantic v2
- SQLite
- FFmpeg / ffprobe
- APScheduler 或轻量后台任务队列
- pytest
- Ruff
- mypy（建议）
- PyInstaller/Nuitka 之一用于最终打包

### Android

- Kotlin
- Jetpack Compose
- Material 3
- Media3 / ExoPlayer
- Retrofit
- OkHttp
- Kotlinx Serialization
- Room（仅用于本地必要缓存/设置）
- DataStore
- Hilt
- Coil
- Navigation Compose
- Coroutines / Flow
- JUnit
- Compose UI tests

## 3. Server 模块

建议目录：

```text
server/
  app/
    main.py
    api/
      v1/
        libraries.py
        media.py
        review.py
        favorites.py
        delete.py
        duplicates.py
        sprites.py
        tasks.py
        system.py
        pairing.py
    core/
      config.py
      logging.py
      security.py
      paths.py
    adapters/
      jellyfin/
        client.py
        models.py
        mapper.py
    services/
      media_index.py
      sprite_service.py
      review_service.py
      favorite_service.py
      delete_service.py
      duplicate_service.py
      pairing_service.py
      task_service.py
    db/
      session.py
      models.py
      migrations/
    workers/
      sprite_worker.py
      duplicate_worker.py
    admin/
      static/
      templates/
    tests/
```

## 4. Android 模块

```text
android/
  app/
  core/
    network/
    model/
    database/
    datastore/
    media/
    ui/
  feature/
    connect/
    home/
    library/
    mediawall/
    player/
    review/
    favorites/
    deletequeue/
    duplicates/
    settings/
```

## 5. API 规范

统一前缀：

`/api/v1`

统一响应建议：

```json
{
  "success": true,
  "data": {},
  "error": null,
  "request_id": "..."
}
```

错误：

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "MEDIA_NOT_FOUND",
    "message": "媒体不存在",
    "details": {}
  },
  "request_id": "..."
}
```

必须：

- API 版本化
- OpenAPI 可访问
- 错误码稳定
- request_id
- 合理超时
- 幂等设计
- 分页
- 排序字段白名单
- 输入校验
- 高风险 API 审计

## 6. 建议 API

### 系统

- `GET /api/v1/system/health`
- `GET /api/v1/system/info`
- `GET /api/v1/system/storage`
- `GET /api/v1/system/logs/export`

### 配对

- `GET /api/v1/pairing/status`
- `POST /api/v1/pairing/start`
- `POST /api/v1/pairing/confirm`
- `POST /api/v1/pairing/revoke`

### Jellyfin/Library

- `GET /api/v1/libraries`
- `PUT /api/v1/libraries/selection`

### 媒体

- `GET /api/v1/media`
- `POST /api/v1/media/refresh`
- `GET /api/v1/media/{media_id}`
- `GET /api/v1/media/{media_id}/playback`
- `GET /api/v1/media/{media_id}/thumbnail`
- `GET /api/v1/media/{media_id}/original`

### 雪碧图

- `GET /api/v1/media/{media_id}/sprite`
- `POST /api/v1/media/{media_id}/sprite/generate`

### 喜欢

- `GET /api/v1/favorites`
- `PUT /api/v1/favorites/{media_id}`
- `DELETE /api/v1/favorites/{media_id}`

### 批阅

- `POST /api/v1/review/sessions`
- `GET /api/v1/review/sessions/latest`
- `GET /api/v1/review/sessions/{session_id}`
- `GET /api/v1/review/sessions/{session_id}/queue`
- `POST /api/v1/review/sessions/{session_id}/seen`
- `POST /api/v1/review/sessions/{session_id}/position`
- `POST /api/v1/review/sessions/{session_id}/advance`
- `POST /api/v1/review/sessions/{session_id}/complete`

### 待删除

- `GET /api/v1/delete-queue`
- `POST /api/v1/delete-queue/{media_id}`
- `DELETE /api/v1/delete-queue/{media_id}`
- `POST /api/v1/delete-queue/commit`

### 重复

- `GET /api/v1/duplicates/exact`
- `GET /api/v1/duplicates/suspected`
- `POST /api/v1/duplicates/scan`
- `GET /api/v1/duplicates/jobs/{job_id}`

### 后台任务

- `GET /api/v1/tasks`
- `GET /api/v1/tasks/{task_id}`
- `POST /api/v1/tasks/{task_id}/cancel`

## 7. 数据库

建议表：

- `app_settings`
- `paired_devices`
- `library_selection`
- `media_cache_index`
- `media_sync_state`
- `favorites`
- `review_sessions`
- `review_session_items`
- `delete_queue`
- `duplicate_groups`
- `duplicate_members`
- `background_tasks`
- `audit_log`

不要复制完整 Jellyfin 数据库，只缓存本项目需要的字段。

`media_cache_index` 额外保存 `is_available`、`sync_generation`、`last_seen_at`；
`media_sync_state` 以 `library_id` 为主键保存状态、任务 ID、处理进度、最近开始/成功时间和
脱敏错误；`media_refresh_target` 以库主键防止重叠 generation 并发。Jellyfin 凭据、原始
traceback 和完整上游数据库不得写入这些表。

## 8. media_id

客户端不可依赖真实路径。

推荐：

- 优先保存 Jellyfin Item ID 作为 `jellyfin_id`
- 另生成本项目稳定 `media_id`
- 指纹可由 canonical path + size + modified_time 生成 hash
- 文件迁移/重扫时允许重新关联

## 9. 缓存目录

绝对禁止缓存污染媒体目录。

默认 Windows 数据根：

`%ProgramData%\MediaReview\`

结构：

```text
MediaReview/
  config/
    config.json
  database/
    mediareview.db
  cache/
    thumbnails/
    sprites/
    previews/
    temp/
  logs/
  runtime/
  backups/
```

如果用户自定义数据盘：

`D:\MediaReviewData\`

仍使用完全相同子目录。

缓存分片：

```text
cache/sprites/a8/a8f37291.../
  sprite.webp
  sprite.json
  cover.webp
```

## 10. Sprite manifest

```json
{
  "schema_version": 1,
  "media_id": "...",
  "source_size": 123,
  "source_modified": 123456,
  "duration_ms": 182400,
  "frame_count": 24,
  "columns": 6,
  "rows": 4,
  "tile_width": 320,
  "tile_height": 180,
  "format": "webp"
}
```

## 11. 雪碧图策略

建议自适应：

- < 30 秒：10–12 帧
- 30 秒–2 分钟：16–20 帧
- 2–10 分钟：24–30 帧
- 10–60 分钟：30–40 帧
- > 60 分钟：40–60 帧

统一输出 WebP。

生成必须：

- 后台异步
- 可取消
- 可查询进度
- 原视频优先级高于雪碧图任务
- 缓存失效条件至少包括 source_size / source_modified

## 12. 删除

永久删除逻辑：

- 客户端只提交 media_id
- 服务端重新解析当前真实对象
- 检查仍存在
- 检查属于允许管理的媒体库
- 检查在 delete_queue
- 最终 commit 请求包含确认 token / nonce
- 单条执行
- 每条写 audit_log
- 失败继续下一条
- 返回 success / failed 列表

## 13. 安全

个人局域网也要有最基本安全：

- 配对 token
- API token 存安全存储
- 非配对设备不可调用危险 API
- CORS 最小化
- 管理后台默认只允许本机或已配对局域网
- 删除 API 需要额外权限检查
