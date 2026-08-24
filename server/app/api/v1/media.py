"""媒体接口: SQLite 媒体墙数据源与后台刷新入口。

- GET /media: 只从 SQLite count/filter/order/page，不等待 Jellyfin
- POST /media/refresh: 幂等编排持久化媒体刷新任务
- GET /media/{media_id}: 读取本地缓存索引的单条媒体详情

图片字段返回需 MediaReview 认证的相对代理 URL；视频播放不经过中间层代理。
"""

from __future__ import annotations

from datetime import datetime
from typing import Literal

from fastapi import APIRouter, Depends, Query, Request, Response
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.adapters.jellyfin.client import JellyfinClient
from app.adapters.jellyfin.models import MediaType
from app.api.v1.auth import require_auth
from app.api.v1.jellyfin import jellyfin_client
from app.core.errors import MediaNotFoundError, ValidationFailedError
from app.core.responses import Envelope, ok
from app.db.session import get_db
from app.services import media_index
from app.services import tasks as task_service

router = APIRouter(prefix="/media", tags=["media"])

SortField = Literal["name", "created", "size", "duration", "resolution", "random"]
SortOrder = Literal["asc", "desc"]


class MediaSummary(BaseModel):
    media_id: str
    name: str
    media_type: MediaType
    library_id: str
    duration_ms: int | None = None
    size_bytes: int | None = None
    width: int | None = None
    height: int | None = None
    container: str | None = None
    created_at: datetime | None = None
    modified_at: datetime | None = None
    # 需 MediaReview 配对认证的相对缩略图代理 URL
    cover_url: str | None = None
    # 需 MediaReview 配对认证的相对原图代理 URL；视频为 None
    original_url: str | None = None


class MediaSyncSummary(BaseModel):
    state: Literal["idle", "pending", "running", "succeeded", "failed", "cancelled"]
    stale: bool
    task_id: str | None = None
    processed: int
    total: int
    last_success_at: datetime | None = None
    message: str


class MediaPage(BaseModel):
    items: list[MediaSummary]
    total: int
    page: int
    page_size: int
    sync: MediaSyncSummary


class MediaRefreshBody(BaseModel):
    library_ids: list[str] | None = None
    force: bool = False


def media_thumbnail_url(media_id: str) -> str:
    return f"/api/v1/media/{media_id}/thumbnail"


def media_original_url(item) -> str | None:
    if item.media_type != "image":
        return None
    return f"/api/v1/media/{item.media_id}/original"


def _summary_from_row(
    row, cover_url: str | None = None, original_url: str | None = None
) -> MediaSummary:
    return MediaSummary(
        media_id=row.media_id,
        name=row.name,
        media_type=row.media_type,
        library_id=row.library_id,
        duration_ms=row.duration_ms,
        size_bytes=row.size_bytes,
        width=row.width,
        height=row.height,
        container=row.container,
        created_at=row.created_at,
        modified_at=row.modified_at,
        cover_url=cover_url,
        original_url=original_url,
    )


def _require_user_id(request: Request) -> str:
    user_id = request.app.state.settings.jellyfin.user_id
    if not user_id:
        raise ValidationFailedError(
            "尚未确定 Jellyfin 用户,请先调用 /api/v1/libraries 完成媒体库配置"
        )
    return user_id


@router.get("", response_model=Envelope[MediaPage])
async def list_media(
    request: Request,
    library_id: str | None = Query(default=None, min_length=1),
    media_type: MediaType | None = Query(default=None),
    sort_by: SortField = Query(default="name"),
    sort_order: SortOrder = Query(default="asc"),
    page: int = Query(default=1, ge=1),
    page_size: int = Query(default=50, ge=1, le=200),
    search: str | None = Query(default=None, max_length=200),
    exclude_favorites: bool = Query(default=False, description="排除已点赞媒体(未点赞筛选)"),
    random_seed: str | None = Query(default=None, max_length=128),
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[MediaPage]:
    """立即返回数据库分页；空索引只编排一次后台刷新。"""
    _require_user_id(request)
    selected = set(media_index.selected_library_ids(db))
    if library_id:
        targets = [library_id]
        auto_refresh_allowed = library_id in selected
    else:
        targets = sorted(selected)
        auto_refresh_allowed = True
        if not targets:
            raise ValidationFailedError("尚未勾选任何媒体库,请先完成媒体库配置")

    available_count = media_index.available_media_count(db, targets)
    if available_count == 0 and auto_refresh_allowed:
        media_index.schedule_media_refresh(db, targets)
    paged, total = media_index.list_cached_media(
        db,
        library_ids=targets,
        media_type=media_type,
        search=search,
        exclude_favorites=exclude_favorites,
        sort_by=sort_by,
        sort_order=sort_order,
        page=page,
        page_size=page_size,
        random_seed=random_seed,
    )
    sync = media_index.media_sync_view(db, targets, available_count=available_count)
    return ok(
        MediaPage(
            items=[
                _summary_from_row(
                    row,
                    cover_url=media_thumbnail_url(row.media_id),
                    original_url=media_original_url(row),
                )
                for row in paged
            ],
            total=total,
            page=page,
            page_size=page_size,
            sync=MediaSyncSummary.model_validate(sync),
        )
    )


@router.post("/refresh", status_code=202, response_model=Envelope[dict])
async def refresh_media(
    body: MediaRefreshBody,
    request: Request,
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[dict]:
    """编排所选媒体库刷新；同目标集合 pending/running 时返回原任务。"""
    _require_user_id(request)
    selected = media_index.selected_library_ids(db)
    if not selected:
        raise ValidationFailedError("尚未勾选任何媒体库,请先完成媒体库配置")
    targets = selected if body.library_ids is None else sorted(set(body.library_ids))
    if not targets or any(library_id not in set(selected) for library_id in targets):
        raise ValidationFailedError("只能刷新已勾选且存在的媒体库")
    task = media_index.schedule_media_refresh(db, targets, force=body.force)
    return ok(task_service.safe_task_view(task))


@router.get("/{media_id}", response_model=Envelope[MediaSummary])
async def get_media(
    media_id: str,
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[MediaSummary]:
    """按 media_id 读取单条媒体详情(来自本地缓存索引)。"""
    row = media_index.get_cached_media(db, media_id)
    if row is None:
        raise MediaNotFoundError()
    return ok(
        _summary_from_row(
            row,
            cover_url=media_thumbnail_url(row.media_id),
            original_url=media_original_url(row),
        )
    )


def _image_response(payload: bytes, content_type: str) -> Response:
    return Response(
        content=payload,
        media_type=content_type,
        headers={"X-Content-Type-Options": "nosniff"},
    )


@router.get("/{media_id}/thumbnail", response_class=Response)
async def get_media_thumbnail(
    media_id: str,
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
    client: JellyfinClient = Depends(jellyfin_client),
) -> Response:
    row = media_index.get_cached_media(db, media_id)
    if row is None or not row.jellyfin_id:
        raise MediaNotFoundError()
    payload, content_type = await client.thumbnail_image(row.jellyfin_id)
    return _image_response(payload, content_type)


@router.get("/{media_id}/original", response_class=Response)
async def get_media_original(
    media_id: str,
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
    client: JellyfinClient = Depends(jellyfin_client),
) -> Response:
    row = media_index.get_cached_media(db, media_id)
    if row is None or not row.jellyfin_id:
        raise MediaNotFoundError()
    if row.media_type != "image":
        raise ValidationFailedError("仅图片支持原图读取")
    payload, content_type = await client.original_image(row.jellyfin_id)
    return _image_response(payload, content_type)


class PlaybackInfo(BaseModel):
    media_id: str
    title: str
    stream_url: str
    requires_jellyfin_auth: bool
    message: str
    media_type: str
    duration_ms: int | None = None
    width: int | None = None
    height: int | None = None
    container: str | None = None


@router.get("/{media_id}/playback", response_model=Envelope[PlaybackInfo])
async def get_playback_info(
    media_id: str,
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
    client: JellyfinClient = Depends(jellyfin_client),
) -> Envelope[PlaybackInfo]:
    """获取媒体播放信息: 返回无凭据 Jellyfin 直连地址与视频元数据。

    当前尚无可撤销的客户端级 Jellyfin 凭据合同，因此明确标记为需要认证；
    中间层不转发视频流。仅视频支持播放。
    """
    row = media_index.get_cached_media(db, media_id)
    if row is None:
        raise MediaNotFoundError()
    if row.media_type != "video":
        raise ValidationFailedError("仅视频支持播放,该媒体非视频类型")
    stream_url = client.video_stream_url(row.jellyfin_id)
    return ok(
        PlaybackInfo(
            media_id=row.media_id,
            title=row.name,
            stream_url=stream_url,
            requires_jellyfin_auth=True,
            message="需要为此设备配置 Jellyfin 客户端认证后才能播放；当前不会下发服务器 API Key",
            media_type=row.media_type,
            duration_ms=row.duration_ms,
            width=row.width,
            height=row.height,
            container=row.container,
        )
    )


class ProgressBody(BaseModel):
    position_ms: int = Field(default=0, ge=0)
    is_paused: bool = False


@router.post("/{media_id}/progress", response_model=Envelope[dict])
async def report_progress(
    media_id: str,
    body: ProgressBody,
    request: Request,
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
    client: JellyfinClient = Depends(jellyfin_client),
) -> Envelope[dict]:
    """回传播放进度到 Jellyfin(Sessions/Playing/Progress),形成播放闭环。"""
    row = media_index.get_cached_media(db, media_id)
    if row is None:
        raise MediaNotFoundError()
    if row.media_type != "video" or not row.jellyfin_id:
        raise ValidationFailedError("仅视频支持进度上报")
    user_id = request.app.state.settings.jellyfin.user_id
    if not user_id:
        raise ValidationFailedError("尚未确定 Jellyfin 用户,请先完成媒体库配置")
    await client.report_progress(
        user_id=user_id,
        item_id=row.jellyfin_id,
        position_ms=body.position_ms,
        is_paused=body.is_paused,
    )
    return ok({"media_id": media_id, "reported": True})
