# Stage8H 审查摘要

阶段：Player V2 & Home Experience Reconstruction。分支 feature/mediareview-v2-stage8h-player-home-rebuild；完整104节规格见 docs/STAGE8H_PLAYER_HOME_SPEC.md。

## 实现与范围

Server新增认证文件夹树、隐藏存储路径及folder_tree_v1；首页单双列、可收起顶部、目录面包屑与浏览锚点保留。相册与浏览窗口采用有界LRU及保留内容的后台刷新，Coil加载真实封面；Android移除Sprite请求，Server旧Sprite缓存/DB保留。

Full/Review共享进度条、方向锁、seek与倍速状态机。播放、切源、恢复与手势核验当前源/真实内核/宿主归属；seek以真实内核位置为起点。旧内核释放错误在确认换源/退出前按宿主和URL隔离，新内核错误反馈保留。根Snackbar按详情路由为操作按钮留出空间。

Android code18 / 2.0.0-alpha11；Server1.3.0 / Contract2 / 七能力。保留MediaRepository、Review Session、SQLite和GSY；无新数据库migration、Room、全量媒体下载或平行生命周期系统。Favorites全量接口及N+1属于既有性能债，没有退化证据不扩展优化。

## 当前有效门禁

- Server完整pytest577通过；Ruff服务器114文件及发布builder1文件通过；发布文档/打包契约114项回归通过。
- 最终Debug/Test冻结输入：Main B7502639 / Test8B979945。完整connected实际141项，0failure/error/skipped，Gradle成功12m15；完整JUnit XML与原输出已归档。
- 同一生产输入最终JVM444项/69 suites，0failure/error/skipped；Gradle JVM/lint成功9m42，lint0错误/42警告/15信息。旧输入门禁不代替本次结果。
- 失败与修复追溯保留：原37项34/3、严格补验5项4/1、两次139中断、分类/App16项13/3、根提示3/3、旧错误/控制时钟确定性2项RED及后续GREEN。最终整套覆盖其修正，不改写历史失败。
- 完整审查链及本轮生产错误监听/测试锚点增量补审C0/I0/M0。所有实际通过与截图/HTTP/真实位置证据均按输入区分，不以语义节点存在替代物理操作。

正式唯一assembleRelease、隔离Server EXE health与正式ZIP、最终push/clean及十文档Handoff仍待完成。代码门禁通过不代替发布产物核验。

## 边界与风险

PiP与外挂SRT/ASS按规格Deferred。内嵌字幕公开cue链无法稳定渲染，入口隐藏；多音轨通过真实公共API与设备专项。视觉/触感、逐档比例与窄屏体验、跨重启磁盘封面缓存及真实手机/LAN仍保留用户验证边界。

旧内核隔离不能撤回已经入队的GSY回调；本次确定性回归验证退休内核错误重放与当前错误反馈，不冒充真实断网验收。

生产升级桥仍BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT；本阶段只产出离线Server Artifact，不部署生产、不改真实Jellyfin/媒体、不清手机数据、不操作SCM或ControlHub生命周期。用户仅使用docs/STAGE8H_USER_VALIDATION.md的一条15步路线。

风险最高三点：真实手机/家庭网络尚未验证；窄屏视觉/提示/触感及跨重启缓存需体验确认；生产升级依赖外部正式合同。

阶段结论：发布收尾进行中（最终自动化已通过，正式产物和交接待核验；尚未声明CODE_READY / REAL_LAN_PASS / PRODUCTION_READY）。