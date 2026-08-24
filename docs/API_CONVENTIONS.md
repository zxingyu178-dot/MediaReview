# API 约定

## URL

统一：

`/api/v1/...`

使用复数资源名。

## 分页

请求：

- `page`
- `page_size`

或 cursor，项目统一一种后不得混用。

## 排序

`sort_by`
`sort_order=asc|desc`

服务端使用白名单映射，禁止直接拼 SQL 字段。

媒体列表支持 `name|created|size|duration|resolution|random`。缺失排序值在升序和降序中
都置于末尾。`random` 接受可选 `random_seed`；缺省为 UTC 日期，同一 seed 跨页稳定，
禁止使用 `ORDER BY RANDOM()`。

## 时间

统一 ISO-8601 UTC 输出。

## ID

客户端只使用：

- media_id
- session_id
- task_id
- duplicate_group_id

不得把 Windows 路径作为 API 主键。

## 危险操作

必须：

- 配对认证
- request_id
- audit log
- 二次确认字段
- 幂等保护

## 后台任务

耗时超过普通请求阈值的操作：

- 雪碧图批量生成
- 重复扫描
- 完整 Hash
- 大批删除
- 媒体索引刷新

返回 task_id，并由客户端轮询或 SSE 获取状态。

### 媒体列表同步元数据

`GET /api/v1/media` 保持 `items,total,page,page_size`，并增加 `sync`：

- `state`: `idle|pending|running|succeeded|failed|cancelled`
- `stale`: 旧缓存是否正等待刷新或因失败/取消而陈旧
- `task_id`, `processed`, `total`, `last_success_at`
- `message`: 脱敏中文状态消息

该 GET 只查询 SQLite。空缓存会幂等编排一次后台刷新并立即返回，不调用 Jellyfin
`/Items`。已有缓存即使同步 pending、running 或 failed 也立即返回。

### 主动刷新

`POST /api/v1/media/refresh` 请求体：

```json
{"library_ids": ["library-id"], "force": false}
```

`library_ids=null` 表示全部已选库；未知或未选 ID 返回 422。成功返回 HTTP 202 任务视图。
同一目标集合已有 pending/running 任务时，即使 `force=true` 也返回原任务。

### 通用任务接口

- `GET /api/v1/tasks`
- `GET /api/v1/tasks/{task_id}`
- `POST /api/v1/tasks/{task_id}/cancel`

三者都要求配对认证。取消 pending/running 幂等；运行中任务在分页边界协作退出。
任务视图不返回原始 params、result、上游异常、traceback、Token 或 API Key。
