"""后台任务引擎(TaskManager)。

职责:
- 轮询 background_task 表中 pending 的任务,交给已注册的处理器执行
- 处理器在独立线程中运行,避免阻塞 HTTP 事件循环与 SQLite 写锁
- 统一维护任务 状态/进度/开始/结束时间,失败时记录 error

处理器注册: `register(task_type, handler)`。
handler 签名: `def handler(session: Session, task: BackgroundTask) -> None`
(在 to_thread 的线程内调用,任务自行 flush/commit)。
"""

from __future__ import annotations

import asyncio
from collections.abc import Callable
from typing import Any

import sqlalchemy as sa
from sqlalchemy.orm import Session

from app.core.logging import get_logger
from app.db.models import BackgroundTask, MediaRefreshTarget, MediaSyncState, utc_now
from app.db.session import Database

logger = get_logger("tasks")


class TaskManager:
    def __init__(self, database: Database, *, poll_interval: float = 1.0) -> None:
        self.database = database
        self.poll_interval = max(0.1, poll_interval)
        self._handlers: dict[str, Callable[..., None]] = {}
        self._running = False
        self._worker: asyncio.Task[Any] | None = None

    def register(self, task_type: str, handler: Callable[..., None]) -> None:
        self._handlers[task_type] = handler

    async def start(self) -> None:
        """启动后台工作循环(幂等)。"""
        if self._running:
            return
        self._running = True
        self._worker = asyncio.create_task(self._loop(), name="task-manager")

    async def stop(self) -> None:
        """停止工作循环。正在执行的任务执行完后自然结束。"""
        self._running = False
        if self._worker is not None:
            self._worker.cancel()
            try:
                await self._worker
            except asyncio.CancelledError:
                pass
            self._worker = None

    async def _loop(self) -> None:
        while self._running:
            await self._tick()
            await asyncio.sleep(self.poll_interval)

    async def _tick(self) -> None:
        candidate = self._claim_next()
        if candidate is None:
            return
        task_id, task_type, *rest = candidate
        handler = self._handlers.get(task_type)
        if handler is None:
            self._fail_orphan(task_id, f"未注册的任务类型: {task_type}")
            return
        logger.info("执行后台任务 %s (type=%s)", task_id, task_type)
        await asyncio.to_thread(handler, self.database, task_id)

    # -- 数据库操作(独立短会话) --

    def _claim_next(self) -> tuple[str, str, str] | None:
        """原子地取一个 pending 任务并标记为 running,防止多实例重复处理。"""
        with self.database.session() as session:
            candidate = (
                sa.select(BackgroundTask.task_id)
                .where(BackgroundTask.status == "pending")
                .order_by(BackgroundTask.created_at.asc(), BackgroundTask.task_id.asc())
                .limit(1)
                .scalar_subquery()
            )
            claimed = session.execute(
                sa.update(BackgroundTask)
                .where(
                    BackgroundTask.task_id == candidate,
                    BackgroundTask.status == "pending",
                )
                .values(status="running", started_at=utc_now())
                .returning(BackgroundTask.task_id, BackgroundTask.type, BackgroundTask.status)
            ).first()
            if claimed is None:
                session.rollback()
                return None
            session.commit()
            return (claimed.task_id, claimed.type, claimed.status)

    def _fail_orphan(self, task_id: str, error: str) -> None:
        with self.database.session() as session:
            row = session.get(BackgroundTask, task_id)
            if row is None:
                return
            row.status = "failed"
            row.error = error
            row.finished_at = utc_now()
            session.execute(
                sa.delete(MediaRefreshTarget).where(MediaRefreshTarget.task_id == task_id)
            )
            session.commit()


def safe_task_view(task: BackgroundTask) -> dict:
    """返回不含 params/result/raw exception 的通用任务视图。"""
    safe_error = None
    if task.status == "failed":
        safe_error = (
            "媒体同步失败，请稍后重试"
            if task.type == "media_refresh"
            else "后台任务执行失败，请稍后重试"
        )
    return {
        "task_id": task.task_id,
        "type": task.type,
        "status": task.status,
        "progress": task.progress,
        "media_id": task.media_id if task.type != "media_refresh" else None,
        "error": safe_error,
        "created_at": task.created_at,
        "started_at": task.started_at,
        "finished_at": task.finished_at,
    }


def list_task_rows(
    session: Session,
    *,
    task_type: str | None = None,
    status: str | None = None,
    limit: int = 100,
) -> list[BackgroundTask]:
    statement = sa.select(BackgroundTask)
    if task_type:
        statement = statement.where(BackgroundTask.type == task_type)
    if status:
        statement = statement.where(BackgroundTask.status == status)
    statement = statement.order_by(BackgroundTask.created_at.desc()).limit(limit)
    return list(session.scalars(statement).all())


def cancel_task(session: Session, task: BackgroundTask) -> BackgroundTask:
    """幂等取消 pending/running；运行中处理器在分页边界观察 cancelled。"""
    won = session.execute(
        sa.update(BackgroundTask)
        .where(
            BackgroundTask.task_id == task.task_id,
            BackgroundTask.status.in_(("pending", "running")),
        )
        .values(status="cancelled", finished_at=utc_now())
        .returning(BackgroundTask.task_id)
        .execution_options(synchronize_session=False)
    ).first()
    if won is not None:
        session.execute(
            sa.update(MediaSyncState)
            .where(
                MediaSyncState.task_id == task.task_id,
                MediaSyncState.state.in_(("pending", "running")),
            )
            .values(state="cancelled", last_error=None)
        )
        session.execute(
            sa.delete(MediaRefreshTarget).where(MediaRefreshTarget.task_id == task.task_id)
        )
    session.flush()
    session.refresh(task)
    return task


__all__ = ["TaskManager", "cancel_task", "list_task_rows", "safe_task_view"]
