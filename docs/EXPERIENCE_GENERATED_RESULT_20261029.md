# 删除可靠性、界面体验与生图接入准备结果

日期：2026-10-29。源码目标Server1.6.4/数据库0018、Android Alpha22/code29。当前尚未构建本轮发布包、未部署或安装；生产仍沿用上一轮版本。

## 改动与验证边界

- 删除：同步耗时工作移出事件循环；数据库旁OS锁隔离执行与中断恢复；nonce保存所属设备和逐项可信结果。响应丢失后只查询原批次，不再次删除。异常发生在物理删除后且缺少可信结果时保持unknown，不计入确定释放空间。0018仅增加可空回执列，旧记录不伪造历史结果。
- Android：提交前保存按服务器及凭据隔离的nonce；最多4次退避核对，可前台恢复。完成、未确认、尚未提交分别表达；旧服务不冒充支持查询。用户看过结果后才清理本地批次。
- UI：待删除与相册区分加载中、失败和真实空列表；删除结果未知时不以空列表暗示成功；相册和删除页补底部安全区；长目录路径两行省略。首页、整理页继续复用已有局部错误及重试。仅静态检查与编译，未做手机或模拟器视觉验收。
- 生图：新增与批阅同级入口、独立加密连接配置、能力状态、可空详情模型、分页增量控制器及提示词版本评分合同。同ID原位更新、新图队尾追加、空增量不清空、游标失效可重新核对。总分和可选7维均1至5，未知版本须明确整体评分，未知归属禁止评分。
- 生图HTTP合同尚未冻结，生产绑定明确返回能力不可用；不猜测端点、不读取真实图片、不提交评分。图片访问准备仅接受独立桥同源并禁止重定向，不复用视频服务凭据。NSFW未修改。

## 已执行验证（不合并为单轮全绿）

命令在仓库根运行，Python使用server/.venv/Scripts/python.exe，PYTHONPATH指向server；pytest basetemp均为review_meta/experience-20261028子目录。

- pytest test_delete_result_recovery.py、test_delete_nonce_11.py、test_delete_receipt_8i.py：前轮定向通过，证据receipt-safety.log；跨进程锁、迁移和配对归属补测7项通过，证据lock-auth-migration.log。
- pytest test_delete_result_recovery.py test_native_sources.py test_health_contract_8d1.py -k 'delete or recovery or health or capabilities'：30通过、1跳过、2失败。新异常回执回归通过；两项失败分别为旧版本断言与临时SQLite无法打开。
- 修正版版本一致性/health测试7通过；将TEMP/TMP显式指向已有临时目录后，原失败的本地快照外nonce安全用例单独通过。日志scan-recheck.log、native-temp-fixed.log保留失败与复测证据。
- Gradle :app:testDebugUnitTest，过滤Generated*Test、DeleteRecoveryTest、DeleteQueueViewModelTest、V2ServerOrganizeRepositoryContractTest、ReleaseNotes*Test：69通过，BUILD SUCCESSFUL。包含分页、位置、游标失效、评分边界、同源约束、批次持久恢复与来源隔离；android-targeted-green.log。
- 未跑全量pytest/JVM/connected，不连接手机或模拟器，不追加真实删除。现有无关弃用warning未修改。

## 关键文件与剩余交付

服务端：api/v1/delete_queue.py、services/delete_queue.py、delete_execution.py、db/models.py、0018_delete_receipt.py。Android：organize/delete、V2ServerOrganizeRepository、feature/v2/nsfw、MediaNavigator/V2MainScreen、AlbumScreen、ReviewScopeSheet。具体协议与回滚边界见DELETE_RESULT_API.md；上游需求仍见NSFW_MOBILE_API_REQUEST_V2.md。

尚待：一次发布构建、签名/版本/完整性核验、唯一发布入口更新、提交push与最终逐条审查。网络故障、进程中断仍可能导致结果未知；本轮避免把未知说成失败或成功，不承诺永不发生异常。真实设备视觉、家庭网络稳定性、生图真实HTTP联通均未验证。
