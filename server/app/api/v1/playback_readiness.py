"""认证播放诊断：Jellyfin 源探测 Server→Jellyfin；本地源直读，不冒充 Jellyfin 可达性。"""

from typing import Any

from fastapi import APIRouter, Depends, Query, Request
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.adapters.jellyfin.client import JellyfinClient
from app.api.v1.auth import require_auth
from app.api.v1.jellyfin import jellyfin_client
from app.api.v1.media import _resolve_stream_key
from app.core.errors import AppError, JellyfinError
from app.core.responses import Envelope, ok
from app.db.models import MediaCacheIndex
from app.db.session import get_db

router = APIRouter(prefix="/system", tags=["system"])

LOCAL_DIRECT_READ_MESSAGE = "本地来源直读：使用已配对设备认证，不依赖 Jellyfin"


def _local_readiness(db: Session, row: MediaCacheIndex) -> dict[str, Any]:
    """本地源诊断：只验证本地句柄可读，绝不把 Jellyfin 不可达当作本地源问题。"""
    data: dict[str, Any] = {
        "source_type": "local",
        # 本地源播放与 Jellyfin 无关，显式置空而不是显示"不可达"。
        "jellyfin_server_reachable": None,
        "client_stream_base": "",
        "client_stream_source": "configured_client_url",
        "client_stream_authoritative": False,
        "client_address_status": "not_applicable",
        "device_playback_key_status": "not_applicable",
        "media_id": row.media_id,
        "media_valid": False,
        "direct_stream": {"status": "not_checked", "http_status": None, "bytes_read": 0},
        "phone_reachability": "not_tested",
        "local_direct_read": {"status": "not_checked", "message": LOCAL_DIRECT_READ_MESSAGE},
    }
    if row.media_type != "video":
        data["local_direct_read"] = {
            "status": "not_video",
            "message": "本地图片不走播放链路，请直接读取原图",
        }
        return data
    try:
        from app.services.native_sources import open_media

        handle, _info = open_media(db, row)
        handle.close()
    except AppError:
        data["local_direct_read"] = {
            "status": "unavailable",
            "message": "本地文件当前不可读取，请在电脑端重新扫描本地来源",
        }
        return data
    data["local_direct_read"]["status"] = "readable"
    data["media_valid"] = True
    data["direct_stream"] = {"status": "readable", "http_status": None, "bytes_read": 0}
    return data


@router.get("/playback-readiness", response_model=Envelope[dict[str, Any]])
async def playback_readiness(
    request: Request,
    media_id: str | None = Query(default=None, max_length=256),
    device=Depends(require_auth),
    db: Session = Depends(get_db),
    client: JellyfinClient = Depends(jellyfin_client),
) -> Envelope[dict[str, Any]]:
    config = request.app.state.settings.jellyfin
    if media_id:
        local_row = db.get(MediaCacheIndex, media_id)
        if local_row is not None and local_row.source_type == "local":
            return ok(_local_readiness(db, local_row))
    data: dict[str, Any] = {
        "source_type": "jellyfin",
        "jellyfin_server_reachable": False,
        "client_stream_base": "",
        "client_stream_source": "configured_client_url"
        if config.client_url
        else "request_host_fallback",
        "client_stream_authoritative": bool(config.client_url),
        "client_address_status": "valid",
        "device_playback_key_status": "not_checked",
        "media_id": None,
        "media_valid": False,
        "direct_stream": {"status": "not_checked", "http_status": None, "bytes_read": 0},
        "phone_reachability": "not_tested",
    }
    try:
        data["client_stream_base"] = config.client_base_url(request.url.hostname or "")
    except ValueError:
        data["client_address_status"] = "invalid"
    try:
        await client.system_info()
        data["jellyfin_server_reachable"] = True
    except JellyfinError:
        return ok(data)
    query = select(MediaCacheIndex).where(
        MediaCacheIndex.media_type == "video", MediaCacheIndex.is_available.is_(True)
    )
    if media_id:
        query = query.where(MediaCacheIndex.media_id == media_id)
    row = db.scalar(query.order_by(MediaCacheIndex.media_id).limit(1))
    if row is None:
        data["direct_stream"]["status"] = "no_video"
        return ok(data)
    data["media_id"] = row.media_id
    try:
        key = await _resolve_stream_key(db, device, client)
    except JellyfinError:
        data["device_playback_key_status"] = "unavailable"
        return ok(data)
    data["direct_stream"] = await client.direct_stream_probe(row.jellyfin_id, key)
    # Direct Range 成功同时确认媒体存在与设备凭据有效，不额外要求 user_id。
    data["media_valid"] = data["direct_stream"]["status"] == "readable"
    data["device_playback_key_status"] = (
        "valid"
        if data["direct_stream"]["status"] == "readable"
        else "rejected"
        if data["direct_stream"]["status"] == "auth_failed"
        else "unverified"
    )
    return ok(data)
