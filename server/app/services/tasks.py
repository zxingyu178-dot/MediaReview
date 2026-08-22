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

from app.core.logging import get_logger
from app.db.models import BackgroundTask, utc_now
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
            row = (
                session.query(BackgroundTask)
                .filter(BackgroundTask.status == "pending")
                .order_by(BackgroundTask.created_at.asc())
                .first()
            )
            if row is None:
                return None
            row.status = "running"
            row.started_at = utc_now()
            session.commit()
            return (row.task_id, row.type, "running")

    def _fail_orphan(self, task_id: str, error: str) -> None:
        with self.database.session() as session:
            row = session.get(BackgroundTask, task_id)
            if row is None:
                return
            row.status = "failed"
            row.error = error
            row.finished_at = utc_now()
            session.commit()


__all__ = ["TaskManager"]
