#!/usr/bin/env python3
"""RC clean build/实际产物验证。旧 MediaReview_Migration_1.2.2 不再复用。
默认重新构建；--prepare-only 记录本轮源/产物哈希与验证结果。
最终提交后 --package-verified 重验本轮证据，源码或产物变化均拒绝。
不读取生产配置，不部署、不发邮件。
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SERVER = ROOT / "server"
DIST = SERVER / "dist/MediaReviewServer"
DEPLOY = ROOT / "deployment"
OUT = ROOT / "deploy_handoff"
APK_DIR = ROOT / "android/app/build/outputs/apk"
VERSION = "1.2.2"
ANDROID_VERSION = "2.0.0-alpha8"
ANDROID_CODE = 15
ANDROID_PACKAGE = "com.mediareview.app"
REQUIRED_CAPABILITIES = (
    "review_session",
    "review_nearest",
    "organize",
    "delete_nonce",
    "duplicates_paged",
    "library_selection",
)
ALL_SCRIPTS = (
    "install.ps1",
    "start.ps1",
    "stop.ps1",
    "restart.ps1",
    "status.ps1",
    "repair.ps1",
    "diagnose.ps1",
    "uninstall.ps1",
)
EVIDENCE = OUT / "stage8e-build-evidence.json"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def clean_generated(path: Path, expected: Path) -> None:
    if path.absolute() != expected.absolute() or path.resolve() != expected.absolute():
        raise ValueError("拒绝清理非预期构建目录或链接")
    if path.exists():
        shutil.rmtree(path)


def build_exe() -> None:
    clean_generated(SERVER / "dist", SERVER / "dist")
    clean_generated(SERVER / "build/pyinstaller", SERVER / "build/pyinstaller")
    subprocess.run(  # noqa: S603
        [
            str(SERVER / ".venv/Scripts/python.exe"),
            "-m",
            "PyInstaller",
            "packaging/mediareview_server.spec",
            "--distpath",
            "dist",
            "--workpath",
            "build/pyinstaller",
            "--clean",
            "--noconfirm",
        ],
        cwd=SERVER,
        check=True,
    )
    if not (DIST / "MediaReviewServer.exe").is_file():
        raise ValueError("Server EXE 构建失败")


def build_release_apk() -> Path:
    clean_generated(APK_DIR / "release", APK_DIR / "release")
    subprocess.run(  # noqa: S603
        [
            str(ROOT / "android/gradlew.bat"),
            "--no-daemon",
            "--offline",
            "clean",
            ":app:assembleRelease",
        ],
        cwd=ROOT / "android",
        check=True,
    )
    apk = APK_DIR / "release/app-release.apk"
    if not apk.is_file():
        raise ValueError("没有新生成的签名 Release APK")
    return apk


def android_build_tools() -> Path:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ValueError("请使用 Environment 登记的 ANDROID_HOME")
    tools = Path(sdk) / "build-tools"
    candidates = sorted(
        (
            item
            for item in tools.iterdir()
            if item.is_dir() and re.fullmatch(r"\d+\.\d+\.\d+", item.name)
        ),
        key=lambda item: tuple(map(int, item.name.split("."))),
        reverse=True,
    )
    for item in candidates:
        if (item / "aapt.exe").is_file() and (item / "apksigner.bat").is_file():
            return item
    raise ValueError("已登记 SDK 中找不到 aapt/apksigner")


def parse_apk_badging(output: str) -> dict:
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", output)
    if not match or (match[1], int(match[2]), match[3]) != (
        ANDROID_PACKAGE,
        ANDROID_CODE,
        ANDROID_VERSION,
    ):
        raise ValueError("APK 自身 package/version 不符合 RC 要求")
    return {"package": match[1], "version_code": int(match[2]), "version_name": match[3]}


def verify_apk(apk: Path) -> dict:
    tools = android_build_tools()
    badging = subprocess.run(  # noqa: S603 SDK 登记路径
        [str(tools / "aapt.exe"), "dump", "badging", str(apk)],
        capture_output=True,
        text=True,
        check=True,
    )  # noqa: S603
    android = parse_apk_badging(badging.stdout)
    subprocess.run(  # noqa: S603 SDK 登记路径
        [str(tools / "apksigner.bat"), "verify", "--verbose", str(apk)],
        capture_output=True,
        text=True,
        check=True,
    )  # noqa: S603
    return {**android, "signature": "PASS"}


def validate_binary_health(data: dict) -> dict:
    if (
        data.get("status") != "ok"
        or data.get("version") != VERSION
        or data.get("api_contract") != 2
        or not set(REQUIRED_CAPABILITIES).issubset(data.get("capabilities", []))
    ):
        raise ValueError("Server EXE 实际 health/版本/Contract/capabilities 不符合要求")
    return {key: data[key] for key in ("status", "version", "api_contract", "capabilities")}


def verify_server_binary(exe: Path) -> dict:
    scratch = ROOT / ".e2e_tmp/stage8e-binary"
    scratch.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="probe-", dir=scratch) as temporary:
        data_root = Path(temporary)
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        config = data_root / "config/config.json"
        config.parent.mkdir()
        config.write_text(
            json.dumps(
                {
                    "server": {"host": "127.0.0.1", "port": port},
                    "jellyfin": {"url": "http://127.0.0.1:1", "api_key": ""},
                    "security": {"pairing_required": False},
                    "features": {"sprites": False, "duplicate_scan": False},
                }
            ),
            encoding="utf-8",
        )
        environment = {
            key: value
            for key, value in os.environ.items()
            if not key.startswith("MEDIAREVIEW") and key != "PYTHONPATH"
        }
        environment["MEDIAREVIEW_DATA_ROOT"] = str(data_root)
        with (data_root / "binary-output.txt").open("w", encoding="utf-8") as output:
            process = subprocess.Popen(  # noqa: S603 本轮新建 EXE 绝对路径
                [str(exe.resolve())],
                cwd=exe.parent,
                env=environment,
                stdout=output,
                stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
            )  # noqa: S603
            try:
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                deadline = time.monotonic() + 60
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise ValueError("Server EXE 隔离实例提前退出")
                    try:
                        with opener.open(
                            f"http://127.0.0.1:{port}/api/v1/system/health", timeout=2
                        ) as response:  # noqa: S310 回环地址由绑定端口构造
                            envelope = json.load(response)
                        if not envelope.get("success"):
                            raise ValueError("Server EXE health envelope 无效")
                        return validate_binary_health(envelope.get("data", {}))
                    except OSError:
                        time.sleep(0.5)
                raise ValueError("Server EXE health 超时，PACKAGING BLOCKED")
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)


def source_inputs() -> dict[str, str]:
    result = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, check=True)  # noqa: S603,S607
    inputs = {}
    for relative in result.stdout.decode("utf-8").split("\0"):
        if relative.startswith(
            ("server/app/", "server/packaging/", "android/app/src/main/", "android/gradle/")
        ) or relative in {
            "server/run_server.py",
            "server/alembic.ini",
            "server/pyproject.toml",
            "android/app/build.gradle.kts",
            "android/build.gradle.kts",
            "android/settings.gradle.kts",
            "android/gradle.properties",
            "android/gradlew",
            "android/gradlew.bat",
            "scripts/build_deploy.py",
        }:
            inputs[relative] = sha256(ROOT / relative)
    return inputs


def server_files() -> dict[str, str]:
    return {
        path.relative_to(DIST).as_posix(): sha256(path)
        for path in sorted(DIST.rglob("*"))
        if path.is_file()
    }


def git_head(*, require_clean: bool = False) -> str:
    if require_clean:
        status = subprocess.run(  # noqa: S603
            ["git", "status", "--porcelain"],  # noqa: S607 已登记 Git CLI
            cwd=ROOT,
            capture_output=True,
            check=True,  # noqa: S607
        )  # noqa: S603,S607
        if status.stdout.strip():
            raise ValueError("发布打包要求 Git clean")
    return subprocess.run(  # noqa: S603
        ["git", "rev-parse", "HEAD"],  # noqa: S607 已登记 Git CLI
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=True,  # noqa: S607
    ).stdout.strip()  # noqa: S603,S607


def release_manifest(evidence: dict, head: str, exe: Path, apk: Path) -> dict:
    server = validate_binary_health({"status": "ok", **evidence["server"]})
    android = evidence["android"]
    if (
        android.get("package"),
        android.get("version_code"),
        android.get("version_name"),
        android.get("signature"),
    ) != (ANDROID_PACKAGE, ANDROID_CODE, ANDROID_VERSION, "PASS"):
        raise ValueError("已验证 APK 元数据不匹配")
    if not re.fullmatch(r"[0-9a-f]{40}", head):
        raise ValueError("Git HEAD 无效")
    return {
        "server_version": server["version"],
        "api_contract": server["api_contract"],
        "required_capabilities": list(REQUIRED_CAPABILITIES),
        "android_version_name": android["version_name"],
        "android_version_code": android["version_code"],
        "android_package": android["package"],
        "git_head": head,
        "server_exe_sha256": sha256(exe),
        "apk_sha256": sha256(apk),
        "apk_signature": "PASS",
        "apk_size": apk.stat().st_size,
        "production_deployment": "NOT PERFORMED",
        "real_user_lan": "NOT TESTED",
    }


def render_handover(manifest: dict) -> str:
    return f"""# MediaReview RC 部署交接

- Server: {manifest["server_version"]}
- API Contract: {manifest["api_contract"]}
- Android: {manifest["android_version_name"]}
- Android versionCode: {manifest["android_version_code"]}
- Android package: {manifest["android_package"]}
- Git HEAD: {manifest["git_head"]}
- 状态：READY_FOR_USER_VALIDATION；用户真实 LAN 尚未验收。

版本来自 EXE 实际 health 与 APK manifest，签名校验 PASS。
安装前阅读 USER_INSTALL_GUIDE.md、UPGRADE_ROLLBACK.md、USER_LAN_VALIDATION.md。
生产必须显式配置 jellyfin.client_url，不接受 request_host_fallback。
本包不包含真实配置、数据库、凭据或用户媒体。禁止永久删除真实媒体。
"""


def write_sha256(root: Path) -> None:
    lines = [
        f"{sha256(path)}  {path.relative_to(root).as_posix()}"
        for path in sorted(root.rglob("*"))
        if path.is_file() and path.name != "SHA256SUMS.txt"
    ]
    (root / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def verify_zip(zip_path: Path, package_root: str) -> int:
    with zipfile.ZipFile(zip_path) as archive:
        lines = archive.read(f"{package_root}/SHA256SUMS.txt").decode("utf-8").splitlines()
        expected = {f"{package_root}/SHA256SUMS.txt"}
        for line in lines:
            digest, relative = line.split("  ", 1)
            entry = f"{package_root}/{relative}"
            if hashlib.sha256(archive.read(entry)).hexdigest() != digest:
                raise ValueError("ZIP SHA256 回验失败")
            expected.add(entry)
        if set(archive.namelist()) != expected or len(archive.namelist()) != len(expected):
            raise ValueError("ZIP 有未登记文件或重复清单")
        return len(lines)


def prepare() -> dict:
    build_head = git_head(require_clean=True)
    OUT.mkdir(exist_ok=True)
    EVIDENCE.unlink(missing_ok=True)
    before = source_inputs()
    build_exe()
    apk = build_release_apk()
    exe = DIST / "MediaReviewServer.exe"
    evidence = {
        "server": verify_server_binary(exe),
        "android": verify_apk(apk),
        "server_sha256": sha256(exe),
        "server_files": server_files(),
        "apk_sha256": sha256(apk),
        "inputs": before,
        "build_git_head": build_head,
        "verified_at": dt.datetime.now(dt.UTC).isoformat(),
    }
    if source_inputs() != before:
        raise ValueError("构建期间源码变化，拒绝记录新鲜构建证据")
    EVIDENCE.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Fresh EXE health / APK manifest / signature PASS; build evidence recorded.")
    return evidence


def package_verified() -> Path:
    head = git_head(require_clean=True)
    evidence = json.loads(EVIDENCE.read_text(encoding="utf-8"))
    age = dt.datetime.now(dt.UTC) - dt.datetime.fromisoformat(evidence["verified_at"])
    exe, apk = DIST / "MediaReviewServer.exe", APK_DIR / "release/app-release.apk"
    if (
        not 0 <= age.total_seconds() <= 6 * 3600
        or evidence["inputs"] != source_inputs()
        or evidence["server_sha256"] != sha256(exe)
        or evidence["server_files"] != server_files()
        or evidence["apk_sha256"] != sha256(apk)
    ):
        raise ValueError("新鲜构建证据失效，禁止复用旧 EXE/APK")
    evidence["server"] = verify_server_binary(exe)
    evidence["android"] = verify_apk(apk)
    manifest = release_manifest(evidence, head, exe, apk)
    manifest["build_git_head"] = evidence["build_git_head"]
    manifest["build_verified_at"] = evidence["verified_at"]
    manifest["source_input_sha256"] = evidence["inputs"]
    name = f"MediaReview_RC_{ANDROID_VERSION}_Server-{VERSION}_{dt.date.today():%Y%m%d}_{head[:7]}"
    package = OUT / name
    if package.exists():
        raise ValueError("发布目录已存在，拒绝覆盖历史产物")
    package.mkdir()
    shutil.copytree(DIST, package / "MediaReviewServer")
    (package / "scripts").mkdir()
    for script in ALL_SCRIPTS:
        shutil.copy2(DEPLOY / "scripts" / script, package / "scripts" / script)
    for filename in ("version.ps1", "config.example.json"):
        shutil.copy2(DEPLOY / filename, package / filename)
    for filename in (
        "LICENSE",
        "THIRD_PARTY_NOTICES.md",
        "UPGRADE_ROLLBACK.md",
        "USER_INSTALL_GUIDE.md",
        "USER_LAN_VALIDATION.md",
    ):
        shutil.copy2(ROOT / filename, package / filename)
    shutil.copy2(DEPLOY / "README.md", package / "README.md")
    shutil.copy2(apk, package / "app-release.apk")
    ffmpeg = ROOT / "third_party/ffmpeg"
    if (ffmpeg / "ffmpeg.exe").is_file():
        (package / "ffmpeg").mkdir()
        for filename in ("ffmpeg.exe", "ffprobe.exe"):
            if (ffmpeg / filename).is_file():
                shutil.copy2(ffmpeg / filename, package / "ffmpeg" / filename)
    (package / "HANDOVER.md").write_text(render_handover(manifest), encoding="utf-8")
    (package / "RELEASE_MANIFEST.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    write_sha256(package)
    zip_path = OUT / f"{name}.zip"
    with zipfile.ZipFile(zip_path, "x", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(package.rglob("*")):
            if path.is_file():
                archive.write(path, f"{name}/{path.relative_to(package).as_posix()}")
    count = verify_zip(zip_path, name)
    user_apk = OUT / f"MediaReview_{ANDROID_VERSION}_stage8e.apk"
    shutil.copy2(apk, user_apk)
    (OUT / f"{name}_SHA256.txt").write_text(
        f"git_head {head}\n"
        f"zip {zip_path.name} {zip_path.stat().st_size} bytes {sha256(zip_path)}\n"
        f"apk {user_apk.name} {user_apk.stat().st_size} bytes {sha256(user_apk)}\n"
        f"exe MediaReviewServer.exe {sha256(exe)}\n",
        encoding="utf-8",
    )
    print(f"RC INTEGRITY PASS: {count} SHA256 entries; {zip_path}")
    return zip_path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_mutually_exclusive_group()
    actions.add_argument("--prepare-only", action="store_true")
    actions.add_argument("--package-verified", action="store_true")
    args = parser.parse_args()
    try:
        if not args.package_verified:
            prepare()
        if not args.prepare_only:
            package_verified()
    except (OSError, ValueError, KeyError, subprocess.SubprocessError):
        print("PACKAGING BLOCKED：构建、产物验证或完整性门禁失败。", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
