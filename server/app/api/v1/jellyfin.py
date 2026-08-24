"""Jellyfin 连通性接口: 状态、用户、媒体库列表。

媒体分页等业务接口在阶段 3 的 media API 提供。
"""

from __future__ import annotations

from collections.abc import AsyncIterator

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel

from app.adapters.jellyfin.client import JellyfinClient
from app.api.v1.auth import require_localhost_or_auth
from app.core.config import AppConfig
from app.core.errors import ConfigError
from app.core.responses import Envelope, ok

router = APIRouter(prefix="/jellyfin", tags=["jellyfin"])


class JellyfinStatus(BaseModel):
    server_name: str
    version: str
    jellyfin_id: str


class JellyfinUser(BaseModel):
    jellyfin_id: str
    name: str


class JellyfinLibrary(BaseModel):
    jellyfin_id: str
    name: str
    collection_type: str | None = None


async def jellyfin_client(request: Request) -> AsyncIterator[JellyfinClient]:
    settings: AppConfig = request.app.state.settings
    if not settings.jellyfin.is_configured():
        raise ConfigError("Jellyfin 尚未配置 API Key,请先在管理后台完成配置")
    async with JellyfinClient(settings.jellyfin) as client:
        yield client


@router.get("/status", response_model=Envelope[JellyfinStatus])
async def status(
    _auth=Depends(require_localhost_or_auth),
    client: JellyfinClient = Depends(jellyfin_client),
) -> Envelope[JellyfinStatus]:
    info = await client.system_info()
    return ok(
        JellyfinStatus(
            server_name=info.server_name,
            version=info.version,
            jellyfin_id=info.jellyfin_id,
        )
    )


@router.get("/users", response_model=Envelope[list[JellyfinUser]])
async def users(
    _auth=Depends(require_localhost_or_auth),
    client: JellyfinClient = Depends(jellyfin_client),
) -> Envelope[list[JellyfinUser]]:
    raw_users = await client.users()
    return ok([JellyfinUser(jellyfin_id=u.jellyfin_id, name=u.name) for u in raw_users])


@router.get("/libraries", response_model=Envelope[list[JellyfinLibrary]])
async def libraries(
    _auth=Depends(require_localhost_or_auth),
    user_id: str = Query(min_length=1),
    client: JellyfinClient = Depends(jellyfin_client),
) -> Envelope[list[JellyfinLibrary]]:
    raw_libraries = await client.libraries(user_id)
    return ok(
        [
            JellyfinLibrary(
                jellyfin_id=lib.jellyfin_id,
                name=lib.name,
                collection_type=lib.collection_type,
            )
            for lib in raw_libraries
        ]
    )
