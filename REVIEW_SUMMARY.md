# MediaReview 阶段审查摘要

## 阶段

- 编号 / 名称：**Stage 8D — 全应用整合 / Legacy 清理 / 验证构建**
- 分支：`feature/mediareview-v2-stage8d-integration`
- 产品版本：`versionCode 11` / `versionName 2.0.0-alpha4`（Base：10 / alpha3）
- 目标：把已完成的模块组成一个干净、统一、可整体交付体验的 MediaReview 2.0，
  并完成旧代码清理与全量回归。

## 是否达到目标

达到。P0（重复分组稳定身份）、Legacy 清理、播放路径统一、版本/更新日志、全量回归、
APK 与 Handoff 全部完成（详见 `review_handoff/stage8d_pkg/00~09`）。

## 主要新增 / 修改

### Server
- `app/services/duplicate_scanner.py`：`_stable_group_id()` 取代 `idx` 式 group_id；
  `_bucket_groups` 改为接收哈希键。
- `tests/test_duplicate_group_id_stability_8d.py`（新增，3 项）。
- 无 API 变化（`group_id` 契约不变，值更稳定）、无数据库结构变化（NO SCHEMA MIGRATION）。

### Android
- 新增 `core/pairing/PairingRepository.kt` + `core/pairing/UrlNormalize.kt`（由 `feature/connect` 迁入）。
- 删除旧 1.x UI / 数据层 / 旧自研播放器 / 旧批阅窗口等（见 `docs/V2_RUNTIME_INVENTORY.md`、`04_LEGACY_CLEANUP.md`）。
- 版本号与 `ReleaseNotesCatalog` 更新（新增 Alpha 4）；跨版本合同测试同步。
- 唯一正式入口保持 `MainActivity → V2MainScreen`；唯一正式播放路径保持 GSY Native。
- androidTest：稳定性修复（更新日志异步持久化等待、GSY 播放器首帧等待预算 30s→60s）。

## 测试结果（真实执行）

| 项目 | 结果 |
|---|---|
| Server pytest 全量 | 439 passed / 0 failed / 0 error |
| Android JVM `testDebugUnitTest` | 334 passed / 0 failed / 0 error |
| Instrumentation `connectedDebugAndroidTest` | 49 passed / 0 failed / 0 skipped |
| Lint | 0 errors（41 warnings，无新增） |
| assembleDebug / compileDebugKotlin | BUILD SUCCESSFUL |
| 模拟器 Smoke | 8 张截图，无 crash |
| 生产部署 | 未执行 |

## 已知问题 / 遗留

见 `07_KNOWN_ISSUES.md`。要点：similar 算法未扩大；未引入 Room/SWR；Review 聚合未优化；
group_id 首次升级会重写（Keep 按新身份重建）；性能回归测试对机器负载敏感。

## 风险最高的三个点

1. group_id 算法切换后的历史 Keep 继承语义（预期行为，已说明）。
2. 大范围删除旧代码后的隐藏路径（已由全量编译 + 全量设备测试覆盖）。
3. 软件渲染模拟器下 GSY 起播时序波动（已通过提高等待预算 + 重跑确认）。

## 结论

阶段结论：**合格**（所有 Stage 8D 验收门槛满足；生产 Server 未自动部署；
等待用户安装 Stage 8D APK 做整体体验验收）。
