# MediaReview Stage 8E.1 审查摘要

阶段：Pre-LAN Closure / Upgrade Safety。基线 a080422b0d03ca64f48b77856b54776ca265a15b；分支 feature/mediareview-v2-stage8e.1-pre-lan-closure。
Android Alpha8/code15、Server1.2.2/Contract2保持；运行时代码、播放器/首页/批阅/图片Viewer/整理业务、API及数据库schema未修改。

## 实际变更

发布工具统一枚举全部RC复制源，prepare分离build_inputs/package_inputs，拒绝旧receipt及两类输入漂移；最终staging、ZIP和独立APK均与原receipt连接，保护历史产物，失败清理仅本轮输出。新增变更拒绝及TOCTOU回归。

既有纳管服务升级脚本默认只读WhatIf；隔离事务实现停服后快照、配置/DB/WAL/SHM/旧binary/WinSW一致备份与完整hash集合、staging/switch/health与恢复，17场景121断言。停服前快照及隐藏文件两项审查问题已修复；测试闭包作用域问题有实证修复。脚本已登记既有全局Registry、项目引用和CHANGELOG，不新增服务/binding。

正式生产adapter尚缺官方共享锁、批准绑定和审计入口，**生产Execute保持BLOCKED**。完整事务fixture通过不能写成正式可运行生产升级工具；整体PRE_LAN_READY未达到。

## 已执行验证

- Server最终串行529项通过，0failures/0errors/0skipped，313.650秒，1条既有Starlette/httpx弃用警告。首轮并行负载下100k行random查询1.204秒超1秒门槛，原失败日志保留，未放宽断言。
- 包装专项58项与部署相关回归84项通过；独立复审关闭两项时序P1。
- 升级17场景121断言，PS7、PS5.1及当前命令宿主直接调用通过；AST通过。
- Server按项目cwd的ruff check/format通过，builder专项check/format通过；git diff --check通过。错误cwd的初次lint调用如实保留并纠正。
- Android lint成功：0errors、40warnings、10information；本阶段JVM测试未重跑，先前Stage8E的363项仅历史基线。
- 原Alpha8 Release APK真实GSY内核4/4通过：完整005 40s/1280×720/523→1661ms；批阅001 24s/1280×720/700→1936ms，006 22s/1024×768/508→1736ms，Downloads002 10s/1280×720/564→1766ms。未修改/重签目标APK；测试专用helper只读取实际内核并导航实际页面。
- 黑盒首页、Demo图片、收藏、整理、首次更新/重启不自动弹/设置手动Alpha8通过；累计历史更新fixture显示Alpha2至Alpha8，关闭后已读15、重启不再自动弹，通过；安装APK哈希不变，未进行旧手机迁移。
- 静态版本声明、既有实际EXE隔离health、现有manifest与EXE/APK哈希一致；未重建用户APK/RC。

## 边界、风险与遗留

旧Alpha4与Alpha8证书不同，原位升级BLOCKED；用户决定暂不迁移，实际手机安装身份未核实。Jellyfin历史退出码1根因UNKNOWN，开始Stopped，后续非本阶段动作变为Running/8096回环可响应，启动发起者未归属。本阶段未启动/停止/改配置；生产MediaReview仍1.1.0，未复制/修改真实DB，未删除真实媒体。Real Jellyfin LAN NOT TESTED。

最高风险三项：正式ControlHub升级入口未闭合；旧手机签名与迁移数据边界未验；实际手机网络/Server视频与连续批阅尚未验收。
审查假数据清理遭自动审批拒绝，目录保留并说明，不绕过重试。交付以最终Git提交、远端验证、clean及Stage8E.1 Handoff SHA256为准；不自动进入8F。

阶段结论：有条件合格（离线实现与证据可交接，正式升级BLOCKED，整体PRE_LAN_READY未达到）。
