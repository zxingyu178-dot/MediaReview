# MediaReview Stage 8E 审查摘要

阶段：候选发布、生产预检与真实局域网验收准备。
基线：0b3fda176110b9accbdf6efc0dd09c58edef8023。
分支：feature/mediareview-v2-stage8e-real-lan-rc。
Server 1.2.2 / API Contract 2；Android code15 / 2.0.0-alpha8。

## 实际变更

既有打包脚本改为干净构建，不复用旧 EXE/APK；启动隔离的实际 EXE 校验 health，读取实际 APK manifest 并验证签名。记录构建输入和整个 Server 分发目录指纹，最终清洁 HEAD 打包时重复核对，逐项回验 ZIP SHA256。Server 与 Android 版本分别写入生成的 HANDOVER/RELEASE_MANIFEST。
安装脚本兼容新 RC 目录，拒绝绕过既有 WinSW 服务；SCM 查询失败时在任何生产写入前停止。新增发布完整性测试、预检报告、安装指南和 LAN 验收说明。Android 仅升级版本与累计更新说明；无播放器、API 或数据库改动。
主要位置：scripts/build_deploy.py、deployment/scripts/install.ps1、Android release notes/version、server/tests/test_release_package_integrity_8e.py。

## 已执行验证

- 部署/发布专项39项通过；SCM写入保护与遗漏输入指纹两项均有先失败后通过记录。
- 独立复审：Critical 0 / 未解决 Important 0。
- Android JVM363项通过，0失败/错误；lint0errors/40warnings；编译、debug与androidTest构建通过。
- Ruff check/format与git diff --check通过。
- Server首轮全量484项通过；补充2项审查回归后的最终全量待执行。
- 本轮4条真实HTTP播放、干净EXE/Release APK及最终包验证仍在执行，实际结果在最终文档与交接包补充。

## 生产与用户验收边界

既有生产仍1.1.0，未覆盖，未执行备份；Jellyfin服务启动失败，自动登录未验证。已核对现有client_url与物理WLAN地址一致，不能代替手机连接证明。用户不在家，真实用户LAN与首帧耗时NOT TESTED。永久删除真实媒体NOT PERFORMED。

最高风险：用户手机实际网络路径待验；生产升级需要现有服务控制权限与停机备份；首次真机播放耗时尚无实测数据。
候选包完成后仅等待用户安装验收，不自动进入下一阶段。
阶段结论：有条件合格（最终自动化与产物验证进行中）。
