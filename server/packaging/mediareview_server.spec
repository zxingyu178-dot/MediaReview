# -*- mode: python ; coding: utf-8 -*-
"""MediaReview Server PyInstaller spec(onedir,便于打包成部署 ZIP)。

用法:
    pyinstaller packaging/mediareview_server.spec --distpath dist --workpath build/pyinstaller

产物: dist/MediaReviewServer/ 目录,内含 MediaReviewServer.exe + _internal/(Python 运行时 + app + 迁移)。
"""
from PyInstaller.utils.hooks import collect_submodules

block_cipher = None

datas = [
    # alembic 迁移脚本与模板(运行时按 _MEIPASS 相对定位)
    ("../alembic.ini", "."),
    ("../app/db/migrations", "app/db/migrations"),
]

hiddenimports = (
    collect_submodules("uvicorn")
    + collect_submodules("uvicorn.protocols")
    + collect_submodules("uvicorn.loops")
    + collect_submodules("uvicorn.logging")
    + collect_submodules("sqlalchemy")
    + collect_submodules("alembic")
    + collect_submodules("app")
)

a = Analysis(
    ["../run_server.py"],
    pathex=[".."],
    binaries=[],
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)
pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="MediaReviewServer",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name="MediaReviewServer",
)
