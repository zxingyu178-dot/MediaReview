"""批阅引擎接口。

- POST /review/sessions                创建批阅会话(固化为一次性队列)
- GET  /review/sessions/{session_id}   会话总览(source + progress)
- GET  /review/sessions/latest         断点恢复: 最近活动会话
- GET  /review/sessions/{session_id}/queue        队列媒体(排序后,含摘要)
- POST /review/sessions/{session_id}/seen        标记某媒体已看/未看
- POST /review/sessions/{session_id}/advance     前进到下一项(当前项视为已看)
- POST /review/sessions/{session_id}/complete    手动完成会话
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.adapters.jellyfin.client import JellyfinClient, item_original_url, item_thumbnail_url
from app.adapters.jellyfin.mapper import include_types_for
from app.api.v1.auth import require_auth
from app.api.v1.jellyfin import build_jellyfin_client
from app.api.v1.media import _SORT_KEY_FN, MediaSummary, _sort_items, _summary_from_row
from app.core.errors import ConflictError, NotFoundError, ValidationFailedError
from app.core.responses import Envelope, ok
from app.db.session import get_db
from app.services import duplicate_scanner, media_index, review

router = APIRouter(prefix="/review", tags=["review"])


class SourceSnapshot(BaseModel):
    filter: dict = Field(default_factory=dict)
    sort: dict = Field(default_factory=dict)


class CreateSessionBody(BaseModel):
    """批阅会话创建请求:只提交 source(筛选/排序),队列由服务端按已选媒体库构建。"""

    source: SourceSnapshot = SourceSnapshot()


class SeenBody(BaseModel):
    media_id: str = Field(min_length=1)
    seen: bool = True


class QueueItem(BaseModel):
    index: int
    media: MediaSummary


class ReviewQueuePage(BaseModel):
    items: list[QueueItem]
    total: int
    page: int
    page_size: int


def _get_review(db: Session, session_id: str):
    review_session = review.get_session(db, session_id)
    if review_session is None:
        raise NotFoundError(message="批阅会话不存在")
    return review_session


async def _collect_review_queue(
    request: Request,
    client: JellyfinClient,
    db: Session,
    filter_snapshot: dict,
    sort_snapshot: dict,
) -> list:
    """按 source 从已选媒体库收集并排序完整批阅队列(供创建会话使用)。

    与 /media 列表同源:不依赖客户端提交 media_ids,天然支持数千/上万媒体。
    """
    user_id = request.app.state.settings.jellyfin.user_id
    if not user_id:
        raise ValidationFailedError("尚未确定 Jellyfin 用户,请先完成媒体库配置")
    targets = media_index.selected_library_ids(db)
    if not targets:
        raise ValidationFailedError("尚未勾选任何媒体库,请先完成媒体库配置")

    media_type = filter_snapshot.get("media_type")
    search = filter_snapshot.get("search")
    sort_by = sort_snapshot.get("sort_by", "name")
    sort_order = sort_snapshot.get("sort_order", "asc")
    if sort_by not in _SORT_KEY_FN:
        raise ValidationFailedError("不支持的排序字段")
    if sort_order not in ("asc", "desc"):
        raise ValidationFailedError("不支持的排序方向")

    include_types = include_types_for(media_type)
    all_items: list = []
    seen: set[str] = set()
    for lib_id in targets:
        for item in await media_index.collect_library_items(
            client,
            user_id,
            lib_id,
            include_types=include_types,
            search_term=search,
        ):
            if item.media_id not in seen:
                seen.add(item.media_id)
                all_items.append(item)
    if not all_items:
        raise ValidationFailedError("没有可批阅的媒体")
    media_index.upsert_media_items(db, all_items)
    ordered = _sort_items(all_items, sort_by, sort_order)
    return _dedupe_exact_duplicates(db, ordered)


def _dedupe_exact_duplicates(db: Session, ordered: list) -> list:
    """完全重复(byte-identical)文件在默认批阅队列只保留一个代表项。

    代表项取排序后最先出现的成员;其余从批阅队列移除。**只影响批阅队列,不自动
    删除任何文件**(删除仍需用户确认),与 AGENTS.md「疑似/重复不自动删除」一致。
    """
    groups = duplicate_scanner.scan_exact_duplicates(db)
    if not groups:
        return ordered
    drop: set[str] = set()
    for grp in groups:
        keep = next((m.media_id for m in ordered if m.media_id in grp.media_ids), None)
        for media_id in grp.media_ids:
            if media_id != keep:
                drop.add(media_id)
    if not drop:
        return ordered
    return [it for it in ordered if it.media_id not in drop]


@router.post("/sessions", response_model=Envelope[dict])
async def create_review_session(
    body: CreateSessionBody,
    request: Request,
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[dict]:
    # 客户端按需构建,避免 Jellyfin 未配置时在鉴权前抛 500(鉴权优先返回 401)
    client = await build_jellyfin_client(request)
    try:
        ordered = await _collect_review_queue(
            request,
            client,
            db,
            body.source.filter,
            body.source.sort,
        )
        # 新建会话前完成所有旧 active 会话,避免数据库长期累计多个 active
        review.complete_all_active(db)
        created = review.create_session(
            db,
            [it.media_id for it in ordered],
            filter_snapshot=body.source.filter,
            sort_snapshot=body.source.sort,
        )
        db.commit()
        return ok(review.session_view(created))
    finally:
        await client.close()


@router.get("/sessions/latest", response_model=Envelope[dict])
async def latest_session(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[dict]:
    active = review.latest_active_session(db)
    if active is None:
        raise NotFoundError(message="没有可恢复的批阅会话")
    return ok(review.session_view(active))


@router.get("/sessions/{session_id}", response_model=Envelope[dict])
async def get_review_session(
    session_id: str, _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[dict]:
    return ok(review.session_view(_get_review(db, session_id)))


def _queue_urls(request: Request, media) -> tuple[str | None, str | None]:
    """基于配置构造封面/原图直连 URL;Jellyfin 未配置时返回 None(不阻塞批阅接口)。"""
    settings = request.app.state.settings.jellyfin
    if not settings.is_configured() or not media.jellyfin_id:
        return None, None
    api_key = settings.api_key.get_secret_value()
    cover = item_thumbnail_url(settings.host, api_key, media.jellyfin_id)
    original = (
        item_original_url(settings.host, api_key, media.jellyfin_id)
        if media.media_type == "image"
        else None
    )
    return cover, original


@router.get("/sessions/{session_id}/queue", response_model=Envelope[ReviewQueuePage])
async def get_queue(
    session_id: str,
    request: Request,
    page: int = Query(default=1, ge=1),
    page_size: int = Query(default=50, ge=1, le=200),
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[ReviewQueuePage]:
    """分页返回批阅队列(按会话顺序)。支持数千/上万媒体,客户端按需翻页。"""
    _get_review(db, session_id)
    items = review.session_items(db, session_id)
    total = len(items)
    offset = (page - 1) * page_size
    paged = items[offset : offset + page_size]
    result: list[QueueItem] = []
    for row in paged:
        media = media_index.get_cached_media(db, row.media_id)
        if media is None:
            continue
        cover, original = _queue_urls(request, media)
        result.append(
            QueueItem(
                index=row.index,
                media=_summary_from_row(media, cover_url=cover, original_url=original),
            )
        )
    return ok(
        ReviewQueuePage(
            items=result,
            total=total,
            page=page,
            page_size=page_size,
        )
    )


@router.post("/sessions/{session_id}/seen", response_model=Envelope[dict])
async def set_seen(
    session_id: str,
    body: SeenBody,
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[dict]:
    _get_review(db, session_id)
    if not review.mark_seen(db, session_id, body.media_id, body.seen):
        raise NotFoundError(message="媒体不在该批阅会话中")
    db.commit()
    return ok({"media_id": body.media_id, "seen": body.seen})


class PositionBody(BaseModel):
    index: int = Field(default=0, ge=0)


@router.post("/sessions/{session_id}/position", response_model=Envelope[dict])
async def set_position(
    session_id: str,
    body: PositionBody,
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[dict]:
    """随批阅位置移动更新 current_index(断点恢复依据)。"""
    updated = review.set_position(db, session_id, body.index)
    if updated is None:
        raise NotFoundError(message="批阅会话不存在或已完成")
    db.commit()
    return ok(review.progress_view(updated))


@router.post("/sessions/{session_id}/advance", response_model=Envelope[dict])
async def advance(
    session_id: str, _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[dict]:
    updated = review.advance(db, session_id)
    if updated is None:
        raise ConflictError(message="会话已完成或不存在,无法前进")
    db.commit()
    return ok(review.progress_view(updated))


@router.post("/sessions/{session_id}/complete", response_model=Envelope[dict])
async def complete_session(
    session_id: str, _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[dict]:
    updated = review.complete(db, session_id)
    if updated is None:
        raise NotFoundError(message="批阅会话不存在")
    db.commit()
    return ok(review.progress_view(updated))


__all__ = ["router"]
