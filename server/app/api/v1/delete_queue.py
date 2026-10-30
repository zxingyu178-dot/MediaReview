"""待删除队列接口(两阶段删除 + 一次性 nonce)。

- GET    /delete-queue                      待删除列表(含媒体摘要)
- POST   /delete-queue/{media_id}           加入待删除(未删,可撤销)
- DELETE /delete-queue/{media_id}           从待删除撤销(仅 pending)
- POST   /delete-queue/commit/prepare       生成一次性删除确认 nonce(绑定队列快照)
- POST   /delete-queue/commit               携带 nonce 确认并真实删除(最终一步)
"""

from __future__ import annotations

from datetime import datetime
import json

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.api.v1.auth import require_auth
from app.api.v1.media import (
    _summary_from_row,
    media_original_url,
    media_thumbnail_url,
    row_source_version,
)
from app.core.errors import ConflictError, MediaNotFoundError, NotFoundError
from app.core.responses import Envelope, ok
from app.db.session import get_db
from app.db.models import DeleteCommitNonce
from app.services import delete_queue, media_index

router = APIRouter(prefix="/delete-queue", tags=["delete-queue"])


class DeleteAlreadyProcessedError(ConflictError):
    code = "DELETE_ALREADY_PROCESSED"
    default_message = "该删除请求已处理，请刷新队列核对结果，勿重复提交"


class CommitResult(BaseModel):
    outcome: dict[str, str]
    deleted_count: int = 0
    deleted_bytes: int = 0
    missing_count: int = 0
    failed_count: int = 0


class QueueSummaryView(BaseModel):
    """待删除摘要(Stage 8C §11): 只统计 pending,供 Organize 首页展示,不返回列表。"""

    count: int
    total_bytes: int
    unknown_size_count: int = 0


class CommitPrepView(BaseModel):
    nonce: str
    expires_at: datetime
    count: int
    total_bytes: int
    media_ids: list[str]
    unknown_size_count: int = 0


class CommitBody(BaseModel):
    nonce: str = Field(min_length=1, max_length=64)


@router.get("", response_model=Envelope[list[dict]])
def list_queue(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[list[dict]]:
    delete_queue.recover_interrupted_deletes(db)
    result: list[dict] = []
    for row in delete_queue.list_queue(db):
        media = media_index.get_cached_media(db, row.media_id)
        result.append(
            {
                "media_id": row.media_id,
                "status": row.status,
                "size_bytes": row.size_bytes,
                "added_at": row.added_at,
                # Stage 8C.1 §23: failed 项下发失败原因(guard 代码,如 file_size_changed /
                # library_not_allowed / stat_failed:*),客户端映射为简短中文,不展示原始堆栈。
                "error": row.error,
                # Stage 8C §16: 队列项封面与 /media 缓存版本语义一致,
                # 客户端不得为了显示封面再逐条请求媒体详情(N+1)。
                "media": (
                    _summary_from_row(
                        media,
                        cover_url=media_thumbnail_url(media.media_id, row_source_version(media)),
                        original_url=media_original_url(media),
                    )
                    if media
                    else None
                ),
            }
        )
    return ok(result)


@router.get("/summary", response_model=Envelope[QueueSummaryView])
def queue_summary(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[QueueSummaryView]:
    """待删除队列摘要: 单条 SQL 聚合 pending 的 count / total_bytes。"""
    count, total_bytes, unknown = delete_queue.pending_summary(db, include_unknown=True)
    return ok(QueueSummaryView(count=count, total_bytes=total_bytes, unknown_size_count=unknown))


@router.post("/commit/prepare", response_model=Envelope[CommitPrepView])
def prepare_commit(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[CommitPrepView]:
    delete_queue.recover_interrupted_deletes(db)
    prep = delete_queue.prepare_commit(db, owner_device_id=_device_identity(_auth))
    db.commit()
    return ok(
        CommitPrepView(
            nonce=prep.nonce,
            expires_at=prep.expires_at,
            count=prep.count,
            total_bytes=prep.total_bytes,
            media_ids=prep.media_ids,
            unknown_size_count=prep.unknown_size_count,
        )
    )


@router.post("/commit", response_model=Envelope[CommitResult])
def commit(
    body: CommitBody,
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[CommitResult]:
    existing = db.get(DeleteCommitNonce, body.nonce)
    if existing is not None and existing.owner_device_id is not None:
        _require_receipt_owner(existing, _auth)
    try:
        outcome = delete_queue.commit_with_nonce(db, body.nonce)
    except delete_queue.NonceMissingError:
        raise NotFoundError(message="删除确认不存在或已失效,请重新发起") from None
    except delete_queue.NonceReusedError:
        raise DeleteAlreadyProcessedError() from None
    except delete_queue.NonceExpiredError:
        raise ConflictError(message="该删除确认已过期,请重新发起") from None
    db.commit()
    if db.get(DeleteCommitNonce, body.nonce).receipt_state == "unknown":
        raise ConflictError(message="部分删除结果未确认，请核对原批次；不要重新提交")
    return ok(
        CommitResult(
            outcome=outcome,
            deleted_count=outcome.deleted_count,
            deleted_bytes=outcome.deleted_bytes,
            missing_count=outcome.missing_count,
            failed_count=outcome.failed_count,
        )
    )


def _device_identity(auth) -> str:
    return auth.device_id if auth is not None else "__unpaired_development__"


def _require_receipt_owner(row, auth) -> None:
    if row.owner_device_id != _device_identity(auth):
        raise NotFoundError(message="删除批次不存在或不可访问")


@router.get("/commit/{nonce}/result", response_model=Envelope[dict])
def deletion_result(nonce: str, _auth=Depends(require_auth), db: Session = Depends(get_db)):
    row = db.get(DeleteCommitNonce, nonce)
    if row is None:
        raise NotFoundError(message="删除批次不存在或不可访问")
    _require_receipt_owner(row, _auth)
    state = row.receipt_state or "unknown"
    payload = json.loads(row.result_json) if row.result_json else None
    return ok({
        "state": state,
        "result": payload if state == "completed" else None,
        "confirmed_partial_result": payload if state != "completed" else None,
    })


@router.post("/{media_id}", response_model=Envelope[dict])
def enqueue(
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
def dequeue(
    media_id: str, _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[dict]:
    removed = delete_queue.dequeue(db, media_id)
    if not removed:
        raise ConflictError(message="该媒体不在可撤销的待删除队列中")
    db.commit()
    return ok({"media_id": media_id, "queued": False})


__all__ = ["router"]
