# REVIEW_SUMMARY — MediaReview 1.1 Task F：Windows 部署、升级、回滚与产物（1.1.0-rc1）

## 阶段编号与名称

- 阶段 F（takeover plan `docs/superpowers/plans/2026-08-30-mediareview-1.1-takeover.md`）
- 名称：Windows 部署、升级、回滚与产物（`1.1.0-rc1`）
- 提交：`feat(deploy): add transactional 1.1 upgrade`（F1–F5 收口）+ 本阶段收口提交
  （F6–F8：签名 APK、部署包组装、沙箱生命周期与独立审查）
- 基线：`f679222`（Task E 终点）

## 阶段目标

交付可在 Windows 上安装、事务式升级、失败回滚、安全卸载的 `1.1.0-rc1` 部署闭环：
8 个 PowerShell 部署脚本（PS 5.1 兼容、按 EXE 精确归属进程、仅 TCP 8766 + UDP 35001 防火墙、
卸载默认保数据）；自包含 Server EXE + 随包 FFmpeg；签名 Release APK（无 debug 兜底）；
`MediaReview_Migration_1.1.0` 部署包 + SHA-256 校验；沙箱 install/upgrade/rollback/uninstall
实测 + 独立部署安全审查 CLEAN + 验收 ZIP。

## 实际完成内容

- **F1 部署契约 RED→GREEN（`tests/test_deployment_contract_11.py`，24 例）**：8 脚本齐全且
  `#requires -Version 5.1`、特权脚本要求管理员、status 只读；EXE 精确名为
  `MediaReviewServer.exe`（禁旧 `Mediaserver.exe`）；stop/restart 按 EXE 绝对路径（`.Path`）
  归属进程、禁止按端口杀；install 磁盘检查/升级前备份 config+DB/失败回滚/写
  `CURRENT_VERSION`/检测旧版本；防火墙仅 TCP 8766 + UDP 35001；卸载默认保数据且删除数据
  目录位于 `-DeleteData` 守卫块内；FFmpeg 随包优先 + 系统 PATH 兜底；`build_deploy.py`
  产出 `MediaReview_Migration_1.1.0` 根 + 8 脚本 + SHA-256；spec 产物名；Android
  applicationId/版本/名称；LICENSE/THIRD_PARTY_NOTICES 存在；Release 签名 fail-closed。
- **F2 Server EXE**：PyInstaller onedir 构建 `server/dist/MediaReviewServer/MediaReviewServer.exe`；
  捆绑 `third_party/ffmpeg/{ffmpeg,ffprobe}.exe` 并记录版本/SHA-256；临时数据根冒烟——DB
  迁移到 head、health ok、日志解析路径与版本无密钥；配置读取容忍 UTF-8 BOM。
- **F3 用户控制**：`start/stop/restart/status.ps1` 按 EXE 绝对路径精确归属进程，不误杀无关
  监听；restart/install 内置 health 检查；status 只读。
- **F4 事务式升级**：stop owned service → 备份 config/DB/旧二进制 → staging `.new` →
  新程序同数据根迁移 + health → 原子提升 → 写版本标记；健康检查失败或 commit 阶段异常均
  经 `Restore-Previous` 回滚旧二进制/config/DB 并重启旧版；新增路径参数校验（
  `InstallDir`/`DataRoot` 含 `&|<>%"` 即 throw，防 schtasks/start.cmd 注入）。
- **F5 防火墙**：仅 `New-NetFirewallRule` TCP 8766 + UDP 35001，不触碰其他端口。
- **F6 签名 Release APK**：应用名家庭媒体管家 / 1.1.0 / versionCode 6 / 自适应图标；
  `android/key.properties` + `keystore/mediareview-release.jks`（均 git 忽略）为 Release 构建
  签名；`apksigner verify` 证书 DN `CN=MediaReview`、SHA-256 `592c2595…`；`build.gradle.kts`
  Release 强制 release 签名、缺失密钥构建失败（AGP `validateSigningRelease` 兜底），
  **移除 debug 签名兜底**（独立审查 I4）。
- **F7 部署包组装**：`deploy_handoff/MediaReview_Migration_1.1.0_20260902_2216.zip`（120 文件）
  含 server/8 脚本/ffmpeg/app-release.apk/文档；`SHA256SUMS.txt` 逐文件回验通过；密钥/绝对
  路径/用户名扫描干净（0 泄露）；`build_deploy.py` 默认强制 `app-release.apk`（缺失即失败，
  仅显式 `--allow-debug` 才回退 debug）。
- **F8 沙箱生命周期 + 独立审查**：`deployment/tests/test_sandbox_lifecycle.ps1` 对最终交付包
  解包实测 Phase A 全新安装 / B 事务式升级 / C 失败回滚 / D 卸载保数据 → 24/24 PASS；
  独立部署安全审查 **0 Critical / 3 Important / 10 Minor**，3 项 Important 全部修复并经独立
  复验 **3/3 → CLEAN**。收口新增根级 `ruff.toml`（与 server 同一套规则），使 `ruff check .`
  从仓库根对 scripts/ 统一生效并全绿（scripts 原无配置、不受 lint 约束）。

## 是否完整达到目标

是。F1–F8 全部完成。F6 的"从真机拉取已装 APK 比对指纹"与 Jellyfin/真机链路验证依赖已连接
真机（本机无设备），按 plan 与既有阶段约定留待 Task G 真机验收；其余 F6 产物（签名 Release
APK、证书指纹）已在本机完成并可复核。

## 核心架构 / API / 数据库变化

- 新增：`deployment/scripts/` 8 个 PowerShell 部署/运维脚本、`deployment/config.example.json`、
  `deployment/tests/test_sandbox_lifecycle.ps1`、`HANDOVER.md`、`UPGRADE_ROLLBACK.md`、
  `server/packaging/mediareview_server.spec`（EXE 名 `MediaReviewServer`）、
  `scripts/build_deploy.py`（部署包构建）、根目录 `LICENSE`、`THIRD_PARTY_NOTICES.md`。
- 修改：`server/app/core/config.py`（UTF-8 BOM 容忍）；`android/app/build.gradle.kts`
  （Release 签名 fail-closed）；`server/tests/test_deployment_contract_11.py`（24 例）。
- Server API/数据库：无新增端点、无数据库迁移（部署闭环不触碰业务 API）。
- Android：Release 签名方式变化（强制 release 证书，禁 debug 兜底）；版本 1.1.0/versionCode 6。

## Android UI/交互变化

无 UI 变化。仅 Release 构建签名配置 fail-closed（F6），应用名/版本/图标按 1.1.0 契约核对。

## 已执行测试与结果（真实命令）

```powershell
# 部署契约（24 例）
server\.venv\Scripts\python.exe -m pytest server\tests\test_deployment_contract_11.py -q
# => 24 passed（F1 22 例 + 新增签名 fail-closed 2 例）

# Server 全量（server/ 目录，隔离数据根）
server\.venv\Scripts\python.exe -m pytest tests --no-header -p no:warnings
# => 351 passed in 195.13s (0:03:15)，exit 0
server\.venv\Scripts\python.exe -m ruff check .          # => All checks passed!（根级 ruff.toml，scripts/ 统一规则）
server\.venv\Scripts\python.exe -m ruff format --check .  # => 136 files already formatted

# 沙箱生命周期（对最终交付包解包实测，一次性临时目录 + 端口 18866）
powershell -NoProfile -ExecutionPolicy Bypass -File deployment\tests\test_sandbox_lifecycle.ps1 `
  -SourcePackage "$env:TEMP\MR_pkg_final\MediaReview_Migration_1.1.0"
# => 通过: 24  失败: 0（Phase A/B/C/D 全 PASS）

# Release APK 签名与哈希
gradlew.bat :app:assembleRelease            # => BUILD SUCCESSFUL，validateSigningRelease 通过
apksigner.bat verify --print-certs app-release.apk
# => Signer #1 DN: CN=MediaReview,...; SHA-256 digest: 592c25955929ca7fa968f4450452a45919b9d8da6d91e8ca7274c5dd65973564
Get-FileHash app-release.apk -Algorithm SHA256
# => 19F6C90853A2FBF9EF504B1BDDB7E31B55EB4FD44BF32FF91B4259D1AC8C1AE0

# 部署包组装与校验
server\.venv\Scripts\python.exe scripts\build_deploy.py
# => ZIP 内校验和回验通过: 120 个文件
# => 部署包: deploy_handoff\MediaReview_Migration_1.1.0_20260902_2216.zip
# 解包后密钥/绝对路径/用户名扫描 => 0 泄露

# git diff --check => 通过（仅 LF→CRLF 提示）
```

## 独立审查

- 审查报告：本阶段 `.superpowers/sdd/task-f-independent-review.md`（Windows 部署安全审查）。
- 结论 **0 Critical / 3 Important / 10 Minor**。3 项 Important 已全部修复并经独立复验：
  - I1 计划任务 SYSTEM/HIGHEST 权限过高 → 降为 **NETWORK SERVICE / MEDIUM**，并对数据目录
    `icacls /grant "*S-1-5-20:(OI)(CI)M"` 授权写（install.ps1 / repair.ps1）。
  - I2 `build_deploy.py` 静默回退 debug APK → 默认强制 `app-release.apk`（缺失即失败），
    仅显式 `--allow-debug` 才回退。
  - I3 `THIRD_PARTY_NOTICES.md` 含真实盘符绝对路径 → 脱敏为 `%ProgramFiles%\Jellyfin\Server\...`。
- 10 Minor（不阻塞，记录在案）：M2 防火墙规则硬编码端口未随 `-Port`、M3 诊断脱敏覆盖为
  尽力而为、M4 删除路径护栏、M5 dist 复用无白名单、M6 Release 未开 R8 混淆、M7 密钥口令
  明文（本地、不入库）、M8 沙箱残留清理、M9 配置占位符 fail-fast、M10 备份累积策略。
  M1（schtasks 字符串注入）已随新增路径参数校验缓解。
- 正面确认：进程按 EXE 路径精确归属；卸载默认保数据；`Start-Process -FilePath` 具名参数；
  Server `api_key` 以 SecretStr 持有、无日志记录；SHA-256 生成与 ZIP 内回验逻辑正确；
  PS 5.1 兼容（NetSecurity 依赖 Win8+，符合目标环境）。

## 已知问题 / 遗留 TODO

- 真实手机 in-place 升级：需从真机拉取已装 `com.mediareview.app` APK，与 release 证书指纹
  （592c2595…）比对，证明无卸载重装/本地数据损失；本机无已连接设备，留待 Task G 真机验收。
- Jellyfin 直连、UDP 发现、配对、Wi-Fi 切换等真机链路验证留待 Task G。
- 防火墙/计划任务实际创建需要管理员，沙箱测试未覆盖（由契约测试静态断言 + 独立审查保障）；
  Task G 可在干净 Windows 环境做一次管理员级 install 实测。
- 10 Minor 记录在案不阻塞（可选后续加固）。

## 是否建议进入下一阶段

建议进入 Task G（全量验收与正式发布）。F 阶段已交付可安装/升级/回滚/卸载的部署闭环、
签名 Release APK 与校验完备的部署包；门禁全绿、沙箱生命周期 24/24、独立部署审查 CLEAN。
剩余仅真机链路与干净环境管理员级实测，属 Task G 范围。

## Agent 自认为风险最高的 3 个点

1. **NETWORK SERVICE 运行权限的实测覆盖不足**：计划任务降权为 NETWORK SERVICE/MEDIUM 属
   独立审查要求的核心修复，但防火墙/计划任务创建需管理员，沙箱测试（非管理员）无法实测
   该账户能否正常读写数据目录并运行 FFmpeg；已通过 `icacls S-1-5-20` 授权写数据根，
   但真实 Windows 上的 ACL/账户解析（含中文系统）需 Task G 干净环境管理员级 install 确认。
2. **Release 密钥口令强度**：`key.properties`/keystore 口令为 `mediareview123`（本地弱口令）。
   已确保 git 忽略、不进部署包，但若构建机被攻破则密钥泄露；Task G 建议评估更换强口令并
   妥善保管 keystore 离线副本。
3. **回滚真实性与备份累积**：升级/回滚逻辑经沙箱实测通过，但真实生产数据（媒体库大 DB、
   真实 Jellyfin 配置）下的升级迁移耗时与回滚可靠性未在真实环境演练；`backup/` 备份累积
   无清理策略，长期占用磁盘（M10）。

## 敏感信息说明

本阶段未将任何真实密钥/Token/密码/绝对路径提交仓库或打入部署包：`key.properties` 与
`keystore/*.jks` 均 git 忽略且不在 `build_deploy.py` 清单；`config.example.json` 的
`api_key` 为占位符 `__SET_DURING_SETUP__`；`THIRD_PARTY_NOTICES.md` FFmpeg 来源已脱敏为
`%ProgramFiles%` 相对写法；部署包解包扫描确认 0 泄露（无 `D:\` 构建机路径、无用户名、无
`mediareview123`）。测试全部使用一次性临时数据根/端口。

阶段结论：合格
