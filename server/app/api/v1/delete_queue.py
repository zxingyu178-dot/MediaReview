"""待删除队列接口(两阶段删除)。

- GET    /delete-queue               待删除列表(含媒体摘要)
- POST   /delete-queue/{media_id}    加入待删除(未删,可撤销)
- DELETE /delete-queue/{media_id}    从待删除撤销(仅 pending)
- POST   /delete-queue/commit        确认并真实删除全部 pending(最终一步)
"""

from __future__ import annotations

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.api.v1.auth import require_auth
from app.api.v1.media import _summary_from_row
from app.core.errors import ConflictError, MediaNotFoundError
from app.core.responses import Envelope, ok
from app.db.session import get_db
from app.services import delete_queue, media_index

router = APIRouter(prefix="/delete-queue", tags=["delete-queue"])


class CommitResult(BaseModel):
    outcome: dict[str, str]


@router.get("", response_model=Envelope[list[dict]])
async def list_queue(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[list[dict]]:
    result: list[dict] = []
    for row in delete_queue.list_queue(db):
        media = media_index.get_cached_media(db, row.media_id)
        result.append(
            {
                "media_id": row.media_id,
                "status": row.status,
                "size_bytes": row.size_bytes,
                "added_at": row.added_at,
                "media": _summary_from_row(media) if media else None,
            }
        )
    return ok(result)


@router.post("/commit", response_model=Envelope[CommitResult])
async def commit(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[CommitResult]:
    outcome = delete_queue.commit_all(db)
    db.commit()
    return ok(CommitResult(outcome=outcome))


@router.post("/{media_id}", response_model=Envelope[dict])
async def enqueue(
    media_id: str, _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[dict]:
    media = media_index.get_cached_media(db, media_id)
    if media is None:
        raise MediaNotFoundError()
    created = delete_queue.enqueue(db, media_id, size_bytes=media.size_bytes)
    if not created:
        raise ConflictError(message="该媒体已在待删除队列中")
    db.commit()
    return ok({"media_id": media_id, "queued": True})


@router.delete("/{media_id}", response_model=Envelope[dict])
async def dequeue(
    media_id: str, _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[dict]:
    removed = delete_queue.dequeue(db, media_id)
    if not removed:
        raise ConflictError(message="该媒体不在可撤销的待删除队列中")
    db.commit()
    return ok({"media_id": media_id, "queued": False})


__all__ = ["router"]
