"""批阅引擎服务: 会话 + 一次性队列 + 断点快照。

- create_session 把客户端已排序的 media_ids 固化为队列(index),并快照筛选/排序参数
- mark_* / advance 更新已看标记与当前游标
- 断点恢复: latest_active_session 返回最近活动会话,队列与会话数据本地持久化
"""

from __future__ import annotations

import json
import secrets

import sqlalchemy as sa
from sqlalchemy.orm import Session

from app.db.models import ReviewSession, ReviewSessionItem, utc_now


def _new_session_id() -> str:
    return utc_now().strftime("%Y%m%d%H%M%S") + secrets.token_hex(4)


def create_session(
    session: Session,
    media_ids: list[str],
    *,
    filter_snapshot: dict | None = None,
    sort_snapshot: dict | None = None,
) -> ReviewSession:
    """创建批阅会话。media_ids 顺序即批阅顺序(去重、忽略空项)。"""
    ordered: list[str] = []
    seen: set[str] = set()
    for media_id in media_ids:
        if not media_id:
            continue
        if media_id in seen:
            continue
        seen.add(media_id)
        ordered.append(media_id)

    review = ReviewSession(
        session_id=_new_session_id(),
        status="active",
        filter_snapshot=json.dumps(filter_snapshot or {}, ensure_ascii=False),
        sort_snapshot=json.dumps(sort_snapshot or {}, ensure_ascii=False),
        current_index=0,
        total_count=len(ordered),
        seen_count=0,
    )
    session.add(review)
    session.flush()
    for index, media_id in enumerate(ordered):
        session.add(
            ReviewSessionItem(
                session_id=review.session_id,
                media_id=media_id,
                index=index,
                seen=False,
            )
        )
    session.flush()
    return review


def get_session(session: Session, session_id: str) -> ReviewSession | None:
    return session.get(ReviewSession, session_id)


def latest_active_session(session: Session) -> ReviewSession | None:
    return session.scalars(
        sa.select(ReviewSession)
        .where(ReviewSession.status == "active")
        .order_by(ReviewSession.updated_at.desc())
        .limit(1)
    ).first()


def session_items(session: Session, session_id: str) -> list[ReviewSessionItem]:
    return list(
        session.scalars(
            sa.select(ReviewSessionItem)
            .where(ReviewSessionItem.session_id == session_id)
            .order_by(ReviewSessionItem.index.asc())
        ).all()
    )


def mark_seen(session: Session, session_id: str, media_id: str, seen: bool) -> bool:
    """标记队列中某项已看/未看,返回是否存在该项。"""
    row = session.scalars(
        sa.select(ReviewSessionItem).where(
            ReviewSessionItem.session_id == session_id,
            ReviewSessionItem.media_id == media_id,
        )
    ).first()
    if row is None:
        return False
    if row.seen != seen:
        row.seen = seen
        _recount(session, session_id)
        session.flush()
    return True


def advance(session: Session, session_id: str) -> ReviewSession | None:
    """把当前游标指向下一项(当前项视作已看)。"""
    review = get_session(session, session_id)
    if review is None or review.status != "active":
        return None
    if review.current_index < review.total_count:
        review.current_index += 1
        _recount(session, session_id)
        # 到达末尾自动完成
        if review.current_index >= review.total_count:
            review.status = "completed"
            review.completed_at = utc_now()
        session.flush()
    return review


def complete(session: Session, session_id: str) -> ReviewSession | None:
    review = get_session(session, session_id)
    if review is None:
        return None
    review.status = "completed"
    review.completed_at = utc_now()
    review.updated_at = utc_now()
    _recount(session, session_id)
    session.flush()
    return review


def complete_all_active(session: Session) -> int:
    """完成所有 active 会话(新建会话前调用,避免数据库长期累计多个 active)。"""
    rows = session.scalars(sa.select(ReviewSession).where(ReviewSession.status == "active")).all()
    now = utc_now()
    for row in rows:
        row.status = "completed"
        row.completed_at = now
        row.updated_at = now
    session.flush()
    return len(rows)


def set_position(session: Session, session_id: str, index: int) -> ReviewSession | None:
    """设置批阅位置(断点恢复):随批阅移动更新 current_index。"""
    review = get_session(session, session_id)
    if review is None or review.status != "active":
        return None
    clamped = max(0, min(int(index), review.total_count))
    review.current_index = clamped
    review.updated_at = utc_now()
    # 位置已到末尾视为完成
    if clamped >= review.total_count:
        review.status = "completed"
        review.completed_at = utc_now()
    session.flush()
    return review


def _recount(session: Session, session_id: str) -> None:
    seen_count = session.scalar(
        sa.select(sa.func.count())
        .select_from(ReviewSessionItem)
        .where(
            ReviewSessionItem.session_id == session_id,
            ReviewSessionItem.seen.is_(True),
        )
    )
    review = session.get(ReviewSession, session_id)
    if review is not None:
        review.seen_count = int(seen_count or 0)
        review.updated_at = utc_now()


def progress_view(review: ReviewSession) -> dict:
    return {
        "session_id": review.session_id,
        "status": review.status,
        "current_index": review.current_index,
        "total_count": review.total_count,
        "seen_count": review.seen_count,
        "created_at": review.created_at,
        "updated_at": review.updated_at,
        "completed_at": review.completed_at,
    }


def source_view(review: ReviewSession) -> dict:
    return {
        "filter": json.loads(review.filter_snapshot or "{}"),
        "sort": json.loads(review.sort_snapshot or "{}"),
    }


def session_view(review: ReviewSession) -> dict:
    return {
        **progress_view(review),
        "source": source_view(review),
    }


__all__ = [
    "advance",
    "complete",
    "complete_all_active",
    "create_session",
    "get_session",
    "latest_active_session",
    "mark_seen",
    "progress_view",
    "session_items",
    "session_view",
    "set_position",
    "source_view",
]
