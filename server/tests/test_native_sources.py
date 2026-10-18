"""本地来源回归：仅使用 pytest 临时文件。"""
import json
from pathlib import Path

import pytest
import sqlalchemy as sa

from app.db.models import BackgroundTask, Favorite, MediaCacheIndex
from app.db.session import Database
from app.services import native_sources


@pytest.fixture
def native_db(tmp_path):
    db = Database(tmp_path / "index.sqlite")
    db.create_all()
    yield db
    db.dispose()


def scan(db, root_id):
    with db.session() as s:
        task = native_sources.schedule_scan(s, root_id)
        task.status = "running"
        task_id = task.task_id
        s.commit()
    native_sources.run_scan(db, task_id)
    with db.session() as s:
        return s.get(BackgroundTask, task_id)


def test_scan_stable_ids_incremental_and_offline_keeps_favorite(native_db, tmp_path):
    folder = tmp_path / "source"
    (folder / "a" / "b").mkdir(parents=True)
    photo = folder / "a" / "b" / "same.jpg"
    photo.write_bytes(b"temporary picture")
    with native_db.session() as s:
        root = native_sources.register_root(s, "临时来源", str(folder))
        root_id = root.root_id
        s.commit()
    assert scan(native_db, root_id).status == "succeeded"
    with native_db.session() as s:
        row = s.scalar(sa.select(MediaCacheIndex))
        mid = row.media_id
        assert mid.startswith("loc_") and len(mid) == 24
        assert row.source_type == "local" and row.media_path == "a/b/same.jpg"
        s.add(Favorite(media_id=mid))
        s.commit()
    task = scan(native_db, root_id)
    assert json.loads(task.result)["changed"] == 0
    photo.unlink()
    folder.rename(tmp_path / "offline")
    assert scan(native_db, root_id).status == "failed"
    with native_db.session() as s:
        assert not s.get(MediaCacheIndex, mid).is_available
        assert s.get(Favorite, mid) is not None


def test_identity_scoped_by_root_and_reject_unsafe_paths(native_db, tmp_path):
    assert native_sources.local_media_id("one", "a.jpg") != native_sources.local_media_id("two", "a.jpg")
    for path in ("relative", r"\\host\share", str(tmp_path / "missing")):
        with native_db.session() as s, pytest.raises(native_sources.SourcePathError):
            native_sources.register_root(s, "坏目录", path)
    root = tmp_path / "valid"
    root.mkdir()
    with native_db.session() as s:
        registered = native_sources.register_root(s, "临时", str(root))
        for relative in ("../escape.jpg", str(tmp_path / "escape.jpg"), "a/../../escape.jpg"):
            with pytest.raises(native_sources.SourcePathError):
                native_sources.safe_path(registered, relative)


def test_scan_skips_symlink(native_db, tmp_path):
    root = tmp_path / "valid"
    root.mkdir()
    outside = tmp_path / "outside.jpg"
    outside.write_bytes(b"never index")
    try:
        (root / "link.jpg").symlink_to(outside)
    except OSError:
        pytest.skip("Windows 未授权建立符号链接")
    with native_db.session() as s:
        row = native_sources.register_root(s, "临时", str(root))
        root_id = row.root_id
        s.commit()
    assert scan(native_db, root_id).status == "succeeded"
    with native_db.session() as s:
        assert s.scalar(sa.select(sa.func.count()).select_from(MediaCacheIndex)) == 0


def test_local_delete_rejected_even_forged_pending(native_db, tmp_path):
    from app.core.errors import ForbiddenError
    from app.db.models import DeleteQueue
    from app.services import delete_queue
    root = tmp_path / "sample"
    root.mkdir()
    file = root / "keep.jpg"
    file.write_bytes(b"keep sample")
    with native_db.session() as s:
        root_id = native_sources.register_root(s, "临时", str(root)).root_id
        s.commit()
    scan(native_db, root_id)
    with native_db.session() as s:
        row = s.scalar(sa.select(MediaCacheIndex))
        with pytest.raises(ForbiddenError):
            delete_queue.enqueue(s, row.media_id)
        # 模拟旧队列或恶意数据，即使绝对路径满足旧删除机制也必须拒绝。
        row.media_path = str(file)
        s.add(DeleteQueue(media_id=row.media_id, status="pending", size_bytes=file.stat().st_size))
        s.commit()
        nonce = delete_queue.prepare_commit(s).nonce
        outcome = delete_queue.commit_with_nonce(s, nonce)
        assert outcome[row.media_id] == "failed" and outcome.deleted_bytes == 0
        assert s.get(DeleteQueue, row.media_id).error == "local_source_read_only"
    assert file.read_bytes() == b"keep sample"


def test_native_api_no_jellyfin_no_path_leak_or_query_scan(app, tmp_path, monkeypatch):
    from fastapi.testclient import TestClient
    source = tmp_path / "source"
    source.mkdir()
    (source / "sample.jpg").write_bytes(b"sample")
    with TestClient(app, base_url="http://127.0.0.1", client=("127.0.0.1", 1234)) as client:
        response = client.post("/api/v1/sources/local", json={"name": "样本", "path": str(source)})
        assert response.status_code == 202, response.text
        root_id = response.json()["data"]["source"]["source_id"]
        task_id = response.json()["data"]["task"]["task_id"]
        with app.state.database.session() as s:
            s.get(BackgroundTask, task_id).status = "running"
            s.commit()
        native_sources.run_scan(app.state.database, task_id)
        monkeypatch.setattr(native_sources, "_walk", lambda *a: pytest.fail("GET must not scan"))
        assert client.get("/api/v1/libraries").status_code == 200
        page = client.get("/api/v1/media").json()
        assert page["data"]["items"][0]["source_type"] == "local"
        assert page["data"]["items"][0]["can_delete"] is False
        assert str(source) not in json.dumps(page)
        assert client.post("/api/v1/sources/local", json={"name": "样本", "path": str(source)},
                           headers={"Origin": "https://evil.invalid"}).status_code == 403
    with TestClient(app) as client:
        assert client.post(f"/api/v1/sources/{root_id}/scan").status_code == 403


def test_additive_migration_preserves_0014_rows_and_rollback(tmp_path):
    from alembic import command
    from alembic.config import Config
    from app.db.migrate import _server_root
    config = Config(str(_server_root() / "alembic.ini"))
    config.set_main_option("script_location", str(_server_root() / "app/db/migrations"))
    db_path = tmp_path / "old.sqlite"
    config.set_main_option("sqlalchemy.url", f"sqlite:///{db_path}")
    command.upgrade(config, "0014")
    import sqlite3
    with sqlite3.connect(db_path) as connection:
        connection.execute("INSERT INTO library_selection (jellyfin_id,name,selected,sort_order,updated_at) VALUES ('old-lib','旧库',1,0,CURRENT_TIMESTAMP)")
        connection.execute("INSERT INTO media_cache_index (media_id,jellyfin_id,library_id,name,media_type,fingerprint,synced_at) VALUES ('old-media','jf-old','old-lib','旧片','video','old-fp',CURRENT_TIMESTAMP)")
        connection.execute("INSERT INTO favorite (media_id,created_at) VALUES ('old-media',CURRENT_TIMESTAMP)")
    command.upgrade(config, "head")
    with sqlite3.connect(db_path) as connection:
        assert connection.execute("SELECT media_id,jellyfin_id,source_type FROM media_cache_index").fetchone() == ("old-media", "jf-old", "jellyfin")
        assert connection.execute("SELECT media_id FROM favorite").fetchone()[0] == "old-media"
    command.downgrade(config, "0014")
    with sqlite3.connect(db_path) as connection:
        assert connection.execute("SELECT media_id FROM favorite").fetchone()[0] == "old-media"
    command.upgrade(config, "head")
    with sqlite3.connect(db_path) as connection:
        connection.execute("INSERT INTO local_root VALUES ('native-root','样本','test-only',1,'pending',NULL)")
    with pytest.raises(RuntimeError, match="禁止有损降级"):
        command.downgrade(config, "0014")
