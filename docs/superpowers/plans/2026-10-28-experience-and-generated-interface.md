# 删除可靠性与生图接口准备执行计划

执行方式：按executing-plans串行落实；用户已批准设计。所有业务回归使用隔离数据，不连接手机、不操作真实媒体、不改NSFW。

目标：删除期间正常响应、超时可查可信结果；关键界面状态清晰；生图一级入口和客户端接口准备就绪。

架构：复用FastAPI/SQLAlchemy/SQLite与Android Compose/Retrofit/现有队列。新增能力可探测，旧客户端兼容。不建立新服务。

## 任务1：服务端删除执行与持久回执
- [x] 复用test_delete_nonce_11的临时文件/数据库，新增test_delete_result_recovery.py，先复现慢删除阻塞健康请求及回执不可查询。
- [x] 修改app/services/delete_queue.py和api/v1/delete_queue.py；执行单元独立会话，明确正在执行与中断恢复互斥，不能只改async为def便交付。
- [x] 在models.py与下一顺序Alembic迁移中保存批次所属设备和逐项可信结果、字节数；物理删除确认与结果持久化的崩溃窗口必须保持未知、不推断成功。
- [x] 添加认证/所属设备检查的结果查询合同；nonce重放仍不重新执行。并发与重启恢复仅查询/收敛原批次。
- [x] 定向运行新回归、nonce/receipt/local-delete路径安全相关用例，测试迁移升级与拒绝危险回滚；记录命令与真实结果。

## 任务2：Android删除恢复与UI
- [x] ApiModels/MediaReviewApi/V2ServerOrganizeRepository加入可探测结果查询；保存待核对批次与服务器身份，不跨源查错批次。
- [x] DeleteQueueViewModel新增有界核对状态与前台恢复；补响应丢失、完成回执、未知、旧服务、失败和切换来源测试。
- [x] DeleteQueueScreen/DeleteResultSheet完善处理中/核对中/失败与历史记录展示、底部安全区和大字体布局。
- [x] 检查HomeScreen、相册、ReviewScopeSheet、OrganizePage已有状态复用与文字布局，实施直接相关修正；记录静态检查与渲染检查的区别。

## 任务3：生图接口与独立入口
- [x] feature/v2/nsfw新增Repository合同、能力/连接/详情/评分模型和增量控制器，复用GeneratedImageQueue；先写定向JVM回归。
- [x] 独立凭据/连接配置与同源图片校验；上游未冻结HTTP合同前默认能力不可用，禁止假端点自动写生产。
- [x] V2MainScreen增加已批准的生图审核一级入口，信息面板展示可空字段、能力说明与局部错误；新图不打断浏览，评分绑定版本图组。
- [x] 验证分页/去重/原位更新/空增量/游标失效/评分对象隔离/连接与凭据边界；测试资源不进入Release。

## 任务4：收口
- [x] 完成相关JVM和编译；按需要只构建一次正式APK与Server包，更新版本和唯一发布入口。未部署版本不得写成生产已修复。
- [x] 更新PROJECT_STATUS、DEV_LOG及简短结果报告；commit/push，逐条核对原设计完成情况，剩余真实设备/上游联通限制明确列出。

验证边界：UI为静态检查与编译，未渲染；详情为可空领域模型，wire未知字段映射等待上游冻结，不声称HTTP已集成。分轮测试、产物和限制详见EXPERIENCE_GENERATED_RESULT_20261029.md及EXPERIENCE_COMPLETION_AUDIT.md。

