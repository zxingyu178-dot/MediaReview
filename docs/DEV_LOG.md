# 开发日志

> Agent 每完成一个阶段必须追加记录,不允许覆盖历史。

### 2026-09-06 — 1.1.0-rc1 后 · 媒体墙竖屏筛选区层叠修复与紧凑重排（真机反馈问题 1 根治 + 问题 2）

真机截图一:筛选区控件相互重叠(滑块画在搜索框上、`类型` 按钮被盖住)。模拟器竖屏
uiautomator bounds 数值复现:`排序` 与 `封面 N 列+Slider` 行完全同位。

根因:`ResponsiveMediaWallLayout` 的筛选容器是 `Box`(层叠语义),竖屏 stacked 分支把
OutlinedTextField、Spacer、FlowRow、封面滑块行 4 个同级元素直接 emit 进该 Box,
全部层叠在左上角。8f10090 的"文字+滑块同行"修复未触及该容器语义。横屏 compact
分支只有单一 Row,不受影响。修复:stacked 分支包进 `Column(fillMaxWidth)`。

真机截图二(层叠修复后):竖屏筛选区占 5 行太高,整宽滑轨浪费空间。重排为两行:
第一行 = 搜索框(weight) + `封面 N 列` 标签 + −/+ 步进 IconButton(48dp 触控,
2/5 边界禁用);第二行 = 筛选按钮横向滚动 Row。移除竖屏 Slider 与 FlowRow
(及其 ExperimentalLayoutApi OptIn),横屏 compact 分支不变。

验证:

- 竖屏 bounds:搜索框+步进器一行(y≈424-592),筛选按钮滚动一行(y650-703),
  筛选区高度 ~730px → ~280px;`类型` 恢复可见。
- 步进器实测:点 `+` → `封面 3 列`,点 `-` → `封面 2 列`。
- 横屏 bounds 与修复前一致(单行横向滚动)。
- `:app:assembleDebug` / `:app:testDebugUnitTest` / `:app:lintDebug` 均 BUILD SUCCESSFUL。
- 修复随 debug 验证版 APK(20260906)提供真机安装。

遗留:正式 1.1.0 发布产物需重打包(含本修复);真机门其余项仍待真机验收。

### 2026-09-02 — MediaReview 1.1 Task G · 全量验收与正式发布准备

Task G 全量验收：clean 门禁、生产备份与回滚演练、100k 性能验证、真实 Jellyfin 集成冒烟、
设备侧受限记录、最终独立审查与 rc1 产物收口。

G1 全量自动化门禁（clean 基线，证据在 `review_meta/`）：

- Server：全量 pytest 通过（`g1_server_pytest.txt`）、ruff check/format 全绿（`server_lint.txt`/
  `server_format.txt`）。
- Android：`g1_android_gate.txt` 记录 `testDebugUnitTest` + `assembleDebug` + `assembleRelease` +
  `lintDebug` 全部 BUILD SUCCESSFUL（0 error）。
- 迁移升级/回滚、部署契约（24 例）随 Server 全量 pytest 一并覆盖；`git diff --check` 通过。

G2 生产配置/DB 备份（无明文密钥）+ 升级/回滚演练（`g2_backup_manifest.txt`、`g2_config_masked.json`、
`g2_sandbox_lifecycle.txt`）：备份清单只含脱敏配置；沙箱 install/upgrade/rollback/uninstall 实测通过。

G3 56k/100k 性能验证（`g3_perf_100k.txt`、`g3_refresh_sync.txt`）：缓存分页 P95<1s（实测单页
0.031-0.06s）、DB 查询<250ms、refresh 响应<500ms、无列表时 Jellyfin 扫描、同步失败保留旧缓存可浏览。

G4-G6 设备侧受限（无真机/模拟器）：

- 环境核查：`adb devices` 无设备；`.android\avd` 为空；`E:\aihome\tools\android-sdk` 无
  `emulator\emulator.exe`。按「无真实手机验收最多 rc1」约束，设备侧项目如实标记受限。
- G4 真机连接（UDP 发现/手动 IP/重复配对单记录/重启重连/撤销重配对/回环 URL 禁发/Wi-Fi 切换恢复）：
  服务端对应逻辑已由 pytest 覆盖；设备侧留待真机。
- G5 媒体与组织：服务端侧 pytest 全覆盖；真实 Jellyfin 集成冒烟通过（`g5_real_jellyfin.txt`：
  健康检查 + 配对 + 真实库读取 20 个 + 1967 条媒体同步 + Direct/HLS 播放合同）。冒烟中发现并修复
  **Jellyfin client 采信环境代理**问题（`49d45c5`）：部署机存在 `all_proxy=socks5://…` 时，httpx
  因缺 socksio 扩展在构造期抛 ImportError，导致 /libraries 等一切 Jellyfin 调用 500；加
  `trust_env=False` 并补回归测试 `test_client_ignores_env_proxy_trust_env_false`（V1 仅局域网/回环
  直连，不应被环境代理劫持）。已知限制：当前真实 Jellyfin 实例 `/Auth/Keys` 返回 500（服务端实例
  问题），设备级播放凭据签发在 rc1 文档中记为 known limitation。图片/雪碧图/批阅 P0P1/收藏一致性/
  nonce 删除/重复整理均已有 pytest 覆盖；设备侧操作体验留待真机。
- G6 无障碍与布局（360×740/390×844/740×360、font≥1.3、TalkBack、48dp 触控、中文标签）：无设备，
  记录待真机；代码层响应式与 48dp 触控门槛已由既有 JVM/Compose 测试覆盖。

G7 最终独立审查 + 产物重建 + rc1 标记：

- 独立审查（`.superpowers/sdd/task-g-independent-review.md`）：HEAD=49d45c5，对抗式覆盖
  trust_env 安全/回归测试有效性/版本断言/无 key 序列化/无视频代理/禁止工件/文档诚实性，
  结论 **CLEAN（0 Critical / 0 Important / 2 Minor）**。M-1 docstring 描述机制不精确（断言仍有效）；
  M-2 trust_env=False 同时禁用环境 CA 变量信任（依赖系统信任库者不受影响），建议部署文档注明。
- Android 最终门禁（JAVA_HOME=JDK17）：`testDebugUnitTest` + `assembleDebug` + `assembleRelease` +
  `lintDebug` 全部 BUILD SUCCESSFUL（证据 `review_meta/g7_android_gate.txt`）。
- 产物重建（`scripts/build_deploy.py`，先删旧 dist 强制重建 EXE）：
  - 新 EXE 冒烟通过（`review_meta/g7_exe_smoke.txt`）：迁移 0001→0014 完整、health ok / version 1.1.0、
    日志密钥扫描 0 命中。
  - 部署包 `deploy_handoff/MediaReview_Migration_1.1.0_20260903_0036.zip`（100,697,221 字节，
    SHA-256 18A2E836…）；外部解包逐文件回验 SHA256SUMS **VERIFIED=163 BAD=0**；
    密钥扫描 CLEAN（`review_meta/g7_deploy_zip.txt`）。
  - Release APK 与 F 阶段一致（无 Android 生产代码变更）：15,550,794 字节，
    SHA-256 19F6C9…，release 证书 CN=MediaReview（592c2595…）apksigner verify 通过。
- 冒烟中发现并记录：`MEDIAREVIEW_DATA_ROOT` 仅用于定位 config.json；临时根无 config.json 时
  storage.data_root 回落默认 `%ProgramData%\MediaReview`（既有行为）。真实部署由 install.ps1
  在 config.json 显式写 storage.data_root，不受影响。
- **标记 `1.1.0-rc1`**（真机门未过，不 tag `1.1.0`）。

G8 交付产物（见交付物清单与手交区）：

- APK：`android/app/build/outputs/apk/release/app-release.apk`（1.1.0 / versionCode 6 / 家庭媒体管家）
- 迁移包：`deploy_handoff/MediaReview_Migration_1.1.0_20260903_0036.zip`（163 文件 + SHA256SUMS）
- 校验和：SHA-256 清单（ZIP 内 SHA256SUMS.txt + 本日志记录 ZIP/APK 散列）
- 文档：README/LICENSE/THIRD_PARTY_NOTICES/HANDOVER/UPGRADE_ROLLBACK
- 报告：ACCEPTANCE.md（P0/P1 逐项服务端已验/待真机标注）、test/performance/phone 报告（review_meta/）

G9 Hermes 邮件交付：待用户确认收件人后执行。

---

### 2026-09-02 — MediaReview 1.1 Task F6-F8 · 签名 APK、部署包组装、沙箱生命周期与独立审查

Task F 收口：签名 Release APK、部署包组装与 SHA-256、沙箱 install/upgrade/rollback/uninstall
实测、独立部署审查 CLEAN、验收 ZIP。

代码与测试：
- `android/app/build.gradle.kts`：Release 强制使用 release 签名（`signingConfigs.getByName("release")`），
  缺失 `key.properties` 任一字段时间指向不存在的 `__MISSING_RELEASE_KEY__`，由 AGP
  `validateSigningRelease` 使 Release 构建失败——**禁止 debug 签名兜底**（独立审查 I-4）。
  `key.properties` 与 `keystore/mediareview-release.jks` 均 git 忽略、不进包。
- `server/tests/test_deployment_contract_11.py` 增至 24 例：新增
  `test_release_signing_is_fail_closed_no_debug_fallback`（无条件绑定 release 签名、无条件
  create("release")、禁旧兜底表述）。
- `deployment/scripts/install.ps1`：
  - 计划任务由 SYSTEM/HIGHEST 降为 **NETWORK SERVICE / MEDIUM**（最小权限），并对数据目录
    `icacls ... /grant "*S-1-5-20:(OI)(CI)M"` 授权其写入（独立审查 I1）。
  - 新增路径参数校验：`InstallDir`/`DataRoot` 含 `&|<>%"` 即 throw（防 schtasks/start.cmd 注入）。
  - 升级 commit 阶段（删旧二进制→Move-Item→写版本标记）以 try/catch + `Restore-Previous` 包裹，
    任何异常回滚旧版本并重启。
- `deployment/scripts/repair.ps1`：计划任务同样降为 NETWORK SERVICE/MEDIUM + icacls。
- `scripts/build_deploy.py`：默认强制 `app-release.apk`（缺失即 `return 1` 失败），仅显式
  `--allow-debug` 才允许回退 debug（独立审查 I2）；ruff format 规范化；`subprocess.run` 补
  `# noqa: S603`。
- `THIRD_PARTY_NOTICES.md`：FFmpeg 来源路径脱敏为 `%ProgramFiles%\Jellyfin\Server\...`
  （移除真实盘符路径，独立审查 I3）。
- 新增根级 `ruff.toml`：与 server/pyproject.toml 同一套规则（select E,F,W,I,UP,B,S,C4），
  使 `ruff check .` 从仓库根对 scripts/（build_deploy.py、build_review_handoff.py）统一生效；
  scripts 原先无配置、不受 lint 约束，现一并纳入并全绿。server/ 子树仍由就近的
  server/pyproject.toml 优先解析。

验证与产物：
- Release APK：`gradlew :app:assembleRelease` BUILD SUCCESSFUL，`apksigner verify` 证书
  DN `CN=MediaReview`、SHA-256 `592c2595…`（release 证书）；APK 15,550,794 字节，
  SHA-256 `19F6C90853A2FBF9EF504B1BDDB7E31B55EB4FD44BF32FF91B4259D1AC8C1AE0`。
- 部署包：`deploy_handoff/MediaReview_Migration_1.1.0_20260902_2216.zip`（120 文件），
  含 server/8 脚本/ffmpeg/app-release.apk/文档，SHA256SUMS 逐文件回验通过；
  密钥/绝对路径/用户名扫描干净（0 泄露）。
- 沙箱生命周期（`deployment/tests/test_sandbox_lifecycle.ps1`，对最终交付包解包实测）：
  Phase A 全新安装 / B 事务式升级 / C 失败回滚 / D 卸载保数据 → **24/24 PASS**。
- Server 全量：`pytest tests` → **351 passed in 195.13s**、exit 0；`ruff check .`（根级
  ruff.toml）All checks passed；`ruff format --check .` 136 files already formatted。
- 部署契约聚焦：`tests\test_deployment_contract_11.py` → 24 passed。
- 独立部署安全审查：**0 Critical / 3 Important / 10 Minor**；3 项 Important（I1 任务权限、
  I2 debug APK 兜底、I3 绝对路径）已全部修复并经独立复验 **3/3 → CLEAN**；10 Minor 不阻塞
  （M1 已随路径校验缓解，M2-M10 记录为后续阶段可选加固）。

遗留（Task G 处理）：
- 真实手机 in-place 升级：需从真机拉取已装 `com.mediareview.app` APK 与 release 证书指纹比对
  （本机无已连接设备，留待 Task G 真机验收）。
- Jellyfin 直连、UDP 发现、配对等真机链路验证留待 Task G。

### 2026-09-02 — MediaReview 1.1 Task F1-F5 · 部署契约、EXE、进程控制、事务式升级、防火墙

Task F 前半段完成 Windows 部署闭环：可移植契约测试、自包含 EXE 构建、精确进程归属、
事务式升级与回滚、最小端口防火墙。

代码与测试：
- `server/tests/test_deployment_contract_11.py` 新增 22 例（F1 RED→GREEN）：8 个脚本齐全且
  PS 5.1 兼容（`#requires -Version 5.1`）、特权脚本要求管理员、status 只读；EXE 精确名为
  `MediaReviewServer.exe`（任何脚本不得引用旧 `Mediaserver.exe`）；stop/restart 按 EXE 绝对
  路径（`.Path`）归属进程，禁止 `Get-NetTCPConnection + Stop-Process` 按端口杀进程；install
  做磁盘检查、升级前备份 config+database、失败回滚并写 `CURRENT_VERSION`、检测旧版本；
  防火墙只开放 TCP 8766 + UDP 35001；uninstall 默认保留数据且删除数据目录位于
  `if ($DeleteData)` 守卫块内；FFmpeg 优先随包、`Get-Command ffmpeg` 兜底系统 PATH；
  `build_deploy.py` 产出 `MediaReview_Migration_1.1.0` 根 + 8 脚本 + SHA-256；spec 产物名为
  `MediaReviewServer.exe`；Android applicationId/版本/名称与 1.1.0 一致；LICENSE 与
  THIRD_PARTY_NOTICES 存在。
- `deployment/scripts/`：新增 `start.ps1`/`stop.ps1`/`restart.ps1`/`status.ps1`（F3 用户控制）；
  重写 `install.ps1`（F4 事务式升级 + F5 防火墙）、`uninstall.ps1`（F3 保数据）、`repair.ps1`
  （PS5.1 头 + `MediaReviewServer.exe`）、`diagnose.ps1`（进程归属按 EXE 路径）。
- `server/packaging/mediareview_server.spec`：EXE 名 `Mediaserver` → `MediaReviewServer`（F2）。
- `scripts/build_deploy.py`：产出固定根 `MediaReview_Migration_1.1.0`，递归 SHA-256 校验和并在
  ZIP 内回验，含 8 脚本/APK/FFmpeg/文档（F7 基础）。
- `server/app/core/config.py`：读取配置容忍 UTF-8 BOM（PowerShell 5.1 `Set-Content -Encoding
  UTF8` 写 BOM），`utf-8-sig` 读取。
- 根目录新增 `LICENSE`（MIT）与 `THIRD_PARTY_NOTICES.md`（FFmpeg 版本/SHA-256 记录）（F2）。
- `android/app/build.gradle.kts`：versionName `1.1.0`、versionCode `6`（>5）（F6 契约前置）。

EXE 构建与冒烟（F2）：
- 安装 PyInstaller；`third_party/ffmpeg/` 复制 `ffmpeg.exe`/`ffprobe.exe` 并记录 SHA-256。
- `server/dist/MediaReviewServer/MediaReviewServer.exe`（13.8MB onedir）构建成功。
- 临时数据根冒烟：DB 迁移到 head、health 返回 ok、日志解析路径与版本无密钥泄漏。
- FFmpeg 随包优先、系统 PATH 兜底，缺失时优雅降级。

TDD 与验证：
- Server 全量：`pytest -q` → **349 passed**、exit 0；`ruff check .` All checks passed；
  `ruff format --check .` 94 files already formatted（先修 3 处再全绿）。
- 部署契约聚焦：`tests\test_deployment_contract_11.py` → 22 passed。
- `git diff --check` 通过（仅 LF→CRLF 提示，符合既有仓库行为）。

遗留（F6/F7/F8 处理）：
- F6 签名 Release APK：Release 构建类型未配置签名、项目内无 keystore、SDK 无已连接设备、
  无 AVD/emulator 组件；需与用户确认签名密钥决策（卸载重装/本地数据损失风险）。
- F7 需补齐 `HANDOVER.md`/`UPGRADE_ROLLBACK.md` 并组装 `MediaReview_Migration_1.1.0`。
- F8 沙箱安装/升级/回滚/卸载 + 独立部署审查 + 验收 ZIP。

### 2026-09-02 — MediaReview 1.1 Task E · Windows 运维控制台

Task E 实现 Windows 运维控制台：状态总览 / 配对与设备 / 媒体库与索引 / 缓存管理 /
后台任务 / 错误与日志 六面板，全部复用既有 `/api/v1` 服务，不复制业务逻辑，无媒体墙。

代码（本提交 `feat(admin): add operations console`）：
- `app/admin.py` 全量重写：单页运维控制台（内联 CSS/JS）。响应式网格
  `minmax(min(100%,340px),1fr)` + 480px 断点适配 360px/桌面；原生 `<dialog>` 危险操作
  二次确认（清理缓存/撤销设备/清理已用码/取消任务/开始重复扫描，`askConfirm` 可复用）；
  全部交互为原生 button/input/a + `:focus-visible`，键盘全可操作；令牌输入支持 LAN 使用，
  本机自动配对仅回环；15s 轻量轮询。
- `app/api/v1/system.py` 新增：
  - `GET /system/dashboard`：版本/host/port/LAN 地址/Jellyfin 配置与可达性（尽力而为探测，
    不外泄 key）/媒体库勾选/索引计数/同步状态聚合。
  - `POST /system/cache/clear?confirm=true|1`：危险操作服务端 confirm 硬门槛（缺/错 400），
    只清 cache/{thumbnails,sprites,previews,temp}，随后同步失效 ready 雪碧图清单。
  - `GET /system/errors`、`GET /system/logs`：最近日志行输出前经 `_mask_log_text` 脱敏
    （mr_ token/Bearer/api_key/配对码/Windows 盘符/UNC/POSIX 路径）。
  - 诊断导出复查：日志打包前逐行脱敏、配置 masked、表计数白名单，绝不含媒体原文件/密钥。
- `tests/test_ops_console_11.py` 新增 21 例（E1 RED→GREEN）：未认证 LAN 拒绝/回环放行、
  缓存清理 confirm 门槛与范围、配对码回环限制、错误/日志/诊断脱敏、对抗式密钥扫描
  （dashboard/errors/logs/诊断 ZIP 四路输出）、控制台页面六面板/无媒体墙/dialog 二次确认/
  键盘可操作/无敏感值。

TDD 与验证：
- Server 全量：`pytest -o addopts= -p no:cacheprovider -v` → **326 passed, 1 warning in
  195.82s (0:03:15)**、exit 0（`-o addopts=` 仅为非 TTY 下捕获计数行；`-q` 同集 exit 0）；
  `ruff check .` All checks passed；`ruff format --check .` 93 files already formatted。
- 聚焦 E 阶段：`tests\test_ops_console_11.py + tests\test_system_api.py` → 26 passed。
- `git diff --check` 通过；无禁止工件泄漏。
- E6 独立运维安全审查 CLEAN（0C/0I/3M，`.superpowers/sdd/task-e-independent-review.md`）：
  3 Minor 为日志级别子串匹配/多文件拼接、`_lan_ipv4()` 依赖外网路由、`console-*` 设备记录
  累积，均不阻塞。

### 2026-09-02 — MediaReview 1.1 Task D · 安全删除 + 重复整理（Android 收口）

Task D 覆盖批阅/收藏一致性核查、两阶段永久删除（nonce）、重复分组持久化与后台扫描、
Android 双栏对比 + 保留选择。

前置核查（D2/D3，无代码改动）：
- 批阅窗口行为：混合图/视频、稳定 pager、P0 缓冲停 P1、横屏视频居中、删除失败停留当前项，
  既有实现核查无缺口。
- 收藏一致性：Media/Player/Review/Favorites 经 revision 图与成功变更才更新，核查无缺口。

Server（已提交）：
- `ca9f75a feat(delete): add nonce two-phase commit contract`：两阶段永久删除。
  `POST /delete-queue/commit/prepare` 返回一次性 nonce（UTC 过期、数量/字节/媒体 ID 摘要，
  不含路径）；`POST /delete-queue/commit` 仅接受 nonce，逐项返回 `success|missing|failed`；
  nonce 一次性、过期/复用/篡改拒绝；服务端重解析媒体 ID、校验所选库/队列/指纹/当前文件身份，
  逐项独立执行并写审计。迁移 `0014_task_d_delete_nonce_duplicates` 含 delete_commit_nonce /
  duplicate_group / duplicate_group_member，down_revision=0012 单一线性头。
- `8e824b7 feat(duplicates): persist scan groups as background task`：重复扫描持久化后台任务。
  exact=size+duration+分段 quick fingerprint+combined SHA-256；疑似=duration/size/resolution
  （1.1 无 pHash）；任务支持暂停/继续/取消/进度；绝不自动删除。API：
  `POST /duplicates/scan`（幂等）/ `GET /duplicates/status` / `GET /duplicates`
  （exact/similar 分组含成员与 keep）/ `POST /duplicates/{group_id}/keep` /
  `POST /tasks/{task_id}/pause|resume`。

Android（本提交 `feat(duplicates): dual-column compare + keep`）：
- 契约层：ApiModels 新增 DuplicateGroupDto（members/keep）/ DuplicateMemberDto /
  DuplicateScanStatusDto / DuplicateKeepRequest / TaskStateDto；MediaReviewApi 新增
  scan/status/keep/pause/resume 端点；MediaDataSource 新增 8 个方法；
  MediaRepository 实现（失败返回 null/false 不抛）；TestFakes / MainShell 的
  ShellRepository 补齐实现。
- DuplicatesViewModel：分组加载（revision 门 + 成功才失效）、触发扫描→轮询进度
  （1500ms/20 次 miss 兜底）、暂停/继续/取消、双栏对比（逐成员取摘要）、保留选择
  （服务端成功才落本地）。
- DuplicatesScreen：扫描状态卡（进度/暂停/继续/取消）、完全重复/疑似重复分组列表、
  双栏对比网格 + 保留勾选、中文提示"保留仅作整理记录，删除仍需在待删除页确认"。

TDD 与验证：
- Android RED→GREEN：先补 DuplicatesViewModelTest 8 例 + ApiModelsTest DTO 3 例（编译失败
  RED）→ 实现契约/ViewModel/Screen 后 focused BUILD SUCCESSFUL；修复 fake 未覆写
  `resumeTask` 导致的"暂停/继续"测试挂起。
- Android 全量：JVM 154 tests / 0 failures（+11）；assembleDebug、lintDebug BUILD SUCCESSFUL。
- Server 全量：`pytest -q` 通过（exit 0）；`ruff check .` All checks passed；
  `ruff format --check .` 92 files already formatted。
- `git diff --check` 通过；构建产物 `.tmp_kct/` 与 `android/META-INF/` 泄漏已清理。

### 2026-09-02 — MediaReview 1.1 Task D7 · 全量门禁 + 破坏安全独立审查 + 验收 ZIP

D7 为 Task D 收口门禁，不新增功能代码（仅文档与证据收口）。

全量门禁（D7a）：
- Server 全量：`pytest -q` → 308 tests collected、exit 0（全过）；`ruff check .` All checks
  passed；`ruff format --check .` 92 files already formatted。
  - 注：性能门禁（100k 响应性 <2.0s）与 Android 构建并行时出现一次 2.09s 抖动失败，
    单跑复测 exit 0（CPU 竞争，非代码问题）。
- Android 四目标 `--rerun-tasks` 干净重跑：`:app:testDebugUnitTest :app:assembleDebug
  :app:assembleAndroidTest :app:lintDebug` → BUILD SUCCESSFUL（6m37s，91 tasks）；
  JVM 154 tests / 0 failures / 0 errors（33 套件）；lintDebug 0 Error（47 条既有
  Warning：依赖版本/图标/清单类，与 Task D 无关，诚实记录）。

独立审查（D7b）：
- 对抗性破坏安全审查（`.superpowers/sdd/task-d-independent-review.md`），对
  6485881..HEAD（HEAD=afa6b0b）全量改动逐项裁决。
- 结论 **CLEAN（0 Critical / 0 Important / 4 Minor）**：nonce 合同（一次性/过期/防伪/
  服务端重取 DB 身份/逐项独立/逐项审计/TOCTOU 快照）成立；Android 需 AlertDialog 二次
  确认，无单步永久删除；重复扫描全链路只读不删；任务控制无双重运行/死锁；迁移 0014
  单一线性 head、升降对称；禁止工件检索仅命中文档文件名。
- 4 Minor（不阻塞，记录在案）：M1 OSError 文本可能含绝对路径写入本地 SQLite 审计/错误
  字段（不外发）；M2 重扫清空旧分组并清掉 keep 标记；M3 resume 后 progress 重置、
  paused 时 POST /scan 为 no-op；M4 任务书文字写 down_revision=0012，实际 0014 为 0013
  （链路线性成立，文字误差）。建议后续阶段处理 M1/M2。

文档与证据（D7c）：
- `REVIEW_SUMMARY.md`（阶段 D 完整总结，结论：合格）。
- `review_meta/server_tests.txt`（pytest 真实输出）、`review_meta/android_lint.txt`
  （lint 明细）作为门禁证据随验收 ZIP 打包。

验收 ZIP（D7d）：`python scripts/build_review_handoff.py --stage D --name … --base 6485881`
生成于 `review_handoff/`，确认产物存在且体积达标后向用户汇报。

### 2026-09-02 — MediaReview 1.1 Task C · Direct Play 与单次 HLS 回退（收口）

完成（含前置小提交与收口修复）：

- 前置（`89644fd fix(review): monotonic session ids`）：review 会话平局打破键改为单调递增，
  关闭 Task B 遗留 M-A（session_id 随机后缀微秒碰撞时与创建顺序无关的全量门禁偶发红）。
- Server（`814740e feat(server): add secure playback contract`）：
  - 播放合同新增 `direct` / `fallback_hls`（均为 `{url, headers}`）+ `resume_position_ms`；
    `stream_url` 保留为一版兼容字段恒等于 `direct.url`；`requires_jellyfin_auth=false`。
  - 设备级播放凭据：按设备签发/复用 Jellyfin 命名 key（`mediareview-<installation_id>`），
    value 只存服务端 `paired_device`（迁移 `0013_device_playback_key`，`down_revision=0012`
    保持单一线性头），仅在播放响应 headers `X-Emby-Token` 下发，绝不进入 URL/JSON/日志；
    支持按名幂等撤销。
  - Direct/HLS URL 构造前后均做 server-key 排除终检；凭据签发失败 fail-closed（中文错误，
    不下发任何直连地址）；Jellyfin 续播位置读取失败返回 0 不阻塞。
  - HLS 为 `master.m3u8` 最小转码集（h264+aac），Android 状态机不得二次回退；
    中间层仍不转发视频流。
- Android（`757a33d feat(android): add direct hls state machine`）：
  - `PlaybackStateMachine`：纯转移函数——Direct 成功→DirectPlaying；Direct 失败→恰好一次
    FallbackHls；HLS 失败→中文终态（"无法播放该视频(直连与转码回退均失败):…"）；
    取消/切换媒体完全重置并重新获得回退配额；迟到的旧成功/失败事件被忽略。
  - `PlayerCore`：单例 `HttpDataSource.Factory` 共享默认请求头（X-Emby-Token），
    `playStream` 支持 headers + startPositionMs；播放器错误经 SharedFlow 上抛。
  - `PlayerViewModel`：错误/成功两个 collector 驱动状态机；Direct 失败进入唯一一次 HLS 回退
    （`usingFallback` 指示）；legacy `stream_url` 兼容；HLS 无端点直接中文终态。
- 收口修复（本提交，TDD RED→GREEN）：
  - 审查发现：错误→事件映射中 Idle 态错误被忽略——Media3 数据源准备期即失败（未经过
    READY，stage 仍为 Idle）是最常见失败形态，此前不会触发 HLS 回退，与 Task C Step 2
    "Direct datasource/container/decoder error → exactly one HLS transition" 不符。
  - RED：新增 `ErrorEventMappingTest` 4 例（Idle→DirectStartFailed 等）首次编译失败
    （`errorEventFor` 未定义）。
  - GREEN：抽出共享纯函数 `errorEventFor(stage, reason)`（Idle/DirectPlaying→
    DirectStartFailed、FallbackHls→HlsStartFailed、Terminal→null），PlayerViewModel 改用之；
    focused 全绿。

独立审查返修（首轮 NOT CLEAN：1 Critical / 3 Important / 6 Minor → 全部关闭后复审 CLEAN）：

- **C1（Critical）Jellyfin /Auth/Keys 契约错配**：`ensure_device_stream_key` 用 POST
  `Name` 参数、按 GET 返回项 `Name` 字段匹配；真实 Jellyfin 契约是 POST 参数 **`app`**、
  GET 返回项字段 **`AppName`**（经官方生成 SDK/API 文档/Jellyfin-Cli 多方核实）。真实服务器上
  `/playback` 恒 500，Direct/HLS 整体不可用；测试 mock 复刻了错误字段导致"测试全绿、
  生产走不到"。修复：client.py 改 `app`/`AppName`；同步修正 `conftest.py`、
  `test_connection_identity_11.py`、`test_media_image_proxy_11.py`、
  `test_playback_contract_11.py` 四处 mock；新增锁定测试
  `test_device_key_provisioning_uses_real_jellyfin_app_contract`（RED：仅改 client 时聚焦
  测试失败 → GREEN：mock 修正后 36 passed）。
- **I1（Important）批阅模式无凭据**：review 路径（`resolvePlayable`→`prepareSilent`）只取
  `stream_url`，视频请求无 X-Emby-Token → 直接进批阅（含断点恢复）401。修复：`ReviewPlayable`
  增加 `headers`；`resolvePlayable` 缓存整个 PlaybackInfoDto 并经新纯函数
  `directPlaybackEndpoint` 提取 direct URL + headers（legacy stream_url 兜底）；`PlayerCore`
  抽 `applyHttpHeaders` 供 `playStream`/`prepareSilent` 共用注入共享 HttpDataSource。
  新增 `ReviewPlayableEndpointTest` 3 例。
- **I2（Important）"可撤销"未落地**：`revoke_device_stream_key` 无生产调用方，撤销设备不清
  key 列。修复：`pairing.revoke_device` 清空 jellyfin_key_* 列；新增
  `pairing.device_stream_key_name`；`POST /pairing/revoke` 改 async，经新可选依赖
  `optional_jellyfin_client` 尽力在 Jellyfin 侧撤销命名 key（未配置/失败不阻塞本地撤销）。
  新增 HTTP 级测试 `test_revoke_device_clears_jellyfin_key_and_revokes_upstream`。
- **I3（Important）迟到错误污染新会话**：错误 collector 只按 stage 映射、不按会话身份过滤，
  上一媒体缓冲错误在 `machine.reset()` 后被当作新会话 Direct 失败。修复：`PlayerCore` 错误流
  改 `PlayerErrorEvent(mediaId, error)`（携带出错槽位媒体 id）；`PlayerViewModel` 经新纯函数
  `isCurrentSessionError` 过滤（事件媒体 id ≠ 当前媒体即丢弃）。新增 `SessionErrorFilterTest` 3 例。
- 6 Minor 记录在案不阻塞：M1 usingFallback 只写不读、M2 Retry/Cancelled 生产未 dispatch、
  M3 普通播放器绕过 MediaUrlResolver（服务端已拒 loopback，纵深防御不对称）、M4 批阅不消费
  fallback_hls/resume、M5 user_id 未配置发无效续播请求（404→0）、M6 HLS 从原始续播位重启。

TDD 与验证：

- Server focused：`test_playback_contract_11.py` 等 41 passed（命名 key 幂等/撤销/失败
  fail-closed、无 server key 序列化、非视频 400、续播位置、HLS 唯一回退）。
- Server 全量：`pytest -q` 通过（退出码 0，约 291 项）；`ruff check .` All checks passed；
  `ruff format --check .` 89 files already formatted。
- Android RED→GREEN：`ErrorEventMappingTest` 编译失败（RED）→ 实现后 focused BUILD SUCCESSFUL。
- Android 全量四目标（`testDebugUnitTest/assembleDebug/assembleAndroidTest/lintDebug`
  `--rerun-tasks`）：BUILD SUCCESSFUL（91 tasks）；JVM 136 tests / 0 failures（+4）；
  debug APK 22,656,939 B；androidTest APK 已构建；lint 0 errors。
- `git diff --check` 通过；工作树 clean；分阶段提交。

独立审查返修后门禁（复审 CLEAN 依据）：

- Server focused（播放合同 + 配对 + 图片代理 + phase7）52 passed；全量 pytest 通过
  （退出码 0）；ruff check/format 全绿。
- Android JVM **142 tests / 0 failures**（+6：SessionErrorFilter 3 + ReviewPlayableEndpoint 3）；
  四目标 --rerun-tasks BUILD SUCCESSFUL；lint 0 errors。
- 独立复审报告 `.superpowers/sdd/task-c-independent-review.md`：**CLEAN**
  （0 Critical / 0 Important / 6 Minor）。

遗留边界：

- instrumentation 仍无设备执行（本机无 ADB 设备/模拟器/system image）；Task C Step 6 格式矩阵
  （MP4/H.264、MKV/HEVC、4K、多音轨、内嵌字幕、仅转码）与真机 Direct<3s/HLS<8s 属 Task G。
- 设备级命名 key 的创建/回读/撤销契约已按官方文档修正并锁定测试，但未在真实 Jellyfin 服务器
  上端到端验证（本机无真实 Jellyfin）——Task G 真机阶段必须实测。
- HLS 最小转码集未经真实 Jellyfin 转码服务验证（本机无真实 Jellyfin）。
- 6 Minor 记录在案（见上），建议随 Task D/E 顺手处理。

提交：

- `89644fd fix(review): monotonic session ids`（前置，2026-08-30）
- `814740e feat(server): add secure playback contract`（2026-08-30）
- `757a33d feat(android): add direct hls state machine`（2026-08-30）
- `fix(android): fall back to hls on direct prepare failure`（收口修复）
- `fix(review-findings): close task C critical and importants`（独立审查返修）
- `docs(handoff): record task C completion and review`（本阶段文档）

### 2026-08-30 — MediaReview 1.1 Task B · Paging 3 媒体墙、图片与雪碧图闭环

完成：

- B1（`6577939 feat(android): page media wall`）：新增不可变 `MediaQuery` 与
  `MediaPagingSource`（键=服务端页码；异常包装 LoadResult.Error、CancellationException
  原样上抛；sync 回传）；`MediaWallViewModel` 改 `combine(query,refresh).flatMapLatest{Pager}`
  + `cachedIn` 唯一页缓存；`MediaWallScreen` 换 LazyPagingItems（LoadState 驱动骨架/空态/
  离线重试/追加失败就地重试）；Server 新增 `GET /media/folders`（父目录 SQL 聚合、
  folder_id=SHA-256 前缀、响应无路径）与 `folder_id` 筛选；`MediaDataSource.loadMedia`
  加 folderId、新增 loadMediaFolders（三个实现同步）。
- B2（本提交）：图片查看器详情重试/重复 load 取消/离开 `cancelDecoding`/Coil 按视口解码；
  雪碧图 ensure 202 {task_id,status}、处理器进度里程碑 20/40/100、前后两处协作取消、
  终态 CAS 条件 UPDATE；Android 轮询任务进度、等待覆盖层"生成中 N%"+取消按钮、
  失败/取消中文终态并复位可重试、`tileIndexFor` 纯函数化。
- 审查驱动的生产修复：TaskManager._loop 增加异常保护（此前一次瞬时 SQLite 锁冲突即
  永久杀死后台任务引擎——既有缺陷被本轮 100k 测试暴露，回归 test_task_manager.py）。

TDD 与验证：

- Server RED：文件夹/100k 测试初次 6 failed（路由捕获与未实现）；task_manager 回归
  RED（异常逃逸）→ 修复后 GREEN。
- Android RED：MediaPagingSourceTest 编译失败（三个符号未实现）→ 实现后 focused GREEN。
- 首轮独立审查 **NOT CLEAN**：100k 测试与 1s 轮询争锁 flaky（database is locked）、
  _loop 无异常保护、测试夹具 4×W605。修复：100k 预迁移+预插种与轮询彻底解耦
  （连续 3 次 + 审查员 2 次全过）；_loop 异常保护；raw string；并顺手处理
  终态 CAS、失败/取消文案、轮询耗尽复位、死代码 ErrorBox、取消上抛测试。
- 第二轮独立复审（`.superpowers/sdd/task-b-independent-review.md`）：**CLEAN**
  （0 Critical / 0 Important / 6 Minor）。审查员复现全部声称数字：Server 282 passed、
  ruff 全绿、Android 122/0、四目标构建成功、100k 两次无 flake（durations 0.03-0.06s）。
- 全量门禁：Server 282 passed + ruff check/format 全绿；Android JVM 122/0 +
  四目标 --rerun-tasks BUILD SUCCESSFUL（91 tasks，修复前后各一次）；git diff --check 通过。

遗留：

- instrumentation 仍未设备执行（无设备）；M-A review 会话平局打破键为基线既有
  偶发 flake（建议 Task C 前顺手修复）；M-B 终态文案 UI 不可达、M-C 取消按钮
  空 taskId、M-D 异常路径无 CAS、M-E ready 直返也 202、M-F 查看器 runCatching
  吞取消（瞬态）——均记录于复审报告，随下一阶段处理。
- folders 100k 全库聚合 0.6-1.1s（超 1s 目标、远低于 2.0s 硬上限）；图片位图级
  加载失败无重试 UI（详情级已闭环）。

提交：

- `feat(android): page media wall`（B1，基线 d542e08）
- `feat(media): close image and sprite flows`（B2，基线 6577939）

### 2026-08-30 — MediaReview 1.1 Task A · 关闭 Task 4 两个 Important

完成：

- 新增 `DeleteOutcomeStatus` 共享解析器（ApiModels.kt）：`success/missing/failed` 之外一律
  Unknown fail-closed；`DeleteQueueViewModel.commit()` 全部改走解析器，全成功批次推进
  FinalDelete（DeleteQueue/Media/Favorites/Duplicates 四区），摘要"删除成功 X 项[,缺失 Y 项
  [,失败 Z 项]]"，空 outcome 显示"删除完成"。
- 修复执行中发现的第三个缺陷：基线 `commit()` 的摘要被尾随 `load()` 整体状态替换立即抹成
  null（UI 从未真正显示过摘要）；`load()` 改用 `update` 保留 `commitResult`。
- JVM 竞态测试 `reviewRootDeactivationTokenGuardsInFlightSettleResumption`：fake loadPlayback
  挂起在不可取消 `suspendCoroutine` 停车点，取消后释放必须被失效 token 拦截（旧 settle/play=0），
  重入新 token 正常落位播放（1/1）。可取消 await 会被 cancel 杀死、无法复现生产窗口，故用
  不可取消停车点（审查确认此为 RED 反证确定性的关键）。
- 生产壳 instrumentation 竞态测试 `productionShellGuardsInFlightSettleAcrossRootSwitch`：
  生产 MainShellScreen + 真实 ReviewViewModel + 真实切根失活路径 + 真实队列项 + 可控挂起
  lookup + 重入断言，满足 post-fix review I2 全部要素。
- 表驱动删除合同测试 6 案例覆盖全成功/全失败/空/仅 missing/混合/unknown-wire 的摘要与
  revision 矩阵；解析器直测钉死大小写宽容与 `deleted→Unknown` 反协议闸。

TDD 与验证：

- RED：表驱动测试初次 `5 tests, 1 failed`（`outcome={a=success} 摘要 expected:<删除成功 1 项>
  but was:<null>`）——同时暴露协议错配与摘要被抹两处缺陷。
- GREEN：focused 7/7 通过。
- RED 反证：移除 `settleScheduler.reset()` 后竞态测试确定失败（`旧 settle 不得落地 expected:<0>
  but was:<1>`），恢复后与基线逐字节一致。
- 全量 Android 四目标：BUILD SUCCESSFUL（3m54s，91 tasks）；JVM 102 tests / 0 failures；
  debug APK 22,344,728 B；androidTest APK 1,023,373 B；lint 0 errors。
- Server：聚焦删除协议 13 passed（确认 success 合同）；全量 273 passed + ruff 全绿
  （Task 0A 回合实测，本阶段未改 Server 代码）。
- 独立审查（`.superpowers/sdd/task-4-task-a-independent-review.md`）：**CLEAN**
  （0 Critical / 0 Important / 4 Minor），审查员独立重跑 focused 7/7、全量 JVM 102/102、
  androidTest 编译、Server 13/13，并推演确认 RED 反证确定性。

遗留边界：

- **instrumentation 未在设备执行**（ADB 无设备、无模拟器）：androidTest 仅编译验证；设备级
  执行与真机验收属 Task G（审查 M4）。
- Minor 遗留（不阻塞）：M1 commit 网络失败仍显示"删除完成"（基线即有）；M2 Unknown 摘要
  与 failed 合并计"失败"；M3 androidTest 计数器未统一 `@Volatile`。均记录于独立审查报告，
  建议随下一阶段顺手修复。

提交：

- `fix(android): close task 4 deletion and shell gates`（基线 `f0c797c`）

### 2026-08-30 — MediaReview 1.1 Task 0A · 可移植阶段验收工具

完成：

- 新增 `scripts/build_review_handoff.py`（纯标准库 argparse/subprocess/pathlib/zipfile）：
  打包范围为 `git diff --name-only <base>`（基线到工作树、仅已跟踪文件）+ 固定状态文档；
  只含已批准文本扩展名；禁止类别（build/日志/DB/APK/EXE 等）与超限文件排除并逐条记录到
  `review_meta/excluded_files.txt`；`.env`/keystore/`key.properties` 等疑似密钥路径、无效或
  非祖先 base、缺少 `REVIEW_SUMMARY.md` 或"阶段结论"行、缺少测试证据、ZIP 超限一律非零退出
  且不产出 ZIP。
- 新增 `server/tests/test_review_handoff_builder.py` 15 个端到端测试：在临时 Git 仓库真实运行
  构建器，覆盖必需条目、文件名格式、删除路径、未跟踪排除、密钥 fail-closed、禁止类别排除、
  大小上限、`git` 不可用与非法参数。

TDD 与验证：

- RED：脚本缺失时 15 个测试中 12 failed / 3 passed（3 个为"不存在即非零"的空真通过）。
- GREEN：实现后 15 passed（9.25s）。
- 全量门禁：Server `pytest tests` 273 passed（258 旧 + 15 新，exit 0，隔离数据根）；
  `ruff check .` 全部通过；`ruff format --check .` 86 files 全部通过。
- 同回合环境验证（未计入本阶段改动）：全量 258 passed 复验、Android 四目标
  `testDebugUnitTest/assembleDebug/assembleAndroidTest/lintDebug` BUILD SUCCESSFUL（100 JVM tests）。
- 文档同步：`REVIEW_HANDOFF_RULES.md` 步骤 9、`docs/TOOLCHAIN_AND_RELEASE_OPERATIONS.md`
  工具表与缺口章节、`TASKS.md` 新增 Task 0A 节、根 `REVIEW_SUMMARY.md` 重写为本阶段摘要。
- 验收 ZIP：`review_handoff/MediaReview_Review_Stage-0A_*.zip`，已用 7z 列表 + 解包复核
  无禁止条目。

遗留：

- 证据文件由 Agent 手工写入，"记录真实输出"依赖独立审查复核（构建器只强制存在性）。
- 密钥检查是路径级而非内容级；内容级脱敏仍靠 `REVIEW_HANDOFF_RULES.md` §6 的人工/审查流程。
- Android/部署/发布链路不在本阶段范围；Task A（关闭 Task 4 两个 Important）为下一阶段。

提交：

- `build(review): add portable handoff packager`（本阶段单一提交，基线 `fe4666b`）

### 2026-08-30 — 交接工具链与 Hermes 邮件规则审计

- 新增 `docs/TOOLCHAIN_AND_RELEASE_OPERATIONS.md`，记录当前主机 uv/Python 3.12/JDK/Android SDK/ADB/apksigner/zipalign/Git/7-Zip/FFmpeg 路径、初始化命令和不可移植边界。
- 新增 `docs/HERMES_APK_EMAIL_DELIVERY.md`，规定 APK 签名继承门禁、共享目录交接、显式唤醒 Hermes、收件地址确认、非图片 MIME、大小/拒收/fail-closed 和实际附件名/字节数/SHA-256 验收。
- 发现 `scripts/build_review_handoff.py` 实际缺失；在 takeover plan 新增 Task 0A，未补齐前不得宣称新阶段完成。
- 发现 `scripts/build_deploy.py` 仍硬编码 0.8.1/`Mediaserver.exe` 且缺少 1.1 正式产物合同；明确禁止用于 1.1 发布。
- 当前主机未发现 Emulator、PyInstaller；Jellyfin 7.1.4 FFmpeg/ffprobe 已定位并记录哈希，但尚未完成许可/再分发审查，不能直接装包。Release signing 尚未配置，且必须与手机已安装 APK 证书一致才能无损覆盖升级。
- 识别旧 PowerShell 安装脚本的 5.1 不兼容、FFmpeg 未复制、UDP 35001 缺失、健康失败仍成功和回滚/所有权边界缺口；旧部署链不得生成 1.1 正式包。
- 根 README/历史开发路线的接管指向、`.superpowers` 忽略范围、冻结提交判定和 D 盘 remote 边界已修正规则；未来审查 Markdown 必须跟踪，临时 diff 可由 Git 重建。
- 本次没有读取 Hermes 密钥、没有修改 Hermes 房间脚本、没有发送邮件，也没有启动或部署服务。

### 2026-08-30 — 完整项目交接冻结

交接状态：

- 新增根入口 `TAKEOVER_READ_FIRST.md`、准确状态 `docs/HANDOFF_STATUS_2026-08-30.md` 和后续完整实施计划
  `docs/superpowers/plans/2026-08-30-mediareview-1.1-takeover.md`。
- 冻结点为 `feature/mediareview-1.1` 当前历史；Task 0–3 已独立 CLEAN，Task 4 最新独立审查仍为
  NOT CLEAN（2 个 Important），Task 5–10 未按 1.1 实施。
- 修正 `TASKS.md` 的状态冲突：Task 4 已存在的视觉代码不等于通过门禁；删除 `success` 协议和生产主壳
  settle 竞态集成回归关闭前不得进入 Task 5。
- 本条只写交接文档和状态，不启动服务、不访问生产配置/数据库/密钥，也不声明重新执行历史测试。

### 2026-08-29 — MediaReview 1.1 Task 4 · 深色设计系统、品牌与主导航

完成：

- Android 升级到 `1.1.0-alpha2`/versionCode 5，建立固定深色 token 和 code-native adaptive launcher。
- 配对后进入单一四入口主壳；媒体直达媒体墙，批阅/收藏复用真实流，整理复用既有状态并显示计数。
- 设置、播放器和图片查看器保持独立全屏；四部分连接状态以同步或静态 attention banner 呈现。
- 根页面使用保存状态与幂等首次加载门；重新配对替换旧主壳，避免重复 back stack。
- 清除生产 Compose 的 emoji/符号控制和散落颜色值；Material Icons 均具中文语义，播放器文字菜单达到 48dp。

TDD 与验证：

- 首轮 RED：JVM 因缺少主壳 API 稳定失败；instrumentation 因缺少底栏/共享状态组件稳定失败。
- 审查修复 RED：幂等加载门 JVM 测试和真实 root/chrome/back-stack/48dp instrumentation 合同先失败；
  实现后 focused JVM 与 instrumentation 编译通过。
- 最终 JVM、debug APK、test APK、lint 和 diff-check 证据见 `.superpowers/sdd/task-4-report.md`。

遗留：

- instrumentation 已构建但未在模拟器/真机运行；360x740、390x844、横屏、font scale 1.3 与 TalkBack
  仍需设备验收。未访问真实 MediaReview/Jellyfin/LAN，也未改变 Task 5/6 或服务端业务。

提交：

- `feat(android): add dark media shell`（本任务单一 focused commit）

接受审查 remediation：

- I1：真实主壳 root lifecycle 在批阅失活时取消 settle，并暂停 P0/P1 两个播放器。
- I2：收藏、媒体库、待删除、重复文件改为业务 revision + load gate；普通切根不重载，相关成功变更后返回精确刷新。
- I3：横屏媒体筛选改为单行横向滚动，Compose 合同覆盖 740x360、font scale 1.3 与主壳 chrome 后的网格高度。
- I4/M1：Task 4 修改过的 Compose 文件不再使用已有 token 的 dp/sp 字面量；`✓` 已移除并加入符号回归。
- I5：主壳测试使用生产 `MainRootStateHost` 验证 Review deactivation 与收藏 revision 刷新；报告不再把合成内容称为真实业务页面。
- 第二轮 RED：focused JVM 因 revision API 缺失失败，instrumentation 因 lifecycle/responsive API 缺失失败；
  focused GREEN 为 15 tests / 0 failures，instrumentation Kotlin 编译通过。最终 full 证据见 Task 4 report。

---

### 2026-08-30 — MediaReview 1.1 Task 4 最终修复（F1/F2/F3）

完成：

- Review root 失活先 `reset()` latest-wins scheduler，再取消 job、暂停 P0/P1；repository suspend
  failure boundary 对 `CancellationException` 重新抛出，旧 settle 即使从不可取消 fake 恢复也不能落地。
- 新增 `Media` content area 与显式 mutation 依赖矩阵。媒体库选择、收藏变更、待删除入/出队和
  最终删除只在确认成功且确有变化后推进相关 revision；部分删除含 `deleted`/`missing` 即刷新
  DeleteQueue/Media/Favorites/Duplicates，全失败或空结果不失效。
- 生产主壳把持久化的 `MediaWallViewModel` 传入 `MediaWallScreen`，Media root 激活按 revision
  刷新媒体库与当前 query/filter，不重建 ViewModel；搜索流跳过初始空值，避免初始化重复查询。
- 新增 `MediaDataSource`、`ReviewPlaybackController` 与 settings 窄测试 seam；生产 Hilt 仍注入
  原 `MediaRepository`、`PlayerCore`、`MediaWallSettingsStore`，未改变 server/API/Task 5/6。
- 新增生产 `MainShellScreen` Compose integration test：真实 ViewModel + 受控 fake，点击生产导航，
  覆盖 Review 停用、成功收藏 mutation 后 Media 恰好一次重载、无关切换不重载。
- 删除 mutation/lifecycle 的源码字符串断言；源码合同只保留纯视觉/资源规则。

TDD 与验证：

- RED：首个 Media area 测试 `1 failed`；可测 seam 后 F1/F2 focused `5 tests, 5 failed`；
  repository 旧取消吞噬实现反证 `1 test, 1 failed`；Media 初载精确一次测试发现实际加载 2 次。
- GREEN：F1/F2/Media focused `11 tests, 0 failures`；JVM 全量 `100 tests, 0 failures`（26 suites）。
- fresh Android：brief 指定四目标命令 `BUILD SUCCESSFUL in 2m 5s`，91 actionable tasks 全执行；
  debug APK 22,344,728 bytes，androidTest APK 1,019,345 bytes，lint 0 errors / 47 warnings。

限制：ADB 无连接设备，Compose instrumentation 仅构建未执行；未验证真机/模拟器视觉、交互或物理设备行为。

---

## 模板

### YYYY-MM-DD — 阶段 X

完成：

- 

验证：

- 

测试：

- 

遗留：

- 

提交：

- 

---

### 2026-08-20 — 阶段 15 · Stage 15(批阅稳定性 + 播放器完整交互 + 安全强化)

完成：

- **Review settled 事件 latest-wins**(Android 0.9.1):
  - 页面快速连续滑动时 `settleJob?.cancel()` 取消旧切换/位置任务,并新增纯逻辑 `LatestWinsScheduler` 令牌校验(网络/准备阶段返回后只有最新令牌才执行 settle/prepareNext),彻底杜绝"旧协程晚于新协程完成导致播放串位"。
- **P0/P1 重构**:页面停稳后**立即**切换已 ready 的当前视频——`onSettled` 只解析当前项 URL,不再为"下一条的下一条"做网络等待;下一条 URL 获取 + `prepareNext` 全部放后台协程,不阻塞当前播放。`PlayerCore.settle` 只接受已解析 URL 的当前项;`prepareNext` 独立于 settle 由调用方后台触发。
- **Jellyfin 进度防串片**:`PlayerCore` 为每个槽位记录 `slotMediaId`,`snapshotFor(mediaId)` 只返回对应播放器的实时快照;媒体切换后对旧 mediaId 返回 null 直接跳过上报;切换前在 `onSettled` 内快照旧 mediaId 再补报最后进度——禁止拿新的 activePlayer 给旧 mediaId 上报。
- **Review 向前分页 + 恢复结束判断**:新增纯逻辑 `ReviewQueueWindow`(resumePage 恢复定位 / append 向后 / prepend 向前 / `atEnd = baseIndex + items.size >= total`);向上滑到顶部自动加载更早分页并补偿 pager 偏移;恢复模式结束判断修正为窗口末端判定(旧逻辑 `items.size >= total` 在恢复模式 baseIndex>0 时误判)。
- **PlayerCore 状态刷新**:增加 `onIsPlayingChanged` / `onPlayWhenReadyChanged`,暂停/恢复即时反映到 `PlaybackStatus`。
- **PlayerScreen 底栏 Column**:控制层改为 Column 堆叠(播放/进度 + 倍速/音量/比例/音轨/字幕/横竖屏/锁定),解决控制行互相重叠。
- **播放器手势**:单击显隐控制层、双击左/右半屏快退/快进 10s、左半屏上下滑调亮度、右半屏上下滑调音量、左右滑 seek、音轨/字幕切换(TrackSelectionOverride)、锁定(手势与控制层禁用,锁图标解锁)。
- **媒体墙排序/筛选**:Server 排序补 `resolution`/`random`,新增 `exclude_favorites`(未点赞筛选);Android `SortField` 补两项 + 未点赞开关按钮。
- **完全重复队列代表项**(Server 0.8.1):创建批阅会话前 `_dedupe_exact_duplicates` 只保留每个 byte-identical 分组中排序最先的代表项,其余移出批阅队列;**不自动删除任何文件**。
- **System/Admin 安全**:`/system/info`、`/system/storage`、`/system/diagnostics/export`、`/admin` 改为 `require_localhost_or_auth`(本机回环放行,局域网设备必须携带合法 token);诊断包日志在打包前做敏感字段脱敏(token/配对码/api_key)。
- **点赞/删除体验**:点赞轻量弹跳动画;待删除成功后自动滑向下一条并保留撤销提示。
- **Settings / Reconnect / Error**:新增设置页(服务器信息/检查连接/重新配对/清除配置);首页错误态 + 重试;播放器错误态 + 重试。
- 版本号:Server 0.8.1 + Android 0.9.1。

验证：

- Server:`pytest` 全量通过(**145 项**,+6:敏感接口 localhost-or-auth ×3、诊断日志脱敏、重复代表项、exclude_favorites 筛选);`ruff check` 通过。
- Android:`gradlew :app:assembleDebug :app:testDebugUnitTest` **BUILD SUCCESSFUL**(APK 已产出;单测 30 项全绿,新增 ReviewQueueWindowTest 7 + LatestWinsSchedulerTest 3 + ReviewPlayerSlotsTest +3)。

测试：

- server:`test_remote_client_cannot_access_sensitive_endpoints`、`test_loopback_can_access_sensitive_endpoints`、`test_remote_with_valid_token_can_access_sensitive_endpoints`、`test_diagnostics_export_masks_sensitive_logs`、`test_review_queue_dedupes_exact_duplicates`。
- android:新增 `ReviewQueueWindowTest`(恢复分页/结束判断/向前分页)、`LatestWinsSchedulerTest`(令牌)、`ReviewPlayerSlotsTest`(+settle→prepareNext 交替槽位流程)。

遗留：

- 播放器手势/字幕音轨/亮度音量/锁定等真机手感待具备环境机器验证;真机 + 真实 Jellyfin 联调待部署阶段。

提交：

- 本机无 git CLI,git 提交待具备 git 的机器执行(内容以验收 ZIP 内 `review_meta/git_*.txt` 说明为准)。

---

### 2026-08-20 — 阶段 16 · Stage 16(预部署收口 + 部署产物)

完成(Stage 15 ChatGPT 深度验收通过后并入的 8 项收口 + 部署):

1. **媒体墙视频单击修复**:`MediaWallScreen` 视频卡片 `longPressScrub(onTap = onClick)`,恢复媒体墙 → 普通播放器入口。
2. **认证边界补全**(Server 0.8.1):`/media/*`、`/libraries/*` 加 `require_auth`;`/cache/*`、`/jellyfin/*` 加 `require_localhost_or_auth`(依赖顺序保证 401 先于 jellyfin 配置校验)。health / discovery / pairing verify 保持匿名。防止未配对局域网设备经媒体接口拿到带 `?api_key=` 的 Jellyfin 直连 URL。
3. **Android Coil 统一认证 ImageLoader**:新增 `CacheAuthInterceptor`(仅对 `/api/v1/` 路径附加 Bearer,避免污染 Jellyfin 直连 URL);`@Named("cache") OkHttpClient` + 注入 `ImageLoader`,在 `MediaReviewApp.onCreate` 设为 Coil 单例,雪碧图文件等服务器图片请求自动带 token。
4. **Media Snapshot Cache**(media_index.py):内存快照(TTL 180s + LRU 8 + 媒体库选择变化时 `invalidate_media_snapshots`);`list_media` 命中快照则不再扫描 Jellyfin 全库(仍落地 SQLite 供详情/收藏/播放读取)。验收测试:`page1/2/3` 全库采集只执行一次;`10000` 媒体 mock 分页(切排序/翻页不再扫描)。
5. **LatestWinsScheduler token 永久单调递增**:`reset()` 只前移计数器(不复用历史 token),并补"永不复用/多次 reset 单调递增"测试。
6. **Review position 串行上报**:`Channel.CONFLATED` + 单消费者协程,快速滑动时服务器按正确顺序收到最新位置;补 `PositionChannelConflationTest`。
7. **PlayerCore 槽位绝对索引**:`onSettled` 使用 `ReviewQueueItemDto.index`(服务端绝对 queue index)作为 settle/prepareNext 的槽位身份,prepend 不再破坏匹配。
8. **待删除成功才翻页**:`onDelete` 服务器 enqueue 成功后才发出 `ReviewEvent.DeleteSucceeded`,UI 收集事件后再滑向下一条(不再点击即翻)。

部署产物(Stage 16):

- **Server EXE**:PyInstaller onedir(`packaging/mediareview_server.spec`),`run_server.py` 入口;`migrate.py` 冻结感知(`_MEIPASS`);已验证 EXE 启动 + `GET /api/v1/system/health` 200(version 0.8.1)+ `/admin` 200。产物 `server/dist/MediaReviewServer/Mediaserver.exe`。
- **部署脚本**(deployment/scripts/):`install.ps1`(管理员/复制/数据目录/配置/ffmpeg/端口/防火墙/开机自启任务/health/报告)、`repair.ps1`(检查+重建不删数据)、`uninstall.ps1`(默认保留数据,-DeleteData 才删)、`diagnose.ps1`(脱敏诊断 ZIP)。
- **部署包**:`scripts/build_deploy.py` 组装 `deploy_handoff/MediaReviewServer-0.8.1_deploy_*.zip`(25.8MB,含 EXE+运行时+脚本+配置+HANDOVER)。FFmpeg 由 `third_party/ffmpeg/` 提供,缺失时 install.ps1 检查系统 PATH 并告警。

验证:

- Server:`pytest` 全量通过(**150 项**,+5:媒体/媒体库/缓存认证边界、loopback 缓存放行、token 通过、jellyfin 401、快照只扫一次、10000 分页);`ruff check` All checks passed。
- Android:`gradlew :app:assembleDebug :app:testDebugUnitTest` **BUILD SUCCESSFUL**,**34 项单测全绿**(+4:LatestWins 复用/单调 ×2、PositionChannelConflation ×2)。
- EXE 冒烟:真实启动 Mediaserver.exe,health 200 + admin 200(本机真实执行)。

测试:

- server:`test_remote_client_cannot_access_media_libraries_cache`、`test_loopback_can_access_cache`、`test_remote_with_token_passes_media_libraries_cache_auth`、`test_media_pagination_scans_jellyfin_once`、`test_media_pagination_large_library_no_rescan`。
- android:`LatestWinsSchedulerTest`(+2)、`PositionChannelConflationTest`(2)。

遗留:

- 播放器横纵拖动/单双击手势冲突需真机统一验证(必要时合并为手势仲裁状态机)——真机阶段。
- FFmpeg 未在本机,部署包不含;需在目标机提供或确认系统 ffmpeg。
- 正式签名 APK 与 clean-machine 部署测试待迁移到真实 Jellyfin 电脑后执行。

提交:

- 本机无 git CLI,git 提交待具备 git 的机器执行(内容以验收 ZIP 内 `review_meta/git_*.txt` 说明为准)。

---

### 2026-08-20 — 阶段 14 · Stage 14(批阅断点闭环 + 播放器完整控制 + Web 管理后台 + 诊断/部署准备)

完成：

- **批阅断点真正闭环**(Server 0.8.0 + Android 0.9.0):
  - Server 新增 `POST /review/sessions/{id}/position`(body index)更新 `current_index`;新增服务 `complete_all_active`,创建新会话前自动完成所有旧 active,避免数据库累计多个 active。
  - Android 恢复时只加载**包含目标绝对索引的分页**(`baseIndex = (index//PAGE_SIZE)*PAGE_SIZE`,`startIndex = index-baseIndex`)并正确定位;页面停稳 `markSeen` + `setReviewPosition(absolute)` 随批阅移动更新游标。
  - 深度恢复测试:1000 条媒体,`position=637` → latest current_index=637,第 13 页含绝对 637(m0637)。
- **Pager 边界保护**:`rememberPagerState { ui.items.size }`(页数=已加载数而非 total),settled ≥ 末页-5 提前 `loadMore`,快速滑动不再出现空白页。
- **普通播放器进度上报**:周期任务不再捕获旧 `playing`/`position`(每次直接读 `core.player` 实时值);退出/切走/离开页面时补最后一条上报(批阅侧同样在离开当前视频的 LaunchedEffect finally 补报)。
- **稳定性修正**:`loadMore()` try/finally 恢复 `loadingMore=false` 允许重试;`dequeueDelete()` 返回真实成功状态,服务器成功后才改 UI(批阅撤销/待删除恢复均遵循)。
- **播放器完整控制**:倍速(0.5~2x)、音量/静音、画面比例(适应/缩放/填充)、横竖屏切换;PlayerCore 增加 `setPlaybackSpeed/setVolume`。
- **Web 管理后台**(`app/admin.py`,挂载 `/admin`):状态面板(健康/版本/Jellyfin/配对)、生成一次性配对码(安全约束沿用 pairing 接口,本机可生成)、缓存/任务概览、下载诊断包。复用现有 API,不复制业务逻辑。
- **诊断导出**:`GET /api/v1/system/diagnostics/export` 返回 ZIP(脱敏配置 + 版本 + 缓存统计 + 关键表计数 + 最近日志),不含真实密钥。
- **部署准备**:`deployment/config.example.json` 核对(雪碧图尺寸上限为代码级默认,无需新增配置);Server 版本 0.8.0、Android 0.9.0。
- 版本号:Server 0.8.0 + Android 0.9.0。

验证：

- Server:`pytest` 全量通过(**139 项**,+3:深度恢复 637/1000、/admin 页面、诊断 ZIP);`ruff check` + `ruff format --check` 全部通过(73 文件)。
- Android:`gradlew :app:assembleDebug :app:testDebugUnitTest` **BUILD SUCCESSFUL**(APK 已产出;ApiModelsTest 4 + UrlNormalizeTest 4 + PanClampTest 3 + ReviewPlayerSlotsTest 6 = 17 项,0 失败),本机真实执行。

测试：

- server:`test_review_resume_deep_index`(position/queue 深度恢复)、`test_admin_page_served`、`test_diagnostics_export_zip`(含脱敏断言)。
- android:既有 17 项单测全绿(状态机/路由/平移)。

遗留：

- 批阅/播放器增强控制(字幕/音轨/亮度)与真机手感待具备环境机器验证。
- Web 管理后台远程配对码生成按安全模型仅本机可用(远程需手动 IP + 配对)。
- 部署(EXE 打包/防火墙/开机自启/install.ps1)属后续部署阶段。
- 本机无 git CLI,未执行分阶段提交(需具备 git 的机器执行 `feat/server-admin-diagnostics-review-resume` + `feat/android-player-controls-resume-fixes`)。

提交：

- (待 git 环境可用后执行)feat/server-admin-diagnostics-review-resume / feat/android-player-controls-resume-fixes

---

### 2026-08-20 — 阶段 12/13 · Stage 12-13(P0 播放器修正 + Review 服务端建队列 + 喜欢/待删除/重复页)

完成：

- **Server:Review 会话改由服务端构建**(`app/api/v1/review.py`):`POST /review/sessions` 只接收 `source`(filter/sort),服务端按已选媒体库 + Jellyfin 收集完整队列(去重、upsert 缓存、复用 /media 排序白名单),不依赖客户端提交 media_ids;`GET /sessions/{id}/queue` 改为分页(返回 `{items,total,page,page_size}`),支持数千/上万媒体。`build_jellyfin_client`(jellyfin.py)在路由内按需获取客户端(优先 dependency_overrides,保证鉴权失败时不触碰 Jellyfin、未配置也不在鉴权前 500)。
- **Server:播放进度上报闭环**(`app/api/v1/media.py`):`POST /media/{media_id}/progress`(position_ms/is_paused,require_auth)→ Jellyfin `Sessions/Playing/Progress`。
- **Server:雪碧图/进度相关测试**+ Review 分页/鉴权优先测试更新。Server 0.7.0。
- **Android P0 修正**:
  - `PlayerCore.playStream()` 补 `ensureListeners()`,普通播放器首次进入即正确更新 Playing/Paused/Buffering/Error。
  - 修复 P1 stale-ready:新增 `ReviewPlayerSlots` 纯状态机(槽位 ready 跟踪 + 决策),任何 stop 同步清空 ready,快速滑到已停止项时重新 prepare;配套 6 例 JVM 单测。
  - 图片页不向 ExoPlayer prepare 空 URL:settle 到图片时停止当前视频/保持静默,但正常预加载下一个视频。
- **Android Review 重构**:只提交 source;队列分页懒加载(接近底部自动加载下一页);进入时优先恢复最近活动会话(批阅断点,`/latest`),可「新批阅」;启动恢复已有喜欢/待删除状态;点赞/删除 API 成功后才更新 UI;撤销待删除基于真实 `lastDeletedMediaId`(不依赖 pager index);`load()` 异常捕获 + 重试;播放进度每 10s 回传。
- **Android 普通播放器**:进度条改为可拖动 Slider;每 10s 回传播放进度。
- **Android 页面**:喜欢页(列表打开/取消喜欢)、待删除页(数量 + 预计释放空间 + 单项恢复 + 最终删除二次确认 + 结果)、重复文件页(完全重复/疑似重复分组,只读不自动删除),首页与导航接入。Android 0.8.0。

验证：

- Server:`pytest` 全量通过(136 项,+1 进度上报测试;Review 流程改为 source-based + 分页);`ruff check` + `ruff format --check` 全部通过。
- Android:`gradlew :app:assembleDebug :app:testDebugUnitTest` **BUILD SUCCESSFUL**(APK 已产出;ApiModelsTest 4 + UrlNormalizeTest 4 + PanClampTest 3 + ReviewPlayerSlotsTest 6 = 17 项,0 失败),本机真实执行。

测试：

- server:重写 `test_review_api_flow_source_based`(source 建会话 + 队列分页 + seen/advance);新增 `test_media_progress_reports_to_jellyfin`;认证优先 401 用例回归。
- android:+`ReviewPlayerSlotsTest`(首次切换 A/未就绪、B 预加载后已就绪、stopInactive 清空 stale-ready、stopSlot 清空、reset)。

遗留：

- 批阅进度上报在图片页自动停止;普通/批阅播放器增强控制(倍速/字幕/横竖屏等)留待播放器稳定后补。
- 真机联调(Media3 实机流、批阅 P0/P1 手感、长按雪碧图)仍待具备环境与真机的机器。
- 本机无 git CLI,未执行分阶段提交(需具备 git 的机器执行 `feat/android-pages-review-fixes` + `feat/server-review-source-progress`)。

提交：

- (待 git 环境可用后执行)feat/android-pages-review-fixes / feat/server-review-source-progress

---

### 2026-08-20 — 阶段 11 · Stage 11(Media3 播放器 + 批阅模式 + P0/P1 预加载 + 原图/导航/雪碧图修正)

完成：

- **Server:原图 API**(`app/api/v1/media.py`):`MediaSummary` 增加 `original_url`(图片 → Jellyfin 原图 Download 直连 URL;视频为 None 走播放 API);`app/adapters/jellyfin/client.py` 抽出 `item_stream_url/item_original_url/item_thumbnail_url` 纯函数并保留客户端薄封装;`review.py` 批阅队列改为按配置构造封面/原图 URL(不再强制要求 Jellyfin 已配置)。Server 0.6.0。
- **Server:雪碧图整体尺寸上限**(`app/media/ffmpeg.py`):`build_sprite_plan` 增加 `max_sheet_dimension`(默认 3200,与常规 10 列×320 画布一致,不扭曲常规计划);画布超限按比例缩小单格(下限 48px),限制服务端生成超大位图。
- **Image Viewer 修正**(`feature/viewer/`):路由改为只传 `mediaId`;新增 `ImageViewerViewModel` 自行拉取详情,**原图优先**(`original_url` 回退 `cover_url`);平移加边界约束 `clampPanOffset`(纯函数,3 例 JVM 单测),缩回 1x 自动归零。
- **导航重构**:`ImageViewerDestinations`/`PlayerDestinations` 只传 mediaId;`HomeScreen` 新增「批阅」入口;媒体墙视频单击 → `PlayerDestinations`、图片 → `ImageViewerDestinations`(都只传 mediaId)。
- **可复用 Player Core**(`core/media/PlayerCore.kt`):普通播放器与批阅共享同一 Media3 播放层(不允许两套实现);内部 `player`(P0)+ `preload`(P1)两个 ExoPlayer;`settle()` 页面停稳后切到目标并预加载下一条,优先复用已预加载的那台实现"停稳即播";当前缓冲时 `stop()` P1 释放带宽,恢复后重新预加载;`activePlayer()`/`activeIsPreload` 供 UI 绑定正确实例。
- **Media3 普通播放器**(`feature/player/`):`PlayerScreen`(全屏 PlayerView + 返回/播放暂停/进度)+ `PlayerViewModel`(playback API 取直连流地址),`onCleared` 释放共享核心。
- **批阅模式**(`feature/review/`):`ReviewScreen` 竖屏 VerticalPager 视频/图片混合,横屏视频 RESIZE_MODE_FIT 居中;右侧 ❤(点赞写 SQLite)/ 🗑(待删除可撤销 + snackbar 撤销)/ ⋯(详情对话框);顶栏「批阅 X/Y」。`ReviewViewModel`:创建会话(拉 200 条媒体建队列)→ 拉队列 → 页面停稳 `settle` 切 P0/P1 并 `markSeen`;播放流地址按需拉取并缓存。
- **雪碧图轮询 + 位图 LRU**(`feature/mediawall/SpriteViewModel.kt`):未就绪触发生成后按 2s×15 低频轮询清单,生成完成自动置 ready(无需再长按);位图缓存 `MAX_BITMAPS=12` LRU 淘汰,防内存持续上涨。
- 版本号:Server 0.6.0 + Android 0.7.0。

验证：

- Server:`pytest` 全量通过(135 项,较上阶段 +3:original_url 图/视频、雪碧图画布上限、默认上限内不缩放);`ruff check` + `ruff format --check` 全部通过。
- Android:`gradlew :app:assembleDebug :app:testDebugUnitTest` **BUILD SUCCESSFUL**(APK 已产出;ApiModelsTest 4 + UrlNormalizeTest 4 + PanClampTest 3,均 0 失败),本机真实执行。

测试：

- server:+`test_media_original_url_image_present_video_null`、`test_build_sprite_plan_caps_sheet_dimension`、`test_build_sprite_plan_sheet_within_default_cap_unchanged`
- android:+`PanClampTest`(scale=1x 归零 / 边界内保持 / 超界钳制)

遗留：

- 批阅断点恢复(「继续上次批阅」入口)尚未接入,下一轮做(服务端 `/review/sessions/latest` 已就绪)。
- 批阅视频结束后自动前进、播放器底层(倍速/字幕/横竖屏等增强控制)留待播放器稳定后补。
- 真机联调(Media3 实机播放、批阅 P0/P1 预加载手感、雪碧图长按手势)仍待具备环境与真机的机器。
- 本机无 git CLI,未执行分阶段提交(需具备 git 的机器执行 `feat/android-player-review` + `feat/server-original-url-sprite-cap`)。

提交：

- (待 git 环境可用后执行)feat/android-player-review / feat/server-original-url-sprite-cap

---

### 2026-08-20 — 阶段 10 · Stage 10(Android Image Viewer + 雪碧图预览)

完成：

- **媒体墙既存修正收口(上一会话已落盘,本阶段核实并补全编译)**:封面列数/默认排序/类型筛选持久化到 DataStore(`core/datastore/MediaWallSettingsStore`),重启保持;分页并发防护(request generation + Job cancel,旧请求晚归丢弃);错误页「重试」按钮;媒体库筛选入口(全部库/指定库);接近底部自动加载下一页(替代「加载更多」按钮)。
- **修复 Android 编译中断**:上一会话在 `MediaWallScreen` 已接入 `ImageViewerDestinations` 导航但查看器未实现,且存在错误导入 `androidx.compose.foundation.lazy.grid.snapshotFlow`(应为 `androidx.compose.runtime.snapshotFlow`),导致工作区不可编译;本阶段补全查看器并修复导入。
- **Image Viewer**(`feature/viewer/ImageViewerScreen.kt`):全屏大图(Coil),双击缩放、双指缩放、拖动平移,顶栏返回+文件名;路由 `image_viewer/{mediaId}/{name}/{cover}` 三段参数(URL 编码)。媒体墙图片卡片点击进入查看器。
- **Sprite 预览**(`feature/mediawall/`):`MediaReviewApi` 新增 `GET/POST /cache/sprites/{media_id}`;`MediaRepository` 补全雪碧图相对 url 为绝对地址;`SpriteViewModel`(Hilt)按 media_id 缓存清单+位图,未就绪时自动 `ensureSprite` 触发后台生成并提示;`SpritePreviewUi` 实现长按(500ms)进入预览 + 横向滑动映射 `[0,1]` → 格子索引,Canvas 按 columns×rows 网格裁剪绘制当前格,底部时间进度条+当前时间;松手恢复卡片;与滚动容器的垂直滚动做了事件消费冲突规避。
- 版本号:Android 0.6.0(Server 无改动,保持 0.5.0)

验证：

- Android:`gradlew :app:assembleDebug :app:testDebugUnitTest` **BUILD SUCCESSFUL**(APK 输出 `app-debug.apk`;ApiModelsTest 4 + UrlNormalizeTest 4,均 0 失败),本机真实执行。
- Server:`pytest` 全量通过(132 项);`ruff check` + `ruff format --check` 全部通过(72 文件已格式化)。

测试：

- android:ApiModelsTest 4 项 + UrlNormalizeTest 4 项(本阶段新增 Android 侧 UI/交互无 JVM 单测,需真机/模拟器验证)
- server:132 项(本阶段无 Server 改动,全量回归确认未破坏)

遗留：

- Media3 普通播放器为下一阶段(按用户建议:先 Image Viewer + 雪碧图稳定,再接播放器,最后批阅+P0/P1 预加载)。
- 视频卡片单击本阶段不响应(等待 Media3 接入);长按雪碧图预览为主要视频交互。
- 雪碧图长按交互与真实雪碧图数据的体验需真机/模拟器验证(服务端无雪碧图时显示「生成中,请稍后再试」并自动触发生成)。
- 本机无 git CLI,未执行分阶段提交(需具备 git 的机器执行 `feat/android-image-viewer-sprite`)。

提交：

- (待 git 环境可用后执行)feat/android-image-viewer-sprite

---

### 2026-08-19 — 阶段 9 · Stage 09(Android 首次编译 + 修正 + Server Profile/首页/媒体库/媒体墙)

完成：

- **首次真正编译 Android**:本机探测到 Android SDK(`D:\Android\Sdk`,platform 34/35/36)+ JDK 21 + Gradle 8.9 缓存,首次在本机跑通 `assembleDebug` 与 `testDebugUnitTest`。
  - 修编译问题:`AndroidManifest` 中 `usesCleartextTraffic` 由无效子节点改为 `<application android:usesCleartextTraffic="true">`;补 `androidx.hilt:hilt-navigation-compose`(新增 `hiltNavigation="1.2.0"`);Hilt 无法绑定裸 lambda 工厂,改为 `ApiFactory` 类注入;修 `libs.androidx.compose.ui.tooling` 访问器拼写;`launcher.xml` 与 `strings.xml` 重复 `app_name`(删除 launcher.xml);`gradle.properties` 加 `android.suppressUnsupportedCompileSdk=35`。
  - 生成真实 `gradle-wrapper.jar`/`gradlew`(`gradle wrapper --no-validate-url`),工程从此可用 `gradlew` 构建。
- **顺收修正**:
  1. AndroidManifest `allowBackup=false`(防配对 token 随备份外流)。
  2. 手动 IP 默认端口:输入 `192.168.1.10` 自动补 `http://192.168.1.10:8765`,有端口则尊重(`normalizeBaseUrl` + 单测)。
  3. deviceId 不再用服务器 IP:首启生成稳定 UUID 持久化到 DataStore,后续恒定,配对/取消配对不清除。
- **服务器自动发现应答端**(`app/services/discovery.py` + `app/main.py` lifespan):UDP 组播 239.255.42.99:35001 收到 `MEDIAREVIEW_DISCOVER` 即回 `MEDIAREVIEW <name>\n<port>`;独立线程不阻塞主服务;测试 `tests/test_discovery.py` 走真实 UDP(收到探测回包、忽略非探测)。
- **Server 媒体墙封面**:`MediaSummary` 增加 `cover_url`(Jellyfin Primary 缩略图直连 URL),list/detail 均注入。
- **Stage 09 Android 功能**:
  - Server Profile + 首页(`feature/home`,显示服务器地址/设备编号/配对态,入口到媒体库选择与媒体墙)。
  - Library Selection(`feature/library`:媒体库勾选、保存、返回)。
  - Media Wall(`feature/mediawall`):LazyVerticalGrid 封面网格 + 列数(封面大小)滑杆(2~5 列)+ 排序(名称/添加时间/大小/时长,再点切换升降序)+ 类型筛选(全部/视频/图片)+ 拼音无关的简单搜索(服务端 search)+ 分页加载更多,Cover 用 Coil `AsyncImage`(直连 Jellyfin Primary)。
  - 导航:connect → home → library / media_wall。
  - 新增 JVM 单测 `UrlNormalizeTest`(端口补全 4 例)。API 模型 Envelope 解析 4 例仍在。
- 版本号 0.5.0(server + android versionName)

验证：

- Server:`pytest` 全量通过(132 项);`ruff check` + `format` 全部通过
- Android:`gradlew :app:assembleDebug` **BUILD SUCCESSFUL**(APK ≈17.9MB);`:app:testDebugUnitTest` **BUILD SUCCESSFUL**(ApiModelsTest 4 + UrlNormalizeTest 4,均 0 失败)
- 各次 Gradle 构建均在真实环境执行(无模拟)

测试：

- tests/test_discovery.py:UDP 应答端收到探测回包 / 忽略非探测
- android UrlNormalizeTest:无端口补 8765、保留显式端口、去尾部斜杠、https 前缀保留
- android ApiModelsTest:统一响应包 + 配对模型解析

遗留：

- 真机联调(媒体墙真实 Jellyfin 数据、自动发现组播在真机 Wi-Fi 的表现)仍需具备环境机器/真机
- git 提交(本机无 git CLI,需具备 git 的机器执行)
- 下一阶段可进入雪碧图/播放器/批阅

提交：

- (待 git 环境可用后执行)feat/android-mediawall + feat/server-discovery + fix/android-fixes

---

### 2026-08-19 — 阶段 8 · Stage 08(Android 骨架 + Server Discovery + Pairing + 3 项验收修正)

完成：

- **非阻塞验收修正(Server)`(与阶段 8 一并完成)`**:
  1. **Pairing Code 安全**(`app/api/v1/pairing.py`):`POST /pairing/code` 默认仅允许本机回环(127.0.0.1/::1)或管理后台;新增 `security.pairing_code_remote_allowed`(默认 False,开发可开启)作为开发专用开关;远程请求返回 403。手机端只调用 `/pairing/verify`,配对码由电脑管理后台展示。
  2. **/pairing/status 读配置**(`app/api/v1/pairing.py`):不再写死 `pairing_required=True`,改读 `request.app.state.settings.security.pairing_required`。
  3. **Full SHA-256 三层收敛**(`app/services/hash_tasks.py`):`size+duration → quick_hash →(quick_hash 相同且数量≥2)→ full sha256`;避免两个 20GB 视频仅因大小/时长碰巧相同就被整体读取。功能结果不变,只减磁盘压力。
- **Android 工程骨架**(`android/`):`app/` 单模块,Compose + Material3 + Hilt + Retrofit + kotlinx-serialization + DataStore + Navigation Compose;依赖版本集中于 `gradle/libs.versions.toml`;`gradle-wrapper.properties` 指向 Gradle 8.9。
- **Server Discovery**(`android/.../feature/connect/discovery/ServerDiscovery.kt`):UDP 组播探测 + 超时收集(IO 线程,不阻塞主线程),与手动 IP 平级。
- **Android Pairing**(`android/.../feature/connect/data/PairingRepository.kt` + `ConnectViewModel.kt` + `ConnectScreen.kt`):健康检查 → 输入配对码 → `/pairing/verify` 签发 token;`TokenProvider` 内存持有 + `AuthInterceptor` 自动附加 Bearer;`ServerProfileStore`(DataStore)持久化 baseUrl/token/device_id,启动恢复已配对态;手机端不调用 `/pairing/code`。
- 版本号 0.4.0(server pyproject/app/__init__ + android versionName)

验证：

- Server:`pytest` 全量通过(新增回环/远程/status 配置用例,累计 130 项);`ruff check` + `ruff format` 全部通过
- Android:本机无 Java/Android SDK,当前为**源码脚手架未编译**;具备环境的机器执行 `gradlew :app:assembleDebug` 构建、`:app:testDebugUnitTest` 跑 JVM 单测

测试：

- tests/test_stage7_fix.py 新增:远程客户端生成配对码 → 403;本机回环生成 → 200;/pairing/status 读取配置
- android/app/src/test/.../ApiModelsTest.kt:统一响应包与配对模型反序列化(JVM 单测)

遗留：

- Android 需在具备 JDK17 + Android SDK 的机器补编译与仪器测试(含 discovery/pairing 端到端)
- UDP 组播发现依赖服务器回包协议;当前实现仅客户端侧,服务器应答需在进入媒体墙阶段落地,若不可行则退化手动 IP
- 本机无 git,未执行分阶段提交
- 真实 ffmpeg/Jellyfin 实机联调仍待具备环境的机器补验

提交：

- (待 git 环境可用后执行)feat/android-bootstrap + fix-server-pairing-code

---

### 2026-08-19 — 阶段 7-fix(ChatGPT 验收修正: 重复检测多级哈希 + Pairing 认证闭环)

完成：

- **Duplicate Scanner 多级哈希重做**(`app/services/duplicate_scanner.py`):size + duration 仅作**候选筛选**,绝不直接判为 exact。
  - `scan_candidates`: size+duration 一致且 quick_hash 未计算(等待后台任务)。
  - `scan_high_confidence`: size+duration 一致 且 **quick_hash 采样一致**(头部+25/50/75%+尾部组合 SHA-256)-> "高度可信重复"。
  - `scan_exact_duplicates`: size+duration 一致 且整文件 **full sha256 一致** -> 真正的 byte-identical。
  - 新增 `_is_valid_hash`: 过滤 `HASH_UNREADABLE` 哨兵,读取失败的媒体不进入任何分组。
- **后台哈希任务**(`app/services/hash_tasks.py` + 注册进 `app/services/tasks.py`):磁盘 I/O 全部在独立线程执行,不阻塞 FastAPI 请求。两阶段——`quick pass` 为缺失 quick_hash 的已索引媒体采样哈希;`full pass` 仅对候选重复媒体计算整文件 sha256(非候选不做全盘读取以控大文件性能)。分批短会话提交,避免长持 SQLite 写锁;失败写 `unreadable-io` 哨兵防无限重试。
- **Pairing 认证闭环**(`app/services/pairing.py` + `app/db/models.py` + Alembic `0009_auth_tokens` + `app/api/v1/auth.py`):
  - `POST /pairing/verify` 成功后签发可持久化 Bearer token;数据库只存 **token_hash**(SHA-256),明文 token 不落库、不写日志。
  - 受保护 API 接入统一 `require_auth` 依赖: favorites / review / delete-queue(含**永久删除 commit**)/ duplicates,未认证一律 401。
  - `POST /pairing/revoke` 支持撤销设备,revoke 后原 token 立即失效。
  - `paired_device` 新增 `token_hash`(唯一)、`revoked`、`last_seen_at`;0009 用 SQLite batch_alter_table 加唯一约束。
- 版本号 0.3.2

验证：

- `pytest` 全量通过(127 项: 阶段 0~6 + 阶段 7 + 阶段 7-fix 新增用例)
- `ruff check` 0 错误、`ruff format` 全部已格式化

测试：

- tests/test_stage7_fix.py 新增 8 项: 未配对客户端危险 API 全部 401;合法 token 放行危险 API;伪造 token 401;revoke 后立即失效;数据库仅存 token_hash(不泄露明文);后台任务补齐 quick/full 哈希(a/b exact,反例 c 不同);读取失败写哨兵且不判为重复;**size+duration 相同但内容不同的反例不得判为 exact/high**。
- tests/test_phase7_capabilities.py 适配 token 校验(补默认 media_path 使 `has_pending_hashes` 生效)。

遗留：

- 本机无 git,未执行提交(需在具备 git 的机器执行 `feat/server-auth-and-duplicate-hash`)
- 真实 ffmpeg / Jellyfin 实机联调仍需具备环境的机器补验
- Android 端未开始(等待本次 7-fix 验收后再进入)

提交：

- (待 git 环境可用后执行)feat/server-auth-and-duplicate-hash

---

### 2026-08-19 — 阶段 7(Server 收口修正 + 剩余前置能力)

完成：

- **永久删除安全**(`app/services/delete_queue.py` + `app/services/audit.py`):`commit_all` 真实删除前逐项重新校验——
  ① 媒体仍存在于缓存索引且 `media_path` 存在;② 媒体所属库仍处于"已选"(当前允许管理)状态;③ 文件身份一致(当前 size 与入队/索引记录一致,或 mtime 未晚于索引记录;存在 fingerprint 时用 fingerprint 比对)。
  任一项不符即拒绝删除、置 `failed`、记录审计(`delete_commit` 含失败原因),绝不仅凭历史 `media_path` 直接 `os.remove`。实际删除用 `os.replace` 到回收区路径 + 截断,文件被替换/修改时因身份校验失败而拒绝。逐项处理,失败不影响其余项。
- **雪碧图自适应策略**(`app/media/ffmpeg.py`):`build_sprite_plan` 不再"固定约每 2 秒抽帧并可到 240 帧",改为按视频时长自适应闭区间调度——
  `<30s→10~12`、`30s~2min→16~20`、`2~10min→24~30`、`10~60min→30~40`、`>60min→40~60(受 max_frames=60 约束)`;默认 `max_frames` 由 240 改为 60。
  单格尺寸按视频宽高比保持(`_tile_size_for`),竖屏/非 16:9 不再被强制拉成 320×180;取样间隔与网格由帧数反推,保证覆盖率。
- **Playback API**(`app/api/v1/media.py`):`GET /api/v1/media/{media_id}/playback` 返回 Jellyfin 直连更新流地址与视频元数据(title/media_type/duration/宽高/容器),仅视频支持;中间层不转发视频流(遵守 AGENTS.md 禁止项)。
- **Duplicate Scanner**(`app/services/duplicate_scanner.py` + `app/api/v1/duplicates.py`):`scan_all` / `scan_exact_duplicates`(按 size+duration 完全一致分组,size 降序) / `scan_similar_candidates`(疑似重复基础能力)。注册进 `app/api/v1/__init__.py`。
- **Pairing**(`app/db/models.py` + Alembic `0007_pairing` + `app/services/pairing.py` + `app/api/v1/pairing.py`):`paired_device` / `pairing_code` 表;`POST /pairing/code` 生成 6 位码(含过期秒数)、`POST /pairing/verify` 校验码并写入已配对设备(码须存在/未用/未过期)。注册进路由。
- 版本号 0.3.1

验证：

- `pytest` 全量通过(累计 100 + 阶段 7 新增用例)
- `ruff check` + `ruff format` 全部通过

测试：

- tests/test_delete_fav_service.py 新增:文件体积变化禁止删除、同体积替换但 mtime 变化仍禁止删除、所属库被取消勾选禁止删除(均断言失败原因与审计记录,文件保留)
- tests/test_sprite_service.py:自适应帧数闭环(短/中/长/超长)、非 16:9 宽高比保持、max_frames=60 上界
- tests/test_phase7_capabilities.py:Playback API 返回直连流地址与元数据;Duplicate Scanner 完全重复分组;Pairing 生成+校验+过期拒绝+重复设备合并

遗留：

- 真实 ffmpeg 雪碧图生成、真实 Jellyfin/Jellyfin 直连播放联调,仍需在具备环境的机器上补验
- Android 端未开始(等待本次验收后再进入)
- 完全重复检测为"基础能力",疑似重复进阶(内容感知)按 V1 语义仅做候选,不做自动删除

提交：

- (本机 git 可用后执行)feat/server-rework-pairing

---

### 2026-08-19 — 阶段 0 + 阶段 1(Server 基础框架)

完成：

- `server/` Python 工程脚手架:pyproject.toml、venv、依赖锁定安装、ruff + pytest 配置
- `app/core/paths.py`:PathManager 数据目录管理(config/database/cache/logs/runtime/backups),缓存分片路径 `cache/<category>/<key前2位>/<key>/`,非法类别/键直接拒绝
- `app/core/config.py`:AppConfig 五段结构(server/jellyfin/storage/security/features),加载优先级 默认值 → `config/config.json` → 环境变量 `MEDIAREVIEW__SECTION__KEY`;data_root 支持 `%ProgramData%` 展开与 `MEDIAREVIEW_DATA_ROOT` 覆盖;API Key 使用 SecretStr 并提供 `masked_dict()` 脱敏输出
- `app/core/logging.py`:控制台 + 滚动文件双输出,自动注入 request_id
- `app/core/request_id.py`:ContextVar + 中间件,支持外部 X-Request-ID 透传(校验字符白名单防头注入),响应头回传
- `app/core/errors.py`:AppError 错误体系,稳定错误码(MEDIA_NOT_FOUND / JELLYFIN_ERROR / VALIDATION_ERROR 等),用户可见消息全部中文
- `app/core/responses.py`:统一响应包 `{success, data, error, request_id}`,PEP 695 泛型 Envelope
- `app/db/`:SQLAlchemy 2.x ORM(naming convention 稳定约束名)、SQLite WAL/外键/busy_timeout PRAGMA、每请求会话依赖
- Alembic 迁移骨架 + 初始迁移 0001(app_settings 表),应用启动自动 `upgrade head`;env.py 支持注入 URL / 环境变量 / data_root 三级解析
- `app/api/v1/system.py`:`GET /api/v1/system/health`(数据库连通性)、`/info`(配置脱敏)、`/storage`(缓存占用与磁盘空间)
- `app/main.py`:create_app 应用工厂;统一异常处理(AppError / HTTPException 中文映射 / 参数校验 422 / 未知异常 500 不泄露内部信息);模块级 `app` 惰性构造避免导入副作用;OpenAPI 位于 `/api/openapi.json`、文档 `/api/docs`
- 端口 8765、绑定 0.0.0.0 均来自配置,未写死业务代码

验证：

- `pytest` 31 项全部通过
- `ruff check` + `ruff format` 全部通过
- uvicorn 真实启动冒烟测试:health 返回统一包且 database=ok;`/api/docs` 200;外部 X-Request-ID 正确回传

测试：

- tests/test_paths.py:目录结构、分片路径、非法输入拒绝
- tests/test_config.py:默认值、JSON 加载、环境变量覆盖、SecretStr 掩码、损坏配置报 ConfigLoadError、非法端口被拒
- tests/test_system_api.py:health/info/storage/OpenAPI
- tests/test_api_conventions.py:request_id 生成/复用/替换、404/405 统一包、AppError 统一包
- tests/test_db.py:启动自动迁移(alembic_version 存在)、settings 读写、WAL 生效

遗留：

- 本机无 git,未执行分阶段提交(AGENTS.md 第 2 节);git 可用后需补 `feat/server-bootstrap` 提交
- 本机无 Java/Android SDK 与 ffmpeg,阶段 8 起的 Android 构建与阶段 4 的真实 FFmpeg 生成验证受限,需在具备环境的机器上补验
- Alembic `script.py.mako` 的 autogenerate 流程尚未实际使用(首个迁移为手写)
- 生产 token 校验(paired_devices)在阶段 8/配对阶段实现,当前无鉴权中间件

提交：

- (待 git 环境可用后执行)feat/server-bootstrap

---

### 2026-08-19 — 阶段 2(Jellyfin Adapter)

完成：

- `app/adapters/jellyfin/models.py`:JF* 前缀模型宽松解析 Jellyfin 原始响应(System/Info、Users、Items、分页),时间戳统一转无时区 UTC;统一 DTO `Library` / `MediaItem`(含 media_id、fingerprint)
- `app/adapters/jellyfin/mapper.py`:类型映射(Movie/Episode/Video/MusicVideo→video,Photo→image,其余拒绝)、ticks→毫秒换算、media_id=sha256(jellyfin_id) 前 24 位、fingerprint=path规范化+size+modified,与 ARCHITECTURE 第 8 节一致
- `app/adapters/jellyfin/client.py`:httpx.AsyncClient 封装;连接/超时→JellyfinError(中文提示),401/403→JellyfinAuthError(JELLYFIN_AUTH_FAILED);能力:system_info / users / libraries / items 分页(单页上限 1000)/ 单条 media / 直连 URL 构造(视频 stream?static=true、原图 Download、缩略图 Images/Primary,均含 api_key 供 Android 直连)/ 播放进度上报(Sessions/Playing/Progress,PositionTicks 换算)
- `app/api/v1/jellyfin.py`:`GET /api/v1/jellyfin/status`、`/users`、`/libraries?user_id=`;未配置 API Key 时返回 CONFIG_ERROR(不发起网络请求)
- Jellyfin 原始 JSON 结构未泄漏到 adapter 之外(遵守阶段 2 禁止项)

验证：

- `pytest` 62 项全部通过(新增 31 项)
- `ruff check` + `ruff format` 全部通过

测试：

- tests/test_jellyfin_mapper.py:类型识别、ticks 换算、media_id 稳定性、路径规范化指纹、大小变化改变指纹、非法类型拒绝、未命名库兜底
- tests/test_jellyfin_client.py:全部走 httpx.MockTransport;系统信息/用户/库/分页映射/单条获取、Authorization 头、401→认证错误、连接失败→502、进度上报报文体、直连 URL 格式
- tests/test_jellyfin_api.py:三个端点统一包、user_id 缺失 422、上游失败 502 统一包、未配置 CONFIG_ERROR

遗留：

- 直连 URL 含 api_key(局域网 V1 取舍,客户端仅限已配对设备;配对鉴权在后续阶段接入)
- 阶段 3 需将 user_id 持久化到配置(当前由客户端显式传入)
- 真实 Jellyfin 10.x 实机联调待部署阶段补验(本机无 Jellyfin)

提交：

- (待 git 环境可用后执行)feat/jellyfin-adapter

---

### 2026-08-19 — 阶段 4 + 5 + 6(Cache/Sprite + Review Engine + Favorites/Delete Queue)

完成：

- `app/db/models.py` + Alembic `0003_cache_sprite` / `0004_review_engine` / `0005_favorites_delete_queue` / `0006_add_media_path`:新增 `background_task`(后台任务: sprite/pending/running/进度/错误)、`sprite_manifest`(雪碧图清单: 网格、瓦片尺寸、取样间隔、指纹、状态、url)、`review_session` / `review_session_item`(批阅会话: 快照/进度/断点;item.session_id 为普通索引列,与全库无外键约定一致)、`favorite`(点赞)、`delete_queue`(待删除两阶段)、`audit_log`(审计);`media_cache_index` 增加 `media_path`(服务端内部真实文件路径,绝不暴露给客户端)
- `app/media/ffmpeg.py`:`SpritePlan` / `build_sprite_plan`(按时长计算网格与取样间隔,受 min/max 帧约束)、`build_ffmpeg_args`(纯函数拼 tile 命令)、`FfmpegExecutor`(ffprobe/ffmpeg 封装,路径解析支持 `storage.ffmpeg_dir` 或系统 PATH)、`MediaExecutor` 协议便于测试注入替身
- `app/services/tasks.py`:`TaskManager` 后台任务引擎(轮询 pending→`asyncio.to_thread` 执行,不阻塞 HTTP 事件循环与 SQLite 写锁;注册/启停/孤儿任务失败处理),`app/main.py` lifespan 接入(sprites 特性开启时注册 sprite 处理器)
- `app/services/sprite.py` + `app/api/v1/cache.py`:GET 缓存统计、GET 任务状态、POST 生成雪碧图(就绪直返/指纹匹配缓存验证/否则编排后台任务)、缓存失效(指纹变化清理陈旧文件)、GET 雪碧图文件;清单含 url 直连地址
- `app/services/review.py` + `app/api/v1/review.py`:POST 创建会话(去重保序、filter/sort 快照、断点恢复)、GET 会话/详情、GET 队列(含当前索引媒体)、POST 标记 seen、POST 前进/后退、POST 完成、POST 恢复
- `app/services/favorites.py` + `app/api/v1/favorites.py`:GET 点赞列表、POST/DELETE 点赞(幂等,校验 media 存在)
- `app/services/delete_queue.py` + `app/api/v1/delete_queue.py`:GET 待删列表、POST 入队(可撤销)、DELETE 撤销、POST commit(两阶段最终一步: 真实删除 `media_path` 文件并清理索引/点赞/队列;路由 `commit` 置于动态媒体 id 之前防遮蔽)
- `app/services/audit.py`:点赞增删、入队/撤销/删除提交等关键操作审计日志
- `app/api/v1/__init__.py` 挂载 cache/review/favorites/delete_queue 路由
- 版本号 0.3.0

验证：

- `pytest` 100 项全部通过(新增 test_sprite_service / test_review_service / test_delete_fav_service / test_phase456_api / test_tasks 等)
- `ruff check` + `ruff format` 全部通过

测试：

- tests/test_sprite_service.py:短/长视频网格规划、min_frames 上抬、ffmpeg 参数、mock executor 生成真实文件与清单、指纹缓存失效、任务失败路径
- tests/test_review_service.py:会话去重保序、快照、seen 推进、完成
- tests/test_delete_fav_service.py:点赞幂等、待删除两阶段生命周期真实删文件、缺路径仍清理索引
- tests/test_phase456_api.py:cache/review/favorites/delete-queue 全链路集成
- tests/conftest.py:Database fixture 基于 tmp_path 隔离

遗留：

- 真实 FFmpeg 生成、app 启动冒烟(TaskManager 守护运行)需在具备 ffmpeg 的机器回放sprite 端到端验证,本机无 ffmpeg
- 阶段 7 起 Android 端接入这些 API(批阅 UI/雪碧图预览/点赞/删除队列)
- 重复检测(完全/疑似)为独立阶段,尚未实现
- 本机无 git,未执行分阶段提交

提交：

- (待 git 环境可用后执行)feat/cache-and-sprite / feat/review-engine / feat/favorite-delete

---

### 2026-08-19 — 阶段 3(Library Selection + Media Index)

完成：

- `app/db/models.py` + Alembic `0002_library_media_index`:新增 `library_selection`(媒体库勾选: jellyfin_id/name/collection_type/selected/sort_order)与 `media_cache_index`(媒体缓存索引: media_id 主键、jellyfin_id、library_id、name、media_type、duration/size/宽高/容器、fingerprint、created/modified/synced),只缓存本项目需要字段,不复制 Jellyfin 数据库
- `app/services/media_index.py`:媒体库 upsert(新增默认勾选、保留已有勾选)、多库勾选保存(app 只调整已知库,忽略未知 id)、已选库查询;媒体缓存索引按 media_id upsert(返回新增条数)、单条读取;`collect_library_items` 分页拉取单库全部统一 MediaItem(单页 500)
- `app/adapters/jellyfin/mapper.py`:`include_types_for(media_type)` 映射统一类型 → Jellyfin IncludeItemTypes(按需限制 video/image)
- `app/core/config.py`:`persist_jellyfin_user_id`,把 user_id 原子写入 config.json(临时文件替换),保留其余配置、损坏文件容错、同值幂等
- `app/api/v1/libraries.py`:`GET /api/v1/libraries`(确定用户: 显式 user_id > 已持久化配置 > 单用户自动发现,并持久化;返回勾选列表)、`PUT /api/v1/libraries/selection`(保存多库勾选)
- `app/api/v1/media.py`:`GET /api/v1/media`(按 library_id 或全部已选库采集,支持 page/page_size 分页、排序白名单 name/created/size/duration、media_type 筛选、search 搜索,并落地缓存索引)、`GET /api/v1/media/{media_id}`(读缓存索引返回单条详情,不泄露路径/Jellyfin 内部 ID)
- 版本号 0.2.0

验证：

- `pytest` 79 项全部通过(新增 17 项: libraries/media 接口 + user_id 持久化单元)
- `ruff check` + `ruff format` 全部通过

测试：

- tests/test_library_api.py:user_id 持久化(写入/保留其余配置/损坏文件)、显式与已配置 user_id 列表、勾选保存、未知 id 忽略
- tests/test_media_api.py:单库默认排序、size 排序、video/image 类型筛选、无匹配、分页、排序字段白名单 422、未勾选 422、全部已选库合并、详情回路、MEDIA_NOT_FOUND
- tests/conftest.py:Jellyfin mock 升级为按 ParentId 返回媒体库(电影 5 视频/照片 2 图)、按 IncludeItemTypes 过滤(镜像真实 Jellyfin);jellyfin_api_client fixture 预设 user_id
- tests/test_jellyfin_client.py:media_page 断言随 mock 扩展同步更新(电影库 5 项、照片库 2 项)

遗留：

- 采集"全部已选库"合并时采用内存全量拉取+本地分页/排序,适合个人局域网规模;超大库需在阶段 4 缓存索引后改为 DB 查询
- 排序为内存白名单实现,未透传 Jellyfin SortBy;后续如需规模化可改为上游排序
- user_id 持久化后,后续启动以 config.json 为准;本机无 Jellyfin 实机联调仍待部署阶段补验
- 本机无 git,未执行分阶段提交

提交：

- (待 git 环境可用后执行)feat/media-index

---

### 2026-08-24 — 1.1 Task 1 · 数据库优先媒体索引与后台同步

完成：

- Server 版本更新为 `1.1.0`；`GET /api/v1/media` 改为 SQLite 唯一列表数据源，
  count、library/type/search/favorite exclusion、六类排序与 page slicing 全部在 SQL 完成。
- `MediaCacheIndex` 增加 `is_available`、`sync_generation`、`last_seen_at`，新增
  `MediaSyncState`；Alembic `0010` 把旧行无损升级为 available，并增加媒体墙查询索引。
- `media_refresh` 注册到 `TaskManager`：Jellyfin 每页 500 条，SQLite bulk upsert，
  generation 完整成功门禁；失败/取消不隐藏旧缓存，错误只记录脱敏中文消息。
- 新增 `POST /api/v1/media/refresh`、`GET /api/v1/tasks`、
  `GET /api/v1/tasks/{task_id}`、`POST /api/v1/tasks/{task_id}/cancel`；均保持配对认证边界，
  通用任务视图不返回 raw params/result/traceback。
- `MediaPage` 增加 `sync`；空缓存立即返回并只编排一个任务，已有缓存同步失败时仍立即可读。

验证：

- 真实 RED：新增 focused 验收首次 `13 failed`，失败原因为缺失 SQL 查询、同步状态/处理器、
  refresh/tasks API 和 0010 列。
- focused GREEN：`15 passed`；全量 Server：`165 passed`，仅保留已记录的第三方
  Starlette `httpx` 弃用警告。
- `ruff check .`：`All checks passed!`；`ruff format --check .`：通过。
- 10 万条 SQLite 索引、50 项分页断言 `< 1s`；生产性能目标记录为 `< 250ms`。
- 自审追加 RED→GREEN：active-target 部分唯一索引防并发重复活动任务；旧调用路径 upsert
  不清除活动 `sync_generation`；最后一页提交后再次检查取消状态，避免 cancelled 任务进入
  unseen 失效窗口。

测试：

- `test_media_index_11.py`：SQL 筛选/排序/稳定随机/availability/10 万条性能、失败、取消、
  generation 成功门禁。
- `test_media_sync_api_11.py`：GET 零 Jellyfin Items、空缓存幂等入队、force 幂等、目标校验、
  通用任务查询/取消/脱敏/认证。
- `test_db.py`：从真实 0009 schema 升级 0010，媒体行和收藏状态无损。
- `test_media_api.py`：原详情、播放进度、收藏、图片 URL 与分页兼容路径改为预置 SQLite。

独立审查返修：

- `GET /media` 移除 Jellyfin client 依赖，直连 URL 改用纯配置函数；未知/未选库不再由 GET
  自动入队。
- 全目标成功后才以任务终态 CAS、unseen 失效和库状态同事务提交；第二库失败与末页取消
  竞态均保留所有旧 unseen。
- `TaskManager` 用 SQLite 条件 UPDATE/RETURNING 原子 claim，双 manager 只有一个胜出。
- 0010/ORM/query 统一五种固定排序表达式，并补齐有/无 media type 索引；真实 0009→0010、
  100,000 行、24 个排序组合通过 `<1s`，固定排序关键计划无 `TEMP B-TREE`。
- 返修 focused `25 passed`；全量重跑 `171 passed`；ruff check/format 均通过。

第二轮独立复审返修：

- cancel API 改为单条 pending/running 条件 UPDATE/RETURNING；只有 CAS winner 才更新
  `MediaSyncState` 和释放目标租约，陈旧 ORM 请求不能覆盖 succeeded。
- 0010 新增 `media_refresh_target` 库主键租约；调度把 `[A]` 与 `[A,B]` 等重叠请求拆为
  不相交活动任务，并发调度也由数据库唯一约束兜底。
- `upsert_media_items()` 内部按 500 条分块；16,000 条 legacy payload 两次 upsert 均不触发
  SQLite 变量上限，新增计数语义保持。
- 单库复合索引之外新增多库全局排序索引；真实 0009→0010、100,000 行、单/多库 × 六排序
  × 升降序 × 有/无 media type 共 48 组合通过 `<1s`，五种固定排序分别命中 library/global
  索引且无 `TEMP B-TREE`。

遗留：

- `< 250ms` 是生产目标，本任务自动化只强制测试机 `< 1s`；真实 56,533 条生产副本、
  后台刷新耗时和手机端超时消失需后续部署/实机阶段验证。
- 本任务未修改 Task 2 批阅建队；其现有同步 Jellyfin 行为按范围留给下一任务。

提交：

- `feat(server): add database-first media refresh`（本任务单一提交）

---

### 2026-08-24 — MediaReview 1.1 Task 2 · 批阅数据库建队与稳定分页

完成：

- `POST /api/v1/review/sessions` 改为 SQLite-only：只读取当前已选库的可用
  `MediaCacheIndex`，不构造 Jellyfin/httpx 客户端、不调用 `/Items`。
- source 的 `media_type`、`search`、`sort_by`、`sort_order` 按 `GET /media` 语义校验；
  random 排序把 seed 固化到会话快照，保证保存队列可复现。
- SQL 窗口函数完成筛选、稳定 NULL-last 排序、exact 代表项和连续绝对 index；队列使用单次
  `INSERT ... SELECT` 写入。只有 full SHA-256 确认组折叠，疑似重复保留，不修改媒体文件。
- 旧 active 完成、新会话和队列处于同一事务；校验或插入失败保持旧 active 且不留部分新队列。
- queue API 改为 SQL `COUNT + OFFSET/LIMIT` 后一次 JOIN 媒体索引，不调用全量
  `session_items()`，无逐项 N+1；缺失/不可用媒体不压缩绝对 index 或 total。
- latest-active 对相同 updated/created 时间增加 session ID 稳定 tie-break；旧 current index、
  seen、position、advance、complete、恢复与 envelope 回归通过。

验证：

- TDD RED：`server/tests/test_review_queue_11.py` 初次运行 `6 failed`，失败原因均为待实现合同。
- focused GREEN：新增 Task 2 测试 `6 passed`；批阅/鉴权回归 `35 passed`。
- 100,000 行隔离建队实测 `1.112s`，低于测试机 `<5s` 门槛（非生产 SLA）。
- full server pytest：`182 passed, 1 warning in 82.97s`；warning 为既有 Starlette/httpx
  deprecation，不影响结果。

遗留：

- `<5s` 仅为隔离测试机门禁；真实媒体库并发刷新期间的写锁竞争和部署机器耗时留待后续实测。
- 本任务未进入 Android、播放、删除、指纹算法、后台刷新或部署范围。

独立审查修复（C1/I1/M1）：

- **C1 API Key 边界**：review queue、media list/detail 的 cover/original 改为 MediaReview 相对
  URL；新增配对认证 thumbnail/original 图片代理。Jellyfin key 仅在上游 Authorization header，
  不进入 URL/JSON；代理验证媒体类型、`image/*`、25 MiB 上限并脱敏错误，绝不代理视频流。
- **I1 exact 合同**：新增共享 `hash_contract`，Python scanner 与 SQLite SQL 共同要求精确
  64 位 hex full SHA-256；短值、非 hex、失败哨兵和 quick-only 不折叠。
- **M1 单一生产入口**：删除 API 层未使用的 `_dedupe_exact_duplicates`、依赖与私有直测，只保留
  `create_session_from_index` 真实入口验收。
- TDD remediation RED：安全/哈希 focused 首次 `6 failed, 3 passed`；GREEN `9 passed`。
- remediation full server pytest：`190 passed, 1 warning in 96.24s`。
- 严格 hash SQL 合同后的 100,000 行隔离建队实测 `1.212s`，仍低于 `<5s` 测试机门禁。

第二轮独立审查修复（C2/I1/I2/M2）：

- **C2 播放凭据边界**：legacy `stream_url` 保留但移除 Jellyfin server API key；响应新增
  `requires_jellyfin_auth=true` 与中文状态，明确当前凭据不足且不可直接播放。中间层未新增视频代理；
  Task 6 负责建立可撤销客户端凭据或正式 playback contract。
- **I1 SQLite 动态类型边界**：SQLite 连接注册共享 strict full SHA-256 deterministic UDF，SQL 同时
  要求 `typeof=text` 与原始字节长度 64。NUL 头/中/尾、`64hex+NUL+suffix`、BLOB、Unicode、非 hex
  与 Python scanner 保持一致，真实建队不误折叠。
- **I2 图片重定向边界**：图片读取显式 `follow_redirects=False`；同源、跨源、链路本地和循环 30x
  均不发起第二跳，返回不含 Location、上游 body 或 server key 的脱敏错误。
- **M2 单一排序合同**：删除已无调用的 `_SORT_KEY_FN` / `_sort_items()`，并把图片字段注释更新为
  需配对认证的 MediaReview 相对代理 URL。

第三轮独立审查修复（I3/M3）：

- **I3 配置源头约束**：`JellyfinConfig.url` 在构造和赋值时统一规范化 HTTP(S) scheme、host、port
  与安全 base path；拒绝 userinfo/query/fragment、空或非法 host、非法 port、控制字符、反斜杠、
  空路径段与 dot-segment。验证错误隐藏原始输入，避免含 key 的非法 URL 进入错误文本。
- Jellyfin client 和 direct URL builder 复用同一规范化函数，user/item ID 统一按单个 URL path
  segment 编码。完整 playback JSON 回归覆盖 base path、特殊 item ID、body/header 无 server key，
  继续只返回无凭据 direct URL且不新增视频代理。
- **M3 依赖入口收敛**：删除 Task 2 旧同步建队移除后已无调用的 `build_jellyfin_client()`；保留
  FastAPI `jellyfin_client` async dependency 作为唯一生命周期/测试 override 入口。

最终放行审查修复（I4）：

- `JellyfinConfig` 增加 url/api_key 模型级交叉校验，构造、先 key 后 URL、先 URL 后 key 三条路径
  都在赋值落地前拒绝 URL 任意组件携带非空 server key；大小写变化、合法 host/base path 与逐字节
  percent 编码均覆盖，ValidationError 继续隐藏输入与 key。
- Jellyfin client 构造、`video_stream_url()` 每次生成前后以及 playback DTO 序列化前分别执行同一
  无 key 终检。测试使用 `model_construct`、私有 base URL 变更和 mock builder 模拟未来配置/依赖
  绕过，均只得到脱敏配置错误；合法无 key base path 继续保留，仍未新增视频代理。

提交：

- `feat(server): build review queues from sqlite`（本任务单一提交）

---

### 2026-08-24 — MediaReview 1.1 Task 3 · 局域网 URL、发现与配对身份

完成：

- Server TCP 默认/部署脚本统一到 `8766`，UDP 继续 `35001`；Android 解析发现服务名和端口，
  使用数据包来源 host 去重并执行 `/health` 确认，手动 hostname/IPv4/括号 IPv6 始终可用。
- 新增可选 `jellyfin.client_url`；留空时按认证请求访问 MediaReview 的 host 派生 Jellyfin
  client-facing base URL并保留 scheme/port/base path。诊断隐藏 server-only URL；server key 不进
  URL/JSON/header，图片代理与禁止视频代理合同保持。
- Android 新增集中 `MediaUrlResolver`，在 repository 数据边界解析所有 Coil/Media3 使用的图片、
  雪碧图与 playback URL，拒绝非 HTTP(S)、userinfo、畸形与凭据参数。
- installation ID 首次生成后持久化；删除 `android-default` fallback。Server 按规范化
  installation ID upsert，同设备三次配对只保留一行并逐次使旧 token 失效。
- Alembic `0012_pairing_device_identity` 直接从 `0010` 链接；合并仅限 trim+casefold 完全相同
  的旧 ID并保留最新有效凭据，不猜测不同历史 ID。升级、回滚和数据保留有真实迁移测试。
- Android bearer token 改用 Keystore AES-GCM；首次读取迁移旧明文 key后删除，clear 删除凭据与
  base URL但保留 installation ID。连接模型分别暴露 MediaReview/Jellyfin/sync/authentication。

TDD 与验证：

- Server RED：Task 3 focused 初次 `7 failed`；诊断 loopback 另有独立 `1 failed`。
- Server GREEN：Task 3 focused 通过；全量 `244 tests` 通过，ruff check/format 与 diff check 通过。
- Android RED：focused 首次因待实现 resolver/discovery/credential/state 接口稳定失败；实现后
  首轮 `15/15`，自审补凭据持久化/内存同时 clear 后最终 `16/16` focused 通过。
- Android full：`52` 个 JVM 测试通过，`:app:assembleDebug` 成功。

遗留边界：

- 按任务约束未进行真实 LAN/组播、真机、真实 Jellyfin 或 Android Keystore instrumentation；
  这些属于部署/物理设备验收，不由 JVM/ASGI 证据代替。
- 三个不同历史 ID 无法安全推断为同一物理设备，0012 会保留三行并在 Task 3 report 中明确为
  cleanup limitation。
- 未做 Task 4 视觉重构、Task 6 播放凭据、HLS/播放器状态机、删除/重复或部署/live-service 变更。

---

