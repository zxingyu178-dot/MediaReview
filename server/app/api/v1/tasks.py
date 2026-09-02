"""通用后台任务查询与协作取消接口。"""

from __future__ import annotations

from typing import Literal

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.api.v1.auth import require_auth
from app.core.errors import NotFoundError
from app.core.responses import Envelope, ok
from app.db.models import BackgroundTask
from app.db.session import get_db
from app.services import tasks as task_service

router = APIRouter(prefix="/tasks", tags=["tasks"])
TaskStatus = Literal["pending", "running", "succeeded", "failed", "cancelled"]


@router.get("", response_model=Envelope[list[dict]])
async def list_tasks(
    task_type: str | None = Query(default=None, alias="type", max_length=32),
    status: TaskStatus | None = Query(default=None),
    limit: int = Query(default=100, ge=1, le=500),
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[list[dict]]:
    rows = task_service.list_task_rows(
        db,
        task_type=task_type,
        status=status,
        limit=limit,
    )
    return ok([task_service.safe_task_view(row) for row in rows])


@router.get("/{task_id}", response_model=Envelope[dict])
async def get_task(
    task_id: str,
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[dict]:
    task = db.get(BackgroundTask, task_id)
    if task is None:
        raise NotFoundError(message="任务不存在")
    return ok(task_service.safe_task_view(task))


@router.post("/{task_id}/cancel", response_model=Envelope[dict])
async def cancel_task(
    task_id: str,
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[dict]:
    task = db.get(BackgroundTask, task_id)
    if task is None:
        raise NotFoundError(message="任务不存在")
    task_service.cancel_task(db, task)
    return ok(task_service.safe_task_view(task))


@router.post("/{task_id}/pause", response_model=Envelope[dict])
async def pause_task(
    task_id: str,
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[dict]:
    task = db.get(BackgroundTask, task_id)
    if task is None:
        raise NotFoundError(message="任务不存在")
    task_service.pause_task(db, task)
    return ok(task_service.safe_task_view(task))


@router.post("/{task_id}/resume", response_model=Envelope[dict])
async def resume_task(
    task_id: str,
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[dict]:
    task = db.get(BackgroundTask, task_id)
    if task is None:
        raise NotFoundError(message="任务不存在")
    task_service.resume_task(db, task)
    return ok(task_service.safe_task_view(task))


__all__ = ["router"]
