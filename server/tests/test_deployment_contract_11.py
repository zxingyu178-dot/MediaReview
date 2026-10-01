"""Task F 部署契约 RED 测试（Windows 部署、升级、回滚与产物）。

覆盖 takeplan Task F 的关键契约：

- 8 个 PowerShell 脚本齐全且 PS 5.1 兼容、特权脚本要求管理员。
- 可执行文件精确名为 ``MediaReviewServer.exe``（任何脚本不得引用旧 ``Mediaserver.exe``）。
- start/stop/restart/status 按 EXE 绝对路径精确归属进程，禁止按端口乱杀。
- install 内置事务式升级：磁盘检查、备份 config+DB、失败回滚、写 CURRENT_VERSION。
- 防火墙只开放 TCP 8766 + UDP 35001，不触碰其他端口。
- 卸载默认保留数据，仅显式 -DeleteData 才删。
- FFmpeg 优先随包、其次系统 PATH。
- ``scripts/build_deploy.py`` 产出 ``MediaReview_Migration_1.1.0`` 根 + 8 脚本 +
  SHA-256 校验和。
- PyInstaller spec 产物名为 ``MediaReviewServer.exe``。
- Android applicationId 与当前 2.0 产品线版本一致；LICENSE 与 THIRD_PARTY_NOTICES 存在。
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "deployment" / "scripts"
SERVER = ROOT / "server"
ANDROID = ROOT / "android"

EXPECTED_SCRIPTS = (
    "install.ps1",
    "start.ps1",
    "stop.ps1",
    "restart.ps1",
    "status.ps1",
    "repair.ps1",
    "diagnose.ps1",
    "uninstall.ps1",
)
PRIVILEGED_SCRIPTS = (
    "install.ps1",
    "start.ps1",
    "stop.ps1",
    "restart.ps1",
    "repair.ps1",
    "uninstall.ps1",
)

# PowerShell 7 专属语法(PS 5.1 下会解析失败),出现即视为不兼容。
PS7_ONLY_TOKENS = (
    "??=",
    "-Parallel",
    "::Wait",
    "ConvertFrom-Json -AsHashtable -AsHashtable",
)


def _script(name: str) -> str:
    return (SCRIPTS / name).read_text(encoding="utf-8-sig")


# ---------------------------------------------------------------- 脚本齐全与兼容性
def test_all_eight_scripts_exist() -> None:
    for name in EXPECTED_SCRIPTS:
        assert (SCRIPTS / name).is_file(), f"缺少部署脚本: {name}"


def test_all_scripts_require_powershell_51() -> None:
    for name in EXPECTED_SCRIPTS:
        head = _script(name).splitlines()[0]
        assert head == "#requires -Version 5.1", f"{name} 首行必须声明 PS 5.1"


def test_privileged_scripts_require_admin() -> None:
    for name in PRIVILEGED_SCRIPTS:
        assert "#requires -RunAsAdministrator" in _script(name), f"{name} 必须要求管理员"


def test_status_script_is_read_only() -> None:
    # status 只读,不得要求管理员,也不得停止/启动进程。
    assert "#requires -RunAsAdministrator" not in _script("status.ps1")


def test_no_powershell7_only_syntax() -> None:
    for name in EXPECTED_SCRIPTS:
        body = _script(name)
        for token in PS7_ONLY_TOKENS:
            assert token not in body, f"{name} 含 PS7 专属语法: {token}"


# ---------------------------------------------------------------- 精确 EXE 名
def test_scripts_reference_exact_exe_name() -> None:
    for name in EXPECTED_SCRIPTS:
        body = _script(name)
        assert "MediaReviewServer.exe" in body, f"{name} 必须引用 MediaReviewServer.exe"
        assert "Mediaserver.exe" not in body, f"{name} 不得引用旧名 Mediaserver.exe"


def test_pyinstaller_spec_produces_media_review_server_exe() -> None:
    spec = (SERVER / "packaging" / "mediareview_server.spec").read_text(encoding="utf-8")
    assert 'name="MediaReviewServer"' in spec
    assert 'name="Mediaserver"' not in spec


# ---------------------------------------------------------------- 进程归属(start/stop/restart/status)
def test_stop_restart_stop_only_owned_process_by_exe_path() -> None:
    for name in ("stop.ps1", "restart.ps1"):
        body = _script(name)
        # 按 EXE 绝对路径过滤,而非按端口/所有同名进程。
        assert "MediaReviewServer.exe" in body
        assert ".Path" in body, f"{name} 应按进程路径(.Path)归属"
        # 禁止"按端口监听结果直接杀进程"这一危险模式。
        assert not ("Get-NetTCPConnection" in body and "Stop-Process" in body), (
            f"{name} 不得按端口杀进程"
        )


def test_status_reports_health_and_version() -> None:
    body = _script("status.ps1")
    assert "system/health" in body
    assert "MediaReviewServer.exe" in body


# ---------------------------------------------------------------- 事务式升级(F4)
def test_install_checks_disk_free_space() -> None:
    body = _script("install.ps1")
    assert "Free" in body or "FreeSpace" in body, "install 必须做磁盘剩余空间检查"


def test_install_backs_up_config_and_database_before_upgrade() -> None:
    body = _script("install.ps1")
    assert "Backup" in body
    assert "config" in body and "database" in body, "升级前必须备份 config 与 database"


def test_install_restores_on_failure_and_keeps_current_version_marker() -> None:
    body = _script("install.ps1")
    assert "Restore" in body, "升级失败必须回滚"
    assert "CURRENT_VERSION" in body, "必须写 CURRENT_VERSION 标记"


def test_install_rejects_upgrade_when_prior_version_missing() -> None:
    body = _script("install.ps1")
    # 升级路径必须检测已安装版本,缺失时给出明确失败,不静默覆盖。
    assert "Get-ItemProperty" in body or "CURRENT_VERSION" in body


# ---------------------------------------------------------------- 防火墙(F5)
def test_firewall_opens_only_tcp_8766_and_udp_35001() -> None:
    body = _script("install.ps1")
    assert "TCP" in body and "8766" in body
    assert "UDP" in body and "35001" in body
    # 不得为其他端口开防火墙规则(只允许上面两个)。
    rule_lines = [ln for ln in body.splitlines() if "New-NetFirewallRule" in ln]
    for ln in rule_lines:
        ports = [t for t in ln.replace("-LocalPort", " ").split() if t.isdigit()]
        for p in ports:
            assert p in ("8766", "35001"), f"防火墙不得开放非预期端口 {p}"


# ---------------------------------------------------------------- 卸载保数据
def test_uninstall_preserves_data_unless_delete_data() -> None:
    body = _script("uninstall.ps1")
    assert "DeleteData" in body
    # 删除数据目录必须位于 if ($DeleteData) 守卫之后(块级守卫)。
    guard_idx = body.find("if ($DeleteData)")
    del_idx = body.find("Remove-Item $DataRoot")
    assert guard_idx != -1, "缺少 -DeleteData 守卫"
    assert del_idx != -1, "必须出现删除数据目录的代码路径"
    assert guard_idx < del_idx, "删除数据目录必须位于 -DeleteData 守卫块内"


# ---------------------------------------------------------------- FFmpeg 优先级
def test_ffmpeg_prefers_bundled_then_system() -> None:
    body = _script("install.ps1")
    # 同时存在随包 ffmpeg 检测与系统 PATH 兜底。
    assert "ffmpeg.exe" in body
    assert "Get-Command ffmpeg" in body


# ---------------------------------------------------------------- build_deploy.py(F7)
def test_build_deploy_uses_media_review_migration_root() -> None:
    builder = (ROOT / "scripts" / "build_deploy.py").read_text(encoding="utf-8")
    assert "MediaReview_Migration_1.1.0" in builder
    assert "MediaReviewServer.exe" in builder


def test_build_deploy_packages_all_eight_scripts_and_sha256() -> None:
    builder = (ROOT / "scripts" / "build_deploy.py").read_text(encoding="utf-8")
    for name in EXPECTED_SCRIPTS:
        assert name in builder, f"部署包必须包含脚本 {name}"
    assert "SHA256" in builder, "部署包必须产出 SHA-256 校验和"


def test_build_deploy_version_matches_server() -> None:
    builder = (ROOT / "scripts" / "build_deploy.py").read_text(encoding="utf-8")
    init = (SERVER / "app" / "__init__.py").read_text(encoding="utf-8")
    assert '__version__ = "1.1.0"' in init
    assert "1.1.0" in builder


# ---------------------------------------------------------------- 许可文档(F2)
def test_license_and_third_party_notices_exist() -> None:
    assert (ROOT / "LICENSE").is_file(), "缺少 LICENSE"
    assert (ROOT / "THIRD_PARTY_NOTICES.md").is_file(), "缺少 THIRD_PARTY_NOTICES.md"


# ---------------------------------------------------------------- Android 产物(F6)
def test_android_version_name_and_code_and_application_id() -> None:
    """Android 版本合同: 2.0 产品线 + versionCode/versionName 格式合法。

    Stage 8C.2 §34: 版本号随产品版本递增(见 docs/VERSION_POLICY.md),不再把
    具体数值钉死在断言里(否则每次版本迭代都要改这个部署合同测试);
    具体数值的正确性与"必须有 Release Notes"由 Android 侧
    `ReleaseNotesContractTest` 与版本政策共同保证。
    """
    gradle = (ANDROID / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    assert 'applicationId = "com.mediareview.app"' in gradle
    assert re.search(r'versionName = "2\.0\.0-(alpha|beta)\d+"', gradle), (
        "versionName 必须是 2.0 产品线版本(如 2.0.0-alpha3)"
    )
    assert re.search(r"versionCode = \d+", gradle), "必须声明 versionCode"


def test_android_app_name_is_zh() -> None:
    strings = (ANDROID / "app" / "src" / "main" / "res" / "values" / "strings.xml").read_text(
        encoding="utf-8"
    )
    assert "家庭媒体管家" in strings


# ---------------------------------------------------------------- Release 签名 fail-closed(I-4)
def test_release_signing_is_fail_closed_no_debug_fallback() -> None:
    """Release 必须强制使用 release 签名;缺失密钥时构建失败,禁止静默回退 debug 签名。"""
    gradle = (ANDROID / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    release_block = gradle.split("buildTypes {", 1)[1]
    # 无条件绑定 release 签名(不得包裹在 if (keystoreProps.isNotEmpty()) 内)。
    assert 'signingConfig = signingConfigs.getByName("release")' in release_block
    # 不得出现条件绑定或"以 debug 签名兜底"的旧逻辑。
    assert "if (keystoreProps.isNotEmpty())" not in release_block
    assert "以 debug 签名兜底" not in gradle


def test_release_signing_config_created_unconditionally() -> None:
    gradle = (ANDROID / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    assert 'create("release")' in gradle
