"""数据库初始化与迁移测试。"""

from __future__ import annotations

from pathlib import Path

from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient
from sqlalchemy import inspect, text

from app.db.migrate import _server_root
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


def test_0010_upgrades_existing_media_without_row_or_user_state_loss(tmp_path: Path) -> None:
    """0010 必须把旧索引置为可用，并保留媒体与收藏状态。"""
    database_path = tmp_path / "pre-0010.db"
    database_url = f"sqlite:///{database_path}"
    root = _server_root()
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "app" / "db" / "migrations"))
    config.set_main_option("sqlalchemy.url", database_url)
    command.upgrade(config, "0009")

    from sqlalchemy import create_engine

    engine = create_engine(database_url)
    with engine.begin() as connection:
        connection.execute(
            text(
                """
                INSERT INTO media_cache_index
                    (media_id, jellyfin_id, library_id, name, media_type, fingerprint)
                VALUES
                    ('aaaaaaaaaaaaaaaaaaaaaaaa', 'jf-a', 'lib-a', '旧缓存', 'video', 'fp-a')
                """
            )
        )
        connection.execute(
            text(
                "INSERT INTO favorite (media_id, created_at) "
                "VALUES ('aaaaaaaaaaaaaaaaaaaaaaaa', CURRENT_TIMESTAMP)"
            )
        )

    command.upgrade(config, "head")

    inspector = inspect(engine)
    columns = {column["name"] for column in inspector.get_columns("media_cache_index")}
    task_indexes = {index["name"]: index for index in inspector.get_indexes("background_task")}
    with engine.connect() as connection:
        media = connection.execute(
            text(
                "SELECT media_id, is_available, sync_generation, last_seen_at "
                "FROM media_cache_index"
            )
        ).one()
        favorite_count = connection.execute(text("SELECT count(*) FROM favorite")).scalar_one()

    assert {"is_available", "sync_generation", "last_seen_at"} <= columns
    assert media.media_id == "aaaaaaaaaaaaaaaaaaaaaaaa"
    assert media.is_available == 1
    assert media.sync_generation is None
    assert media.last_seen_at is None
    assert favorite_count == 1
    assert task_indexes["uq_background_task_active_target"]["unique"] == 1
