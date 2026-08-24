#!/usr/bin/env python3
"""MediaReview 部署包构建(Stage 16)。

步骤:
1. 调用 PyInstaller 构建 Server EXE(server/dist/MediaReviewServer)
2. 若存在 third_party/ffmpeg/{ffmpeg.exe,ffprobe.exe} 则一并打包
3. 组装部署目录并输出 ZIP

用法(项目根):
    python scripts/build_deploy.py
"""
from __future__ import annotations

import datetime as dt
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


def build_exe() -> None:
    """用 PyInstaller 构建 Server EXE(onedir)。"""
    print("==> 构建 Server EXE ...")
    subprocess.run(
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


def main() -> int:
    # 1. 构建 EXE
    if not (DIST / "Mediaserver.exe").exists():
        build_exe()
    if not (DIST / "Mediaserver.exe").exists():
        print("Server EXE 构建失败", file=sys.stderr)
        return 1

    # 2. 组装部署目录
    version = "0.8.1"
    deploy_dir = ROOT / "deploy_handoff" / f"MediaReviewServer-{version}"
    if deploy_dir.exists():
        shutil.rmtree(deploy_dir)
    (deploy_dir / "server").mkdir(parents=True)
    (deploy_dir / "scripts").mkdir()
    (deploy_dir / "ffmpeg").mkdir(exist_ok=True)

    print("==> 复制 Server ...")
    shutil.copytree(DIST, deploy_dir / "server" / "MediaReviewServer")

    print("==> 复制脚本与配置 ...")
    for script in ("install.ps1", "repair.ps1", "uninstall.ps1", "diagnose.ps1"):
        shutil.copy(DEPLOY / "scripts" / script, deploy_dir / "scripts" / script)
    shutil.copy(DEPLOY / "config.example.json", deploy_dir / "config.example.json")

    # 3. FFmpeg(可选)
    if (FFMPEG_SRC / "ffmpeg.exe").exists():
        print("==> 打包 FFmpeg ...")
        shutil.copy(FFMPEG_SRC / "ffmpeg.exe", deploy_dir / "ffmpeg" / "ffmpeg.exe")
        if (FFMPEG_SRC / "ffprobe.exe").exists():
            shutil.copy(FFMPEG_SRC / "ffprobe.exe", deploy_dir / "ffmpeg" / "ffprobe.exe")
    else:
        print("!! 未找到 third_party/ffmpeg/ffmpeg.exe,部署包不含 FFmpeg(install.ps1 会检查)")

    # 4. 交接文档
    handover = deploy_dir / "HANDOVER.md"
    handover.write_text(
        f"""# MediaReview Server 交接说明

## 版本
- Server: {version}
- Android: 0.9.1
- 构建日期: {dt.datetime.now().strftime('%Y-%m-%d %H:%M')}

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

## 故障
先运行 `scripts/diagnose.ps1` 导出诊断包。
""",
        encoding="utf-8",
    )
    shutil.copy(DEPLOY / "README.md", deploy_dir / "README.md")

    # 5. 压缩 ZIP
    OUT.mkdir(exist_ok=True)
    stamp = dt.datetime.now().strftime("%Y%m%d_%H%M")
    zip_path = OUT / f"MediaReviewServer-{version}_deploy_{stamp}.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for f in deploy_dir.rglob("*"):
            if f.is_file():
                z.write(f, f.relative_to(OUT))
    shutil.rmtree(deploy_dir)
    print(f"==> 部署包已生成: {zip_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
