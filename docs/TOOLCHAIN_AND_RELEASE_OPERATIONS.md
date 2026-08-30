# MediaReview 1.1 工具链与发布操作索引

日期：2026-08-30

本文件区分“仓库自带工具”“本机可复用工具”“尚未补齐的发布工具”。路径是当前 AI Home 主机的已核验位置；换电脑后必须重新发现，不能照抄机器路径。

## 1. 当前项目入口

| 用途 | 位置 | 状态 |
|---|---|---|
| 接管入口 | `TAKEOVER_READ_FIRST.md` | 可用 |
| 完整计划 | `docs/superpowers/plans/2026-08-30-mediareview-1.1-takeover.md` | 可用 |
| Server 依赖 | `server/pyproject.toml` | 开发依赖可用；未包含 PyInstaller |
| Android 构建 | `android/gradlew.bat` | 可用，Gradle Wrapper 8.9 |
| Server 部署构建 | `scripts/build_deploy.py` | **旧 0.8.1 脚本，不得用于 1.1 正式发布** |
| 阶段验收 ZIP | `scripts/build_review_handoff.py` | **已补齐**（Task 0A，端到端测试 `server/tests/test_review_handoff_builder.py` 覆盖） |
| Hermes APK 邮件 | `docs/HERMES_APK_EMAIL_DELIVERY.md` | 流程已记录；Hermes 当前附件实现尚不支持 APK |

## 2. 当前主机已发现的工具

| 工具 | 已核验路径 | 用法/边界 |
|---|---|---|
| Git | `C:\Program Files\Git\cmd\git.exe` | 提交、diff、clone；不要重新初始化本仓库 |
| uv | `C:\Espressif\tools\python\Scripts\uv.exe` | 发现/安装 Python 3.12；不要依赖 PATH 中的 Python 3.11 |
| Python 3.12.13 | `C:\Users\30566\AppData\Roaming\uv\python\cpython-3.12-windows-x86_64-none\python.exe` | 由 `uv python find 3.12` 实测；换机后重新发现 |
| JDK 21 | `E:\aihome\tools\jdk\jdk-21.0.5+11` | 设置 `JAVA_HOME` |
| Android SDK | `E:\aihome\tools\android-sdk` | 设置 `ANDROID_HOME` |
| ADB | `E:\aihome\tools\android-sdk\platform-tools\adb.exe` | 真机/模拟器发现与 instrumentation |
| apksigner | `E:\aihome\tools\android-sdk\build-tools\35.0.0\apksigner.bat` | Release APK 签名验证 |
| zipalign | `E:\aihome\tools\android-sdk\build-tools\35.0.0\zipalign.exe` | Release APK 对齐 |
| 7-Zip | `E:\aihome\tools\7zip\7z.exe` | 发布包结构检查，不代替 SHA-256 |
| PowerShell | 系统 Windows PowerShell 5.1；开发环境另有 `pwsh` | 正式部署脚本优先兼容系统自带 5.1；若改为强制 PS7，必须显式预检/提供获取方式，不能依赖 Codex runtime |
| FFmpeg | `C:\Program Files\Jellyfin\Server\ffmpeg.exe` | 7.1.4-Jellyfin，82,746,368 bytes，SHA-256 `812AC83375B8A926DA2859503D8C120544BD78DDC57ABAD026613D34C305A8D2` |
| ffprobe | `C:\Program Files\Jellyfin\Server\ffprobe.exe` | 7.1.4-Jellyfin，82,604,544 bytes，SHA-256 `D332077363D0C1B8407C949D701E96BFAC71F2412392E09D90C3AAF8892B18E7` |

PATH 中的 `python` 是 3.11.15，`py.exe`、Android Emulator、system image/AVD 和 PyInstaller 当前均不存在。因此不能声称模拟器或 EXE 发布链已就绪。Jellyfin 自带 FFmpeg 已找到，但尚未合法、可复现地纳入发布包；复用前必须核对来源、许可证、再分发条件、版本和哈希，并补齐 `LICENSE`/`THIRD_PARTY_NOTICES.md`。

本 D 盘接管副本应没有 Git remote。若 `git remote -v` 仍指向 E 盘原源码，必须先移除；只有用户明确指定新的托管位置后才可添加新 remote。

## 3. 新工作目录初始化

### Server

当前主机优先通过 uv 创建 D 盘自己的 Python 3.12 venv：

```powershell
Set-Location D:\MediaReview_1.1_Handoff
$uv = 'C:\Espressif\tools\python\Scripts\uv.exe'
& $uv python find 3.12
& $uv venv --python 3.12 --seed 'server\.venv'
& 'server\.venv\Scripts\python.exe' -m pip install --upgrade pip
& 'server\.venv\Scripts\python.exe' -m pip install -e '.\server[dev]'
```

换电脑时先安装 Python 3.12，再执行：

```powershell
uv venv --python 3.12 --seed server\.venv
server\.venv\Scripts\python.exe -m pip install -e '.\server[dev]'
```

测试必须隔离数据根：

```powershell
$env:MEDIAREVIEW_DATA_ROOT = Join-Path $env:TEMP 'mediareview-agent-test'
server\.venv\Scripts\python.exe -m pytest server\tests
server\.venv\Scripts\python.exe -m ruff check server
server\.venv\Scripts\python.exe -m ruff format --check server
```

严禁让测试默认落到 `C:\ProgramData\MediaReview`。

### Android

```powershell
$env:JAVA_HOME = 'E:\aihome\tools\jdk\jdk-21.0.5+11'
$env:ANDROID_HOME = 'E:\aihome\tools\android-sdk'
Set-Location android
.\gradlew.bat --no-daemon :app:testDebugUnitTest
.\gradlew.bat --no-daemon :app:assembleDebug :app:assembleAndroidTest :app:lintDebug
```

如果另一台电脑没有缓存依赖，首次不要加 `--offline`。`android/local.properties` 只保存该电脑的 `sdk.dir`，已被 Git 忽略，不得提交。

设备检查：

```powershell
& 'E:\aihome\tools\android-sdk\platform-tools\adb.exe' devices -l
```

列表为空时只能说 androidTest APK 已构建，不能说 instrumentation 已执行。

## 4. APK 签名与升级身份

- 当前仓库没有 Release signing 配置，也不应包含私钥。
- `*.jks`、`*.keystore`、`key.properties` 已被 `.gitignore` 排除。
- Task F 必须确定唯一发布证书并安全备份；证书丢失会导致以后无法覆盖升级同一 applicationId。
- 新证书并不自动兼容用户手机上已经安装的旧 APK。真机可用后必须提取已安装 APK 并比较证书指纹：

```powershell
$adb = 'E:\aihome\tools\android-sdk\platform-tools\adb.exe'
$signer = 'E:\aihome\tools\android-sdk\build-tools\35.0.0\apksigner.bat'
& $adb shell pm path com.mediareview.app
# 将上一步返回的 base.apk 路径代入；不要猜测路径。
& $adb pull '<returned-base.apk-path>' '.\release\installed-current.apk'
& $signer verify --verbose --print-certs '.\release\installed-current.apk'
& $signer verify --verbose --print-certs '.\release\家庭媒体管家-1.1.0.apk'
```

两者证书指纹必须一致才可称为覆盖升级。原签名密钥不可获得时，必须明确报告“无法无损覆盖升级，需要卸载重装并可能丢失本地状态”，由用户决定；不得静默创建新密钥。
- 任何 Agent 不得把 keystore 密码、alias 密码或私钥复制到项目、聊天、日志、验收 ZIP、邮件正文。
- 正式 APK 必须验证：

```powershell
& 'E:\aihome\tools\android-sdk\build-tools\35.0.0\apksigner.bat' verify `
  --verbose --print-certs '.\release\家庭媒体管家-1.1.0.apk'
Get-FileHash '.\release\家庭媒体管家-1.1.0.apk' -Algorithm SHA256
```

## 5. 已知工具缺口

### 阶段验收 ZIP 构建器（Task 0A 已补齐）

`scripts/build_review_handoff.py` 已按 takeover plan Task 0A 用 TDD 实现（端到端测试位于
`server/tests/test_review_handoff_builder.py`，覆盖必需条目、禁止项、密钥 fail-closed 与大小上限）。
使用方式：

```powershell
# 1) 先把真实测试/lint 输出写入 review_meta\（server_tests.txt、server_lint.txt、android_tests.txt、android_lint.txt）
# 2) 确认根目录 REVIEW_SUMMARY.md 已按规则编写并含"阶段结论"行
Set-Location D:\MediaReview_1.1_Handoff
python scripts\build_review_handoff.py --stage 0A --name 阶段名 --base <上一阶段基线>
```

脚本行为：打包范围 = `git diff --name-only <base>`（基线到工作树，仅已跟踪文件）+ 固定状态文档；
只含已批准文本扩展名；禁止类别/超限文件被排除并记录在 `review_meta/excluded_files.txt`；
`.env`、keystore 等疑似密钥路径、无效 base、缺少摘要或证据、ZIP 超限均以非零退出且不产出 ZIP。
`review_handoff/` 与 `review_meta/` 均被 Git 忽略。

当前主机 Codex 的 `C:\Users\30566\.codex\skills\subagent-driven-development\scripts\review-package`
只生成冻结 diff，不再是验收流程的一部分，仅作历史参考。

最新 Task 4 审查引用的 diff 未随 D 盘副本携带，可由 Git 历史重建：

```powershell
git diff -U10 c4a7d3a..6538641 > .superpowers\sdd\review-c4a7d3a..6538641.diff
```

该 `.diff` 是可再生临时证据，按 `.gitignore` 不提交；审查 Markdown 必须保持可追踪。

### 旧部署构建器不可作为 1.1 发布工具

`scripts/build_deploy.py` 仍硬编码：

- 版本 `0.8.1`
- `Mediaserver.exe`
- 四个 PowerShell 脚本
- FFmpeg 可缺失
- 不包含 APK、SHA-256、升级回滚和正式包结构

因此在 Task F 完成前禁止用它生成名为 1.1.0 的部署包。

旧 `install.ps1`/`repair.ps1`/`uninstall.ps1` 还存在运行级发布风险：

- `ConvertFrom-Json -AsHashtable` 不兼容 Windows PowerShell 5.1。
- 安装流程没有把包内 FFmpeg 复制到安装目录，也没有配置 UDP 35001 防火墙规则。
- 健康检查失败仍可能返回成功，没有安装失败自动回滚。
- repair 可能把服务自身监听端口误判为冲突；uninstall 的进程和递归删除缺少严格所有权/绝对路径边界。
- `start.ps1`、`stop.ps1`、`restart.ps1`、`status.ps1` 尚不存在。

Task F 必须先用干净机/临时根测试修复这些合同；在此之前不得把旧脚本称为可升级、可回滚安装器。

### PyInstaller、FFmpeg 许可和发布核验未就绪

- `server/pyproject.toml` 没有 PyInstaller；Task F 必须固定版本、安装、构建并验证。
- 已找到 Jellyfin 7.1.4 的 FFmpeg/ffprobe，但项目没有 `LICENSE` 和 `THIRD_PARTY_NOTICES.md`；不得直接复制进正式包。Task F 必须完成许可证/再分发审查，记录来源、版本和 SHA-256，并验证程序优先使用随包二进制。
- 当前缺少一键 toolchain 验证、Server 全量测试、Android 全量测试、Release 构建/验签和最终包结构验证脚本；这些属于 Task F 的正式发布缺口。

## 6. 最终产物位置约定

```text
release/
├─ 家庭媒体管家-1.1.0.apk
├─ MediaReview_Migration_1.1.0.zip
├─ CHECKSUMS.txt
├─ RELEASE_NOTES.md
└─ verification/
   ├─ android-tests.txt
   ├─ server-tests.txt
   ├─ signing.txt
   ├─ malware-scan.txt
   └─ physical-phone-acceptance.md
```

`release/` 产物不提交 Git；最终交付时复制到 AI Home `shared/outbox/<agent>/`，再按 Hermes 邮件流程发送 APK。
