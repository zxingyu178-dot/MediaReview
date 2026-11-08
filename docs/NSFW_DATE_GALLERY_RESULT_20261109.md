# 生图最新与日期图库（2026-11-09）

## 已批准的行为与实现

默认最新在前，可切最早在前；日期有全部、今天、昨天、近7天、自选日期。日期按手机本地日界转换为 UTC 半开区间，自选结束日期包含整天，近7天包含今天及前6天。图片日期取 Studio 生成/导入资产时间，不是 EXIF 时间或审核更新时间。

图库分页使用独立 page cursor，增量检查使用 sync cursor；保存审核位置时同时保存查询与 scope cursor。继续上次按原范围和图片恢复，不从首图逐页回放历史。新增图片只通知，不插入当前审核顺序；点击查看最新才重新查询。旧服务未声明 `mobile_gallery_query_v1` 时保留原浏览流程，并提示排序与日期筛选需要更新电脑端。

主要文件在 Android `feature/v2/nsfw`：`NsfwGallery.kt`（筛选/日期控件）、`NsfwReviewScreen.kt`（入口）、`NsfwReviewViewModel.kt`（独立分页/水位/续审/新图通知），以及 data 下的 `NsfwGalleryQuery.kt`、`NsfwRepository.kt`、`NsfwProfileStore.kt`、`NsfwBrowsePositionStore.kt`、`NsfwPairingRepository.kt`、`NsfwModels.kt` 和 `network/NsfwStudioApi.kt`。不改原图加载器、审核提交、提示词评分、数据库结构或服务生命周期。

## 验证记录

Gradle 公共参数：`--offline --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process`，工作目录 Android，JDK17，沿用已登记 SDK 与现有缓存。

- 初始新增日期/图库用例因缺少实现编译失败；不是 JUnit 断言失败。
- 第一轮 `:app:testDebugUnitTest` 选 `NsfwGalleryQueryTest`、`NsfwReviewViewModelTest`、`NsfwRepositoryContractTest`、`NsfwPairingRepositoryTest`：135项通过（分别4/84/31/16），10分1秒，相关生产 UI/Kotlin 编译通过。
- 定向复核发现旧详情请求会跨筛选写回，甚至旧401可能清除当前配对；补充两项回归，与续审范围/图片身份两项合同回归一起运行，4项均预期失败。修正筛选代次时使旧详情失效，并严格核对续审响应身份。
- 一次追加命令被 PowerShell 拆分 Gradle 属性，在配置阶段失败，未执行测试；已将该参数整体引用后重试。
- “查看最新”应切换到最新排序并保留日期，新增1项先断言失败（9分30秒），再修正。最终定向任务首启动在编译前主动停止以补齐新增类型导入，未执行测试；后续使用独立日志重试，不当成通过。
- 最终 `:app:testDebugUnitTest --tests '*NsfwReviewViewModelTest.gallery*' --tests '*NsfwRepositoryContractTest.gallery*'`：10项通过（VM7、真实HTTP合同3），0失败/错误，10分21秒；包含旧详情成功/401不干扰新筛选、旧查询晚返回不覆盖/清除新加载、最新按钮、独立游标、新图通知、旧scope续审及错误身份拒绝。生产与测试代码编译通过，`git diff --check`通过。不是一次全量135+10测试。

本轮测试日志保留于项目忽略的 `temp/gallery-*-20261109.log`；不提交真实图片、提示词或配对令牌。不重跑全量 Server/JVM/connected；只验证改动链路，第一轮未受影响用例复用证据，后续结果分轮记录。

## 发布边界

本文件当前记录源码候选；正式入口仍 Alpha34/code41、MediaReview Server1.6.6。新功能尚未打包、未手机或模拟器验收，不称真实局域网通过。新图库需要 NSFW 后端候选 c9b7813（PR38）；生产尚未部署。保持用户“生成队列自然完成后部署”的要求，不中断生成任务，不改 ComfyUI，不动真实审核/评分数据。后台退出修复及正式受管入口切换另按原维护计划执行。
