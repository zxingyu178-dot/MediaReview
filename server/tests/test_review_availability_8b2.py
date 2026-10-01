"""Stage 8B.2 媒体可用性 / 稀疏队列合同（Server 侧）。

覆盖评审 §6~§9 / §16~§20 / §28~§30：

- 统一进度计数：total / seen / unavailable / remaining / completed（**不重复计数**）；
- 完成条件改为 ``remaining_count == 0``（媒体失效不再永久阻塞 Session）；
- ``complete`` 继续 fail-closed（remaining > 0 → 409，会话保持 active）；
- ``GET /review/sessions/{id}/nearest``：SQL 直查最近可用项（forward / backward / nearest）；
- 大稀疏 Session 的 nearest 只发 1 条 SQL（禁止把 SessionItem 拉进 Python 再循环）。
"""

from __future__ import annotations

import time
from pathlib import Path

import pytest
import sqlalchemy as sa

from app.db import models
from app.db.session import Database
from app.services import review


def _media(media_id: str, *, available: bool = True) -> models.MediaCacheIndex:
    return models.MediaCacheIndex(
        media_id=media_id,
        jellyfin_id=f"jf-{media_id}",
        library_id="lib-a",
        name=f"{media_id}.mp4",
        media_type="video",
        duration_ms=1_000,
        size_bytes=10_000,
        width=1920,
        height=1080,
        fingerprint=f"fp-{media_id}",
        is_available=available,
    )


def _seed_media(db: Database, entries: list[tuple[str, bool]]) -> None:
    with db.session() as session:
        session.add_all([_media(media_id, available=available) for media_id, available in entries])
        session.commit()


def _create_session(db: Database, media_ids: list[str]) -> str:
    with db.session() as session:
        created = review.create_session(session, media_ids)
        session.commit()
        return created.session_id


def _mark_seen(db: Database, session_id: str, media_ids: list[str]) -> None:
    with db.session() as session:
        for media_id in media_ids:
            assert review.mark_seen(session, session_id, media_id, True) is not None
        session.commit()


def _progress(client, session_id: str) -> dict:
    response = client.get(f"/api/v1/review/sessions/{session_id}")
    assert response.status_code == 200
    return response.json()["data"]


# ---------------------------------------------------------------- §28 进度计数合同


def test_case1_unavailable_items_no_longer_block_completion(app, client) -> None:
    """Case 1：100 total / 97 seen / 3 unavailable → remaining=0 → 允许完成。"""
    db: Database = app.state.database
    ids = [f"m{i:03d}" for i in range(100)]
    _seed_media(db, [(media_id, True) for media_id in ids[:97]])
    _seed_media(db, [(media_id, False) for media_id in ids[97:]])
    session_id = _create_session(db, ids)
    _mark_seen(db, session_id, ids[:97])

    progress = _progress(client, session_id)
    assert progress["total_count"] == 100
    assert progress["seen_count"] == 97
    assert progress["unavailable_count"] == 3
    assert progress["remaining_count"] == 0
    assert progress["completed_count"] == 100

    done = client.post(f"/api/v1/review/sessions/{session_id}/complete")
    assert done.status_code == 200, "媒体失效不得永久阻塞完成"
    assert done.json()["data"]["status"] == "completed"


def test_case2_remaining_positive_still_rejects_completion(app, client) -> None:
    """Case 2：95 seen / 3 unavailable / 2 available unseen → remaining=2 → 409。"""
    db: Database = app.state.database
    ids = [f"m{i:03d}" for i in range(100)]
    _seed_media(db, [(media_id, True) for media_id in ids[:97]])
    _seed_media(db, [(media_id, False) for media_id in ids[97:]])
    session_id = _create_session(db, ids)
    _mark_seen(db, session_id, ids[:95])

    progress = _progress(client, session_id)
    assert progress["seen_count"] == 95
    assert progress["unavailable_count"] == 3
    assert progress["remaining_count"] == 2

    rejected = client.post(f"/api/v1/review/sessions/{session_id}/complete")
    assert rejected.status_code == 409, "还有可批阅内容时必须拒绝完成"
    assert rejected.json()["error"]["code"] == "CONFLICT"
    assert _progress(client, session_id)["status"] == "active"


def test_case3_seen_then_unavailable_is_not_double_counted(app, client) -> None:
    """Case 3：seen 后又变 unavailable 不能重复计入 completed（completed ≤ total）。"""
    db: Database = app.state.database
    # A: seen + unavailable；B: seen + available；C: unseen + unavailable
    _seed_media(db, [("a", False), ("b", True), ("c", False)])
    session_id = _create_session(db, ["a", "b", "c"])
    _mark_seen(db, session_id, ["a", "b"])

    progress = _progress(client, session_id)
    assert progress["total_count"] == 3
    assert progress["seen_count"] == 2
    assert progress["unavailable_count"] == 2
    assert progress["remaining_count"] == 0
    assert progress["completed_count"] == 3
    assert progress["completed_count"] <= progress["total_count"], "绝不出现 completed > total"
    assert progress["completed_count"] != (
        progress["seen_count"] + progress["unavailable_count"]
    ), "seen 与 unavailable 重叠时不得简单相加"

    assert client.post(f"/api/v1/review/sessions/{session_id}/complete").status_code == 200


def test_case4_progress_reacts_to_dynamic_availability(app, client) -> None:
    """Case 4：Session 创建后媒体逐步变 unavailable，进度必须动态更新。"""
    db: Database = app.state.database
    _seed_media(db, [("a", True), ("b", True), ("c", True)])
    session_id = _create_session(db, ["a", "b", "c"])
    _mark_seen(db, session_id, ["a"])

    first = _progress(client, session_id)
    assert (first["unavailable_count"], first["remaining_count"]) == (0, 2)

    # b 变成不可用：remaining 掉到 1，且仍未完成
    with db.session() as session:
        row = session.get(models.MediaCacheIndex, "b")
        assert row is not None
        row.is_available = False
        session.commit()
    second = _progress(client, session_id)
    assert (second["unavailable_count"], second["remaining_count"]) == (1, 1)
    assert client.post(f"/api/v1/review/sessions/{session_id}/complete").status_code == 409

    # c 也变成不可用：remaining=0 → 允许完成
    with db.session() as session:
        row = session.get(models.MediaCacheIndex, "c")
        assert row is not None
        row.is_available = False
        session.commit()
    third = _progress(client, session_id)
    assert (third["unavailable_count"], third["remaining_count"]) == (2, 0)
    assert client.post(f"/api/v1/review/sessions/{session_id}/complete").status_code == 200


def test_advance_and_position_use_remaining_semantics(app, client) -> None:
    """§10：advance / position 的自动完成同样只认 remaining_count == 0。"""
    db: Database = app.state.database
    _seed_media(db, [("a", True), ("b", True), ("c", False)])
    session_id = _create_session(db, ["a", "b", "c"])
    _mark_seen(db, session_id, ["a"])

    # b 仍可批阅 → position 到末尾不得完成
    resp = client.post(f"/api/v1/review/sessions/{session_id}/position", json={"index": 3})
    assert resp.json()["data"]["status"] == "active"
    # advance 到末尾同样不得完成
    client.post(f"/api/v1/review/sessions/{session_id}/advance")
    assert _progress(client, session_id)["status"] == "active"

    # b 批阅后 remaining=0 → position 到末尾允许完成
    _mark_seen(db, session_id, ["b"])
    resp = client.post(f"/api/v1/review/sessions/{session_id}/position", json={"index": 3})
    assert resp.json()["data"]["status"] == "completed"


# ---------------------------------------------------------------- §29 nearest 合同


def _seed_sparse_session(db: Database, available_indexes: set[int], total: int = 10) -> str:
    ids = [f"m{i}" for i in range(total)]
    _seed_media(
        db,
        [(media_id, index in available_indexes) for index, media_id in enumerate(ids)],
    )
    return _create_session(db, ids)


def _nearest(client, session_id: str, index: int, direction: str = "nearest"):
    return client.get(
        f"/api/v1/review/sessions/{session_id}/nearest",
        params={"index": index, "direction": direction},
    )


def test_nearest_returns_self_when_anchor_available(app, client) -> None:
    db: Database = app.state.database
    session_id = _seed_sparse_session(db, {2, 5, 9})

    assert _nearest(client, session_id, 5).json()["data"]["index"] == 5


def test_nearest_prefers_forward_when_anchor_unavailable(app, client) -> None:
    db: Database = app.state.database
    session_id = _seed_sparse_session(db, {2, 5, 9})

    assert _nearest(client, session_id, 3).json()["data"]["index"] == 5
    assert _nearest(client, session_id, 0, "forward").json()["data"]["index"] == 2


def test_nearest_falls_back_backward_when_nothing_later(app, client) -> None:
    db: Database = app.state.database
    session_id = _seed_sparse_session(db, {2, 5, 9})

    # 锚点 10：后面（>=10）没有可用项 → nearest 回退到 9
    assert _nearest(client, session_id, 10).json()["data"]["index"] == 9
    assert _nearest(client, session_id, 9, "backward").json()["data"]["index"] == 5
    # backward 是严格小于：anchor 自身可用也不返回自己
    assert _nearest(client, session_id, 5, "backward").json()["data"]["index"] == 2


def test_nearest_returns_null_when_everything_unavailable(app, client) -> None:
    db: Database = app.state.database
    session_id = _seed_sparse_session(db, set())

    body = _nearest(client, session_id, 4).json()["data"]
    assert body["index"] is None


def test_nearest_rejects_invalid_direction_and_index(app, client) -> None:
    db: Database = app.state.database
    session_id = _seed_sparse_session(db, {1})

    assert _nearest(client, session_id, 1, "sideways").status_code == 422
    assert _nearest(client, session_id, -1).status_code == 422


def test_nearest_404_for_unknown_session(client) -> None:
    assert _nearest(client, "no-such-session", 0).status_code == 404


# ---------------------------------------------------------------- §30 大稀疏 Session


def test_nearest_on_large_sparse_session_uses_single_sql_query(tmp_path: Path) -> None:
    """10 万条 SessionItem、绝大多数 unavailable：nearest 必须单条 SQL 直接命中。"""
    db = Database(tmp_path / "database" / "review-sparse.db")
    db.create_all()
    try:
        total = 100_000
        ids = [f"{i:024x}" for i in range(total)]
        available_indexes = {63_750, 63_751, 99_999}
        with db.engine.begin() as connection:
            connection.execute(
                sa.insert(models.MediaCacheIndex),
                [
                    {
                        "media_id": media_id,
                        "jellyfin_id": f"jf-{index}",
                        "library_id": "lib-a",
                        "name": f"M{index}.mp4",
                        "media_type": "video",
                        "fingerprint": f"fp-{index}",
                        "is_available": index in available_indexes,
                    }
                    for index, media_id in enumerate(ids)
                ],
            )
        with db.session() as session:
            created = review.create_session(session, ids)
            session.commit()
            session_id = created.session_id

        statements: list[str] = []

        @sa.event.listens_for(db.engine, "before_cursor_execute")
        def capture(_conn, _cursor, statement, _parameters, _context, _executemany):
            if "review_session_item" in statement.lower():
                statements.append(" ".join(statement.lower().split()))

        with db.session() as session:
            started = time.perf_counter()
            found = review.nearest_available_item(
                session, session_id, index=63_700, direction="nearest"
            )
            elapsed = time.perf_counter() - started

        assert found == 63_750
        assert len(statements) == 1, f"nearest 必须是单条 SQL（实际 {len(statements)} 条）"
        assert "limit" in statements[0], "nearest 必须由 SQL LIMIT 命中而不是拉全量"
        assert elapsed < 2.0, f"100k 稀疏 nearest 耗时 {elapsed:.3f}s，超过测试机 2s 门槛"
    finally:
        db.dispose()


@pytest.mark.parametrize(
    ("direction", "expected"),
    [("forward", 9), ("backward", 5), ("nearest", 9)],
)
def test_nearest_service_direction_semantics(tmp_path: Path, direction: str, expected: int) -> None:
    db = Database(tmp_path / f"database/nearest-{direction}.db")
    db.create_all()
    try:
        ids = [f"m{i}" for i in range(10)]
        with db.engine.begin() as connection:
            connection.execute(
                sa.insert(models.MediaCacheIndex),
                [
                    {
                        "media_id": media_id,
                        "jellyfin_id": f"jf-{index}",
                        "library_id": "lib-a",
                        "name": f"M{index}.mp4",
                        "media_type": "video",
                        "fingerprint": f"fp-{index}",
                        "is_available": index in {2, 5, 9},
                    }
                    for index, media_id in enumerate(ids)
                ],
            )
        with db.session() as session:
            created = review.create_session(session, ids)
            session.commit()
            session_id = created.session_id
            assert (
                review.nearest_available_item(session, session_id, index=6, direction=direction)
                == expected
            )
    finally:
        db.dispose()