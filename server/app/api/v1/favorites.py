"""收藏接口。

- GET /favorites                收藏列表(按收藏时间倒序,含媒体摘要)
- POST /favorites/{media_id}    收藏媒体(幂等)
- DELETE /favorites/{media_id}  取消收藏
"""

from __future__ import annotations

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.api.v1.auth import require_auth
from app.api.v1.media import MediaSummary, _summary_from_row
from app.core.errors import MediaNotFoundError
from app.core.perf import log_perf, perf_now
from app.core.responses import Envelope, ok
from app.db.session import get_db
from app.services import favorites, media_index

router = APIRouter(prefix="/favorites", tags=["favorites"])


class FavoriteItem(BaseModel):
    media_id: str
    media: MediaSummary | None = None


@router.get("", response_model=Envelope[list[dict]])
async def list_favorites(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[list[dict]]:
    """收藏列表(阶段 8A.1 起只在用户首次进入收藏页时才请求,不再阻塞首页)。"""
    started = perf_now()
    ids = favorites.list_ids(db)
    favorite_ids = set(ids)
    result: list[dict] = []
    for media_id in ids:
        row = media_index.get_cached_media(db, media_id)
        result.append(
            {
                "media_id": media_id,
                "media": _summary_from_row(
                    row,
                    is_favorite=media_id in favorite_ids,
                )
                if row
                else None,
            }
        )
    log_perf("favorites_list", start=started, count=len(result))
    return ok(result)


@router.post("/{media_id}", response_model=Envelope[dict])
async def add_favorite(
    media_id: str, _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[dict]:
    cached = media_index.get_cached_media(db, media_id)
    if cached is None and not favorites.is_favorite(db, media_id):
        raise MediaNotFoundError()
    created = favorites.add(db, media_id)
    db.commit()
    return ok({"media_id": media_id, "favorited": True, "created": created})


@router.delete("/{media_id}", response_model=Envelope[dict])
async def remove_favorite(
    media_id: str, _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[dict]:
    removed = favorites.remove(db, media_id)
    db.commit()
    return ok({"media_id": media_id, "favorited": False, "removed": removed})


__all__ = ["router"]
