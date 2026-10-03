# MediaReview 阶段审查摘要

## 阶段

- 编号 / 名称：**Stage 8D.1 — Server 兼容 / Keep 迁移 / 系统栏**
- 分支：`feature/mediareview-v2-stage8d.1-compatibility-closure`（基于 `6dce891`）
- 产品版本：Android `versionCode 12` / `2.0.0-alpha5`；Server `1.1.0 → 1.2.0`
- 小版本政策：**NO USER APK**
- 目标：解决 Server/App API 兼容协议、Server 版本号、旧 Duplicate Keep 安全迁移、
  深色状态栏图标、部署兼容门禁。

## 是否达到目标

达到（详见 `review_handoff/stage8d1_pkg/00~09`）。

## 主要新增 / 修改

### Server
- `app/__init__.py`：`__version__ = "1.2.0"`、`SERVER_API_CONTRACT = 2`、`SERVER_CAPABILITIES`。
- `api/v1/system.py`：health 增加 `api_contract` / `capabilities`。
- `services/duplicate_scanner.py`：Legacy 序号 ID 识别 + `(type, 成员集合)` 签名安全迁移。
- 新增测试：`test_health_contract_8d1.py`(4)、`test_legacy_keep_migration_8d1.py`(7)。
- 部署合同同步到 1.2.0（build_deploy.py / deployment ps1 / pyproject / 合同测试）。
- 无 API 破坏性变更；**NO SCHEMA MIGRATION**。

### Android
- `core/pairing/PairingRepository.kt`：`REQUIRED_SERVER_API_CONTRACT = 2`、`CompatibilityState`、
  `ConnectionState(+compatibility/serverVersion/serverApiContract)`、health contract Gate（不清 Token）。
- `core/model/ApiModels.kt`：`HealthOut(+api_contract=1/+capabilities)`。
- `feature/v2/data/server/V2ServerStatus.kt`：`Incompatible` 状态 + 版本/contract + `ServerIncompatibleException`。
- `feature/v2/data/V2MediaRepositoryRouter.kt`：Incompatible 时读取类接口 fail-fast。
- `feature/v2/settings/V2DataSourceSheet.kt`：`服务器版本过旧` 提示（警告色）+ 双方版本。
- `MainActivity.kt`：`enableEdgeToEdge(SystemBarStyle.dark(...))`（浅色系统栏图标）。
- 版本 bump + Alpha 5 更新日志。

## 测试结果（真实执行）

| 项目 | 结果 |
|---|---|
| Server pytest 全量 | 450 passed / 0 failed / 0 error |
| Android JVM | 340 passed / 0 failed / 0 error |
| Lint | 0 errors（41 warnings，无新增） |
| compileDebugKotlin / UnitTest / AndroidTest | BUILD SUCCESSFUL |
| Instrumentation（定向） | SystemBarUiTest / Stage8D1CompatibilityUiTest / Stage8C1WhatsNewUiTest / Stage8AServerModeTest |
| 用户 APK | NOT GENERATED — SMALL VERSION POLICY |
| 生产部署 | NOT PERFORMED |

## 已知问题 / 遗留

见 `07_KNOWN_ISSUES.md`。要点：similar 算法未改；未引入 Room/SWR；未做 Room/播放器/Viewer/Review
新功能（§46）；AGP/compileSdk 警告与 Kotlin deprecated icon 警告记为技术债，本阶段不扩范围。

## 风险最高的三个点

1. Legacy Keep 迁移的匹配边界（已用 7 项测试锁死：同类型同成员才迁移）。
2. 兼容性 Gate 的 fail-fast 位置（读取类接口 guard；组合期取值接口不抛异常）。
3. 系统栏样式对 Player/Viewer 全屏的影响（播放器自行隐藏/恢复，已加设备测试与截图）。

## 结论

阶段结论：**合格**（Stage 8D.1 验收门槛满足；未生成用户 APK；未部署生产）。
