# V1 任务清单

## Foundation
- [x] 仓库初始化(2026-08-19,git 提交待环境可用)
- [x] Server scaffold
- [ ] Android scaffold(源码脚手架,0.5.0 起可编译/联调)
- [x] CI/lint/test(ruff + pytest 就绪)

## Android(0.5.0 首次编译通过)
- [x] Android 工程编译(assembleDebug + 单测)0.5.0
- [x] Server discovery(客户端 + 服务器应答端)0.5.0
- [x] Pairing 0.5.0
- [x] Server profile(首页)0.5.0
- [x] Library selection 0.5.0
- [x] Media wall + 封面大小 + 排序/筛选/搜索 0.5.0
- [x] 媒体墙设置持久化(DataStore:列数/排序/类型)0.6.0
- [x] 分页并发防护(request generation + Job cancel)0.6.0
- [x] 错误页重试按钮 0.6.0
- [x] 媒体库筛选入口 0.6.0
- [x] 自动加载下一页 0.6.0

## Android(0.6.0 Image Viewer + 雪碧图预览)
- [x] Image viewer(全屏大图,双击/双指缩放,平移)0.6.0
- [x] 媒体卡片点击:图片 → Image Viewer 0.6.0
- [x] Sprite preview(视频长按 + 横向滑动映射时间)0.6.0
- [x] 雪碧图未就绪自动触发生成 + 提示 0.6.0

## Android(0.7.0 Media3 播放器 + 批阅模式 + P0/P1)
- [x] 原图 API:MediaSummary 增加 original_url(图片原图,视频为 None)0.7.0
- [x] Image Viewer 改原图优先 + 平移边界约束(缩回 1x 归零)0.7.0
- [x] 导航重构:查看器/播放器路由只传 mediaId,目标页自行取详情 0.7.0
- [x] 可复用 Player Core(普通播放器 + 批阅共享 Media3 播放层,双实例 P0/P1)0.7.0
- [x] Media3 普通播放器(全屏 PlayerView + 播放/暂停/进度)0.7.0
- [x] 媒体墙视频单击 → 普通播放器 0.7.0
- [x] 批阅模式:竖屏 Pager + 视频/图片混合 + 横屏视频居中 + 右侧 ❤/🗑/⋯ 0.7.0
- [x] 批阅 P0/P1:页面停稳后才播放,下一条预加载,缓冲时限制 P1 0.7.0
- [x] 批阅 session_seen(什么都不操作也记录)+ 点赞写 SQLite + 待删除可撤销 0.7.0
- [x] 雪碧图生成低频轮询(自动进入 ready)+ 位图 LRU/数量上限 0.7.0
- [x] 服务端雪碧图整体最大尺寸上限 0.7.0

## Android(0.8.0 修正 + 喜欢/待删除/重复页)
- [x] P0:PlayerCore.playStream 初始化 listener(普通播放器首进即正确显示状态)0.8.0
- [x] P0:修复 P1 stale-ready(任何 stop 同步清空 ready,快速滑到已停止项重新 prepare)+ 槽位状态机测试 0.8.0
- [x] P0:Review 会话由服务端按已选库+sort/filter 构建,Android 只提交 source,队列分页支持数千/上万媒体 0.8.0
- [x] P0:图片页不向 ExoPlayer prepare 空 URL(进入图片停止当前视频/保持静默,正常预加载下一个视频)0.8.0
- [x] 小修:撤销待删除使用真实 lastDeletedMediaId(不依赖 pager index)0.8.0
- [x] 小修:点赞/删除 API 成功后才更新 UI;批阅启动恢复已有喜欢/待删除状态 0.8.0
- [x] 小修:ReviewViewModel.load() 异常捕获 + 重试;普通播放器进度改为可拖动 Slider 0.8.0
- [x] Jellyfin 播放进度上报闭环(服务端 progress API + Android 周期上报)0.8.0
- [x] 喜欢独立页面(可打开/取消喜欢)0.8.0
- [x] 待删除页面(数量/预计释放空间/单项恢复/最终删除二次确认+结果)0.8.0
- [x] 重复文件页面(完全重复 + 疑似重复,只读不自动删除)0.8.0
- [x] 批阅断点恢复(进入时恢复最近活动会话,可「新批阅」)0.8.0

## Android(0.9.0 断点恢复 + 播放器完整控制 + 稳定性)
- [x] 批阅断点恢复:Server current_index 随 position 更新;Android 只加载含目标绝对索引的分页并正确定位 0.9.0
- [x] Pager 边界保护:页数=已加载数,快速滑动不出现空白页(提前预取)0.9.0
- [x] 普通播放器进度上报读取实时状态(不缓存旧 playing)0.9.0
- [x] 退出/切走视频补最后一次 Jellyfin 进度上报 0.9.0
- [x] loadMore() try/finally 恢复 loadingMore 允许重试 0.9.0
- [x] dequeueDelete 返回真实成功状态,成功后才改 UI 0.9.0
- [x] 播放器完整控制:倍速/音量静音/画面比例/横竖屏 0.9.0

## Server(0.8.0 Review 断点 + Web 管理后台 + 诊断)
- [x] 批阅断点:POST /review/sessions/{id}/position 更新 current_index;新会话自动完成旧 active 0.8.0
- [x] 深度恢复测试(恢复到第 637/1000 条)0.8.0
- [x] Web 管理后台 /admin(状态/配对码/缓存/诊断导出)0.8.0
- [x] 诊断导出 API(日志+脱敏配置+表计数 ZIP)0.8.0

## Server(0.8.1 Stage15: 安全强化 + 批阅体验)
- [x] System/Admin 敏感接口改为 localhost-or-auth(本机放行,局域网需 token)0.8.1
- [x] 诊断日志敏感字段脱敏(token/配对码/api_key)0.8.1
- [x] 完全重复文件在默认批阅队列只保留一个代表项(不自动删除任何文件)0.8.1
- [x] 媒体排序补 resolution/random 字段 + exclude_favorites(未点赞筛选)0.8.1

## Server(1.1.0 Task 1: 数据库优先媒体索引与后台同步)
- [x] `GET /media` 改为 SQLite count/filter/search/order/page，零 Jellyfin `/Items` 调用
- [x] `MediaCacheIndex` availability/generation/last_seen + `MediaSyncState` + Alembic 0010 无损升级
- [x] 10 万条索引 50 项分页测试机 `< 1s`，生产目标 `< 250ms`
- [x] `media_refresh` 每批 500 条 bulk upsert，完整成功后才隐藏未见项目
- [x] 同步失败/取消保留旧缓存并返回脱敏中文状态
- [x] `POST /media/refresh` 幂等刷新 + `/tasks` 查询/详情/协作取消（配对认证）

## Server(1.1.0 Task 2: 批阅数据库建队与稳定分页)
- [x] `POST /review/sessions` 仅从已选库 SQLite 可用索引建队，不构造或访问 Jellyfin/httpx
- [x] SQL 筛选/搜索/稳定排序/完全重复代表项 + 单次 `INSERT ... SELECT` 队列写入
- [x] 随机会话固化 seed；旧 active 完成、新会话和队列写入同一事务并失败回滚
- [x] 队列使用 SQL `COUNT + OFFSET/LIMIT + JOIN`，缺失项不压缩绝对 index/total
- [x] 10 万条建队测试机 `< 5s`（严格 hash 合同后实测 1.212s；非生产 SLA）
- [x] current index、seen、position、advance、complete、latest-active 与 API envelope 回归兼容
- [x] C1：review/media 图片字段改配对认证相对代理 URL，server Jellyfin Key 不进入响应
- [x] I1：SQL exact 与 duplicate scanner 共享严格 64-hex full SHA-256 合同
- [x] M1：删除已脱离生产入口的 Python 队列去重 helper 与私有直测
- [x] C2：playback JSON/URL/header 不下发 server key；无凭据 direct URL 明示仍需 Jellyfin 认证
- [x] I1（二审）：SQLite text/字节长度/shared UDF 拒绝 NUL、BLOB、Unicode 等动态类型分叉
- [x] I2：图片上游禁用自动重定向，同源/跨源/链路本地/循环均不发起第二跳
- [x] M2：删除死 `_SORT_KEY_FN` / `_sort_items` 并更新图片代理注释
- [x] I3：Jellyfin HTTP(S) base URL 在配置边界严格规范化，拒绝 userinfo/query/fragment 等注入
- [x] I3：保留安全 base path、编码 Jellyfin ID，完整 playback JSON 不含 server key
- [x] M3：删除无调用 `build_jellyfin_client()`，统一使用 FastAPI async dependency
- [x] I4：url/api_key 构造与双向赋值交叉校验，host/path/percent/大小写均不得携带 server key
- [x] I4：Jellyfin client 与 playback 序列化终检，即使配置入口绕过也不返回含 key URL

## MediaReview(1.1.0 Task 3: 局域网 URL、发现与配对身份)
- [x] TCP 默认端口统一为 8766；UDP 35001 回复解析、来源 host、去重与 health 确认
- [x] 手动 hostname/IPv4/括号 IPv6/HTTP(S)/port 规范化与中文失败，手动入口始终保留
- [x] 可选 `jellyfin.client_url` 与 request-host 派生 client-facing playback URL
- [x] Android `MediaUrlResolver` 集中解析相对图片/雪碧图并拒绝不安全或凭据 URL
- [x] installation ID 先稳定持久化；同设备重复配对 upsert/rotate token/旧 token 失效
- [x] Alembic `0012_pairing_device_identity` 从 0010 链接，覆盖升级/去重/回滚与数据保留
- [x] token 迁移到 Android Keystore-backed AES-GCM；clear 保留 installation ID
- [x] repository/view-model 分离 MediaReview、Jellyfin、sync、authentication 状态
- [x] 保持 Task 2 图片代理、server-key 排除、禁视频代理与 Task 6 认证过渡合同

## MediaReview(1.1.0 Task 0A: 可移植阶段验收工具) — 完成
- [x] `scripts/build_review_handoff.py` 纯标准库构建器：`--stage/--name/--base`，输出 `review_handoff/MediaReview_Review_Stage-<id>_<timestamp>.zip`
- [x] 打包范围 = `git diff --name-only <base>`（基线到工作树、仅已跟踪文件）+ 固定状态文档 + `review_meta/` 证据
- [x] 只含已批准文本扩展名；禁止类别与超限文件排除并记录；`.env`/keystore 等疑似密钥路径非零失败
- [x] 缺少 REVIEW_SUMMARY/阶段结论/测试证据、无效或非祖先 base、ZIP 超限、git 不可用均 fail-closed
- [x] 15 个端到端测试（临时 Git 仓库真实运行）；RED 12 failed → GREEN 15 passed；全量 273 passed；ruff check/format 全绿

## MediaReview(1.1.0-alpha3 Task B: Paging 3 媒体墙、图片与雪碧图闭环) — 完成
- [x] Paging 3 依赖 + 不可变 `MediaQuery` + `MediaPagingSource`（键=页码、错误包装、取消上抛）
- [x] ViewModel `flatMapLatest` 新查询新 Pager + `cachedIn` 唯一页缓存 + revision 刷新门
- [x] MediaWallScreen 换 LazyPagingItems：骨架/空态/离线重试/追加失败就地重试，2-5 列与全部筛选保留
- [x] 文件夹辅助视图在媒体墙内（服务器 folder_id=SHA-256 前缀，响应无路径）；`GET /media/folders` + `folder_id` 筛选
- [x] 图片查看器：详情重试、离开取消、Coil 按视口解码（≥1px 兜底）、平移钳制回归
- [x] 雪碧图：ensure 202、进度里程碑 20/40/100、前后两处协作取消、终态 CAS、失败/取消中文文案与可重试
- [x] 100k 无 Jellyfin 扫描门禁（预迁移+预插种解耦；连续 3 次全过；单页 0.03-0.08s）
- [x] Server 282 passed + ruff 全绿；Android JVM 122/0 + 四目标 BUILD SUCCESSFUL（两轮）
- [x] 独立审查：第一轮 NOT CLEAN（100k flake + _loop 无保护 + W605）→ 修复 → 第二轮 CLEAN（0C/0I/6M；M-A 平局打破键为基线既有，M-B..M-F 记录在案）

## MediaReview(1.1.0-beta1 Task C: Direct Play 与单次 HLS 回退) — 完成

> 状态（2026-09-02，独立审查返修后）：**代码与审查门禁通过（CLEAN）**。首轮审查
> NOT CLEAN（1C/3I/6M）→ 修复 C1（Jellyfin /Auth/Keys 契约 app/AppName）、I1（批阅路径
> 注入设备凭据）、I2（撤销清除/撤销 key）、I3（迟到错误会话过滤）→ 复审 CLEAN。
> instrumentation 仍无设备执行；格式矩阵与真机 Direct<3s/HLS<8s 属 Task G。

- [x] 前置：review 会话平局打破键单调化（`fix(review): monotonic session ids`，关闭 Task B M-A）
- [x] Server 播放合同：`direct`/`fallback_hls`（{url, headers}）+ `resume_position_ms`；`stream_url` 一版兼容恒等于 direct.url
- [x] 设备级播放凭据：按设备签发/复用 Jellyfin 命名 key（`mediareview-<installation_id>`），value 只存服务端 paired_device
- [x] 迁移 `0013_device_playback_key` 从 0012 延伸，单一线性 head，upgrade/downgrade 对称
- [x] 凭据只在播放响应 headers `X-Emby-Token` 下发，绝不进入 URL/JSON/日志；支持按名幂等撤销
- [x] Direct/HLS URL 构造前后 server-key 排除终检；凭据签发失败 fail-closed（中文错误，不下发直连地址）
- [x] HLS 为 `master.m3u8` 最小转码集（h264+aac）；中间层不转发视频流
- [x] Android `PlaybackStateMachine` 纯转移：Direct 失败→恰好一次 HLS 回退→中文终态；取消/切换媒体重置回退配额
- [x] `PlayerCore` 单例 HttpDataSource.Factory 共享请求头；`playStream` 支持 headers + startPositionMs；错误 SharedFlow 上抛
- [x] `PlayerViewModel` 错误/成功 collector 驱动状态机；legacy stream_url 兼容；`usingFallback` 指示
- [x] 收口修复：`errorEventFor` 覆盖 Idle 态（数据源准备期失败）触发 HLS 回退，RED→GREEN（ErrorEventMappingTest 4 例）
- [x] 返修 C1：Jellyfin /Auth/Keys 契约改 `app`/`AppName` + 四处 mock + 锁定测试（真实服务器上 /playback 恒 500 的根因）
- [x] 返修 I1：批阅路径 `ReviewPlayable.headers` + `directPlaybackEndpoint` + `applyHttpHeaders` 注入设备凭据（ReviewPlayableEndpointTest 3 例）
- [x] 返修 I2：`pairing.revoke_device` 清列 + `POST /pairing/revoke` 经 optional_jellyfin_client 尽力撤销命名 key（HTTP 测试）
- [x] 返修 I3：`PlayerErrorEvent(mediaId, error)` + `isCurrentSessionError` 会话过滤（SessionErrorFilterTest 3 例）
- [x] Server focused 52 passed + 全量 pytest 通过 + ruff 全绿；Android JVM 142/0 + 四目标 BUILD SUCCESSFUL + lint 0 errors
- [x] 独立审查两轮：首轮 NOT CLEAN（1C/3I/6M）→ 返修 → 复审 CLEAN（`.superpowers/sdd/task-c-independent-review.md`）

## MediaReview(1.1.0-beta1 Task D: 批阅、收藏、安全删除与重复整理)

> 状态（2026-09-02）：Task D 全部完成。Server 侧随 `ca9f75a`（nonce 两阶段删除）与
> `8e824b7`（重复分组持久化后台任务）提交；Android 双栏对比 + 保留选择为 `afa6b0b`
> 提交；D7 全量门禁（Server 308 全过 + Android 四目标 BUILD SUCCESSFUL）+ 破坏安全
> 独立审查 CLEAN（0C/0I/4M）+ 验收 ZIP 完成。

- [x] D1 RED 测试基线：批阅 settled-only/P0/P1 带宽仲裁/绝对索引恢复/seen 唯一性、收藏/删除/撤销/进度幂等、nonce 过期/复用/篡改、逐项继续、文件身份复核与审计（既有 + 新增锁定测试）
- [x] D2 批阅窗口行为核查：混合图/视频、稳定 pager、P0 缓冲停 P1、横屏视频居中、删除失败停留当前项（既有实现核查无缺口）
- [x] D3 收藏一致性核查：Media/Player/Review/Favorites 经 revision 图与成功变更才更新（既有实现核查无缺口）
- [x] D4 两阶段永久删除：server 重解析媒体 ID、校验库/队列/指纹/文件身份、逐项独立执行并审计、拒绝客户端路径（`ca9f75a`）
- [x] D5 迁移 0014：down_revision=0012 单一线性头，delete_commit_nonce / duplicate_group / duplicate_group_member，覆盖升级/回滚/失败回滚/备份恢复（不重写 0012）
- [x] D6a 服务端重复分组持久化 + 后台任务（`8e824b7`）：exact=size+duration+分段 quick fingerprint+combined SHA-256；疑似=duration/size/resolution；任务暂停/继续/取消/进度；绝不自动删除
- [x] D6b Android 双栏对比 + 保留选择：扫描触发/轮询/暂停/继续/取消、分组列表、双栏对比、保留标记（本提交）
- [x] D6c 测试：DuplicatesViewModelTest 8 例 + ApiModelsTest DTO 3 例 + 全量门禁（Android JVM 154/0 + assembleDebug + lintDebug + server pytest/ruff/format）
- [x] D7 全量门禁 + 破坏安全独立审查 CLEAN + 验收 ZIP（Server 308/ruff/format 全过 + Android 四目标 BUILD SUCCESSFUL + 审查 0C/0I/4M + `review_handoff` ZIP 生成）

## Android(1.1.0-alpha2 Task 4: 深色设计系统、品牌与主导航)

> 状态（2026-08-30，Task A 后）：**代码与独立审查门禁通过（CLEAN）**。Task A 关闭了最终删除
> `success` 协议与生产主壳 settle 竞态两个 Important；androidTest 仍只构建未执行（无设备），
> 设备级验收与真机验收属于 Task G。Minor M1/M2/M3 遗留至后续阶段。

- [x] 固定深色 Compose token：核心色、四级排版、4/8 间距、圆角、阴影、动效、状态色与 48dp 触控门槛
- [x] code-native adaptive launcher：standard、round、Android 13 monochrome，深色底与青色房屋/播放标志
- [x] 单一 `main_shell` 与 `媒体 / 批阅 / 收藏 / 整理` 四入口；根切换保留状态且不复制 back stack
- [x] 媒体默认直达内容；批阅/收藏复用真实流；整理显示媒体库、待删除、重复文件既有计数
- [x] 设置、播放器、图片查看器独立全屏；URL/installation ID 仅设置可见
- [x] 共享 top/bottom bar、卡片、骨架屏、空态、离线重试、连接/同步 banner、Snackbar host
- [x] 可见符号控制替换为 Material Icons + 中文语义；播放器文字菜单达到 48dp
- [x] 关闭最终删除协议：Android 使用 Server 真实 `success/missing/failed`，汇总与 Media/Favorites/DeleteQueue/Duplicates 失效矩阵通过真实状态回归（Task A，2026-08-30）
- [x] 接受审查 I3/I4/M1：740x360/font 1.3 响应式媒体墙、token 单一来源、`✓` 符号回归
- [x] 关闭生产主壳旧 settle token 回归：真实 queue + 挂起 playback + 切根 + 释放旧请求 + 重入新 token，并完成移除 reset 的 RED 反证（Task A；JVM 反证已执行，androidTest 仅编译未执行——无设备）
- [x] Task 4 JVM 100 tests 全通过；Compose instrumentation APK 已构建，当前无设备故未执行
- [x] 新独立审查达到 0 Critical / 0 Important（Task A 审查 CLEAN，含 4 Minor：M1 commit 失败文案、M2 Unknown 摘要单列、M3 @Volatile 统一、M4 设备执行缺口留待真机阶段）→ Task 4 / alpha2 代码与审查门禁通过；真机/设备验收仍属 Task G

## Server
- [x] 配置
- [x] 日志
- [x] SQLite
- [x] Alembic
- [x] Health
- [x] Jellyfin Adapter
- [x] Library Selection
- [x] Media API(0.5.0 增加 cover_url)
- [x] Playback API(0.3.1)
- [x] Sprite Service(0.3.0)
- [x] Background Tasks(0.3.0)
- [x] Favorites(0.3.0)
- [x] Review Sessions(0.3.0)
- [x] Delete Queue(0.3.0)
- [x] Duplicate Scanner(0.3.1)
- [x] Duplicate Scanner 多级哈希(size+时长候选 / quick_hash 高度可信 / full sha256 exact,0.3.2)
- [x] Background Duplicate Hash Task(0.3.2)
- [x] Pairing(0.3.1)
- [x] Pairing 认证闭环(Bearer token + token_hash + revoke,0.3.2)
- [x] Pairing Code 局域网安全(仅本机/管理后台生成,0.4.0)
- [x] /pairing/status 读取配置(0.4.0)
- [x] Full SHA-256 三层收敛(size+duration→quick_hash→≥2 才全量,0.4.0)
- [x] 自动发现 UDP 应答端(0.5.0)
- [x] Admin 0.8.0(Web 管理后台 /admin)
- [x] Diagnostics 0.8.0(诊断导出 ZIP + 0.8.1 脱敏)

## Android(0.9.1 Stage15: 批阅稳定性 + 播放器完整交互)
- [x] Review settled 事件 latest-wins(取消旧切换/position 任务 + 令牌校验)0.9.1
- [x] P0/P1 重构:页面停稳立即切换 ready 当前视频;P1 URL 获取 + prepare 放后台 0.9.1
- [x] Jellyfin 进度防串片:切换前快照 mediaId/player/position/paused 再上报 0.9.1
- [x] Review 向前分页 + 恢复结束判断 baseIndex+items.size >= total 0.9.1
- [x] PlayerCore onIsPlayingChanged/onPlayWhenReadyChanged 状态刷新 0.9.1
- [x] PlayerScreen 底栏改 Column 解决控制层重叠 0.9.1
- [x] 播放器手势:单击显隐/双击快进退/亮度音量/左右 seek 0.9.1
- [x] 播放器字幕/音轨切换 + 锁定 0.9.1
- [x] 媒体墙排序补分辨率/随机 + 未点赞筛选 0.9.1
- [x] 点赞轻量弹跳动画;待删除成功后自动滑向下一条并保留撤销 0.9.1
- [x] Settings 页 + 检查连接/重连/清除配置 + 首页错误状态 0.9.1
- [x] ReviewViewModel 分页窗口/令牌与槽位状态机测试 0.9.1

## Android(待后续阶段)
- [x] ~~Image viewer~~(0.6.0 已完成)
- [x] ~~Sprite preview~~(0.6.0 已完成)
- [x] ~~Media3 player~~(0.7.0 已完成)
- [x] ~~Review mode~~(0.7.0 基础版已完成)
- [x] ~~P0/P1 preloading~~(0.7.0 已完成)
- [x] ~~Favorites~~(0.8.0 已完成)
- [x] ~~Delete queue~~(0.8.0 已完成)
- [x] ~~Duplicates~~(0.8.0 已完成)
- [x] ~~批阅断点恢复~~(0.8.0 已完成)
- [x] Settings 0.9.1(服务器信息/检查连接/重连/清除配置)
- [x] Error states 0.9.1(首页/媒体墙/播放器/批阅错误+重试)
- [x] Reconnect 0.9.1(Settings 重新配对 + Connect 流程)
- [ ] 真机联调(真实 Jellyfin / 自动发现组播)

## Deployment
- [x] Server EXE(PyInstaller onedir,Mediaserver.exe 已验证启动+health+admin)0.8.1
- [x] FFmpeg 打包支持(build_deploy.py 检测 third_party/ffmpeg;install.ps1 兜底系统 PATH)
- [x] install.ps1(管理员/复制/数据目录/配置/ffmpeg/端口/防火墙/开机自启/health/报告)0.8.1
- [x] repair.ps1(文件/服务/端口/ffmpeg/config/DB 检查 + 重建,不删数据)0.8.1
- [x] uninstall.ps1(停服务删任务删程序,默认保留数据;-DeleteData 才删)0.8.1
- [x] diagnose.ps1(版本/脱敏配置/health/日志/服务/端口/ffmpeg/Jellyfin/磁盘/DB → ZIP)0.8.1
- [x] Server ZIP 部署包(build_deploy.py 产出 deploy_handoff/MediaReviewServer-0.8.1_deploy_*.zip)0.8.1
- [x] Handover(HANDOVER.md 已生成)
- [ ] APK(Android 0.9.1 APK 已产出 app-debug.apk,正式签名 APK 待发布)
- [ ] Clean-machine deployment test(迁移到真实 Jellyfin 电脑后执行)

## Stage 16 预部署收口(0.8.1 / 0.9.1)
- [x] 媒体墙视频单击修复:longPressScrub(onTap=onClick)恢复普通播放器入口
- [x] 认证边界:/media /libraries require_auth、/cache /jellyfin localhost-or-auth,防 api_key 泄露
- [x] Android Coil 统一 Bearer ImageLoader(雪碧图/服务器图片自动带 token)
- [x] Media Snapshot Cache(TTL 180s + LRU 8 + 库选择失效):page1→3 全库只扫一次,含 10000 媒体验收测试
- [x] LatestWinsScheduler token 永久单调递增,reset 不复用历史 token
- [x] Review position 改 Channel.CONFLATED 单消费者串行上报 + 测试
- [x] PlayerCore 槽位改用服务端绝对 queue index(prepend 不再破坏匹配)
- [x] 待删除 enqueue 成功后才自动滑向下一条(DeleteSucceeded 事件)
