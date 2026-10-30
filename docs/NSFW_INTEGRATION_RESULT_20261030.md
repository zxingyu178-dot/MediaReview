# 生图审核对接结果（2026-10-30）

## 交付与功能

Alpha23/code30，源码84aada2。日常分支feature/mediareview-local-only与feature/nsfw-image-review-v2均已推送并统一到该提交。独立APK为deploy_handoff/MediaReview_2.0.0-alpha23_nsfw.apk，唯一入口deploy_handoff/CURRENT_RELEASE.json已更新。

- SHA256：b28ec7e3dc8958ba51450203d5b49553b1dc4c8d781aa1fae108c22184db9428；20,797,240字节。apksigner校验成功，证书与已安装版本一致。
- 独立生图审核与配对，审核桥固定8877。新图增量追加队尾，不自动改变当前选择；显式点击提示可看最新图并清除计数。
- 保留/淘汰回执被接受后才前进；失败停留，主动切图不被旧回包打断。收藏/重置不自动切图。
- 最近审核可撤销：仅列表与详情快照一致、原状态可准确恢复时开放；使用原成功回执的updated_at校验，冲突不覆盖、不重提；成功后消费撤销记录。网络异常保留入口供人工核对，不自动补发。
- 提示词评分与图片审核分开。按可靠的提示词版本查看多图样本后评分；版本未知须显式选择整体评分。新样本只提示复评，不自动修改分数。
- 提示词、生成与入库时间、尺寸等详情支持未知值；不猜测历史导入图片与提示词的归属。主次按钮分行，缩略图跟随当前项。

## 验证证据

证据位于review_meta/nsfw-integration-20261029（不提交私有截图、凭据与数据库）。

- `:app:testDebugUnitTest --tests '*NsfwReviewViewModelTest'`：最终64项、0失败/0错误，undo-green-1030.log。历史计数和连续审核分别观察RED后修复；撤销/新图3项也先观察RED。此前整合定向JVM164项通过属于另一轮，不合并声称单轮通过。
- `:app:assembleRelease --max-workers=1 --no-daemon -Pkotlin.compiler.execution.strategy=in-process`：BUILD SUCCESSFUL，18m2s，alpha23-experience-final-1030.log。
- `aapt dump badging`确认包名com.mediareview.app、Alpha23/code30；`apksigner verify --print-certs`成功；复制到交付目录后哈希复核一致。
- 服务端分轮：核心36项、兼容22项、迁移2项、冷启动6项通过。没有重新跑全套或connected测试。
- 旧Alpha23候选包曾保留数据安装，真实Wi-Fi配对、缩略图和预览实际显示成功。**最终交互修正版未在手机验证**，当前设备不可连接。未给用户真实图片做审核或评分。

## 运行与限制

审核桥服务端源码分支codex/mobile-review-v2，3fd93ab已push；部署树等价提交0ca2673。迁移0021已应用，迁移前一致性数据库与程序备份已保留。端口8877已按用户授权登记，仍由Studio父运行时管理，不另建生命周期系统。

2026-10-30经ControlHub首轮启动就绪超时，carrier已自行清理；确认无残留监听后有界重试成功。Studio健康ok/0.37.0；父运行时启动审核桥RUNNING/owned=true/8877。对唯一自建256×256样本完成真实配对认证、缩略图/预览/原图读取、KEEP/RESET/REJECT、相同请求回执重放、同键不同载荷409及过期状态409。经官方两阶段接口精确清理该样本，GET确认404，并吊销唯一临时测试设备。证明real-bridge-probe-1030.json全部6项为true；没有修改用户真实图片。

MediaReview生产Server未升级；现有1.6.4交付包保持原样，内置Alpha22事实在CURRENT_RELEASE中保留。NSFW主工作树存在其他人的未提交改动，未覆盖。部署一致性脚本硬要求app HEAD=origin/main，当前功能分支与部署cherry-pick模式不满足该历史检查，不能声称该门禁通过；不伪造构建戳。

## 使用方法

在ControlHub启动NSFW Studio V2，在Studio设置中启动手机审核桥并取得配对码。手机进入底部“生图审核”，输入电脑局域网地址（默认8877）和配对码；此配对独立于媒体库服务器。保留/淘汰只改审核状态，不删除文件。提示词无可靠关联时可看图、审核，但不能强行认领评分。

剩余验收：最终APK保留数据覆盖安装、布局、评分面板与审核写入的真机复核。手机曾安装的是同版本早期候选包，不能以其验证替代当前文件。代码与电脑端链路已收口；手机当前不可连接，不能声称整个目标已完成。
