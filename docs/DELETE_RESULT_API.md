# 删除结果恢复合同（开发中，尚未部署）

本合同扩展现有两阶段删除。旧commit接口保持原返回结构，重复提交仍返回409；客户端不得为了找回结果再次执行commit。

## 结果查询

`GET /api/v1/delete-queue/commit/{nonce}/result`，必须认证且与prepare设备一致。未认证401；其他设备或不存在的批次404。设备被撤销后不可读取。旧迁移记录没有设备归属，不开放查询、不伪造历史回执。

- `prepared`：批次尚未获得执行权，查询不会启动它。
- `running`：正在处理，稍后只读查询；列表刷新不得执行该批次的中断恢复。
- `completed`：`result`与原commit结果结构一致，含outcome、deleted_count、deleted_bytes、missing_count、failed_count。
- `unknown`：执行中断或不能确认完整结果；`result=null`，不得按队列消失或预计大小推算成功。

未完成时，`confirmed_partial_result`可包含此前已经持久化的逐项事实，不能作为整批完成结果。客户端首版明确显示未确认，不将部分数据当作完整统计。

执行/中断恢复通过数据库旁的OS文件锁互斥。锁由OS在进程终止后释放；只读结果查询不持锁。每请求独立SQLAlchemy会话；同步路由由框架线程池执行，避免阻塞事件循环。

## 数据库0018

给delete_commit_nonce增加nullable owner_device_id、receipt_state、result_json，旧记录原样保留。无需重新索引媒体。所有物理删除仍通过现有路径与文件身份检查，回执查询不会触发物理删除。

正式升级前经ControlHub停服，备份旧程序及完整数据（包括一致SQLite备份），再由新版执行迁移。当前开发仅迁移隔离测试库，生产仍0017/Server1.6.3。存在running批次时downgrade拒绝；结果已核对且有备份才可降级。降级会丢弃新增回执字段；回滚到旧程序应优先恢复升级前一致备份，不能把旧EXE直接覆盖到新库后宣称支持。

客户端待核对nonce要绑定服务器与配对身份，切换来源不得向新来源查询旧批次。确认提交前持久保存，完成结果由用户读过后再清除。离线采用有界退避，恢复前台可继续核对；未知保持显式提示，不自动重新prepare/commit。
