"""批阅引擎接口。

- POST /review/sessions                创建批阅会话(固化为一次性队列)
- GET  /review/sessions/{session_id}   会话总览(source + progress)
- GET  /review/sessions/latest         断点恢复: 最近活动会话
- GET  /review/sessions/{session_id}/queue        队列媒体(排序后,含摘要 + seen 状态)
- GET  /review/sessions/{session_id}/nearest      最近可用项(SQL 直查,稀疏队列恢复/跳页)
- POST /review/sessions/{session_id}/seen        标记某媒体已看/未看(返回权威进度计数)
- POST /review/sessions/{session_id}/advance     前进到下一项(当前项视为已看)
- POST /review/sessions/{session_id}/complete    手动完成会话(fail-closed:有未批阅则拒绝)
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
    # Stage 8B.1 §11:队列项必须携带会话的已看状态(客户端恢复后知道是否已批阅过)
    seen: bool = False
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
    return ok(review.session_view(db, created))


@router.get("/sessions/latest", response_model=Envelope[dict])
async def latest_session(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[dict]:
    active = review.latest_active_session(db)
    if active is None:
        raise NotFoundError(message="没有可恢复的批阅会话")
    return ok(review.session_view(db, active))


@router.get("/sessions/{session_id}", response_model=Envelope[dict])
async def get_review_session(
    session_id: str, _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[dict]:
    return ok(review.session_view(db, _get_review(db, session_id)))


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
    for index, seen, media in paged:
        cover, original = _queue_urls(request, media)
        result.append(
            QueueItem(
                index=index,
                seen=seen,
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
    """标记已看/未看,返回**服务端权威**进度(total/seen/unavailable/remaining/completed)。

    重复标记幂等(不会重复计数);Android 端不得自行 +1(Stage 8B.1 §13/§14)。
    """
    _get_review(db, session_id)
    result = review.mark_seen(db, session_id, body.media_id, body.seen)
    if result is None:
        raise NotFoundError(message="媒体不在该批阅会话中")
    db.commit()
    return ok(result)


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
    return ok(review.progress_view(db, updated))


@router.post("/sessions/{session_id}/advance", response_model=Envelope[dict])
async def advance(
    session_id: str, _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[dict]:
    updated = review.advance(db, session_id)
    if updated is None:
        raise ConflictError(message="会话已完成或不存在,无法前进")
    db.commit()
    return ok(review.progress_view(db, updated))


@router.post("/sessions/{session_id}/complete", response_model=Envelope[dict])
async def complete_session(
    session_id: str, _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[dict]:
    """手动完成会话(fail-closed:``remaining_count > 0`` 时拒绝,会话保持 active)。

    Stage 8B.2 §8/§9:完成条件是"没有仍可批阅的内容",媒体失效不再永久阻塞完成。
    """
    try:
        updated = review.complete(db, session_id)
    except review.UnfinishedReviewError as exc:
        raise ConflictError(message=str(exc)) from exc
    if updated is None:
        raise NotFoundError(message="批阅会话不存在")
    db.commit()
    return ok(review.progress_view(db, updated))


@router.get("/sessions/{session_id}/nearest", response_model=Envelope[dict])
async def nearest_available(
    session_id: str,
    index: int = Query(ge=0),
    direction: str = Query(default="nearest"),
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[dict]:
    """SQL 直查最近可用项(Stage 8B.2 §16/§17):``{"index": <int|null>}``。

    - ``forward``: 第一个 ``index >= 锚点`` 的可用项;
    - ``backward``: 锚点之前最近的可用项;
    - ``nearest``: 先向后、找不到再向前(与客户端恢复规则一致)。

    返回 ``index=null`` 表示**服务端明确**该方向上没有可用媒体 ——
    与"网络失败"是完全不同的结果,客户端不得混为一谈(§34)。
    """
    _get_review(db, session_id)
    if direction not in ("forward", "backward", "nearest"):
        raise ValidationFailedError("direction 只支持 forward / backward / nearest")
    found = review.nearest_available_item(db, session_id, index=index, direction=direction)
    return ok({"index": found})


__all__ = ["router"]
