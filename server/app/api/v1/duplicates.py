"""重复检测接口(Task D: 持久化分组 + 后台扫描任务,只读不自动删除)。

- POST /duplicates/scan            编排后台重复扫描(进度/暂停/取消/恢复)
- GET  /duplicates/status          最近一次扫描任务状态
- GET  /duplicates                 最近扫描的持久化分组(exact 优先)
- GET  /duplicates/exact|high|candidates|similar  按类型筛选
- POST /duplicates/{group_id}/keep 记录双栏对比页的人工"保留"选择

首次访问若媒体缺少哈希,会自动编排哈希后台任务(hash I/O 不阻塞本请求)。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.api.v1.auth import require_auth
from app.core.errors import MediaNotFoundError
from app.core.responses import Envelope, ok
from app.db.session import get_db
from app.services import duplicate_scanner, hash_tasks
from app.services.tasks import safe_task_view

router = APIRouter(prefix="/duplicates", tags=["duplicates"])


class DuplicateMemberView(BaseModel):
    media_id: str
    name: str
    keep: bool = False


class DuplicateGroupView(BaseModel):
    group_id: str
    type: str  # exact | high | candidate | similar
    count: int
    media_ids: list[str]
    names: list[str]
    size_bytes: int
    duration_ms: int | None = None
    detail: str = ""
    members: list[DuplicateMemberView] = []


class KeepBody(BaseModel):
    media_id: str = Field(min_length=1)
    keep: bool


def _view(group: duplicate_scanner.DuplicateGroup) -> DuplicateGroupView:
    return DuplicateGroupView(
        group_id=group.group_id,
        type=group.type,
        count=group.count,
        media_ids=group.media_ids,
        names=group.names,
        size_bytes=group.size_bytes,
        duration_ms=group.duration_ms,
        detail=group.detail,
        members=[
            DuplicateMemberView(media_id=mid, name=name, keep=group.keep.get(mid, False))
            for mid, name in zip(group.media_ids, group.names, strict=False)
        ],
    )


def _ensure_hash(db: Session) -> None:
    """若存在待哈希媒体则编排后台哈希任务(幂等),并在当前事务提交。"""
    if duplicate_scanner.has_pending_hashes(db):
        hash_tasks.schedule_duplicate_hash(db)
        db.commit()


@router.post("/scan", response_model=Envelope[dict])
def scan(_auth=Depends(require_auth), db: Session = Depends(get_db)) -> Envelope[dict]:
    _ensure_hash(db)
    task = duplicate_scanner.schedule_duplicate_scan(db)
    db.commit()
    return ok(safe_task_view(task))


@router.get("/status", response_model=Envelope[dict])
def status(_auth=Depends(require_auth), db: Session = Depends(get_db)) -> Envelope[dict]:
    task = duplicate_scanner.latest_duplicate_scan(db)
    return ok(safe_task_view(task) if task else {"task_id": None})


@router.get("", response_model=Envelope[list[DuplicateGroupView]])
def persisted_all(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[list[DuplicateGroupView]]:
    _ensure_hash(db)
    return ok([_view(g) for g in duplicate_scanner.persisted_groups(db)])


@router.get("/exact", response_model=Envelope[list[DuplicateGroupView]])
def persisted_exact(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[list[DuplicateGroupView]]:
    _ensure_hash(db)
    return ok([_view(g) for g in duplicate_scanner.persisted_groups(db, "exact")])


@router.get("/high", response_model=Envelope[list[DuplicateGroupView]])
def persisted_high(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[list[DuplicateGroupView]]:
    return ok([_view(g) for g in duplicate_scanner.persisted_groups(db, "high")])


@router.get("/candidates", response_model=Envelope[list[DuplicateGroupView]])
def persisted_candidates(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[list[DuplicateGroupView]]:
    _ensure_hash(db)
    return ok([_view(g) for g in duplicate_scanner.persisted_groups(db, "candidate")])


@router.get("/similar", response_model=Envelope[list[DuplicateGroupView]])
def persisted_similar(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[list[DuplicateGroupView]]:
    return ok([_view(g) for g in duplicate_scanner.persisted_groups(db, "similar")])


@router.post("/{group_id}/keep", response_model=Envelope[dict])
def keep(
    group_id: str,
    body: KeepBody,
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[dict]:
    changed = duplicate_scanner.set_keep(db, group_id, body.media_id, body.keep)
    if not changed:
        raise MediaNotFoundError(message="重复分组或成员不存在")
    db.commit()
    return ok({"group_id": group_id, "media_id": body.media_id, "keep": body.keep})


__all__ = ["router"]
