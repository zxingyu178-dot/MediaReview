# 生成图片手机审核接入方案 v1（待实施）

日期：2026-10-28。范围：需求与接口调查、开发方案；本轮未修改业务代码、启动服务、配对或写入审核状态。

## 1. 核对后的基线

- MediaReview当前分支`feature/mediareview-local-only`，HEAD `529c7a8`，工作区调查时干净；当前独立发布APK为Alpha20/code27，构建源码`d0ba539`，Server1.6.3/Contract2。以CURRENT_RELEASE和PROJECT_STATUS为准，不能从Server包内附的旧Alpha18恢复客户端。
- NSFW开发目录`projects/nsfw-studio-v2`（main）与部署目录`projects/nsfw-studio-v2-app`（deploy/app-instance）调查时均为`6ad633a`，工作区干净。项目规则记载v0.36.1工作流更新；frontend/package.json及backend包常量仍为0.36.0，存在版本标记差异，不能仅凭某个版本字符串决定部署。未探测运行实例，实施前再次核对health、配置与已登记启动入口。
- NSFW审核桥已有实现，不只是占位接口：`backend/app/api/mobile/{system,pairing,images,review}.py`、`image_review_session_service.py`。冻结协议见`docs/integration/MEDIAREVIEW_NSFW_CONTRACT_V1.md`、`openapi-mobile-review.json`和fixtures。
- 旧`MEDIAREVIEW_COMPATIBILITY_AUDIT.md`锁定Alpha9/ba19d01，属于历史材料。Android实施指南里的全局DataMode建议仅作参考，不直接替换Alpha20的数据源路由。
- NSFW项目要求外部Agent按黑盒契约接入，不能改其后端、前端、脚本、配置或数据库。本方案仅修改MediaReview；确需上游修复时交维护者处理。

## 2. 推荐架构与备选

推荐手机直接连接现有Mobile Review Bridge（8767），新增独立图片审核功能。MediaReview Server（8766）继续服务原媒体库，Studio桌面端（8000）保持loopback。不创建第三套服务器，不让手机直连ComfyUI。

备选一是把Studio输出文件夹登记成普通媒体源：能看图，却缺少生成任务、审核状态和正确结果回传，不能满足本需求。备选二是由MediaReview Server代理审核桥：多一跳和一套凭据/错误处理，本期没有必要。首期选直接接入。

状态归Studio权威管理，手机只保留连接信息、有限分页窗口、恢复标识和短期未确认请求信息。Studio生图与入库逻辑不变。

## 3. 手机流程和交互

- 在现有“批阅”入口提供“媒体库批阅 / 生成图片审核”选择；保留原媒体库连接和入口，不用全局NSFW模式替换首页、收藏、整理等路由。
- 首次进入生成图审核：输入电脑地址，默认8767；检查产品/合同/能力后输入桌面展示的六位配对码。配对独立保存，不覆盖MediaReview的token或服务器地址。
- 首期默认待审核NEW，支持新到旧/旧到新；可选择KEEP/REJECT状态建立复核会话。更换范围需明确新建会话，优先恢复原会话，不能每次进入都重新开始。
- 图片全屏显示，左右翻图，双指/双击缩放，缩放期间优先平移图片；轻触隐藏/显示工具栏。图片审核与视频上下换片是不同模式，页内提示交互，不引入滑动保留或滑动驳回。
- 底栏：保留、驳回、收藏、恢复待审；顶栏：当前位置与已审/总数。仅保留/驳回成功后自动到下一项；收藏、恢复待审留在当前项，避免误触连审。翻页本身不算审核。
- “驳回”只是REJECT，文件保留，不进入MediaReview待删除队列；永久清理由Studio桌面既有流程负责。首期不做批量永久删除。
- 展示合同已有的尺寸、时间、seed、kind、job_id。当前DTO没有完整提示词/模型信息，首期不承诺提示词编辑、模型对比、重新生图或任务列表筛选。按任务筛选有服务端内部支持，但需维护者确认冻结合同及任务发现入口后再开放。
- 新生成图片不会自动插入冻结会话；界面说明“当前批次为固定快照”，完成或主动刷新后明确创建下一批，避免数量和位置跳变。

## 4. API与状态一致性

| 功能 | 现有接口与客户端要求 |
| --- | --- |
| 能力检查 | GET /mobile/system/health，product=nsfw-studio、支持Contract1及image_review/review_session等必需能力；未知协议按兼容表处理，不照搬MediaReview Contract2门槛 |
| 配对 | GET /mobile/pairing/status，POST /mobile/pairing/verify；installation_id稳定，token独立加密落盘；桌面8000生成配对码，手机不调用管理接口 |
| 恢复/新建 | GET /mobile/review/sessions/latest，仅明确404 REVIEW_SESSION_NOT_FOUND才新建POST /mobile/review/sessions；401/5xx/超时不能当成无会话；COMPLETED单独呈现完成状态 |
| 队列 | GET /mobile/review/sessions/{id}/queue，page_size=50；按绝对index合并定位，恢复到目标页，有界双向窗口，保留空洞/缺失项诊断，不静默压缩索引 |
| 审核 | POST /mobile/review/sessions/{id}/decision，携带item_index、expected_status和expected_updated_at；仅以响应image/session更新状态和计数 |
| 位置/结束 | POST /position防抖并串行保留最新位置；POST /complete返回completed=false时展示剩余项，不能强制显示完成 |
| 图像 | thumbnail/preview/original均走8767 Bearer请求，返回二进制；图片错误仍可能为JSON envelope，需要区分 |

同一会话写入采用串行队列，按钮提交中禁用重复操作；异步响应绑定服务器身份、会话ID和图片/index，不能覆盖已经切换的新来源。多个设备可能操作同一最近会话：当前latest实现未按设备隔离，因此不宣称支持独立多人进度。

409时采用服务端权威image，并重新读取相关队列/会话状态，提示已被其他端修改，不自动覆盖。写请求超时属于“结果未确认”：先重读对应队列状态再判断，不直接自动重发或自动跳下一张；桥没有公开幂等键或操作回执，本期不实现离线批量审核写回。

收藏请求action省略、只传favorite；实现中favorite=true和false都会令该项seen=true，必须按服务端计数呈现。“恢复待审”只发RESET，不顺带提交favorite=false（否则可能又被标为seen）。恢复待审不等于通用撤销，首期不做会覆盖桌面新状态的历史撤销。

## 5. 图片加载和隔离

复用Alpha20的Coil/ZoomableImage能力；不复制整套图片缓存框架、不复用视频播放器或删除控制器。审核默认1536预览，列表使用512缩略图；当前图片主动放大再取原图，相邻仅预取少量预览，快速翻页取消过期请求，限制原图并发与解码尺寸。

缓存身份包含Studio服务器、设备/配对代际、image_id、资源档位和内容版本；凭据不进入URL或普通日志。退出/撤销配对后停止访问原来源缓存。仅向已配对的同源Bridge发送Authorization，拒绝跨源图片URL、携带凭据的重定向，避免图片地址把token带到其他服务。

合同描述ETag/If-None-Match；当前图片路由返回FileResponse及ETag，但未看到显式条件请求处理或304用例。实施时用实际HTTP验证，不能把“有ETag头”写成“已验证304命中”。若上游未实现，先保证本地有界缓存正确，记录上游改进项。

## 6. 分阶段交付与定向验证

1. **接入与配对**：新增data/nsfw协议DTO、独立ProfileStore/凭据命名空间、API和连接状态；复用已有CredentialCipher。以冻结fixtures验证JSON、201/404/401、错误产品、错误端口、失效配对及凭据隔离。交付可连接的功能骨架。
2. **图片审核闭环**：新增review/nsfw的Repository/ViewModel/Screen，增加批阅来源选择；完成恢复、分页、缩放、四项操作、冲突/超时恢复、位置保存和完成确认。复用现有底栏/图片组件，不扩展全局MediaRepository为万能接口。
3. **模拟器端到端与交付**：用现有MockWebServer及合成图片验证成功、缺图、慢图、401、409、结果已落库但响应丢失、快速翻页和大队列恢复；经维护者允许的隔离测试环境验证真实Bridge。禁止以生产图库试验REJECT/RESET写入。

无手机时模拟器可通过10.0.2.2访问电脑8767；这证明电脑到模拟器的接口链路，不等同家庭Wi-Fi、防火墙、真实手机内存/缩放手感验收。生产Bridge不在线时不擅自启动，后续使用既有ControlHub生命周期入口。若需临时受控服务或合同变更，先按登记/维护者流程处理。

每阶段只跑相关JVM、必要编译及少量Compose/HTTP集成用例，串行运行重型任务；补原媒体库/视频批阅入口的短烟测。最后构建一次同证书APK、版本按当时CURRENT_RELEASE递增（若基线未变为Alpha21/code28）；不预先修改发布指针或升级生产。

验收要求：配对来源互不污染；预览和原图可显示；KEEP/REJECT/收藏/RESET手机与Studio权威状态一致；翻页不误判已审；断线/重启恢复正确位置；409/超时不虚报成功、不重复决策；大量图片按页读取；原媒体库功能可继续使用。交付简报、定向测试证据、Git SHA/push状态和独立APK。真机/HomeLAN未验证需单独标注。

建议先完成“待审图片→保留/驳回→桌面状态同步”这一条核心链路，再开放更多筛选和生成操作。预计3个开发检查点，工期取决于隔离Bridge联调与上游合同差异，不承诺一次开发即生产验收通过。
