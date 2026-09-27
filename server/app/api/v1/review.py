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

import secrets

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.api.v1.auth import require_auth
from app.api.v1.media import (
    MediaSummary,
    _summary_from_row,
    media_original_url,
    media_thumbnail_url,
    row_source_version,
)
from app.core.errors import ConflictError, NotFoundError, ValidationFailedError
from app.core.responses import Envelope, ok
from app.db.session import get_db
from app.services import media_index, review

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


def _validated_source(body: CreateSessionBody) -> tuple[dict, dict]:
    """按 GET /media 的字段语义校验灵活 source 快照。"""
    filter_snapshot = dict(body.source.filter)
    sort_snapshot = dict(body.source.sort)
    media_type = filter_snapshot.get("media_type")
    if media_type is not None and media_type not in ("video", "image"):
        raise ValidationFailedError("不支持的媒体类型")
    search = filter_snapshot.get("search")
    if search is not None and (not isinstance(search, str) or len(search) > 200):
        raise ValidationFailedError("搜索条件必须是最多 200 个字符的文本")
    sort_by = sort_snapshot.get("sort_by", "name")
    if sort_by not in ("name", "created", "size", "duration", "resolution", "random"):
        raise ValidationFailedError("不支持的排序字段")
    sort_order = sort_snapshot.get("sort_order", "asc")
    if sort_order not in ("asc", "desc"):
        raise ValidationFailedError("不支持的排序方向")
    random_seed = sort_snapshot.get("random_seed")
    if random_seed is not None and (not isinstance(random_seed, str) or len(random_seed) > 128):
        raise ValidationFailedError("随机排序种子必须是最多 128 个字符的文本")
    if sort_by == "random" and not random_seed:
        sort_snapshot["random_seed"] = secrets.token_hex(16)
    return filter_snapshot, sort_snapshot


@router.post("/sessions", response_model=Envelope[dict])
async def create_review_session(
    body: CreateSessionBody,
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[dict]:
    filter_snapshot, sort_snapshot = _validated_source(body)
    targets = media_index.selected_library_ids(db)
    try:
        created = review.create_session_from_index(
            db,
            library_ids=targets,
            filter_snapshot=filter_snapshot,
            sort_snapshot=sort_snapshot,
        )
    except ValueError as exc:
        raise ValidationFailedError(str(exc)) from exc
    db.commit()
    return ok(review.session_view(created))


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
    """返回不含 Jellyfin 凭据的 MediaReview 相对图片 URL。"""
    settings = request.app.state.settings.jellyfin
    if not settings.is_configured() or not media.jellyfin_id:
        return None, None
    # 阶段 8A.1.1 §6: 与 /media 一致带上 source_version,保证客户端缓存失效逻辑相同
    return (
        media_thumbnail_url(media.media_id, row_source_version(media)),
        media_original_url(media),
    )


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
    paged, total = review.session_queue_page(
        db,
        session_id,
        page=page,
        page_size=page_size,
    )
    result: list[QueueItem] = []
    for index, media in paged:
        cover, original = _queue_urls(request, media)
        result.append(
            QueueItem(
                index=index,
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
