#!/usr/bin/env python3
"""MediaReview 1.2.0 部署包构建。

产出根目录固定为 ``MediaReview_Migration_1.2.0``,可执行文件名为 ``MediaReviewServer.exe``。

步骤:
1. 调用 PyInstaller 构建 Server EXE(server/dist/MediaReviewServer/MediaReviewServer.exe)
2. 若存在 third_party/ffmpeg/{ffmpeg.exe,ffprobe.exe} 则一并打包
3. 复制 8 个部署脚本(config.example.json 模板)
4. 复制 Android APK(优先 release,否则 debug)
5. 复制交接文档(LICENSE/THIRD_PARTY_NOTICES/README/HANDOVER/install-upgrade-rollback 等)
6. 生成 SHA-256 校验和 SHA256SUMS.txt
7. 压缩为 deploy_handoff/MediaReview_Migration_1.2.0_<时间戳>.zip 并回验校验和

用法(项目根):
    python scripts/build_deploy.py
"""

from __future__ import annotations

import datetime as dt
import hashlib
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SERVER = ROOT / "server"
DIST = SERVER / "dist" / "MediaReviewServer"
DEPLOY = ROOT / "deployment"
OUT = ROOT / "deploy_handoff"
FFMPEG_SRC = ROOT / "third_party" / "ffmpeg"
APK_DIR = ROOT / "android" / "app" / "build" / "outputs" / "apk"

VERSION = "1.2.0"
PACKAGE_ROOT = f"MediaReview_Migration_{VERSION}"
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
DOCS = (
    "README.md",
    "LICENSE",
    "THIRD_PARTY_NOTICES.md",
    "HANDOVER.md",
    "UPGRADE_ROLLBACK.md",
)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def build_exe() -> None:
    """用 PyInstaller 构建 Server EXE(onedir)。"""
    print("==> 构建 Server EXE ...")
    subprocess.run(  # noqa: S603
        [
            str(SERVER / ".venv" / "Scripts" / "python.exe"),
            "-m",
            "PyInstaller",
            "packaging/mediareview_server.spec",
            "--distpath",
            "dist",
            "--workpath",
            "build/pyinstaller",
            "--noconfirm",
        ],
        cwd=SERVER,
        check=True,
    )


def find_apk() -> Path | None:
    release = APK_DIR / "release" / "app-release.apk"
    if release.is_file():
        return release
    debug = APK_DIR / "debug" / "app-debug.apk"
    if debug.is_file():
        return debug
    return None


def write_sha256(root: Path) -> None:
    """递归计算 root 下所有文件 SHA-256,写 SHA256SUMS.txt。"""
    sums: list[str] = []
    for f in sorted(p for p in root.rglob("*") if p.is_file() and p.name != "SHA256SUMS.txt"):
        rel = f.relative_to(root).as_posix()
        sums.append(f"{sha256(f)}  {rel}")
    (root / "SHA256SUMS.txt").write_text("\n".join(sums) + "\n", encoding="utf-8")


def main() -> int:
    # 0. RC/正式包必须带签名 Release APK;仅显式 --allow-debug 时才允许回退 debug。
    import argparse

    parser = argparse.ArgumentParser(description="构建 MediaReview 1.2.0 部署包")
    parser.add_argument(
        "--allow-debug",
        action="store_true",
        help="Release APK 缺失时允许回退 debug APK(仅临时调试用)",
    )
    args = parser.parse_args()

    # 1. 构建 EXE
    if not (DIST / "MediaReviewServer.exe").exists():
        build_exe()
    if not (DIST / "MediaReviewServer.exe").exists():
        print("Server EXE 构建失败", file=sys.stderr)
        return 1

    # 2. 组装部署目录
    deploy_dir = OUT / PACKAGE_ROOT
    if deploy_dir.exists():
        shutil.rmtree(deploy_dir)
    (deploy_dir / "server").mkdir(parents=True)
    (deploy_dir / "scripts").mkdir()
    (deploy_dir / "ffmpeg").mkdir(exist_ok=True)

    print("==> 复制 Server ...")
    shutil.copytree(DIST, deploy_dir / "server" / "MediaReviewServer")

    print("==> 复制 8 个部署脚本与配置模板 ...")
    for script in ALL_SCRIPTS:
        shutil.copy(DEPLOY / "scripts" / script, deploy_dir / "scripts" / script)
    shutil.copy(DEPLOY / "config.example.json", deploy_dir / "config.example.json")

    # 3. FFmpeg(可选)
    if (FFMPEG_SRC / "ffmpeg.exe").exists():
        print("==> 打包 FFmpeg ...")
        shutil.copy(FFMPEG_SRC / "ffmpeg.exe", deploy_dir / "ffmpeg" / "ffmpeg.exe")
        if (FFMPEG_SRC / "ffprobe.exe").exists():
            shutil.copy(FFMPEG_SRC / "ffprobe.exe", deploy_dir / "ffmpeg" / "ffprobe.exe")
    else:
        print(
            "!! 未找到 third_party/ffmpeg/ffmpeg.exe,部署包不含 FFmpeg(install.ps1 会检测系统 PATH)"
        )

    # 4. Android APK(默认强制 release,缺失则失败;仅 --allow-debug 回退 debug)
    apk = find_apk()
    if apk and apk.name == "app-release.apk":
        print(f"==> 复制 APK: {apk.name}")
        shutil.copy(apk, deploy_dir / apk.name)
    elif apk and args.allow_debug:
        print(f"!! 警告: Release APK 缺失, 按 --allow-debug 回退 debug APK: {apk.name}")
        shutil.copy(apk, deploy_dir / apk.name)
    elif not apk:
        print("!! 未找到 APK(android/app/build/outputs/apk/{release,debug})", file=sys.stderr)
        return 1
    else:
        print(
            "Release APK 缺失,RC/正式包必须使用签名 Release APK;如需临时调试请显式加 --allow-debug",
            file=sys.stderr,
        )
        return 1

    # 5. 交接文档
    for doc in DOCS:
        src = ROOT / doc
        if src.is_file():
            shutil.copy(src, deploy_dir / doc)
        elif doc == "HANDOVER.md":
            (deploy_dir / doc).write_text(
                f"""# MediaReview 1.2.0 部署交接

## 版本
- Server: {VERSION}
- Android: {VERSION}
- 构建日期: {dt.datetime.now().strftime("%Y-%m-%d %H:%M")}

## 部署
1. 解压本 ZIP 到目标电脑。
2. 右键 `scripts/install.ps1` → 使用 PowerShell 以管理员运行。
3. 按提示确认 Jellyfin(编辑 `%ProgramData%\\MediaReview\\config\\config.json`)。
4. 浏览器打开 `http://<电脑IP>:8766/admin` 生成配对码。
5. 手机安装 APK,自动发现电脑,输入配对码完成。

## 数据位置
默认 `%ProgramData%\\MediaReview`(config/database/cache/logs)。

## 备份
至少备份 config 与 database;cache 可重建。

## 升级/回滚
见 `UPGRADE_ROLLBACK.md`。

## 故障
先运行 `scripts/diagnose.ps1` 导出诊断包。
""",
                encoding="utf-8",
            )
        else:
            print(f"!! 缺少文档: {doc}")

    # 6. SHA-256
    print("==> 生成 SHA256SUMS.txt ...")
    write_sha256(deploy_dir)

    # 7. 压缩 ZIP
    OUT.mkdir(exist_ok=True)
    stamp = dt.datetime.now().strftime("%Y%m%d_%H%M")
    zip_path = OUT / f"{PACKAGE_ROOT}_{stamp}.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(p for p in deploy_dir.rglob("*") if p.is_file()):
            z.write(f, f.relative_to(OUT))

    # 8. 回验每个校验和(在 ZIP 内核对一次)
    with zipfile.ZipFile(zip_path) as z:
        names = set(z.namelist())
        sums_file = next(n for n in names if n.endswith("SHA256SUMS.txt"))
        sums = z.read(sums_file).decode("utf-8").splitlines()
        checked = 0
        for line in sums:
            digest, rel = line.split("  ", 1)
            entry = f"{PACKAGE_ROOT}/{rel}".replace("\\", "/")
            if entry not in names:
                raise SystemExit(f"ZIP 内缺少文件: {entry}")
            actual = hashlib.sha256(z.read(entry)).hexdigest()
            if actual != digest:
                raise SystemExit(f"ZIP 校验和失败: {entry}\n  期望 {digest}\n  实际 {actual}")
            checked += 1
    print(f"==> ZIP 内校验和回验通过: {checked} 个文件")

    shutil.rmtree(deploy_dir)
    print(f"==> 部署包已生成: {zip_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
