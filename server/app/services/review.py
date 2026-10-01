"""批阅引擎服务: 会话 + 一次性队列 + 断点快照。

- create_session 把客户端已排序的 media_ids 固化为队列(index),并快照筛选/排序参数
- mark_* / advance 更新已看标记与当前游标
- 断点恢复: latest_active_session 返回最近活动会话,队列与会话数据本地持久化
"""

from __future__ import annotations

import json
import secrets
import threading

import sqlalchemy as sa
from sqlalchemy.orm import Session

from app.db.models import MediaCacheIndex, ReviewSession, ReviewSessionItem, utc_now
from app.services import media_index
from app.services.hash_contract import full_sha256_sql_predicate

_session_id_lock = threading.Lock()
_last_session_stamp: str | None = None


class UnfinishedReviewError(ValueError):
    """会话仍有未批阅内容,拒绝完成(fail-closed)。"""


def _new_session_id() -> str:
    """生成严格单调递增的会话 ID(秒+微秒时间戳 + 随机防碰撞后缀)。

    latest_active_session 用 session_id 字典序做平局打破,因此 ID 必须随创建
    顺序单调;Windows 时钟粒度可能让相邻两次 utc_now() 返回同一值,用进程内
    守卫强制递增。随机后缀只防跨进程碰撞,不参与同进程内的排序。
    """
    global _last_session_stamp
    with _session_id_lock:
        stamp = utc_now().strftime("%Y%m%d%H%M%S%f")
        if _last_session_stamp is not None and stamp <= _last_session_stamp:
            stamp = str(int(_last_session_stamp) + 1)
        _last_session_stamp = stamp
    return stamp + secrets.token_hex(4)


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
) -> tuple[list[tuple[int, bool, MediaCacheIndex]], int]:
    """用 SQL 绝对队列分页并批量联结可用媒体；缺项不压缩绝对索引。

    每项返回 ``(index, seen, media)``：seen 是会话状态(客户端恢复后据此
    判断当前条目以前是否已经批阅过),与媒体可用性无关。
    """
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
            ReviewSessionItem.seen.label("seen"),
            ReviewSessionItem.media_id.label("media_id"),
        )
        .where(ReviewSessionItem.session_id == session_id)
        .order_by(ReviewSessionItem.index.asc())
        .offset(offset)
        .limit(page_size)
        .subquery()
    )
    rows = session.execute(
        sa.select(queue_page.c.queue_index, queue_page.c.seen, MediaCacheIndex)
        .join(MediaCacheIndex, MediaCacheIndex.media_id == queue_page.c.media_id)
        .where(MediaCacheIndex.is_available.is_(True))
        .order_by(queue_page.c.queue_index.asc())
    ).all()
    return [(int(index), bool(seen), media) for index, seen, media in rows], total


def mark_seen(session: Session, session_id: str, media_id: str, seen: bool) -> dict | None:
    """标记队列中某项已看/未看,返回服务端权威进度;媒体不在会话中时返回 None。

    幂等:已经是目标状态时不重复计数。返回 ``{media_id, seen, **review_progress_counts}``
    —— Android 端不得自行 +1,必须直接用这里的权威值（Stage 8B.2 §6/§7）。
    """
    row = session.scalars(
        sa.select(ReviewSessionItem).where(
            ReviewSessionItem.session_id == session_id,
            ReviewSessionItem.media_id == media_id,
        )
    ).first()
    if row is None:
        return None
    if row.seen != seen:
        row.seen = seen
        _recount(session, session_id)
        session.flush()
    review = get_session(session, session_id)
    if review is None:  # pragma: no cover - 队列项存在必然有会话
        return None
    return {"media_id": media_id, "seen": seen, **review_progress_counts(session, session_id)}


def advance(session: Session, session_id: str) -> ReviewSession | None:
    """把当前游标指向下一项(当前项视作已看)。"""
    review = get_session(session, session_id)
    if review is None or review.status != "active":
        return None
    if review.current_index < review.total_count:
        review.current_index += 1
    _recount(session, session_id)
    # 到达末尾自动完成 —— fail-closed(Stage 8B.2 §10:只认 remaining_count == 0)
    if review.current_index >= review.total_count and _remaining_count(session, session_id) == 0:
        review.status = "completed"
        review.completed_at = utc_now()
    session.flush()
    return review


def complete(session: Session, session_id: str) -> ReviewSession | None:
    """完成会话(fail-closed)。

    完成条件(Stage 8B.2 §8/§9):``remaining_count == 0``
    —— 仍有可批阅内容时抛 [UnfinishedReviewError],绝不关闭会话;
    媒体失效(unavailable)不再永久阻塞完成。
    已完成的会话幂等返回。
    """
    review = get_session(session, session_id)
    if review is None:
        return None
    _recount(session, session_id)
    counts = review_progress_counts(session, session_id)
    if counts["remaining_count"] > 0:
        raise UnfinishedReviewError(
            f"还有未批阅内容({counts['remaining_count']} 条可批阅),无法完成会话"
        )
    if review.status != "completed":
        review.status = "completed"
        review.completed_at = utc_now()
        review.updated_at = utc_now()
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
    """设置批阅位置(断点恢复):随批阅移动更新 current_index。

    位置到达末尾时只在 ``remaining_count == 0`` 才自动完成
    (Stage 8B.2 §10:媒体失效不再阻塞,仍有可批阅内容则保持 active)。
    """
    review = get_session(session, session_id)
    if review is None or review.status != "active":
        return None
    clamped = max(0, min(int(index), review.total_count))
    review.current_index = clamped
    review.updated_at = utc_now()
    if clamped >= review.total_count:
        _recount(session, session_id)
        if _remaining_count(session, session_id) == 0:
            review.status = "completed"
            review.completed_at = utc_now()
    session.flush()
    return review


def _available_item_condition() -> sa.ColumnElement[bool]:
    """SessionItem 对应媒体当前是否可用（缺失的媒体行视为 unavailable）。"""
    return sa.and_(
        MediaCacheIndex.media_id.is_not(None),
        MediaCacheIndex.is_available.is_(True),
    )


def review_progress_counts(session: Session, session_id: str) -> dict:
    """**统一**的 Session 进度计数（Stage 8B.2 §6/§7，所有 Review API 共用）。

    - ``total_count``        = Session 创建时固化的原始队列长度（不压缩、不动态调整）；
    - ``seen_count``         = 被用户实际批阅过的 SessionItem 数；
    - ``unavailable_count``  = 当前已失效（无媒体行 / is_available=false）的 SessionItem 数；
    - ``remaining_count``    = 当前仍可用且 seen=false 的数量（**完成条件**）；
    - ``completed_count``    = ``total_count - remaining_count``。

    注意：``completed_count`` **不等于** ``seen_count + unavailable_count`` ——
    一条媒体可能先 seen 后来再 unavailable，两边都会被计入；
    这里用 ``total - remaining`` 统一计算，避免重复计数（§6/§28 Case 3）。
    """
    item = ReviewSessionItem
    available = _available_item_condition()
    row = session.execute(
        sa.select(
            sa.func.count().label("total"),
            sa.func.sum(sa.case((item.seen.is_(True), 1), else_=0)).label("seen"),
            sa.func.sum(sa.case((available, 0), else_=1)).label("unavailable"),
            sa.func.sum(
                sa.case((sa.and_(item.seen.is_(False), available), 1), else_=0)
            ).label("remaining"),
        )
        .select_from(item)
        .join(MediaCacheIndex, MediaCacheIndex.media_id == item.media_id, isouter=True)
        .where(item.session_id == session_id)
    ).one()
    total = int(row.total or 0)
    remaining = int(row.remaining or 0)
    return {
        "total_count": total,
        "seen_count": int(row.seen or 0),
        "unavailable_count": int(row.unavailable or 0),
        "remaining_count": remaining,
        "completed_count": total - remaining,
    }


def _remaining_count(session: Session, session_id: str) -> int:
    return review_progress_counts(session, session_id)["remaining_count"]


def nearest_available_item(
    session: Session,
    session_id: str,
    *,
    index: int,
    direction: str = "nearest",
) -> int | None:
    """SQL 直查最近可用项（Stage 8B.2 §16/§17）。

    - ``forward``  : 第一个 ``index >= anchor`` 的可用项；
    - ``backward`` : ``index < anchor`` 中最大的可用项；
    - ``nearest``  : 先 forward，找不到再 backward（与客户端恢复规则一致）。

    整条 SQL 由 ``LIMIT 1`` 命中，绝不把 SessionItem 拉进 Python 循环。
    """
    item = ReviewSessionItem
    base = (
        sa.select(item.index)
        .join(MediaCacheIndex, MediaCacheIndex.media_id == item.media_id)
        .where(item.session_id == session_id, MediaCacheIndex.is_available.is_(True))
    )
    if direction == "forward":
        return _scalar_index(session, base.where(item.index >= index).order_by(item.index.asc()))
    if direction == "backward":
        return _scalar_index(session, base.where(item.index < index).order_by(item.index.desc()))
    found = _scalar_index(session, base.where(item.index >= index).order_by(item.index.asc()))
    if found is not None:
        return found
    return _scalar_index(session, base.where(item.index < index).order_by(item.index.desc()))


def _scalar_index(session: Session, statement) -> int | None:
    value = session.scalar(statement.limit(1))
    return int(value) if value is not None else None


def _recount(session: Session, session_id: str) -> None:
    """会话计数落库（统一走 [review_progress_counts]，禁止各接口自己算）。"""
    counts = review_progress_counts(session, session_id)
    review = session.get(ReviewSession, session_id)
    if review is not None:
        review.seen_count = counts["seen_count"]
        review.updated_at = utc_now()


def progress_view(session: Session, review: ReviewSession) -> dict:
    return {
        "session_id": review.session_id,
        "status": review.status,
        "current_index": review.current_index,
        **review_progress_counts(session, review.session_id),
        "created_at": review.created_at,
        "updated_at": review.updated_at,
        "completed_at": review.completed_at,
    }


def source_view(review: ReviewSession) -> dict:
    return {
        "filter": json.loads(review.filter_snapshot or "{}"),
        "sort": json.loads(review.sort_snapshot or "{}"),
    }


def session_view(session: Session, review: ReviewSession) -> dict:
    return {
        **progress_view(session, review),
        "source": source_view(review),
    }


__all__ = [
    "UnfinishedReviewError",
    "advance",
    "complete",
    "complete_all_active",
    "create_session",
    "create_session_from_index",
    "get_session",
    "latest_active_session",
    "mark_seen",
    "nearest_available_item",
    "progress_view",
    "review_progress_counts",
    "session_items",
    "session_queue_page",
    "session_view",
    "set_position",
    "source_view",
]
