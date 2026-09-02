# REVIEW_SUMMARY — MediaReview 1.1 Task G：全量验收与正式发布准备（1.1.0-rc1）

## 阶段编号与名称

- 阶段 G（takeover plan `docs/superpowers/plans/2026-08-30-mediareview-1.1-takeover.md`）
- 名称：全量验收与正式发布（clean 门禁、生产备份/回滚演练、100k 性能、真实 Jellyfin 冒烟、
  设备侧受限记录、最终独立审查、产物重建与校验、`1.1.0-rc1` 标记）
- 提交：`52b5234`（branding 测试同步 1.1.0/versionCode 6）+ `49d45c5`
  （Jellyfin client 忽略环境代理 trust_env=False + 回归测试）+ `dae857b`
  （G 阶段文档/审查/证据收口）
- 基线：`93613ab`（Task F 终点）；标签：`1.1.0-rc1` → `dae857b`
- 独立审查：`.superpowers/sdd/task-g-independent-review.md`，结论 **CLEAN（0C/0I/2M）**

## 阶段目标

按 `docs/ACCEPTANCE.md` P0/P1 完成全量验收，交付可发布的 `1.1.0-rc1`：clean 全量门禁、
生产配置/DB 备份（无明文密钥）+ 升级/回滚演练、56k/100k 性能验证、真实 Jellyfin 集成冒烟、
设备侧能力如实受限记录、最终独立审查、从已审查提交重建产物并回验校验和。**约束：无真实手机
验收最多标记 rc1，不 tag `1.1.0`**；正式 `1.1.0` 待真机门通过。

## 实际完成内容

- **G1 clean 门禁**（证据 `review_meta/g1_server_pytest.txt`、`g1_android_gate.txt`、
  `g1_android_jvm_lint.txt`、`server_lint.txt`、`server_format.txt`）：Server 全量 pytest 通过
  （351 passed, exit 0）+ ruff check/format 全绿；Android `testDebugUnitTest`/`assembleDebug`/
  `assembleRelease`/`lintDebug` 全部 BUILD SUCCESSFUL；迁移升级/回滚与部署契约（24 例）随
  Server 全量 pytest 覆盖；`git diff --check` 通过。
- **G2 生产配置/DB 备份 + 升级/回滚演练**（`review_meta/g2_backup_manifest.txt`、
  `g2_config_masked.json`、`g2_sandbox_lifecycle.txt`）：备份清单只含脱敏配置；沙箱
  install/upgrade/rollback/uninstall 实测通过。
- **G3 56k/100k 性能验证**（`review_meta/g3_perf_100k.txt`、`g3_refresh_sync.txt`）：缓存分页
  P95<1s（实测单页 0.031-0.06s）、DB 查询<250ms、refresh 响应<500ms、列表请求零 Jellyfin 扫描、
  同步失败保留旧缓存可浏览。
- **G4 真机连接验证**：**受限（无设备）**。环境核查：`adb devices` 无设备、`.android\avd`
  为空、SDK 无 emulator 二进制。服务端 UDP 发现/手动 IP 规范化/重复配对单记录/撤销重配对/
  回环 URL 禁发均已有 pytest 覆盖；设备侧留待真机。
- **G5 媒体与组织验证**：服务端侧 pytest 全覆盖；**真实 Jellyfin 集成冒烟通过**
  （`review_meta/g5_real_jellyfin.txt`）：健康检查 + 配对 + 读取真实库 20 个 + 1967 条媒体同步 +
  Direct/HLS 播放合同。冒烟发现并修复 **Jellyfin client 采信环境代理**问题（`49d45c5`）：
  部署机存在 `all_proxy=socks5://…` 时，httpx 因缺 socksio 在构造期抛 ImportError 导致全部
  Jellyfin 调用 500；加 `trust_env=False`（V1 仅局域网/回环直连，不采信环境代理）+ 回归测试
  `test_client_ignores_env_proxy_trust_env_false`。已知限制：当前真实 Jellyfin 实例
  `/Auth/Keys` 返回 500（服务端实例问题），设备级播放凭据签发记为 rc1 known limitation。
- **G6 无障碍与布局**：**受限（无设备）**，记录待真机（360/390/740 宽、font≥1.3、TalkBack、
  48dp 触控、中文标签）；代码层响应式与 48dp 触控门槛已由既有 JVM/Compose 测试覆盖。
- **G7 最终独立审查 + 产物重建 + rc1 标记**：
  - 独立审查 CLEAN（0C/0I/2M；M-1 docstring 机制描述不精确但断言有效、M-2 trust_env 同时禁用
    环境 CA 变量信任）。
  - Android 最终门禁 BUILD SUCCESSFUL（`review_meta/g7_android_gate.txt`）。
  - 产物重建（`scripts/build_deploy.py`，先删旧 dist 强制重建 EXE 含代理修复）：新 EXE 冒烟
    通过（迁移 0001→0014、health ok/version 1.1.0、日志密钥扫描 0 命中，
    `review_meta/g7_exe_smoke.txt`）；部署包
    `deploy_handoff/MediaReview_Migration_1.1.0_20260903_0036.zip`（100,697,221 字节，
    SHA-256 18A2E836…），外部解包逐文件回验 SHA256SUMS **VERIFIED=163 BAD=0**、密钥扫描
    CLEAN（`review_meta/g7_deploy_zip.txt`）。
  - **标记 `1.1.0-rc1`**（`dae857b`）；真机门未过不 tag `1.1.0`。
- **G8 交付产物**：Release APK（家庭媒体管家 1.1.0/versionCode 6）、迁移部署包 + SHA256SUMS、
  文档（README/LICENSE/THIRD_PARTY_NOTICES/HANDOVER/UPGRADE_ROLLBACK）、
  ACCEPTANCE.md 逐项服务端已验/待真机标注、测试/性能/真机报告（review_meta/）。

## 主要新增/修改文件

- `server/app/adapters/jellyfin/client.py`：`trust_env=False`（修复环境代理劫持）。
- `server/tests/test_jellyfin_client.py`：+1 回归测试（15 passed）。
- `android/app/src/test/java/com/mediareview/app/BrandingResourceTest.kt`：版本断言同步
  versionCode 6 / versionName "1.1.0"。
- `docs/ACCEPTANCE.md`：P0/P1 逐项标注服务端已验/待真机；`docs/DEV_LOG.md`、`TASKS.md`：
  G1-G9 状态与证据；`.superpowers/sdd/task-g-independent-review.md`：独立审查报告。
- `review_meta/`（git 忽略，随验收 ZIP 打包）：G1/G2/G3/G5/G7 证据文件。

## 核心架构变化、API 变化、数据库变化

- 无 API / 数据库 schema 变化（G 阶段为验收与收口，非功能开发）。
- 架构级行为收敛：JellyfinClient 不再采信环境代理（`trust_env=False`），消除部署机带
  SOCKS/HTTP 代理变量时的启动/调用失败，同时使携带 API key 的请求头不再经代理转发
  （安全收窄）。

## Android UI/交互变化

- 无用户可见 UI 变化。品牌测试断言与 `1.1.0`/versionCode 6 对齐（仅测试同步）。

## 已执行测试、测试结果、lint / format / type check 结果

- Server 全量 pytest：**351 passed, exit 0**（含迁移升级/回滚、部署契约 24 例、Jellyfin
  client 回归；`g1_server_pytest.txt`）。
- ruff check：**All checks passed**；ruff format：**94 files already formatted**。
- Android：**BUILD SUCCESSFUL**（testDebugUnitTest + assembleDebug + assembleRelease +
  lintDebug，`g7_android_gate.txt`）。
- `git diff --check`：通过（仅 LF→CRLF 提示）。
- 独立审查：CLEAN（0C/0I/2M）。
- EXE 冒烟：迁移 0001→0014 + health ok + 日志密钥扫描 0 命中。
- 部署包外部回验：SHA256SUMS 163/163 一致；密钥/绝对路径扫描 CLEAN。

## 已知问题 / 遗留 TODO

- **无真机/模拟器**：`.android\avd` 为空、SDK 无 emulator。Android 设备侧全部验收（真机连接、
  媒体墙/播放器/批阅操作体验、无障碍与布局、Direct<3s/HLS<8s、真机 in-place 升级指纹比对）
  留待真机环境，产物为 `1.1.0-rc1`。
- 当前真实 Jellyfin 实例 `/Auth/Keys` 返回 500（服务端实例问题），设备级播放凭据签发在 rc1
  记为 known limitation。
- M-1/M-2（审查 Minor）：回归测试 docstring 机制描述不精确（断言有效）；trust_env=False 禁用
  环境 CA 变量信任（依赖系统信任库者不受影响），建议部署文档注明。
- F 阶段 10 Minor 与 E 阶段 3 Minor 记录在案不阻塞。

## 是否建议进入下一阶段

G 阶段收口完成，代码与产物达到 rc1 发布就绪（0C/0I、全部门禁绿、校验和完整）。**正式
`1.1.0` 的发布门 = 真机验收**（连接链路、媒体体验、无障碍布局、in-place 升级指纹比对），
无法在本机无设备条件下完成，需用户提供真机或模拟器环境后执行。建议：先交付 rc1 产物给用户
真机试装；真机门通过后再 tag `1.1.0` 并走最终发布与 Hermes 交付。

## Agent 自认为风险最高的 3 个点

1. **设备侧验收缺失**：无真机/模拟器，Android 全部 P0/P1 设备侧项（连接/媒体墙/播放器/批阅/
   无障碍/布局）未经真实设备验证。代码与状态机有 JVM/Compose 测试覆盖，但「自动化测试通过
   ≠ 真机可用」；这是 rc1 不能升级为正式 1.1.0 的核心原因。
2. **真实 Jellyfin `/Auth/Keys` 500**：当前实例无法签发设备级播放凭据，播放合同回退到无凭据
   direct URL（依赖 Jellyfin 对局域网游客开放）。换一个正常的 Jellyfin 实例后需重验
   Direct/HLS 端到端播放与凭据签发/撤销闭环。
3. **干净 Windows 管理员级部署未实测**：F 阶段沙箱为非管理员环境，防火墙/计划任务创建
   （NETWORK SERVICE 运行权限、icacls ACL）在真实干净 Windows 上未管理员级实测；沙箱 24/24
   与部署契约 24 例只能静态保障，真实部署行为需在目标机 install.ps1 实测。

## 敏感信息说明

本阶段未提交/打包任何真实密钥、Token、密码、绝对路径：`key.properties`/`keystore/*.jks`
git 忽略且不在部署包清单；`config.example.json` 的 `api_key` 为占位符；部署包解包密钥扫描
CLEAN（0 命中）；Jellyfin 冒烟使用临时 API key 未入库、未打包；测试全部使用一次性临时数据根/
端口。本机 `C:\ProgramData\MediaReview` 存在 F 阶段沙箱部署残留（非生产数据，未触碰）。

阶段结论：合格（rc1；正式 1.1.0 待真机门通过）
