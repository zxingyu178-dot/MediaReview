"""Jellyfin 连通性接口: 状态、用户、媒体库列表。

媒体分页等业务接口在阶段 3 的 media API 提供。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel

from app.adapters.jellyfin.client import JellyfinClient
from app.adapters.jellyfin.manager import JellyfinClientManager
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


def _jellyfin_manager(request: Request) -> JellyfinClientManager:
    """取进程级客户端管理器;未经 lifespan 构造的应用(部分测试)也保持共享语义。"""
    manager = getattr(request.app.state, "jellyfin_manager", None)
    if manager is None:
        manager = JellyfinClientManager()
        request.app.state.jellyfin_manager = manager
    return manager


async def jellyfin_client(request: Request) -> JellyfinClient:
    """共享的长生命周期 Jellyfin 客户端(复用 Keep-Alive 连接池)。

    阶段 8A.1 起不再按请求新建客户端: 媒体墙一次会并发数十个缩略图请求,
    按请求建连会让每个封面都重新 TCP + 认证握手。
    """
    settings: AppConfig = request.app.state.settings
    if not settings.jellyfin.is_configured():
        raise ConfigError("Jellyfin 尚未配置 API Key,请先在管理后台完成配置")
    return await _jellyfin_manager(request).get(settings.jellyfin)


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
