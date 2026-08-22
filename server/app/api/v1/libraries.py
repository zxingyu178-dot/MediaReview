"""媒体库勾选接口: 列出并保存多媒体库选择。

与 Jellyfin 的媒体库列表不同,这里会把勾选状态持久化到本地
library_selection 表,供媒体墙与后续批阅使用。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.adapters.jellyfin.client import JellyfinClient
from app.api.v1.auth import require_auth
from app.api.v1.jellyfin import jellyfin_client
from app.core.config import persist_jellyfin_user_id
from app.core.errors import ValidationFailedError
from app.core.responses import Envelope, ok
from app.db.session import get_db
from app.services import media_index

router = APIRouter(prefix="/libraries", tags=["libraries"])


class LibraryItem(BaseModel):
    jellyfin_id: str
    name: str
    collection_type: str | None = None
    selected: bool
    sort_order: int


class SelectionBody(BaseModel):
    # 勾选(selected=true)的媒体库 id 集合,其余已知库视为取消勾选
    selected: list[str] = Field(default_factory=list)


def _dto(row) -> LibraryItem:
    return LibraryItem(
        jellyfin_id=row.jellyfin_id,
        name=row.name,
        collection_type=row.collection_type,
        selected=row.selected,
        sort_order=row.sort_order,
    )


async def _resolve_user_id(request: Request, client: JellyfinClient, explicit: str | None) -> str:
    """确定当前 Jellyfin 用户: 显式参数 > 已持久化配置 > 单用户自动发现。"""
    if explicit:
        return explicit
    configured = request.app.state.settings.jellyfin.user_id
    if configured:
        return configured
    users = await client.users()
    if len(users) == 1:
        return users[0].jellyfin_id
    raise ValidationFailedError("无法自动确定 Jellyfin 用户,请在请求中指定 user_id 参数")


def _persist_user_id(request: Request, user_id: str) -> None:
    """更新内存配置并将其写入 config.json(仅当发生变化时)。"""
    settings = request.app.state.settings
    if settings.jellyfin.user_id == user_id:
        return
    settings.jellyfin.user_id = user_id
    persist_jellyfin_user_id(request.app.state.paths.config_file, user_id)


@router.get("", response_model=Envelope[list[LibraryItem]])
async def list_libraries(
    request: Request,
    user_id: str | None = Query(default=None, min_length=1),
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
    client: JellyfinClient = Depends(jellyfin_client),
) -> Envelope[list[LibraryItem]]:
    """列出 Jellyfin 媒体库并同步勾选状态(新增库默认勾选、保留已有勾选)。"""
    resolved = await _resolve_user_id(request, client, user_id)
    _persist_user_id(request, resolved)
    libraries = await client.libraries(resolved)
    media_index.upsert_libraries(db, libraries)
    return ok([_dto(row) for row in media_index.library_selection_rows(db)])


@router.put("/selection", response_model=Envelope[list[LibraryItem]])
async def save_selection(
    body: SelectionBody,
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[list[LibraryItem]]:
    """保存多库勾选: 仅调整本地已存在媒体库的 selected 状态。"""
    known = {row.jellyfin_id for row in media_index.library_selection_rows(db)}
    media_index.apply_selection(db, body.selected, known_ids=known)
    return ok([_dto(row) for row in media_index.library_selection_rows(db)])
