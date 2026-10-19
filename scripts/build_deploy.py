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
import tomllib
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SERVER = ROOT / "server"
DIST = SERVER / "dist/MediaReviewServer"
DEPLOY = ROOT / "deployment"
OUT = ROOT / "deploy_handoff"
APK_DIR = ROOT / "android/app/build/outputs/apk"
VERSION = "1.5.2"
API_CONTRACT = 2
ANDROID_VERSION = "2.0.0-alpha15"
ANDROID_CODE = 22
ANDROID_PACKAGE = "com.mediareview.app"
REQUIRED_CAPABILITIES = (
    "review_session",
    "review_nearest",
    "organize",
    "delete_nonce",
    "duplicates_paged",
    "library_selection",
    "folder_tree_v1",
    "thumbnail_profiles_v1",
    "review_folder_filter_v1",
    "delete_receipt_v1",
    "native_sources_v1",
    "native_range_v1",
    "native_read_only_v1",
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
    "upgrade-aihome-managed.ps1",
)
EVIDENCE = OUT / "stage8i-build-evidence.json"


def current_package_name() -> str:
    return f"MediaReview_Server_{VERSION}_stage9"


def current_apk_name() -> str:
    return f"MediaReview_{ANDROID_VERSION}_stage9.apk"


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
            "--max-workers=1",
            "-Pkotlin.compiler.execution.strategy=in-process",
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
        or data.get("api_contract") != API_CONTRACT
        or not set(REQUIRED_CAPABILITIES).issubset(data.get("capabilities", []))
    ):
        raise ValueError("Server EXE 实际 health/版本/Contract/capabilities 不符合要求")
    return {key: data[key] for key in ("status", "version", "api_contract", "capabilities")}


def verify_server_binary(exe: Path) -> dict:
    scratch = ROOT / "temp/stage8i-ux/binary"
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
                    "storage": {"data_root": str(data_root)},
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
            except Exception:
                output.flush()
                failure = ROOT / "review_meta/stage8i/binary-failure.txt"
                failure.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(data_root / "binary-output.txt", failure)
                raise
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


def verify_version_ssot() -> None:
    """只读静态声明；不导入 Server 或执行部署脚本。"""

    def field(relative: str, pattern: str) -> str:
        match = re.search(pattern, (ROOT / relative).read_text(encoding="utf-8"), re.MULTILINE)
        if not match:
            raise ValueError(f"版本 SSOT 声明缺失：{relative}")
        return match[1]

    declarations = (
        field("server/app/__init__.py", r'^__version__\s*=\s*"([^"]+)"\s*$'),
        field("deployment/version.ps1", r'^\$ExpectedServerVersion\s*=\s*"([^"]+)"\s*$'),
        tomllib.loads((SERVER / "pyproject.toml").read_text(encoding="utf-8"))["project"][
            "version"
        ],
    )
    contracts = (
        field("server/app/__init__.py", r"^SERVER_API_CONTRACT\s*=\s*(\d+)\s*$"),
        field("deployment/version.ps1", r"^\$RequiredApiContract\s*=\s*(\d+)\s*$"),
    )
    android = "android/app/build.gradle.kts"
    if (
        any(value != VERSION for value in declarations)
        or any(int(value) != API_CONTRACT for value in contracts)
        or field(android, r'^\s*versionName\s*=\s*"([^"]+)"\s*$') != ANDROID_VERSION
        or int(field(android, r"^\s*versionCode\s*=\s*(\d+)\s*$")) != ANDROID_CODE
        or field(android, r'^\s*applicationId\s*=\s*"([^"]+)"\s*$') != ANDROID_PACKAGE
    ):
        raise ValueError("版本 SSOT 不一致：源码、部署声明与 builder 必须匹配")


def package_sources(*, include_artifacts: bool = True) -> dict[str, Path]:
    """RC 实际复制文件的唯一清单：目标相对路径 -> 源文件。"""
    sources = {f"scripts/{name}": DEPLOY / "scripts" / name for name in ALL_SCRIPTS}
    sources.update(
        {
            f"scripts/{path.name}": path
            for path in sorted((DEPLOY / "scripts").glob("*.ps1"))
            if path.is_file()
        }
    )
    sources.update({name: DEPLOY / name for name in ("version.ps1", "config.example.json")})
    sources.update(
        {
            name: ROOT / name
            for name in (
                "LICENSE",
                "THIRD_PARTY_NOTICES.md",
                "UPGRADE_ROLLBACK.md",
                "USER_INSTALL_GUIDE.md",
                "USER_LAN_VALIDATION.md",
            )
        }
    )
    sources["README.md"] = DEPLOY / "README.md"
    ffmpeg = ROOT / "third_party/ffmpeg"
    if (ffmpeg / "ffmpeg.exe").is_file():
        for name in ("ffmpeg.exe", "ffprobe.exe"):
            if (ffmpeg / name).is_file():
                sources[f"ffmpeg/{name}"] = ffmpeg / name
    if include_artifacts:
        sources.update(
            {
                f"MediaReviewServer/{path.relative_to(DIST).as_posix()}": path
                for path in sorted(DIST.rglob("*"))
                if path.is_file()
            }
        )
        sources["app-release.apk"] = APK_DIR / "release/app-release.apk"
    return sources


def package_inputs(*, include_artifacts: bool = True) -> dict[str, str]:
    return {
        source.relative_to(ROOT).as_posix(): sha256(source)
        for source in package_sources(include_artifacts=include_artifacts).values()
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


def verify_receipted_package(zip_path: Path, package: Path, expected: dict[str, str]) -> None:
    """最终内容必须连接原 receipt；现场 SHA 清单不是新的信任根。"""
    members = {f"{package.name}/{relative}" for relative in expected}
    members.add(f"{package.name}/SHA256SUMS.txt")
    with zipfile.ZipFile(zip_path) as archive:
        if set(archive.namelist()) != members or len(archive.namelist()) != len(members):
            raise ValueError("ZIP receipt 成员集合不匹配")
        for relative, digest in expected.items():
            if hashlib.sha256(archive.read(f"{package.name}/{relative}")).hexdigest() != digest:
                raise ValueError(f"ZIP 内容与原 receipt 证据不匹配：{relative}")
    staging = {
        path.relative_to(package).as_posix(): sha256(path)
        for path in package.rglob("*")
        if path.is_file() and path.relative_to(package).as_posix() != "SHA256SUMS.txt"
    }
    if staging != expected:
        raise ValueError("staging 内容与原 receipt 证据不匹配")


def prepare() -> dict:
    build_head = git_head(require_clean=True)
    OUT.mkdir(exist_ok=True)
    EVIDENCE.unlink(missing_ok=True)
    verify_version_ssot()
    before = source_inputs()
    package_before = package_inputs(include_artifacts=False)
    build_exe()
    apk = build_release_apk()
    exe = DIST / "MediaReviewServer.exe"
    evidence = {
        "server": verify_server_binary(exe),
        "android": verify_apk(apk),
        "server_sha256": sha256(exe),
        "server_files": server_files(),
        "apk_sha256": sha256(apk),
        "build_inputs": before,
        "package_inputs": package_inputs(),
        "build_git_head": build_head,
        "verified_at": dt.datetime.now(dt.UTC).isoformat(),
    }
    if source_inputs() != before or package_inputs(include_artifacts=False) != package_before:
        raise ValueError("构建期间源码或打包输入变化，拒绝记录新鲜构建证据")
    EVIDENCE.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Fresh EXE health / APK manifest / signature PASS; build evidence recorded.")
    return evidence


def package_verified() -> Path:
    head = git_head(require_clean=True)
    evidence = json.loads(EVIDENCE.read_text(encoding="utf-8"))
    if not isinstance(evidence, dict) or not {"build_inputs", "package_inputs"}.issubset(evidence):
        raise ValueError("旧构建证据缺少 build_inputs/package_inputs，必须重新 prepare")
    age = dt.datetime.now(dt.UTC) - dt.datetime.fromisoformat(evidence["verified_at"])
    exe, apk = DIST / "MediaReviewServer.exe", APK_DIR / "release/app-release.apk"
    if (
        not 0 <= age.total_seconds() <= 6 * 3600
        or evidence["build_inputs"] != source_inputs()
        or evidence["package_inputs"] != package_inputs()
        or evidence["server_sha256"] != sha256(exe)
        or evidence["server_files"] != server_files()
        or evidence["apk_sha256"] != sha256(apk)
    ):
        raise ValueError("新鲜构建证据失效，禁止复用旧 EXE/APK")
    verify_version_ssot()
    evidence["server"] = verify_server_binary(exe)
    evidence["android"] = verify_apk(apk)
    if (
        evidence["build_inputs"] != source_inputs()
        or evidence["package_inputs"] != package_inputs()
    ):
        raise ValueError("产物验证期间输入变化，构建证据失效")
    manifest = release_manifest(evidence, head, exe, apk)
    manifest["build_git_head"] = evidence["build_git_head"]
    manifest["build_verified_at"] = evidence["verified_at"]
    manifest["source_input_sha256"] = evidence["build_inputs"]
    manifest["build_input_sha256"] = evidence["build_inputs"]
    manifest["package_input_sha256"] = evidence["package_inputs"]
    name = current_package_name()
    package = OUT / name
    zip_path = OUT / f"{name}.zip"
    user_apk = OUT / current_apk_name()
    checksum_path = OUT / f"{name}_SHA256.txt"
    outputs = (package, zip_path, user_apk, checksum_path)
    if any(path.exists() for path in outputs):
        raise ValueError("发布产物已存在，拒绝覆盖历史产物")
    sources = package_sources()
    expected = {
        relative: evidence["package_inputs"][source.relative_to(ROOT).as_posix()]
        for relative, source in sources.items()
    }
    if (
        expected["MediaReviewServer/MediaReviewServer.exe"] != evidence["server_sha256"]
        or expected["app-release.apk"] != evidence["apk_sha256"]
        or manifest["server_exe_sha256"] != evidence["server_sha256"]
        or manifest["apk_sha256"] != evidence["apk_sha256"]
    ):
        raise ValueError("原 receipt 的 EXE/APK 证据不一致")
    generated = {
        "HANDOVER.md": render_handover(manifest),
        "RELEASE_MANIFEST.json": json.dumps(manifest, ensure_ascii=False, indent=2),
    }
    expected.update(
        {
            relative: hashlib.sha256(text.encode("utf-8")).hexdigest()
            for relative, text in generated.items()
        }
    )
    # 仅清理本轮创建的输出；历史交付在上面的存在检查中受保护。
    created = []
    try:
        package.mkdir()
        created.append(package)
        for relative, source in sources.items():
            destination = package / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
            if sha256(destination) != expected[relative]:
                raise ValueError("复制期间打包输入变化，构建证据失效")
        for relative, text in generated.items():
            (package / relative).write_bytes(text.encode("utf-8"))
        write_sha256(package)
        with zipfile.ZipFile(zip_path, "x", zipfile.ZIP_DEFLATED) as archive:
            created.append(zip_path)
            for path in sorted(package.rglob("*")):
                if path.is_file():
                    archive.write(path, f"{name}/{path.relative_to(package).as_posix()}")
        count = verify_zip(zip_path, name)
        verify_receipted_package(zip_path, package, expected)
        # 从已核对的 staging 导出，不再从变化中的 live APK 取第二份内容。
        with user_apk.open("xb"):
            created.append(user_apk)
        shutil.copy2(package / "app-release.apk", user_apk)
        if sha256(user_apk) != evidence["apk_sha256"]:
            raise ValueError("独立 APK 与原 receipt 证据不匹配")
        zip_digest = sha256(zip_path)
        checksum_text = (
            f"git_head {head}\n"
            f"zip {zip_path.name} {zip_path.stat().st_size} bytes {zip_digest}\n"
            f"apk {user_apk.name} {user_apk.stat().st_size} bytes {evidence['apk_sha256']}\n"
            f"exe MediaReviewServer.exe {evidence['server_sha256']}\n"
        )
        with checksum_path.open("x", encoding="utf-8", newline="\n") as handle:
            created.append(checksum_path)
            handle.write(checksum_text)
        # 最后快照检查覆盖 ZIP、独立 APK 和摘要导出的全过程。
        verify_zip(zip_path, name)
        verify_receipted_package(zip_path, package, expected)
        if (
            sha256(zip_path) != zip_digest
            or sha256(user_apk) != evidence["apk_sha256"]
            or checksum_path.read_text(encoding="utf-8") != checksum_text
        ):
            raise ValueError("最终交付产物与原 receipt 证据不匹配")
        if (
            evidence["build_inputs"] != source_inputs()
            or evidence["package_inputs"] != package_inputs()
            or evidence["server_files"] != server_files()
            or evidence["server_sha256"] != sha256(exe)
            or evidence["apk_sha256"] != sha256(apk)
            or git_head(require_clean=True) != head
        ):
            raise ValueError("导出期间源码、打包输入或产物变化，构建证据失效")
    except BaseException:
        for path in reversed(created):
            if path == package:
                clean_generated(path, package)
            else:
                path.unlink(missing_ok=True)
        raise
    print(f"RC INTEGRITY PASS: {count} SHA256 entries; {zip_path}")
    return zip_path


VERIFIED_RC_SERVER_VERSION = "1.2.2"
VERIFIED_RC_ANDROID_VERSION = "2.0.0-alpha8"
VERIFIED_RC_ANDROID_CODE = 15

# 原 Stage8E 正式 RC inventory 是完整 dist 的信任锚，不能接受现场重算的自声明清单。
VERIFIED_RC_INVENTORY_SHA256 = "a1e784495ce1756761cc202949e7fbcf6630edb8ad3d59ec4ace2942045eef3d"
VERIFIED_RUNTIME_HEAD = "a080422b0d03ca64f48b77856b54776ca265a15b"
VERIFIED_APK_SHA256 = "600aa647aae8731e3b241592db9b303295f2cb04c4be6cb0c5011b418185bfab"
VERIFIED_EXE_SHA256 = "51a1f9d26d7c4d0b8780221b4c7318231b7ff6efedf1fe4a00baad498e3ab3c0"


def runtime_inputs() -> dict[str, str]:
    return {
        key: value for key, value in source_inputs().items() if key != "scripts/build_deploy.py"
    }


def verified_rc(source_rc: Path, manifest_path: Path) -> tuple[dict, dict[str, str]]:
    if source_rc.is_symlink() or getattr(source_rc.stat(), "st_file_attributes", 0) & 0x400:
        raise ValueError("原 RC 目录禁止链接/reparse")
    source_rc = source_rc.resolve()
    if sha256(source_rc / "SHA256SUMS.txt") != VERIFIED_RC_INVENTORY_SHA256:
        raise ValueError("原正式 RC inventory 信任锚不匹配")
    if manifest_path.resolve() != source_rc / "RELEASE_MANIFEST.json":
        raise ValueError("必须显式指定同一原正式 RC 的 manifest")
    expected = {}
    for line in (source_rc / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if not match:
            raise ValueError("原 RC SHA256 格式无效")
        digest, relative = match.groups()
        if (
            relative in expected
            or "\\" in relative
            or ":" in relative
            or relative.startswith("/")
            or any(part in ("", ".", "..") for part in relative.split("/"))
            or relative == "SHA256SUMS.txt"
        ):
            raise ValueError("原 RC SHA256 路径无效或重复")
        expected[relative] = digest
    actual = {}
    for path in source_rc.rglob("*"):
        if path.is_symlink() or (
            hasattr(path.stat(), "st_file_attributes") and path.stat().st_file_attributes & 0x400
        ):
            raise ValueError("原 RC 禁止链接/reparse")
        if path.is_file() and path.relative_to(source_rc).as_posix() != "SHA256SUMS.txt":
            actual[path.relative_to(source_rc).as_posix()] = sha256(path)
    if actual != expected:
        raise ValueError("原 RC 完整文件集合或 SHA256 不匹配")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    required = {
        "server_version": VERIFIED_RC_SERVER_VERSION,
        "api_contract": API_CONTRACT,
        "android_version_name": VERIFIED_RC_ANDROID_VERSION,
        "android_version_code": VERIFIED_RC_ANDROID_CODE,
        "android_package": ANDROID_PACKAGE,
        "apk_signature": "PASS",
        "git_head": VERIFIED_RUNTIME_HEAD,
        "apk_sha256": VERIFIED_APK_SHA256,
        "server_exe_sha256": VERIFIED_EXE_SHA256,
    }
    if any(manifest.get(key) != value for key, value in required.items()):
        raise ValueError("原正式 RC provenance/版本/二进制身份不匹配")
    if (
        expected.get("app-release.apk") != VERIFIED_APK_SHA256
        or expected.get("MediaReviewServer/MediaReviewServer.exe") != VERIFIED_EXE_SHA256
        or not re.fullmatch(r"[0-9a-f]{40}", manifest.get("build_git_head", ""))
        or not manifest.get("build_verified_at")
    ):
        raise ValueError("原正式 RC 缺少已验证构建 provenance")
    provenance = manifest.get("build_input_sha256", manifest.get("source_input_sha256"))
    if not isinstance(provenance, dict) or runtime_inputs() != {
        key: value for key, value in provenance.items() if key != "scripts/build_deploy.py"
    }:
        raise ValueError("运行时源输入与原正式 RC provenance 不匹配")
    return manifest, actual


def repackage_snapshot(source_rc: Path, manifest_path: Path) -> dict:
    manifest, files = verified_rc(source_rc, manifest_path)
    verify_version_ssot()
    return {
        "source_rc": str(source_rc.resolve()),
        "source_manifest": manifest,
        "source_files": files,
        "source_sha256sums": sha256(source_rc / "SHA256SUMS.txt"),
        "runtime_inputs": runtime_inputs(),
        "package_inputs": package_inputs(include_artifacts=False),
        "builder_sha256": sha256(Path(__file__)),
        "package_git_head": git_head(require_clean=True),
    }


def repackage_receipt_path(receipt: Path, source_rc: Path, *, creating: bool) -> Path:
    """收据仅允许 OUT 内扁平 JSON；先检查原路径，再规范化，禁止重定向。"""
    for candidate in (receipt, OUT):
        for ancestor in (candidate, *candidate.parents):
            try:
                info = ancestor.lstat()
            except FileNotFoundError:
                continue
            if ancestor.is_symlink() or getattr(info, "st_file_attributes", 0) & 0x400:
                raise ValueError("repackage receipt 路径禁止链接/reparse")
    resolved = receipt.resolve()
    output = OUT.resolve()
    source = source_rc.resolve()
    if (
        resolved.parent != output
        or resolved.is_relative_to(source)
        or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*\.json", receipt.name)
    ):
        raise ValueError("repackage receipt 仅允许 OUT 内新的扁平 JSON 文件")
    if creating and resolved.exists():
        raise ValueError("repackage receipt 已存在，拒绝覆盖；请指定新的 JSON 文件名")
    if not creating and not resolved.is_file():
        raise ValueError("repackage receipt 必须是已有普通 JSON 文件")
    return resolved


def prepare_repackage(source_rc: Path, manifest_path: Path, receipt: Path | None = None) -> Path:
    receipt = repackage_receipt_path(
        receipt or OUT / "stage8e2-repackage-evidence.json", source_rc, creating=True
    )
    snapshot = repackage_snapshot(source_rc, manifest_path)
    if snapshot != repackage_snapshot(source_rc, manifest_path):
        raise ValueError("准备期间输入变化")
    OUT.mkdir(exist_ok=True)
    receipt = repackage_receipt_path(receipt, source_rc, creating=True)
    with receipt.open("x", encoding="utf-8") as stream:
        json.dump(
            {
                "mode": "repackage-verified",
                "verified_at": dt.datetime.now(dt.UTC).isoformat(),
                **snapshot,
            },
            stream,
            ensure_ascii=False,
            indent=2,
        )
    print(f"REPACKAGE RECEIPT: {receipt}")
    return receipt


def repackage_verified(source_rc: Path, manifest_path: Path, receipt: Path) -> Path:
    receipt = repackage_receipt_path(receipt, source_rc, creating=False)
    evidence = json.loads(receipt.read_text(encoding="utf-8"))
    snapshot = repackage_snapshot(source_rc, manifest_path)
    age = dt.datetime.now(dt.UTC) - dt.datetime.fromisoformat(evidence["verified_at"])
    if (
        evidence.get("mode") != "repackage-verified"
        or not 0 <= age.total_seconds() <= 21600
        or any(evidence.get(key) != value for key, value in snapshot.items())
    ):
        raise ValueError("repackage receipt 失效：源码、脚本、原 RC 或 HEAD 变化")
    manifest = dict(snapshot["source_manifest"])
    head = snapshot["package_git_head"]
    manifest.update(
        runtime_build_git_head=manifest["build_git_head"],
        runtime_release_git_head=manifest["git_head"],
        runtime_source_inputs=snapshot["runtime_inputs"],
        package_git_head=head,
        git_head=head,
        package_input_sha256=snapshot["package_inputs"],
        original_release_manifest_sha256=snapshot["source_files"]["RELEASE_MANIFEST.json"],
        repackaged_at=evidence["verified_at"],
    )
    name = f"MediaReview_RC_Alpha8_Server-{VERIFIED_RC_SERVER_VERSION}_{head[:7]}"
    package, zip_path = OUT / name, OUT / f"{name}.zip"
    if package.exists() or zip_path.exists():
        raise ValueError("发布产物已存在，拒绝覆盖历史产物")
    sources = package_sources(include_artifacts=False)
    expected = {
        relative: snapshot["package_inputs"][path.relative_to(ROOT).as_posix()]
        for relative, path in sources.items()
    }
    for relative, digest in snapshot["source_files"].items():
        if relative == "app-release.apk" or relative.startswith("MediaReviewServer/"):
            sources[relative] = source_rc / relative
            expected[relative] = digest
    generated = {
        "RELEASE_MANIFEST.json": json.dumps(manifest, ensure_ascii=False, indent=2),
        "HANDOVER.md": render_handover(manifest),
    }
    expected.update(
        {key: hashlib.sha256(value.encode("utf-8")).hexdigest() for key, value in generated.items()}
    )
    created = []
    try:
        package.mkdir()
        created.append(package)
        for relative, path in sources.items():
            destination = package / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, destination)
            if sha256(destination) != expected[relative]:
                raise ValueError("复制期间输入变化")
        for relative, content in generated.items():
            (package / relative).write_bytes(content.encode("utf-8"))
        write_sha256(package)
        with zipfile.ZipFile(zip_path, "x", zipfile.ZIP_DEFLATED) as archive:
            created.append(zip_path)
            for path in sorted(package.rglob("*")):
                if path.is_file():
                    archive.write(path, f"{name}/{path.relative_to(package).as_posix()}")
        verify_zip(zip_path, name)
        verify_receipted_package(zip_path, package, expected)
        if snapshot != repackage_snapshot(source_rc, manifest_path):
            raise ValueError("最终输入或 HEAD 漂移，receipt 失效")
        verify_zip(zip_path, name)
        verify_receipted_package(zip_path, package, expected)
        if snapshot != repackage_snapshot(source_rc, manifest_path):
            raise ValueError("最终 HEAD 或输入漂移")
    except BaseException:
        for path in reversed(created):
            if path == package:
                clean_generated(path, package)
            else:
                path.unlink(missing_ok=True)
        raise
    return zip_path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_mutually_exclusive_group()
    actions.add_argument("--prepare-only", action="store_true")
    actions.add_argument("--package-verified", action="store_true")
    actions.add_argument(
        "--prepare-repackage",
        action="store_true",
        help="只读验证原正式 RC 并记录重打包 receipt，不构建",
    )
    actions.add_argument(
        "--repackage-verified",
        action="store_true",
        help="消费 prepare-repackage receipt；HEAD/脚本/原 RC 漂移均拒绝",
    )
    parser.add_argument(
        "--source-rc", type=Path, help="原正式 RC 解压目录，包含完整 Server dist 与同一 APK"
    )
    parser.add_argument(
        "--source-manifest", type=Path, help="该原正式 RC 内的 RELEASE_MANIFEST.json"
    )
    parser.add_argument("--repackage-receipt", type=Path, help="prepare-repackage receipt 路径")
    args = parser.parse_args()
    if args.prepare_repackage or args.repackage_verified:
        if not args.source_rc or not args.source_manifest:
            parser.error("重打包必须显式指定 --source-rc 和 --source-manifest")
        if args.repackage_verified and not args.repackage_receipt:
            parser.error("最终重打包必须显式指定 --repackage-receipt")
    try:
        if args.prepare_repackage:
            prepare_repackage(args.source_rc, args.source_manifest, args.repackage_receipt)
            return 0
        if args.repackage_verified:
            repackage_verified(args.source_rc, args.source_manifest, args.repackage_receipt)
            return 0
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
