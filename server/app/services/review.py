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

from app.db.models import MediaCacheIndex, ReviewSession, ReviewSessionItem, utc_now
from app.services import media_index
from app.services.hash_contract import full_sha256_sql_predicate


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


def _queue_order(
    sort_by: str,
    sort_order: str,
    random_seed: str | None,
) -> tuple[sa.ColumnElement, list[sa.ColumnElement]]:
    """返回与媒体墙一致的 SQLite 排序值和 NULL-last 稳定排序表达式。"""
    sort_value = media_index._sort_expression(sort_by, random_seed)
    descending = sort_order == "desc"
    value_direction = sort_value.desc() if descending else sort_value.asc()
    tie_direction = (
        MediaCacheIndex.media_id.desc() if descending else MediaCacheIndex.media_id.asc()
    )
    if sort_by == "resolution":
        missing_rank = sa.case(
            (sort_value > 0, 0),
            (sort_value.is_(None), 1),
            else_=2,
        )
    else:
        missing_rank = sa.case((sort_value.is_(None), 1), else_=0)
    return sort_value, [missing_rank.asc(), value_direction, tie_direction]


def _representative_queue_select(
    *,
    library_ids: list[str],
    media_type: str | None,
    search: str | None,
    sort_by: str,
    sort_order: str,
    random_seed: str | None,
):
    """构造仅含完全重复代表项的有序 SQL，不物化媒体索引。"""
    conditions = [
        MediaCacheIndex.is_available.is_(True),
        MediaCacheIndex.library_id.in_(library_ids),
    ]
    if media_type:
        conditions.append(MediaCacheIndex.media_type == media_type)
    if search and search.strip():
        pattern = f"%{media_index._escaped_like(search.strip().casefold())}%"
        conditions.append(sa.func.lower(MediaCacheIndex.name).like(pattern, escape="\\"))

    sort_value, source_order = _queue_order(sort_by, sort_order, random_seed)
    valid_exact = sa.and_(
        MediaCacheIndex.size_bytes.is_not(None),
        MediaCacheIndex.duration_ms.is_not(None),
        full_sha256_sql_predicate(MediaCacheIndex.sha256),
    )
    duplicate_key = sa.case(
        (
            valid_exact,
            sa.literal("hash:").op("||")(MediaCacheIndex.sha256),
        ),
        else_=sa.literal("media:").op("||")(MediaCacheIndex.media_id),
    )
    source = (
        sa.select(
            MediaCacheIndex.media_id.label("media_id"),
            sort_value.label("sort_value"),
            sa.case(
                (sort_value > 0, 0),
                (sort_value.is_(None), 1),
                else_=2,
            ).label("missing_rank")
            if sort_by == "resolution"
            else sa.case((sort_value.is_(None), 1), else_=0).label("missing_rank"),
            sa.func.row_number()
            .over(
                partition_by=[
                    MediaCacheIndex.size_bytes,
                    MediaCacheIndex.duration_ms,
                    duplicate_key,
                ],
                order_by=source_order,
            )
            .label("duplicate_rank"),
        )
        .where(*conditions)
        .cte("review_source")
    )
    descending = sort_order == "desc"
    result_order = [
        source.c.missing_rank.asc(),
        source.c.sort_value.desc() if descending else source.c.sort_value.asc(),
        source.c.media_id.desc() if descending else source.c.media_id.asc(),
    ]
    representatives = (
        sa.select(
            source.c.media_id,
            (sa.func.row_number().over(order_by=result_order) - 1).label("queue_index"),
        )
        .where(source.c.duplicate_rank == 1)
        .cte("review_representatives")
    )
    return sa.select(representatives.c.media_id, representatives.c.queue_index)


def create_session_from_index(
    session: Session,
    *,
    library_ids: list[str],
    filter_snapshot: dict | None = None,
    sort_snapshot: dict | None = None,
) -> ReviewSession:
    """从 SQLite 媒体索引事务性创建批阅会话及队列。"""
    targets = sorted({library_id for library_id in library_ids if library_id})
    if not targets:
        raise ValueError("尚未勾选任何媒体库,请先完成媒体库配置")
    saved_filter = dict(filter_snapshot or {})
    saved_sort = dict(sort_snapshot or {})
    media_type = saved_filter.get("media_type")
    search = saved_filter.get("search")
    sort_by = saved_sort.get("sort_by", "name")
    sort_order = saved_sort.get("sort_order", "asc")
    random_seed = saved_sort.get("random_seed")
    queue_select = _representative_queue_select(
        library_ids=targets,
        media_type=media_type,
        search=search,
        sort_by=sort_by,
        sort_order=sort_order,
        random_seed=random_seed,
    ).subquery()
    total = int(session.scalar(sa.select(sa.func.count()).select_from(queue_select)) or 0)
    if total == 0:
        raise ValueError("没有可批阅的媒体")

    complete_all_active(session)
    created = ReviewSession(
        session_id=_new_session_id(),
        status="active",
        filter_snapshot=json.dumps(saved_filter, ensure_ascii=False),
        sort_snapshot=json.dumps(saved_sort, ensure_ascii=False),
        current_index=0,
        total_count=total,
        seen_count=0,
    )
    session.add(created)
    session.flush()
    insert = sa.insert(ReviewSessionItem).from_select(
        ["session_id", "media_id", "index", "seen"],
        sa.select(
            sa.literal(created.session_id),
            queue_select.c.media_id,
            queue_select.c.queue_index,
            sa.false(),
        ).order_by(queue_select.c.queue_index),
    )
    session.execute(insert)
    session.flush()
    return created


def get_session(session: Session, session_id: str) -> ReviewSession | None:
    return session.get(ReviewSession, session_id)


def latest_active_session(session: Session) -> ReviewSession | None:
    return session.scalars(
        sa.select(ReviewSession)
        .where(ReviewSession.status == "active")
        .order_by(
            ReviewSession.updated_at.desc(),
            ReviewSession.created_at.desc(),
            ReviewSession.session_id.desc(),
        )
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


def session_queue_page(
    session: Session,
    session_id: str,
    *,
    page: int,
    page_size: int,
) -> tuple[list[tuple[int, MediaCacheIndex]], int]:
    """用 SQL 绝对队列分页并批量联结可用媒体；缺项不压缩绝对索引。"""
    total = int(
        session.scalar(
            sa.select(sa.func.count())
            .select_from(ReviewSessionItem)
            .where(ReviewSessionItem.session_id == session_id)
        )
        or 0
    )
    offset = (page - 1) * page_size
    queue_page = (
        sa.select(
            ReviewSessionItem.index.label("queue_index"),
            ReviewSessionItem.media_id.label("media_id"),
        )
        .where(ReviewSessionItem.session_id == session_id)
        .order_by(ReviewSessionItem.index.asc())
        .offset(offset)
        .limit(page_size)
        .subquery()
    )
    rows = session.execute(
        sa.select(queue_page.c.queue_index, MediaCacheIndex)
        .join(MediaCacheIndex, MediaCacheIndex.media_id == queue_page.c.media_id)
        .where(MediaCacheIndex.is_available.is_(True))
        .order_by(queue_page.c.queue_index.asc())
    ).all()
    return [(int(index), media) for index, media in rows], total


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
    "create_session_from_index",
    "get_session",
    "latest_active_session",
    "mark_seen",
    "progress_view",
    "session_items",
    "session_queue_page",
    "session_view",
    "set_position",
    "source_view",
]
