"""数据库初始化与迁移测试。"""

from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient
from sqlalchemy import inspect, text

from app.db.models import AppSetting, utc_now


def test_startup_migration_initializes_db(client: TestClient, data_root: Path) -> None:
    """应用启动即完成迁移: alembic_version 与业务表都存在。"""
    database = client.app.state.database
    inspector = inspect(database.engine)
    tables = set(inspector.get_table_names())
    assert "app_settings" in tables
    assert "alembic_version" in tables


def test_settings_roundtrip(client: TestClient) -> None:
    database = client.app.state.database
    with database.session() as session:
        session.merge(AppSetting(key="ui.grid_columns", value="3"))
        session.commit()
    with database.session() as session:
        row = session.get(AppSetting, "ui.grid_columns")
        assert row is not None
        assert row.value == "3"
        assert row.updated_at is not None


def test_wal_mode_enabled(client: TestClient) -> None:
    database = client.app.state.database
    with database.engine.connect() as connection:
        mode = connection.execute(text("PRAGMA journal_mode")).scalar()
    assert str(mode).lower() == "wal"


def test_utc_now_naive_utc() -> None:
    now = utc_now()
    assert now.tzinfo is None
