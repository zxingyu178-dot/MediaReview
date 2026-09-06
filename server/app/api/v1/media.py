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

from app.adapters.jellyfin.client import JellyfinClient, JellyfinError
from app.adapters.jellyfin.models import MediaType
from app.api.v1.auth import require_auth
from app.api.v1.jellyfin import jellyfin_client
from app.core.config import ensure_jellyfin_url_excludes_api_key
from app.core.errors import ConfigError, MediaNotFoundError, ValidationFailedError
from app.core.responses import Envelope, ok
from app.db.models import utc_now
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


class FolderSummary(BaseModel):
    """文件夹辅助视图条目:folder_id 是服务器派生的不透明 ID,不下发任何文件路径。"""

    folder_id: str
    name: str
    count: int


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


@router.get("/folders", response_model=Envelope[list[FolderSummary]])
async def list_media_folders(
    request: Request,
    library_id: str | None = Query(default=None, min_length=1),
    media_type: MediaType | None = Query(default=None),
    search: str | None = Query(default=None, max_length=200),
    exclude_favorites: bool = Query(default=False),
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[list[FolderSummary]]:
    """文件夹辅助视图(媒体墙内使用,非独立导航):按父目录聚合当前筛选范围内的媒体。"""
    _require_user_id(request)
    selected = set(media_index.selected_library_ids(db))
    if library_id:
        targets = [library_id]
    else:
        targets = sorted(selected)
        if not targets:
            raise ValidationFailedError("尚未勾选任何媒体库,请先完成媒体库配置")
    folders = media_index.list_media_folders(
        db,
        library_ids=targets,
        media_type=media_type,
        search=search,
        exclude_favorites=exclude_favorites,
    )
    return ok([FolderSummary.model_validate(f) for f in folders])


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
    folder_id: str | None = Query(default=None, max_length=32, description="文件夹辅助视图 ID"),
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

    folder_dirname: str | None = None
    if folder_id:
        folder_dirname = media_index.resolve_folder_dirname(
            db,
            library_ids=targets,
            media_type=media_type,
            search=search,
            exclude_favorites=exclude_favorites,
            folder_id=folder_id,
        )
        if folder_dirname is None:
            raise MediaNotFoundError()

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
        folder_dirname=folder_dirname,
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


class PlaybackEndpoint(BaseModel):
    """单个播放端点:URL + 认证 headers。凭据只在 headers,绝不进入 URL。"""

    url: str
    headers: dict[str, str] = {}


class PlaybackInfo(BaseModel):
    """Task C 播放合同:Direct Play + 唯一一次 HLS 回退。

    - direct: Jellyfin 原盘直连(static=true);
    - fallback_hls: 唯一一次回退(master.m3u8 转码),Android 状态机不得二次回退;
    - headers 携带设备级 X-Emby-Token(服务端按设备签发/复用的命名 key,可撤销),
      它不等于服务端 API key,且绝不进入任何 URL;
    - stream_url 为一版兼容字段,恒等于 direct.url;旧客户端可忽略新增字段。
    """

    media_id: str
    title: str
    direct: PlaybackEndpoint
    fallback_hls: PlaybackEndpoint
    resume_position_ms: int = 0
    stream_url: str
    requires_jellyfin_auth: bool = False
    stream_url_authoritative: bool = False
    stream_url_source: Literal["configured_client_url", "request_host_fallback"] = (
        "request_host_fallback"
    )
    stream_url_rewrite_hosts: list[str] = []
    message: str
    media_type: str
    duration_ms: int | None = None
    width: int | None = None
    height: int | None = None
    container: str | None = None


PLAYBACK_KEY_PREFIX = "mediareview-"
_PLAYBACK_SHARED_KEY_NAME = "mediareview-shared-playback"


def _device_key_name(installation_id: str | None) -> str:
    if not installation_id:
        return _PLAYBACK_SHARED_KEY_NAME
    return f"{PLAYBACK_KEY_PREFIX}{installation_id[:48]}"


async def _resolve_stream_key(db: Session, device, client: JellyfinClient) -> str:
    """解析设备级播放凭据:设备行持久化复用;开发模式用共享命名 key。

    任何签发失败都抛 JellyfinError,由端点 fail-closed(不下发任何直连地址)。
    """
    if device is not None and device.jellyfin_key_value:
        return device.jellyfin_key_value
    name = _device_key_name(device.installation_id if device is not None else None)
    key = await client.ensure_device_stream_key(name)
    if device is not None:
        device.jellyfin_key_name = name
        device.jellyfin_key_value = key
        device.jellyfin_key_created_at = utc_now()
        db.commit()
    return key


@router.get("/{media_id}/playback", response_model=Envelope[PlaybackInfo])
async def get_playback_info(
    media_id: str,
    request: Request,
    device=Depends(require_auth),
    db: Session = Depends(get_db),
    client: JellyfinClient = Depends(jellyfin_client),
) -> Envelope[PlaybackInfo]:
    """获取媒体播放信息: Direct Play + 唯一一次 HLS 回退 + 设备级可撤销凭据。

    - 凭据: 服务端按设备签发/复用 Jellyfin 命名 key(可撤销),只放进
      ``direct.headers``/``fallback_hls.headers`` 的 X-Emby-Token;
    - 服务端自己的 API key 绝不出现在任何 URL/headers/序列化输出;
    - 中间层不转发视频流;仅视频支持播放;签发失败 fail-closed。
    """
    row = media_index.get_cached_media(db, media_id)
    if row is None:
        raise MediaNotFoundError()
    if row.media_type != "video":
        raise ValidationFailedError("仅视频支持播放,该媒体非视频类型")
    jellyfin_settings = request.app.state.settings.jellyfin
    request_host = request.url.hostname or ""
    try:
        client_base = jellyfin_settings.client_base_url(request_host)
    except ValueError as exc:
        # 回环/仅服务器可达的派生 host 不下发给客户端(fail-closed),转成可理解的中文错误
        raise ConfigError(
            "无法为当前网络环境生成可直连的 Jellyfin 播放地址,请配置 jellyfin.client_url"
        ) from exc
    server_key = jellyfin_settings.api_key.get_secret_value()
    authoritative = bool(jellyfin_settings.client_url)

    try:
        stream_key = await _resolve_stream_key(db, device, client)
    except JellyfinError as exc:
        raise ConfigError("无法为设备签发 Jellyfin 播放凭据,请稍后重试") from exc

    direct_url = client.video_stream_url(row.jellyfin_id, base_url=client_base)
    hls_url = client.hls_stream_url(row.jellyfin_id, base_url=client_base)
    for url in (direct_url, hls_url):
        try:
            ensure_jellyfin_url_excludes_api_key(url, server_key)
        except ValueError:
            raise ConfigError("Jellyfin 播放配置存在凭据泄漏风险") from None

    stream_headers = {"X-Emby-Token": stream_key}
    resume_position_ms = await client.item_resume_position_ms(
        jellyfin_settings.user_id or "", row.jellyfin_id
    )
    return ok(
        PlaybackInfo(
            media_id=row.media_id,
            title=row.name,
            direct=PlaybackEndpoint(url=direct_url, headers=stream_headers),
            fallback_hls=PlaybackEndpoint(url=hls_url, headers=dict(stream_headers)),
            resume_position_ms=resume_position_ms,
            stream_url=direct_url,
            requires_jellyfin_auth=False,
            stream_url_authoritative=authoritative,
            stream_url_source=(
                "configured_client_url" if authoritative else "request_host_fallback"
            ),
            stream_url_rewrite_hosts=[],
            message="已附带设备级 Jellyfin 播放凭据(可撤销);凭据只在 headers 中传递",
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
