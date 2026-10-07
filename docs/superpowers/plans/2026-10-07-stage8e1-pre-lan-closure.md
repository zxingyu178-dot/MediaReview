# Stage 8E.1 离线收尾实施计划

用户提供的 Stage 8E.1 说明为设计依据；2026-10-07 用户明确继续离线收尾、暂不迁移 App。

## 边界

- 基线 a080422，分支 feature/mediareview-v2-stage8e.1-pre-lan-closure。
- 不修改 Android/Server 运行时代码，沿用 code15/Alpha8、Server 1.2.2/Contract2。
- 不生成新用户 APK/RC，不卸载旧 App。签名不兼容保持 BLOCKED。
- 生产服务、真实数据库、媒体只读；不得启动/停止 Jellyfin 或生产 MediaReview。
- 升级工具真实执行路径只在隔离 fixture 验证；实际环境仅 WhatIf。

## 任务与验证

1. 发布输入完整性：新增 package_inputs，prepare 分开保存 build_inputs/package_inputs；打包拒绝任一漂移、旧证据、EXE/APK 替换。统一枚举打包文件，新增脚本也纳入。参数化回归测试，版本 SSOT 检查源码/部署/builder/已有 EXE/已有 manifest。
2. 受控升级工具：先阅读 ControlHub 官方合同，沿用 AIHome.MediaReview 身份与官方控制入口。默认只读；实现停止、端口释放、一致备份和哈希、staging、切换、health/readiness、失败恢复。fixture 覆盖成功与各失败路径，重复脚本按既有 Registry 规范登记。
3. Jellyfin 只读诊断：限定相关事件与启动失败附近日志、脱敏；记录 1066/1 与缺失证据，结论只能 ROOT_CAUSE_IDENTIFIED/LIKELY_CAUSE/UNKNOWN。
4. 最终 Release APK smoke：在独立模拟器数据安装既有 Alpha8 APK，验证首次/再次更新日志、设置手动、首页/视频/图片/批阅/收藏/整理。实际播放测量 duration、解码宽高、position 递增。与 Real Jellyfin LAN NOT TESTED 分开。
5. 各任务审查、必要测试/lint；更新 DEV_LOG、TASKS、REVIEW_SUMMARY，提交 push。生成阶段验收包和唯一用户交付 Stage8E.1 Handoff（十份说明、脱敏日志、git/source/SHA256），复核 Git clean。全部离线 gate 满足才能 PRE_LAN_READY。

## 执行方式

使用 subagent-driven-development skill 为独立任务分派新执行 Agent，任务后审查与最终整体审查；主 Agent 负责 Release smoke、整合和交接。任何无法证明的结果如实记录，不扩大至 Stage 8F。
