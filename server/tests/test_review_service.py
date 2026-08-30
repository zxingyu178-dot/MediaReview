"""阶段 5: 批阅会话服务测试(创建/去重/进度/标记/自动完成)。"""

from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path

from app.db.session import Database
from app.services import review


def _make_db(data_root: Path) -> Database:
    db = Database(data_root / "database" / "mediareview.db")
    db.create_all()
    return db


def test_create_session_dedup_and_order(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    with db.session() as s:
        created = review.create_session(
            s,
            ["a", "b", "a", "c", ""],
            filter_snapshot={"media_type": "video"},
            sort_snapshot={"sort_by": "name"},
        )
        items = review.session_items(s, created.session_id)
        assert [it.media_id for it in items] == ["a", "b", "c"]
        assert created.total_count == 3
        assert created.status == "active"
    db.dispose()


def test_mark_seen_and_advance(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    with db.session() as s:
        created = review.create_session(s, ["a", "b", "c"])
        assert review.mark_seen(s, created.session_id, "b", True)
        assert not review.mark_seen(s, created.session_id, "nope", True)
        updated = review.advance(s, created.session_id)
        assert updated.current_index == 1
        assert updated.seen_count == 1
        # 前进不自动标记上一项为已看;seen_count 只统计显式标记
    db.dispose()


def test_advance_to_end_auto_completes(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    with db.session() as s:
        created = review.create_session(s, ["a", "b"])
        for _ in range(2):
            review.advance(s, created.session_id)
        done = review.get_session(s, created.session_id)
        assert done.status == "completed"
        assert done.completed_at is not None
    db.dispose()


def test_complete_and_latest_active(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    with db.session() as s:
        s1 = review.create_session(s, ["a"])
        s2 = review.create_session(s, ["b", "c"])
        assert review.latest_active_session(s).session_id == s2.session_id
        review.complete(s, s1.session_id)
        assert review.latest_active_session(s).session_id == s2.session_id
        view = review.session_view(review.get_session(s, s1.session_id))
        assert view["status"] == "completed"
    db.dispose()


FROZEN_NOW = datetime.now(UTC)


def test_session_ids_strictly_monotonic_under_frozen_clock(tmp_path: Path) -> None:
    """冻结时钟下连续生成的会话 ID 必须严格单调递增。

    latest_active_session 用 session_id.desc() 做平局打破;随机后缀会让
    同一时钟粒度内创建的两个会话以随机顺序胜出(全量门禁偶发红)。
    """
    original = review.utc_now
    review.utc_now = lambda: FROZEN_NOW
    try:
        ids = [review._new_session_id() for _ in range(64)]
    finally:
        review.utc_now = original
    for a, b in zip(ids, ids[1:], strict=False):
        assert a < b, f"会话 ID 必须严格单调递增: {a} !< {b}"
