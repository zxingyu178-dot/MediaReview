"""缓存与雪碧图接口。

- GET  /cache/statistics        缓存概览(DB/缩略图/雪碧图/用量)
- GET  /cache/tasks/{task_id}   后台任务状态(前端轮询)
- GET  /cache/sprites/{media_id}         雪碧图清单(含状态与 url)
- POST /cache/sprites/{media_id}         触发雪碧图生成(幂等: 已就绪且指纹一致则直返)
- DELETE /cache/sprites/{media_id}       使雪碧图失效(删文件+清单)
- GET  /cache/sprites/{media_id}/file    下载雪碧图文件
"""

from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, Depends, Request
from fastapi.responses import FileResponse
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.api.v1.auth import require_localhost_or_auth
from app.core.errors import MediaNotFoundError, NotFoundError
from app.core.responses import Envelope, ok
from app.db.models import BackgroundTask, SpriteManifest
from app.db.session import get_db
from app.services import media_index, sprite

router = APIRouter(prefix="/cache", tags=["cache"])


def _sprites_dir(request: Request) -> Path:
    return request.app.state.paths.sprites_dir


def _task_view(task: BackgroundTask) -> dict:
    return {
        "task_id": task.task_id,
        "type": task.type,
        "status": task.status,
        "progress": task.progress,
        "media_id": task.media_id,
        "error": task.error,
        "result": task.result,
        "created_at": task.created_at,
        "started_at": task.started_at,
        "finished_at": task.finished_at,
    }


@router.get("/statistics", response_model=Envelope[dict])
async def cache_statistics(
    request: Request,
    _auth=Depends(require_localhost_or_auth),
    db: Session = Depends(get_db),
) -> Envelope[dict]:
    """返回缓存目录的占用概览。敏感接口:本机或已认证设备。"""
    sprite_count = db.scalar(
        select(func.count()).select_from(SpriteManifest).where(SpriteManifest.status == "ready")
    )
    pending_tasks = db.scalar(
        select(func.count())
        .select_from(BackgroundTask)
        .where(BackgroundTask.status.in_(("pending", "running")))
    )
    sprites: Path = request.app.state.paths.sprites_dir
    sprite_bytes = sum(f.stat().st_size for f in sprites.glob("*") if f.is_file())
    max_gb = request.app.state.settings.storage.cache_max_gb
    return ok(
        {
            "sprite_count": int(sprite_count or 0),
            "sprite_bytes": int(sprite_bytes),
            "pending_tasks": int(pending_tasks or 0),
            "max_gb": max_gb,
            "used_gb": round(sprite_bytes / (1024**3), 3),
        }
    )


@router.get("/tasks/{task_id}", response_model=Envelope[dict])
async def get_task(
    task_id: str, _auth=Depends(require_localhost_or_auth), db: Session = Depends(get_db)
) -> Envelope[dict]:
    task = db.get(BackgroundTask, task_id)
    if task is None:
        raise NotFoundError(message="任务不存在")
    return ok(_task_view(task))


@router.get("/sprites/{media_id}", response_model=Envelope[dict])
async def get_sprite_manifest(
    media_id: str, _auth=Depends(require_localhost_or_auth), db: Session = Depends(get_db)
) -> Envelope[dict]:
    manifest = sprite.get_manifest(db, media_id)
    if manifest is None:
        raise NotFoundError(message="该媒体尚无雪碧图,请先生成")
    return ok(sprite.manifest_view(manifest))


@router.post("/sprites/{media_id}", response_model=Envelope[dict])
async def ensure_sprite(
    media_id: str,
    request: Request,
    _auth=Depends(require_localhost_or_auth),
    db: Session = Depends(get_db),
) -> Envelope[dict]:
    """确保媒体雪碧图可用: 已就绪则直返;否则编排后台任务。"""
    media = media_index.get_cached_media(db, media_id)
    if media is None:
        raise MediaNotFoundError()
    manifest = sprite.get_manifest(db, media_id)
    if (
        manifest is not None
        and manifest.status == "ready"
        and manifest.fingerprint == media.fingerprint
    ):
        return ok(sprite.manifest_view(manifest))
    task = sprite.schedule_sprite(db, media_id, media.fingerprint)
    db.commit()
    return ok({"task_id": task.task_id, "status": task.status})


@router.delete("/sprites/{media_id}", response_model=Envelope[dict])
async def invalidate_sprite(
    media_id: str,
    request: Request,
    _auth=Depends(require_localhost_or_auth),
    db: Session = Depends(get_db),
) -> Envelope[dict]:
    removed = sprite.invalidate_manifest(db, media_id, _sprites_dir(request))
    db.commit()
    return ok({"media_id": media_id, "removed": removed})


@router.get("/sprites/{media_id}/file")
async def sprite_file(
    media_id: str,
    request: Request,
    _auth=Depends(require_localhost_or_auth),
    db: Session = Depends(get_db),
) -> FileResponse:
    manifest = sprite.get_manifest(db, media_id)
    if manifest is None or manifest.status != "ready" or not manifest.sprite_file:
        raise NotFoundError(message="该媒体的雪碧图尚未生成")
    path = _sprites_dir(request) / manifest.sprite_file
    if not path.is_file():
        raise NotFoundError(message="雪碧图文件缺失,请重新生成")
    return FileResponse(path, media_type="image/jpeg")


__all__ = ["router"]
