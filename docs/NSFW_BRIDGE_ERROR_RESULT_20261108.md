# 审核桥错误状态修复与部署

## 已交付

NSFW Studio 的审核桥启动失败后，后续状态查询曾将 ERROR 覆盖成 STOPPED，桌面无法持续显示故障。修复仅保留无自有进程、无健康响应时已有的 ERROR；主动停止清除错误，再次启动和外部健康桥仍按实际探测更新。未改变端口、配对、审核、评分或媒体数据协议，手机继续使用 Alpha34。

- 源码 dcee897；[PR36](https://github.com/zxingyu178-dot/nsfw-studio-v2/pull/36) 合并至 develop，main/develop/日用实例统一 b06b8358cc2212e2b0198f7de9619e85514529ba。
- 修改 NSFW 文件：backend/app/services/mobile_review_runtime_service.py、tests/backend/test_mobile_runtime.py、docs/MOBILE_BRIDGE_AUTOSTART.md。
- 复用本次已完成的最小回归：原实现新增用例失败；`python -m pytest tests/backend/test_mobile_runtime.py tests/backend/test_mobile_bridge_lifespan.py` 修复后17通过，1既有弃用警告。未重复本地全量。PR CI42478862925、develop CI42479173569、main CI42479509138 的前后端检查均成功。
- 生产切换前确认队列无运行/等待/暂停任务。程序、私有配置、原前端共831文件受保护备份，ZIP与源逐文件哈希一致；既有 backup_db 实现创建SQLite快照，quick_check=ok。一次性核对脚本初用错误表名，在切换前按实际模型修正并复核成功；未覆盖已有备份。
- 正式 scripts/deploy_app_dist.ps1 执行成功，Vite构建11.04秒；scripts/check_app_dist.ps1 全部通过。开发根的两个既有未跟踪文档保留，已跟踪源码无差异，未纳入构建。
- 官方 ControlHub start 返回 succeeded。Studio健康ok/0.37.0；8877为RUNNING、owned=true并随Studio自动恢复；数据库配对记录一致。ComfyUI监听进程前后相同，未停启ComfyUI。

## 不能忽略的运行异常

本次官方 ControlHub stop 返回 failed/adapter_failed，不能记为停服成功。项目 carrier 日志随后记录协作退出等待30秒超时并自行执行进程树兜底，最终退出0；独立确认计划任务Ready、8000和8877释放、ControlHub status=offline后才替换程序。未另行强杀或绕过官方入口。本轮未修改carrier或ControlHub，**优雅停止问题尚未修复**；启动时恢复流程有历史孤儿槽位核对日志，但启动后队列仍为空。不能称整个生命周期验收全绿。

后续优先定位连接等待/退出超时原因，核对carrier的兜底与生命周期约定；不直接通过缩短超时或扩大杀进程范围掩盖问题。

## 验证边界

本轮没有手机/HomeLAN复测，没有故意破坏生产审核桥制造启动失败。错误状态恢复由隔离回归证明；生产证据仅覆盖部署身份、健康、自有桥自动恢复、配对保留和ComfyUI未停启。私有收据在NSFW开发工作树temp/bridge-error-deploy-private.json，程序与数据快照保留用于恢复，不公开配置或令牌。
