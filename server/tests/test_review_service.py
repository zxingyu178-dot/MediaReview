"""阶段 5: 批阅会话服务测试(创建/去重/进度/标记/自动完成)。"""

from __future__ import annotations

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
