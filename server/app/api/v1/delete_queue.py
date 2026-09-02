"""待删除队列接口(两阶段删除 + 一次性 nonce)。

- GET    /delete-queue                      待删除列表(含媒体摘要)
- POST   /delete-queue/{media_id}           加入待删除(未删,可撤销)
- DELETE /delete-queue/{media_id}           从待删除撤销(仅 pending)
- POST   /delete-queue/commit/prepare       生成一次性删除确认 nonce(绑定队列快照)
- POST   /delete-queue/commit               携带 nonce 确认并真实删除(最终一步)
"""

from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.api.v1.auth import require_auth
from app.api.v1.media import _summary_from_row
from app.core.errors import ConflictError, MediaNotFoundError, NotFoundError
from app.core.responses import Envelope, ok
from app.db.session import get_db
from app.services import delete_queue, media_index

router = APIRouter(prefix="/delete-queue", tags=["delete-queue"])


class CommitResult(BaseModel):
    outcome: dict[str, str]


class CommitPrepView(BaseModel):
    nonce: str
    expires_at: datetime
    count: int
    total_bytes: int
    media_ids: list[str]


class CommitBody(BaseModel):
    nonce: str = Field(min_length=1, max_length=64)


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


@router.post("/commit/prepare", response_model=Envelope[CommitPrepView])
async def prepare_commit(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[CommitPrepView]:
    prep = delete_queue.prepare_commit(db)
    db.commit()
    return ok(
        CommitPrepView(
            nonce=prep.nonce,
            expires_at=prep.expires_at,
            count=prep.count,
            total_bytes=prep.total_bytes,
            media_ids=prep.media_ids,
        )
    )


@router.post("/commit", response_model=Envelope[CommitResult])
async def commit(
    body: CommitBody,
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[CommitResult]:
    try:
        outcome = delete_queue.commit_with_nonce(db, body.nonce)
    except delete_queue.NonceMissingError:
        raise NotFoundError(message="删除确认不存在或已失效,请重新发起") from None
    except delete_queue.NonceReusedError:
        raise ConflictError(message="该删除确认已使用,请重新发起") from None
    except delete_queue.NonceExpiredError:
        raise ConflictError(message="该删除确认已过期,请重新发起") from None
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
