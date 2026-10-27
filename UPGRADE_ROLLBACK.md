# MediaReview 1.6.3 升级与回滚

当前唯一发布目标：Server1.6.3/Contract2/0017 + 独立Alpha19/code26 APK；具体文件和哈希见 `deploy_handoff/CURRENT_RELEASE.json`。Server包沿用既有1.6.3，包内Alpha18不是当前手机安装入口；本轮只覆盖升级Android，不替换生产Server。

- 升级前通过ControlHub停止同一服务身份，备份有效程序、配置、配对数据库和全部活动数据；备份放在受保护目录，校验完整性。历史备份及`.old-*`不嵌套复制，但原件保留。
- 新程序只从已验证manifest指定的目录进入stage；确认路径无链接/重解析点、完整文件集合与哈希一致后切换。不得以新包目录的名称代替内容验证。
- 服务仅通过ControlHub启动，health与EXE哈希必须匹配1.6.3。失败时记录阶段并停在安全状态；不得启动某个“看起来能用”的旧EXE。
- 回滚必须恢复成对的程序和数据备份。数据库0017包含`delete_queue.queue_token`，不能只换回0016程序。存在`committing`/`delete_done`/`delete_pending`待恢复操作时，先用0017完成恢复与备份，再决定是否允许降级。
- 本地删除仍要求Windows10 1709以上的POSIX安全语义；不会退回延迟删除。验证只允许隔离可丢弃样本，本轮不删除真实媒体。
- 不更改服务身份、端口、系统策略或手机数据；不以SCM、底层Helper或平行计划任务替代ControlHub生命周期入口。旧版本历史流程不构成当前授权。

历史文档保存在 `docs/history/20261027-before-release-closure/`。当前授权、实际备份和运行结果见 `docs/PROJECT_STATUS.md`，尚无对应成功记录时不能宣称已升级或可回滚。
