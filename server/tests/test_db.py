"""数据库初始化与迁移测试。"""

from __future__ import annotations

import time
from pathlib import Path

from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, event, inspect, text
from sqlalchemy.orm import Session

from app.db.migrate import _server_root
from app.db.models import AppSetting, utc_now
from app.services import media_index


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


def test_0010_query_indexes_serve_all_deterministic_sorts_on_100k_rows(
    tmp_path: Path,
) -> None:
    """在真实 0009→0010 schema 上验证全部排序、类型分支和硬性能门禁。"""
    database_path = tmp_path / "query-plan-0010.db"
    database_url = f"sqlite:///{database_path}"
    root = _server_root()
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "app" / "db" / "migrations"))
    config.set_main_option("sqlalchemy.url", database_url)
    command.upgrade(config, "0009")
    command.upgrade(config, "0010")

    engine = create_engine(database_url)
    with engine.begin() as connection:
        connection.execute(
            text(
                """
                WITH RECURSIVE seq(value) AS (
                    SELECT 0
                    UNION ALL
                    SELECT value + 1 FROM seq WHERE value < 99999
                )
                INSERT INTO media_cache_index (
                    media_id, jellyfin_id, library_id, name, media_type,
                    duration_ms, size_bytes, width, height, fingerprint,
                    created_at, is_available
                )
                SELECT
                    printf('%024x', value),
                    'jf-' || value,
                    'lib-big',
                    printf('movie-%06d', value),
                    CASE WHEN value % 2 = 0 THEN 'video' ELSE 'image' END,
                    CASE WHEN value % 20 = 0 THEN NULL ELSE value * 10 END,
                    CASE WHEN value % 20 = 0 THEN NULL ELSE value * 100 END,
                    CASE WHEN value % 20 = 0 THEN NULL ELSE 640 + value % 4 * 640 END,
                    CASE WHEN value % 20 = 0 THEN NULL ELSE 360 + value % 4 * 360 END,
                    'fp-' || value,
                    CASE WHEN value % 20 = 0 THEN NULL
                         ELSE datetime('2020-01-01', '+' || value || ' seconds') END,
                    1
                FROM seq
                """
            )
        )

    captured: list[tuple[str, object]] = []

    @event.listens_for(engine, "before_cursor_execute")
    def capture_ordered_select(_conn, _cursor, statement, parameters, _context, _executemany):
        normalized = " ".join(statement.upper().split())
        if normalized.startswith("SELECT") and "MEDIA_CACHE_INDEX" in normalized:
            if " ORDER BY " in normalized:
                captured.append((statement, parameters))

    try:
        with Session(engine) as session:
            for sort_by in ("name", "created", "size", "duration", "resolution", "random"):
                for sort_order in ("asc", "desc"):
                    for selected_type in (None, "video"):
                        captured.clear()
                        started = time.perf_counter()
                        rows, total = media_index.list_cached_media(
                            session,
                            library_ids=["lib-big"],
                            media_type=selected_type,
                            sort_by=sort_by,
                            sort_order=sort_order,
                            random_seed="plan-seed",
                            page=1000,
                            page_size=50,
                        )
                        elapsed = time.perf_counter() - started
                        expected_total = 50_000 if selected_type else 100_000
                        assert total == expected_total
                        assert len(rows) == 50, (
                            f"{sort_by}/{sort_order}/type={selected_type} returned {len(rows)}"
                        )
                        assert elapsed < 1.0, (
                            f"{sort_by}/{sort_order}/type={selected_type} took {elapsed:.3f}s"
                        )
                        if sort_by == "random":
                            continue
                        assert captured
                        with engine.connect() as connection:
                            for statement, parameters in captured:
                                plan = connection.exec_driver_sql(
                                    f"EXPLAIN QUERY PLAN {statement}", parameters
                                ).all()
                                detail = " | ".join(str(row[-1]) for row in plan)
                                assert "TEMP B-TREE" not in detail.upper(), (
                                    f"{sort_by}/{sort_order}/type={selected_type}: {detail}"
                                )
    finally:
        event.remove(engine, "before_cursor_execute", capture_ordered_select)
        engine.dispose()
