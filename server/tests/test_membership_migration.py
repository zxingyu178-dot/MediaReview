"""归属迁移仅使用可丢弃数据库。"""
import pytest
import sqlalchemy as sa
from alembic import command
from alembic.config import Config

from app.db.migrate import _server_root


def test_membership_upgrade_preserves_ids_and_refuses_lossy_downgrade(tmp_path):
    root = _server_root()
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "app/db/migrations"))
    url = f"sqlite:///{tmp_path / 'migration.db'}"
    config.set_main_option("sqlalchemy.url", url)
    command.upgrade(config, "0015")
    engine = sa.create_engine(url)
    try:
        with engine.begin() as connection:
            connection.execute(sa.text("INSERT INTO media_cache_index (media_id, jellyfin_id, library_id, name, media_type, fingerprint, is_available) VALUES ('stable', 'jf-stable', 'parent', '样本', 'video', 'fp', 1)"))
            connection.execute(sa.text("INSERT INTO favorite (media_id, created_at) VALUES ('stable', CURRENT_TIMESTAMP)"))
            connection.execute(sa.text("INSERT INTO media_cache_index (media_id, source_type, jellyfin_id, library_id, name, media_type, fingerprint, is_available) VALUES ('local-stable', 'local', 'local:stable', 'local_root', '本地样本', 'image', 'fp-local', 0)"))
        command.upgrade(config, "head")
        with engine.begin() as connection:
            assert connection.execute(sa.text("SELECT media_id, library_id, is_available FROM media_library_membership")).one() == ("stable", "parent", 1)
            assert connection.scalar(sa.text("SELECT media_id FROM favorite")) == "stable"
            assert connection.scalar(sa.text("SELECT is_available FROM media_cache_index WHERE media_id = 'local-stable'")) == 0
        command.downgrade(config, "0015")
        command.upgrade(config, "head")
        with engine.begin() as connection:
            connection.execute(sa.text("INSERT INTO media_library_membership (media_id, library_id, is_available) VALUES ('stable', 'child', 1)"))
        with pytest.raises(RuntimeError, match="跨库归属"):
            command.downgrade(config, "0015")
        with engine.connect() as connection:
            assert connection.scalar(sa.text("SELECT count(*) FROM media_library_membership")) == 2
            assert connection.scalar(sa.text("PRAGMA integrity_check")) == "ok"
    finally:
        engine.dispose()
