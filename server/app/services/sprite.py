"""雪碧图服务: 清单管理 + 后台生成处理器。

- session 函数供 API 读取清单与编排任务
- generate_sprite_handler 由 TaskManager 在独立线程执行,产出 sprite 文件与 manifest
- MediaExecutor 可注入替身,测试无需 ffmpeg
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import Callable
from pathlib import Path

import sqlalchemy as sa
from sqlalchemy.orm import Session

from app.db.models import BackgroundTask, SpriteManifest, utc_now
from app.db.session import Database
from app.media.ffmpeg import MediaExecutor, build_sprite_plan
from app.services import media_index

TASK_TYPE_SPRITE = "sprite"

# 雪碧图输出固定尺寸区;文件名用 media_id 保证稳定、可缓存失效
SPRITE_FILENAME = "sprite.jpg"


def sprite_path(sprites_dir: Path, media_id: str) -> Path:
    return sprites_dir / f"{media_id}.jpg"


def get_manifest(session: Session, media_id: str) -> SpriteManifest | None:
    return session.get(SpriteManifest, media_id)


def _sprite_url(media_id: str) -> str:
    return f"/api/v1/cache/sprites/{media_id}/file"


def manifest_view(manifest: SpriteManifest) -> dict:
    return {
        "media_id": manifest.media_id,
        "status": manifest.status,
        "columns": manifest.columns,
        "rows": manifest.rows,
        "count": manifest.count,
        "tile_width": manifest.tile_width,
        "tile_height": manifest.tile_height,
        "interval_ms": manifest.interval_ms,
        "total_duration_ms": manifest.total_duration_ms,
        "video_width": manifest.video_width,
        "video_height": manifest.video_height,
        "url": None if manifest.status != "ready" else _sprite_url(manifest.media_id),
        "error": manifest.error,
        "updated_at": manifest.updated_at,
    }


def schedule_sprite(session: Session, media_id: str, fingerprint: str) -> BackgroundTask:
    """为媒体编排雪碧图生成任务。

    若已存在 pending/running 的同媒体任务则直接返回,避免重复排队。
    """
    existing = (
        session.query(BackgroundTask)
        .filter(
            BackgroundTask.type == TASK_TYPE_SPRITE,
            BackgroundTask.media_id == media_id,
            BackgroundTask.status.in_(("pending", "running")),
        )
        .first()
    )
    if existing is not None:
        return existing
    task = BackgroundTask(
        task_id=utc_now().strftime("%Y%m%d%H%M%S%f") + media_id[-6:],
        type=TASK_TYPE_SPRITE,
        status="pending",
        params=json.dumps({"media_id": media_id, "fingerprint": fingerprint}),
        progress=0,
        media_id=media_id,
    )
    session.add(task)
    session.flush()
    return task


def make_sprite_handler(
    executor: MediaExecutor, sprites_dir: Path
) -> Callable[[Database, str], None]:
    """构造可注册到 TaskManager 的雪碧图生成处理器。"""

    def _handler(database: Database, task_id: str) -> None:
        _execute_sprite_generation(database, task_id, executor, sprites_dir)

    return _handler


def _execute_sprite_generation(
    database: Database, task_id: str, executor: MediaExecutor, sprites_dir: Path
) -> None:
    with database.session() as session:
        task = session.get(BackgroundTask, task_id)
        if task is None:
            return
        params = json.loads(task.params or "{}")
        media = media_index.get_cached_media(session, params.get("media_id", ""))
        if media is None:
            task.status = "failed"
            task.error = "媒体不存在或尚未建立索引"
            task.finished_at = utc_now()
            session.commit()
            return
        media_id = media.media_id
        media_path = media.media_path
        # 指纹不匹配(文件已变)时先清理陈旧文件
        expected_fp = params.get("fingerprint")
        if expected_fp and media.fingerprint != expected_fp:
            _drop_sprite_file(session, media_id, sprites_dir)
            task.status = "failed"
            task.error = "媒体已变更,请重新建立索引后再生成"
            task.finished_at = utc_now()
            session.commit()
            return
        task.progress = 20
        session.commit()

    # 探测时长(在会话外,避免长 I/O 占用写锁)
    duration_ms = None
    if media_path:
        try:
            duration_ms = asyncio_run(executor.probe_duration(media_path))
        except Exception:  # noqa: BLE001 - 探测失败按 0 处理
            duration_ms = None

    # 生成前检查协作取消:已被取消/失败的任务不再继续
    with database.session() as session:
        task = session.get(BackgroundTask, task_id)
        if task is None or task.status in ("cancelled", "failed"):
            return
        task.progress = 40
        session.commit()

    plan = build_sprite_plan(
        duration_ms or 0,
        video_width=media.width,
        video_height=media.height,
    )

    out_file = sprite_path(sprites_dir, media_id)
    try:
        asyncio_run(executor.make_sprite(media_path, out_file, plan))
    except Exception as exc:  # noqa: BLE001
        with database.session() as session:
            task = session.get(BackgroundTask, task_id)
            if task is None:
                return
            task.status = "failed"
            task.error = str(exc)
            task.progress = 0
            task.finished_at = utc_now()
            session.commit()
        _drop_manifest(database, media_id)
        return

    with database.session() as session:
        task = session.get(BackgroundTask, task_id)
        if task is None:
            return
        # 生成期间被协作取消:丢弃产物,保持 cancelled/failed 状态,不复活任务
        if task.status in ("cancelled", "failed"):
            _drop_sprite_file(session, media_id, sprites_dir)
            manifest = session.get(SpriteManifest, media_id)
            if manifest is not None:
                session.delete(manifest)
            session.commit()
            return
        manifest = session.get(SpriteManifest, media_id)
        if manifest is None:
            manifest = SpriteManifest(media_id=media_id)
            session.add(manifest)
        manifest.status = "ready"
        manifest.columns = plan.columns
        manifest.rows = plan.rows
        manifest.count = plan.count
        manifest.tile_width = plan.tile_width
        manifest.tile_height = plan.tile_height
        manifest.interval_ms = plan.interval_ms
        manifest.total_duration_ms = plan.total_duration_ms
        manifest.sprite_file = SPRITE_FILENAME
        manifest.fingerprint = media.fingerprint if media else ""
        manifest.video_width = media.width or 0
        manifest.video_height = media.height or 0
        manifest.error = None
        manifest.updated_at = utc_now()
        # CAS 抢占终态:仅 pending/running 可转为 succeeded;
        # 读取状态与提交之间落库的取消不得被覆盖(TOCTOU)
        won = session.execute(
            sa.update(BackgroundTask)
            .where(
                BackgroundTask.task_id == task_id,
                BackgroundTask.status.in_(("pending", "running")),
            )
            .values(
                status="succeeded",
                progress=100,
                result=json.dumps({"media_id": media_id}),
                finished_at=utc_now(),
            )
            .returning(BackgroundTask.task_id)
            .execution_options(synchronize_session=False)
        ).first()
        if won is None:
            session.rollback()
            _drop_sprite_file(session, media_id, sprites_dir)
            stale = session.get(SpriteManifest, media_id)
            if stale is not None:
                session.delete(stale)
            session.commit()
            return
        session.commit()


def invalidate_manifest(session: Session, media_id: str, sprites_dir: Path) -> bool:
    """使雪碧图失效: 删除索引记录与文件。返回是否存在过。"""
    _drop_sprite_file(session, media_id, sprites_dir)
    manifest = session.get(SpriteManifest, media_id)
    if manifest is None:
        return False
    session.delete(manifest)
    session.flush()
    return True


def _drop_sprite_file(session: Session, media_id: str, sprites_dir: Path) -> None:
    path = sprite_path(sprites_dir, media_id)
    try:
        path.unlink(missing_ok=True)
    except OSError:
        pass


def _drop_manifest(database: Database, media_id: str) -> None:
    with database.session() as session:
        manifest = session.get(SpriteManifest, media_id)
        if manifest is not None:
            session.delete(manifest)
        session.commit()


def asyncio_run(coro: asyncio.Future) -> object:
    """在线程内运行协程(处理器运行在 to_thread 的线程,无事件循环)。"""
    import asyncio as _asyncio

    loop = _asyncio.new_event_loop()
    try:
        return loop.run_until_complete(coro)
    finally:
        loop.close()


__all__ = [
    "SPRITE_FILENAME",
    "TASK_TYPE_SPRITE",
    "get_manifest",
    "invalidate_manifest",
    "make_sprite_handler",
    "manifest_view",
    "schedule_sprite",
    "sprite_path",
]
