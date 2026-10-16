# Stage8I 审查摘要

阶段：Media UX Polish & Review Reliability。分支 feature/mediareview-v2-stage8i-ux-polish；完整35节规格见 docs/STAGE8I_MEDIA_UX_SPEC.md。实现、最终设备门禁与正式离线产物已核验；最终推送和十二文档Handoff执行结果以旁置交付回执为准。

## 实现与架构

固定缩略图profile及完整变体缓存键；媒体墙/相册不请求原图。Viewer保留preview、当前原图优先、相邻source有界预取并取消过期任务。Full/Viewer共用服务端确认的待删除与撤销；共享有界横向Seek，保留双击±10秒和进度条绝对定位。

首页显示实际版本/API/能力诊断；文件夹封面与计数、子目录、递归范围和面包屑。Review使用原生VerticalPager、单手势最多一条、横向主导消费与子控件优先；当前视频循环，EOF竞态通过公开render恢复同一内核，稳定页预取相邻端点与poster。范围支持多选、库归属验证、冻结会话、显式重建及旧Server禁用文件夹选择。

Server解析MediaSources大小并回填索引/队列。删除receipt仅统计实际删除且路径已不存在的普通文件，整条路径链拒绝链接和重解析点；failed保留，missing/failed不计释放。Android传递实际字节，区分旧Server未知和Demo；整理刷新保留内容与重试。未重写现有架构、转发完整视频流或引入平行生命周期系统。核心改动位于图片profile/cache、共享pending/seek、folder/scope/review、delete_queue/receipt和现有构建/交接工具；无新增数据库migration。

Android code19 / 2.0.0-alpha12；Server1.4.0 / API2，新增thumbnail_profiles_v1、review_folder_filter_v1、delete_receipt_v1并保留旧七能力。七条中文更新说明、首装/跨版/关闭持久化同步。

## 当前证据

- Server完整round2：638项通过，Ruff通过，141输入无漂移。随后仅交接脚本/测试修正，受影响27项及Ruff通过，不冒称修正后全638重跑。
- Android完整round2：488 JVM项通过；lint零错误/42警告/16信息；Debug Main/Test构建通过，354输入无漂移。此后四处androidTest修改不改变产品源码、JVM或lint输入，复用匹配结果。
- 最终第三轮完整设备178项全部通过，零失败/错误/跳过，实际退出0，用时1276.588秒，354输入无漂移。保留首轮174/178与第二轮SystemUI ANR中断20项的历史；第三轮独立全178通过，不是跨轮相加。Main字节不变，新Test仅四文件窄修。真实6秒Direct/HLS循环/EOF、Full回归、快速五次翻页、原有blockedNext与图像布局均在最终XML中通过。
- Tasks1–8定向HTTP、真实6秒Direct/HLS循环/EOF、原生快慢五次翻页、Full手势、真实临时文件删除、范围与文件夹导航证据见DEV_LOG及review_meta/stage8i。只读AVD实际画面/位置/源证据与真实手机/家庭LAN分开。
- 全分支独立源码审查C0/I0/M1，四测试窄修补审C0/I0/M2。三个既有格式差异与测试诊断完备性为非阻断Minor；Ruff检查实际通过。Task8清理边界Minor已关闭。
- 真Alpha11→候选Alpha12图片100张body6785700→1329400字节，当前原图2355→967ms；首轮邻完成采样不足，新Test第三轮补齐真实HTTP及Coil完成1641/2079ms；其2048MB guest RAM与旧成对1536MB数据分列。Review首RGB代理波动，不宣称全面提速或硬件首帧SLA。

## 待完成与风险

唯一正式assembleRelease通过；APK code19/alpha12版本与签名核验通过，公共证书SHA256与正式Alpha11一致。新Server EXE隔离health为1.4.0/API2/十能力，正式Server ZIP逐项SHA核验通过。构建及打包提交为91b8406c6ab814326010736b3bf2d57f44723208；后续仅DEV_LOG/TASKS/REVIEW_SUMMARY/计划文档改变，不改变冻结源码及打包输入。最终push/clean与十二文档Handoff在最后文档提交后执行，以旁置终态回执为准。最高CODE_READY / READY_FOR_USER_VALIDATION，不表示真实手机/LAN或生产验收。

风险最高三点：首轮rapid末帧超时根因未确定，保留失败并以完整复验评估；真实手机/家庭网络及视觉触感待用户验证；生产升级依赖外部ControlHub正式合同。

继续BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT；不部署生产、不改真实Jellyfin、不删用户媒体、不清手机数据。不声称REAL_LAN_PASS或PRODUCTION_READY。

阶段结论：有条件合格（CODE_READY / READY_FOR_USER_VALIDATION；最终推送及Handoff以交付回执为准，真实手机/LAN待用户验收）。
