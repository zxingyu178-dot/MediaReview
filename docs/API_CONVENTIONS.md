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

返回 task_id，并由客户端轮询或 SSE 获取状态。
