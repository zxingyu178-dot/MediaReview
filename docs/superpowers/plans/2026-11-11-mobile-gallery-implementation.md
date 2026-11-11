# 手机生图图库与看图修复实施计划

> **For agentic workers:** 使用subagent-driven-development按任务执行；独立图库/删除调查可按dispatching-parallel-agents并行，主Agent负责手势与整合。用户已批准开始，不再请求重复实施授权。

**Goal:** 精简手机图库、提供直接缩放和左右切图、稳定刷新反馈，并定位修复整理删除失败，明确最新排序的后端上线条件。

**Architecture:** 复用NsfwReviewViewModel、查询范围、认证图片loader与ZoomableImage。图库只改展示；独立看图舞台处理Pager/缩放/信息层，不改变审核提交与回执语义。删除先证据后修复。

**Tech Stack:** Kotlin、Compose、现有Coroutines/Coil/Retrofit；不加依赖、不改视频播放器架构。

## Global Constraints

- 工作区 `.worktrees/nsfw-image-review-v2`，分支 `codex/core-flow-polish`，起点fcffa2d；保留其他工作区和日用改动。
- 不用真实媒体执行审核、评分或删除；不清手机数据/配对；不改全局环境。
- 服务启停经既有ControlHub；当前Studio/桥为旧日用版本，新版排序必须真实核对能力，不反转局部列表冒充最新。
- 只跑改动相关验证；新候选递增编号、原签名安装，不覆盖旧候选或擅自提升正式指针。

## Task 1：紧凑图库

- [ ] 修改 `android/app/src/main/java/com/mediareview/app/feature/v2/nsfw/NsfwGallery.kt`：紧凑标题与图标操作，筛选移入面板，次级入口进更多；普通同步不改变行高，重要错误保留。
- [ ] 不修改VM/ReadyPane接口；保留函数签名、按ID选择与anchor更新、旧桥能力门禁。
- [ ] 使用现有UI/纯逻辑回归中的最小用例；展示性样式改动不写镜像源码测试，由最终构建和设备布局验证。

## Task 2：看图与稳定状态

- [ ] 在 `nsfw/NsfwImageStage.kt` 封装当前页原图按需加载与按ID分页，接收items/currentId/baseUrl/loader/canNavigate/onSelect/onPrevious/onNext/onReady/onScale/onTap/resetZoomToken。
- [ ] `NsfwReviewScreen.kt` 使用固定画布与叠层，缩放淡出普通信息，缩回适屏恢复；保留审核/收藏/评分及点击切换的无障碍出口。
- [ ] `viewer/ZoomableImage.kt` 仅增加可选就绪回调与外部复位token，默认行为不变；不重构普通播放器。
- [ ] 为缩放显隐的容差、稳定序号与新列表不重置当前身份补最小行为测试，实际运行确认RED，再实现并定向GREEN。
- [ ] 当前id不变时后台同步不触发滚动，不按items.size重复滚动缩略条；未知总数显示第N张，不反复显示已加载总数。

## Task 3：删除证据与修复

- [ ] 只读确认当前手机错误、服务身份、队列失败码/来源能力。不得点击真实最终删除或修改生产数据库。
- [ ] 优先复用 `DeleteQueueViewModel.kt`、`DeleteQueueScreen.kt` 与 `server/app/services/delete_queue.py` 的正常路径，不绕过library/root/identity/nonce保护。
- [ ] 在最小隔离夹具重现确认的根因；补失败用例后修复对应层，只运行相关JVM或pytest。
- [ ] 若生产必须升级或失败属于未选库/只读来源等保护，准备具体恢复方案，不把保护关掉冒充修复。

## Task 4：排序上线核对与交付

- [ ] 只读核对PR39/main/日用版本和能力，评估保留工作流的备份/部署路径；需要的部署另留明确授权边界。
- [ ] 使用登记JDK/Gradle及项目可写缓存，定向测试与assemble；有失败先定位，不重跑无关全套。
- [ ] 按既有发布入口生成递增签名候选，核对哈希/签名/输入，保留数据安装。
- [ ] 真机验证紧凑布局、直接缩放/切图/返回、刷新稳定与重启；隔离样本验证删除，不把旧桥验证算新版功能通过。
- [ ] 更新PROJECT_STATUS、DEV_LOG及结果，按真实变更提交push，保留已知限制。
