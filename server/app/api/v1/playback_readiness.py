"""认证播放诊断：Server→Jellyfin，不冒充手机 LAN 可达性。"""

from typing import Any

from fastapi import APIRouter, Depends, Query, Request
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.adapters.jellyfin.client import JellyfinClient
from app.api.v1.auth import require_auth
from app.api.v1.jellyfin import jellyfin_client
from app.api.v1.media import _resolve_stream_key
from app.core.errors import JellyfinError
from app.core.responses import Envelope, ok
from app.db.models import MediaCacheIndex
from app.db.session import get_db

router = APIRouter(prefix="/system", tags=["system"])


@router.get("/playback-readiness", response_model=Envelope[dict[str, Any]])
async def playback_readiness(
    request: Request,
    media_id: str | None = Query(default=None, max_length=256),
    device=Depends(require_auth),
    db: Session = Depends(get_db),
    client: JellyfinClient = Depends(jellyfin_client),
) -> Envelope[dict[str, Any]]:
    config = request.app.state.settings.jellyfin
    data: dict[str, Any] = {
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
