"""慢删除及丢失响应回归；仅使用隔离数据库与可丢弃文件。"""

from concurrent.futures import ThreadPoolExecutor
from threading import Event
import subprocess
import sys

import sqlalchemy as sa
from alembic import command
from alembic.config import Config
from pathlib import Path
import pytest

from test_delete_nonce_11 import _enqueue, _seed_media

from app.services import delete_queue
from app.db.models import DeleteCommitNonce
from app.services.delete_execution import exclusive_deletion


def test_execution_lock_excludes_other_process_and_releases(client):
    database = client.app.state.database
    script = """
import sys
from app.db.session import Database
from app.services.delete_execution import exclusive_deletion
from pathlib import Path
db = Database(Path(sys.argv[1]))
with db.session() as session, exclusive_deletion(session) as acquired:
    print('acquired' if acquired else 'busy')
db.dispose()
"""
    def child():
        return subprocess.run(
            [sys.executable, "-c", script, database.engine.url.database],
            capture_output=True, text=True, timeout=15, check=True,
        ).stdout.strip()

    with database.session() as session, exclusive_deletion(session) as acquired:
        assert acquired
        assert child() == "busy"
    assert child() == "acquired"


def test_receipt_migration_preserves_legacy_and_refuses_running_downgrade(tmp_path):
    config = Config()
    config.set_main_option("script_location", str(Path(__file__).parents[1] / "app/db/migrations"))
    database = tmp_path / "migration.db"
    config.set_main_option("sqlalchemy.url", f"sqlite:///{database}")
    command.upgrade(config, "0017")
    engine = sa.create_engine(f"sqlite:///{database}")
    with engine.begin() as connection:
        connection.exec_driver_sql(
            "INSERT INTO delete_commit_nonce "
            "(nonce,expires_at,used,media_ids_json,total_bytes,created_at) "
            "VALUES ('legacy','2026-10-28',1,'[]',123,'2026-10-28')"
        )
    command.upgrade(config, "0018")
    with engine.begin() as connection:
        row = connection.exec_driver_sql(
            "SELECT total_bytes,owner_device_id,receipt_state,result_json FROM delete_commit_nonce"
        ).one()
        assert tuple(row) == (123, None, None, None)
        connection.exec_driver_sql("UPDATE delete_commit_nonce SET receipt_state='running'")
    with pytest.raises(RuntimeError, match="禁止降级"):
        command.downgrade(config, "0017")
    with engine.begin() as connection:
        connection.exec_driver_sql("UPDATE delete_commit_nonce SET receipt_state='unknown'")
    command.downgrade(config, "0017")
    with engine.connect() as connection:
        assert connection.exec_driver_sql("SELECT total_bytes FROM delete_commit_nonce").scalar_one() == 123
    engine.dispose()


def test_receipt_does_not_disclose_other_device_batch(client):
    nonce = client.post("/api/v1/delete-queue/commit/prepare").json()["data"]["nonce"]
    with client.app.state.database.session() as session:
        row = session.get(DeleteCommitNonce, nonce)
        row.owner_device_id = "different-device"
        session.commit()
    assert client.get(f"/api/v1/delete-queue/commit/{nonce}/result").status_code == 404
    assert client.post("/api/v1/delete-queue/commit", json={"nonce": nonce}).status_code == 404


def test_interrupted_batch_stays_unknown_without_fabricated_receipt(client, tmp_path):
    database = client.app.state.database
    path = tmp_path / "interrupted.mp4"
    path.write_bytes(b"untouched")
    _seed_media(database, "interrupted", str(path))
    _enqueue(database, "interrupted", path.stat().st_size)
    nonce = client.post("/api/v1/delete-queue/commit/prepare").json()["data"]["nonce"]
    with database.session() as session:
        delete_queue._claim_nonce(session, nonce)
    with database.session() as session:
        delete_queue.recover_interrupted_deletes(session)
    receipt = client.get(f"/api/v1/delete-queue/commit/{nonce}/result").json()["data"]
    assert receipt == {"state": "unknown", "result": None, "confirmed_partial_result": None}
    assert path.read_bytes() == b"untouched"
    assert client.post("/api/v1/delete-queue/commit", json={"nonce": nonce}).status_code == 409


def test_slow_delete_does_not_block_health_or_recover_active_item(client, tmp_path, monkeypatch):
    database = client.app.state.database
    path = tmp_path / "slow.mp4"
    path.write_bytes(b"disposable")
    _seed_media(database, "slow-delete", str(path))
    _enqueue(database, "slow-delete", path.stat().st_size)
    nonce = client.post("/api/v1/delete-queue/commit/prepare").json()["data"]["nonce"]
    entered, release = Event(), Event()
    original = delete_queue._delete_one

    def slow_delete(*args, **kwargs):
        entered.set()
        assert release.wait(8), "test release was not signalled"
        return original(*args, **kwargs)

    monkeypatch.setattr(delete_queue, "_delete_one", slow_delete)
    with ThreadPoolExecutor(max_workers=3) as pool:
        deleting = pool.submit(client.post, "/api/v1/delete-queue/commit", json={"nonce": nonce})
        try:
            assert entered.wait(3)
            health = pool.submit(client.get, "/api/v1/system/health")
            assert health.result(timeout=1.5).status_code == 200
            queue = pool.submit(client.get, "/api/v1/delete-queue")
            rows = queue.result(timeout=1.5).json()["data"]
            assert next(row for row in rows if row["media_id"] == "slow-delete")["status"] == "committing"
        finally:
            release.set()
        result = deleting.result(timeout=5)
    assert result.status_code == 200
    assert result.json()["data"]["deleted_count"] == 1
    assert not path.exists()


def test_completed_result_can_be_read_without_executing_delete_again(client, tmp_path):
    database = client.app.state.database
    path = tmp_path / "receipt.mp4"
    path.write_bytes(b"receipt")
    _seed_media(database, "receipt-delete", str(path))
    _enqueue(database, "receipt-delete", path.stat().st_size)
    nonce = client.post("/api/v1/delete-queue/commit/prepare").json()["data"]["nonce"]
    original = client.post("/api/v1/delete-queue/commit", json={"nonce": nonce})
    assert original.status_code == 200
    receipt = client.get(f"/api/v1/delete-queue/commit/{nonce}/result")
    assert receipt.status_code == 200
    assert receipt.json()["data"]["state"] == "completed"
    assert receipt.json()["data"]["result"] == original.json()["data"]
    assert client.post("/api/v1/delete-queue/commit", json={"nonce": nonce}).status_code == 409


def test_unexpected_error_after_remove_never_becomes_confirmed_failure(client, tmp_path, monkeypatch):
    database = client.app.state.database
    path = tmp_path / "unknown-after-remove.mp4"
    path.write_bytes(b"disposable")
    _seed_media(database, "unknown-after-remove", str(path))
    _enqueue(database, "unknown-after-remove", path.stat().st_size)
    nonce = client.post("/api/v1/delete-queue/commit/prepare").json()["data"]["nonce"]

    def interrupted(*args, **kwargs):
        path.unlink()
        raise RuntimeError("unexpected failure after filesystem change")

    monkeypatch.setattr(delete_queue, "_delete_one", interrupted)
    response = client.post("/api/v1/delete-queue/commit", json={"nonce": nonce})
    assert response.status_code == 409
    receipt = client.get(f"/api/v1/delete-queue/commit/{nonce}/result").json()["data"]
    assert receipt["state"] == "unknown"
    assert receipt["result"] is None
    assert "unknown-after-remove" not in (receipt["confirmed_partial_result"] or {}).get("outcome", {})
