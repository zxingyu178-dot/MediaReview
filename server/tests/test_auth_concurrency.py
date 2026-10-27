"""认证心跳不得把只读 HTTP/流式响应变成长时间 SQLite 写事务。"""

import hashlib
import sqlite3
from pathlib import Path

import pytest
from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient

from app.api.v1.auth import require_auth
from app.core.config import AppConfig
from app.core.errors import UnauthorizedError
from app.db.models import PairedDevice
from app.db.session import Database


@pytest.fixture
def auth_probe(tmp_path: Path):
    database = Database(tmp_path / "auth.db")
    database.create_all()
    token = "mr_disposable_auth_concurrency_token"
    with database.session() as session:
        session.add(PairedDevice(device_id="phone", installation_id="phone",
                                 token_hash=hashlib.sha256(token.encode()).hexdigest()))
        session.commit()
    app = FastAPI()
    app.state.database = database
    app.state.settings = AppConfig()

    @app.get("/read")
    def read(_device=Depends(require_auth)):
        # A background writer must be able to commit while this request is active.
        with sqlite3.connect(tmp_path / "auth.db", timeout=0.05) as other:
            other.execute("UPDATE paired_device SET name='worker' WHERE device_id='phone'")
        return {"ok": True}

    @app.get("/pool")
    def pool(_device=Depends(require_auth)):
        return {"checked_out": database.engine.pool.checkedout()}

    @app.get("/identity")
    def identity(device=Depends(require_auth)):
        return {"device_id": device.device_id}

    with TestClient(app) as client:
        yield client, database, {"Authorization": f"Bearer {token}"}
    database.dispose()


def test_auth_releases_write_lock_before_endpoint(auth_probe):
    client, database, headers = auth_probe
    assert client.get("/read", headers=headers).json() == {"ok": True}
    with database.session() as session:
        assert session.get(PairedDevice, "phone").last_seen_at is not None


def test_auth_succeeds_while_background_writer_is_busy(auth_probe):
    client, database, headers = auth_probe
    with database.engine.connect() as writer:
        writer.exec_driver_sql("UPDATE paired_device SET name='busy' WHERE device_id='phone'")
        try:
            assert client.get("/identity", headers=headers).json() == {"device_id": "phone"}
        finally:
            writer.rollback()


def test_revoked_device_is_still_rejected(auth_probe):
    client, database, headers = auth_probe
    with database.session() as session:
        session.get(PairedDevice, "phone").revoked = True
        session.commit()
    with pytest.raises(UnauthorizedError):
        client.get("/identity", headers=headers)


def test_recent_auth_heartbeat_releases_read_connection(auth_probe):
    client, database, headers = auth_probe
    assert client.get("/identity", headers=headers).status_code == 200
    assert client.get("/pool", headers=headers).json() == {"checked_out": 0}
