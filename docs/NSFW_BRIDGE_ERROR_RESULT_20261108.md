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

## 后续隔离诊断：事件推送阻塞退出

2026-11-08已用真实 `job_events` 端点和 EventBroker、最小FastAPI生命周期、系统临时分配的回环端口复现，未启动完整业务App、未访问DataRoot或8000/8877/8188。日用实例与隔离解释器均Uvicorn0.54.0，日用Starlette1.7.0。

| 隔离请求 | 设置server.should_exit后等待1.2秒 | 生命周期清理 | 连接/任务/订阅 |
|---|---|---|---|
| 普通JSON响应已完成 | 已退出 | 已完成 | 0/0/0 |
| 保持真实SSE响应 | 仍运行 | 未开始 | 1/1/1 |
| 上述SSE客户端主动关闭后再等待3秒 | 已退出 | 已完成 | 订阅归零 |

源码顺序一致：Uvicorn先等活动请求，再发lifespan shutdown；端点无限循环只检查客户端断开，心跳不会结束响应；carrier没有Uvicorn graceful超时，30秒后走自带兜底。在lifespan finally里才关闭推送会过晚。本次隔离使用should_exit，未发送真实Windows信号；生产日志证明已收到关闭并等待连接，但不能据此排除其他活动请求也参与当次超时。

建议在现有服务启动封装的关闭前置步骤通知Broker，使SSE正常结束和退订，再让Uvicorn排空普通请求并进入Bridge/Worker清理。仅增加全请求强制取消时限会影响上传等请求，不作为默认方案。行为设计已提交用户选择，尚未实施；后续需覆盖多个连接、满队列、新订阅、重复关闭，以及实际Windows信号/总退出预算。独立只读审查确认该控制流；没有新增依赖、修改carrier、重启服务或声称该故障已修复。

## 用户选择后的候选实现（尚未部署）

用户已选“先正常关闭推送连接”。NSFW 958de0e、codex/graceful-event-shutdown、[PR37](https://github.com/zxingyu178-dot/nsfw-studio-v2/pull/37)：Broker内部结束标记覆盖空/满队列、重复关闭、新订阅和已排队发布；端点正常结束退订；同一Studio服务的app.runtime_server在Uvicorn排空前关Broker，不覆盖信号处理或全局取消普通请求。临时真实HTTP证明SSE先EOF、普通请求返回200、再进lifespan。

新增3项旧实现失败；实现后 `python -m pytest tests/backend/test_event_shutdown.py tests/backend/test_mobile_runtime.py tests/backend/test_mobile_bridge_lifespan.py` 20通过（31.42秒）；另轮既有SSE推送1通过（5.17秒），均1既有弃用警告。不合并为单轮21项。只读审查无阻塞项，未重复本地全量；慢客户端发送背压、普通长请求仍可能延迟退出。正式carrier仍走旧Uvicorn CLI，实际Windows信号/总退出预算及受管部署均未验证，下一步先解决入口来源与官方接入边界，不能只pull便宣称生效。

## Windows隔离信号补验与入口来源

NSFW候选最新08eb475，PR37最新CI进行中。隐藏自有控制台/进程组上的真实CTRL_BREAK单项最终通过（2.97秒），SSE EOF、lifespan完成、订阅0，信号至退出0.329秒；前轮3.46秒后因ready写入改原子替换而只重跑该项。退出码实测3、非0，日志明确完成应用清理；不归一化退出码，不代表正式carrier任务返回0。未加载正式业务App或访问媒体/DataRoot，未操作生产。

日用carrier与既有onboarding-staging源SHA256一致（ebf3891e846413ecb6b16ec7061d3c4d4d6d18f042ee23f9d2289c9f7d11aad0），无需复制重写框架。NSFW已提交docs/CARRIER_STUDIO_SERVER.patch，将唯一子进程命令转为app.runtime_server，git apply --check通过，尚未实际应用。保留既有ControlHub任务、端口和载体；正式切换须备份后受管验证并补新版独立handoff/官方提交，不改旧交接或自行审批。旧说明“实际Windows信号未验”现仅对正式业务实例成立，隔离样本已按本节补验。

## 验证边界

本轮没有手机/HomeLAN复测，没有故意破坏生产审核桥制造启动失败。错误状态恢复由隔离回归证明；生产证据仅覆盖部署身份、健康、自有桥自动恢复、配对保留和ComfyUI未停启。私有收据在NSFW开发工作树temp/bridge-error-deploy-private.json，程序与数据快照保留用于恢复，不公开配置或令牌。
