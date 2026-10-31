# MediaReview 1.6.5 升级与回滚

目标Server1.6.5/Contract2/数据库0018 + Alpha28/code35；产物路径和哈希以已核验的deploy_handoff/CURRENT_RELEASE.json为准。准备包不代表已部署。

- 升级前经ControlHub停服，确认既有服务及端口已停止；备份有效程序、配置、配对和全部活动数据，保留原备份，不嵌套复制历史备份。
- 只使用manifest核验通过的程序目录；禁止双击旧EXE、另建交互实例或绕开ControlHub生命周期。
- 0018为delete_commit_nonce增加所属设备、回执状态及结果列；旧nonce不伪造回执。生产升级前应核对未完成删除，不能把清空列表当作处理完成。
- 有running批次时迁移降级拒绝。回滚优先恢复升级前一致的程序与数据备份；直接降级会丢失新增回执，不能只覆盖旧EXE。0017的queue_token安全约束仍保留。
- 物理删除不可通过恢复数据库撤销。恢复旧备份后可能包含已不存在文件的索引，应先只读核对，不重放旧删除请求。
- 本地安全删除仍要求Windows10 1709以上；本轮验证只使用临时可丢弃样本，不修改真实媒体、系统策略、服务身份或端口。

实际部署、备份和运行结果见docs/PROJECT_STATUS.md。本轮未执行生产升级或回滚。

Alpha28为独立客户端更新；此前已生成的Server1.6.5 ZIP内仍为Alpha27，原ZIP/manifest/哈希保持不变，本轮不重建EXE或重打Server包。手机安装使用CURRENT_RELEASE的apk字段指向的独立APK，不从历史Server包另取旧APK。
