"""Stage 8B.1 批阅正确性收口(Server 侧)。

覆盖评审指出的服务端权威性问题:

- 队列项必须携带 ``seen`` 状态(恢复会话后知道哪些已经批阅过);
- ``markSeen`` 必须返回服务端权威 ``seen_count`` / ``total_count``(客户端不得自行加一);
- 重复 ``markSeen`` 不得重复计数(幂等);
- ``complete`` 必须 fail-closed:还有未批阅时拒绝完成,会话保持 active;
- ``position`` 到达末尾也不得在仍有未批阅时关闭会话。
"""

from __future__ import annotations

from pathlib import Path

from app.db import models
from app.db.session import Database
from app.services import review


def _media(media_id: str, *, name: str, available: bool = True) -> models.MediaCacheIndex:
    return models.MediaCacheIndex(
        media_id=media_id,
        jellyfin_id=f"jf-{media_id}",
        library_id="lib-a",
        name=name,
        media_type="video",
        duration_ms=1_000,
        size_bytes=10_000,
        width=1920,
        height=1080,
        fingerprint=f"fp-{media_id}",
        is_available=available,
    )


def _select_library(db: Database, library_id: str = "lib-a") -> None:
    with db.session() as session:
        session.add(
            models.LibrarySelection(jellyfin_id=library_id, name=library_id, selected=True)
        )
        session.commit()


def _create_indexed_session(db: Database, media_ids: list[str]) -> str:
    """通过服务端建队流程创建会话(与生产同路径),返回 session_id。"""
    with db.session() as session:
        created = review.create_session(session, media_ids)
        session.commit()
        return created.session_id


def _queue(client, session_id: str, **params) -> list[dict]:
    response = client.get(f"/api/v1/review/sessions/{session_id}/queue", params=params)
    assert response.status_code == 200
    return response.json()["data"]["items"]


def _mark_seen(client, session_id: str, media_id: str, seen: bool = True):
    return client.post(
        f"/api/v1/review/sessions/{session_id}/seen",
        json={"media_id": media_id, "seen": seen},
    )


def _session_status(client, session_id: str) -> str:
    response = client.get(f"/api/v1/review/sessions/{session_id}")
    assert response.status_code == 200
    return response.json()["data"]["status"]


def test_queue_items_expose_seen_state(app, client) -> None:
    """队列项必须带 seen 字段,并在标记后更新(恢复会话依赖它)。"""
    db: Database = app.state.database
    _select_library(db)
    with db.session() as session:
        session.add_all([_media(f"m{i}", name=f"M{i}.mp4") for i in range(3)])
        session.commit()
    session_id = _create_indexed_session(db, ["m0", "m1", "m2"])

    assert [item["seen"] for item in _queue(client, session_id)] == [False, False, False]

    assert _mark_seen(client, session_id, "m1").status_code == 200

    updated = _queue(client, session_id)
    assert [(item["index"], item["seen"]) for item in updated] == [
        (0, False),
        (1, True),
        (2, False),
    ]


def test_mark_seen_returns_authoritative_progress(app, client) -> None:
    """seen 响应必须携带服务端权威 seen_count / total_count(客户端不得自行加一)。"""
    db: Database = app.state.database
    _select_library(db)
    with db.session() as session:
        session.add_all([_media(f"m{i}", name=f"M{i}.mp4") for i in range(3)])
        session.commit()
    session_id = _create_indexed_session(db, ["m0", "m1", "m2"])

    body = _mark_seen(client, session_id, "m2").json()["data"]
    assert body["media_id"] == "m2"
    assert body["seen"] is True
    assert body["seen_count"] == 1
    assert body["total_count"] == 3


def test_repeated_mark_seen_does_not_double_count(app, client) -> None:
    """重复标记同一媒体必须幂等:seen_count 不得被重复累加。"""
    db: Database = app.state.database
    _select_library(db)
    with db.session() as session:
        session.add_all([_media(f"m{i}", name=f"M{i}.mp4") for i in range(3)])
        session.commit()
    session_id = _create_indexed_session(db, ["m0", "m1", "m2"])

    first = _mark_seen(client, session_id, "m1").json()["data"]
    second = _mark_seen(client, session_id, "m1").json()["data"]
    assert first["seen_count"] == 1
    assert second["seen_count"] == 1, "已 seen 的媒体再次上报不得重复计数"

    # 取消已看后计数回退,且再确认不会被历史值污染
    unmarked = _mark_seen(client, session_id, "m1", seen=False).json()["data"]
    assert unmarked["seen"] is False
    assert unmarked["seen_count"] == 0


def test_complete_rejects_when_unseen_remain(app, client) -> None:
    """complete 必须 fail-closed:还有未批阅内容时拒绝关闭会话。"""
    db: Database = app.state.database
    _select_library(db)
    with db.session() as session:
        session.add_all([_media(f"m{i}", name=f"M{i}.mp4") for i in range(3)])
        session.commit()
    session_id = _create_indexed_session(db, ["m0", "m1", "m2"])
    assert _mark_seen(client, session_id, "m0").status_code == 200

    response = client.post(f"/api/v1/review/sessions/{session_id}/complete")

    assert response.status_code == 409, "还有未批阅时 Server 必须拒绝完成"
    assert response.json()["error"]["code"] == "CONFLICT"
    assert _session_status(client, session_id) == "active"


def test_complete_succeeds_when_all_seen(app, client) -> None:
    db: Database = app.state.database
    _select_library(db)
    with db.session() as session:
        session.add_all([_media(f"m{i}", name=f"M{i}.mp4") for i in range(3)])
        session.commit()
    session_id = _create_indexed_session(db, ["m0", "m1", "m2"])
    for media_id in ("m0", "m1", "m2"):
        assert _mark_seen(client, session_id, media_id).status_code == 200

    response = client.post(f"/api/v1/review/sessions/{session_id}/complete")

    assert response.status_code == 200
    body = response.json()["data"]
    assert body["status"] == "completed"
    assert body["seen_count"] == body["total_count"] == 3


def test_position_at_end_does_not_complete_with_unseen(app, client) -> None:
    """position 到达末尾时,只要还有未批阅内容,会话必须保持 active(§20/§19)。"""
    db: Database = app.state.database
    _select_library(db)
    with db.session() as session:
        session.add_all([_media(f"m{i}", name=f"M{i}.mp4") for i in range(3)])
        session.commit()
    session_id = _create_indexed_session(db, ["m0", "m1", "m2"])

    response = client.post(
        f"/api/v1/review/sessions/{session_id}/position",
        json={"index": 3},
    )

    assert response.status_code == 200
    assert response.json()["data"]["status"] == "active", "仍有未批阅不得自动完成"
    assert _session_status(client, session_id) == "active"

    # 全部批阅后再到末尾:允许完成
    for media_id in ("m0", "m1", "m2"):
        assert _mark_seen(client, session_id, media_id).status_code == 200
    done = client.post(f"/api/v1/review/sessions/{session_id}/position", json={"index": 3})
    assert done.json()["data"]["status"] == "completed"


def test_unavailable_tail_items_still_exposed_and_counted(app, client) -> None:
    """尾部不可用媒体不压缩绝对索引,也不改变 total_count(客户端据此判断到末尾)。"""
    db: Database = app.state.database
    _select_library(db)
    with db.session() as session:
        session.add_all(
            [
                _media("m0", name="M0.mp4"),
                _media("m1", name="M1.mp4", available=False),
                _media("m2", name="M2.mp4"),
            ]
        )
        session.commit()
    session_id = _create_indexed_session(db, ["m0", "m1", "m2"])

    items = _queue(client, session_id)
    assert [item["index"] for item in items] == [0, 2]
    response = client.get(f"/api/v1/review/sessions/{session_id}/queue")
    assert response.json()["data"]["total"] == 3


def _seed_available(db: Database, media_ids: list[str]) -> None:
    """Stage 8B.2：完成条件改为 remaining==0，服务层测试必须让条目真的"可用"。"""
    with db.session() as session:
        session.add_all(
            [
                models.MediaCacheIndex(
                    media_id=media_id,
                    jellyfin_id=f"jf-{media_id}",
                    library_id="lib-a",
                    name=f"{media_id}.mp4",
                    media_type="video",
                    fingerprint=f"fp-{media_id}",
                    is_available=True,
                )
                for media_id in media_ids
            ]
        )
        session.commit()


def test_service_complete_raises_unfinished_error(tmp_path: Path) -> None:
    """服务层合同:仍有**可批阅**内容时 complete 必须抛 UnfinishedReviewError。"""
    db = Database(tmp_path / "database" / "mediareview.db")
    db.create_all()
    try:
        _seed_available(db, ["a", "b"])
        with db.session() as session:
            created = review.create_session(session, ["a", "b"])
            session.commit()
            try:
                review.complete(session, created.session_id)
            except review.UnfinishedReviewError:
                pass
            else:  # pragma: no cover - 显式反证
                raise AssertionError("仍有未批阅时 complete 必须拒绝")
            assert review.get_session(session, created.session_id).status == "active"
            review.mark_seen(session, created.session_id, "a", True)
            review.mark_seen(session, created.session_id, "b", True)
            session.commit()
            done = review.complete(session, created.session_id)
            assert done is not None and done.status == "completed"
    finally:
        db.dispose()


def test_service_advance_to_end_requires_all_seen(tmp_path: Path) -> None:
    """advance 到末尾必须同样 fail-closed（仍有可批阅内容时不得关闭会话）。"""
    db = Database(tmp_path / "database" / "mediareview.db")
    db.create_all()
    try:
        _seed_available(db, ["a", "b"])
        with db.session() as session:
            created = review.create_session(session, ["a", "b"])
            session.commit()
            for _ in range(2):
                review.advance(session, created.session_id)
            session.commit()
            assert review.get_session(session, created.session_id).status == "active", (
                "仍有可批阅内容时 advance 到末尾不得关闭会话"
            )
            review.mark_seen(session, created.session_id, "a", True)
            review.mark_seen(session, created.session_id, "b", True)
            review.advance(session, created.session_id)
            session.commit()
            assert review.get_session(session, created.session_id).status == "completed"
    finally:
        db.dispose()