"""媒体接口: 媒体墙数据源。

- GET /media: 从已选媒体库分页拉取统一 MediaDTO,支持排序/筛选/搜索,并落地缓存索引
- GET /media/{media_id}: 读取本地缓存索引的单条媒体详情

播放/缩略图直连 URL 由阶段 Playback API 单独提供。
"""

from __future__ import annotations

from datetime import datetime
from typing import Literal

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.adapters.jellyfin.client import JellyfinClient
from app.adapters.jellyfin.mapper import include_types_for
from app.adapters.jellyfin.models import MediaType
from app.api.v1.auth import require_auth
from app.api.v1.jellyfin import jellyfin_client
from app.core.errors import MediaNotFoundError, ValidationFailedError
from app.core.responses import Envelope, ok
from app.db.session import get_db
from app.services import favorites, media_index

router = APIRouter(prefix="/media", tags=["media"])

SortField = Literal["name", "created", "size", "duration", "resolution", "random"]
SortOrder = Literal["asc", "desc"]

# 允许客户端传入的排序字段白名单(服务端映射,禁止直接拼 SQL/上游字段)
_SORT_KEY_FN = {
    "name": lambda it: it.name.casefold() if it.name else "",
    "created": lambda it: it.created_at,
    "size": lambda it: it.size_bytes,
    "duration": lambda it: it.duration_ms,
    "resolution": lambda it: ((it.width or 0) * (it.height or 0)) or None,
    "random": lambda it: it.media_id,
}


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
    # 封面缩略图直连 URL(客户端可直连 Jellyfin,中间层不代理图片字节)
    cover_url: str | None = None
    # 原图直连 URL(图片查看器优先读取;视频为 None,走播放 API)
    original_url: str | None = None


class MediaPage(BaseModel):
    items: list[MediaSummary]
    total: int
    page: int
    page_size: int


def _original_url(client: JellyfinClient, item) -> str | None:
    """图片返回原图直连 URL;视频原图走播放 API,这里不返回。"""
    if item.media_type != "image":
        return None
    return client.image_original_url(item.jellyfin_id)


def _summary_from_item(
    item, cover_url: str | None = None, original_url: str | None = None
) -> MediaSummary:
    return MediaSummary(
        media_id=item.media_id,
        name=item.name,
        media_type=item.media_type,
        library_id=item.library_id,
        duration_ms=item.duration_ms,
        size_bytes=item.size_bytes,
        width=item.width,
        height=item.height,
        container=item.container,
        created_at=item.created_at,
        modified_at=item.modified_at,
        cover_url=cover_url,
        original_url=original_url,
    )


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


def _sort_items(items, sort_by: SortField, sort_order: SortOrder):
    """按白名单排序;字段缺失(None)永远排在末尾,与升降序无关。"""
    key = _SORT_KEY_FN[sort_by]
    present = [it for it in items if key(it) is not None]
    missing = [it for it in items if key(it) is None]
    present.sort(key=key, reverse=(sort_order == "desc"))
    return present + missing


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
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
    client: JellyfinClient = Depends(jellyfin_client),
) -> Envelope[MediaPage]:
    """分页返回媒体墙数据(支持单库或全部已选库、排序、类型筛选、搜索、未点赞)。"""
    user_id = _require_user_id(request)
    if library_id:
        targets = [library_id]
    else:
        targets = media_index.selected_library_ids(db)
        if not targets:
            raise ValidationFailedError("尚未勾选任何媒体库,请先完成媒体库配置")

    include_types = include_types_for(media_type)
    # 媒体快照缓存:TTL 内同源(库集+类型+搜索)分页/切排序不再重新扫描 Jellyfin 全库
    all_items = media_index.get_media_snapshot(targets, media_type, search)
    if all_items is None:
        collected: list[object] = []
        seen: set[str] = set()
        for lib_id in targets:
            for item in await media_index.collect_library_items(
                client, user_id, lib_id, include_types=include_types, search_term=search
            ):
                if item.media_id not in seen:
                    seen.add(item.media_id)
                    collected.append(item)
        media_index.put_media_snapshot(targets, media_type, search, collected)
        all_items = collected
    # 无论快照命中与否都落地 SQLite(供详情/播放/收藏等按 DB 读取),但不触发 Jellyfin 采集
    media_index.upsert_media_items(db, all_items)

    if exclude_favorites:
        favorite_ids = set(favorites.list_ids(db))
        all_items = [it for it in all_items if it.media_id not in favorite_ids]
    ordered = _sort_items(all_items, sort_by, sort_order)
    offset = (page - 1) * page_size
    paged = ordered[offset : offset + page_size]
    return ok(
        MediaPage(
            items=[
                _summary_from_item(
                    it,
                    cover_url=client.thumbnail_url(it.jellyfin_id),
                    original_url=_original_url(client, it),
                )
                for it in paged
            ],
            total=len(all_items),
            page=page,
            page_size=page_size,
        )
    )


@router.get("/{media_id}", response_model=Envelope[MediaSummary])
async def get_media(
    media_id: str,
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
    client: JellyfinClient = Depends(jellyfin_client),
) -> Envelope[MediaSummary]:
    """按 media_id 读取单条媒体详情(来自本地缓存索引)。"""
    row = media_index.get_cached_media(db, media_id)
    if row is None:
        raise MediaNotFoundError()
    return ok(
        _summary_from_row(
            row,
            cover_url=client.thumbnail_url(row.jellyfin_id),
            original_url=_original_url(client, row),
        )
    )


class PlaybackInfo(BaseModel):
    media_id: str
    title: str
    stream_url: str
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
    """获取媒体播放信息: 返回 Jellyfin 直连流地址与视频元数据。

    接口只返回直连 URL,不自带凭据;Android 用其直连 Jellyfin 播放,
    中间层不转发视频流(见 AGENTS.md 禁止事项)。仅视频支持播放。
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
