# MediaReview 阶段审查摘要

## 阶段

- 编号 / 名称：**Stage 8D.2 — Compatibility Gate / Server Access Guard / Deployment Preflight**
- 分支：`feature/mediareview-v2-stage8d.2-server-gate-closure`（基于 `21b2c00`）
- 产品版本：Android `versionCode 13` / `2.0.0-alpha6`；Server `1.2.0 → 1.2.1`（API Contract 仍为 `2`）
- 小版本政策：**NO USER APK**
- 目标：在整个 App 范围落实 `health → compatibility confirmed → business API`；
  部署后 Server 版本 / API Contract 不正确即视为部署失败。

## 是否达到目标

达到（详见 `review_handoff/stage8d2_pkg/00~10`）。

## 主要新增 / 修改

### Android
- `core/pairing/PairingRepository.kt`：新增 `Result.Incompatible(version, apiContract, connection)`；
  旧 Server（`api_contract < 2`）不再伪装成 `HealthOk`，`connectServer()` 收到 Incompatible 立即停止，
  **不 verify / 不保存 Server Mode / 不清 Token**。
- `feature/v2/data/server/V2ServerAccessGuard.kt`（新增）：`requireBusinessAccess()` 统一 Guard，
  按 `Online / Incompatible / Probing / Offline / AuthRejected / Unconfigured` 抛对应异常；
  `shouldLoadServerBusinessData(status)` 只对 `Online` 返回 true。
- 三个 Server 仓库（`V2ServerMediaRepository` / `V2ServerOrganizeRepository` /
  `V2ServerReviewSessionRepository`）的 `private suspend fun <T> call(...)` 首行统一调用 Guard（最后一道防线）。
- `feature/v2/data/server/V2ServerStatus.kt`：`onRequestSuccess()` 改为 no-op（普通业务请求成功
  **不能**把 `Incompatible/Probing/...` 升级为 `Online`）；`onRequestFailure` 仅在 `Online` 时降级；
  新增 `reset()` 清理 serverVersion / apiContract / compatibility。
- `feature/v2/home/V2HomeViewModel.kt`：SERVER 冷启动与 Demo→Server 切换改为
  `restore → 显示壳 → status=Probing → probe()`，仅 `Online` 才 `reloadAll()`（UI 仍立即显示，不阻塞 Splash）。
- `feature/v2/data/V2MediaRepositoryRouter.kt`：所有会发网络请求的 suspend 方法重新归类到 `dataActive()`（过 Gate）；
  纯内存读取接口保持不抛（避免 Composition 崩溃）；`setAlbumCover` 为本地 DataStore，不过 Server Gate。
- 版本 bump `versionCode 13` / `versionName 2.0.0-alpha6` + Alpha 6 更新日志。

### Server / 部署
- `app/__init__.py`：`__version__ = "1.2.1"`，`SERVER_API_CONTRACT = 2`（**不升 Contract**）；
  `pyproject.toml` 同步 1.2.1。
- `deployment/version.ps1`（新增）：`$ExpectedServerVersion` / `$RequiredApiContract` 唯一事实源。
- `deployment/scripts/install.ps1`：`Test-HealthContract` 同时校验 `status` + `version` + `api_contract` + 能力清单；
  最终健康失败**不再 Warning 假成功**——升级路径自动回滚旧版本并 `exit != 0`（`UPGRADE FAILED` + `ROLLBACK PASS|FAIL`），
  首次安装失败则停进程、删自启任务、标记 `INSTALL FAILED` 并 `exit != 0`。
- `status.ps1` / `diagnose.ps1` / `start.ps1` / `restart.ps1` / `repair.ps1`：输出 / 记录 version + api_contract（+ capabilities）。
- `scripts/build_deploy.py`：VERSION → 1.2.1，并把 `deployment/version.ps1` 随部署包发布（修复 dot-source 缺失缺陷）。
- 无 API 破坏性变更；**NO SCHEMA MIGRATION**。

## 测试结果（真实执行）

| 项目 | 结果 |
|---|---|
| Server pytest 全量 | **452 passed / 0 failed / 0 error**（exit 0） |
| Android JVM | **351 passed / 0 failed / 0 error**（47 个结果文件聚合） |
| Lint | **0 errors**（41 warnings，无新增） |
| compileDebugKotlin | **BUILD SUCCESSFUL** |
| 部署沙箱生命周期测试 | **17 通过 / 0 失败**（Gate 5 例 + 升级失败回滚镜像） |
| Instrumentation（定向） | **8 / 8 passed**（Stage8D1CompatibilityUiTest / Stage8C1WhatsNewUiTest / Stage4BrowserUiTest） |
| 用户 APK | **NOT GENERATED — SMALL VERSION POLICY** |
| 生产部署 | **NOT PERFORMED** |

> 说明：`Stage8AServerModeTest` 依赖宿主机 Mock Server（`10.0.2.2:8799`），该 Mock Server 非仓库内产物，
> 本阶段未重新运行；其覆盖的 Server 访问顺序已由 JVM 合同测试（§27/§28/§55）以 MockWebServer 请求顺序断言覆盖。

## 已知问题 / 遗留

见 `07_KNOWN_ISSUES.md`。要点：`test_task_manager` 后台循环单例测试在整机高负载下偶发失败、单独复跑通过（历史已知）；
未引入 Room / SWR / 新播放器功能；AGP/compileSdk 警告与 Kotlin deprecated icon 警告记为技术债。

## 风险最高的三个点

1. Guard 覆盖完整性（三分支仓库 call() + Router 重新分类已用 JVM 测试锁死「未 Online 时 0 业务请求」）。
2. `onRequestSuccess` no-op 后 Online 的唯一来源是 health probe（已加「业务成功不能升级 Incompatible」测试）。
3. 部署 Gate 的失败语义（final health 失败必须回滚 / 非零退出，已由沙箱镜像测试覆盖）。

## 结论

阶段结论：**合格**（Stage 8D.2 验收门槛满足；未生成用户 APK；未部署生产）。
