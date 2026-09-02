"""Task D: 两阶段最终删除的 nonce 合同(服务 + API)。

合同:
- ``prepare_commit(session)`` 返回一次性 nonce + 过期时间 + 待删快照(count/bytes/media_ids);
- ``commit_with_nonce(session, nonce)`` 只删除 nonce 快照内的媒体(TOCTOU 防护),
  每项独立 success/missing/failed 并写审计;nonce 只能使用一次、有时效;
- 未知/已用/过期 nonce 分别抛 NonceMissingError / NonceReusedError / NonceExpiredError;
- API: POST /delete-queue/commit/prepare -> {nonce,...}; POST /delete-queue/commit {nonce}
  -> {outcome}; commit 缺 nonce 或 nonce 无效时 4xx。
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from conftest import client as _client_fixture  # noqa: F401  (仅确保夹具可导入)

from app.db.models import LibrarySelection, MediaCacheIndex
from app.db.session import Database
from app.services import delete_queue


def _make_db(data_root: Path) -> Database:
    db = Database(data_root / "database" / "mediareview.db")
    db.create_all()
    return db


def _seed_media(
    db: Database,
    media_id: str,
    media_path: str,
    size: int | None = None,
) -> None:
    with db.session() as s:
        s.merge(
            LibrarySelection(
                jellyfin_id="lib-movies", name="电影", collection_type="movies", selected=True
            )
        )
        s.commit()
    p = Path(media_path)
    if size is None:
        size = p.stat().st_size if p.is_file() else 0
    with db.session() as s:
        s.add(
            MediaCacheIndex(
                media_id=media_id,
                jellyfin_id="j-" + media_id,
                library_id="lib-movies",
                name=media_id + ".mp4",
                media_type="video",
                fingerprint="fp-" + media_id,
                size_bytes=size,
                media_path=media_path,
            )
        )
        s.commit()


def _enqueue(db: Database, media_id: str, size: int) -> None:
    with db.session() as s:
        assert delete_queue.enqueue(s, media_id, size_bytes=size) is True
        s.commit()


def test_prepare_returns_nonce_with_snapshot(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    f1 = tmp_path / "a.mp4"
    f2 = tmp_path / "b.mp4"
    f1.write_bytes(b"aaa")
    f2.write_bytes(b"bbbbbb")
    _seed_media(db, "aaa" * 8, str(f1), size=3)
    _seed_media(db, "bbb" * 8, str(f2), size=6)
    _enqueue(db, "aaa" * 8, 3)
    _enqueue(db, "bbb" * 8, 6)

    with db.session() as s:
        prep = delete_queue.prepare_commit(s)
        s.commit()

    assert prep.nonce and len(prep.nonce) >= 16
    assert prep.count == 2
    assert prep.total_bytes == 9
    assert set(prep.media_ids) == {"aaa" * 8, "bbb" * 8}
    assert prep.expires_at > datetime.now(UTC).replace(tzinfo=None)
    db.dispose()


def test_commit_with_nonce_deletes_snapshot_and_audits(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    f1 = tmp_path / "a.mp4"
    f2 = tmp_path / "b.mp4"
    f1.write_bytes(b"aaa")
    f2.write_bytes(b"bbbbbb")
    _seed_media(db, "aaa" * 8, str(f1), size=3)
    _seed_media(db, "bbb" * 8, str(f2), size=6)
    _enqueue(db, "aaa" * 8, 3)
    _enqueue(db, "bbb" * 8, 6)

    with db.session() as s:
        prep = delete_queue.prepare_commit(s)
        s.commit()
        nonce = prep.nonce
    with db.session() as s:
        outcome = delete_queue.commit_with_nonce(s, nonce)
        s.commit()

    assert outcome == {"aaa" * 8: "success", "bbb" * 8: "success"}
    assert not f1.exists() and not f2.exists()
    db.dispose()


def test_commit_rejects_reused_nonce(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    f1 = tmp_path / "a.mp4"
    f1.write_bytes(b"aaa")
    _seed_media(db, "aaa" * 8, str(f1), size=3)
    _enqueue(db, "aaa" * 8, 3)

    with db.session() as s:
        prep = delete_queue.prepare_commit(s)
        s.commit()
        nonce = prep.nonce
    with db.session() as s:
        delete_queue.commit_with_nonce(s, nonce)
        s.commit()
        with pytest.raises(delete_queue.NonceReusedError):
            delete_queue.commit_with_nonce(s, nonce)
    db.dispose()


def test_commit_rejects_expired_nonce(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    f1 = tmp_path / "a.mp4"
    f1.write_bytes(b"aaa")
    _seed_media(db, "aaa" * 8, str(f1), size=3)
    _enqueue(db, "aaa" * 8, 3)

    with db.session() as s:
        prep = delete_queue.prepare_commit(s)
        s.commit()
        nonce = prep.nonce
    # 把 nonce 过期时间改到过去
    with db.session() as s:
        row = s.get(delete_queue.DeleteCommitNonce, nonce)
        row.expires_at = datetime.now(UTC).replace(tzinfo=None) - timedelta(seconds=1)
        s.commit()
    with db.session() as s:
        with pytest.raises(delete_queue.NonceExpiredError):
            delete_queue.commit_with_nonce(s, nonce)
    db.dispose()


def test_commit_rejects_unknown_nonce(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    with db.session() as s:
        with pytest.raises(delete_queue.NonceMissingError):
            delete_queue.commit_with_nonce(s, "deadbeef" * 4)
    db.dispose()


def test_commit_only_deletes_nonce_snapshot_toctou(tmp_path: Path) -> None:
    """nonce 生成后新入队的媒体不得被"顺手"删除(TOCTOU 防护)。"""
    db = _make_db(tmp_path)
    f1 = tmp_path / "a.mp4"
    f2 = tmp_path / "c.mp4"
    f1.write_bytes(b"aaa")
    f2.write_bytes(b"cc")
    _seed_media(db, "aaa" * 8, str(f1), size=3)
    _seed_media(db, "ccc" * 8, str(f2), size=2)
    _enqueue(db, "aaa" * 8, 3)

    with db.session() as s:
        prep = delete_queue.prepare_commit(s)
        s.commit()
        nonce = prep.nonce
    # prepare 之后新入队一份
    _enqueue(db, "ccc" * 8, 2)

    with db.session() as s:
        outcome = delete_queue.commit_with_nonce(s, nonce)
        s.commit()

    assert outcome == {"aaa" * 8: "success"}
    assert not f1.exists()
    assert f2.exists(), "新入队媒体不得被 nonce 删除"
    with db.session() as s:
        assert len(delete_queue.list_queue(s)) == 1
    db.dispose()


def test_api_prepare_then_commit_with_nonce(tmp_path: Path, client) -> None:
    """API 层: prepare -> commit{nonce} 闭环;重复 commit 4xx;缺 nonce 422。"""
    f1 = tmp_path / "api-a.mp4"
    f1.write_bytes(b"media")
    db = client.app.state.database
    _seed_media(db, "aaa" * 8, str(f1), size=5)
    _enqueue(db, "aaa" * 8, 5)

    prep = client.post("/api/v1/delete-queue/commit/prepare")
    assert prep.status_code == 200, prep.text
    data = prep.json()["data"]
    nonce = data["nonce"]
    assert data["count"] == 1
    assert data["total_bytes"] == 5
    assert data["media_ids"] == ["aaa" * 8]

    r = client.post("/api/v1/delete-queue/commit", json={"nonce": nonce})
    assert r.status_code == 200, r.text
    assert r.json()["data"]["outcome"]["aaa" * 8] == "success"
    assert not f1.exists()

    # 复用 nonce -> 4xx
    r2 = client.post("/api/v1/delete-queue/commit", json={"nonce": nonce})
    assert r2.status_code == 409

    # 缺 nonce -> 422
    r3 = client.post("/api/v1/delete-queue/commit", json={})
    assert r3.status_code == 422


def test_migration_0014_adds_nonce_and_duplicate_tables(tmp_path: Path) -> None:
    """0014 新增 nonce 与重复分组表;升级/回滚对称且不丢既有设备/索引数据。"""
    from alembic import command
    from alembic.config import Config
    from sqlalchemy import create_engine, inspect, text

    database_path = tmp_path / "pre-0014.db"
    database_url = f"sqlite:///{database_path}"
    root = Path(__file__).resolve().parents[1]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "app" / "db" / "migrations"))
    config.set_main_option("sqlalchemy.url", database_url)
    command.upgrade(config, "0013")

    engine = create_engine(database_url)
    with engine.begin() as connection:
        connection.execute(
            text(
                "INSERT INTO media_cache_index (media_id, jellyfin_id, library_id, name, "
                "media_type, fingerprint) VALUES ('aaa', 'jf-a', 'lib-a', '旧媒体', "
                "'video', 'fp-a')"
            )
        )
    engine.dispose()

    command.upgrade(config, "0014")
    engine = create_engine(database_url)
    tables = set(inspect(engine).get_table_names())
    assert {"delete_commit_nonce", "duplicate_group", "duplicate_group_member"} <= tables
    with engine.begin() as connection:
        row = connection.execute(
            text("SELECT name FROM media_cache_index WHERE media_id = 'aaa'")
        ).scalar()
    assert row == "旧媒体"
    engine.dispose()

    command.downgrade(config, "0013")
    engine = create_engine(database_url)
    tables = set(inspect(engine).get_table_names())
    assert "duplicate_group" not in tables
    assert "duplicate_group_member" not in tables
    assert "delete_commit_nonce" not in tables
    with engine.begin() as connection:
        row = connection.execute(
            text("SELECT name FROM media_cache_index WHERE media_id = 'aaa'")
        ).scalar()
    assert row == "旧媒体"
    engine.dispose()
