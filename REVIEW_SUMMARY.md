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

- 部署/发布补充专项42项通过；SCM写入保护与遗漏输入指纹两项均有先失败后通过记录。
- 独立复审：Critical 0 / 未解决 Important 0。
- Android JVM363项通过，0失败/错误；lint0errors/40warnings；编译、debug与androidTest构建通过。
- Ruff check/format与git diff --check通过。
- Server最终全量487 passed，0失败/错误，1条既有依赖弃用警告；220.54秒。
- 本轮真实HTTP播放4/4通过，29.311秒；Server与Demo完整播放器/批阅均实际播放且进度增长。
- 干净PyInstaller构建与Gradle clean assembleRelease通过；实际EXE health=ok/1.2.2/Contract2/六项能力。
- 实际APK= com.mediareview.app/code15/alpha8；apksigner PASS（v2/RSA2048）。
- RC预提交完整性实测167项SHA256全部通过；最终文档提交后以同一已验证构建输入、重新核验实际产物生成最终HEAD包，最终交付结果见00_HANDOFF。
- 构建与签名初次失败如实保留，隔离存储配置和本地迁移密钥路径已修复；未修改Server Runtime。
- 已登记既有打包脚本mediareview-build-deploy，并补项目Registry引用和全局CHANGELOG；未新增长期服务。

## 生产与用户验收边界

既有生产仍1.1.0，未覆盖，未执行备份；Jellyfin服务启动失败，自动登录未验证。已核对现有client_url与物理WLAN地址一致，不能代替手机连接证明。用户不在家，真实用户LAN与首帧耗时NOT TESTED。永久删除真实媒体NOT PERFORMED。

最高风险：用户手机实际网络路径待验；生产升级需要现有服务控制权限与停机备份；首次真机播放耗时尚无实测数据。
候选包完成后仅等待用户安装验收，不自动进入下一阶段。
阶段结论：有条件合格（READY_FOR_USER_VALIDATION；真实LAN未测试，生产未升级）。
