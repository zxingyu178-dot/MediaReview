# Server 1.6.6 删除定向验收

## 冻结程序验证

复用既有1.6.6 EXE，SHA256 `38068db4fbccf25f93f1cd0675f101df7b204abd880b3da8a0fc2dfa681a777a`，未重新打包。一次性脚本 `temp/verify_delete166_binary.py` 与结果 `review_meta/delete166-binary-result.json` 留在本地忽略目录；所有文件、数据库、配置均在新建隔离目录。启动后先核对监听进程确为该EXE，才发送写请求；仅回环临时端口、禁用发现广播和配对要求，未访问生产删除接口。

|场景|实测结果|
|---|---|
|正常删除|入队与prepare不删文件；commit后样本消失，成功1、释放17字节；重复入队409|
|重复nonce|409 DELETE_ALREADY_PROCESSED，原回执不变；同路径新建替代文件保留，无第二条成功审计|
|文件缺失|prepare后移除自有样本，commit缺失1、成功0、释放0|
|文件占用|Windows不共享删除句柄，失败1、释放0，file_in_use；文件保留，失败项不自动重试|
|越界索引|仅篡改隔离数据库为../outside.jpg，path_rejected、释放0；根内样本与根外隔离哨兵均保留|

共5场景通过；审计success/missing/file_in_use/path_rejected各1。脚本最终终止自己的进程并确认端口释放，子进程退出码1，不代表正常服务退出验证。认证、手机、家庭局域网、崩溃后未知结果恢复均未覆盖；没有删除真实媒体或改生产队列。

## 首轮失败与源码诊断修复

首轮扫描即失败，未执行删除。隔离数据库副本复现SQLAlchemy OperationalError，SQLite无法打开数据库文件；测试进程继承的TEMP/TMP不可写。只为该进程设置自有可写临时目录后，同一诊断扫描及上述EXE验证通过；没有修改用户或系统环境变量，不能据首轮故障断言生产打包有问题。

同时确认源码将数据库异常统一记录为local_source_unavailable。最小修复：native_sources按SQLAlchemyError记录local_index_write_failed，日志只写稳定码和异常类型，不写SQL、路径、参数；tasks返回“媒体索引写入失败，请查看电脑端诊断”。保留扫描算法、离线标记、原索引可用性和删除安全逻辑。故障记录仍需数据库可写；数据库持续完全不可写时无法保证落库，不宣称解决该边界。

修改生产文件：`server/app/services/native_sources.py`、`server/app/services/tasks.py`；回归：`server/tests/test_native_sources.py`。

在当前工作树server目录使用项目既有venv、进程级PYTHONPATH及可写TEMP/TMP：

```text
python -m pytest tests/test_native_sources.py::test_scan_index_write_failure_is_not_source_offline -q
```

修复前1失败，实际错误码为local_source_unavailable。

```text
python -m pytest tests/test_native_sources.py::test_scan_index_write_failure_is_not_source_offline tests/test_native_sources.py::test_scan_stable_ids_incremental_and_offline_keeps_favorite -q
git diff --check
```

修复后2通过、diff检查通过。既有Starlette/httpx弃用警告保留，未扩大处理；未跑全套pytest/Gradle/connected，未构建APK或新EXE。诊断修复仅在源码，冻结EXE与生产仍1.6.6旧诊断行为。

阶段结论：有条件合格。删除二进制定向证据成立，真实设备及认证/未知结果恢复边界仍待补验；不声明PRODUCTION_READY或REAL_LAN_PASS。

## 追加：持久化中断边界后的真实EXE重启

没有重复上述5场景。一次性脚本`temp/verify_delete166_restart.py`复用原启动/隔离/身份检查代码；脚本SHA256为`ed37c8cb3eb20d9694aa69e3b1f154c00b6d3ebec71da7f914a346c0b7476d1d`，结果在本地忽略文件`review_meta/delete166-restart-result.json`。运行命令为项目既有venv的`python temp/verify_delete166_restart.py`，退出0。

两个场景均先由实际EXE扫描临时样本、入队并prepare，再终止自己的实例，在离线隔离数据库构造`nonce.used=1/receipt_state=running`与队列`committing`。分别保留原文件、移除自己的样本；每次重启同一冻结EXE并重新核对监听所有者后才查询/重试：

- 原文件存在：文件字节保持不变，队列failed/interrupted_not_deleted，审计failed:interrupted_not_deleted一条。
- 原文件缺失：队列与媒体元数据收敛，审计recovered_deleted一条。
- 两次批次查询均为`state=unknown,result=null,confirmed_partial_result=null`；原nonce重发均409，不编造成功数或释放字节。

2场景通过，验证了真实程序启动恢复消费这些持久化状态的行为；**不是实际删除调用中途断电/崩溃注入**，也不证明手机丢响应后的完整恢复体验。认证依然未测。结束后隔离端口无监听、生产Server1.6.6健康且服务Running；没有修改或重启生产实例。测试脚本终止自身EXE的退出码仍1，不代表正常服务退出。
