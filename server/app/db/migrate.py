"""程序化运行 Alembic 迁移,应用启动时自动升级到最新版本。"""

from __future__ import annotations

import sys
from pathlib import Path

from alembic import command
from alembic.config import Config


def _server_root() -> Path:
    """server 根目录:开发时是源码目录;PyInstaller 冻结后是解包目录。"""
    if getattr(sys, "frozen", False):  # pragma: no cover - 仅部署形态走此分支
        return Path(getattr(sys, "_MEIPASS", "."))
    return Path(__file__).resolve().parents[2]


def run_migrations(database_url: str) -> None:
    root = _server_root()
    alembic_cfg = Config(str(root / "alembic.ini"))
    alembic_cfg.set_main_option("script_location", str(root / "app" / "db" / "migrations"))
    alembic_cfg.set_main_option("sqlalchemy.url", database_url)
    command.upgrade(alembic_cfg, "head")
