# MediaReview 项目状态（唯一活文档 / SSOT）

> **发布入口收口，产物待构建**：候选Server1.6.5/API2/0018/15能力，Android Alpha27/code34。已修复builder仍指Alpha24及强制清Gradle产物的问题，复用当前解释器与显式Gradle入口；构建失败撤销旧回执，缓存不能代替新发布证据。发布/部署/版本定向91项通过，1项旧SCM集成测试因Windows执行策略未进入目标逻辑，未计通过；未改机器策略。当前正式指针仍Alpha26/历史Server1.6.4包，生产仍以10:06实查1.6.3为准。随包文档已同步新版，后续先commit/push，再串行构建核验。

> **后续交互隔离验证通过**：源码候选 Alpha27/code34（尚未发布），源码 `fbf554a` 已push到 `codex/core-flow-polish`；解除生图配对确认及审核/评分提交保护，视频刷新保留内容、真实状态和避免双圈。85项JVM单轮通过；最后界面补漏只构建App2m52s，复用未改逻辑的JVM结果；最终模拟器慢刷新1项（6.011秒）与原启动/收藏2项（4.138秒）分轮通过，生图取消保留位置/确认才清配对及新文案看图复核通过。模拟器、临时桥、辅助会话关闭，设备/forward/测试端口为空。当前唯一正式安装包仍 Alpha26。2026-10-31 10:06 只读实查生产健康：`ok / Server1.6.3 / API2 / 14 capabilities / windows_service`；这台电脑尚未运行本轮相册 SQL 优化，未做生产部署。

> **2026-10-31 后续打磨进行中**：开发分支 `codex/core-flow-polish`，源码 `c242d99` 已 push，基线 `e253aa0`。相册聚合/归属 SQL 与同步读取线程修复，批阅草稿保留/缺失目录阻止开始、生图读写状态分离；服务端 87+13 项分轮通过，Android 单轮 128 项（43+67+18）及业务编译通过。5万条合成索引的最终源码对照：24库摘要3539.64→840.64ms、分页1300.85→286.09ms，所测结果摘要相同，非手机验收。详见 [核心流程记录](CORE_FLOW_POLISH_RESULT_20261031.md)。当前交付仍是下述 Alpha26，未构建 Alpha27、未部署服务；不以旧包证明新修复。

> 更新时间：2026-10-31（Alpha26/code33最终APK核验、隔离空态恢复复核通过；49+6分轮，手机待复核，两个Git分支已push对齐）
> 日常分支：`feature/mediareview-local-only`；本轮体验分支：`codex/album-review-ux`；当前交付APK构建源码`85a551d`，NSFW性能源码`9115cb6`（未部署）。
> 加载源码提交`bb02671`已push；此前目录修复`6a74876`、交付记录`d18c31d`等本地提交亦一并推送成功。下方旧网络失败与运行态检查点按各自日期理解。
>
> **本文件是"当前状态"的唯一事实源。** `README` 只保留摘要；阶段文档（STAGE8*/STAGE9*、REVIEW_SUMMARY、TAKEOVER_READ_FIRST、HANDOVER 等）都是历史快照，不再代表当前状态。任何代码提交、发布、部署、服务恢复都必须同步更新本文件与末尾变更记录。

## 0. 一句话状态

**Alpha26最终产物**：源码85a551d，最终Release5m2s成功，原证书/code33/复制SHA256均通过；20,813,624字节，SHA256 `0c0364607d12b0da1d7d54108517eb68beb523340de637cadb131746e30f414e`。CURRENT_RELEASE已切换独立Alpha26并保留Alpha25指针备份。隔离AVD确认空相册按钮可恢复新增合成图片、封面实际显示，非空下拉发起新请求；49项+后补6项分轮通过，首候选生图/评分/审核门禁证据复用。手机断开未装Alpha26，真实相册服务端约6秒查询仍待优化；代码/收口记录8bc36f3已push，两个远端分支SHA相同。模拟器、临时后端、辅助会话均停止，设备/forward/临时端口为空。详见[修复交付记录](UX_POLISH_RESULT_20261031.md)，目标打磨仍继续，不声称产品最终合格。下方网络受阻段落为历史过程，已由本段收口。

**Alpha26复核补漏**：首候选Release8m24s成功，模拟器确认全选/清空与隔离保存、视频页不请求相册摘要、生图图片区域+27%且无信息遮挡、1–5可见、404预览禁用审核；发现空书架下拉不产生请求。已补“重新加载相册”及仅失效摘要的手动刷新，相关6项通过6m6s，与原49项分轮。首候选已移入诊断目录，CURRENT_RELEASE仍Alpha25，待最终包复核后切换。手机断开，未安装Alpha26；普通Git推送reset、连接器写403，当前提交尚未push。详见[本轮记录](UX_POLISH_RESULT_20261031.md)。

**2026-10-31 Alpha26候选**：媒体库全选/清空草稿、相册元数据按进入页面加载和8项滚动预取、生图大图无信息遮挡/默认折叠缩略图/紧凑操作及评分1–5布局已实现；预览未成功禁止保留/淘汰。49项相关JVM单轮通过、生产编译通过；Release及新APK真机验证尚待执行，当前手机/唯一APK仍Alpha25。相册真实请求50/143约5964ms，服务端慢查询仍需下一批定位，未改生产服务或NSFW。见[本轮记录](UX_POLISH_RESULT_20261031.md)。用户新目标为持续打磨至合格产品，本批不作为最终验收。

**用户流程测试补充（模拟器+真机，未改代码）**：独立Android35模拟器复现评分5分裁切、预览失败仍接受审核、同步禁用审核、刷新圈提前消失、范围草稿重置和直接清生图配对；另发现首次连接空态不清与底部按钮安全间距问题。合成图显示/详情、撤销、6→8追加保留当前位置通过。手机Alpha23已保留数据覆盖Alpha25/code32；范围草稿重置同样复现，三个已就绪seek落点短采样未见回跳，画面左右seek和硬解码Render输出有证据。真机两张生图均无可靠提示词关联，评分禁用，未提交审核/评分；未做全库、长时断线、逐帧与首帧性能验收。见[本次结果及12张合成截图](UX_EMULATOR_RESULT_20261030.md)。临时模拟器/夹具和手机辅助会话均已停止，临时盘清理被自动审批拒绝、保留未绕过；生产服务不变，待办尚未修复。

**后续用户流程检查（仅文档）**：基于Alpha25/e4eef2e重新检查连接、找媒体、批阅、生图审核和整理路径，确认“断开”直接解除配对、后台同步共用busy禁用审核、未等预览成功可审核、刷新圈固定700ms、选择目录再次打开重置草稿等改进点；另列原图缩放、目录搜索/就地批阅、用户文案及长队列资源风险。见[用户流程检查](UX_FLOW_REVIEW_20261030.md)。这不是截图视觉审计或实机复现；未改生产代码、未重跑测试/构建、未部署。当前APK仍为下文已核验Alpha25，待办尚未修复。

**2026-10-30视频加载专项交付**：Alpha25/code32独立APK一次Release构建成功（6m7s），版本、签名、复制哈希及包内新读取器/消费者核验通过，CURRENT_RELEASE已更新。批阅当前稳定后才预取下一条最多2MiB，缓存预算4MiB，接入GSY既有HTTP扩展共用前缀读取；按配对/端点头隔离并在播放前复验实体和认证，不兼容走原读取。补横向/进度条拖动及暂停/退出取消，不改手势进度计算。70项相关JVM单轮通过；另17项版本合同16通过/1旧版本预期失败，修正后仅该1项重跑通过，分轮闭环；最终UI编译通过。构建源码cbe36c4已push。详见[视频专项结果](VIDEO_PREFIX_RESULT_20261030.md)。未安装手机，首帧/拖动未测；两个生产服务不变，NSFW性能源码未部署。

**2026-10-30加载资源专项交付**：Alpha24/code31独立APK构建、版本/签名/SHA256和复制核验通过，CURRENT_RELEASE已更新。隔离5/20图SELECT从12/42降为3/3，并修复状态/revision并发错配与历史事件扫描；NSFW最终feed/scoring14项、运行态适配19项、前端19项分别通过，tsc/vite通过。Android加载69项通过，版本门禁93项首轮91通过/2历史预期失败，相关7项修正后重跑通过，分轮记录。NSFW性能分支未部署，当前实例与手机保持原状；视频字节预缓冲未启用，接入分析及后续边界见[专项结果](LOADING_RESOURCE_RESULT_20261030.md)。手机首帧、布局/流畅度/耗电待实机。

**2026-10-30早前生图审核交付（独立APK已由Alpha24替代）**：Alpha23/code30最终Release已核验，CURRENT_RELEASE已更新；两个开发分支统一84aada2，64项ViewModel回归通过。Studio与审核桥8877已恢复在线，自建样本真实认证图片与审核/幂等/冲突链路通过并清理。最终APK手机覆盖安装、布局与写入验收因设备不可连接仍待验证。详见[收口记录](NSFW_INTEGRATION_RESULT_20261030.md)。

**2026-10-29历史交付快照（客户端已被上文Alpha23替代）**：Alpha22/code29与Server1.6.4/0018产物核验通过，CURRENT_RELEASE已更新。69项定向JVM、Server分轮安全回归及184项包哈希校验记录见[本轮报告](EXPERIENCE_GENERATED_RESULT_20261029.md)。源码aa441a1、构建基线17cd886已push。手机/生产仍未升级；生图仅接口准备，真实HTTP未接通。下方为过程中和历史快照。


**2026-10-29开发进展（尚未发布）**：删除持久回执与Android恢复、UI状态优化、生图独立入口及客户端合同已实现；69项相关JVM通过，服务端安全与环境复测记录见[本轮结果](EXPERIENCE_GENERATED_RESULT_20261029.md)。源码目标Server1.6.4/0018、Alpha22/code29，尚未生成发布包或部署。CURRENT_RELEASE仍为上一轮已验证产物；本轮不连接手机、不改NSFW。下方删除超时未修复描述是上一轮生产基线，不代表本轮源码。


**最新真机删除核验（用户明确授权）**：原69条记录已处理，实际删除45文件/5,142,507,504字节，另24条旧路径已不存在、未计删除大小。逐个原路径与索引均消失、队列0、审计success45/missing24；三个nonce重放409且不再执行。手机第二/三批结果准确，最终显示空列表。首批约20秒超过客户端15秒等待时限，手机未收到完整回执；该超时体验缺陷未修复，不代表文件未删。版本仍Alpha21/Server1.6.3，未改业务或升级；详见[真机删除核验](DELETE_PHONE_RESULT_20261028.md)。

**五项手机反馈修复（Alpha21已安装）**：主页仅视频、批阅横滑seek/提交位置显示、文件夹直接勾选/完整展开、删除上下项按钮；有界重连并响应前台/网络恢复。58项相关JVM、另16版本用例及4项隔离设备用例通过，后补恢复4项亦通过，不合并为单轮计数；用户要求后只用真机。手机确认70%拖动连续8次不回跳、画面左右seek不换片、上下页码切换、范围勾选和开始按钮可达、相册可用且无演示入口。第62项短采样未确认出帧，长期断线稳定性仍需观察。正式APK20,649,784字节、演示资源0项、同证书覆盖成功，CURRENT_RELEASE已更新；源码8d18d80已push。Server1.6.3/windows_service恢复且Running，旧测试产物清理约73MB、模拟器和构建守护进程已停止。详见[五项修复与清理记录](FIVE_ISSUES_RESULT_20261028.md)。

**手机基线与接口复核（2026-10-28）**：USB手机此前Alpha19/code26，已校验CURRENT_RELEASE的APK哈希并install -r保留数据升级Alpha20/code27。真实界面可见分类卡、排序入口及全部排序选项，切换大小倒序后摘要正确；未验证图片筛选selected语义或全部媒体排序结果，不扩成全量验收。辅助会话退出、ADB forward为空。NSFW开发/部署目录现均882629f（v0.36.2），相对6ad633a未新增mobile/integration接口；增量发现、提示词详情、手机评分仍缺失。完整生图页面接入待维护者提供新增合同；本轮不改NSFW、不发布新版本。

**生图审核开发新要求（2026-10-28，进行中）**：用户要求与批阅同级的独立页面、新图追加队尾、逐图查看生成提示词/时间/尺寸，随后要求接入桌面提示词评分。最新边界见[动态队列设计](superpowers/specs/2026-10-28-generated-image-live-review.md)，优先于上一版冻结会话方案。已形成[给NSFW维护者的接口需求](NSFW_MOBILE_API_REQUEST_V2.md)，复用8767、不新增端口；上游现有合同缺增量发现、提示词详情及手机评分接口。MediaReview开始稳定追加队列基础，尚未接通独立页面或发布APK；NSFW未修改。

**后续功能规划（2026-10-28，未实施）**：已基于Alpha20和NSFW开发/部署目录相同提交6ad633a调查8767审核桥，形成[生成图片审核接入方案](superpowers/specs/2026-10-28-nsfw-review-integration-plan.md)。建议独立图片审核入口、独立配对与状态机、直连既有桥；不套用旧Alpha9审计或媒体删除语义。本轮只写方案，未启动服务、配对、改业务或发布新版本。

**目录专项最新（Alpha20已交付）**：用户确认子目录在前，默认只列当前目录直接图片/视频，可显式递归。新增常驻类型筛选及独立排序，修复单库根展开和排序上下文，澄清总数/当前数/加载状态。39项相关JVM、4项隔离模拟器设备用例通过，演示界面已复核；同Alpha19证书的Release构建成功，CURRENT_RELEASE已指向独立Alpha20/code27，源码`d0ba539`已push。ADB无手机，未安装Alpha20，真机/LAN未验证。Server不改、不部署，实际health仍1.6.3/windows_service。详见[目录审查与整改](FOLDER_BROWSE_RESULT_20261028.md)。

**批阅专项最新（Alpha19已安装）**：用户确认上下切换、单击暂停、进度条拖动，取消滑动决策和双击跳进度/长按快捷操作。已复现旧冻结会话404及批阅漏接内核错误；Alpha19改为VerticalPager并补错误回传/范围重选入口；71项相关JVM及4项隔离模拟器核心测试通过。Release同证书核验、手机保留数据覆盖安装成功；实际Server下第5/6项上下切换、播放进度前进、暂停拖动仍暂停、播放拖动继续、横向画面不换片已抽样确认，未遍历全库或逐帧检查。生产Server保持1.6.3；CURRENT_RELEASE指向独立Alpha19，Server包内旧Alpha18不作为手机安装入口。源码`1abbeb0`已push。详见[批阅专项记录](REVIEW_REPAIR_20261027.md)。

**最新实机排障与部署**：用户连接Alpha18，发现服务有进程但无8766监听、历史QueuePool耗尽及大量旧路径404。经用户单独授权精确服务恢复后，手机重新检测在线。保留24有效库、取消2失效选择，正常API逐库刷新全部succeeded、processed合计56553；不删除媒体。连接归还缺陷已按RED/GREEN修复：media相关36通过，补认证后相关11通过（重叠，不合计）。源码d87b69a已push，EXE健康与182项包校验通过；用户单独授权后通过ControlHub完整备份升级至1.6.3，健康ok、单实例，唯一CURRENT_RELEASE已同步。Alpha18不变，真机视频实际画面、图片查看与缩略图抽样确认；全部目录未逐个验收。断线后仍可能需手动重新检测，列表/目录延迟仍约数秒；端口丢失触发与整批刷新失败原因尚未确证。详见[实机排障记录](PHONE_REPAIR_20261027.md)。下方旧状态均为各时段历史快照。

**本次运维收口已执行**：用户确认后，经ControlHub停服、完整受保护备份并升级Server1.6.2/Contract2/0017，已启动且数据库ok、14项能力；终检只有一个MediaReview服务实例，8766归属与安装EXE哈希匹配。排除已验证身份的旧手动残留实例，首次失败备份标记不完整，最终有效全量备份及一致性快照保留。唯一发布入口为`deploy_handoff/CURRENT_RELEASE.json`→`current/`。Alpha18实际附件核验后发往已确认QQ，SMTP接受、拒收0，未声称收件箱已收到。ControlHub GUI已启动，Jellyfin在线、health200；应用内管理页打开请求queued，外部浏览器启动被自动审批拒绝，未计可见。未刷新生产索引、未操作真实媒体、未清手机数据，手机/HomeLAN验收待用户回家。详见[收口记录](DEPLOYMENT_CLOSURE_20261027.md)。下方旧版本与未部署陈述均为对应阶段历史快照，以本段及权威版本表为当前运行事实。

**加载专项交付快照（部署前）**：Server1.6.2 + Alpha18/code25，雪碧图保持删除。图片缓存统一、滚动预取取消、缓存I/O线程化、播放源/续播并行已完成。Server13项与复核后缓存5项分别通过；JVM首轮57通过/1旧版本断言失败，修正后相关20项通过，非单轮全绿。隔离API35模拟器两项HTTP图片检查通过：合成小图冷304ms/内存4ms、磁盘命中、慢响应滚动新窗口23ms及账号隔离。Release/EXE构建、APK版本/签名及同Alpha17证书、实际EXE health、182项发布文件哈希通过。产物在`deploy_handoff/loading-1.6.2/`，源码bb02671已push；详见[专项结果与SHA256](LOADING_RESULT_20261027.md)。模拟器/探针已停止并清理本轮临时文件。生产最新health仍**1.5.2/windows_service，Running**，未部署/未发邮件/手机HomeLAN未验证；默认URL视频缓存无认证身份，下一条内容预缓冲按方案保留后续。下文1.6.1为本日上一轮记录。

目录修复阶段源码曾为 **Server1.6.1/Contract2/迁移0017 + Android Alpha17/code24**。已修复同用户Jellyfin库级递归漏项：后台改为有界的逐层分页、同目录分页异常失败闭合、跨目录稳定ID去重，完整成功后才失效旧索引。最终客户端/索引45项、目录/API52项通过；四个真实上游样本只读计数132/288/101/1866均符合对照。既有Alpha17在隔离模拟器连到修复版后显示全局5项、原递归0库3项及子目录2项，图片实际渲染；合成视频出帧未确认（goldfish Surface错误），不计播放通过。生产仍为**1.5.2/0016/windows_service，Running且DB正常**，未升级或刷新。详见[修复与测试结果](FOLDER_ZERO_REPAIR_RESULT_20261027.md)。§3、§5中的旧运行态与旧产物待办属于历史检查点，以本段最新实测为准。

本轮Server1.6.1包已生成：`deploy_handoff/folder-fix-1.6.1/MediaReview_Server_1.6.1_stage9.zip`（134,336,115字节，SHA256 `b644616f20535b4a80fb432ecfd206866e3e8681c5d3c210e280c2b0966618c6`）；实际EXE health及182项文件校验通过，构建源码`6a74876`。复用Alpha17，不重建APK；产物未经生产部署或手机/HomeLAN验收。

## 1. 权威版本表（SSOT）

加载调查及用户裁剪决定见[调查与建议](LOADING_PREVIEW_ASSESSMENT_20261027.md)。[专项方案](superpowers/specs/2026-10-27-media-loading-design.md)已获确认，正在实施；以本页最新段落及专项结果为准。

| 组件 | 权威值 | 证据（文件:行 / 来源） |
| --- | --- | --- |
| Server 版本（候选源码，待构建/未部署） | `1.6.5` | `server/app/__init__.py`、`deployment/version.ps1`、`server/pyproject.toml`与builder一致；现有历史包仍1.6.4，不是生产已装版本 |
| API Contract | `2` | `server/app/__init__.py:8`、`deployment/version.ps1:13` |
| Server 能力（源码15项） | 前述生产14项另加`delete_result_v1`；完整列表以`SERVER_CAPABILITIES`为准 | 源码重查；生产1.6.3于2026-10-31 10:06健康实查14项，不得混同 |
| 数据库迁移 head（源码） | `0018` | revision字段实值；文件`server/app/db/migrations/versions/0018_delete_receipt.py`，生产未据此升级 |
| Android 版本（候选源码/调试验证包） | `versionCode 34` / `versionName 2.0.0-alpha27` | 源码fbf554a，正式签名交付包待构建；不是已安装手机版本 |
| 当前独立正式APK | `versionCode 33` / `2.0.0-alpha26` | 构建源码85a551d，aapt/签名/SHA256与CURRENT_RELEASE一致，2026-10-31再次复核哈希 |
| 手机最近核验版本 | `versionCode 32` / `2.0.0-alpha25` | 2026-10-30 install -r及实际包检查；Alpha26仅隔离AVD复核，未安装手机 |
| Android 包名 | `com.mediareview.app` | `android/app/build.gradle.kts:27` |
| 生产最近核验版本 | `1.6.3` | 2026-10-31 10:06健康实查windows_service；1.6.4历史包未部署，1.6.5待构建，发布清单不能替代生产实测 |

## 2. 产物状态

**当前独立Alpha26为20,813,624字节，沿用既有证书，最终哈希0c0364…0f414e；隔离复核通过、手机待装、未发邮件，唯一入口为CURRENT_RELEASE。Server1.6.4包仍附历史Alpha22，不作为手机安装入口，且未部署。** 历史Alpha21为20,649,784字节、Alpha20为43,655,758字节；以下1.5.2/Alpha15表格仅保留历史，不作当前部署入口。Alpha16/Server1.6.0见§15：

| 产物 | 字节 | SHA256（实测） | 备注 |
| --- | --- | --- | --- |
| `deploy_handoff/MediaReview_2.0.0-alpha15_stage9.apk` | 43,606,610 | `b950d25f88d8a520fdc36afff3400b35c422567da6b3498b4bcf853a8f93ee44` | 签名与 Alpha14 同证书；**未安装到手机** |
| `deploy_handoff/MediaReview_Server_1.5.2_stage9.zip` | 134,260,522 | `50fb541bda5c5cdf9fb52c5f246ab60cd59560c7c6f723a11e4c250b526d7095` | 180 项 SHA 清单自洽；包内 README 为 Stage8I 旧文（待发布前修） |
| 包内 `MediaReviewServer/MediaReviewServer.exe` | 14,559,389 | `e0dbb6d07b65b58731e844ef33625792af8dbb5401f848708e9b5381e7513b17` | 构建 `git_head=2858d3c`、Server 1.5.2/Contract 2/0016 |
| 生产已安装 EXE | 14,559,389 | 同上 `e0db…7513b17` | 与 1.5.2 正式包**逐字节一致** |

**关键事实**：已发布 1.5.2 的构建基线是 `2858d3c`；`747e704`（图片 404 归类修复）与 `2dd9ff2`（仅文档）都在其之后。因此**生产 1.5.2 不含 404 修复**，缺封面仍返回 502。要改变必须重新打包发版。

## 3. 生产运行态（2026-10-24 实测）

| 项 | 实测值 | 检查方式 |
| --- | --- | --- |
| SCM 服务 `AIHome.MediaReview` | **Stopped / Manual** | `Get-Service` |
| 8766 监听 | `0.0.0.0:8766` 由 **PID 3896** `MediaReviewServer.exe` 持有（会话 0，创建于 2026-10-23 17:32:00） | `Get-NetTCPConnection` / `Get-CimInstance Win32_Process` |
| 该实例 health | `status=ok`、`1.5.2`、`api_contract=2`、DB ok、13 能力，但 `instance_mode=interactive`、`lifecycle_target=interactive` | `GET /api/v1/system/health` |
| ControlHub | integration `mediareview` = approved；lifecycle 状态 `offline`（识别到的是 interactive 身份，不是官方服务） | `control_hub.lifecycle_cli status` |
| 最后一次官方服务正常 | 2026-10-20 08:14 升级 1.5.2 后三次 health 均为 `windows_service / AIHome.MediaReview` | `review_meta/stage9/production-152-upgrade.json` |
| 手机 | ADB 无设备；Alpha15 未安装 | `adb devices` |

> 风险：交互式实例不是官方生命周期对象，无自启、不受 ControlHub 管理；历史记录中它已换过一次 PID（3916 → 3896），来源仍未确认（见 `review_meta/ops-20261023/service-restore-log.md`）。

## 4. 验证矩阵（证据级别：A 实机/生产｜B 构建/隔离｜T 源码自动化测试｜C 文档自述｜D 未执行）

| 项目 | 结果 | 级别 | 证据 |
| --- | --- | --- | --- |
| 10-23 Server 全量 pytest（含 404 修复） | **693 通过 / 2 失败 / 695 项 / 422.70s**；2 项为本机 ffmpeg 完整性级别环境问题，换可写 basetemp 单独复跑通过 | T | `review_meta/ops-20261023/server-full-green2.log`、`docs/DEV_LOG.md:2881-2885` |
| 10-23 改动前全量 pytest | 690 通过 / 4 失败 / 484.50s（失败均已分类） | T | `review_meta/ops-20261023/server-full-baseline.log` |
| 10-23 图片代理定向回归 | 16 通过（含新增 404 用例） | T | `docs/DEV_LOG.md:2883` |
| Android JVM | 75 suites / 498 tests / 0 失败（10-23 基线；之后未改 Android，未重跑） | C（复用基线） | `review_meta/ops-20261023/`（记录）、DEV_LOG |
| **10-24 P0/P1 本地删除后 Server 全量** | **709 通过 / 2 失败 / 711 项 / 430.01s**；2 项为本机 ffmpeg 完整性级别环境限制（默认 Temp 不可写），换项目内可写 basetemp 单独复跑 2 通过 | T | `review_meta/ops-20261024/server-full-p1-fast.log` |
| **10-24 P0/P1 后 Android 全量 JVM** | **75 suites / 507 tests / 0 失败 / 0 错误 / 0 跳过**（BUILD SUCCESSFUL） | T | `review_meta/ops-20261024/android-full-p1.log`、`android/app/build/test-results/` |
| **10-24 新增本地删除安全测试** | 12 类场景（两阶段成功/审计无路径、missing、root 停用/缺失、同尺寸替换、越界/绝对/UNC/目录、跨根、junction、逐条失败、nonce 快照、file_in_use、扩展分类） | T | `server/tests/test_native_sources.py` |
| **10-25 P5 发版准备定向验证** | Server 指定四文件 **79 passed / 0 failed / 0 skipped**；Android 指定过滤 **20 tests / 0 failures / 0 errors / 0 skipped**，BUILD SUCCESSFUL | T | `review_meta/codex-review-20261025-round7/p5-report.md` |
| 8I 完整 AVD connected | 第三轮 178/178 通过（2026-10-17） | B | `REVIEW_SUMMARY.md` 历史摘要、`review_meta/stage8i/final-connected-round3` |
| 9D Alpha14/1.5.0 交付 | APK 签名 PASS；隔离 EXE health 1.5.0/Contract2/13 能力；ZIP 162 文件核验 | B | `review_meta/stage9/delivery-receipt.json` |
| 1.5.0/1.5.1/1.5.2 生产升级 | 均经授权、停服备份、逐文件核验、health 通过 | A | `STAGE9_RESULT.md`、`review_meta/stage9/production-152-*.json` |
| 10-20 0016 回填 | 56,566 条归属；升级前后 配对19/收藏4/批阅会话1/待删38/缓存56,566/库选择21 数量一致 | A | `production-152-before.json` / `verified.json` |
| Alpha15 装机与真机/LAN 验收 | **未执行** | D | ADB 无设备 |
| seek 后 `Invalid NAL length` | **未复现、未修复**（证据仅来自 Alpha14/code21） | D | `review_meta/ops-20261023/seek-nal-report.md` |
| `media_folders` 慢性慢查询 | **已定位、未修复**（56k 行基线 2.6–3.1s，峰值 11.5s；0 目录仍 3.8s） | A（调查） | `review_meta/ops-20261023/image-perf-report.md` |
| 图片 404→502 修复 | 源码已修（`747e704`），**未发版、未进生产** | 部分 | `docs/DEV_LOG.md:2870-2890` |
| GitHub 推送 | **2026-10-24 已成功推送**（`2dd9ff2..5786f8f`，含 `747e704` 与统一文档提交） | A（远端） | `git push` 输出、`git status -sb` |

## 5. 已知问题（按严重度）

| 级别 | 问题 | 状态 | 影响 | 下一步 |
| --- | --- | --- | --- | --- |
| **S0** | 已发布 1.5.2 不含 `747e704` 404 修复 | 产物 `git_head=2858d3c`；P5 源码已包含该修复 | 现有产物缺封面仍报 502 | 后续获准时构建并验证 1.6.0 / Alpha16 产物 |
| **S0** | 官方服务 Stopped，8766 被来源未确认的 interactive 实例占用 | PID 3896（10-23 曾为 3916） | 无自启、ControlHub 不可管理、重启后手机不可达 | 管理员结束占用进程 → ControlHub 启动 → 3 次 health 核验 |
| **S1** | Alpha16 产物未构建，未上真机 | 本轮仅源码准备 | 0017、本地 POSIX-only 删除与手机/LAN 行为均无新版实机证据 | 另行获准构建/安装后按 `USER_LAN_VALIDATION.md` 定向验收 |
| **S1** | seek 后 `Invalid NAL length` | 根因未确认，未做猜测性修改 | 拖动播放可能中断 | 按 `seek-nal-report.md` 取证后再修 |
| **S1** | `media_folders` 慢性慢查询 + 5s 台阶 | 已定位，未修 | 目录页 2.6–11.5s；缩略图 53 次 >5s | 先做零风险分段打点，再做结构性优化 |
| **S2** | 本地源永久删除：源码已实现两阶段安全删除；本地源仍无重复检测/转码 | P1 第七轮复审通过，P5 版本/契约源码已同步 | 已发布 1.5.2/Alpha15 仍不能删本地媒体，1.6.0/Alpha16 产物未构建 | 后续授权构建 + 真机隔离样本验证 |
| **S2** | 0016 无法恢复历史已被覆盖的跨库归属 | 仅回填现存归属 | 部分子库目录需刷新同步后才恢复 | 升级后确认库勾选并刷新 |
| **S2** | 本机 2 项 pytest 环境失败 + `server/app/main.py` 2 条既有 ruff 违规 | 环境级 / 既有 | 不阻塞产品，但不能宣称全绿 | 修本机工具目录完整性标签 / 另开小提交清 ruff |
| **S3** | GitHub 不可达（已于 2026-10-24 恢复） | 已解决：`2dd9ff2..5786f8f` 推送成功 | 远端交接渠道恢复 | 保持每次提交后核对 `git status -sb` |
| **S3** | 文档/registry 口径漂移 | 本次已统一 S 级（见 §8） | 交接误判 | 保持本文件更新 |

## 6. 阶段时间线（索引）

| 阶段 | 日期 | 代表 commit | 交付版本 | 状态 |
| --- | --- | --- | --- | --- |
| 1.0 基线导入 | 2026-08-22 | `cf8f363` | — | ✅ |
| 1.1 Task 0A–4 / Task F | 08-24～09-02 | `ef528d3`、`fac901e` | 1.1 开发 / Server 1.1.0-rc1 | ✅（真机门未过） |
| 1.1.0-rc1 / rc2 | 09-03 / 09-06 | tag `1.1.0-rc1`/`rc2` | Android code7 | ◐ 未发布正式 1.1.0 |
| 2.0 Stage1–3 | 09-19～09-22 | `633e31c`、`5f196fd` | 2.0.0-alpha1 | ✅（JVM/构建级） |
| Stage 8A–8J | 09-24～10-17 | `0f63090` … `cbf56ba` | Alpha1→Alpha13/code20；Server 1.2.2→1.4.1 | ✅（8J 手机故障未复现） |
| Stage 9A–9D 原生本地源 | 10-18 | `f4ae162`、`62f5abf`、`ddd6b00`、`838941d`、`f1a4ac8` | Alpha14/code21；Server 1.5.0 | ✅ 定向/隔离验证；仅 2 段视频真机出帧 |
| 1.5.1 锁竞争热修 | 10-18～10-19 | `d704f51` | Server 1.5.1 | ✅ 生产升级；拖动恢复未确认 |
| 10-19 完整体检 / UI 审查 | 10-19 | `91c0ed3`、`c581830`、`9ef0719` | 1.5.1/alpha14 | ◐ 确认跨库覆盖、seek NAL、慢请求、UI 缺陷 |
| 10-20 0016 修复 + 1.5.2 发版与生产升级 | 10-20 | `2858d3c`、`2dd9ff2` | Alpha15/code22；Server 1.5.2/0016 | ✅ 发布/升级有回执；Alpha15 未装机 |
| 10-23 图片 404 归类修复 | 10-23 | `747e704` | 源码修复，未发版 | ◐ 全量 pytest 693/695；未推送 |
| 10-23 服务恢复尝试 | 10-23 | — | — | ✗ 未恢复（端口冲突、无管理员权限） |
| 10-24 进度统一 + 跳过 Jellyfin 评估 | 10-24 | `5786f8f`、`37e93e2` | — | ✅（只读评估 + 文档统一 + 推送） |
| 10-24 本地源为主 P0/P1 开发 | 10-24 | `2e59b9d`、`b3543a3`、`e5fe3c5`（已推送 `feature/mediareview-local-only`） | 源码：Server 能力 14（+`native_delete_v1`）/ Android 未升版 | ◐ 源码完成并验证；未发版、未真机 |

## 7. 产品方向：本地源为主 + 本地安全删除（2026-10-24 已批准，P0/P1 源码完成）

用户真实需求：**同一局域网下用手机看电脑视频，并能审核、删除不想留的视频。** 2026-10-24 用户批准按推荐方案开始开发。

**已确认决策**
- 方向：**本地源为唯一必需来源，Jellyfin 代码保留、配置可选**（方案 B 产品方向 + C 工程形态）。
- 本地删除：**不可恢复但全链路安全**（两阶段 + nonce + 二次确认 + 路径链/身份复验 + 审计 + 逐条失败）；可恢复的回收站/延迟删除列为 P2 可选，未做。
- 删除开关：默认启用（仅对已登记且 enabled 的本地根生效；后续如需可加配置项）。
- 转码：P3 暂缓，等真机 A/B 实测后再决定 remux 或重编码。
- 规则：已修订项目 `AGENTS.md` 的 Stage9 约定，允许"两阶段安全删除"。

**开发分支**：`feature/mediareview-local-only`（从 `37e93e2` 创建），Server 与 Android 并行开发；Lead 已复核并跑全量验证，提交后推送。

| 阶段 | 内容 | 规模 | 状态 |
| --- | --- | --- | --- |
| P0 | 去 Jellyfin 依赖收口（引导/诊断/运维文案、扩展名白名单、MIME） | S，1–2 人日 | **源码完成并验证（未发版）** |
| P1 | **本地安全永久删除**（Server 能力 `native_delete_v1` + 入队/提交本地分支 + Android 门禁改造 + 测试） | M，3–5 人日 | **第七轮复审通过，进入 P5** |
| P2 可选 | 删除可回滚（Windows 回收站或延迟删除 + 撤销窗口） | M，2–4 人日 | 未开始（可选） |
| P3 | 播放兜底（按需 remux / HLS 转码） | L，7–12 人日 | 待真机 A/B 后决策 |
| P5 | 发布（Server 1.6.0 + Android alpha16/code23 + 回滚说明） | S，1–2 人日 | **历史 Alpha16/Server 1.6.0 产物已构建；未部署、未真机/LAN/生产验收，见 §15** |

验收标准（P1）：只能经"入队 → prepare nonce → 确认"删除；文件被替换/换链/junction/root 禁用/快照外项全部拒绝且不删；成功项审计可查；失败逐条不中断；**只在临时目录与合成文件上验证，真机删除样本需用户另行授权**。

## 8. 文档地图（谁代表当前状态）

| 文档 | 角色 | 说明 |
| --- | --- | --- |
| **`docs/PROJECT_STATUS.md`（本文件）** | **唯一活状态源** | 所有"当前版本/当前状态/待办"以此为准 |
| `README.md` | 活摘要 | 只保留项目简介+当前版本摘要并链接本文件 |
| `AGENTS.md`（项目） | 规则 | 工程规则；顶部有指向本文件的进度声明 |
| `docs/PRODUCT_SPEC.md`、`docs/ARCHITECTURE.md` | 规格 | 定义产品与技术架构（含 Stage9 来源扩展）；进度不在此 |
| `docs/DEV_LOG.md` | 流水账 | 按时间追加的开发记录（历史证据） |
| `TASKS.md` | 任务清单 | 当前/历史任务；顶部指向本文件 |
| `docs/ACCEPTANCE.md` | 1.1 历史验收清单 | 已被 2.0 实际进度取代，仅供追溯 |
| `TAKEOVER_READ_FIRST.md`、`HANDOVER.md`、`REVIEW_SUMMARY.md` | **历史快照** | 分别停在 1.1 接管期 / 1.1.0 / Stage8I |
| `STAGE8J_RESULT.md`、`STAGE9_RESULT.md`、`docs/STAGE9_*` | **历史快照** | 阶段交付/体检记录 |
| `deploy_handoff/*` | 历史产物 | 包含 1.5.2/Alpha15 与 1.6.0/Alpha16 历史产物；Alpha17 产物未构建 |
| `review_meta/` | 本地证据（git 忽略） | 回执/日志/调查报告，不入库 |

## 9. 变更记录

| 日期 | 变更 | 证据 |
| --- | --- | --- |
| 2026-10-23 | 图片 404 归类 + 失败诊断补齐；修正陈旧版本断言；全量 pytest 693/695 | `747e704`、`docs/DEV_LOG.md:2870-2890` |
| 2026-10-24 | 建立本文件为唯一活状态源；修订 README/TAKEOVER/HANDOVER/REVIEW_SUMMARY/TASKS/deployment 等 S 级漂移；完成跳过 Jellyfin 可行性评估；GitHub 恢复后推送 `2dd9ff2..5786f8f` | `review_meta/ops-20261024/progress-audit.md`、`jellyfin-skip-assessment.md`；`git push` |
| 2026-10-24 | 本地源为主 P0/P1：新增 `native_delete_v1` 与本地两阶段安全删除、去 Jellyfin 诊断/文案收口、Android 只信任 `can_delete`；Server 全量 709/711（2 项环境）、Android 507/507 | `feature/mediareview-local-only`、`docs/DEV_LOG.md`、`review_meta/ops-20261024/*.log` |
| 2026-10-25 | Codex 复审 C1/I4/M1；完成 C1 句柄绑定删除、I1 队列代次(0017)、I2 状态机+恢复(含 Lead 修复本地恢复误判)、I3 Android fail-closed、M1 审计；Server 721/723（2 环境）、Android 511/511 | `review_meta/codex-review-20261025/`、`review_meta/codex-fix-20261025/`、`docs/DEV_LOG.md` |
| 2026-10-25 | 第七轮复审后进入 P5；源码版本统一为 Server 1.6.0 / Alpha16-code23 / Contract2 / 0017，builder 要求 14 能力；产物未构建 | `review_meta/codex-review-20261025-round7/p5-report.md`、`docs/DEV_LOG.md` |
| 2026-10-26 | UI P1–P4 完成后准备 Alpha17 源码：Android code24、中文 UI 重构 Release Notes、版本/兼容文档同步；受影响 Android 20/20、Server 32/32 通过，产物未构建 | `review_meta/ui-refactor-20261025/alpha17-report.md`、`docs/DEV_LOG.md` |

## 10. Codex 独立复审（2026-10-25，本地删除阻断项）

Codex `gpt-5.6-sol` 对 `37e93e2..373025b` 做独立安全复审（182,126 tokens），报告 `review_meta/codex-review-20261025/local-delete-review.md`。结论 **C1 / I4 / M1，阻断发布**：

| 项 | 级别 | 摘要 | 处置 |
| --- | --- | --- | --- |
| C1 | Critical | 复验句柄关闭后仍按路径 `os.remove`，未绑定 st_dev/st_ino；并发换链可删除登记根外文件或同尺寸替换文件 | **已修（源码，未提交）**：Windows 句柄 disposition 删除 + fail-closed + 确定性竞态测试（本机 junction 实测执行） |
| I1 | Important | nonce 只绑定 media_id；撤销后同 ID 重新入队会被旧 nonce 删除 | **已修（源码，未提交）**：队列代次 queue_token + 迁移 0017 + nonce 绑定 + 同 ID 回归 |
| I2 | Important | 文件删除与 nonce/审计/索引事务不原子；删除后失败会"文件已删但请求 500、nonce 未消耗" | **已修（源码，未提交）**：先提交 claim/committing → 逐项独立事务 → 启动恢复 + 逐项异常隔离；另修本地源恢复误判（Lead 发现） |
| I3 | Important | Android can_delete 缺字段/缓存 miss 时 fail-open | **已修（源码，未提交）**：DTO 可空、仅 `==true` 放行、缓存 miss/缺字段 fail-closed + 测试 |
| I4 | Important | 安全测试缺竞态/同 ID/删除后事务失败/Android 缺字段用例 | **已补**：竞态/同 ID/旧快照/异常恢复/迁移回填/Android 缺字段与缓存 miss |
| M1 | Minor | 快照中跳过/非 pending 项无逐条审计 | **已修**：稳定审计码（无路径） |

**验证**：Server 全量 **721 通过 / 2 失败 / 723 项 / 437.51s**（2 项为本机 ffmpeg 完整性环境限制，换可写 basetemp 单独复跑 2 通过）；Android 全量 JVM **511 tests / 0 失败**；ruff 改动文件通过。

**P5 发版继续暂停**：待 Codex 额度复位后（2026-10-25 05:44）做**二次独立复审**，通过后提交/推送并进入 P5。当前分支 `feature/mediareview-local-only`。

## 11. 第二轮独立复审（2026-10-25 05:52）

Codex `gpt-5.6-sol` 对修复提交 `373025b..cfe7ca5` 做第二轮复审，报告 `review_meta/codex-review-20261025-round2/round2-review.md`。结论 **BLOCK：C0 / I2 / M2**（C1、I1、I3 判定已关闭）。

| 编号 | 级别 | 问题 | 处置 |
| --- | --- | --- | --- |
| I5 | Important | Windows delete-on-close：disposition 成功后若其他 FILE_SHARE_DELETE 句柄未关，文件仍可见；旧实现会落 `failed`，但文件稍后永久消失 | **已修（源码）**：disposition 成功即按 success 收敛，不再用 lstat/CloseHandle 改判；真实第二共享句柄测试 |
| I6 | Important | 0017 downgrade 未处理 `committing` 行 | **已修（源码）**：存在 committing 时 fail-closed 拒绝降级且不改写；补回滚测试 |
| M2 | Minor | 非执行快照分支审计失败被静默吞掉 | **已修（源码）**：三类审计并入 claim 同事务，失败整体回滚、nonce 不消费 |
| M3 | Minor | Win32 ERROR_ACCESS_DENIED(5) 被误报为 file_in_use | **已修（源码）**：5→`delete_access_denied`，32/33→file_in_use；Android 中文映射 |

P1 发版仍阻断；待第三轮独立复审通过后再评估 P5。提交 `f3f2149`/`90498cc`/`cfe7ca5` 已于 2026-10-25 05:46 推送到 GitHub（`373025b..cfe7ca5`）。

## 12. 第三轮复审与 I5-A/I5-B 修复（2026-10-25 07:12）

第三轮 Codex 复审（`review_meta/codex-review-20261025-round3/round3-review.md`）判定 **BLOCK**：I6/M2/M3 已关闭，但 I5 与 I2 组合仍存在 "delete-pending 后元数据事务失败先落 failed、文件后消失"（I5-A）与 "CloseHandle 失败被忽略却清理元数据报 success"（I5-B）。

修复（Codex，`review_meta/codex-fix-20261025-round3/fix-report.md`）：
- Windows 优先 `FILE_DISPOSITION_INFO_EX` + POSIX 语义：disposition 后关闭句柄并以 `lstat` 验证名字消失才算 success；不支持才回退传统 disposition，回退路径只返回 `pending`。
- 新增持久状态 `delete_done` / `delete_pending` 与 receipt `pending` / `pending_count`；`delete_done` 先独立提交再清理元数据，清理失败保持 `delete_done`（success，恢复补清理并审计）；`delete_pending` 由恢复只观察，名字消失后转 `delete_done`；重复恢复幂等、不再次删除。惰性恢复入口加入 `GET /delete-queue` 与 `prepare`。
- CloseHandle 失败：POSIX 名字已验证消失仍 success；传统路径保持 pending；不写路径。
- Android 支持 `Pending` 状态与"删除请求已提交，等待系统完成"，旧 Server（无 pending 字段）兼容。

验证：Codex 定向四文件 **94 passed**；真实 Windows 专项 5 passed；Android 3 类 36/36；Lead 全量 Server **736/739**（2 项本机 ffmpeg 环境 + 1 项 10 万行性能抖动，三项单独复跑均通过）、Android **511/511**；ruff 通过。

P1 仍待第四轮定点复审通过后发版。

## 13. 当前等待项（2026-10-25 07:29）

- 第三轮修复已提交并推送：`485e2e7`（POSIX disposition + delete_done/delete_pending 状态机）、`48aa26f`（Android pending 展示）、`03083da`（文档）；远端 `cfe7ca5..03083da`。
- **第四轮 Codex 定点复审因 5 小时额度用尽顺延**（复位 2026-10-25 10:47；Codex CLI 报 usage limit）。P5 发版继续暂停，等复审结论。
- Lead 全量：Server 736/739（2 项本机 ffmpeg 环境、1 项 10 万行性能抖动，三项单独复跑均通过）；Android 511/511；ruff 改动文件通过。

## 14. 当前等待项（2026-10-25 12:45）

- 第六轮修复提交 `5aa367a`（Jellyfin 恢复父链/身份证据收紧）本地领先 origin 1；push 因 GitHub 网络暂时失败，待重试。
- 第七轮 Codex 定点复审因 5 小时额度用尽顺延（复位 15:49；Codex CLI 报 usage limit）。P5 发版继续暂停。
- Lead 全量：Server 743/745（2 项本机 ffmpeg 环境，单独复跑通过）；Android 511/511；ruff 改动文件通过。

## 15. P5 Alpha16 发版产物（历史，2026-10-25 17:32）

第九轮 Codex 复审结论 **PASS_WITH_MINOR**（C0/I0/M1，M8-T 为测试覆盖缺口，不阻断）。P5 源码与产物已完成：

| 项 | 值 |
| --- | --- |
| Server | 1.6.0 / API Contract 2 / 迁移 0017 / 14 能力（含 `native_delete_v1`） |
| Android | 2.0.0-alpha16 / code 23 / `com.mediareview.app` |
| 构建 HEAD | `ced2f07`（build 与源码同 HEAD；工作区 clean） |
| Server ZIP | `deploy_handoff/MediaReview_Server_1.6.0_stage9.zip`，133,242,411 bytes，SHA256 `04be46db617d4f238e485055e339aaf500cfb4f05172940a30e367ef69bdffdf` |
| APK | `deploy_handoff/MediaReview_2.0.0-alpha16_stage9.apk`，43,606,606 bytes，SHA256 `42d188cb37b4b45c3bcedacc8ef5b8670ae61844b78e066d8b477a40be3b1dfa`，signature PASS |
| EXE | SHA256 `2a9f5cfe45f98b8c24bf644245dcef29fcb85240703206c59d3e495feb73064d` |
| 部署/真机 | **部署 NOT PERFORMED；家庭 LAN/真机 NOT TESTED**（需用户管理员停服备份、连接手机验收） |

升级/回滚边界见 `deploy_handoff/MediaReview_Server_1.6.0_stage9/UPGRADE_ROLLBACK.md`：0017 在存在 `committing`/`delete_done`/`delete_pending` 行时降级 fail-closed；Windows 本地删除最低 Windows 10 1709（POSIX 语义）。

## 16. Android UI 大规模重构（2026-10-26，P1–P4）

用户确认方向：**Google Photos 式相册体验 + 文件夹分类**；批阅优先“视觉极简、进度与导航、更好的预览”。需求基线 `docs/UI_REFACTOR_PLAN.md`，Codex 实现、Lead 规划/复核/模拟器走查。

| 阶段 | 提交 | 内容 | 验证 |
| --- | --- | --- | --- |
| P1 | `be3c92d` | 设计 token + 公共组件；首页顶栏收敛/更多菜单/无边框网格；目录折叠顶栏+横滑面包屑；搜索全屏化 | 513 tests / 0 fail；Lead rerun 通过 |
| P2 | `66ca2f9` | 相册按日期分节网格；沉浸式查看器（缩放/翻页/下滑关闭/动画/±1 预取）；顶底栏与信息层重做 | 522 tests / 0 fail |
| P3 | `3abc77b` | 批阅极简顶栏+进度、底部五项动作、左右滑切换、上下滑决策、撤销、快进一屏 | 529 tests / 0 fail；Lead rerun 通过 |
| P4 | `ab9b393` | 打磨：沉浸页隐藏底栏、segmented/边缘渐隐、下拉刷新、触觉反馈、空/错/载态、无障碍与性能；修复批阅原生 Surface inset 时序 | 79 suites / 533 tests / 0 fail；Lead rerun 通过 |

模拟器（MediaReview_Test，demo 数据）截图：`review_meta/ui-refactor-20261025/shots/`（home、album、imgviewer、review）。
Alpha17 源码准备将 UI 重构写入 Release Notes，并把 Android 源码版本提升到 code24；未做：真机/家庭 LAN 验收、长按多选批量、Alpha17 APK 构建与发布。

## 17. Jellyfin 库接入与扫描（2026-10-26）

用户反馈“更新了库但不知道成功没”，账号 moviehub 已确认（仅本机 Jellyfin 使用）。排查结果与处置：

- 根因：Jellyfin 同时存在「视频合集（混合）」库（指向整个 `D:\视频合集`）和 19 个分类库（各自指向子目录）；Jellyfin 报 `Found duplicate path`，分类库因路径重复全部 0 项，扫描长时间卡在 ~63%。
- 处置（用户确认“删混合库，保留 19 个分类库”）：取消卡住的扫描 → 删除混合库（仅 Jellyfin 索引，未动文件）→ 触发全库刷新。当前 19 个分类库已开始正常索引。
- 进度：截至 2026-10-26 01:10，19 库合计约 **35,300 项**（`图片集` 31,863、`工作文件` 1,667、`高颜值素人泄密150套合集` 1,221 等），扫描仍在运行（~96%，照片多且部分图片损坏导致 TagLib 报错被记录并跳过）。监控日志：`temp/jellyfin_scan_monitor.log`。
- 后续：扫描完成后在 MediaReview App 的库选择里勾选需要的分类库并同步即可；本次未改 Server、未改 Jellyfin 媒体文件。

## 18. Android UI 重构状态（历史检查点，2026-10-26 01:10）

- P1 `be3c92d`、P2 `66ca2f9`、P3 `3abc77b` 已提交（本地 ahead 2，GitHub 网络不稳定待推送）；P4 打磨因 Codex 额度用尽于 04:33 复位后继续。
- 截图走查：`review_meta/ui-refactor-20261025/shots/`（home/album/imgviewer/review，模拟器 MediaReview_Test + demo 数据）。

## 19. Jellyfin 扫描完成与限制（2026-10-26 01:06）

- 扫描于 2026-10-26 01:05:46 完成（Completed），19 个分类库合计 **36,161 项**；监控日志 `temp/jellyfin_scan_monitor.log`。
- 库结构：19 个分类库（`图片集` 32,724、`工作文件` 1,667、`高颜值素人泄密150套合集` 1,221 等）。`D:\视频合集` 下仍有 5 个文件夹未建库：`BeautyBox`(55 文件)、`JX`(92)、`CS`(13)、`Maniacccccc`(79)、`大神胖哥迷玩`(407)；需要可再添加。
- 已知限制：部分含超大视频的库入库数远少于文件数（如 `12345` 131 个 mp4 → 1 项，`3165260528` 288 → 2 项）。Jellyfin 日志显示 ffmpeg 抽帧/探测超时（10s），大文件/异常封装会被跳过或延迟；这是 Jellyfin 媒体探测限制，不是扫描失败。
- 建议：MediaReview 优先使用**本地源**直接读取 `D:\视频合集`（不经 Jellyfin 探测）；Jellyfin 侧如需完整索引，可后续调大 ffprobe/ffmpeg 超时或改用兼容封装。
- 未修改任何媒体文件；未改动 Server/API。

## 20. Android UI P4 状态（2026-10-26）

- P4 打磨（沉浸页隐藏底栏、segmented/边缘渐隐、下拉刷新、触觉反馈、空/错/载态、无障碍、性能）已完成并提交为 `ab9b393`；Codex 与 Lead 复核均为 79 suites / 533 tests / 0 失败。
- 批阅视频黑屏在 P3 基线 APK 上同样复现（模拟器 goldfish/软解限制），不作为 P4 回归；真机视频画面待验收。

## 21. Android Alpha17 交付（2026-10-26）

- UI 重构 P1–P4 + 打磨已提交：`be3c92d`/`66ca2f9`/`3abc77b`/`ab9b393`；Alpha17 版本与 Release Notes：`4fe38d2`。
- 交付产物：`deploy_handoff/MediaReview_2.0.0-alpha17_stage9.apk`（43,655,762 bytes，SHA256 `8782f42b…6571f6`，versionCode 24 / 2.0.0-alpha17，signature PASS），已发送到用户确认的 QQ 邮箱（SMTP accepted + SEND_OK 附件字节/哈希一致）。
- Server 保持 1.6.0（本次仅 Android UI 变化）；`--package-verified` 因 1.6.0 同名发布包已存在而拒绝覆盖，故未生成新 Server ZIP。
- Jellyfin 已补建剩余 5 个分类库（共 24 库），重新扫描进行中（~96%）；真机验收仍待用户完成。

## 22. Jellyfin 24 库最终状态（2026-10-26 09:02）

- 24 个分类库全部建立并扫描完成（Completed），合计 **36,532 项**。
- 新增 5 库：`大神胖哥迷玩` 346、`JX` 19、`Maniacccccc` 4、`BeautyBox` 1、`CS` 1；超大视频仍受 Jellyfin ffprobe 超时限制（如 `12345` 131 文件→1 项），不是扫描失败。
- MediaReview 建议优先使用本地源直读 `D:\视频合集`；Jellyfin 作为可选源。


### 2026-10-27 运维最终收口

用户明确确认升级并发送。1.6.2已通过ControlHub完整备份后部署并启动；确认单实例、服务/端口归属及程序哈希一致，数据库正常。Alpha18附件通过MIME字节校验后SMTP接受，拒收0。详见DEPLOYMENT_CLOSURE_20261027.md。历史记录保留，手机/HomeLAN尚未验收。

## 生图分支合入记录（历史增补）

| 2026-10-29 | 新增 NSFW Studio 生图审核 V2 客户端（独立配对 / feed / 详情 / 按 image_id 审核 / 提示词版本评分）；真实 Bridge 缺 v2 能力，联调 BLOCKED；生产版本与产物未变 | 分支 `feature/nsfw-image-review-v2`；`docs/integration/NSFW_MOBILE_API_CONTRACT_V2.md`；`REVIEW_SUMMARY.md` |
| 2026-10-29 | 阶段 A 客户端加固：favorite-only、revision 防旧覆新、前台轮询、游标异常、恢复重验、评分幂等冻结、异步 generation 守卫、REMOVE tombstone、同源图片 URL、404/409/410 错误码；v2 契约与 OpenAPI 提案更新 | 分支同上；`NSFW_MOBILE_API_CONTRACT_V2.md` §5 差异表；NSFW JVM 88 tests |
| 2026-10-29 | V2A.1 边界补漏：REMOVE/tombstone revision 顺序、非法 revision 拒绝、统一游标恢复、空队列、首次历史计数、评分多 pending 与上限、兼容性 Unknown、connection epoch、取消语义、旧审核结果与 image_id 校验、关联严格失败关闭 | 分支同上；契约 §2.1/§2.6；NSFW JVM 106 tests |

## 10. 2026-10-29 分支：NSFW 生图审核 V2（客户端）

- **范围**：只在 MediaReview Android 客户端新增「生图审核」一级页面；不改 NSFW Studio 仓库、Bridge、数据库或部署，不改 MediaReview Server/Jellyfin 接口，不加端口。
- **状态**：客户端契约冻结、独立配对与凭据隔离、feed/详情/审核/评分 UI 与模拟接口测试已完成；全量 JVM 81 suites / 604 tests 全绿，`assembleDebug` 通过，lint 0 errors / 37 warnings；五入口与 Tab 状态恢复仪器测试在 `MediaReview_Test` AVD 通过（`OK (2 tests)`）。
- **阶段 A 加固（2026-10-29）**：favorite-only 不改审核状态；revision 单调防旧覆新；前台可见有界轮询；410 单次重置与游标不推进保护；恢复配对重验 capability；评分载荷冻结 + SavedStateHandle 恢复提示；detail/panel generation 防跨图污染；feed REMOVE tombstone；图片 URL 仅同源；404/409/410 与旧 401 码映射。NSFW JVM 88 tests 全绿。
- **V2A.1 边界补漏（2026-10-29）**：REMOVE tombstone revision 顺序、非法 revision/未知事件拒绝、sync/loadMore 共用游标恢复、终态缺少 next_cursor、REMOVE 空队列、首次历史不计数、评分 pending 多记录/上限/旧记录保留、兼容性 Unknown 不启动 feed、connection epoch、CancellationException、旧审核结果不显示成功、详情 image_id 校验、关联状态严格失败关闭。NSFW JVM 106 tests 全绿。
- **真实联调：BLOCKED**。当前 NSFW Bridge `/mobile/system/health` 为 `api_contract=1`，能力集不含 `mobile_feed_v2 / mobile_image_details_v2 / mobile_image_decision_v2 / mobile_prompt_review_v2 / mobile_prompt_evaluation_v2`；客户端按契约在配对前阻断并列出缺失能力。
- **未验证**：无真实 Bridge，未做手机端到端、未生成真机截图、未声明正式可用；真机（非模拟器）未连接。
- **版本**：Android 仍为 `2.0.0-alpha15 / code22`；未发版、未改生产服务与数据。
- 契约细节见 `docs/integration/NSFW_MOBILE_API_CONTRACT_V2.md`；审查摘要见 `REVIEW_SUMMARY.md` 顶部。

2026-10-30定向验证终态：`:app:testDebugUnitTest --tests com.mediareview.app.feature.v2.nsfw.NsfwReviewViewModelTest`，56项、0失败、0错误，BUILD SUCCESSFUL（5m48s）。日志history-counter-green-1030.log。修正版Release及手机验证尚未完成。
