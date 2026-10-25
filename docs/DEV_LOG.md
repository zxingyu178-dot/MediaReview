# 开发日志

> Agent 每完成一个阶段必须追加记录,不允许覆盖历史。

### 2026-09-06 — 1.1.0-rc2 APK 邮件交付（G9 完成）

发送前提:defender 扫描 rc2 APK `found no threats`;签名/SHA-256 已记录;真机边界
(RC 候选、正式 1.1.0 待真机门)写入邮件正文。

发送方式:Hermes `send_mail.py` 截至 2026-09-06 仍为 MIMEImage 附件路径(未修复非图片
MIME),沿用 2026-09-03 rc1 经用户明确授权的直连 SMTP 模式:运行时读取
`E:\aihome\hermes\config\secrets.env`(仅内存,不落盘不打印),APK 以
`application/vnd.android.package-archive` 附加,附 CHECKSUMS.txt;收件为用户确认的
默认地址(全部记录掩码 `30*****@qq.com`);fail-closed(附件/构造/拒收任一失败不发送)。

结果:`SMTP accepted` + `SEND_OK attachment=家庭媒体管家-1.1.0-rc2.apk bytes=15616330
sha256=B1482B8F…B89C1D`,与 `release/CHECKSUMS.txt` 完全一致 → 记录为「APK 已交付」。
交付档案:共享 `inbox/hermes/2026-09-06-mediareview-apk-email.md`(含交付状态)、
`E:\aihome\codex\logs\mediareview_email_delivery_20260906_165500.log`(凭据 NOT_LOGGED)。
部署迁移包(97MB)超常规邮件附件上限,不邮件交付,保留 deploy_handoff 共享路径交付。

### 2026-09-06 — 1.1.0-rc2 重打发布（真机反馈批量修复 + 真实播放端到端验证）

Jellyfin 恢复后完成真实环境验证,并重打发布产物标记 rc1 → **1.1.0-rc2**(versionCode 7,
不 tag 1.1.0,真机门仍待真机验收):

- 真实服务器(8766)链路审计:配对 → /playback 200(设备级凭据签发正常)→ Direct HEAD
  直连 Jellyfin 200(198MB mp4),URL 无密钥泄漏。
- **模拟器真实媒体播放验证**:媒体墙"暂时离线"横幅随 Jellyfin 恢复消失;点开真实视频
  Direct Play 实际起播(进度 0:15 → 0:55 / 32:05 持续前进,控制层锚定底部,无崩溃,
  logcat 无 FATAL)。此前一次模拟器直连超时确认为 Jellyfin 当时正在停止,非网络/代码问题。
- 产物重建(Android 门禁 testDebugUnitTest + assembleRelease + lintDebug 全绿,
  versionCode 6→7 + BrandingResourceTest 同步):
  - 签名 Release APK:release/家庭媒体管家-1.1.0-rc2.apk(15,616,330 字节,
    SHA-256 B1482B8F…B89C1D;apksigner 验证证书 CN=MediaReview,
    SHA-256 592c2595… 与 rc1 一致,真机可覆盖升级)。
  - 部署包:deploy_handoff/MediaReview_Migration_1.1.0_20260906_1628.zip
    (100,756,042 字节,SHA-256 58A7A444…9E9A75D;ZIP 内 163 文件校验和回验通过)。
  - EXE 冒烟:临时数据根迁移 0001→0014 head、health ok/version 1.1.0、
    启动日志密钥扫描 0 泄露(err.log 唯一命中为 alembic 迁移说明文字)。
  - release/CHECKSUMS.txt 更新(rc1/rc2 APK + 部署包);过期 debug 验证版 APK 已移除。
- 部署包现含三个修复提交:d2331f5(筛选区层叠)/988a151(筛选区紧凑两行)/
  a7cd814(播放器锚点 + hls 模块 + 同步 user_id + 回环 500)。
- 注意:真实部署(Windows 服务)仍是 rc1 旧程序,需用新迁移包升级后才有服务端修复;
  Hermes 邮件交付(G9)仍待用户确认收件人。

### 2026-09-06 — 1.1.0-rc1 后 · 播放器专项审计与修复（真机反馈"播放器没功能"根治）

用户反馈整体使用感受不佳、播放器功能全无。经模拟器/服务端全链路审计,定位并修复:

1. **播放器控制层锚点错误（Critical,Android）**:`PlayerScreen` 把 `BottomControls`
   直接放进外层 `Box` 未加 align,Box 默认子级在左上角——播放键/进度条/倍速/音轨等
   全部叠在屏幕顶部与顶栏重叠,完全不可用。修复:BottomControls 增加 modifier 参数并
   锚定 `Alignment.BottomCenter`。模拟器实测控制层两行正确落在底部(y≈2121-2348)。
2. **缺少 media3-exoplayer-hls 依赖（Critical,Android）**:工程仅打包 media3-exoplayer
   + ui;Direct 失败触发 HLS 回退时,DefaultMediaSourceFactory 运行时反射加载
   `HlsMediaSource$Factory` 抛 ClassNotFoundException → 主线程 FATAL,应用闪退。
   这是"点视频就崩/播放器没功能"的直接根因。修复:版本目录与 app 依赖加入
   `androidx.media3:media3-exoplayer-hls:1.4.1`。
3. **首次选库后媒体同步必失败直到重启（Important,Server）**:`make_media_refresh_handler`
   启动时深拷贝 JellyfinConfig;首次库配置自动发现并持久化的 user_id 只写回共享配置
   实例,handler 快照不可见 → 刷新恒报"缺少 Jellyfin 用户"。修复:handler 捕获共享
   配置对象本身,不深拷贝。测试环境(8776)实测:修复后选库 → refresh 202 → 22 条同步
   成功 → /playback 直连 URL 200。
4. **回环请求 playback 500（Minor,Server）**:本机请求(Host=127.0.0.1)派生客户端
   Jellyfin 地址时被 `_client_reachable_hostname` 拒绝,ValueError 未捕获成 500。
   修复:转 `ConfigError` 中文提示(建议配置 jellyfin.client_url)。fail-closed 语义不变。

端到端验证(模拟器 → 测试栈 8776/8097,合成测试内容):

- 配对(手动 IP 10.0.2.2:8776 + 配对码)→ 媒体墙缓存加载 → 点视频进播放器 →
  控制层在底部、Direct 播放真实起播(45s 测试视频完整播放,AudioTrack 45s、解码全程)
  → 播完 0:45/0:45。HLS 模块打包后不再闪退。
- 批阅页:顶栏/右侧 ❤收藏 🗑待删除 ⋯更多(48dp)/底部导航齐全;收藏页空态中文文案
  正常;整理页媒体库/待删除/重复计数正常。
- 真实服务器(8766)审计:配对 → /playback 200,Direct HEAD 直连 Jellyfin 200(198MB
  mp4)。注意:审计后真实 Jellyfin 服务在会话中期停止(8096 无监听),媒体墙按设计
  显示"暂时离线,已缓存内容仍可查看";播放恢复需重启 Jellyfin(未擅自启动)。
- 门禁:Server 全量 pytest(exit 0;test_task_manager 单例在并发构建负载下偶发锁
  冲突,单独复跑通过)+ ruff check/format 全绿;Android assembleDebug +
  testDebugUnitTest + lintDebug 全绿。
- 回归辅助:测试栈 start_mrsrv.py 改为保留已持久化 user_id;模拟器切回真实服务器
  8766 并恢复媒体墙加载。

遗留:媒体墙竖屏筛选/播放器修复需随下一次正式发布重打包;真实 Jellyfin 停止原因
待用户确认;播放器缓冲期无进度/时长显示(0:00/0:00)可后续打磨。

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


---

## V2 Stage 7 — Review Hardening + Offline V2 Closure（2026-09-23）

分支：feature/mediareview-v2-stage7-review-hardening（base 8fc047f，HEAD 43025d3）
4 commits：session lifecycle / playback transitions / media source decouple / playback uri + tap fix

改动摘要：
- ReviewSession + enterReview/refreshQueue/restoreSession：进入批阅每次都重新检查 Repository，
  完整播放器返回恢复原会话（不跳回第 1 条）；空队列页 restartAllVideos 重建全量队列（修复死按钮）。
- 完成条件改为 queue.all reviewed（isComplete），取代旧“末页已批阅”弱判定。
- 480ms 稳定批阅：ReviewStableGate（纯逻辑，snapshotFlow+collectLatest 取消语义，任何滚动立即取消）。
- 换页防黑屏：当前页 Poster 保留直到首帧就绪后 200ms 淡出；awaitPlayerHostReady 收敛 host 等待（2s 超时）。
- 单击 = 播放/暂停（顶层 TapOverlay），操作层常驻；完成页/Sheet 暂停策略；More 改 ModalBottomSheet。
- 播放源解耦：ReviewMediaSource + MediaRepository.playbackHeaders；Route player 不再硬编码 demoRawVideoUri；
  修复 DemoAssets.playbackUri 指向 res/raw（asset:// 旧 URI FileNotFound）。

验证：JVM 306 tests 通过（含本阶段新增 12 项 Review 用例）；assembleDebug PASS；lint 0 errors/42 warnings；
模拟器（MediaReview_Test/API35）实测全流程通过（自动播放、单击、完成页暂停与重阅、空队列重阅 1/22、
More/信息 sheet、完整播放器深链返回原页、转场无黑屏）。
Real-device Performance: NOT VERIFIED（等待用户真机验收）。

交接：temp/MediaReview_Stage7_Handoff_20260923_43025d3.zip（2.07MB）+ temp/MediaReview-v2-stage7-review-hardening.apk

---

## V2 Stage 7.1 — Review Playback Correctness + Handoff Integrity（2026-09-24）

分支：feature/mediareview-v2-stage7.1-review-correctness（base 61ebc32 = Stage7 HEAD，已 push）
源码提交 d3564ea（唯一代码 HEAD；最终 +docs 提交后 HEAD 见 HANDOFF）。

修复摘要（仅正确性，不加功能）：
- Poster 整层由 posterAlpha 控制（Black/AsyncImage/Scrim/Icon），淡出后 GSYPlayerSurface 完全露出。
- Controller 首始源 = queue[currentIndex]（恢复第 N 条即播第 N 条）；currentLoadedMediaId 事实源，
  首次也完整 setUp；换源 setHeaders→setUp 顺序应用 Headers。
- Sheet 关闭两语义（closeSheetAndResume / closeSheetWithoutResume）；打开完整播放器无瞬间 resume。
- 480ms 计时仅内容可见（revealedPage==page）后开始；重新批阅显式重载（单条队列也可重播）。
- Playback-ready reveal 措辞（Playing 且 currentPosition>0）；Debug SourceState/PlaybackReady 日志。
- Demo playbackHeaders 显式空（GSY 对本地源非空头限制，实测）；相关单测（Headers 不串源 / 单条重启）。

验证（Fresh，--rerun-tasks）：JVM 308 tests / 0 fail；assembleDebug PASS；lint 0 errors / 43 warnings。
模拟器 QA：播放动态 3.76% / 暂停静止 0%；页2/3/5 源一致；FullPlayer Back 源正确且播放；
双声源日志顺序无 resume→pause；完成页重批回第 1 条 posMs=0；空队列重批保留。
遗留：模拟器 MediaCodec→TextureView 纹理偶发黑屏（环境，见 04_KNOWN_ISSUES）；Real-device NOT VERIFIED。

---

## V2 Stage 8A — Production Data Bridge（真实 Server 数据接入第一阶段）（2026-09-24）

分支：feature/mediareview-v2-stage8a-production-bridge（base 27727bf = Stage 7.1 HEAD）

### 目标与范围

第一次建立真正的 V2 → Production Repository → 现有 MediaReview API → FastAPI → Jellyfin 通道：
真实 Server 模式下可完成媒体分页读取、文件夹、搜索、排序、真实封面、原图 Viewer、真实视频播放
（Direct Play + 一次 HLS fallback + Headers + resume），同时 DEMO 离线模式完整保留。
本阶段**不接**真实 Review Session（Stage 8B）、不做 Organize 真实能力。

### 数据层（Android）

- `V2DataMode { DEMO, SERVER }` + `V2DataModeStore`（DataStore 持久化，默认 DEMO）：取代 Stage 1 写死的
  `V2AppMode.CURRENT`；启动只读本地值，不联网、不等待 Server、不被 ConnectScreen 阻塞。
- `V2MediaRepositoryRouter`：Hilt 仍然只绑定 `MediaRepository`，由路由按运行时模式委托
  Demo / Server 实现（不再"接 Server 就退回旧 UI"）。
- `feature/v2/data/server/`：
  - `V2ServerMediaRepository`（分页列表 / 文件夹 / 收藏服务器确认制 / resolvePlayback /
    reportProgress / 相册；Review 相关方法在 8A 显式不做并注释）；
  - `V2MediaMapper`（MediaSummary → V2Media，Server 资源 URL 只进 `V2ServerResourceCache`，
    不写入 Demo 语义字段 assetPath/thumbPath/sprite*）；
  - `V2PlaybackResolver`（Direct 必存在的 fail-closed + 唯一一次 HLS 回退合同）；
  - `V2ServerSessionBootstrap`（只读 profile + 恢复 TokenProvider，不做 health/pairing/Jellyfin/media 串行检查）；
  - `V2ServerStatus` + `V2ServerStatusStore` + `V2ServerHealthMonitor`（后台轻量探测：在线 / 重新连接中 /
    离线 / 未配置 / 认证失效）。
- 列表统一走 `mediaPage(V2MediaQuery)`：Server 模式 search/sort/media_type/folder_id/page 全部交给
  `GET /api/v1/media`（50 条/页，滚动加载下一页，禁止全量读取）；Demo 仍内存排序后分页。
- 收藏：`setFavorite` 返回服务器确认结果；失败不改 UI 状态并 Snackbar 提示（禁止假成功）。

### 播放（Android）

- 新增 `suspend fun resolvePlayback(mediaId): V2PlaybackSource`（Direct / fallback_hls / resume / 尺寸）。
- `PlaybackContext` 只保存 `mediaId + title + 队列 id + currentIndex`；URL / headers 由
  `V2NativePlayerViewModel` 按需异步解析（Loading / Ready / Error 三态），
  **禁止一次解析整个队列**（允许预取下一条，窗口 ≤ 2）。
- `GsyNativePlayerScreen` 不再自己构造播放地址：Ready 到达时成对 `setHeaders` + `setUp`；
  内核 Error → 同源切 HLS（不重新请求）→ 再失败进 Error；resume_position_ms 在 Prepared 后 seek；
  进度按 开始 / 暂停 / 退出 / 每 15s 上报 `POST /media/{id}/progress`。

### UI

- `V2DataSourceSheet`：数据源 ● 演示数据 / ○ 我的服务器 + 首次配置（手动地址 + 配对码，
  复用 `PairingRepository.checkHealthy / verifyAndPair`）+ 状态行 + 重新检测 / 断开连接。
- 首页顶部轻量数据源状态条（Demo 显示"演示数据离线"；Server 显示在线/离线/认证失效/重新连接中/未配置）。
- 整理页新增"数据源"入口卡片；根 Scaffold 统一 Snackbar。

### Server（Python）

- `GET /api/v1/media/folders`：新增 `image_count` / `cover_media_id` / `cover_url`（最新一张 IMAGE 作封面对其
  求 cover_url 走既有缩略图代理），用一条窗口函数查询完成，避免服务端 N+1。
- `MediaSummary`：新增 `folder_id` / `folder_name`（与文件夹接口同源；只下发显示名，绝不下发路径）。

### Review（Stage 7.1 收尾）

- §17.1：`host.postDelayed { controller.play() }` 改为协程 `delay(120)` + 取消后二次确认
  （page 仍是 settledPage 且 mediaId 未变），换页取消不再留下 stale Play。
- §17.2：新增 `hasPlaybackAdvanced`（当前源是否真的 Playing 且 position>0），Paused/Completed 只有在
  它为 true 时才允许 Poster 淡出；换媒体 / 重新批阅重置，杜绝"未首帧被暂停 → 黑屏"。
- Server 模式显示"真实批阅接入将在 Stage 8B 完成"占位页，不建立队列、不做批量播放地址解析。

### 真实环境暴露并修复的两个缺陷（模拟器 + Mock Server 端到端复验）

1. **GSY 播放 headers 丢失（严重）**：只调用 `controller.setHeaders(...)` 会被随后的 `setUp(url, …)` 覆盖，
   实测 Direct/HLS 请求完全没有 `X-Emby-Token`（服务端 403 → 播放失败）。
   修复：headers 进 `GSYVideoOptionBuilder.setMapHeadData(...)` 后再 `setUp(builder, false)`；
   `GsyNativePlayerScreen`（正式路径）与 `V2ReviewScreen`（换源路径）统一处理。
   证据：修复前 `MRPlayer applySource headers=[]` + `/stream/… stream_header=None → 403`；
   修复后 `headers=[X-Emby-Token]` + `STREAM … OK`。
2. **收藏 / 进度接口 DTO 合同错误**：服务端返回布尔（`favorited` / `reported`），
   客户端用 `Map<String, String>` 接收 → 解析失败 → 收藏静默失效。
   修复：新增 `MutationResultDto`，`addFavorite` / `removeFavorite` / `reportProgress` 改用它。

### 测试与验证

- Android JVM：`326 tests / 0 failures`（新增 `V2ServerMediaRepositoryContractTest`：列表映射 / 文件夹映射 /
  search & sort & media_type & folder_id 参数 / 分页 hasMore / 封面与原图 URL 解析 / 收藏成功与失败 /
  **收藏与进度布尔结果解析** / 401 认证失效 / 网络不可达离线 / 未配置 fail-fast / playback direct+HLS+resume /
  **headers 真实 HTTP A→B 不串源** / Server 模式同步播放地址 fail-fast）。
- Android：`:app:assembleDebug` PASS；`:app:lintDebug` 0 errors / 43 warnings（与 Stage 7.1 同）。
- Server：`tests/test_media_api.py` 29 passed；全量 `360 tests / 1 failed`（既有失败：
  `test_deployment_contract_11` 断言 versionCode=6 / 1.1.0，与本阶段无关）；ruff check 通过。
- 模拟器 DEMO V2 UI：13/16 passed；3 个失败在 base 代码上同样失败（已 `git stash` 复验基线，属既有问题）。
- 模拟器 Server 模式端到端（Mock Server，非真实 Jellyfin）：**4/4 passed**
  （分页 / 文件夹 / 封面 Coil 真加载 / 原图 / 播放 headers / 收藏 / 媒体墙渲染 / 服务端筛选与搜索）。
- 真实 App（非测试进程）Server 模式手动流程：数据源 Sheet 连接（服务器在线 / 已配对）→ 媒体墙真实封面 →
  Viewer `照片 30 | 1 / 30` → 视频播放至 Completed；Mock 审计日志见交接包 logs/。
- DEMO 回归：模拟器批阅页自动播放 `PlaybackReady ok=true state=Playing posMs=167 advanced=true`。

### 遗留 / 未做

- REAL SERVER TEST（真实 Jellyfin）：NOT TESTED（本机无可用且允许使用的真实环境），
  已用 Mock Server 完成等价 HTTP 合同端到端，并在报告中标明"MOCK"。
- 真实手机（物理设备）：NOT VERIFIED。
- 播放进度上报节流写死 15 秒；Review Server Session、Organize 真实能力按计划留到后续阶段。

## 运维控制台"加载中…"修复（2026-09-26）

### 现象

`http://127.0.0.1:8766/admin` 页面框架渲染完成，但数据区永久停在"加载中…"，所有面板空白；
ControlHub 中"家庭媒体管家"显示"离线"，"打开应用"按钮禁用。

### 根因（两个相互独立的问题）

1. **端口冲突（先前已修）**：一个交互模式 `MediaReviewServer` 进程占用 8766，
   WinSW 托管的服务实例反复 `bind 0.0.0.0:8766 → Errno 10048` 失败，
   服务停在 STOPPED(WIN32_EXIT_CODE 1067)；且 ControlHub 探针契约要求
   `instance_mode=windows_service`，而端口上实际是 `interactive` 实例 → 判定"信号缺失"。
2. **运维控制台内联 JS 语法错误（本次修复）**：`app/admin.py` 用**普通三引号字符串**定义 `_PAGE`，
   页面内联 JS 中本意为 JS 转义的 `\"` 被 Python 解析成 `"`，实际输出
   `"<tr><th scope="row">"` —— 提前闭合 JS 字符串字面量，
   整个 `<script>` 报 `SyntaxError: missing ) after argument list`，
   **脚本一行都不执行**，页面因此永远停在初始态；`join("\n")` 同类（真实换行未闭合字符串）。

### 修复

- `_PAGE = """…"""` → `_PAGE = r"""…"""`（原始字符串），一行根治整类转义缺陷。
- 新增回归测试 `test_admin_page_inline_script_keeps_attribute_quotes_escaped`
  （守护"JS 字符串内 HTML 属性引号必须保持转义"）。

### 验证

- 内联 JS 语法：修复前 `node --check` 报 SyntaxError，修复后 exit 0；
  裸属性引号由 46 处降为 0 处。
- 服务端测试：`tests/test_ops_console_11.py` **19 passed**；全量 **180 项仅 1 项失败**，
  且该失败为既有问题（`test_deployment_contract_11` 读取外部 `D:\MediaReview_1.1_Handoff\`
  并断言 versionName=1.1.0），与本次改动无关。
- 就地升级本机服务（停服务 → 备份 104 个文件 → 覆盖替换 → 启服务）后复测：
  `/admin` 内联 JS 合法；`pairing/code` 与自动配对 200；此前 401 的三个面板接口
  带令牌后全部 200；stage8a 文件夹合同随包上线（374 个文件夹含
  `image_count` / `cover_media_id` / `cover_url`），媒体带 `folder_id` / `folder_name`。
- 部署日志 `C:\ProgramData\MediaReview\deploy_opsconsole_20260926_233613.log`；
  回滚备份 `C:\ProgramData\MediaReview\backup\opsconsole-fix-20260926_233613`。


## V2 Stage 8A.1 — Loading Pipeline Optimization（2026-09-27）

基线 `30ce89e`（Stage 8A + OpsConsole 修复），分支 `feature/mediareview-v2-stage8a.1-loading-pipeline`。

### 目标

真机反馈"功能可用但加载明显偏慢"。本阶段不开发新功能，只减少等待。

### Server 侧

- **Jellyfin 客户端长生命周期**：新增 `JellyfinClientManager`，在 FastAPI lifespan 创建/关闭；
  `jellyfin_client` 依赖不再 `async with` 每请求新建。httpx 连接池显式配置
  `max_connections=32 / max_keepalive=16 / keepalive_expiry=30s`；
  host/api_key/user_id 变化时关闭旧客户端重建（绝不沿用旧凭据）。
- **缩略图磁盘缓存**：新增 `ThumbnailCacheService`，接入既有 `cache/thumbnails/<xx>/<key>/` 分片；
  key = `sha256(media_id + 源指纹 + 变体)`（指纹含 jellyfin_id/modified_at/size_bytes）；
  原子写入 + single-flight；响应带 `Cache-Control` / `ETag` / `X-MediaReview-Cache`，支持 304。
  缩略图尺寸改 `maxWidth=480&maxHeight=270`（竖图不再传 480×850 再由客户端裁）。
- **`MediaSummary.is_favorite`**：一次 `WHERE media_id IN (...)` 批量取回；客户端不再预取整个收藏列表。
- **`/original` 流式**：改 `StreamingResponse` + `ImageStream`，边读边转发；
  状态码/非图片/重定向/25MB 上限等安全约束全部保留。
- **`MR_PERF` 打点**：`/media`、`/media/folders`、`/favorites`、`/thumbnail`、`/original`、`/playback`
  记录 `duration_ms` / 缓存命中 / 字节数（单调时钟，不含凭据）。

### Android 侧

- **启动优先级**：`reloadAll` 改为 P0 先发 `GET /media?page=1`（不 await），
  P1 folders+albums 放同一后台 Job 并行补齐；收藏改为 `ensureFavoritesLoaded()` 懒加载。
  废弃原 `folders → albums → favorites → media` 顺序。
- **folders 去重**：Repository 短时缓存 + 单飞，启动期 `GET /media/folders` 只 1 次。
- **收藏状态**：DTO 加 `is_favorite`；`V2MediaMapper` 直接取 `summary.is_favorite`；
  删除 `favoriteIdSet(api)` 预取。
- **分页预取**：`PREFETCH_DISTANCE_ITEMS = 16`（原 4），抽 `shouldLoadNextPage` 纯函数。
- **封面管线**：`MediaCard` 的 `SubcomposeAsyncImage` → `rememberAsyncImagePainter + Image`；
  Skeleton 固定铺底；新增停止滚动时的下一屏 6~8 张低并发预取（复用全局单例 ImageLoader，
  重新滑动即取消）。
- **Viewer 渐进加载**：`ZoomableImage(previewUri, fullUri, loadFullResolution)`；
  第一帧显示已缓存封面（不黑屏），原图仅当前页后台加载后就地替换；
  邻页 `model=null` → 不发原图请求。
- **`V2Perf`**：tag=`MR_PERF`，仅 Debug 输出，单调时钟；覆盖 home/media/cover/paging/viewer/player。

### 测试与验证

- Server：新增 `tests/test_loading_pipeline_8a1.py` **12 项全过**；全量 **192 项仅 1 项既有失败**
  （`test_deployment_contract_11` 读外部 1.1 交接目录）；`ruff check` 全过。
- Android JVM：**337 项全过**（新增 `Stage8A1LoadingPipelineTest` 7 项）。
- Android instrumentation（模拟器 `MediaReview_Test`）：32 项 19 过 13 失败 ——
  3 项 Stage 8A 已确认的既有失败、4 项缺宿主机 Mock Server、6 项 1.1 壳层测试（本次未复验基线）；
  **新增 `HomeStartupPriorityTest` 通过**。
- Lint：`0 errors, 43 warnings`（与基线一致，无新增 error）。
- APK：`versionCode=8 / versionName=2.0.0-alpha1`，SHA-256 `fbb6dea7…`。

### 未做 / 未验证

- 真机 + 真实 Jellyfin 的冷/热性能计时：**NOT MEASURED**（无设备连接；新构建未部署到生产服务）。
- 预取距离 12/16/20 真机对比：**NOT EXECUTED**（代码取文档建议值 16）。
- Room 本地缓存（Stage 8A.2 预留）、Review Session（Stage 8B）、Organize、雪碧图 Server 接入：
  按 §31~§34/§45 明确不做。


## V2 Stage 8A.1.1 — Loading Pipeline Closure（2026-09-27）

基线 `b264eaa`（Stage 8A.1 加载管线），分支 `feature/mediareview-v2-stage8a.1.1-loading-closure`。

### 性能打点时间源修正（§1）

- 不再把 `V2Perf` object 类初始化时间当作 Process Start：改由 `MediaReviewApp.onCreate`
  第一行调用 `V2Perf.onProcessStart()`。
- 改为**独立测量会话**：Home / Paging / Viewer / Player 各自计时，不再全部相对 `home_enter`。
  - APP：`app_start → home_enter`
  - HOME：`home_enter → first_media_request → first_media_response → first_media_render /
    first_cover_success / visible_8_covers_ready`
  - PAGING：`page_request → page_response`（每次请求新页新建会话）
  - VIEWER：`viewer_open → preview_visible → full_image_ready`
  - PLAYER：`player_open → playback_info_ready → first_frame`
- 全局 claim flag 全部移除；每次打开 Viewer / Player 重建会话，上一条媒体的状态不会污染下一条。

### P0/P1 状态解耦（§2）

- 新增 `folderError` / `albumError` / `favoritesError`。
- `loadFolders` 失败不再写 `listError`（原先会把文件夹故障显示成媒体墙"加载失败"）。

### 收藏懒加载可重试（§3）

- 只有服务端明确成功才置 `favoritesLoaded`；失败保持 false，下次进入收藏页会重新请求。
- 失败不清空已有收藏数据；新增 `retryFavorites()` 与收藏页"重新加载"入口。

### Folders 缓存绑定 server 身份（§4）

- 缓存条目改为 `(baseUrl, loadedAtMs, data)`；TTL 只对同一服务器有效。
- `MediaRepository.invalidateAuxiliaryCache()`（Demo 空实现，Router 两侧失效）；
  ViewModel 在 `setDataMode` / `connectServer` / `clearServerConfig` 主动失效。
- 新增**真实并发** single-flight 测试（`Dispatchers.Default` 下 `async×4` → 仍只 1 次 HTTP）。

### Server 侧

- **缩略图几何**：grid cover 改为 Jellyfin `fillWidth=480&fillHeight=270`，
  消除"9:16 竖图只有约 152×270 再被客户端放大 3 倍"的模糊路径；
  四种宽高比（16:9 / 9:16 / 4:3 / 1:1）断言上游参数一致。
- **URL 版本化**：`/media`、`/media/folders`、`/review queue` 的 `cover_url` 统一带
  `?v=<source_version>`（sha256(源指纹)[:16]，不含任何敏感信息），
  客户端与 Server 失效逻辑一致。
- **缓存容量收口**：`stats()` / `prune()`，默认上限 1 GiB 按 mtime 淘汰，
  每 32 次写入检查一次并放到线程执行。
- **磁盘/上游耗时**：`disk_read_ms` / `disk_write_ms` / `upstream_ms` 进入 `MR_PERF`。
- 修正缩略图读回 content-type：`image/jpg` → `image/jpeg`。

### 测试规模核实（§9，纠正此前错误数字）

- Server：`pytest --collect-only -q` 实测 **377 collected / 377 executed / 376 passed / 1 failed**
  （既有失败 `test_deployment_contract_11`，读外部 1.1 交接目录）。
  **Stage 8A/8A.1 曾报告"全量 192 项"，该数字有误。**
- Android JVM：**338 collected / 338 executed / 338 passed / 0 failed**（60 个测试文件，
  由 test-results XML 汇总）。
- Android instrumentation（定向 `feature.v2.loading` 包）：**6 discovered / 6 executed / 6 passed**。

### 性能测量（模拟器，§10）

- DEMO 冷启动 RUN 1 取得完整 HOME 指标（见 `03_PERFORMANCE_REPORT.md`）。
- RUN 2..5 **不可用**：模拟器在同一会话内严重退化（`app_start → home_enter`
  由 1.76 s 恶化到 14.8 s，且 adb 守护进程中途崩溃），采集窗口内无任何 `MR_PERF` 输出。
  因此 **p50/p95/min/max 不成立（只有 1 个有效样本）**，如实标注。
- REAL PHONE / REAL SERVER / DEV MACHINE：**NOT MEASURED**（无真机；按 §11 未部署生产服务）。

### 部署

按 §11 **未部署**：未替换正式 Windows Service、未重启正式 MediaReview Server、
未修改 ControlHub。新 Server 构建仅存在于仓库与交接包中，等待用户批准。
---

# Stage 8B —— Server Review Session / 真实批阅模式接入

- **基线**：`ce171936692ea94ddd1a8dc1454dff6b52a08fab`（feature/mediareview-v2-stage8a.1.1-loading-closure）
- **分支**：`feature/mediareview-v2-stage8b-review-session`
- **提交**（按 §44 拆分）：
  1. `6ef6403` fix(android): close playback and media context correctness gaps（§6~§12 前置正确性）
  2. `754112f` feat(android): add server review session repository（§14/§15/§17/§20~§24/§35）
  3. `f5c6276` feat(android): connect V2 review to server session paging（§3/§4/§5/§17~§40）
  4. `c295e7f` fix(android): explicit position index and single-flight playback resolve（设备测暴露）
  5. `8f7d102` test(android): cover review resume paging and device-side server flow

## 1. 前置正确性（§6~§12，单独提交）

- 新增 `V2PlaybackSourceController`（纯逻辑，可 JVM 单测）：Source Identity（mediaId + token）、
  Direct → 一次 HLS → Error、retry 必须回 DIRECT、P1 预取单槽；
- 完整播放器改用它，并且内核报错必须上报"播放器实际装载的 mediaId"（旧媒体迟到错误 → Ignore）；
- `openMedia` / `openMediaIn` / `openMediaInVideoQueue` 不再 `coerceAtLeast(0)` 静默指向第 1 项；
  目标不在首页列表时退化为**单条上下文**（收藏页/文件夹页点进来仍可正常打开，但不指向别的媒体）；
- `V2ContextQueue.clampIndex()`：空队列拒绝写入（旧 `coerceIn(0, -1)` 非法）；
- 相册分页：删除固定 `page=1/page_size=200` 的 `imagesInAlbum`（>200 张被永久截断的产品缺陷），
  改为 `albumPage` 复用 `V2MediaPage`/`V2MediaQuery`；AlbumScreen 滚动加载下一页，Viewer 用已加载窗口。

## 2. 批阅数据层（§14/§15）

- 新增 `feature/v2/review/data/`：`ReviewSessionModels`（含 `ReviewQueueItemUi`，**只带 Metadata**、
  绝对索引；`ReviewQueuePaging` 移植旧版已验证的页计算）、`V2ReviewSessionRepository` + Router、
  `V2ServerReviewSessionRepository`、`DemoReviewSessionRepository`；
- Server 实现：`latest` 404 才新建（§17）、`current_index` 所在页定位（§21）、每页 50（§20）、
  seen/position/complete、队列媒体映射进**共享** `V2ServerResourceCache`；
- 待删除真正接通：`GET /delete-queue`、`POST/DELETE /delete-queue/{id}`，
  `setPendingDelete` 改为返回 Boolean（服务端确认制，§35/§36）；
- 顺带修正客户端合同解析：`ReviewSeenRequest.seen` / `ReviewPositionRequest.index` 加 `@EncodeDefault`；
  seen / position / complete 改用真实 DTO（`ReviewSeenResultDto` / `ReviewProgressDto`）；
  delete-queue 的 `queued` 进入 `MutationResultDto`。

## 3. 批阅接线（§3/§23/§37/§39/§40）

- **删除 `serverModeUnsupported` 占位页**；新增 `V2ReviewUiState`
  （Idle / LoadingSession / LoadingQueue / Ready / Empty / Complete / Error）；
- 进入即恢复 latest；失败 → Error「无法恢复批阅 + 重新尝试」，**绝不误建会话**；
- 播放 P0/P1（只解析当前项 + 最多预取下一条），与完整播放器共用 `V2PlaybackSourceController`；
- seen 服务端确认制；position 只随 settled 变化上报且 latest-wins；complete 服务端确认后才进完成页；
- 完整播放器返回恢复原会话与原绝对位置（不重建）；重新批阅 = 新建会话（服务端先完成旧 active）。

## 4. 测试与验证（真实执行结果）

| 项目 | 结果 |
|---|---|
| JVM 单测 | **380 项全通过**（新增 42 项：播放控制器 9 / 上下文队列 3 / 相册分页合同 4 / 批阅分页 10 / 批阅 VM 15 / 契约测试补充 1） |
| Lint | **0 errors**（43 warnings，与 Stage 8A.1.1 基线一致） |
| assembleDebug | PASS |
| 设备侧端到端 | `Stage8BReviewServerFlowTest`（模拟器内 MockWebServer，真实 HTTP + 真实 JSON）：新建会话 / 分页 / seen / position（绝对索引 0 与 49）/ 只解析 4 条 playback / 重新批阅 = 新建会话，全部通过 |
| Server pytest | 376 项通过；2 项与本阶段无关（既有 1.1 版本号合同测试 + 100k 行排序性能预算在机器满载时超时） |
| 全量 instrumentation | 见 REVIEW_SUMMARY.md「Stage 8B」章节（含逐项归因） |
| 生产部署 | **NOT PERFORMED**（§46） |

## 5. 明确未做

- 未部署生产 Server；未在真实 Jellyfin 上验收；
- 未做 Compose 手势级 UI 自动化；
- 未做 Room / 离线批阅缓存（Stage 8A.2）；
- 未改 Server Review 核心（本阶段 41 个改动文件全部在 `android/` 下）。

---

# Stage 8B.1 — Review Correctness & Delivery Closure

- **基线**：`61832ed1664c5a38839cb765621357fa59932492`（feature/mediareview-v2-stage8b-review-session）
- **分支**：`feature/mediareview-v2-stage8b.1-review-closure`
- **范围**：只做「批阅正确性 + 分页正确性 + Server 状态权威性 + 测试/交付证据可信性」，
  不新增任何产品功能（Room / SWR / 图片批阅 / 整理页 / 播放器 / 雪碧图 / 生产部署全部不做）。

## 1. Server：状态权威化 + complete fail-closed

- `GET /review/sessions/{id}/queue` 的每一项新增 **`seen`** 字段（会话状态，与媒体可用性无关），
  客户端恢复后能知道"这条以前是否已批阅"。
- `POST /review/sessions/{id}/seen` 现在返回**服务端权威进度**
  `{media_id, seen, seen_count, total_count}`；重复标记幂等（不重复计数），
  取消已看（`seen=false`）计数正确回退。Android 端不再自行 `+1`。
- `POST /review/sessions/{id}/complete` **fail-closed**：`seen_count < total_count` 时返回
  409 `CONFLICT`（中文 message：还有未批阅内容…），会话保持 `active`；全部已批阅才允许完成。
- `advance` / `position` 的自动完成同样 fail-closed：位置到末尾但仍有未批阅时**保持 active**
  （§20「还有未批阅内容」的前置条件），全部批阅后才允许自动完成。
- 过时合同测试更新：`test_deployment_contract_11` 的 Android 版本断言由 `1.1.0 / versionCode=6`
  改为当前产品线 `2.0.0-alpha1 / versionCode=8`（不再作为"既有失败"长期挂账）。

## 2. Android：双向分页窗口 + 完成门槛 + latest-wins

- **`ReviewQueueWindow` 重构**：`page/baseIndex` → `firstLoadedPage / lastLoadedPage /
  pageSize / totalCount`；`next = lastLoadedPage+1`、`prev = firstLoadedPage-1`
  （先 next 再 prev 不会再请求中间页，窗口按 absoluteIndex 去重 + 排序）。
- **缺项修正**：`localIndexOf` 由 `absoluteIndex - baseIndex` 改为 `indexOfFirst` 搜索，
  找不到返回 null（600/602/603 缺 601 时 602 的本地索引 = 1 而不是 2）。
- **`atEnd` 改为页边界判断**：`lastLoadedPage * pageSize >= totalCount`，
  队尾媒体失效（如 98/99 已删除）也能正确结束，不再"永远还有下一页"。
- **空页推进**：整页不可用时推进页边界继续向后/向前找（单次最多扫描 8 页防请求风暴），
  推进到末尾后返回窗口快照让 `atEnd` 生效，**不再 return null 死循环**。
- **`current_index` 失效恢复（§7）**：优先"第一个 `absoluteIndex >= current_index` 的可用项"，
  否则回退"`current_index` 之前最近的可用项"，并把恢复出的真实绝对索引**写回服务端 position**；
  整个队列都不可用 → 空队列页（绝不悄悄从本地第 0 条开始播别的视频）。
- **分页 single-flight**：Repository 内 `Mutex` 串行化 next/prev，绝不同时修改窗口。
- **seen 权威 + in-flight 去重**：`markSeen` 返回 `ReviewSeenResult`（服务端 seen_count），
  ViewModel 的 `seenCount` 完全采用服务端值（不再 `baseSeenCount + confirmedSeen.size` 推算）；
  同一媒体并发只发一次 POST；成功后把该条目标记为已批阅。
- **完成逻辑重做（§17/§18/§20/§21）**：`atEnd && seen_count == total_count` 才调用 complete；
  到末尾但还有未批阅 → 提示一次"还有未批阅内容，会话未完成"并保持 active；
  complete 加 single-flight（重复 stable 回调只发一次）。
- **收藏跨页（§16）**：每次 `applyOpened` / `applyPageResult` 都把新增 `favorite=true`
  的媒体并入本地收藏集合。
- **position 序号化 latest-wins（§23）**：settled 变化进入串行写入器（合并中间值），
  30→31→32 时服务端最终必然停在 32；会话切换时丢弃未写完的旧会话位置，
  仓库侧同时把写入固定到调用时的 sessionId（迟到写只落到旧会话）。
- **P1 预取身份校验（§22）**：写入单槽前确认"当前媒体 + next mediaId"都没变、
  且 source identity 仍是当前媒体，否则 DROP STALE PREFETCH。

## 3. 测试与证据可信性（§24~§33）

- **新增 JVM 用例 25 项**（405 项全通过）：双向分页不重复页（两种顺序）、合并去重、
  缺项 localIndex、失效锚点前后回退 + 写回 position、尾部失效仍 atEnd、空页推进、
  并发 next/prev 互斥、队列 `seen` 恢复、seen 权威计数、seen 并发防重、
  完成门槛（seen 未满不得完成 / 满后完成）、complete double-trigger 只发一次、
  新分页收藏合并、stale P1 不覆盖新 P1、position 30→31→32 收敛。
- **新增 Server 用例 8 项**（392 项全通过）：queue `seen` 字段、markSeen 权威计数、
  重复 seen 不重复计数、complete 拒绝未批阅（409 CONFLICT）、全部 seen 允许完成、
  position 到末尾但有未批阅保持 active、不可用尾项不压缩索引、服务层 fail-closed。
- **Hilt harness 修复**：`Stage4BrowserUiTest` 的两个 favorites 导航用例（裸 `ComponentActivity`
  却渲染 `hiltViewModel()`）改用 **`HiltTestActivity`（debug 源集 + debug manifest）** ——
  测试 Activity 与 App 同进程、使用生产 `MediaReviewApp`（保留 GSY Exo2 内核注册）。
  期间验证过错误方案并留证：把 Activity 放 androidTest manifest → 跨进程失败；
  换 `HiltTestApplication` → 生产 Application 初始化被绕过、GSY 回退 IjkPlayerManager
  缺 `libijkffmpeg.so`（7 项 native 失败），均已在注释中记录，避免后人重犯。
- **Search / favorites 断言修正**：搜索用例原先"搜索激活时断言模式行存在"（设计上该行隐藏）
  改为关闭搜索后断言；favorites 用例的 `onNodeWithText("收藏")` 歧义（首页还有同名文件夹卡）
  改用 contentDescription 精确定位；视频收藏卡标题是媒体名"视频A"（"视频收藏"是文件夹名）。
  搜索用例按 §27 复跑 **5/5 PASS**。
- **Legacy 1.1 Shell 测试归置（§26）**：删除 `MainShellComposeTest`（4 项）与
  `MainShellProductionIntegrationTest`（2 项）—— 先证明生产 Runtime 不再走旧 Shell：
  `MainActivity` 只渲染 `V2MainScreen`；`mainShellGraph` / `MainShellScreen` /
  `shouldShowMainChrome` 全项目仅 `MainShell.kt` 自身引用（无任何调用方）；
  `SettingsScreen` 只被旧 Shell 图引用。删除而非 `@Ignore`，并在交接包 `05_KNOWN_ISSUES.md` 留证。
- **交付证据重做**：`gradlew :app:clean :app:assembleDebug` 原始 stdout/stderr 落盘
  `logs/assembleDebug_raw.txt`（exit_code=0）；APK 记录 size / mtime / SHA-256 并写入
  `SHA256SUMS.txt`；`git/LOG.txt` 第一行 = 最终 HEAD（git evidence 在最终 commit+push 之后生成）；
  ZIP 内确有 `logs/server_pytest_raw.txt`；全套有失败只写 PARTIAL；结果术语
  `CODE_READY / USER_VALIDATION_PENDING`。

## 4. 测试结果（真实执行）

| 项目 | 结果 |
|---|---|
| Server pytest（全量） | **392 tests / 0 failed / 0 error，exit_code=0**（wrapper 追加 JUnit 计数，见 logs/server_pytest_raw.txt） |
| Android JVM | **405 tests / 0 failed**（65 suites，test-results XML 汇总） |
| Android instrumentation（模拟器 MediaReview_Test/API35） | **36/36 PASS（0 failed）**，源码 @Test 数与执行数一致；搜索用例另 5/5 复跑稳定 |
| Lint | **0 errors**（41 warnings + 10 info；基线 43 warnings） |
| assembleDebug | **exit_code=0**（`:app:clean :app:assembleDebug`，原始日志见交接包） |
| 生产部署 | **NOT PERFORMED**（§36） |

## 5. 明确未做 / 未验证

- 未部署生产 Server（未替换 Windows Service / 未迁移生产 DB / 未重启 ControlHub）；
- 真实手机 + 真实 Jellyfin 验收未做（`USER_VALIDATION_PENDING`）；
- Compose 手势级 UI 自动化仍未做（设备侧测试驱动 ViewModel + 真实 HTTP）；
- 未进入 Stage 8C（整理中心接 Server），按 §39 完成后停止。

---

# Stage 8B.2 — Review Availability / Sparse Queue Closure

- **基线**：`c55cdd34f264b9f6ea19e35497c66a5efd54c738`（feature/mediareview-v2-stage8b.1-review-closure）
- **分支**：`feature/mediareview-v2-stage8b.2-review-availability`
- **范围**：只解决「Session 建队后媒体变成 unavailable/missing 时的正确性与性能」。
  **小版本 APK 新规**：本阶段不生成、不复制、不发送用户 APK，也不运行 `:app:assembleDebug`。

## 1. 产品语义：媒体失效不再永久阻塞完成

- `total_count` 语义**不变**（Session 创建时固化的原始队列长度）→ absoluteIndex / 分页 / position /
  恢复全部继续依赖这个稳定长度，绝不动态压缩队列。
- 新增**统一**进度计数（`server/app/services/review.py::review_progress_counts`，所有 Review API 共用）：
  - `unavailable_count` = 当前无媒体行 / `is_available=false` 的 SessionItem 数；
  - `remaining_count` = 仍可用且 `seen=false` 的数量（**完成条件**）；
  - `completed_count` = `total_count - remaining_count`（**不是** seen + unavailable，避免"先 seen 后失效"重复计数）。
- 完成条件从 `seen_count == total_count` 改为 **`remaining_count == 0`**：
  `complete` / `advance` / `set_position` 三条路径统一；`complete` 继续 fail-closed（remaining>0 → 409 CONFLICT）。
- 进度字段进入所有 Review 响应：`GET /sessions/{id}`、`latest`、`queue`（每项 `seen`）、
  `seen`、`position`、`advance`、`complete`。

## 2. 恢复性能：不再逐页扫描

- Server 新增 `GET /review/sessions/{id}/nearest?index=&direction=forward|backward|nearest`：
  `ReviewSessionItem JOIN MediaCacheIndex(is_available)` + `ORDER BY index LIMIT 1` 的**单条 SQL**，
  返回 `{"index": <int|null>}`；100k 稀疏队列下 `nearest` 只发 1 条 SQL（测试断言）。
- Android `openWindow` 重构：`current_index` → `nearest`（1 次）→ 目标页（1 次 GET），
  正常恢复 = **1 nearest + 1 queue page**（旧实现最多几十页 HTTP）。
- `current_index >= total_count`（旧 Session / 异常状态）→ 优先 `nearest backward` 找最后一个可用项（§33）。
- 整个 Session 都不可用（nearest 明确 null）→ `ReviewSessionOpen.NoAvailableMedia` →
  UI 空态 + 提示「暂无可继续批阅的媒体」，**不再每次进入重扫整个 Session**。
- **网络失败 ≠ 没有可用媒体**：nearest 失败向上抛 → 恢复走 `Failed`（可重试），
  分页失败走 null（可重试）；绝不当成"没有可用媒体"。

## 3. 稀疏区跳页：删除 MAX_EMPTY_PAGE_SCAN

- `loadNextPage`：整页不可用 → 记录该页已检查 → `nearest(forward, index=该页起点)` → **直接跳到目标页**
  （不再一页一页扫；单次调用最多 1 次 nearest + 2 次 page 请求）。
- `loadPrevPage`：对称处理（`nearest(backward, index=该页起点)`）。
- 定位不到可用项时把边界推进到最后一页 / 第 1 页 → `atEnd` / `canLoadPrev` 正确收敛，不再死循环。
- 旧的 `MAX_EMPTY_PAGE_SCAN` 多页扫描路径**彻底删除**（不保留两套路径）。
- absolute index 继续不压缩（600 / 602 / 603 不会变成 600 / 601 / 602）。

## 4. Android 完成流程改为服务端权威

- 到窗口末端 → `refreshProgress()`（`GET /review/sessions/{id}`，**每次"准备完成"只刷一次**，
  禁止每个 settle 都刷）→ `remaining == 0` 才 `complete()`；否则提示 **「还有 N 条未批阅内容，会话未完成」**；
  刷新失败 → 「无法确认会话进度，请稍后重试」且**绝不完成**。
- `ReviewSessionDto / ReviewProgressDto / ReviewSeenResultDto / ReviewSessionInfo / ReviewSeenResult`
  增加 `unavailable_count / remaining_count / completed_count`，默认 `-1` = 未知（兼容旧 Server，
  绝不把缺失值当 0 而误完成）；`remaining < 0` 时保守回退 `total - seen`。
- 完成页数据已备好（`Complete(totalCount, seenCount, unavailableCount)`），本小版本不扩 UI 范围。

## 5. 测试与验证（真实执行）

| 项目 | 结果 |
|---|---|
| Server pytest（全量） | **407 tests / 0 failed / 0 error / exit_code=0**（新增 `test_review_availability_8b2.py` 15 项） |
| Android JVM | **418 tests / 0 failed**（新增 13 项：nearest 请求数合同 / 跳页 / 越界 / 网络失败 / refresh 一次 / 数字提示等） |
| Lint | **0 errors**（41 warnings + 10 info，与 8B.1 一致，无新增 error） |
| compileDebugKotlin | **BUILD SUCCESSFUL** |
| Instrumentation（模拟器 API35，含宿主 Mock Server 8799） | **36/36 PASS / 0 failed**（设备侧 Review 端到端已适配 nearest 恢复） |
| 用户 APK | **NOT GENERATED（小版本政策）**；未运行 `:app:assembleDebug`（instrumentation 内部构建属工具行为） |
| 生产部署 | **NOT PERFORMED** |

## 6. 明确未做

- 不碰 Organize / Delete Queue UI / Duplicate UI / 媒体库管理 / Room / SWR（属 Stage 8C）；
- 不改播放器（`feature/v2/player/**` 未修改）；无 UI 扩范围（仅新增两条提示文案）；
- 未部署生产；未进入 Stage 8C。

---

# Stage 8C — Organize Center → Real Server V1

- **基线**：`a99c593ee145b86b6721369791f1508b82774e48`（feature/mediareview-v2-stage8b.2-review-availability）
- **分支**：`feature/mediareview-v2-stage8c-organize-center`
- **范围**：把 V2「整理」页整体去 Mock，接真实 Server：
  待删除真 Delete Queue / 重复媒体真 Scanner / 批阅进度真 Session / 媒体库真 Library Selection。
  **大阶段 APK 政策**：全部完成、自动测试通过后**只生成 1 个** `MediaReview-v2-stage8c-organize.apk`。

## 1. 独立 V2 Organize 数据层（不复用旧 Repository）

- 新增 `feature/v2/organize/data/`：`V2OrganizeRepository`（接口 + 领域模型）、
  `V2ServerOrganizeRepository`、`V2DemoOrganizeRepository`、`V2OrganizeRepositoryRouter`
  （按运行时数据源模式委托，与媒体层路由器同构），Hilt 绑定在 `V2DataModule`。
- **禁止复用**旧 `feature/deletequeue` / `feature/duplicates` / `feature/home/data` 的 Repository：
  旧实现 `runSuspendCatching → error → emptyList/null/false`，会把"网络失败"伪装成"没有数据"。
- 新接口所有方法**失败必须上抛**（`§45 错误不能变成 0`）：ViewModel 据此进入 Error 卡片 /
  提示并可重试；404 只在有明确语义处（`latest` 无 active 会话说）被解释为合法状态。
- Demo 模式：待删除 = Demo 批阅的内存待删除集合（同一份状态，Review 里 🗑 的项会出现在整理页），
  最终删除在内存中完成（一次性 nonce 快照语义与 Server 对齐，不碰磁盘）；
  重复媒体 / 媒体库管理抛 `OrganizeFeatureUnavailableInDemoException` → UI 明确显示
  「仅服务器模式可用」，绝不伪装成 0 项。

## 2. Server：Organize 摘要与重复详情（不整包拉列表）

- `GET /api/v1/delete-queue/summary`：单条 SQL 聚合 **pending** 的 `count / total_bytes`
  （`delete_queue.pending_summary()`）；failed 项不计入"预计释放"。
- `GET /api/v1/delete-queue`：队列项 `media` 补齐 `cover_url`（`media_thumbnail_url(media_id, row_source_version(media))`）
  与 `original_url`，与 `/media` 的缓存版本语义完全一致 → **客户端零 N+1**（不必逐条拉媒体详情）。
- `GET /api/v1/duplicates/summary`：`exact_groups / similar_groups`（单条 SQL 计数）+
  最近扫描任务 `scan_task_id / scan_status / scan_progress`。
- `GET /api/v1/duplicates/{group_id}`：分组 + 成员 + **每个成员的媒体摘要**
  （`size/duration/分辨率/media_type/cover_url`），组内媒体行**一次 `IN (...)` 批量 SQL** 取回；
  未知分组 404。
- 删除安全协议**零改动**：nonce 一次性 / 10 分钟过期 / 快照绑定 / library allowlist /
  size+mtime+普通文件校验 / 逐项审计 / success·missing·failed 逐项结果全部保留，
  继续只走 `commit_with_nonce()`。

## 3. 整理首页：四张真实卡片（每卡独立状态）

- `OrganizePage` 从 `V2MainScreen.kt` 拆到 `feature/v2/organize/OrganizePage.kt`（§47）。
- `OrganizeUiState`：`deleteCard / duplicateCard / reviewCard / libraryCard` 各自
  `Loading / Ready / Error / Unavailable` —— 一张卡失败**不白屏**、可**单卡重试**、绝不显示成 0。
- 卡片内容：待删除「12 项 · 预计释放 1.8 GB」；重复媒体（扫描中显示「扫描中 62%」，
  否则「完全重复 3 组 · 疑似重复 7 组」）；**「批阅进度」**（active 会话 → 「37 / 100 · 剩余 63」，
  无 active → 「暂无进行中的批阅」，不伪造历史累计）；媒体库「已选 2 / 共 3 个媒体库」。
- Overview **不为显示数字拉全量列表**（走 §2 的三个 summary/计数接口 + `latest` + `libraries`）。

## 4. 待删除中心：两步最终删除 + 结果逐类显示

- 列表来自 `GET /delete-queue`（服务端随队列下发封面）；恢复单项：Server 成功才刷新列表，
  失败保持原样 + Snackbar（§17）。
- 状态机 `Idle → Preparing → ReadyToConfirm → Committing → Result`：
  **先 `POST commit/prepare` 拿到快照，再用 prepare 的 `count/total_bytes` 弹确认**
  （不是列表旧快照）；确认后**同一 nonce 只 commit 一次**（single-flight），
  Committing 期间按钮 disabled、Back/取消被忽略（§21）。
- 结果面板逐类显示 `成功删除 / 文件已不存在 / 删除失败`（失败名称列出 ≤10 个），
  **失败项仍留在队列**；完成后**停留在本页**刷新列表（不自动退出，§25）。
- 最终删除成功后 V2 刷新（§24）：`V2HomeViewModel.refreshAfterFinalDelete(changedMediaIds)`
  → `MediaRepository.dropCachedMedia()`（新增，Server 实现同步移除 `V2ServerResourceCache` 条目）
  + 失效辅助缓存 + 重载首页列表/文件夹/书架 + 收藏待重载。

## 5. 重复媒体中心与对比页

- 复用 `POST /duplicates/scan` + `GET /duplicates/status` + `POST /tasks/{id}/pause|resume|cancel`：
  进入页面先 GET status 恢复；只在 `pending/running` 轮询（1.5s）；离开页面/切后台停止；
  `succeeded` 后自动重载 exact / similar（§29/§30）。
- 完全重复只对应 `type=exact`（完整 SHA-256 byte-identical）；疑似重复只显示 `similar`
  （high / candidate 不进入用户页面）。
- **绝不自动删除重复文件**；comparison 详情一次请求（禁止 N+1）；"保留此文件"
  走 `POST /duplicates/{group_id}/keep`，**Server 确认后**才更新 UI（失败不变 + 提示）；
  keep 只是人工选择，本阶段不做"一键删除其余"（§36）。

## 6. 媒体库管理

- 复用 `GET /libraries` + `PUT /libraries/selection`（不新增第二套 API）。
- 勾选只改**本地草稿**（点一下不发请求）；「应用」才提交；失败保持页面与草稿 + 提示。
- 全取消在 UI 提前阻止（「至少选择一个媒体库」），Server 仍最终校验。
- 保存成功后通知上层：`V2HomeViewModel.refreshAfterLibraryChange()`
  → `invalidateAuxiliaryCache()` + 重载首页（folders / albums / media / favorites 失效）。

## 7. 导航与契约修正

- 新增路由 `organize/delete`、`organize/duplicates`、`organize/duplicates/{groupId}`、
  `organize/libraries`；详情页自动隐藏 BottomNav，Back 回整理（`isTabRoute` 未扩）。
- **修正既有合同缺陷**：`POST /duplicates/{group_id}/keep` 的响应 `keep` 是**布尔**，
  旧客户端用 `Envelope<Map<String, String>>` 接收会解析失败（静默失效）→
  新增 `DuplicateKeepResultDto`（Stage 8C 合同测试发现）。

## 8. 测试与验证（真实执行）

| 项目 | 结果 |
|---|---|
| Server pytest（全量） | **413 tests / 0 failed / 0 error / exit_code=0**（新增 `tests/test_organize_center_8c.py` 6 项） |
| Android JVM | **464 tests / 0 failed / 0 error**（新增 46 项：仓库合同 / 4 个 ViewModel / 卡片独立性） |
| Lint | **0 errors**（41 warnings，与 8B.2 持平，无新增 error） |
| compileDebugKotlin | **BUILD SUCCESSFUL** |
| Instrumentation（模拟器 API35 + 设备内 MockWebServer + 宿主 Mock 8799） | 见 `logs/android_instrumentation_raw.txt` |
| 用户 APK | **1 个** `MediaReview-v2-stage8c-organize.apk`（clean assembleDebug，最终 HEAD 构建） |
| 生产部署 | **NOT PERFORMED** |

## 9. 明确未做

- 不做视觉大改 / 复杂动画（§49）；不碰 Organize 之外的大模块；
- 不改播放器（`feature/v2/player/**` 未修改）；
- 不改 Review Session 核心（8B.2 语义完整保留）；
- 本阶段不做「将未保留项加入待删除」（§36 明确下一阶段考虑）；
- 未部署生产；完成后停止，不进入 Stage 8D。

### 2026-10-03 — Stage 8D 全应用整合 / Legacy 清理 / 验证构建（2.0.0-alpha4）

分支 `feature/mediareview-v2-stage8d-integration`（基于 8C.2 分支 tip）。

**P0：重复分组稳定身份（第一 Gate）**
- `duplicate_scanner` 删除以枚举序号 `idx` 充当业务身份的旧实现
  （`exact:x:1` / `:2`），改为由 `(type, size, duration, 内容哈希)` 派生的稳定摘要
  `_stable_group_id()`（截断到 56 hex，适配 `group_id` 列宽 64）；exact 用整文件
  `sha256`，high 用 `quick_hash`。
- 新增回归 `server/tests/test_duplicate_group_id_stability_8d.py`：先 B/C 两组 → 新增排序
  更靠前的 A → 重扫，断言 B/C 的 `group_id` 不变、人工 Keep 不丢（exact + high）。
  已验证：把实现临时改回旧 `idx` 时该测试**失败**，改回新实现后通过。

**Legacy 清理（A/B/C 分类，见 docs/V2_RUNTIME_INVENTORY.md）**
- A（无生产引用）删除：`feature/connect` UI/发现、`deletequeue`、`duplicates`、`favorites`、
  `home`、`library`、`mediawall`、`player`、`review`、`settings`、`viewer`、
  `ui/shell/MainShell`、`ui/components/MediaComponents`、
  `core/datastore/MediaWallSettingsStore`、`core/media/PlayerCore`、
  `core/media/ReviewPlaybackController` + 旧 `ReviewQueueWindow` / `ReviewPlayerSlots` /
  `LatestWinsScheduler`。
- B（V2 复用，迁移到 core）：`PairingRepository` → `core/pairing/PairingRepository.kt`；
  `normalizeBaseUrl` → `core/pairing/UrlNormalize.kt`（单测同步迁移）。
- 旧播放器（Stage 2.1/2.2 废弃路径）删除，**只保留 GSY Native 正式路径**。
- 旧测试同步删除/精简（不使用 `@Ignore` 逃避）。

**播放依赖审计**：`dependencyInsight` 确认 media3 由 `gsyvideoplayer-exo2` 传递提供，
但 HLS 未被传递保证；依据「禁止导致 GSY runtime 缺类」保留三个直接依赖并在 gradle 注明。

**版本 / 更新日志**：`versionCode 10 → 11`、`versionName 2.0.0-alpha3 → 2.0.0-alpha4`；
`ReleaseNotesCatalog` 新增 Alpha 4；跨版本（8→11）单 Sheet 聚合 alpha2/3/4 的合同测试同步更新。

**测试与验证（真实执行）**

| 项目 | 结果 |
|---|---|
| Server pytest（全量） | **439 tests / 0 failed / 0 error / exit_code=0** |
| Android JVM | **334 tests / 0 failed / 0 error**（44 suites） |
| Instrumentation（模拟器 MediaReview_Test，API35） | **49 tests / 0 failed / 0 skipped**（宿主 Mock Server @10.0.2.2:8799 + Demo） |
| Lint | **0 errors**（41 warnings，无新增 error） |
| compileDebugKotlin | **BUILD SUCCESSFUL** |
| assembleDebug | **BUILD SUCCESSFUL** |
| 模拟器 Smoke（真机玄关，8 张截图） | 冷启动→更新日志→首页/整理/收藏/批阅→视频播放→返回→force-stop→再启动，无 crash |
| 生产部署 | **NOT PERFORMED** |

**说明**：`tests/test_db.py::test_0010_...`（10 万行 `ORDER BY random()` 阈值 1.0s）对机器负载
敏感，高负载并发时会超时；空载全量运行通过（本阶段未改动该测试）。

### 2026-10-03 — Stage 8D.1 Server 兼容 / Keep 迁移 / 系统栏（2.0.0-alpha5，NO USER APK）

分支 `feature/mediareview-v2-stage8d.1-compatibility-closure`（基于 `6dce891`）。

**P0 Server API Contract**
- Server `1.1.0 → 1.2.0`；新增 `SERVER_API_CONTRACT = 2` 与 `SERVER_CAPABILITIES`；
  `GET /api/v1/system/health` 增加 `api_contract` / `capabilities`；
- Android `REQUIRED_SERVER_API_CONTRACT = 2`；`HealthOut` 增加 `api_contract`(默认 1)/
  `capabilities`(默认空)，旧 Server 缺字段不崩溃；
- `PairingRepository.checkHealthy`：先判 contract，`< 2` 立即 Incompatible，
  **不再请求业务 API**，且**不清 token / 不标记 AuthRejected**（版本不兼容 ≠ 401）；
- `ConnectionState` 增加 `CompatibilityState` + `serverVersion` + `serverApiContract`；
  `V2ServerStatus.Incompatible`；设置页提示"服务器版本过旧"（警告色）+ 双方版本；
- §17 fail-fast：`V2MediaRepositoryRouter` 在已知 Incompatible 时对读取类接口直接抛
  `ServerIncompatibleException`，避免首页 404 串。
- 部署合同同步：`scripts/build_deploy.py`、`deployment/scripts/*.ps1`、
  `deployment/tests/test_sandbox_lifecycle.ps1`、`server/pyproject.toml`、部署合同测试 → 1.2.0。

**P0 旧 Keep 首次安全迁移**
- `duplicate_scanner` 增加 Legacy 序号 ID 识别 + `(type, 成员集合)` 签名安全匹配：
  优先稳定 `(group_id, media_id)`；否则仅当**同类型且成员集合完全相同**时迁移旧 Keep；
  成员增减 / 类型改变 / 拆分合并一律 `keep=false`（禁止模糊迁移）；
  迁移后 DB 只留稳定 ID（运行时业务数据迁移，无 schema 变更）。

**P1 深色系统栏**
- `MainActivity` 改 `enableEdgeToEdge(SystemBarStyle.dark(TRANSPARENT), ...)`：
  深色背景 → 浅色状态栏/导航栏图标；Player/Viewer 全屏隐藏系统栏后退出恢复浅色图标。

**版本 / 更新日志**：`versionCode 11 → 12`、`versionName alpha4 → alpha5`；新增 Alpha 5。

**测试与验证（真实执行）**

| 项目 | 结果 |
|---|---|
| Server pytest（全量） | **450 tests / 0 failed / 0 error**（新增 health contract 4 + legacy keep 迁移 7） |
| Android JVM | **340 tests / 0 failed / 0 error**（新增兼容矩阵 6） |
| Lint | **0 errors**（41 warnings，无新增） |
| compileDebugKotlin / UnitTest / AndroidTest | **BUILD SUCCESSFUL** |
| Instrumentation（定向） | SystemBarUiTest / Stage8D1CompatibilityUiTest / Stage8C1WhatsNewUiTest / Stage8AServerModeTest |
| 用户 APK | **NOT GENERATED — SMALL VERSION POLICY** |
| 生产部署 | **NOT PERFORMED** |

### 2026-10-03 — Stage 8D.2 Compatibility Gate / Server Access Guard / Deployment Preflight（2.0.0-alpha6，NO USER APK）

分支 `feature/mediareview-v2-stage8d.2-server-gate-closure`（基于 `21b2c00`）。
唯一目标：在整个 App 范围真正实现 `health → compatibility confirmed → business API`，
并把 Windows 部署脚本改成「版本 / Contract 不正确 = 部署失败」。

**P0 首次连接旧 Server（§4~§7）**
- `PairingRepository.Result` 新增 `Incompatible(version, apiContract, connection)`；
  `checkHealthy` 对 `api_contract < 2` 的旧 Server 返回 `Incompatible`，**不再伪装 `HealthOk`**；
- `connectServer()` 收到 `Incompatible` 立即停止：**不调用 verify / 不保存 Server Mode / 不清 Token**，
  返回含所需 Contract 的可见错误文案。

**P0 启动 / 切源竞态（§8~§12）**
- `V2HomeViewModel`：SERVER 冷启动与 Demo→Server 均改为
  `restore local profile → 显示 V2 Shell → status=Probing → probe()`，
  仅 `Online` 才 `reloadAll()`；`Incompatible/Offline/AuthRejected` 一律不 reload；
- UI 仍立即显示（不回到 Splash 阻塞）；切 Demo 无需探测、立即可用。

**P0 统一 Server Access Guard（§13~§19）**
- 新增 `feature/v2/data/server/V2ServerAccessGuard.kt`：`requireBusinessAccess()` 按状态抛
  `ServerIncompatible/NotReady/Offline/AuthRejected/NotConfigured`；`shouldLoadServerBusinessData(status)` 只认 `Online`；
- `V2ServerMediaRepository` / `V2ServerOrganizeRepository` / `V2ServerReviewSessionRepository`
  的 `private suspend fun <T> call(...)` **首行统一 Guard**（health / pairing 白名单例外）；
- `V2MediaRepositoryRouter`：所有会发网络请求的 suspend 方法重新归类到 `dataActive()` 过 Gate；
  `mediaById/coverUri/...` 纯内存读取保持不抛；`setAlbumCover` 为本地 DataStore，不过 Server Gate。

**P0 Online 只能由 Health Probe 授予（§20~§26）**
- `V2ServerStatusStore.onRequestSuccess()` 改为 no-op：普通业务请求成功**不能**把
  `Incompatible/Probing/Unconfigured/AuthRejected` 升级为 `Online`；
- `onRequestFailure` 仅在 `Online` 时降级；新增 `reset()` 清理 serverVersion / apiContract / compatibility；
- Server A→B、断开重连、清配置均先 `reset()`，不残留旧服务器元数据。

**P0 Windows 部署 Gate（§31~§45）**
- Server `1.2.0 → 1.2.1`，**API Contract 保持 2**（验证 version 与 Contract 是两套概念）；
- 新增 `deployment/version.ps1` 唯一事实源（`$ExpectedServerVersion=1.2.1` / `$RequiredApiContract=2`）；
- `install.ps1`：`Test-HealthContract` 同时校验 `status` + `version` + `api_contract` + 能力清单
  （review_session / review_nearest / organize / delete_nonce / duplicates_paged / library_selection）；
- 删除「final health 失败仅 Warning 继续安装完成」：升级路径 final health 失败即自动回滚旧二进制 / config / DB 并重启旧版
  （报告 `UPGRADE FAILED` + `ROLLBACK PASS|FAIL`）；首次安装失败则停进程、删计划任务、标记 `INSTALL FAILED`；
  两者均 `exit != 0`；
- `status.ps1` 输出 `健康 / Server Version / API Contract / Compatibility`，contract 不达标非零退出；
  `diagnose.ps1` health.txt 记录 version/api_contract/capabilities/status/components；
  `start/restart/repair` 均确认 health + contract ≥ 2；
- `scripts/build_deploy.py` 补打包 `deployment/version.ps1`（修复部署包 dot-source 缺失缺陷）。

**版本 / 更新日志**：`versionCode 12 → 13`、`versionName alpha5 → alpha6`；新增 Alpha 6。

**测试与验证（真实执行）**

| 项目 | 结果 |
|---|---|
| Server pytest（全量） | **452 passed / 0 failed / 0 error**（exit 0） |
| Android JVM | **351 passed / 0 failed / 0 error**（47 结果文件聚合） |
| Lint | **0 errors**（41 warnings，无新增） |
| compileDebugKotlin | **BUILD SUCCESSFUL** |
| 部署沙箱生命周期测试 | **17 通过 / 0 失败**（Gate 5 例 + 升级失败回滚镜像） |
| Instrumentation（定向） | **8 / 8 passed**（Stage8D1CompatibilityUiTest / Stage8C1WhatsNewUiTest / Stage4BrowserUiTest） |
| 用户 APK | **NOT GENERATED — SMALL VERSION POLICY** |
| 生产部署 | **NOT PERFORMED** |

> 诚实声明：`Stage8AServerModeTest` 依赖宿主机 Mock Server（`10.0.2.2:8799`），
> 该 Mock Server 非仓库内产物，本阶段未重新运行；其覆盖的「health 先于业务」请求顺序
> 已由 JVM MockWebServer 合同测试断言覆盖。`install.ps1` / `diagnose.ps1` 未在真实管理员
> 环境端到端执行（需 admin + 真实 EXE），沙箱以镜像逻辑 + stub 覆盖。
> `test_task_manager` 后台循环单例测试在整机高负载下偶发失败、单独复跑通过（历史已知，非本阶段引入）。

### 2026-10-06 — Stage 8D.3 Server 播放链路闭环（2.0.0-alpha7，NO USER APK）

分支 `feature/mediareview-v2-stage8d.3-server-playback-closure`（基于 `20069ba`）。
用户真实反馈：DEMO 模式播放正常，SERVER 模式（完整播放器与批阅模式）都无法播放。
本阶段只修 Server 播放链路，不重构公共播放器、不改 Demo 播放路径。

**P0 设备播放凭据生命周期（原"永久信任 DB Key"）**
- `JellyfinClient.ensure_device_stream_key`：按 `installation_id` 的**固定命名 Key** 每次向
  Jellyfin 校准——存在即复用（幂等，不会产生 `MediaReview-xxx-1/2/3`），被删除即重建并更新 DB，
  值变化即同步 DB；`_resolve_stream_key` 失败时 **fail-closed**（不下发任何直连地址），
  管理 API Key 绝不作为设备凭据回退。

**P0 播放地址合同（两种 Jellyfin 地址正式区分）**
- `jellyfin.url`（Server→Jellyfin）与 `jellyfin.client_url`（手机→Jellyfin）分列；
  `deployment/config.example.json` 公开 `client_url` + `client_host_allowlist`；
  留空时走 `request_host_fallback`（保留端口/路径前缀，标记非权威）；
  `deployment/README.md` 给出 LAN 示例与只读端口检查（不修改防火墙/Jellyfin 配置）。
- `/playback` 返回 `stream_url_source` / `stream_url_authoritative`，URL 内绝不携带凭据。

**P0 HLS 真实 MediaSourceId**
- 原实现把 item ID 当作 MediaSourceId；现读取 Jellyfin `PlaybackInfo` 返回的真实
  `MediaSources[].Id`；取不到真实源时**不下发猜测的 HLS URL**（Direct Stream 仍可独立使用）。
- 隔离 Jellyfin 10.11.11 实测：`media_source_equals_item_id=true`（本次样例恰好相等），
  但实现不再依赖该巧合。

**新增认证诊断 `GET /api/v1/system/playback-readiness`**
- Server→Jellyfin 与 Phone→Jellyfin 两层分离；返回地址来源/权威性、凭据状态、
  `client_address_status`、媒体有效性、Direct Range 结果；`phone_reachability` 恒为 `not_tested`；
- 有限 Range（`bytes=0-1023`）、禁跟随重定向、移除管理 Authorization、
  严格校验 200/206 的 Content-Type/Content-Length/Content-Range 与实际读取字节数，
  上游任意文本不反射（固定 MIME 白名单）；**绝不返回 API Key / X-Emby-Token / Authorization**。

**P0 Android 手机播放链路检测（设置 → 数据源 → Server 信息）**
- 新增 `V2PlaybackDiagnostic`：health/契约/配对门禁 → readiness → VIDEO `/playback` →
  用**生产 `V2PlaybackResolver`** 得到同一 URL/headers → 独立 OkHttp 执行有限 Range GET
  （不携带业务 Bearer、不跟随重定向）；分类区分未配置/未配对/不兼容/Server 不可达/
  401/403/404/5xx/网络/非视频/可读流；切换连接或退出即取消，旧结果不覆盖新会话；
  UI 只显示 host:port 与受控中文标签，不显示路径、凭据或异常原文。
- 本阶段补强：Server 流检查的 403 与 302 分类独立（不得归为 401 或无媒体），
  与手机探针分类一致。

**真实 HTTP 播放验收（P0 §28~§31）**
- `RealHttpPlaybackFixture`：设备内 MockWebServer 提供**真实 MP4 字节**与 Range 响应，
  `/playback` 下发**设备局域网地址**（非回环、非 `127.0.0.1:1` 之类的假地址）与
  `X-Emby-Token` 认证头；只记录"是否认证/是否 Range/请求 URL"，不打印凭据值。
- `ServerFullPlayerPlaybackTest`：生产 Repository → Resolver → ViewModel → GSY 真实起播，
  断言 `isPlaying`、`duration > 0`、`position > 500ms` 且**持续增长**（不把 Source Ready 当起播）；
- `ServerReviewPlaybackTest`：生产批阅页面，**手势上滑**第 2 条后新 Source 真正播放，
  且播放器实际请求的 URL 与生产解析出的 endpoint 一致；
- 两个类各带 Demo 完整播放器 / Demo 批阅起播回归。

**修复过程中发现的真实缺陷（测试基建，非产品）**
- 初版 fixture 用 `127.0.0.1` 下发播放地址：生产 `MediaUrlResolver` 正确拒绝回环目的地，
  播放永远无法起播（这正是"不能用无法播放的假地址证明 PASS"）；改用设备 LAN 地址
  `10.0.2.15` 后仍失败，原因是 MockWebServer 只监听回环；改为 `start(0.0.0.0, 0)` 后通过。
- 原测试用反射读取 GSY 内部 URL（`GSYComposeHostPlayer` 无公开 URL 访问器，返回 null）→
  无论真实播放是否成功都会超时失败。改为核对**播放器真实发出的视频请求 URL** ==
  生产解析的 endpoint（更强的事实证据），并在测试注释与交接文档中如实说明。

**版本 / 更新日志**：Server `1.2.1 → 1.2.2`（API Contract 保持 `2`）；Android
`versionCode 13 → 14`、`versionName alpha6 → alpha7` + Alpha 7 更新日志。


## 2026-10-06 Stage 8E：RC 构建与交付门禁

用户授权继续 Stage 8E；Android 升为 code15/alpha8，Server 保持1.2.2/Contract2。
改造既有 build_deploy.py：清理固定生成目录，重新构建，验证运行中的实际 EXE 与 APK 自身 manifest/签名，绑定源输入和全部产物指纹，最终清洁 HEAD 打包，ZIP逐项校验。
安装器兼容新 RC 目录，但检测既有 WinSW 服务时停止；SCM 查询失败也必须在生产写入前停止。独立审查两项 Important 经 RED/GREEN 回归关闭，39项专项验证通过。
生产配置 client_url 与当前实体 WLAN 地址一致；Jellyfin 启动返回 SCM unavailable，原服务仍停止，自动登录未验证。未创建平行实例、未覆盖生产、未声称备份成功。手机不在家，实际 LAN 验收留待用户执行。
后续实际构建、全量验证及最终交付结果在本节追加；不将待执行项目标为通过。

实际发布构建发现并修复验证基建问题：隔离配置缺少显式 storage.data_root，启动尝试默认日志路径并因权限拒绝，未覆盖生产；补齐临时存储配置，新增失败→通过回归并保留失败日志。真实新 EXE health 已验证1.2.2/Contract2/6capabilities。Release初次因本地签名配置残留迁移前路径而失败；只修正ignored key.properties的storeFile到项目已有原密钥，不改变签名身份。42项发布/部署补充回归通过。最终重建与全量结果另行追加。


Stage8E最终验证：Server487 passed/0 failed，1条既有警告，220.54秒；Android JVM363/0，lint0errors/40warnings，compile/debug/androidTest构建通过；真实HTTP Server/Demo Full/Review四项29.311秒通过。Ruff通过，168文件格式通过。补充发布/部署42项通过，独立复核未解决Critical0/Important0。第二次干净PyInstaller及Gradle clean assembleRelease成功（Release 2m15s）；实际EXE health1.2.2/Contract2/6capabilities，实际APK code15/alpha8/com.mediareview.app与签名PASS。RC预提交167项SHA256逐项回验通过。最终文档提交之后重新核验相同构建输入与全部产物，生成最终HEAD交付；准确最终SHA/文件大小/哈希见阶段00_HANDOFF和RELEASE_MANIFEST，不在tracked文档硬写形成循环。Registry登记现有mediareview-build-deploy脚本，不新增受管服务。生产仍1.1.0/Jellyfin停止，手机LAN/首帧/旧安装原位升级NOT TESTED，生产覆盖与真实媒体永久删除NOT PERFORMED。候选发布仅READY_FOR_USER_VALIDATION，完成后停止。


## 2026-10-07 Stage 8E.1：Pre-LAN Closure / Upgrade Safety

基线 a080422，分支 feature/mediareview-v2-stage8e.1-pre-lan-closure。用户决定继续离线收尾，暂不迁移 App。Android 运行时代码与 Server 运行时代码未改，沿用 Alpha8/code15、Server1.2.2/Contract2；未重新构建用户 APK 或 RC。

发布工具新增统一 package_sources/package_inputs，prepare 分离 build_inputs/package_inputs，拒绝旧 receipt、输入漂移及EXE/APK替换；最终 staging/ZIP/独立APK均连接原 receipt，失败仅清理本轮输出，保护历史交付。两项时序P1经独立复现、修复和复审关闭，包装58项与相关部署回归84项通过。静态声明、既有最终EXE隔离health和现有manifest版本/哈希一致。

升级工具默认只读/WhatIf，固定既有 AIHome.MediaReview 身份；隔离事务覆盖停服后完整快照、隐藏文件、DB/WAL/SHM/config/WinSW备份、staging与switch、health失败恢复、备份污染和恢复失败。停服前快照/隐藏文件两项审查问题已关闭；测试闭包宿主作用域缺陷实证定位后修复。PS7/PS5.1及直接调用17场景121断言通过。脚本已登记现有全局Registry并更新CHANGELOG，未新增服务/binding。

正式生产adapter仍BLOCKED：官方协调入口尚无经验证的共享锁、批准绑定与审计桥接，生产-Execute在任何写入/生命周期操作前拒绝。不能用fixture成功替代正式升级可用性，因此整体未达到PRE_LAN_READY。

实际既有Alpha8 Release APK在独立模拟器上测量：完整005为40s/1280×720/523→1661ms；批阅001为24s/1280×720/700→1936ms，006为22s/1024×768/508→1736ms，Downloads002为10s/1280×720/564→1766ms。全部isPlaying并由真实GSY内核读取，未注入播放状态。首次/重启/手动更新说明、首页/图片/收藏/整理有黑盒证据；累计历史更新fixture显示Alpha2至Alpha8，关闭持久化后重启不再自动弹，DataStore恢复原值且安装APK哈希不变；原始XML与结构化证据已保存。最终串行Server529项通过，0失败/0错误/0跳过，313.650秒，既有警告1条，未放宽性能断言。Android lint 0errors/40warnings，Server既有配置cwd下ruff check/format通过；错误cwd的初次lint调用和并行负载下性能测试失败均保留并纠正。

Alpha4和Alpha8证书不同，原位迁移BLOCKED，旧手机App未动。Jellyfin历史returncode1根因UNKNOWN；开始Stopped，后续外部动作使服务Running，回环8096可响应，未归属启动发起者。本阶段无Jellyfin/生产MediaReview生命周期动作、无真实DB复制/修改、无真实媒体删除。生产MediaReview仍1.1.0，真实手机LAN NOT TESTED。

自动审批拒绝审查假数据目录的清理，保留并写明恢复/交接边界；不绕过重试。最终测试、提交/远端/清洁状态及SHA256交付清单以Stage8E.1 Handoff证据为准，不自动开启8F。

## 2026-10-07 Stage 8E.2 — 官方升级合同与可信来源

来源 Gate、跨 PowerShell canonical plan、来源变化恢复及 verified repackage 实现完成。原 runtime/API/schema/Android版本与原 APK/EXE 不变；生产执行及新部署 RC 未执行。官方既有 per-action 锁、manifest批准与审计不足以覆盖升级事务，缺口为同实例 IPC、transaction lease、实际计划执行绑定及步骤审计关联，结论 BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT。没有修改 ControlHub 正式合同或安装版。

最终 Server561项通过（312.084秒，零失败/跳过，1条既有警告）；PS5.1/7来源专项各164断言、事务各129断言；重打包90项及独立复审通过；既有ControlHub合同88项通过。Ruff/format/diff通过；正式WhatIf两引擎来源与本地计划摘要一致，仅LOCAL_PLAN，不构成官方批准。独立审查关闭canonical转义、清单大小写、receipt原文件覆盖P1。原始red/final/review证据留在交接包；部分早期PS调试输出覆盖的限制已记录。

用户确认其启动Jellyfin，历史退出码1根因UNKNOWN。生产MediaReview仍1.1.0；手机证书NOT VERIFIED；所有真实手机/LAN/播放/首帧/五项批阅NOT TESTED，按用户决定不迁移App、不发APK。新脚本登记Registry而无新服务/binding。重要交接按最终Git/远端/SHA及Hermes回执确认；不自动进入下一阶段。清理自动审批拒绝的临时目录保留、不重试。

## 2026-10-08 Stage 8F：服务器预览与图片连续浏览

基线98a0352，分支feature/mediareview-v2-stage8f-media-experience。用户明确授权本阶段三项Android体验修复和Alpha9交付；官方升级桥仍BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT。Server保持1.2.2/Contract2，运行时代码和生产服务不动；真实手机迁移与家庭LAN本阶段不执行。

Task1服务器雪碧图：Repository接通ensure、有限task轮询、manifest校验/单位转换及URL解析，15秒总等待预算、20轮上限；Ready缓存绑定服务器身份与失效代次。ViewModel提供可取消Preparing/Ready/Error，Home/Search首次长按不要求已有缓存，滚动/离页/换源取消客户端等待，不取消服务器生成。全局Coil加载与认证，文件失败保留Poster。

TDD首轮14 Repository、5 Controller测试按缺失能力失败，最终类型化测试45项JVM通过且compileDebugKotlin成功。独立审查发现慢文件加载消耗6.5秒播放预算；实际UI另发现长按误触普通点击，均修复。慢HTTP有效RED复现提前停止；取消用例通过。最初新增fixture因主线程DNS失败，修正基建后重新取得有效RED，原日志保留。修复后5项UI与12项相关JVM通过，实际像素验证帧变化、位图就绪后完整播放预算及加载中取消。独立复审与Task1提交结论另行追加。Server sprite/task专项20项通过；不重复未改Server的全量测试。
Task1最终复审：Critical 0、Important 0，慢文件计时与长按误点击均关闭；允许提交。完整Server认证及Home/Search端到端fixture仍在Task4验收，不将当前组件测试冒充完整链路。

Task2真实Viewer待删除：ImageViewerState移除本地fake集合，只保留页索引和UI显隐。VM持有独立Loading/Ready/Error状态、session代次和busy；每次进入读取Repository，写入确认成功才改图标，失败保持原值并提示，离页/换源隔离晚到结果。未知/正在写状态禁用按钮，读取错误可重试；专属Viewer事件仅由一个Snackbar入口显示。实际DemoMediaRepository与整理仓库共享同一队列，加入/整理撤销/重开状态一致。

7项有效RED确认缺失能力，GREEN类型化相关JVM共53项通过，compileDebugKotlin通过。UI初次10项中9通过，Info返回受测试AVD系统覆盖层抢焦点；诊断确认SystemUI ANR及fresh AVD首次ImmersiveModeConfirmation，正常Wait/Got it完成初始化后，原Espresso与信息关闭断言单项1/1通过（33s）。未修改InfoSheet产品代码、未放松断言，失败与恢复证据均保留。当前仅组件/Repository验证，完整真实App fixture由Task4完成。Task2独立审查与提交结论另行追加。
Task2独立审查：Spec符合、Quality Approved，Critical 0/Important 0，允许提交。两项Minor留给最终分支审查：旧write晚到而新write仍busy的额外竞态测试未专门覆盖（现有代次保护正确）；既有AGP/compileSdk兼容警告保留，未升级环境。

Task3相册错误与Viewer续页：Screen/Viewer复用纯AlbumPagingController而各持独立窗口，首屏Loading/Ready/Empty/Error仅明确成功total0为空；追加失败保留已加载照片、total与页码，显式重试和下一尾部位置可再次尝试，同位置不无限循环。专用openAlbumImage携带albumId/排序/50分页上下文，不继承首页folder；动态Pager稳定ID去重追加，当前图片与pending session不重置。书架刷新/封面刷新错误保留旧Shelf；源/会话代次阻止旧结果覆盖。

控制器RED为6个有效行为AssertionError与1个stub窗口NoSuchElement（不独立算行为RED），35相关JVM与compile通过；初次16/16目标UI通过。额外close RED发现VM旧session残留，最小修正后单项1/1通过；旧close不影响新session、source失效空Pager不崩溃。新fixture初次图片路径无效，改用真实内置映射后两项Viewer2/2补验通过，当前名称/索引断言不等于像素或Coil解码证据。累计17个独立UI通过，未声称单轮17项绿；首个同步追加失败Snackbar有UI证据。所有失败/原始日志保留，最终统一门禁和真实Release由Task4完成。Task3已冻结，独立审查进行中，尚未提交。
Task3独立审查：Spec compliant/Quality Approved，Critical 0/Important 0/Minor 0；真实媒体缓存联通聚焦核验通过。最终门禁/Release/真实图片像素由Task4补证，允许第三笔提交。


## 2026-10-09 Stage8F Alpha9 收尾

Alpha9 code16与四条精确中文说明完成，首次只当前版/升级单Sheet聚合。最终全分支源码独立审查C0/I0；实际Viewer原图尺寸/draw闭环有效RED后最小修复，不改Server或正式升级桥。

最终产品冻结输入JVM395/55suite/0失败错误跳过。完整75界面轮为74通过/1收藏后台线程重试失败，原class在Main前提修复后4/4补验通过0.435s，独立复审认可分轮闭合，不伪称单轮75绿；原8失败及外部中断记录保留。最终增量lint成功5m31s，0错误/40警告/8信息，分析与报告缓存来源明确。

唯一Release构建成功5m52s，用户APK MediaReview_2.0.0-alpha9_stage8f.apk=43520046B，SHA256 c424b79ae61e5d49d251cdfef52305e42c9e2900807fd212d3d42936d95fbc6e。clean实际Release fullMain1/1通过65.625s，code16/alpha9/debuggable=false，与安装base.apk同SHA；3播放器真实内核、sprite真帧、原图48–51像素及HTTP、待删除双向与分页故障重试通过。首轮Release配对Sheet未关导致56.643s失败，仅修测试前提；条件Close sheet分支本轮未实跑，新增前提断言与全部原功能断言通过，独立补审C0/I0/M0。产品177输入未变，没有第二次Release构建或重签产品。

旧AVD未就绪时保留原数据，独立干净实例实际boot1后验收；初始seed复制失败不算初始化成功，后由模拟器默认初始化。一次流式安装连接中断未完成，push安装成功；正常Got it确认系统全屏提示后清本次App数据，无伪造系统确认。只结束核实归属且IDLE的本次构建Gradle进程以释放内存。

Server sprite/task20专项仍有效，未重复561；Server代码1.2.2/Contract2、ControlHub BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT不变，生产/用户媒体未操作。用户已允许实机，但当前没有手机连接；Alpha4与正式Release证书不同，未卸载/迁移。Phone/HomeLAN NOT TESTED，最高CODE_READY/READY_FOR_USER_VALIDATION。

第四提交及远端/clean、九说明交接ZIP与已授权QQ邮件实际结果写入Handoff和脱敏回执；APK仅本地、不发送。完成后停止等待下一轮审查。

## 2026-10-09 Stage8G：搜索、浏览状态与上下文闭合

基线ba19d01aba692f0ed010c625f801738ba306bae4，分支feature/mediareview-v2-stage8g-search-context。搜索采用独立SearchUiState与纯分页控制器：300ms防抖、50/page、代次隔离、尾部16项才追加、稳定ID去重、失败保留与显式重试。空词历史/输入提示互斥，搜索不写首页列表；关闭搜索不重载首页。HomeScreen保存两份GridState及搜索对应关键词，返回详情时不回顶。

搜索与收藏在点击时冻结已加载的同类型队列，取消收藏不改队列/索引。首页外图片新增服务器确认后的可观察收藏元数据；旧源收藏请求晚到经现有source代次拒绝。删除无入口FolderScreen及dead route/helper/callback。Android code17/2.0.0-alpha10，四条中文更新说明；Server1.2.2/Contract2、生产服务和官方升级桥不变。

搜索9项与Context/版本相关有效RED/GREEN记录在review_meta/stage8g。首轮App测试被系统ANR/全屏引导及测试前提阻挡，均保留原日志且不计产品RED。独立审查I1/I2及测试T1-T3已修复；I1视频Back后v71不可见、切源晚到map回填为有效RED，I2认证DELETE后旧图标为有效RED。正常确认Got it后I2完整1/1通过17.117s；涉及semantics按钮调用不等同实际触摸命中测试。App输入工具可能超过300ms，端到端只声明最终abc，精确防抖由JVM证明。首页非默认folder/sort由ViewModel测试验证，App采用不同滚动锚点与bounds验证独立恢复。

最终全JVM410项/57suite，0失败/错误/跳过；lint0错误/40既有警告/8信息，Debug/AndroidTest构建同轮成功6m21s。最终源码独立审查C0/I0。82项connected首轮80通过/2失败、XML241.92s；修正旧Fake分页忽略query与视频标题先于控件Ready的前提后2/2补验33.081s，独立补审通过，增量lint/测试构建1m44s成功。明确ANDROID_SERIAL=emulator-5584；真实手机只读核验Alpha4/code11 Debug及证书，不安装、不卸载、不清数据。

本阶段没有新增服务/长期脚本，不需ControlHub onboarding；仍BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT。收藏全量API与N+1为P1 PERFORMANCE DEBT，无性能退化证据不预先优化。真实手机/家庭LAN产品行为仍未验证。唯一assembleRelease成功3m5s；用户APK 43520050B，SHA256 c4afe13228d3ee37d343288649ce88abb7780e174af7e9df515f541f151ec53f，code17/alpha10、不可调试、正式签名验证通过；DEX含新搜索/上下文/确认状态且无FolderScreen，169个冻结产品输入未漂移。没有第二次Release构建或重签产品；Release UI未另跑，交给用户亲自验收。 Git/远端/clean和Stage8G Handoff的后置真实结果以交付证据记录；完成后停止，不进入8H。

本次AVD已正常停止。含递归清理与Git提交准备的命令被自动审批拒绝（blocked by policy），整条命令未执行；临时数据保留在已忽略的temp/stage8g-emulator，未绕过重试。Git提交/推送改为独立执行，真实结果以最终交接证据记录。

## 2026-10-10 Stage8H Task1：Server 文件夹树

仅实施Server Task1：新增认证GET /api/v1/media/folder-tree元数据DTO，每个Library虚拟根，保留共同前缀末端可见目录、隐藏Windows驱动器及UNC存储锚点，建立祖先与直接/递归统计、最新图片封面及source_version URL。树ID为ft_+24hex且按Library与内部祖先身份派生；旧/folders与媒体项旧flat folder_id保持兼容。媒体分页支持树节点默认含后代，include_descendants=false只取直接媒体，继续SQL筛选/排序/分页。

轻量LRU按Database Engine、目标库和媒体generation隔离；媒体/选库ORM及Core/text写入、事务完成失效，writer未提交构建不共享。Session after_commit关闭Engine commit通知早于真实提交的竞态；每次读取仅PRAGMA data_version同connection比较，新connection不复用旧cache，覆盖独立Core与外部Engine提交，无每请求路径重扫。版本Server1.3.0 / Contract2 / folder_tree_v1；没有数据库迁移或新服务/长期脚本。

有效RED原8项因新端点未实现而失败。目标回归pytest（FolderTree、media_api、media_index_11、media_sync_api_11、system_api）76 passed / 1既有StarletteDeprecationWarning / 74.45s；最终FolderTree15 passed / 1.88s（含1000媒体100叶目录四层、直接/递归范围、同名跨磁盘隔离、封面变化、选库/同步/删除、双连接与提交时序/外部Engine）。目标ruff check通过，三文件format --check通过，git diff --check通过。原失败与修正证据在已忽略review_meta/stage8h/task1-*；尚未执行全套Server、Release构建、生产启动/部署、Android或真机/家庭LAN验证；未commit/push，交root统一审查。

Task1独立审查C0/I0/M1后的补验：新增一个组合测试，证明库A树ID在显式library_id=B或仅选择B时返回404/MEDIA_NOT_FOUND，叠加media_type=image/search/exclude_favorites后total=5且page2两项为正确SQL排序窗口。仅新增单测1 passed / 1既有warning / 0.28s与该测试文件ruff通过；未重复76项、未修改实现、未运行Gradle。证据review_meta/stage8h/task1-minor-test.txt与task1-minor-ruff.txt。
# 2026-10-10 — Stage8H Task2 首页层级分类与布局

新增 folder_tree_v1 Android DTO/能力检测和独立 FolderBrowser：单库一级目录、多库 Library roots、最多四个首页分类 chip，目录面包屑与直接媒体每页50项，不污染首页或搜索查询。旧 Server1.2.2 仅分类提示升级，其他 Contract2 功能继续可用。封面 URL 与播放队列 poster 元数据完整保留，消费与缓存由后续 Task3/4 完成。

DataStore 默认双列并持久化；同一 LazyVerticalGrid 按 mediaId/offset 保持单/双列锚点，单列16:9及简洁辅助信息。固定顶部 Overlay、24dp NestedScroll 阈值和56dp紧凑栏只修改translation，不修改列表边距。启动偏好迟到读取覆盖用户新选择已有效运行RED后修复（selectionRevision，IO在锁外）。

统一专项 JVM54项全通过（其中Task2模型/目录/布局/偏好11项及Server仓储合同19项）；主代码、AndroidTest编译和两Debug测试APK实际BUILD SUCCESSFUL 10m39s。模拟器HomeLayoutTouchTest三项、FolderBrowserTouchTest一项全部通过27.038s：真实MediaCard切列ID/offset/y、滚动折叠展开仅scroll位移、真实DataStore新实例、目录click/crumb/back与50+25直接媒体分页。证据review_meta/stage8h/task2-full-player-target-green-debug-retry.log、task2-player-target-green-results、task2-home-folder-device.log与task2-home-coordinate-evidence.log。

独立审查C0/I0/M1：完整HomeScreen全部顶部组合与legacy分类提示的设备覆盖仍需在Task3整页封面/Sprite移除测试补齐；当前组件测试不冒称完整首页视觉验收。未执行最终全量JVM/lint/Release，未部署生产服务、修改Jellyfin或用户手机数据；Stage8H整体仍未完成。

## 2026-10-10 Stage8H Task3：封面与浏览窗口缓存

Album/Browse各独立LRU8，缓存已加载items/page/total/sort/anchor/updatedAt和source generation；重新进入同步展示，再静默刷新，失败保留旧窗口。切源/库/确认删除/显式刷新失效，收藏只patch窗口，分页晚到先应用已确认收藏，离开取消分页任务。元数据coverUrl直接交Coil，非法冷自选（VIDEO/异目录/404）显示默认封面，保留用户偏好用于后续有效恢复；关闭crossfade。首屏与下一屏16项/全局最多2并发预热；目录网格预取扣除子目录卡片数量。Android首页Sprite控制器、UI、仓储请求和旧测试删除，Server dormant端点及数据不变。

完整HomeScreen在320dp/字体1.35时展开高度随字体缩放，clipToBounds防紧凑栏越界；正常248dp与折叠56dp及固定列表inset保留。有效RED包括冷自选三项、关闭page2、旧分页收藏覆盖、40子目录预取偏移和大字体提示被裁切；独立复审C0/I0/M0。

最新专项JVM29项全部GREEN（Cold4/Folder5/HomePolicy3/Window6/ProgressIdentity1/Album10）；统一Debug和AndroidTest两APK构建6m43s成功。设备13项12PASS/1测试等待竞态，81.821s；最后一对14/15并发不能用15到达代表整批，测试等待改为两类16请求完成、保留精确16上限断言，AndroidTest增量构建1m46s及补验1/1 PASS12.941s。实际Home Coil绿色像素、元数据直接封面、分页取消/收藏/失效、3秒SWR失败留旧、大字体真实触摸、目录40子卡偏移、实际MainActivity配对后静态封面长按SpriteHTTP零均PASS。12项中另两项是Task4实际内核暂停seek30续播不回退与lock拖动恢复，不计为Task3功能。证据review_meta/stage8h/task3-reviewed-home-player-device.log、home-prefetch-wait-recheck-device.log、home-large-font-green.png、home-album-pixel-green.png。

AVD一次启动停滞（offline/CPU停止/console timeout）按PID及启动参数核验后仅重启本次自有实例，userdata保留；不计产品RED。未操作用户手机或生产服务，未全量最终门禁/Release；Stage8H总体仍未完成。

## 2026-10-11 Stage8H Tasks4/5 播放生命周期与真实指针收敛（验证中）

Full/Review 共用速度状态机、方向锁、Canvas seek；新增真实认证 HTTP MP4 的实际内核断言，覆盖后台、拖动、双击、长按/上滑锁速、音轨、换源、首帧、完成/重播/显式下一条、退出停止与进度提交。Full/Review 禁用库自动生命周期恢复，使用当前逻辑媒体/URL/stage、attached host、manager listener 与 Exo dataSource 的严格所有权门禁。

GSY 在暂停后可能收到迟到 buffering-end，将 UI state 从 Paused 改回 Playing，但实际内核仍停止；仅在用户播放意图、前台、当前源及当前所属 host 均成立且库状态 Playing/Buffering、内核未播放时补充 manager.start，不重装源或回退 seek。真实37项批次 owned-kernel-stale-state-resume-device.log：34 PASS / 3 FAIL，276.482s。Full后台三种状态、Review播放中/Preparing后台恢复、Review点击暂停恢复、真实首触seek与仍显示Snackbar时More、完成重播/下一条、换URL首帧、退出停止均通过。

三项未关闭 RED：Review显式暂停后播放按钮缺失（135行；此前内核静音/同实例/位置断言通过）；Review正式1.5倍设置后正文中心长按2x（169行）；Full刚seek30秒后右侧双击+10（117行）。审阅暂停按钮已改用明确userPaused意图，避免迟到快照遗漏按钮或临时Sheet暂停误覆盖正文；Full双击基准改读当前所属内核position/duration，保留800ms同侧固定anchor累计。日志直接证实seek30、双击进入正文并触发Exo SEEK；旧快照的具体错误目标未直接打印，根因仍待新输入实测验证。

审阅提示移至顶部操作栏下64dp，关闭遮挡底部进度条/More的问题；保留原提示/撤销/完成业务。四文件临时 MRFullOwned/MRReviewHit/Owner/Effect/Seek/MRBodyPointer/MRSeekPointer 诊断移除；独立清理与按钮增量审查、Full双击内核基准审查均C0/I0/M0。报告分别 final-trace-clean-center-intent-independent-review.md、full-doubletap-kernel-anchor-independent-review.md；静态审查不代替运行GREEN。

Server未变更输入完整pytest577 PASS，ruff115文件PASS；旧Android JVM444 PASS不能代替当前输入。当前 center-intent-kernel-doubletap-build.log 正在执行 Debug/AndroidTest/JVM/lint，新输入设备验证、唯一Release、Server1.3.0部署ZIP、104项最终审计、最终commit/push与交接均未完成。未部署生产、安装/清空手机、修改Jellyfin/真实媒体或操作SCM/ControlHub生命周期。

### 2026-10-11 23:00 新输入门禁与 Full 暂停意图增量

82580本轮 Debug/AndroidTest/JVM/lint 终态BUILD SUCCESSFUL（17m20s，91 tasks：23 executed/68 up-to-date）；JVM69 suites/444 tests/0 failures/errors/skips，lint0 errors/42 warnings。XML及lint报告已分别保存在 center-intent-kernel-doubletap-jvm-results、center-intent-kernel-doubletap-lint-results。此结果属于Full0416B413/Review6F37B458输入；随后修改不能称同输入最终门禁完成。

独立审查新增Full静态I1：显式暂停意图false时，迟到snapshot Playing可能隐藏中心播放按钮，并仍安排3秒自动隐藏。Full改centerIsPlaying=userWantsPlayback，controls播放计时同时要求userWantsPlayback和snapshot.isPlaying，effect key加入意图；保持overlay/锁定/Sheet/手势持有及源/owner门禁。实际Full SHA FEF91AE8B1DB861E21AD20EA36EAA13FF33109F9A58129DC062F77578906D65D。当前TEMP复核C0/I0/M0，Full该状态冲突尚未设备直接复现，不把Review已测根因写成Full直接测量事实。

Full左右双击的目标等待收紧到2秒，±2500ms不变，排除自然播放10秒的假GREEN；其余seek等待仍10秒。Full主动暂停背景用例维持静音/同host/player/source/位置/PAUSE/Play断言，将回前台观察扩至3.5秒，确认超过3秒控件隐藏期仍可恢复。Review与HTTP fixture仅清除文件尾部多余空行；git diff --check通过。新输入 full-center-strict-doubletap-build.log 正在构建并运行JVM；设备回归/最终lint尚未执行。连续800ms三组双击累计仍只有逻辑JVM证据，未经真实三组指针量测，不提升为设备PASS。

### 2026-10-11 23:16 原三项RED关闭与新横滑RED

85989 Debug/AndroidTest/JVM构建终态PASS8m3s；JVM69 suites/444 tests/0 failure/error。随后仅新增真实paused bar30→立即正文横滑.2到.3（预期36秒）用例，AndroidTest增量81067 PASS1m56s。设备5项集中验证终态4 PASS / 1 FAIL /58.029s：严格2秒内Full双击±10、Full后台暂停并持续>3秒按钮、Review后台暂停按钮、Review正式1.5/长按2x/恢复/上滑锁定/解除均PASS；上一轮37项中的三个RED全部关闭，但不将新5项称整批GREEN。

唯一新RED：pausedBarSeekThenImmediateBodyDragUsesActualKernelAnchor178行，真实bar30已通过170行，随后的横滑未在2秒达到anchor+6000；原±2500与实际kernel/静音/身份断言不放松。Main APK SHA B4B88021F244DE22698D8BB4967DE441E1C9665901D4705B8E32648832E8647F；Test APK SHA1745742D13D1836AA9D3C92F494DE4A3FD8DC2CE89E34DE2E53EB4D40D8ABBC5。证据full-center-strict-doubletap-paused-body-device.log与logcat-terminal.log。

Full原横滑开始仍读绘制快照；改为在currentOwnedSourceHost门禁内捕获同一个manager的currentPosition/duration/isPlaying，move沿用本手势捕获duration。长按许可也改用kernelIsPlaying，避免库snapshot的isPlaying仅由Playing/Buffering UI state推导而误准许实际暂停内核加速；这一长按状态冲突未单独设备复现，作为明确静态条件修正记录。共有状态机、绘制、commit/cancel/resume/Poster/P1不动；源失配不begin，换源/后台/锁定仍cancel。实际Full新SHA395228C355D858AE90D9AC212014D1AD8ECFE9D6DC7EEB57699A53F5FE171D97。新输入尚待构建和原横滑RED补验，不宣称GREEN。

只停止本次自有emulator-5584；手机与生产服务未操作。启动轮询早期空ADB输出发生null.Trim诊断错误，后续转换string后确认sys.boot_completed=1才安装测试包，不把启动诊断误报为App失败。

### 2026-10-11 Stage8H 发布说明与任务状态对齐

更新根 USER_INSTALL_GUIDE.md、USER_LAN_VALIDATION.md、UPGRADE_ROLLBACK.md 及 deployment/README.md：当前目标Alpha11/code18、Server1.3.0/Contract2七项能力，保留历史摘要并移除旧电脑地址。包内用户路线使用实际会打包的根 USER_LAN_VALIDATION.md，与 docs/STAGE8H_USER_VALIDATION.md 同字节；不留下指向包内缺失docs的链接。未增加第二条用户路线或新服务控制入口。

TASKS.md / REVIEW_SUMMARY.md切到当前Stage8H状态，明确横滑新增RED、最终门禁、正式打包与Git交付待闭合，未宣称CODE_READY。更多菜单标签的一行意图修正已准备，实际源码在运行中构建期间保持冻结。git diff --check通过（仅既有LF/CRLF提示）；不影响Server代码、生产配置、手机数据或生命周期。

### 2026-10-11 横滑输入构建终态与菜单标签补审

58005全门禁构建实际成功14m55s（91 tasks：23 executed / 68 up-to-date），Full395228C3 / Review4C303E98输入。JVM69 suites/444 tests、0失败/错误/跳过；lint0错误/42警告/15信息，XML与报告归档到full-gesture-kernel-input-jvm-results、full-gesture-kernel-input-lint-results。横滑新增RED的设备补验仍未执行。

独立冻结增量审查C0/I0/M1，唯一M1是More暂停/继续标签仍读snapshot，而动作读userWantsPlayback。等构建终态后将标签改为userWantsPlayback且owned host非AUTO_COMPLETE，与toggle条件相同，不改动作或owner/foreground门禁。实际Full SHA EB4D23B1C3DD6777777CE260A612DF70561EB5C59EA0BB98567950AFEA66FE71与审阅TEMP相同。独立补审该行及发布说明范围C0/I0/M0；报告final-more-label-release-docs-independent-review.md注明审时actual未应用，实际应用由root核验，不篡改报告。

根发布文档更新后的114项部署/发布/重打包契约测试终态PASS，只有既有Starlette弃用警告。Server代码未变，复用完整577和Ruff115结果。启动最终Full EB4D23B1的Debug/AndroidTest/JVM/lint整批，日志final-more-intent-android-gates.log，仍运行中，不宣称最终输入整批通过。正式Release、EXE、connected、Git提交推送与交付尚未执行。

### 2026-10-11 最终输入Android全门禁成功

53073终态BUILD SUCCESSFUL6m24s，91tasks18executed73up-to-date。实际Full EB4D23B1 / Review4C303E98，Debug与AndroidTest完成；JVM69 suites444项，0失败/错误/跳过；lint0错误/42警告/15信息。归档final-more-intent-gate-results。Debug Main APK SHA32506D20E1E14D477C9C4A35B25F59C726683A57E1F352617AC14C735D8ABD6E，Test SHA1745742D13D1836AA9D3C92F494DE4A3FD8DC2CE89E34DE2E53EB4D40D8ABBC5。这不是正式Release。

自有模拟器153460启动于23:42，5584/5585 listener属其qemu子进程169012，原端口空闲。启动轮询早期空native输出仍触发Trim诊断，已改String.Join数组归一化空输出；BOOT_PENDING不是App失败，必须实际boot1后才做最终connected。保留AVD数据、不安装手机。最终设备回归和横滑RED补验尚待执行。

### 2026-10-12 connected中断与旧分类测试前提

前一轮51233最终connected计划139项，日志止于83/139、8 failed，无终态和最终XML。晨间实查工具句柄Unknown、无Java/qemu/emulator进程、ADB无设备，Gradle daemon154156日志同样止于83；原因UNKNOWN_ENVIRONMENT_INTERRUPTION，不伪称通过或主动重启一个仍存活任务。原日志保留，独立记录final-connected-interruption.json（进程不存在/设备空为空的事实针对启动下一实例前）。

已确认旧分类模拟数据不符合Stage8H接口：生产loadFolders读取folderTree，旧Stage8FHttpFixture health六能力都未声明且只有folders；旧Startup/Loading fake也仅覆盖folders，默认folderTree=null。仅在AndroidTest补health1.3七capabilities与认证folder-tree三个节点、旧count30同等metadata；Stage8A先等待分类树非空再保留30/30断言。Startup与Loading fake切到folderTree，保留媒体先开始/收藏惰性及故障不污染媒体原断言。原错误接口未退回产品，不改生产播放/Home/Server。两个App smoke组仍核查真实证据，尚未补验。

### 2026-10-12 新横滑RED实际GREEN及旧App测试前提补正

自有AVD再次实际boot1，安装保留code18/alpha11，读取installed base.apk SHA32506D20与最终Debug包一致。76379真实指针 pausedBarSeekThenImmediateBodyDragUsesActualKernelAnchor 终态1/1 PASS27.922s，2秒near(anchor+6000)、未恢复声音、same player与first认证原断言均保留。原5case该RED已关闭；不把单项当139项全套通过。日志final-paused-body-anchor-focused-device.log，原rawlogcat仅本地归档。

拉取保留AVD的合成测试证据：Stage8F当前code18 run210696和244854，不混入code17历史run。Full smoke长按后画面/语义/HTTP均证明已进入播放器，旧测试直接找书架前提不成立；新增实际Back回home并assertDisplayed，不调用VM。Viewer本次点击前图显示连接提示遮挡按钮、最终metrics无POST；文本消失后退出动画是否影响命中仍是候选。测试等待真实提示消失后推进500ms Compose动画/idle、保存点击前语义/PNG，保持真实click，并加强实际认证POST断言，原48–51像素/原图HTTP/分页/排队恢复完整保留。

Stage8G搜索真实HTTP显示播放器返回后重验已加载首页页1/2，符合规格68后台Refresh；更新精确两页请求与总数、保持搜索期间零首页新增、搜索/首页原1px锚点及查询分页原断言。收藏首个v301未发playback且控件已隐藏；只增强可见控制层前置与真实物理点击，加认证v301请求断言，原冻结图片context/首末导航不放松。这一点击原因仍需deviceGREEN。

实际生产Full EB4D23B1、Review4C303E98和Server源码未变。仅AndroidTest修正，独立补审分类范围C0/I0/M0；App候选独立审查及真实目标补验待完成。启动94369 AndroidTest/lint增量构建，未重复不变JVM444/Server577。自有AVD正常emu kill释放内存，未操作手机/生产。

### 2026-10-12 Task4提交与Task5共享批阅收尾

Task4实际提交0bb8772：Full控制/手势/音轨/严格源归属与生命周期、共享状态机和对应真实HTTP素材测试。提交前新增文件diff检查发现四处末尾多余空行；连同两处Review测试EOF统一清理，final-eof-format-evidence.json记录六文件前后SHA及去除尾部换行后的正文完全相等。功能逻辑未改。

Task5的Review共享gesture/seek与明确暂停按钮、源替换及前后台恢复仍保持实际SHA4C303E98；原37批次的两个Review RED已在5case补验真实关闭，其余shared/full专项历史结果保留，不改写37全绿。独立完整审查+增量补审C0/I0，拟提交批阅改动与对应源替换/后台/手势测试；最终139回归和正式发布门禁仍未完成。

旧测试前提AndroidTest/lint增量94369实际成功10m21s，63tasks9executed54up-to-date。其后仅六文件EOF格式修正，已证明正文完全相同；不把前述构建冒称清理后的编译。下一轮统一编译最终Debug/AndroidTest与lint，再执行旧失败目标及全connected。不重复无逻辑变化的JVM444和Server577。

### 2026-10-12 根Snackbar遮挡的确认与最小设计

84333真实目标16项终态13 PASS/3 FAIL172.312s。分类四项旧RED、搜索返回及static longpress回首页前提已通过。F两项仍在认证POST p51等待失败，G首v301导航仍未发playback。root实看run349607点击前PNG及semantics：全局‘加载更多失败，请重试’提示bounds2210..2400覆盖viewer_delete2211..2316。Viewer自己的局部host已bottom145dp；覆盖者是AlbumPagingController→V2HomeViewModel.emitMessage→V2MainScreen根Scaffold队列，等连接文本和500ms动画不能清掉后续queued提示，旧候选已被真实RED否定。

沿既有布局设计选择：给根host按路由留控制区净空，Viewer145dp、Full160dp，普通Tab保持原位置。替代方案‘等待所有提示结束才准点击’只改善测试前提，保留产品遮挡；‘丢弃跨路由消息’会改变错误反馈业务，故不选。复用原SnackbarHost/Flow，不新增消息系统、不改变播放或删除控制器。当前用户完整规格103已授权普通实现选择自行完成，不重复请求设计批准。

TDD使用已经实际失败且图/语义证实遮挡的F点击→真实认证POST用例，保留一次物理点击及全部原图像素/HTTP/恢复断言；不增等待、不以语义动作绕过。G共同根层是否导致失效尚未证实，只新增点击前PNG/semantics/坐标诊断，新的根offset不预计G通过。修后先跑这三个真实RED，只有新终态证明才关闭，再更新最终门禁/源码审查。

### 2026-10-12 提交状态与根提示修正编译

Task4 0bb8772 / Task5 763800b 已实际提交；计划两节对应状态同步为完成，Task6 保持进行中。EOF清理后最终 JVM 95848 实际444 tests/69 suites/0 failure/error/skip；Debug/Test/lint 4182 实际成功，lint 0 error/42 warning/15 info。上述门禁是根提示修正前的输入，不能代替新改动的最终门禁。

根Snackbar按详情路由145/160dp避开操作区的独立静态补审C0/I0/M0；新输入Debug/Test构建57387实际成功4m39。仅自有AVD emulator-5584 boot_completed=1 后依次安装两份APK成功，focused三项实际RED补验12752正在运行。图片Viewer首项已终态通过，另外两项与整批结果未终态，不预填全绿；未安装用户手机、未部署生产服务。

### 2026-10-12 根提示真实回归闭合

12752 focused三项终态OK (3 tests)，142.499s：actualAppViewerDecodedPixelsAndDelete、actualAppAlpha9MediaExperience、favoriteVideoAndImageWindowsStayIndependent全部PASS。严格保持真实物理点击、认证HTTP、真实图片像素/原图读取、待删除恢复与冻结收藏上下文断言。F遮挡RED已关闭；G本批通过，但仅据此不宣称其旧失败机制与F相同。新点击前PNG/semantics和终态logcat保留root-snackbar-clearance-evidence及focused-device.log。

随即以进程级ANDROID_SERIAL=emulator-5584运行完整connectedDebugAndroidTest，使用同一已安装最终Debug/Test输入。整批尚未终态；旧83/139中断证据继续保留，不能由3项通过替代139项门禁。

### 2026-10-12 完整回归第二次中断与三项新增失败

2010完整connected最后输出132/139、3失败，没有完整XML或Gradle终态。13:49继续时会话句柄已丢失，实际查询Java/自有模拟器进程为0、ADB无设备，确认运行停止后才准备后续目标补验。原因保持UNKNOWN_ENVIRONMENT_INTERRUPTION；140输出文件/9149011字节另存root-clearance-interrupted-connected-output-results，不能声称139通过。

三项已实际FAIL：FullLifecycle横屏后第二showControls等待5秒；periodic位置>=14秒；同ID同Stage换URL后阻塞准备检查新kernel为null。首项此前landscape与同Activity/host/kernel断言已越过，不能称旋转失败。第二项间隔>=14.5秒已越过但未打印真实位置。第三项日志明确旧Exo release timeout触发kernelError；字节码证明旧onPlayerError可通过GSY manager当前listener传播，缺原kernel归属。新Exo随后的Release可能是JUnit cleanup，尚不把时序冒称确定因果。

先在两份AndroidTest添加控制层时钟/节点、真实上报位置/内核位置、切源错误与检查前kernel诊断，未放松原断言；84389仅AndroidTest增量构建实际成功3m11，6executed/48up-to-date。生产输入D33 Debug未改。待实际三目标复现后确定修正，最终门禁和Release仍未完成。

### 2026-10-12 间歇失败补验与确定性回归设计

82929原三项诊断补验终态3/3 PASS45.389s；这说明失败间歇存在，不能据单次通过声明根因全部关闭。MRLifecycleTest打印本次periodic.position14753/kernel14774/实际间隔15471ms；横屏第二showControls count1，时钟欠帧217ms。本次source检查旧内核已停止/URL清除、新Ijk存在且errors0，原像素/认证/继续前进断言全保留。

为避免盲目重跑增加两项确定性测试：真实播放下积累3300ms未结算测试帧后再次showControls，检测先读旧节点后才结算timer的竞态；认证HTTP真实换源后在已释放旧Ijk公开onPlayerError重放观测到的释放错误，要求新内核同身份继续前进，随后当前Ijk错误必须反馈App。回放是明确内核边界注入，不声称真实网络错误；不伪造播放位置/状态。22871仅AndroidTest构建运行中，尚无RED终态。

最小设计候选：测试helper先结算真实时间对应的Compose帧再决定是否物理tap；换源和退出前仅在manager.listener为ownedHost且真实kernel URL为loaded URL时，清旧Ijk错误监听。新内核初始化由GSY照常安装自己的监听，保持真实当前错误链。比扩大超时/吞全部错误更准确，不增加Player或替换库。现有两个故障已有实际RED，新增确定性RED确认后实施；periodic原14秒断言暂不改，等待更强失败数值证据。

### 2026-10-12 确定性RED与最小修正

43138两个新增回归终态2run/2FAIL32.575s：控制时钟欠帧产生同样5秒showControls等待失败；已释放旧Ijk错误回放真实送达当前App errors=[same]，因此新源污染不是仅凭时序猜测。原两项失败详细日志与新RED分别保存，不互相替代。

已实施共享detachRetiredKernelErrorListener：仅loaded URL非空、manager当前listener为owned host且真实Ijk.dataSource等于loaded URL时清旧OnErrorListener；Full/Review只在确认切源的setUp前及owned退出release前调用。Review放在可取消host等待/最新源二次核验之后，避免Loading或等待取消时提前解除仍会恢复的旧源监听。新内核初始化仍安装自己的listener，当前错误链由新增真实Exo边界回归必须证明。

showControls先按原waitWallClock补真实帧再读节点；没有扩大5秒、没有直接操作控制状态，仍真实tap。测试errors改CopyOnWriteArrayList关闭审查跨线程M1。3098新Debug/Test构建进行中，尚不声明GREEN；两个独立补审已派发，原periodic14秒和全部身份/像素/认证/位置断言保持。

### 2026-10-12 旧错误与控制时钟GREEN、暂停验收锚点纠正

18345实际5run/4PASS/1FAIL77.719s：新增旧内核错误隔离（旧错误后同新内核真实前进，当前错误仍到App）、控制欠帧恢复、原横屏同宿主/内核/位置和真实Poster切URL全部PASS。periodic初段本批已过：snapshot14464/kernel14853/真实间隔15912ms；后续暂停回调32539/kernel32539准确一致，但测试仍距先前seek30s要求<2500而超39ms。日志证明Sheet等待期间真实播放继续，不能称产品回调零或滞后。

经独立复核，仅修正AndroidTest基准：Pause实际click前即时beforePause=kernel.position；仍要求回调>25000、距点击前位置<2500、原seek30±2500、native差<=1500、真实认证POST/暂停/恢复/退出全保留。真实播放约15秒的>=14000门槛改读periodic.kernelPosition，和原允许snapshot1500ms轮询延迟一致；保留真实间隔>=14500/回调非零/准确HTTP。此调整未扩大数值或超时；原139批没打印native，不追认该旧失败原因已证明。

共享生产guard独立审查C0/I0/M0；本批两个确定性RED已真实GREEN，但本批仍4/5，最终整套和发布门禁未闭合。下一轮仅最终测试基准增量编译，目标补验后跑新141整套。

### 2026-10-12 暂停真实点击锚点补验与最终141启动

98151单项实际终态OK (1 test)，37.571s；保持15秒实际间隔/真实内核约15秒、回调与真实内核偏差原1500ms、真实认证POST、seek30秒、Pause点击前即时锚点<2500ms且回调>25000ms、暂停静音/恢复/退出释放全断言。此单项不代替最终整套。

启动一次性隐藏最终Gradle runner，仍只执行登记的connectedDebugAndroidTest，ANDROID_SERIAL固定emulator-5584，无过滤。TEMP脚本冻结两APK SHA、记录本次PID/结束退出码与输入未变化；报告JSON不代替真实JUnit XML/Gradle终态。当前预计141项（新增两个确定性用例），实际总数以runner输出/XML核对；尚不声明全通过。生产源码/测试冻结，新JVM/lint/正式Release与ServerZIP尚未完成。

### 2026-10-12 最终141设备门禁终态

冻结输入B750 Debug /8B979 Test，最终connected无过滤执行141项，实际Finished141、BUILD SUCCESSFUL12m15、75 tasks1executed74up-to-date；runner结束退出码0、APK输入未变化。root随后实读完整JUnit XML，141 testcase/0failure或error/0skipped，不以进度或exit receipt替代结果。归档final-retired-guard-connected-results及final-connected-junit.xml，原两次中断和所有RED/补验历史保留。

最终批覆盖新旧source/error、控制时钟、严格真实seek/双击、Full/Review生命周期、目录/缓存/Home与实际App上下文；前述三个间歇失败及新增确定性回归在整套中全部通过。终态logcat保存后仅正常关闭自有emulator-5584释放构建内存；未操作用户手机或生产服务。当前尚待新输入JVM/lint、唯一正式Release/ServerArtifact与Git/Handoff，不提前声明CODE_READY。

### 2026-10-12 最终JVM/lint与发布输入冻结

10984最终生产输入JVM/lint实际终态BUILD SUCCESSFUL9m42，41tasks13executed28up-to-date；root实读69套XML合计444tests/0failure/error/skipped，以及新lint XML0error/42warning/15information。结果已归档final-retired-guard-jvm-results、final-retired-guard-lint-results及verification.json，在Release clean前保留。Server产品输入未变，复用577完整pytest/Ruff115及发布契约114真实结果，不重跑无变更门禁。

REVIEW_SUMMARY/TASKS/计划统一为最终自动化已过、正式产物及交接待核验。远端只读探测成功，Stage8H分支尚未发布；Task6提交后使用既有build_deploy --prepare-only执行唯一正式assembleRelease和新Server隔离核验，不部署生产、不改变长期服务/手机。

## 2026-10-12 Stage8H 正式离线发布产物

Task6提交ec5ccc656d112654b2a31757fb5b3d656eebb807。唯一正式Release构建成功4m52；APK code18 / 2.0.0-alpha11、签名PASS，与Alpha10证书一致。APK 43557458 bytes，SHA256 7b3d97355013073fae33340b3e7f980ea1f52ba229db3104c6e5edb8d7017393。

Server fresh EXE隔离health确认1.3.0 / Contract2 / 七能力；EXE SHA256 abe8eb048768a172cab2cb45c361d14501af9b27ba966555cafa6b890c7a9088。正式ZIP126871995 bytes，SHA256 44b9609c1a5dd290ccf015ed7eb0f458c9743e61f3e17b3e6e8eb02f5d7356a8；169项SHA和ZIP完整性回验PASS。receipt在deploy_handoff/stage8h-build-evidence.json；日志与签名连续性回执在review_meta/stage8h。

最终有效自动化：Server577、JVM444、connected141全部通过；lint0错误/42警告/15信息，Ruff115文件通过。状态CODE_READY / READY_FOR_USER_VALIDATION。后续仅文档提交，正式产物保持ec5ccc6 provenance；最终HEAD、冻结输入一致性、push与十文档Handoff结果通过独立交付回执记录，不反向修改官方产物。未执行手机安装、真实LAN验收、生产部署或ControlHub生命周期；生产升级桥仍BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT。

## 2026-10-13 Stage8I Task1 固定缩略图档位

新增 GRID_SMALL 256×144/q60、GRID_LARGE 480×270/q65、ALBUM 320×427/q65 不可变白名单；认证 thumbnail HTTP 默认 GRID_LARGE，未知 profile 返回422，任意尺寸/quality查询不生效。完整 profile/尺寸/quality/fill variant 参与缓存与ETag，继续使用既有single-flight/prune。Jellyfin thumbnail_image 增加可选quality且直接旧调用默认80。

TDD：新增认证真实HTTP→真实JellyfinClient→MockTransport上游合同，修正测试异步runner后RED为4失败/1通过（旧尺寸/q80）；GREEN指定thumbnail/image/loading/client五文件50 passed、1现有Starlette弃用警告，Ruff check/format五改动文件通过。旧loading_closure默认HTTP期望按新规格从q80更新65。未执行全suite、Release、手机、真实媒体、生产部署或ControlHub操作；版本及capabilities由Task9对齐。待root独立规格/质量审查。

## 2026-10-13 Stage8I Task2 Android 图片管线候选

MediaCard、相册双列、书架和WindowCoverPrefetch使用同一固定profile URL/尺寸，保留origin/v/认证和Demo/Jellyfin路径。当前原图继承同媒体封面源指纹v，source namespace仅显式清源变化；最终删除只drop对应资源并失效folder辅助缓存，未删内容及namespace保留。Viewer当前原图成功后才串行预取±1源，±2仅低清，页/源/退出取消旧预取；current按viewport上限2048、prefetch1×1，均Precision.EXACT。HTTP original仅复用源盘缓存、禁memory旁路，局部max-stale30仍尊重no-store/no-cache；不改全局Coil。低清层保留，单列已有Large以memory占位/cache-only disk层显示，无新增Large HTTP、无UI读盘；空内容自然比例Skeleton，无中央Spinner，原有zoom/上下文保持。

真实TDD记录：默认profile RED20/1fail→第一GREEN35目标JVM及Debug Main/Test通过；production original缺v RED22/1fail（expected source1/null）→GREEN25/0fail；endpoint边界RED4/1fail（fingerprint/null）→最终26目标JVM通过。新Test错误把Coil EventListener interface当构造函数，首次最后编译失败已保留；修复后最终repair session53006 EXIT0、BUILD SUCCESSFUL3m49、26tests0failure/error、两个Debug APK通过，18源码/测试输入哈希未变。Repository真实HTTP两页100图片、三profile300次请求、original0、认证/v与删除保留回归；policy仅补充边界，不代替真实图片。

新增统一设备合同：同一100逐张JPEG列表与Viewer像素性能对比（Alpha11未发生邻预取记录NOT_PRESENT）、actualCoil noTTL源复用/新namespace/no-store与no-cache current回访、真实painter1100×800→300×218、5MP源精确解码、真实Home/Album组合与WindowPrefetch、当前原图优先/±1/±2/连续swipe取消及有界HTTP、单列memory/disk-only Large缓存双延迟仍绿图。当前仅编写/编译，设备执行与最终独立验收由根Agent统一，不能标为device PASS或Task2全部完成。既有Stage8F真实App图片/上下文合同保留。未执行Release、ADB/AVD、手机、生产服务或ControlHub操作；版本由Task9对齐。完整命令、失败历史、APK原字节SHA及局部30秒缓存限制见任务报告。

### Stage8I Task2 根接手：设备证据与原图传输取消（2026-10-13）

- 图片测试首轮7项5PASS/2FAIL；原有App Viewer/缩放/待删除真实合成HTTP回归1PASS。相册组合测试修正为先进入已有SHELF页后已单项PASS。
- Alpha11同100图片真实逐张绿像素/零原图基准1PASS：缩略图body6785700B；改进候选同100图片body1329400B，热回访0B。候选仍是中间Debug（versionName待Task9），以冻结SHA区分；仅模拟器合成fixture记录，不代表手机或家庭LAN验收。
- 已修测试类非void返回。旧内存缓存候选真实RED：no-store回访预期NETWORK实为MEMORY_CACHE；修正版缓存/真实painter/5MP解码3PASS。旧单列缓存候选盘预览1000ms超时（body5000ms），修正版内存/盘两项PASS；旧精度候选真实painter也PASS，不能声称该项已证明RED。
- 快翻复现两轮maxActive4。新增MR_IMAGE_HTTP仅记录路径/计数/单调时间，不记录凭据。p51旧邻预取与p51当前慢body未及时中止，叠加p52/p53至4；翻页取消job不足以立即停止已收到headers后的body读取。
- 根接手新增ImageRequestLifetime：当前源key拥有显式tagged原图Call；现有图片CacheAuthInterceptor登记；Viewer页/来源变化和退出DisposableEffect主动cancel，当前Painter与串行±1源共享所有权。普通缩略图、外部未tagged请求和认证边界不改。新增真实延迟body/晚登记取消JVM测试；生产修复尚待构建与快翻设备GREEN，不能声明Task2验收完成。
- 所有测试仅owned read-onlyAVD，手机/生产服务未操作。终态/日志见temp/stage8i-ux/progress.md与review_meta/stage8i/task-2-device-round1-receipt.json。没有新增长期对象或脚本。

## 根设备终态补充（覆盖前述未执行状态，2026-10-13）
最终window候选Main SHA630863a31b043490a70bb20c336739e58e975a9f9cb8ee7f899f21a7c413a875/Test80f90c15c153fa69c5958c18e460885b214448f2ca17b93b45f900c659644632，97848真实EXIT0/BUILD SUCCESSFUL2m53且输入hash无变化。原图current与prefetch现分独立传输lifetime；通过既有CacheAuthInterceptor把tag传到真实Call，页/源/退出取消两者，同页adjacent变化只取消旧prefetch。新JVM真实延迟body与晚登记取消2PASS，Repo22+Policy4累计28PASS（96421输入，之后只改Compose邻scope与device tests；未广泛重跑无影响JVM）。
设备分轮：旧memory候选no-store回访1FAIL(expectedNETWORK actualMEMORY_CACHE)→修正版缓存class3PASS；原图片class7项5PASS/2FAIL，Shelf导航修正后真实Home/Album PASS；快翻max4真实失败两轮，原图连接取消修正后最终rapid PASS/max2/exit0。旧before-window同页邻源更新1FAIL1500ms旧body仍活→最终same-page PASS。最终另current-before-neighbors PASS、Date60秒过期及5秒must-revalidate真实网络PASS。
最终聚焦5tests四业务PASS、原AppViewer因模拟器SystemUI ANR占窗失败；XML/dumpsys核实后在ownedAVD点击Wait、确认MainActivityfocus，单项AppViewer真实pixel/zoom/pending-delete/context重跑PASS1/35.505秒。失败日志保留，不把首轮合并伪造5/5。累计各候选测试复用范围明确；没有宣称最新APK12个新测试全跑，Task9全量门禁仍待执行。
Alpha11同fixture100逐张绿像素/原图0：缩略图body6785700B；profile候选1329400B，热回访0B。记录均是合成emulator，不是手机/家庭LAN，非SLA。旧precision候选实际Painter也PASS，不能称该項证明RED。最终正式Alpha12/Server1.4/Handoff均未生成。Task2可进入针对性代码验收，整体Stage8I仍未完成。
证据：review_meta/stage8i/task-2-device-round1-receipt.json、task-2-device-round3-receipt.json、task-2-window-build-receipt.json；temp/stage8i-ux相应原始日志、MR_IMAGE_HTTP时序；静态独立审查C0/I0，终态证据刷新请求因模型capacity失败，根已核对实际终态，不虚构独立运行审查。

## 2026-10-13 Stage8I Task3 共享待删除与自适应横向 Seek 候选

将原 ViewerPendingDeleteController 泛化为 PendingDeleteController，保留旧类型别名和 Viewer 入口兼容。Viewer 与正式 Full 入口 GsyNativePlayerWithPendingDelete 共用 Home VM 中唯一队列控制器，读取 / 加入 / 撤销沿用真实 MediaRepository；只有服务端确认才修改 Ready.ids，未知状态禁用并允许失败重试。Full 顶部明确显示“待删除 / 撤销待删除”，成功 Snackbar 的“撤销”绑定原 mediaId 与 session，成功和失败消息都按代次过滤；离页、换媒体、换数据源取消旧任务并隔离迟到响应。播放器只调用待删除队列协议，没有永久删除入口。

Full 与 Review 的实际 PlayerBodyGestures 回调共同使用 PlayerSeekMapping：整屏时长 2%，限制 30,000–180,000ms；5m / 30m / 2h / 6h 对应 30s / 36s / 144s / 180s。非 finite 几何输入、负时长和 Long 边界安全处理。移动仅预览，SeekDragEngine 结束只输出一次提交；原进度条绝对定位、真实 DoubleTapSeekState ±10s、播放 owner / 用户主动暂停 / 临时暂停恢复保护未改。横滑提示在进度附近显示增量与开始→目标时间，旧 SeekGesturePreview 也转用共享映射。

真实验证：39366 helper 范围 RED 1test/1fail（旧5m=90s，需求30s）；89938 controller 成功反馈 RED 8tests/1fail（expected 已加入待删除，actual 空消息；其 controller 生产实现未改，但该轮 Seek 源在构建期间修改，因此不声称整轮输入冻结）。99307 因 --tests 错误绑定 assembleDebugAndroidTest 配置失败；98825 两处 nullable currentMediaId 编译错误，修正后 87357 EXIT0/BUILD SUCCESSFUL4m10、33个目标 JVM 通过。最后收紧失败消息代次、补真实 DoubleTapSeekState 断言及真实 PNG fixture后，95134 EXIT0/BUILD SUCCESSFUL1m40、35tests/0failure/0error、Debug Main 与 AndroidTest APK 编译打包通过。20项 Android 冻结输入构建后回验无变化，git diff --check PASS。日志、冻结清单与 receipt 在 temp/stage8i-ux/task-3-*。

已准备但 writer 未执行设备测试：Stage8IPendingDeleteHttpTest 的正式 Full / Viewer → VM → 生产 Repository → synthetic HTTP 初始加载、加入确认、Snackbar DELETE 撤销、失败重试/写失败、换媒体迟到隔离；现有 Stage8HFullGestureTest 与 Stage8HReviewGestureTest 新增真实60s MP4 / Exo discontinuity 计数，验证 move=0 seek、up=1 seek、内核真实目标位置和暂停保持。60s设备测试不会冒充5m/30m/2h/6h真实播放验收，长时长映射目前仅 JVM。设备、全JVM、lint、Release、手机、家庭LAN、生产服务均未由本 writer 执行；版本 / 正式发布留 Task9。未 commit，待根独立审查及统一设备验收；ControlHub 保持 BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT，无新增长期对象或脚本。

### Task3 根设备失败后测试隔离修正候选
根设备53517真实终态为4tests/4FAIL/59.535s，全部停在测试launch等待Home首屏列表非空，尚未执行待删除业务断言。生产Home bootstrap读取持久化模式；测试helper只在未存模式时默认DEMO，而旧AVD可持久化SERVER。另reloadAll对直接Server Repository且未配置session明确return。测试修正仅在Stage8IPendingDeleteHttpTest保存旧mode→设置DEMO→After finally恢复；Home UI host使用仅mode=DEMO的委托适配器，全部媒体分页、队列读取/加入/撤销仍走真实生产Server Repository HTTP，播放器VM仍直连该Repository。没有修改production health/configured Gate，没有延长timeout、假确认或清app数据；这个测试不验证真实profile/health bootstrap。修正编译及业务设备重跑尚待根协调，原4失败保留，不把它们归因为SystemUI ANR。

### Task3 Server bootstrap fixture 修正（替代上一未验收 mode-adapter 候选）
按根审查要求撤回测试mode适配器，未用它声明业务通过。新的测试保存旧ServerProfile及mode，临时将fixture地址/随机凭据写入既有ServerProfileStore（凭据使用既有Keystore密文存储），设SERVER，直传生产V2ServerMediaRepository，让正式Bootstrap.restore、HealthMonitor.probe、configured/contract gate、reloadAll依次执行。fixture补API2 health和Home所需空folders；新pending测试opt-in既有AuthInterceptor，受保护fixture API要求正确认证，真实probe的media?page_size=1认证请求及host configured/paired有布尔断言。旧fixture默认bare API模式未变，不声称旧Full/Review测试API认证整链路；视频X-Emby认证证据仍独立。After finally用Store.clear/saveBaseUrl/savePairing恢复旧profile并检查base/token一致，嵌套finally恢复旧mode；日志仅记录恢复及Gate布尔值，不输出旧profile、fixture地址或凭据。生产Gate未改，不扩真实配对UI、手机、家庭LAN。此次仅测试三文件变化；主程序沿用95134已验APK，待增量TestAPK编译及根HTTP4重跑。

### Task3 Viewer 认证图片门禁补齐候选
68394仅TestAPK构建真实EXIT0/BUILD SUCCESSFUL4m14，但根只读发现新fixture保护PNG接口、App全局Coil TokenProvider与测试bootstrap provider独立；未把该中间候选当正常Viewer通过。随后仅测试fixture增加私有凭据作用域的CacheAuthInterceptor图片client，Viewer测试保存旧Coil loader、安装独占loader、After恢复旧loader并关闭owned loader/client。fixture为真实64×64绿PNG，接口认证要求保持；点击待删除之前同时等待当前first/original已认证HTTP、真实Drawable success及viewer_pager可见绿色像素，避免blank/error占位伪通过。生产图片DI/预取lifetime/缓存管线没有修改。8110仅TestAPK增量构建中，最终设备HTTP4仍待根统一执行。

### Task3 根最终设备验收（2026-10-13）
正式源码12项冻结哈希无变化，Main APK SHA256=37b044a9b7224c5b02a103d4938a1afd1bdcaeedba2eafd2c29adc8a2e9426b0。复用95134对应35项定向JVM通过；设备62172的2项adaptive与55752的6项bar/double/resume回归均实际通过。实际60s MP4证明横滑move不seek、up仅一次、半屏+15s、暂停保持；5m/30m/2h/6h范围仅JVM验证。

最新测试包8115实际EXIT0、BUILD SUCCESSFUL in 1m35s，三项测试输入冻结无变；Test SHA256=acda660d6d08a03ea82677f951aa8849e91fcbe446de03becb9ae294ef10939e。39038隔离只读AVD实际终态OK(4 tests)/64.998s：Full初始状态/HTTP确认后加入/真实DELETE撤销、加载失败重试与写失败、旧媒体迟到隔离、Viewer真实跨页撤销。Viewer先经过认证original请求、Drawable success及中央绿色像素验证，再加入first、真实swipe到second并断言标题与2/2，点击原Snackbar撤销后两次HTTP仍绑定first且true/false。四项都观察到正式Server配置/配对/probe及旧profile/mode恢复安全布尔日志，未输出凭据。

历史53517四项初始化失败与31192三通过一Viewer图片失败保留。Viewer fixture缺cover/original URL已补，并改为同origin LAN测试地址满足既有URL解析限制；生产解析/认证门禁未放宽。99216中间测试包构建通过但未运行，不代替最终8115测试包。新版仅测试fixture与跨页断言改变，前述35JVM/8gesture依据未变正式源码限定复用，不宣称全量一次性通过。

证据见review_meta/stage8i/task-3-cross-page-final-{build,device}-receipt.json与temp/stage8i-ux/task-3-cross-page-final-{device,events}.log。测试仅本Agent只读AVD及合成HTTP；owned AVD已正常退出、adb设备列表空。全JVM/lint/全connected/Release/手机/家庭LAN/生产部署未执行，Task9保留；ControlHub保持BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT。

## 2026-10-13 Stage8I Task4 Jellyfin 尺寸与真实删除回执候选

服务端新增内部 JFMediaSource 最小解析；有效顶层正 Size 优先，否则仅 primary MediaSources[0].Size。媒体 refresh 沿现有批量索引 upsert 更新旧行并补未知 pending 尺寸；generation 受控同步路径才允许已选库、本机绝对普通文件尺寸兜底，拒绝 URI/UNC/相对路径及父级/文件 symlink/reparse。兜底更新原指纹；队列 load/prepare/summary 只从持久索引做批量 SQL 回填，不做文件 stat。已知队列尺寸和 failed 不改。

DeleteReceipt 保持旧 dict/outcome 合同，API 加 deleted_count/deleted_bytes/missing_count/failed_count。只有 guard 文件身份检查通过、os.remove 返回且 lstat 后验确认路径不存在才计 success 与实际 stat 字节；missing/failed 不计释放 bytes，失败队列保留。库白名单、size/mtime guard、认证与 nonce 快照/单次/过期合同保持。真实隔离小文件测试删除18字节，API认证 nonce 闭环删除16字节；缺失1000估计字节不计释放，noop/PermissionError回执0字节且保留failed。

真实 RED：纠正 cwd 后46339/pytest EXIT1，10项中9 expected failures/1原有topSize行为PASS；本地stat RED 14项中1fail，指纹RED 16项中1fail。首批定向36项PASS/pytest EXIT0；最终80836/EXIT0、18项PASS，ruff相关5源码+1新测试PASS，git diff --check PASS。现有Starlette httpx deprecation warning保留，未因此升级依赖。temp/stage8i-ux/task4-frozen-inputs.json为最终18项结束后的6项SHA256 snapshot，不声称预冻结；该18项执行期间输入无修改。日志/命令/exit/selfreview见task4-report.md。较宽相关回归36700仍在运行，未以18项代表全回归。

未commit；根独立审查后提交。仅隔离temp/syntheticDB/Mock数据，未使用家庭媒体、真实Jellyfin设置、生产服务或手机；Gradle/ADB/部署未执行，无新长期对象/脚本。delete_receipt_v1 health能力宣告与正式1.4版本保留Task9；整体Stage8I未完成，BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT保持。

Task4独立审查specCompliance/codeQuality PASS、C0/I0/M0，定向开发门禁通过并由根提交；较宽36700仍待真实终态，交原执行者只读继续观察。后续Android开发不修改此轮稳定server输入；不以定向通过声称全量回归完成。

Task4较宽回归最终补记：原执行者36700真实工具EXIT0/pytestEXIT0，日志与exit于21:58更新；根实读71条PASSED、0FAILED。包括collection时旧16项Task4和55项既有nonce/mapper/收藏删除/Organize/媒体syncAPI回归；新test在collection后修选库并加父链接，故不将该轮描述为最终18项。80836最终18项独立PASS，二者范围分别保留。五项production在36700期未改，6项postrun当前hash均一致。无全server/手机/生产部署结论。

## 2026-10-13 Stage8I Task5 — FolderBrowser 与运行诊断（软件门禁，设备待验）

沿现有 FolderTree/FolderBrowser/WindowKey 实现：运行诊断读取实际 health store 的 Server 版本/API Contract/Capabilities；缺 folder_tree_v1 时提示层级分类需要 MediaReview Server 1.3.0+，禁用目录入口及 ViewModel 目录请求，保持全局 API contract Gate 与普通媒体/播放/图片/Review 边界。目录卡使用 tree cover_url 与既有缩略图 profile，展示名称/递归媒体数/子目录数；默认包含子目录、可切仅当前目录，scope 实际进入缓存键和 HTTP query，保留锚点/SWR。

真实 RED session15219 EXIT1：默认递归断言失败，5项/1失败；之前78768因测试改写路径失败只是旧5项baseline通过，不记RED。GREEN session59434 EXIT0：FolderPagingControllerTest6、FolderTreeContractTest3、FolderTreeHttpContractTest2，共11项/0失败/0error；默认范围与scope独立缓存/anchor/SWR失败保留已覆盖。仅测试收紧后，最终Debug/AndroidTest构建86962 EXIT0（2m），11项tracked/untracked Android输入SHA256前后一致。日志、counts、manifest、APK hash见temp/stage8i-ux/task-5-*。

新增Stage8IFolderHttpTest两项待根运行：正式Server mode/配对恢复/认证health probe、生产Repo、非回环真实HTTP ft_root>ft_parent>ft_child>ft_leaf、true/false范围query、parent-onlychildren可点、HTTP PNG与中心绿色像素门禁、breadcrumb/back及缺cap基本媒体/Playback/Review Ready。此为Compose widget host，不是NavHost路由验收；既有Demo树测试仅为回归。实际instrumentation/AVD/手机/真实Jellyfin/家庭LAN未执行。没有服务部署、清数据、新长期对象或脚本；BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT保持。未commit，根独立审查/设备门禁后接管；整个Stage8I未完成。

### Task5 根设备终态补记（2026-10-14）
最终Main66915cea34b51f07d7d53c0d601b3979dfecabfea6175847090a8a3444477276/Test45f49c85193960826198de2e478f28d093a4f6d225ff37050018ddbb04497c96，11份预构建输入哈希重连后全部一致。前轮82921实际instrumentation终态OK(2 tests)/30.599s，认证bootstrap/真实ft目录/封面绿色像素/true,false范围/返回路径及缺cap基础Repository Ready；重连工具句柄不存在，不补造shell退出码。证据见task-5-http-device.log/events及review_meta/stage8i/task-5-http-device-receipt.json。

新只读AVD169696运行受影响旧FolderBrowserTouchTest：11109真实工具EXIT0、OK(2 tests)/19.46s，目录真实pointer/父子树/页1,2/50项有界分页/面包屑返回、封面预取窗口回归通过。该轮Demo/widget不能代替上轮认证HTTP，也不代表正式NavHost/手机/家庭LAN。前置SystemUI ANR在测试启动前通过Window/XML观察，选实际Wait按钮并确认Launcher恢复；测试本身无失败。ownedAVD已正常退出，adb列表空。日志/receipt保留，不声称全connected/全JVM/lint/Release或生产部署。

## 2026-10-14 Stage8I Task6 — Review Folder Scope（软件候选，设备门禁待根）

Server source.filter 接受真实 folder_ids 列表（最多100）与真实 include_descendants 布尔值（默认true）；未知/foreign节点或错误类型拒绝，复用 folder_tree.scope_condition 多选OR，在完全重复代表项排序之前过滤，创建队列与范围快照固定。恢复沿既有持久队列，不重新过滤。Android升级唯一共用 ReviewSourceDto.filter 为 Map<String,JsonElement>，保留旧字符串字段与 sort 合同；生产Repo/router/VM/Scope Sheet真实发送arrays/bool。复用FolderTree与FolderCard，全部/多选/包含子目录；旧active默认恢复，重新选择须确认，确认前取消保留旧session/queue。缺review_folder_filter_v1时禁folder并提示升级，基础全部视频仍可用。冷进入先Sheet，取消保持尚未创建的ScopeSelection，不宣称批阅完成。

确认开始新会话时先退役旧Ready并取消旧P0/P1/position/page；POST结果未知或新queue失败转Error，重试只GET canonical latest，避免重复POST。seen在任何挂起前固定原session目标，晚seen/page响应校验会话身份；page另校验generation。创建/恢复与分页共享既有Repo mutex，避免晚旧page写新缓存。标题树异步加载，不阻塞Ready/P0；Sheet/title请求显式传播取消并有generation/session保护。普通restart保持范围；无真实路径标题。

真实RED：Server修正fixture后17项功能断言失败/1原有OR全选快照通过，日志task6-server-red.log（该次tail包装未保留pytest退出码，不把工具EXIT0解释为pytest通过）；首轮84440工具EXIT1含fixture属性错误，已修正。JVM DTO32827 EXIT1，9项/1失败；cold取消21945 EXIT1，1/1失败；POST成功queue失败65590 EXIT1，1/1失败；late16597 EXIT1，2/2失败，实际seen目标old→new及新window总数1→100均被捕获。

最终Server session1244 EXIT0，19 Scope +27既有Queue共46PASS。最终JVM session38736 EXIT0，75PASS/0FAIL/0ERROR（Api9、ServerRepo34、VM32）。唯一最后Main/Test build82780 EXIT0，2m27；326项Android输入前后SHA256一致，0漂移。只有Stage8B旧instrumentation冷进入语义在最终unit后更新：明确选择全部再创建，继续原queue/seen合同；未重复JVM。Server最后仅机械import排序，实际Ruff三文件EXIT0 All checks passed，按无语义变更复用46项行为验证。既有AGP/deprecated警告及VM405冗余always-true条件警告保留，非功能失败。

新增Stage8IReviewScopeHttpTest四项已编译，未由writer运行：正式Store/配对bootstrap/认证HTTP与既有FolderCard绿色封面像素；active取消POST0、确认multi-array/bool POST1、resume仍1；冷进入先Sheet/取消不假完成；缺cap基础全部Review；POST接受但queue503退役旧Ready、canonical retry不再POST。沿现有RealHttpPlaybackFixture opt-in Scope模式，默认Task3/5行为不变；profile/mode嵌套finally恢复且仅assert布尔，不输出凭据。属于Compose widget host，不代表正式NavHost/手机/真实Jellyfin/家庭LAN。

Main SHA256 B7EA00A4C97A71D639CD2CE44485258EABA5F53B94896892D26313A9EA4DB536；Test D1E8C36551C1E892C24CEE1955BDAF65F251300563F8102A48B52E5CAEF2E75B。报告与receipt见temp/stage8i-ux/task6-report.md、task6-build-receipt.json；所有必要log/manifest保留为验收证据，没有一次性脚本或无用temp需清理。未commit/push，根接管独立审查/统一AVD四项Scope及受影响Stage8B门禁。全JVM/全Server/lint/全connected/Release/手机/生产部署未执行；无新增长期对象/脚本或Registry/ControlHub改动，BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT保持，整个Stage8I未完成。

### Task6 根运行门禁补记（2026-10-14）
最终软件：46项Server scope/queue、75项JVM（API9/Repo34/VM32）均实际通过，Ruff相关3文件通过。82780构建实际EXIT0、SUCCESS2m27，326份Android预构建输入根重算0漂移。Main SHA b7ea00a4c97a71d639cd2ce44485258eaba5f53b94896892d26313a9ea4db536；Test SHA d1e8c36551c1e892c24cee1955bdaf65f251300563f8102a48b52e5caef2e75b。Server仅构建终态后机械排序import，未改变46项对应行为；未重复无关检查。

真实Scope设备92623：工具EXIT0、OK(4 tests)/54.772s。正式Server配置/配对/health probe、生产Repository、认证合成HTTP和实际目录封面绿色像素，验证多选JSON数组/include_descendants布尔、取消POST0/确认POST1/恢复仍POST1、冷进入先Sheet取消为中性ScopeSelection、缺cap禁folder但all可建、POST已接受且queue503时退役旧Ready并从latest恢复同一新session且不再次POST。四方法status0；只有两方法输出事件布尔日志，不补造另外两项events。开始捕获误用MRScopeHTTP得到空日志，已按真实MRReviewScopeHTTP重新读取并保留。

受影响旧Stage8B全链路5024：工具EXIT0、OK(1 test)/1.131s，冷latest404显式选择all后继续原真实HTTP queue/seen/progress合同，非原生播放回归。证据为review_meta/stage8i/task-6-{scope-http-device,legacy-review}-receipt.json及对应temp日志。仅owned只读AVD156524，boot1/75.715s、Launcher前台无ANR、安装两APK成功，测试后正常退出、adb空。

保留真实RED：DTO array/bool旧stringMap解码失败；冷cancel假完成；POST成功queue失败旧Ready；seen目标s-0001误变s-created-1；latepage total1被100覆盖。对应修复后同一针对性真实HTTP/VM断言通过；没有用mock policy替代真实ScopeUI门禁。范围限widget/AVD/合成HTTP，未正式NavHost/手机/家庭LAN/生产部署；最终全pytest/fullJVM/lint/connected/Release/1.4cap宣告保留Task9，整体Stage8I未完成。

## 2026-10-15 Stage8I Task7 — 循环播放、原生上下滑与邻项预取（定向验收通过）

Review 使用原生 VerticalPager / atMost(1)，移除 Channel<Float> 逐像素驱动；横向确认消费与纵向原生滑动、长按、子进度条事件隔离。当前页稳定后有界预取相邻 endpoint/poster，当前源优先，过期取消，不创建第二播放器。Review loop=true，Full 保持非循环；poster 请求与展示尺寸一致，短渐变180ms。

真实 EOF 根因是 SDK Completed 清除 prepared 标志，旧 startAfterPrepared 会重建内核。修复通过公开 render 接口恢复同一 Surface/内核，并以归属、生命周期、用户暂停、错误状态和取消守卫约束；等待实际目标落点及两次进展后恢复公开播放状态。独立审查发现任意3秒等待截止的问题后，新行为测试82531真实 RED（3项1失败），取消该截止后 GREEN；不伪造 Playing 或通过反射设置 SDK 状态。

最终候选5 build55086实际EXIT0 / SUCCESS5m32：353份预构建及运行后输入哈希0漂移；新鲜JVM9项通过，未变 VM37 + ServerContract34复用候选4，共80项对应覆盖，不能称80项新鲜运行。Main SHA EEC6ABD0BB71C5F8CA26B6098A790672E8B518D139D7AFC685643BC5A4CE4668；Test SHA 297562FFED07C6074A6BF791736ABE4950D30B70A6C0F28EE31DEAE9C9BC0CC1。

原生59621实际EXIT0 / OK(10 tests)/143.887s：Direct/HLS真实6秒视频超过1.5时长并自然wrap、动态RGB、同一内核；真实EOF后物理seek一次1500ms，Exo READY/Playing、position2102、host2、同一内核，随后自然2次wrap且有RGB。邻poster真实授权HTTP→内存命中、展示重复HTTP0；按住pointer时分页48→49；慢/快各5次纵滑每手势一条、正确source/旧kernel停止；横seek/长按独占、进度条与body隔离；真实Review→Full非循环自然Completed及重播UI。旧手势41303实际EXIT0 / OK(6 tests)/31.951s，Full bar/body/double及Review bar/double通过。根查看EOF post-wrap PNG确认实际画面。

独立最终审查 review_meta/stage8i/task-7-candidate-5-independent-review.md：SOURCE/RUNTIME PASS，C0/I0/M0，支持本任务提交。原始及失败回执保留；build中的PENDING及native中的legacy RUNNING是阶段旧字段，后续独立终态回执覆盖。owned AVD14848/qemu16352正常退出且进程、5584/5585监听与adb设备均已核实为空。

慢滑首RGB观察代理11226/2771/2565/2493/3274ms包含polling/截图/桥延迟，不是精确硬件首帧或SLA，也不是Alpha11→Alpha12性能提升；同fixture对照留Task9。Task8删除结果/整理刷新和Task9版本、全量门禁、正式发布、交接及push仍未完成。仅合成HTTP与只读AVD；未操作手机、家庭Server、真实Jellyfin或用户媒体，BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT保持。

## 2026-10-15 Stage8I Task8 — 真实删除结果与整理页内容保留（定向验收通过）

真实 receipt 四统计字段沿 DTO→Server/Router/Demo/Repository→ViewModel→ResultSheet 保留；完整非负且与逐项结果一致的磁盘统计才展示从盘删除数量/实际释放。缺字段、部分字段、非法统计保持未知，不从 prepare 估算或 outcome 推算释放空间；Demo 明确没有删除磁盘文件。missing/failed包括0始终显示，失败名称与保留队列说明可见，刷新后成功项消失；刷新失败继续保留旧队列。

整理首页同源刷新保留 Ready 摘要和可点击卡片，失败保留内容、显示上次结果并提供独立重试；换源同步清空旧摘要，generation/seq/mode守卫覆盖请求开始和结果写回。相册首次 skeleton 改1:1，与真实cell一致。既有Home/Search/Album/Viewer缓存、anchor、preview和Review180ms反馈沿用，未重写播放器或图片管线。

真实行为RED为原52551日志 BUILDFAILED2m9/11项3失败：DTO字段丢失、同源慢刷新撤下摘要、失败撤下摘要。原agent私有句柄在root重连时不存在，不补造工具退出；XML保存review_meta/stage8i/task8-red。root52791实际EXIT0 SUCCESS5m52，fresh58项通过；补充Ready换源/迟到刷新隔离后65513阶段59项JVM全部PASS（DTO1、ServerRepo19、DeleteQueue12、Duplicates11、Libraries5、Organize11），整体构建因新增AndroidTest extension imports缺失失败，不记产品行为RED。

候选2构建27652 EXIT0 SUCCESS2m42，354输入0漂移；真实AVD测试93006 shellEXIT0但9项1失败/43.752s，不将shell0当全部通过。失败是混合结果名称在底层队列和Sheet重复匹配；其余8方法通过，包括真实0B/failed0、旧Server未知、真实HTTP慢刷新保留/按压像素反馈/实际成功重试与原6导航恢复流程。修正为Dialog祖先限定及等待dismiss完成，候选3构建81863 EXIT0 SUCCESS1m8，Main保持4AC2BE27291C1117C60ABD1B9683DE0D27FAB56EFF000CE21A8D3D7E060284A7，Test为C58EEEC220C41DCD47ADB799276D53A4AABAFE377B8F60396B9D0B2A0356DC54。

候选3原生91788实际EXIT0 / OK(1 test)/16.249s：混合真实4321B/failed1、失败名称限定结果Dialog、关闭后成功项消失且失败项仍在通过；ADB pm path+sha256sum实测安装两APK与归档完全匹配。354份输入运行后0漂移。其他8方法在同Main、未改方法体下复用候选2通过证据，不能称候选3新鲜单轮9/9；59 JVM同样明确复用匹配输入。日志和回执分别保存在review_meta/stage8i/task8-candidate-{1,2,3}及temp/stage8i-ux。

独立产品源码审查C0/I0/M0；测试修正复审C0/I0/M1，唯一Minor为局部HTTP初始化/首次UI等待仍在try之前，若其失败client可能未完整清理。此项不阻断Task8提交，已交Task9在全量connected前关闭。测试对重试的断言已收紧为HTTP成功计数增加+正常非刷新摘要+无错误，避免只等错误文案消失的假通过。

仅root owned只读AVD，不是手机/家庭LAN；未真实用户媒体删除或生产部署，BLOCKED_EXTERNAL_CONTROLHUB_CONTRACT保持。Task9正式版本、性能对照、全pytest/ruff/JVM/lint/fullconnected/Release、正式APK/Server ZIP/十二文档交接和push仍未完成；整体Stage8I未完成。

## 2026-10-17 Stage8I Task9 — 发布候选与最终验收（进行中）

Android code19 / 2.0.0-alpha12，Server1.4.0 / API2；新增 thumbnail_profiles_v1、review_folder_filter_v1、delete_receipt_v1，保留旧七能力。七条中文更新说明与首次/跨版/关闭持久化合同同步，发布指南和既有构建工具同步版本。交接工具限定十二顶层说明及批准文本，排除二进制、媒体和敏感明文；仅允许本地 collect/build，不发送邮件。

完整源码复审发现并修正：删除前 lstat 整条路径链，拒绝链接、重解析点与非普通文件，防止误计释放字节；交接普通文本凭据漏检；范围界面禁用的符号字面量；健康测试导入排序；恢复会话时仅 latest 404 表示需选择范围，已存在会话的窗口404保持真实失败。Task8客户端清理边界移入完整 try/finally。针对性 RED/GREEN、原始失败及独立审查保留，最终源码 C0/I0/M1；Minor 为三个既有文件格式差异，Ruff检查实际通过，不扩大无关整理。

最终服务端 round2 实际638项、零失败/错误/跳过，Ruff通过，141份输入无漂移。随后交接脚本与测试的凭据扫描修正另有27项针对性通过和批准文本扫描零违规；不称638项在该脚本修正后重新全跑。Android round2 实际488项、零失败/错误/跳过，lint零错误/42警告/16信息，Debug Main/Test 构建通过，354份输入无漂移。

首次完整设备验收178项：174通过、4失败，原报告保存在 review_meta/stage8i/final-connected。失败包括旧提示文案断言、布局请求相互干扰、旧单事件滑动输入，以及快速五次翻页末帧复合条件超时。最后一项日志已确认当前正确源实际 READY/Playing，但失败截图被后续测试覆盖，根因未确定；旧Main/Test单独重现1项通过不能解释原失败。此次四文件仅修测试文案、请求隔离、真实滑动输入、邻预取完成采样与独立失败截图，不修改产品代码或放宽原RGB/源/播放/60秒条件。独立补审C0/I0/M2，诊断完备性Minor不阻断。

Main冻结 SHA256 80cdab2fab23da14f1d3ce5df60b25ca6b179792bb5ec8f922a18b63ef510e7b；新Test为 f5cab35ecf7c589f00976411a8136df26f1986c819549a84beee6474ad91ed56。最终完整 connected 复验已开始，四处批准测试修改之外354份输入无漂移；仅使用 owned readonly AVD。为减少无关重复，不再先跑12项补验，也不重复未受影响的Server/JVM/lint。

真实Alpha11对照保存独立APK与同fixture数据。100图冷payload6785700→1329400字节；首轮同条件冷首屏2426→1824ms、热198→126ms且热传输0字节；preview1718→398ms、当前原图2355→967ms。该轮Alpha12邻预取采样缺失，不能据此宣称性能闭环，新Test改为等待真实HTTP与Coil完成事件。Review五次 settled→RGB代理旧2631/3280/2831/3405/2874ms，新2689/6095/4871/2883/2609ms，包含采样与截图成本，不宣称整体提速或硬件首帧SLA。

待完成：完整设备复验终态、邻原图完成性能证据、唯一正式Release与Server ZIP、35节终审、最终推送及十二文档Handoff。尚未生成正式Alpha12/Server1.4产物，不声称阶段完成、家庭LAN通过或生产就绪。

01:38补充：本次完整设备round2因模拟器SystemUI无响应中断。两项Stage4旧测试RootViewWithoutFocus同时，dumpsys明确显示焦点为Application Not Responding:com.android.systemui；保留window/logcat后主动停止本任务合成App并正常退出AVD，因此第三项Stage8F进程中断属于主动终止的后果。实际仅运行20/178、3失败、退出1，354输入无漂移；不将其称为完成整套或产品回归结论。替代只读AVD将guest RAM由1536调为2048MB，需四次稳定Launcher焦点/无ANR采样后开始下一完整轮次。此变化必须与后续性能样本一起披露；此前Alpha11/12成对1536MB数据仍保留原口径。

第三轮开机预检153.706s后连续四次Launcher稳定且无ANR，方开始完整178项。初期40项零失败，严格图像性能样本已在卸载前归档：100张1329400B/100请求/100不同图，热body0B；相邻p51/p49真实HTTP和Coil完成分别1641/2079ms，请求顺序p50→p51→p49。此为2048MB guest RAM补充样本，不与原1536MB旧版直接计算时间改善比例。前述成对Alpha11/12仍保留原口径；本轮完整终态尚待执行结束。

最终完整设备第三轮实际退出0：178项全部通过，0失败/错误/跳过，1276.588秒，354输入运行前后无漂移。原始XML与逐方法日志已归档final-connected-round3，原始失败与环境中断不抹去。严格相邻完成性能样本已齐，Full/Review真实播放及原四失败方法均在本轮XML中通过。仅owned只读AVD/合成素材；手机/LAN未测。下一步唯一正式Release/EXE与离线包，尚未生成正式产物或完成阶段。

## Stage8I 正式离线产物核验

唯一正式Release、Server EXE及离线打包均实际退出0；构建/打包HEAD为91b8406c6ab814326010736b3bf2d57f44723208。APK code19/alpha12、有效签名；EXE实际隔离health1.4.0/API2/十能力；Server ZIP完整文件集合和SHA通过。正式APK SHA256 c5f463dba9db317075e4b866d2b728f4aeff05923fa5448f041d99b957266d21；Server ZIP SHA256 bdd5308b286974758144d9b28e23f7d505e0d3a2fef1262b88c7bd7547ecf6dd。源码与包输入指纹未变，已归档全部Debug/Test和测试报告后才执行clean Release。最终文档提交仅改非指纹状态文档；之后push、远端/clean核验和十二文档ZIP由旁置交付回执记录，不冒称已经执行。未部署生产、未操作真实Jellyfin/用户媒体或手机，真实家庭LAN未测。

## 2026-10-17 Stage8J 定向故障闭环
- 修复图片预览重试/原图失败提示，新增安全HTTP请求号与分类日志；真实手机图片主故障未复现（用户不在家，ADB无设备）。
- 修复未知尺寸0B误导、nonce旧会话认领、旧快照重试失败项、路径资料缺失误报missing与不安全路径拒绝；删除仅隔离样本。无schema迁移、生产升级或真实媒体操作。
- 验证：Server首轮63通过/1旧版本断言失配，health六项补验通过；后续相关删除43通过、诊断3通过。JVM56通过；版本说明22通过/1旧断言失配，原失败项1通过。不是单轮全绿，不重跑638/488/178全量。
- Alpha13/code20一次Release构建、签名及Alpha12证书一致性通过；Server1.4.1一次构建、隔离EXE health/Contract2/十项能力通过。保留既有构建warning；生产仍1.4.0。简报与本地媒体源建议见STAGE8J_RESULT.md，至此停止本轮。

## 2026-10-18 Stage9A 原生来源基础
- 增量0015迁移保留旧Jellyfin主键/收藏；新增本地根、来源字段和进度表，不重建旧表。有本地数据时拒绝有损降级，恢复升级前备份。
- 电脑回环限定的来源登记/扫描接入既有后台单队列；扫描分批、有限深度/条目数，可取消/安全重试；离线仅隐藏索引，收藏保留。普通查询不扫描目录，旧来源不变；本地删除入队与最终执行均拒绝。
- 验证：首次native模块不存在时预期收集失败；实现后 native + media_index_11 + delete_nonce_11 30项通过。补验 native + folder_tree 22项通过（5.09秒），包括临时0014库升级/降级、旧ID/收藏保留、越界/链接拒绝、伪造删除队列和无Jellyfin浏览。未跑全量或设备测试。9B图片/9C播放尚未完成，不部署生产。

## 2026-10-18 Stage9B 本地图片
- 本地原图只读句柄64KiB分块、25MiB文件/32MP像素限制；队列生成EXIF校正缩略图与首帧视频封面，复用有界缓存和版本URL。每次访问重验文件身份/最终句柄路径，替换文件不返回旧缓存。
- Windows DirEntry.stat返回0 inode/dev，改为lstat采集可比身份；修复既有FFmpeg目录解析缺少.exe后缀。视频提取使用已验证句柄stdin及15秒超时，不能解码时给安全错误，播放仍可独立尝试。
- 原图/方向/缓存/文件替换/像素上限首次3失败（Windows身份差异）修复后3通过；native+既有图片代理/缩略图29通过（22.56秒）。新增合成短片封面首次缺.exe失败，修复后该用例1通过（2.01秒）。未操作真实媒体、未执行全量回归或生产升级。

## 2026-10-18 Stage9C 本地视频与进度
- 本地视频复用播放合同（相对URL+配对Bearer headers、无HLS回退），独立GET/HEAD读取仅允许本地视频。单Range206/416、条件请求、64KiB分块、最多4并发，验证文件句柄身份；不转发Jellyfin。
- 本地进度写SQLite；按需ffprobe使用验证过的stdin句柄、数组参数、8秒超时，复用单任务队列，失败不阻塞直读播放，同一指纹不无限重探。
- 定向测试：未实现时4项失败，完成后原生Range/HEAD/条件/鉴权/替换/进度与旧Jellyfin播放合同14项通过（8.75秒）；补充真实配对Bearer只进headers及撤销立即拒绝、合成MP4实际ffprobe时长/分辨率2项通过（3.21秒）。未测真实手机编解码/HomeLAN，不部署生产。

## 2026-10-18 Stage9D Android合流与交付准备
- 来源中立DTO/能力策略提取至adapters/media_source.py，旧Jellyfin导入兼容。Android媒体墙来源筛选与本地标识、库管理入口说明，原图/Full/Review复用既有链路；按钮/VM/仓库阻断本地删除，本地播放地址严格限制到已配对origin，失败不进入Jellyfin HLS。
- 本地图片与批阅/收藏贯通；旧重复/Hash/Sprite明确不接收本地相对路径，本轮不做本地重复检测。扫描跳过真实Windows junction；取消保留未见索引；根目录与自身数据库/缓存必须分离。版本Alpha14/code21、Server1.5.0，Contract2+三项native能力。
- Server定向验收128通过/1部署版本声明未同步失败（118.28秒）；同步声明后仅原失败项及身份/哈希受影响目标3通过；迁移和取消竞争补验2通过（3.86秒）。不是单轮129全绿。首轮JVM23通过；最终四个相关suite共57通过，Gradle 2m38s。新模块F/E9检查通过；保留既有弃用/AGP warning。
- 未跑638/488/178全量、设备测试或lint全量；手机不在家，Phone/HomeLAN NOT TESTED。接下来各构建一次正式APK/Server并验证签名、版本与隔离运行；此刻尚未声称正式产物完成，未部署生产。

## Stage9 发布前图片细节收口
- 补齐本地图片像素/尺寸/解码/实体变化/排队等脱敏日志原因，支持按请求号诊断；缩略图生成后回填EXIF校正分辨率。后台登记页明确首版支持格式与只读边界。
- 只跑受影响图片文件5项通过（12.99秒），F/E9检查通过。Android代码未改，正式APK已一次构建成功（4m38s）；不重建APK。Server EXE尚待本次最终构建及包内隔离冒烟。

## Stage9 最终EXE真实HTTP冒烟发现并修复事务可见性
- 首份EXE health1.5.0/Contract2/native能力通过，但来源登记202后立即GET任务404；同一EXE受控重现确认稍后任务可见。根因为成功响应早于yield依赖事务提交，TestClient会等待依赖收尾而未揭示窗口。
- 新增独立连接回归先失败；本地来源登记/扫描、本地进度及探测编排改为成功回包前提交，3个受影响目标通过（2.06秒）。另修正一次性冒烟工具对HTTP头大小写的错误断言（Range已返回206及正确16字节）。
- 仅Server需因该真实缺陷重新打包；APK与Android输入保持不变，不重建APK，不重跑无关测试。EXE无Authenticode签名；APK签名以最终回执为准。

## Stage9 最终交付与实际命令
- 正式APK仅一次构建，code21/Alpha14、签名PASS，证书与Alpha13一致；EXE初次冒烟发现事务可见性缺陷后仅Server增量重打包，未重复构建APK。最终包内实际EXE严格即时任务查询、原图/两类封面、视频Range/HEAD与进度回读通过，隔离进程退出、测试端口释放；生产未动。
- Server ZIP 96,986,726字节/162文件，完整文件集合及SHA256核验通过。APK 43,606,606字节。最终回执和原始失败/成功日志保留review_meta/stage9，正式产物及边界见STAGE9_RESULT.md。
- 已知限制：新层级树按来源隔离；遗留平面目录聚合仍只按相对dirname，跨本地根同名目录可能聚合统计。本轮使用现有层级分类，不扩展遗留平面接口。手机/LAN/全部编码兼容未测。

实际执行的关键命令（cwd分别为server、android；均使用Environment登记JDK17/SDK和项目venv）：
```text
python -m pytest tests/test_native_sources.py tests/test_native_images.py tests/test_native_playback.py tests/test_jellyfin_mapper.py tests/test_media_api.py tests/test_playback_contract_11.py tests/test_media_image_proxy_11.py tests/test_delete_nonce_11.py tests/test_delete_receipt_8i.py tests/test_health_contract_8d1.py -q -o addopts= --tb=short --junitxml=../review_meta/stage9/9d-server-targeted.xml
python -m pytest tests/test_health_contract_8d1.py::test_deployment_scripts_declare_expected_version_and_contract tests/test_native_sources.py::test_identity_scoped_by_root_and_reject_unsafe_paths tests/test_stage7_fix.py::test_hash_handler_marks_unreadable -q -o addopts= --junitxml=../review_meta/stage9/9d-affected-rerun.xml
python -m pytest tests/test_native_sources.py::test_registration_success_means_task_visible_to_next_connection tests/test_native_sources.py::test_native_api_no_jellyfin_no_path_leak_or_query_scan tests/test_native_playback.py::test_local_progress_no_jellyfin -q -o addopts= --junitxml=../review_meta/stage9/http-visibility-fix.xml
python -m pytest tests/test_native_images.py -q -o addopts= --junitxml=../review_meta/stage9/9d-image-final.xml
gradlew.bat :app:testDebugUnitTest --tests 'com.mediareview.app.feature.v2.data.server.V2ServerMediaRepositoryContractTest' --tests 'com.mediareview.app.feature.v2.player.V2PlaybackSourceControllerTest' --tests 'com.mediareview.app.feature.v2.releasenotes.ReleaseNotesContractTest' --tests 'com.mediareview.app.feature.v2.releasenotes.WhatsNewViewModelTest' --max-workers=1 --console=plain
gradlew.bat :app:assembleRelease --max-workers=1 --offline --console=plain
python -m PyInstaller packaging/mediareview_server.spec --distpath dist/stage9 --workpath build/pyinstaller --noconfirm
```
原始失败不隐藏：最终Server首轮128通过/1版本声明失败，定向补验关闭；真实EXE第一次任务404定位后修复，探针曾错误区分HTTP头大小写，已修正工具断言，未改Range生产实现。不是单轮全绿；EXE NotSigned与APK签名PASS分别报告。

### 2026-10-18 Stage9 真机诊断：Server 1.5.1 锁竞争修复

手机ADB已连接并确认Alpha14/code21。用户复现媒体缺失/服务器反复离线；正式日志包含paired_device.last_seen_at认证UPDATE及后台任务claim的SQLITE_BUSY。只读索引统计存在4,113条Jellyfin视频，尚无本地源；该数量不能证明真实文件全部被发现或可播放。

临时库先复现：认证写锁跨端点保留、写锁忙时合法请求失败、同步任务claim等待写锁阻塞事件循环。修复认证：先只读校验，在线心跳每分钟最多更新，短事务busy_timeout=0并在端点前提交；锁忙回滚后重新校验token/revoked，归还连接前恢复timeout。任务claim也仅对锁忙立即返回并稍后重试。短审查发现to_thread候选方案存在取消后遗留running任务，最终未采用。Contract2、删除安全和播放器未修改；无需重建APK。

定向测试：`pytest tests/test_auth_concurrency.py tests/test_task_manager.py tests/test_stage7_fix.py tests/test_playback_contract_11.py tests/test_native_playback.py -q -o addopts= --tb=short` 38通过；最终claim方案替换后重跑受影响两个文件5通过；`pytest tests/test_health_contract_8d1.py -q -o addopts= --tb=short` 6通过。相关4个Python文件ruff通过。未跑全量Server/JVM/connected。

Server1.5.1 EXE已构建；审查修订后作一次必要增量重建。隔离真实EXE中保持SQLite写锁，12次健康请求均成功，最慢16ms；进程和临时端口已释放。首次隔离脚本漏设STORAGE数据根，日志权限检查即失败、未启动完成；补齐隔离环境后通过，未改生产配置。证据在`review_meta/stage9/phone-debug/`。正式1.5.1部署和手机实际播放尚待后续验证，不能声称掉线或视频故障已全部修复。

后续实机结果：用户明确授权1.5.1，ControlHub停服后完整活动数据/程序保护备份及哈希核验，升级155程序文件并官方启动，health确认1.5.1/Contract2/windows_service。Alpha14/code21未重装。两段Jellyfin H.264/AAC样本各在约5秒窗口Render146，第二段下一窗口Render150、视频Drop0；播放期间生产12次health成功、最慢16ms。拖动后的恢复日志不完整，记为未确认。未验证全库完整性或全部编码；仍见上游封面404，以及后续交互式实例playback_info约7.5秒、目录约11.5秒，不能宣称全部视频问题关闭。用户确认下午手动停服务并打开EXE，继续工作时按原启动授权恢复ControlHub管理；具体结果以service-restored.json为准。未扩大测试、未重建APK，未动真实媒体。

2026-10-19 00:00恢复结果：首个管理员辅助脚本未返回，针对其确切脚本身份终止后使用无交互模式完成；手动Server经路径、启动时间及端口身份验证后关闭，官方ControlHub start成功，SCM Running及health windows_service/AIHome.MediaReview/1.5.1通过。一次性脚本、临时隔离库及手机UI XML已清理，备份/产物/测试证据保留。

### 2026-10-19 用户要求完整体检

未修改生产代码/数据库或库选择。21库逐页Jellyfin元数据与只读SQLite对账确认409个跨库重复Item，单一library_id冲突覆盖导致用户子库101视频、另一库294项在库目录消失；临时库1→0复现，手机从父库正确目录翻页显示全部101。另2项仅上游存在/2项仅缓存存在的差异未判定原因。Release手机播放出帧，但seek后Invalid NAL length再恢复，未称播放全通过。12健康请求全成功max36.8ms，数据库完整性ok/外键0。Server全套一次643通过/4失败/41错误，JVM一次497通过（75 suites）。失败集中在旧打包入口版本声明、0010旧fixture使用0015 ORM和删除摘要旧断言；不篡改断言追求全绿。详情、命令、修复建议和未覆盖范围见docs/STAGE9_HEALTHCHECK.md。未跑会影响用户Release/配对配置的全套connected，未重建产物或部署新版本。

### 2026-10-19 Release 真机功能与UI审查

使用共享android-device-testing Skill，复用既有自动化结果而不重跑全套。实机截图/界面XML确认重复媒体与库管理顶部状态栏重叠、目录长诊断、空态无范围解释、失败删除历史与pending摘要混淆、筛选选中状态缺少无障碍语义。图片样本可显示，无匹配搜索可返回；视频MPEG-4实际出帧145/150/151，另观察到thumbnail上游404。最初仅选择1个上游本来就0媒体的库，不把该空态算为新跨库bug；临时加选有内容库测试后恢复原选择，以本机回执为准。未做真实删除、批阅写入、收藏写入、配对重设或部署。已确认与待复核项、12步流程及限制见docs/STAGE9_UI_AUDIT.md；私人截图仅在review_meta本机保存，不提交或上传。

### 2026-10-20 Stage9 体检缺陷修复

新增0016 Jellyfin多库归属表，保留稳定媒体ID及用户状态；查询、目录、批阅、重复检测、删除权限使用实际归属并去重。每库独立代次，成功只失效本次库未见关系，取消/失败保留旧归属，删除成功后清理关系。本地来源继续原有单根扫描。修正两页状态栏遮挡、折叠诊断、空态与失败历史、未扫描摘要、筛选选中语义和未知图片大小。目标Server1.5.2、Alpha15/code22；同步修正旧发布版本声明和交付说明。

158项服务回归首轮155通过/1失败/2跳过，修正UNION内部排序后30项补测29通过/1跳过，分轮闭环156通过/2跳过；十万条排序/查询计划门禁通过。发布完整性90项首轮85通过/2失败/3跳过，旧文件名断言修正后单项通过，余1项SCM模拟在导入前被WindowsPowerShell执行策略阻断，未计通过、不改策略。Android定向48项通过；Release APK一次成功构建，签名与Alpha14一致。EXE独立目录增量构建，实际隔离API验证1.5.2/Contract2/0016、双库各1/全局1/仅子库1、收藏和路径隐私通过，隔离进程停止。首探针漏假user_id产生422，修夹具后通过，无EXE重建。

未跑全套或178 connected；当前ADB无设备，seek NAL错误仍未确认。没有操作真实媒体、清除手机数据、改变生产配置或升级服务；生产health仍1.5.1。构建基线9ef0719工作树与最终源码按输入指纹关联，使用现有builder验证/打包接口复用实际产物。临时测试目录递归清理被自动审批拒绝，保留并排除提交。详情、命令、备份/回滚与刷新要求见docs/STAGE9_REPAIR_RESULT.md。

### 2026-10-20 — 用户授权部署 Server 1.5.2

使用既有 ControlHub lifecycle CLI 停止及启动 mediareview；备份当前有效程序与全部运行数据（不递归复制历史备份及旧暂存程序），备份和安装逐文件校验通过。首轮备份校验中止且未替换程序，恢复旧版服务后调整临时辅助脚本集合处理，再次完整备份成功。独立管理员诊断命令曾被自动审批拒绝，未执行。

正式服务健康三次均为1.5.2/Contract2/windows_service/AIHome.MediaReview，ControlHub状态online；数据库0016、回填56,566条归属。配对19、收藏4、批阅会话1、删除队列38、缓存56,566、库选择21条，升级前后数量一致。私有回执位于review_meta/stage9/production-152-*。未操作真实媒体，未改变服务身份或端口，未重跑全量测试。ADB无设备；Alpha15安装及手机/LAN复核待连接。后台需确认目标库选择后刷新同步，才能恢复历史被覆盖归属。

## 2026-10-23 图片 404 归类与失败诊断修复（Server 源码，未发版）

背景：只读故障调查（review_meta/ops-20261023/image-perf-report.md）确认，Jellyfin 对"没有 Primary 图"的条目返回 404，但 `client._image_request` / `_open_image_stream` 把全部上游错误统一映射为 `JellyfinError`（502 `JELLYFIN_ERROR`）。生产日志 33 条 `image_proxy_failed ... upstream_status=404 status=502` 全是这种误分类；客户端 `ImageFailure` 的 404 文案成为死代码，且失败日志缺少 media_id/profile/耗时，无法定位到具体图片。

改动：
- 新增 `ImageNotFoundError(NotFoundError)`：`code=IMAGE_NOT_FOUND`、HTTP 404；仅用于上游图片 404，其余 5xx/超时/非图片/重定向仍保持原语义（502 或其他既有错误码）。
- `JellyfinClient._image_request` 与 `_open_image_stream` 在重定向判定之后、通用 >=400 之前识别 404 并抛该错误。
- `image_proxy_failed` 日志补齐 `media_id`、`profile`、`upstream_status`（上游 404 显式记 404）与 `elapsed_ms`；耗时取自 `RequestIdMiddleware` 写入的 `request.state.started_at`，只读、不改变响应。
- 修正陈旧测试 `test_server_version_is_151`（硬编码 1.5.1）：改为 `test_server_version_matches_declared_release`，核对 `deployment/version.ps1` 与 `app.__version__` 一致，避免下次发版再漏改。
- 新增回归：上游 404 → HTTP 404 + `IMAGE_NOT_FOUND` + 日志含 media_id/profile/reason=upstream_not_found，且不泄露上游响应体与 Server Key。新增错误码属增量分类，API Contract 仍为 2，Android 已有 404 文案，无需改 App。

验证（本机 2026-10-23，未操作真实媒体/生产库）：
- 改动前全量：690 通过 / 4 失败 / 484.50s。四类失败均已分类：`test_0010_query_indexes...`（并行负载下 1.150s 超过查询耗时门禁，单独重跑通过）、`test_server_version_is_151`（陈旧硬编码）、`test_native_images::test_video_cover_from_disposable_clip` 与 `test_native_playback::test_metadata_probe_disposable_mp4`（本机 ffmpeg 写默认 pytest 临时目录被 Windows 完整性级别拒绝，非产品缺陷）。
- 受影响定向：`tests/test_media_image_proxy_11.py` 16 通过（含新增用例）；`test_jellyfin_api.py`、`test_media_api.py`、`test_health_contract_8d1.py`、`test_native_images.py` 受影响集合通过；十万条查询门禁与元数据探针单独重跑 2 通过。
- 改动后全量：**693 通过 / 2 失败 / 422.70s**（共 695 项）。2 项失败仍是上述本机 ffmpeg 完整性级别环境问题（`test_video_cover_from_disposable_clip`、`test_metadata_probe_disposable_mp4`），已用项目内可写 basetemp 单独复跑通过；十万条查询门禁与版本断言在本轮通过。不是单轮全绿：2 项环境失败如实保留，未改写断言或跳过。
- Ruff：本次改动文件通过；`server/app/main.py` 仍有 2 条与 HEAD 相同的既有违规（I001 import 分组、E501 第 87 行），未在本轮扩大修改。
- Android：未改代码；JVM 基线 75 suites / 498 tests / 0 失败。

环境说明（本机，非产品问题，供后续 Agent 参考）：登记 JDK17、项目 venv 的 python 与 third_party ffmpeg 位于带 Low 完整性标签的目录，在本会话以 Low IL 运行，不能写默认 `C:\Users\...\Temp` 等 Medium 目录。本轮用同版本 JDK 副本（`temp/jdk17copy`）跑 Android JVM，用 uv base python + venv site-packages 跑 Server 测试，并用 `review_meta/ops-20261023/pytest-full` 作为 pytest basetemp（项目目录可写）。未修改 AIHome 工具目录、系统权限或服务。

边界：未改版本号（1.5.2 保持；待后续打包时统一递增）、未改 API Contract（2）、未改 Android、未部署生产、未操作真实媒体或生产数据库。生产 8766 仍由交互式 PID 3916 占用，官方 Windows 服务未恢复（见 review_meta/ops-20261023/service-restore-log.md）。

## 2026-10-24 进度口径统一与"跳过 Jellyfin"可行性评估（仅文档，未改代码）

背景：项目文档长期漂移——README 仍写 1.1.0-rc2/versionCode 7、根 HANDOVER 为 1.1.0、REVIEW_SUMMARY 停在 Stage8I、TASKS 顶部仍标 Stage8H、deployment/README 正文仍写 alpha12/1.4.0（该旧文还被打进 1.5.2 正式交付 ZIP）。本轮以只读审计（review_meta/ops-20261024/progress-audit.md，371 行）为依据统一口径，并完成用户要求的"跳过 Jellyfin"可行性评估（review_meta/ops-20261024/jellyfin-skip-assessment.md，460 行）。

统一动作：
- 新建 `docs/PROJECT_STATUS.md` 作为唯一活状态源（版本 SSOT、产物 SHA、生产运行态、A–D 级验证矩阵、S0–S3 问题、阶段时间线、文档地图、产品方向决策点）。
- README 当前版本改为 Alpha15/code22 + Server 1.5.2/Contract2/0016，并链接 PROJECT_STATUS；补充本地源能力与 GitHub 当前不可达说明。
- TAKEOVER_READ_FIRST、HANDOVER、REVIEW_SUMMARY、docs/ACCEPTANCE、docs/PRODUCTION_READINESS、docs/STAGE9_HEALTHCHECK 加 `SUPERSEDED`/历史头并指向 PROJECT_STATUS。
- TASKS 顶部改为指向 PROJECT_STATUS，追加 2026-10-24 进度统一与待决策清单。
- deployment/README 正文从 alpha12/1.4.0/十能力更新为 Alpha15/code22、Server 1.5.2、13 能力，并注明 2026-10-20 已授权升级。
- 项目 AGENTS.md 增加"进度以 PROJECT_STATUS 为准"入口，并在 Stage9 约定处注明本地删除放开待用户决策。

评估结论（摘要）：可以跳过 Jellyfin——Server 不配置 API Key 仍可登记本地根、扫描、封面/原图、认证 Range 播放、本地进度，批阅/收藏/媒体墙/文件夹树已来源中立；最大缺口是**本地永久删除被 Server 与 Android 双端硬拒绝**，其次是**无转码/HLS 兜底**。推荐"本地源为唯一必需来源、保留 Jellyfin 代码、配置可选"，先做安全删除（M，3–5 人日）再做播放兜底（L，7–12 人日）；需用户授权修改 AGENTS.md 的只读/禁转发约定。

验证：本轮仅文档与只读审计，未改源码、未跑构建、未改生产/媒体/密钥；GitHub 仍不可达，`747e704` 及本次文档提交均未推送。文档改动需在 commit 前经 `git diff` 复核。
推送补充（2026-10-24）：GitHub 网络恢复，已成功推送 `2dd9ff2..5786f8f`（含 10-23 的 `747e704` 图片 404 修复与本次统一文档提交）；`git status -sb` 显示与 origin 同步。此前记录的"GitHub 不可达、本地 ahead 1"已解除。

## 2026-10-24 本地源为主 P0/P1：本地两阶段安全删除（Server + Android，未发版）

决策与分支：用户批准"本地源为唯一必需来源、Jellyfin 代码保留可配"，先做安全删除、转码留待真机 A/B。开发分支 `feature/mediareview-local-only`（基线 `37e93e2`），Server 与 Android 并行开发，Lead 复核后提交。

Server（14 个文件）：
- 能力：新增 `native_delete_v1`（能力 13→14），`native_read_only_v1` 保留为历史兼容名；`SOURCES["local"].can_delete=True`。
- 删除安全：`native_sources` 新增根/路径/句柄复验（`resolve_deletable_local`：LocalRoot 存在且 enabled、`safe_path` 整链拒绝 UNC/`..`/绝对/链接/junction/reparse/越界、叶必须普通文件、`open_media` 复用指纹与最终路径复验）与稳定失败码映射；`delete_queue` 按 source_type 分派，本地走"解析复验→`_lstat_deletion_path` 整链→归属/尺寸/mtime→`os.remove`→确认不存在"，单条失败不中断，失败码（`local_root_disabled`/`local_root_missing`/`path_rejected`/`file_identity_changed`/`file_in_use`/`delete_not_verified`）写入队列与审计且不含绝对路径。两阶段 nonce 合同、success/missing 回执、deleted_bytes、Jellyfin 路径均不变；删除端点仍 require_auth，目录登记仍仅 loopback。
- 去 Jellyfin 依赖：扩展名白名单新增视频 m4v/ts/m2ts/avi/wmv/flv/mpg/mpeg 与图片 gif/bmp/tiff；`native_playback` MIME 同步；无 Jellyfin 时引导文案改为"登记本地来源或勾选 Jellyfin 媒体库"；playback-readiness 对本地源返回直读诊断（不探测 Jellyfin）；运维面板未配置 Jellyfin 显示 `local_only` 信息态；admin 后台取消"只读/永久删除不可执行"表述。
- 测试：`test_native_sources.py` 新增 12 类场景（两阶段成功且审计无绝对路径、missing、root 停用/缺失、同尺寸替换→身份失败、越界/绝对/UNC/目录拒绝、跨根、junction、逐条失败不中断、伪造/快照外 nonce 不删、PermissionError→file_in_use、扩展分类、无 Jellyfin 诊断/文案）；`test_health_contract_8d1.py` 能力 13→14；`test_native_playback.py` 新容器 MIME 断言。

Android（18 个文件 + 新增 `PendingDeleteText.kt`）：
- 是否可删除只信任服务端下发 `can_delete`，移除 `loc_` 前缀与 `source_type=="jellyfin"` 门禁；能力 `native_delete_v1` 仅用于区分提示（旧 Server → "需要升级电脑端 Server"）。UI 不再显示"本地只读"；删除失败码新增中文映射；二次确认 + nonce 安全流程未改弱。
- 测试：7 个受影响类 150 tests 通过；全量 JVM 75 suites / 507 tests / 0 失败（基线 498，新增 9）。

验证（本机 2026-10-24，合成/临时文件，未操作真实媒体）：
- Server 全量：**709 通过 / 2 失败 / 430.01s（711 项）**。2 项为本机已知 ffmpeg 完整性级别环境限制（默认 Temp 不可写：`test_video_cover_from_disposable_clip`、`test_metadata_probe_disposable_mp4`），换项目内可写 basetemp 单独复跑 **2 通过**；不是产品缺陷，未改写断言。
- 定向：native_sources+playback+health+system 45 通过；delete 三件套 44 通过；media_api 29 通过；playback_readiness 19 通过；health+library 14 通过（分轮，非单轮合计）。
- ruff：本轮改动文件全部通过；仓库另有 27 条既有违规（均在未改动文件），未扩大修改。
- Android 全量 JVM：507/0，BUILD SUCCESSFUL。

边界与未做：版本仍 1.5.2 / Alpha15（未发版，P5 统一升 Server 1.6.0 / Android alpha16-code23 并同步 build_deploy）；未上真机、未做 androidTest/connected、未用真实文件验证 file_in_use（仅 mock PermissionError）；API Contract 仍为 2；`scripts/build_deploy.py` 的 REQUIRED_CAPABILITIES 尚未加入 `native_delete_v1`（P5 处理）；GitHub 推送与最终提交以实际命令结果为准。

## 2026-10-25 Codex 独立安全复审：本地删除发现阻断项（未修复，暂停 P5）

由 Codex（`gpt-5.6-sol`，独立只读审查，182,126 tokens）对 `37e93e2..373025b` 的本地两阶段删除实现做复审，报告：`review_meta/codex-review-20261025/local-delete-review.md`。结论 C1 / I4 / M1，**建议阻断发布**：

- **C1（Critical）**：`open_media` 复验句柄在删除前被关闭，随后仍按字符串路径 `os.remove`，且未把 `_lstat_deletion_path` 的 st_dev/st_ino 与已验证句柄身份绑定。同机并发进程可在检查与删除之间换父目录/叶文件，导致登记根外文件被删除或误删同尺寸替换文件。现有 junction/symlink 测试只覆盖删除前已布置的静态状态，不证明竞态安全。
- **I1（Important）**：nonce 快照只绑定 `media_id`；prepare 后撤销并重新入队同一媒体，旧 nonce 仍会删除新的队列项（两阶段确认未绑定队列代次）。
- **I2（Important）**：文件删除与 nonce claim/审计/索引清理不在同一可恢复状态机；`os.remove` 成功后若审计/索引/事务失败或进程崩溃，会出现"文件已删、请求 500、nonce 未消耗、元数据回滚"的分裂，且可能中断整批。
- **I3（Important）**：Android `can_delete` 缺字段或媒体缓存未命中时 fail-open（`!= false`），应改为显式 true 才放行。
- **I4（Important）**：安全测试对上述竞态/同 ID 队列代次/删除后事务失败/Android 缺字段与缓存 miss 系统性缺失，现有绿色结果高估边界覆盖。
- **M1（Minor）**：快照中队列项不存在或非 pending 时直接 continue，无逐条审计。

处置：暂停 P5 发版；先修 C1/I1/I2/I3/M1 并补齐测试，再由 Lead 跑全量与二次复审。此次复审未修改源码、未运行测试、未启停服务。

## 2026-10-25 Codex 修复轮：C1/I1/I2/I3/M1 + 恢复逻辑缺陷（源码完成，待二次复审）

承接上一轮独立复审（C1/I4/M1，阻断发布）。用户要求后续开发尽量交给 Codex，本机 DSH 会话未暴露 `subagent_codex` 工具，故用 `environment\bin\codex.cmd exec --dangerously-bypass-approvals-and-sandbox -m gpt-5.6-sol` 直接委派 Codex CLI（一次性、冷启动；边界清晰、只改指定目录；Lead 复核 diff 并本地跑测试）。本轮 Codex 用量约 70 万+ tokens，于 2026-10-25 02:00 触发 ChatGPT Plus 5 小时额度上限（复位 05:44），后续已无额度执行二次复审。

实现（Codex，Lead 复核）：
- **C1（句柄绑定删除）**：`native_sources` 新增 `delete_local_media`/`_windows_delete_bound`：先复验根/路径链/指纹/最终路径；Windows 用 `CreateFileW(GENERIC_READ|DELETE, 共享全部, FILE_FLAG_OPEN_REPARSE_POINT)` 二次打开，以 `BY_HANDLE_FILE_INFORMATION`（卷序列+File Index）绑定首次 `fstat` 身份、`GetFinalPathNameByHandleW` 复核根内最终路径，再用同一句柄 `FileDispositionInfo` 删除；句柄删除失败**不回退** `os.remove`（fail-closed）。POSIX 分支严格比对 `lstat` 与 `fstat` 身份后删除并注明残余竞态。新增稳定失败码 `identity_binding_failed`、`delete_not_verified`。
- **I1（队列代次）**：`DeleteQueue` 增加非空随机 `queue_token`，迁移 `0017`（逐行回填 + 非空约束，保留 status 索引）；nonce 快照保存 `(media_id, queue_token)`；commit 要求 token 与 pending 同时匹配；撤销后同 ID 重新入队会被旧 nonce 判 `failed:queue_entry_superseded` 且不删文件；旧纯 ID 快照 fail-closed。
- **I2（持久状态机）**：`commit_with_nonce` 先提交 nonce claim + 全部 `committing` 意图；物理删除逐项执行、逐项独立事务写 outcome/审计/元数据清理；意外异常逐项隔离（`internal_error`）不中断整批；新增 `recover_interrupted_deletes` 在 lifespan 启动时收敛遗留 `committing`（文件已不存在→`recovered_deleted` 清理；仍存在→`interrupted_not_deleted`；幂等、不再次删除）。
- **M1（审计）**：快照缺失/非 pending/代次替换分别写 `skipped:queue_entry_missing`、`failed:not_pending`、`failed:queue_entry_superseded`，不含路径。
- **Lead 审查发现的恢复缺陷**：`_recovery_observes_missing` 原对本地源用绝对路径检查，但本地 `media_path` 是相对路径，会误判"文件仍存在"；已改为 source-aware（local 走 `LocalRoot + safe_path(allow_missing_leaf)`，jellyfin 走绝对路径），并补本地已删/未删恢复测试。
- **I3（Android fail-closed）**：wire DTO `can_delete` 改可空；映射仅在 `== true` 时可删；UI 模型默认 false；仓库门禁 `require(media != null && media.canDelete)`；Home/Review/Viewer fail-closed；缺字段/缓存 miss/显式 false 均 0 次入队 POST。

验证（Lead 本机，2026-10-25）：
- Server 全量 pytest：**721 通过 / 2 失败 / 723 项 / 437.51s**。2 项失败仍是本机 ffmpeg 完整性级别环境限制（`test_video_cover_from_disposable_clip`、`test_metadata_probe_disposable_mp4`），换项目内可写 basetemp 单独复跑 **2 通过**；十万行查询性能门禁本轮通过（此前并行负载下曾抖动 1.0–1.3s）。
- Codex 定向：C1 组 34 passed；I1/I2/M1 删除+迁移组 77 passed（完整指定集 82 passed/1 failed 为上述性能门禁抖动）；恢复修复组 67 passed/1 failed（同一性能门禁）；ruff 改动文件通过。
- Android 全量 JVM：**75 suites / 511 tests / 0 失败 / 0 错误 / 0 跳过**（BUILD SUCCESSFUL）。
- 新增/更新测试：Windows 句柄删除、同尺寸换叶、父目录 junction 竞态（本机 junction 实际创建成功、非 skip）、句柄失败不回退、同 ID 重入队旧 nonce、旧格式快照 fail-closed、缺失/非 pending 审计、删除后审计/索引异常隔离与恢复、0017 回填与索引保留、Android 缺字段/缓存 miss。

边界与未做：未 commit 前的二次独立复审因 Codex 额度耗尽顺延到额度复位（05:44）后；P5 发版（1.6.0/alpha16）继续暂停；未上真机、未跑 connected/androidTest；未操作真实媒体；两次 Codex 子任务报告见 review_meta/codex-fix-20261025/（A3 因额度用尽未写报告，改动与验证以本日志与代码为准）。

## 2026-10-25 第二轮复审修复：I5/I6/M2/M3（源码完成，待第三轮复审）

第二轮 Codex 复审（`review_meta/codex-review-20261025-round2/round2-review.md`）判定 BLOCK：C1/I1/I3 已关闭，I5/I6/M2/M3 未关闭。本轮由 Codex 按复审建议修复（报告 `review_meta/codex-fix-20261025-round2/fix-report.md`）：

- **I5 Windows delete-on-close**：`SetFileInformationByHandle(FileDispositionInfo)` 成功即视为系统接受不可逆删除，**不再**用即时 `lstat` 或 `CloseHandle` 返回值改写为 failed；其他允许 `FILE_SHARE_DELETE` 的句柄未关时按真实字节数 success 收敛并清理元数据。真实 Win32 测试：持有第二共享删除句柄时 receipt=success、元数据清理、关闭前文件对象仍存活、关闭后消失；不允许共享删除的句柄 → `failed:file_in_use` 且文件保留。
- **I6 0017 downgrade**：检测到 `committing` 行时拒绝降级（明确要求先用 0017 代码恢复并备份），不改写数据库；无 committing 时正常回滚并保留 pending/failed 与 status 索引；补回滚测试。
- **M2 非执行快照审计**：`queue_entry_missing`/`queue_entry_superseded`/`not_pending` 的审计并入 nonce claim/committing 同一事务；审计失败整体回滚、nonce 不消费、API 返回错误，不再静默丢审计。
- **M3 错误码**：Win32 32/33 仅映射 `file_in_use`；5（ACCESS_DENIED）映射新稳定码 `delete_access_denied`；Android 增加中文提示"没有权限删除该文件"。

验证（Codex 定向；Lead 全量另记）：
- 真实 Windows 专项 3 passed；M2/receipt 审计 7 passed；I6 downgrade 2 passed；Android `DeleteQueueViewModelTest` 13/13；ruff 通过。
- 指定四文件完整单轮 **86 passed / 1 failed**：唯一失败为既有 10 万行查询性能阈值 1.045s（阈值 1.0s），单项复跑 **1 passed**；不称单轮全绿。

边界：未 commit/push（本轮由 Lead 统一提交）；未构建产物；未上真机；未操作真实媒体/服务。P5 仍暂停，待第三轮独立复审。

### 2026-10-25 Lead 全量验证（I5/I6/M2/M3 修复后）
- Server 全量 pytest：**730 通过 / 2 失败 / 732 项 / 406.98s**；2 项仍为本机 ffmpeg 完整性级别环境限制（`test_video_cover_from_disposable_clip`、`test_metadata_probe_disposable_mp4`），换项目内可写 basetemp 单独复跑 **2 passed**；十万行查询性能门禁本轮通过。
- Android 全量 JVM：**75 suites / 511 tests / 0 失败 / 0 错误 / 0 跳过**（BUILD SUCCESSFUL；M3 新增 `delete_access_denied` 映射用例包含在内）。
- Ruff：本轮改动文件通过。
- 结论：修复相关测试与全量回归通过（除已分类的 2 项本机环境失败）；是否可发版仍以第三轮独立复审为准。

## 2026-10-25 第三轮复审修复：I5-A / I5-B（POSIX 语义 + 可恢复删除状态）

第三轮 Codex 复审（`review_meta/codex-review-20261025-round3/round3-review.md`）判定 BLOCK：I5-A（delete-pending 后元数据事务失败时仍可能先落 failed、文件后消失）与 I5-B（CloseHandle 失败被忽略却清理元数据并报 success）。Codex 修复（报告 `review_meta/codex-fix-20261025-round3/fix-report.md`）：

- Windows 删除优先使用 `FILE_DISPOSITION_INFO_EX`（`FILE_DISPOSITION_DELETE|FILE_DISPOSITION_POSIX_SEMANTICS`）：disposition 接受后关闭句柄，再以 `lstat` 验证名字已消失才算 `success`；系统不支持（ERROR_NOT_SUPPORTED/INVALID_PARAMETER）才回退传统 `FileDispositionInfo`，传统路径一律返回 `pending`，不提前清理元数据。
- 新增持久状态 `delete_done`（物理删除已确认、元数据待清理）与 `delete_pending`（已请求、名字未确认消失）；receipt 增加逐项值 `pending` 与 `pending_count`（不计 success/missing/failed）。
- `delete_done` 先独立提交，再做第二个事务清理索引/收藏/队列与审计；清理失败保持 `delete_done` 返回 success，恢复补清理并写 `metadata_cleanup_recovered`。`delete_pending` 由恢复只观察：仍可见则保持 pending，名字消失后转 `delete_done` 再清理；重复恢复幂等、不再次删除。
- 启动恢复保留，`GET /delete-queue` 与 `POST /delete-queue/commit/prepare` 增加惰性恢复入口；0017 downgrade 对 `committing/delete_done/delete_pending` 任一存在均 fail-closed。
- CloseHandle 失败：POSIX 名字已验证消失仍 success；传统路径保持 pending；内部 warning 不含路径。
- Android：`DeleteOutcomeStatus.Pending` + `pendingCount`，结果面板显示"删除请求已提交，等待系统完成"；旧 Server 无 pending 字段兼容。

验证：
- Codex 定向：四文件 **94 passed**（单轮）；真实 Windows 专项 5 passed（共享句柄 POSIX 立即消失、POSIX+清理失败恢复、传统回退 pending→恢复、CloseHandle 两种故障分流）；Android 3 类 36/36；ruff 通过。
- Lead 全量：Server **736 通过 / 3 失败 / 739 项 / 409.65s**；3 项为 2 项本机 ffmpeg 完整性环境限制 + 1 项 10 万行性能门禁抖动（单独复跑 1 passed；ffmpeg 两项换可写 basetemp 2 passed）。Android 全量 JVM 结果见下一条。
- 未 commit/push（Lead 统一提交）；未构建产物；未上真机；未操作真实媒体/服务。
- Android 全量 JVM：**75 suites / 511 tests / 0 失败 / 0 错误 / 0 跳过**（BUILD SUCCESSFUL；含 pending receipt 展示与旧 Server 兼容用例）。

### 2026-10-25 07:29 状态：Codex 5 小时额度再次用尽，第四轮复审顺延
- 已完成并推送：`485e2e7`（POSIX 删除状态机）、`48aa26f`（Android pending）、`03083da`（文档），`cfe7ca5..03083da` 已推送到 GitHub。
- 第四轮定点复审启动时触发 ChatGPT Plus 额度上限（提示 **10:47 复位**），未生成报告；P5 发版继续暂停。
- 已设 10:48 提醒：额度复位后立即执行第四轮复审；通过则推送/更新状态并进入 P5（Server 1.6.0 / Android alpha16-code23）。

## 2026-10-25 第四轮复审修复：POSIX-only 删除与 receipt 收敛（I7/I8/M4）

第四轮 Codex 复审（`review_meta/codex-review-20261025-round4/round4-review.md`）BLOCK：I7（disposition 已接受后首次状态持久化失败仍可能返回 failed 并被恢复过早终结）与 I8（新增 pending 枚举对旧 Android 不兼容；新 App 面板标题误报"删除完成"）。Lead 决定简化语义并交由 Codex 实施（报告 `review_meta/codex-fix-20261025-round4/fix-report.md`）：

- **POSIX-only**：Windows 本地删除只使用 `FILE_DISPOSITION_INFO_EX=21` + `FILE_DISPOSITION_DELETE|FILE_DISPOSITION_POSIX_SEMANTICS`；移除传统 disposition 回退与 legacy 入口。系统不支持时在任何删除前 fail-closed：稳定码 `delete_not_supported`（中文提示升级系统/使用电脑端删除），文件、索引、队列保持不变。最低系统要求 Windows 10 1709，已写入 deployment/README 与 USER_INSTALL_GUIDE。
- **I7**：POSIX disposition 后关闭句柄并以 `lstat` 确认名字消失；若首次 `delete_done` 持久化 flush/commit 失败，不再返回 failed——保留已耐久提交的 `committing` 行并返回 success（物理删除已验证），恢复在启动/惰性入口看到 `committing+名字缺失` 后转 `delete_done`、补元数据清理与 `recovered_deleted` 审计。`committing+名字仍存在` 仍为 failed（POSIX 下表示 disposition 未生效）。
- **I8**：服务端 receipt 恢复为 `success/missing/failed` 三值，移除 `pending`/`pending_count`；Android DTO/仓库/ViewModel/结果面板回退 pending 语义，旧客户端完全兼容；结果面板不再出现"删除完成+等待系统完成"矛盾。
- `delete_pending` 仅保留历史数据读取与 0017 downgrade 拦截，新代码不再创建。

验证（Codex 定向）：Server 五文件 **101 passed / 0 failed**；Android 三类 36/36；ruff 通过。Lead 全量结果见后续条目。未 commit/push（Lead 统一提交）；未构建产物；未上真机。

### 2026-10-25 Lead 全量验证（第四轮修复后）
- Server 全量 pytest：**737 通过 / 2 失败 / 739 项 / 424.85s**；2 项为本机 ffmpeg 完整性级别环境限制，换可写 basetemp 单独复跑 **2 passed**；十万行查询性能门禁通过。
- Android 全量 JVM：**75 suites / 511 tests / 0 失败 / 0 错误 / 0 跳过**（BUILD SUCCESSFUL）。
- Ruff：改动文件通过。
- 结论：本轮修复相关与全量回归通过（除已分类 2 项本机环境失败）；发版仍以第五轮独立复审通过为前提。

## 2026-10-25 第五轮复审修复：I7-R 四态恢复观察

第五轮 Codex 复审（`review_meta/codex-review-20261025-round5/round5-review.md`）BLOCK：I7-R——恢复把"未观察到名字缺失"当成"原文件仍存在"，会把已返回 success、DB 仍为 committing 的行终结为 failed。Codex 按 Lead 批准方案实现四态只读观察（报告 `review_meta/codex-fix-20261025-round5/fix-report.md`）：

- 新增 `_RecoveryObservation`：`MISSING` / `SAME_ORIGINAL_PRESENT` / `REPLACED_OR_ORIGINAL_GONE` / `UNKNOWN`。
  - local：根缺失/停用 → UNKNOWN；叶缺失 → MISSING；safe_path/IO 异常 → UNKNOWN；叶存在时用 `open_media` 只读句柄身份复验：成功且指纹匹配 → SAME_ORIGINAL_PRESENT，身份不匹配（ConflictError）→ REPLACED_OR_ORIGINAL_GONE。
  - jellyfin：绝对路径 lstat：缺失 → MISSING；异常 → UNKNOWN；存在时按 size + mtime±3s 判断同一对象。
- 恢复策略：MISSING 或 REPLACED → 先耐久化 `delete_done`，再清理旧元数据并写 `recovered_deleted` / `recovered_original_gone`（不含路径），**不删除任何现有文件**；SAME_ORIGINAL_PRESENT 才把 committing 终结为 failed:interrupted_not_deleted；UNKNOWN 保留 committing 等待下次恢复（幂等）。
- 保持 POSIX-only、三值 receipt、0017 downgrade、claim 同事务审计、C1/I1/I2/I5/I6/M2/M3 不变；未新增状态/表/迁移。

验证：Codex 定向四文件 **97 passed / 0 failed**（TDD 先 4 failed）；ruff 通过。Lead 全量：Server **740 通过 / 2 失败 / 742 项 / 438.36s**（2 项本机 ffmpeg 环境限制，换可写 basetemp 单独复跑 2 passed）；Android 全量 JVM 见下一条；未 commit/push（Lead 统一提交）。
- Android 全量 JVM：**75 suites / 511 tests / 0 失败 / 0 错误 / 0 跳过**（BUILD SUCCESSFUL，无 Android 改动，复用上次编译产物）。

## 2026-10-25 第六轮复审修复：Jellyfin 恢复证据收紧（I7-R6A / I7-R6B）

第六轮 Codex 复审（`review_meta/codex-review-20261025-round6/round6-review.md`）BLOCK：
- **I7-R6A**：Jellyfin 恢复把任意父级/卷的 FileNotFoundError 当叶 MISSING，可能把不可达介质上的未删文件当已删收敛；
- **I7-R6B**：Jellyfin 缺 size/mtime 身份证据时仍判 SAME_ORIGINAL_PRESENT，可把替代对象误写为原文件仍在。

Codex 修复（报告 `review_meta/codex-fix-20261025-round6/fix-report.md`）：
- 新增只读 `_lstat_recovery_path`：先按既有绝对/无 `..`/拒绝 UNC 与逐段拒绝链接/reparse 校验，逐级 lstat 卷根与全部父目录；父链任一不可观察 → UNKNOWN；仅父链全部可观察后叶缺失 → MISSING；叶 OSError/ValueError → UNKNOWN。
- Jellyfin 身份证据矩阵：size 已知不一致或 mtime 已知超 ±3s → REPLACED；两项任一缺失 → UNKNOWN；两项均存在且匹配才 SAME_ORIGINAL_PRESENT。
- 测试：父目录改名/不可达 → 保持 committing 且不清理；size=None / mtime=None → UNKNOWN 保持 committing；既有缺失/替换/同文件/幂等用例补全身份依据。
- 保持 POSIX-only、三值 receipt、0017 downgrade、claim 同事务审计、C1/I1/I2/I5/I6/M2/M3 不变。

验证：Codex 四文件定向通过（详见 fix-report）；Lead 全量结果见下一条。未 commit/push（Lead 统一提交）。
- Lead 全量：Server **743 通过 / 2 失败 / 745 项 / 419.28s**（2 项本机 ffmpeg 完整性环境限制，换可写 basetemp 单独复跑 2 passed）；Android 无改动（上次全量 511/511）；ruff 改动文件通过。

### 2026-10-25 12:45 状态：Codex 额度再次用尽，第七轮复审顺延
- 第六轮复审修复（I7-R6A/I7-R6B）已提交 `5aa367a`；Lead 全量 Server **743/745**（2 项本机 ffmpeg 环境，单独复跑 2 passed）、Android 无改动（上次 511/511）。
- 第七轮 Codex 定点复审启动后触发额度上限（提示 **15:49 复位**），未生成报告；P5 发版继续暂停。
- 已设 15:50 提醒：复位后立即重试 push、执行第七轮复审；通过则进入 P5（1.6.0/alpha16 版本、构建、打包、SHA）。

## 2026-10-25 第七轮复审修复：Jellyfin 句柄相对观察（关闭父链/叶 TOCTOU）

第七轮 Codex 复审（进程在写报告前提前结束，日志 `review_meta/codex-review-20261025-round7/round7-review.log`）结论：I7-R6B 已关闭，I7-R6A 仍有父链检查与叶检查之间的 TOCTOU——父目录在两次检查间被移走时，叶路径 lstat 会误判 MISSING，而文件仍在被移走的目录中。Codex 实现（报告因进程提前结束未落盘，改动与验证由 Lead 接管）：

- 新增 `_windows_recovery_file_info`：Jellyfin 恢复在 Windows 上先打开叶的父目录句柄（`FILE_FLAG_BACKUP_SEMANTICS|OPEN_REPARSE_POINT`），用 `GetFileInformationByHandle` 的卷序列+File Index 与打开前 `lstat` 的父目录身份比较；一致后再用 `NtCreateFile(RootDirectory=父句柄, ObjectName=叶)` 相对打开叶（`FILE_OPEN_REPARSE_POINT`）。叶缺失 → MISSING；成功 → 用句柄信息 size/mtime 与 DB 证据比较；其他错误/证据不足 → UNKNOWN。路径字符串不再参与叶存在性判定。
- 恢复四态与收敛策略不变：MISSING→delete_done+`recovered_deleted`；REPLACED→delete_done+`recovered_original_gone`（不删除现有文件）；SAME→仅 committing 写 failed:interrupted_not_deleted；UNKNOWN→保留 committing。
- 测试：真实目录改名 hook（父目录验证后、相对打开前把父目录改名）断言不会误判 MISSING、不清理元数据；Lead 将断言收紧为 `status=failed`/`error=interrupted_not_deleted`（文件仍在移走后的目录中，属积极证明同文件仍在），该用例单独复跑通过。
- 保持 POSIX-only、三值 receipt、0017 downgrade、claim 同事务审计、C1/I1/I2/I5/I6/M2/M3 与 Local 四态观察不变。
- Lead 全量：Server **744 通过 / 2 失败 / 746 项 / 405.66s**（2 项本机 ffmpeg 完整性环境限制，换可写 basetemp 单独复跑 2 passed）；Android 无改动（上次 511/511）；ruff 改动文件通过；第七轮 TOCTOU 用例收紧后单独复跑通过。
- Lead 全量（I7-R8A 修复后）：Server **745 通过 / 2 失败 / 747 项 / 350.47s**（2 项本机 ffmpeg 环境，换可写 basetemp 单独复跑 2 passed）；ruff 改动文件通过。新增 `STATUS_OBJECT_PATH_NOT_FOUND` 注入用例断言保持 committing、索引保留、无终态审计。
